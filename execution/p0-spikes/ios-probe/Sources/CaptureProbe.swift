import AVFoundation

/// 采集与输入路由探针（PRD FR-REC-001/002/004）。
/// 规则：只接受蓝牙耳机输入，路由证据不满足就拒绝，任何情况下不回退手机麦克风；
/// 路由变成非蓝牙立刻关闸并停止，不自动续录。
final class CaptureProbe: @unchecked Sendable {
    enum State: String {
        case idle, routing, recording, finalizing, blocked
    }

    var onStateChange: (@Sendable (State, String) -> Void)?
    /// 会话模式是对照变量：探针要回答“换模式后录音中的耳机命令能否到达”。
    var sessionMode: AVAudioSession.Mode = .default

    private static let bluetoothInputTypes: [AVAudioSession.Port] = [.bluetoothHFP, .bluetoothLE]

    private let session = AVAudioSession.sharedInstance()
    private var engine = AVAudioEngine()
    private var file: AVAudioFile?
    private let lock = NSLock()
    private var current: State = .idle
    private var gateOpen = false
    private var routeEpoch = 0
    private var acceptedFrames: Int64 = 0
    private var discardedFrames: Int64 = 0
    private var firstAcceptedMono: UInt64 = 0
    private var lastAcceptedMono: UInt64 = 0
    private var sampleRate: Double = 0
    private var writeFailures = 0
    private var sessionID = ""
    private var observers: [NSObjectProtocol] = []
    private var volumeObservation: NSKeyValueObservation?

    init() {
        // 输入静音状态变化（iOS 17+）：某些耳机的按键会触发静音切换。只记录，不做动作。
        observers.append(NotificationCenter.default.addObserver(
            forName: AVAudioApplication.inputMuteStateChangeNotification, object: nil, queue: nil
        ) { _ in
            ProbeLogger.shared.log("input_mute_state_changed", ["muted": AVAudioApplication.shared.isInputMuted])
        })
        // 音量类手势是否能作为录音中的停止信号：只记录变化，不做动作。
        volumeObservation = session.observe(\.outputVolume, options: [.old, .new]) { _, change in
            let new = Double(change.newValue ?? -1)
            let old = Double(change.oldValue ?? -1)
            ProbeLogger.shared.log("output_volume_changed", ["value": new, "previous": old, "delta": new - old])
        }
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: nil) { [weak self] note in
            self?.routeChanged(note)
        })
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: nil) { [weak self] note in
            self?.interrupted(note)
        })
        observers.append(center.addObserver(forName: .AVAudioEngineConfigurationChange, object: nil, queue: nil) { [weak self] _ in
            ProbeLogger.shared.log("engine_configuration_change", ["state": self?.state.rawValue ?? "nil"])
            if self?.state == .recording { self?.stop(reason: "engine_configuration_change") }
        })
    }

    var state: State {
        lock.lock()
        defer { lock.unlock() }
        return current
    }

    func requestMicPermission() {
        AVAudioApplication.requestRecordPermission { granted in
            ProbeLogger.shared.log("mic_permission", ["granted": granted])
        }
    }

    // MARK: 命令入口

    func apply(_ command: BusinessCommand, platformCommand: String) {
        let logger = ProbeLogger.shared
        switch (command, state) {
        case (.start, .idle), (.start, .blocked), (.toggle, .idle), (.toggle, .blocked):
            start(trigger: platformCommand)
        case (.stop, .recording), (.stop, .routing), (.toggle, .recording), (.toggle, .routing):
            stop(reason: "command_\(platformCommand)")
        case (.start, .finalizing):
            // 封存期间的明确 START：忽略并记录，不排队（PRD §12.2）。
            logger.log("command_ignored_busy", ["command": command.rawValue, "platform_command": platformCommand])
        case (.toggle, .finalizing):
            logger.log("toggle_discarded_stale", ["platform_command": platformCommand])
        default:
            logger.log("command_noop", ["command": command.rawValue, "state": state.rawValue])
        }
    }

    // MARK: 开始

    func start(trigger: String) {
        let logger = ProbeLogger.shared
        let before = state
        guard before == .idle || before == .blocked else {
            logger.log("capture_start_ignored", ["state": before.rawValue, "trigger": trigger])
            return
        }
        sessionID = UUID().uuidString
        set(.routing)
        logger.log("capture_start_requested", [
            "trigger": trigger,
            "session_id": sessionID,
            "route_before": routeSnapshot(),
        ])

        do {
            try session.setCategory(.playAndRecord, mode: sessionMode, options: [.allowBluetoothHFP])
            try session.setActive(true)
        } catch {
            return block("session_activation_failed", error: error)
        }

        let available = session.availableInputs ?? []
        logger.log("available_inputs", ["inputs": available.map { portInfo($0) }])
        guard let bluetooth = available.first(where: { Self.bluetoothInputTypes.contains($0.portType) }) else {
            return block("no_bluetooth_input_available")
        }
        do {
            try session.setPreferredInput(bluetooth)
        } catch {
            return block("set_preferred_input_failed", error: error)
        }
        // 引擎启动之前先取路由证据：未确认来自蓝牙就不创建任何采集管线。
        guard routeIsBluetoothInput() else {
            return block("route_not_bluetooth_after_preferred_input")
        }

        engine = AVAudioEngine()
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else {
            return block("invalid_input_format")
        }

        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("rec-\(sessionID).caf")
        do {
            file = try AVAudioFile(forWriting: url, settings: format.settings)
        } catch {
            return block("audio_file_create_failed", error: error)
        }

        lock.lock()
        gateOpen = true
        routeEpoch += 1
        acceptedFrames = 0
        discardedFrames = 0
        firstAcceptedMono = 0
        lastAcceptedMono = 0
        writeFailures = 0
        sampleRate = format.sampleRate
        let epoch = routeEpoch
        lock.unlock()

        input.installTap(onBus: 0, bufferSize: 2048, format: format) { [weak self] buffer, _ in
            self?.consume(buffer)
        }
        do {
            try engine.start()
        } catch {
            input.removeTap(onBus: 0)
            return block("engine_start_failed", error: error)
        }

        set(.recording)
        logger.log("capture_started", [
            "session_id": sessionID,
            "route_epoch": epoch,
            "route_verified_before_engine_start": true,
            "sample_rate": format.sampleRate,
            "channels": Int(format.channelCount),
            "route": routeSnapshot(),
        ])
    }

    // MARK: 停止

    func stop(reason: String) {
        DispatchQueue.main.async { self.stopOnMain(reason) }
    }

    private func stopOnMain(_ reason: String) {
        let logger = ProbeLogger.shared
        let before = state
        guard before == .recording || before == .routing else {
            logger.log("capture_stop_ignored", ["state": before.rawValue, "reason": reason])
            return
        }
        set(.finalizing)
        lock.lock()
        gateOpen = false
        lock.unlock()

        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        file = nil
        try? session.setActive(false, options: .notifyOthersOnDeactivation)

        lock.lock()
        let accepted = acceptedFrames
        let discarded = discardedFrames
        let first = firstAcceptedMono
        let last = lastAcceptedMono
        let rate = sampleRate
        let failures = writeFailures
        lock.unlock()

        logger.log("capture_stopped", [
            "session_id": sessionID,
            "reason": reason,
            "accepted_frames": accepted,
            "discarded_frames": discarded,
            "accepted_duration_ms": rate > 0 ? Int(Double(accepted) / rate * 1000) : 0,
            "first_accepted_mono_ns": first,
            "last_accepted_mono_ns": last,
            "write_failures": failures,
            "route_after": routeSnapshot(),
        ])
        set(.idle)
    }

    // MARK: 音频帧

    private func consume(_ buffer: AVAudioPCMBuffer) {
        lock.lock()
        defer { lock.unlock() }
        guard gateOpen else {
            discardedFrames += Int64(buffer.frameLength)
            return
        }
        do {
            try file?.write(from: buffer)
        } catch {
            writeFailures += 1
        }
        acceptedFrames += Int64(buffer.frameLength)
        let now = ProbeLogger.monoNanos()
        if firstAcceptedMono == 0 { firstAcceptedMono = now }
        lastAcceptedMono = now
    }

    // MARK: 通知

    private func routeChanged(_ note: Notification) {
        let logger = ProbeLogger.shared
        let reasonValue = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt) ?? 0
        let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue)
        logger.log("route_change", [
            "reason": reasonValue,
            "reason_name": reason.map { String(describing: $0) } ?? "unknown",
            "state": state.rawValue,
            "route": routeSnapshot(),
        ])
        let st = state
        guard st == .recording || st == .routing else { return }
        if routeIsBluetoothInput() {
            lock.lock()
            routeEpoch += 1
            let epoch = routeEpoch
            lock.unlock()
            logger.log("route_epoch_bumped", ["route_epoch": epoch])
        } else {
            lock.lock()
            gateOpen = false
            lock.unlock()
            logger.log("gate_closed_route_not_bluetooth", ["route": routeSnapshot()])
            stop(reason: "route_lost")
        }
    }

    private func interrupted(_ note: Notification) {
        let typeValue = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt) ?? 0
        let type = AVAudioSession.InterruptionType(rawValue: typeValue)
        ProbeLogger.shared.log("audio_interruption", [
            "type": type.map { String(describing: $0) } ?? "unknown",
            "state": state.rawValue,
        ])
        // 中断开始就停止并封存；中断结束后不自动续录（FR-REC-004）。
        if type == .began, state == .recording { stop(reason: "interruption") }
    }

    // MARK: 辅助

    private func set(_ newState: State) {
        lock.lock()
        current = newState
        let id = sessionID
        lock.unlock()
        onStateChange?(newState, id)
    }

    private func block(_ reason: String, error: Error? = nil) {
        var data: [String: Any] = ["reason": reason, "session_id": sessionID, "route": routeSnapshot()]
        if let ns = error as NSError? {
            data["error_domain"] = ns.domain
            data["error_code"] = ns.code
            data["error"] = ns.localizedDescription
        }
        lock.lock()
        gateOpen = false
        lock.unlock()
        try? session.setActive(false, options: .notifyOthersOnDeactivation)
        set(.blocked)
        ProbeLogger.shared.log("capture_blocked", data)
    }

    private func routeIsBluetoothInput() -> Bool {
        let inputs = session.currentRoute.inputs
        return !inputs.isEmpty && inputs.allSatisfy { Self.bluetoothInputTypes.contains($0.portType) }
    }

    private func portInfo(_ port: AVAudioSessionPortDescription) -> [String: String] {
        // uid 只保留末 6 位，避免日志里出现完整蓝牙地址。
        ["type": port.portType.rawValue, "name": port.portName, "uid_tail": String(port.uid.suffix(6))]
    }

    private func routeSnapshot() -> [String: Any] {
        let route = session.currentRoute
        return [
            "inputs": route.inputs.map { portInfo($0) },
            "outputs": route.outputs.map { portInfo($0) },
            "category": session.category.rawValue,
            "mode": session.mode.rawValue,
            "session_sample_rate": session.sampleRate,
        ]
    }
}
