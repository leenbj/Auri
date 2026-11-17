import AVFoundation

/// 采集与输入路由探针（PRD FR-REC-001/002/004）。
/// 规则：只接受蓝牙耳机输入，路由证据不满足就拒绝，任何情况下不回退手机麦克风；
/// 路由变成非蓝牙立刻关闸并停止，不自动续录。
final class CaptureProbe: @unchecked Sendable {
    enum State: String {
        case idle, routing, recording, finalizing, blocked
    }

    var onStateChange: (@Sendable (State, String) -> Void)?
    var onVolumeUpdate: (@Sendable (Double, VolumeBoundary, VolumeGestureEvaluation?) -> Void)?
    var onVolumeStopTriggered: (@Sendable (String, Double, String, UInt64) -> Void)?
    /// 会话模式是对照变量：探针要回答“换模式后录音中的耳机命令能否到达”。
    var sessionMode: AVAudioSession.Mode = .default
    /// 录音时长硬上限（秒），ADR-0014 保留时长上限兜底；默认 30 分钟。
    var maxDurationSeconds: TimeInterval = 1800

    private let session = AVAudioSession.sharedInstance()
    private var engine = AVAudioEngine()
    private var file: AVAudioFile?
    private let lock = NSLock()
    private var current: State = .idle
    private var gateOpen = false
    private var routeEpoch = 0
    private var generation = 0
    private var acceptedFrames: Int64 = 0
    private var discardedFrames: Int64 = 0
    private var firstAcceptedMono: UInt64 = 0
    private var lastAcceptedMono: UInt64 = 0
    private var recordingStartedMono: UInt64 = 0
    private var sampleRate: Double = 0
    private var writeFailures = 0
    private var sessionID = ""
    private var volumeStopEnabledStorage = true
    private var activeScenarioStorage = "middle"
    private var volumeSequence = 0
    private var volumeDetector = VolumeGestureDetector()
    private var durationLimitWorkItem: DispatchWorkItem?
    private var observers: [NSObjectProtocol] = []
    private var volumeObservation: NSKeyValueObservation?

    init() {
        // 输入静音状态变化（iOS 17+）：某些耳机的按键会触发静音切换。只记录，不做动作。
        observers.append(NotificationCenter.default.addObserver(
            forName: AVAudioApplication.inputMuteStateChangeNotification, object: nil, queue: nil
        ) { _ in
            ProbeLogger.shared.log("input_mute_state_changed", ["muted": AVAudioApplication.shared.isInputMuted])
        })
        // 音量往返停止规则（ADR-0021 提议）：录音中观测 AVAudioSession.outputVolume KVO。
        volumeObservation = session.observe(\.outputVolume, options: [.old, .new]) { [weak self] _, change in
            self?.handleVolumeChange(newValue: change.newValue, oldValue: change.oldValue)
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

    var volumeStopEnabled: Bool {
        get {
            lock.lock()
            defer { lock.unlock() }
            return volumeStopEnabledStorage
        }
        set {
            lock.lock()
            volumeStopEnabledStorage = newValue
            lock.unlock()
        }
    }

    var activeScenarioTag: String {
        get {
            lock.lock()
            defer { lock.unlock() }
            return activeScenarioStorage
        }
        set {
            lock.lock()
            activeScenarioStorage = newValue
            lock.unlock()
        }
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
            let reason: String
            switch platformCommand {
            case "volume_gesture":
                reason = "volume_gesture"
            case "manual":
                reason = "screen_control"
            default:
                reason = "command_\(platformCommand)"
            }
            stop(reason: reason)
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
        lock.lock()
        generation += 1
        let capGeneration = generation
        volumeDetector.reset()
        lock.unlock()

        set(.routing)
        logger.log("capture_start_requested", [
            "trigger": trigger,
            "session_id": sessionID,
            "scenario": activeScenarioTag,
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
        guard let bluetooth = available.first(where: { RouteEvidenceGate.classify(portTypeRaw: $0.portType.rawValue).isBluetooth }) else {
            return block("no_bluetooth_input_available")
        }
        do {
            try session.setPreferredInput(bluetooth)
        } catch {
            return block("set_preferred_input_failed", error: error)
        }
        // 引擎启动之前先取路由证据：未确认来自蓝牙就不创建任何采集管线。
        let entryVerdict = evaluateCurrentRoute()
        guard entryVerdict.isBluetoothOnlyVerified else {
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

        let startedMono = ProbeLogger.monoNanos()
        let initialVolume = Double(session.outputVolume)
        let initialBoundary = volumeDetector.classifyBoundary(volume: initialVolume)

        lock.lock()
        gateOpen = true
        routeEpoch += 1
        acceptedFrames = 0
        discardedFrames = 0
        firstAcceptedMono = 0
        lastAcceptedMono = 0
        recordingStartedMono = startedMono
        writeFailures = 0
        sampleRate = format.sampleRate
        volumeDetector.reset()
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

        scheduleDurationLimit(forGeneration: capGeneration, expectedSessionID: sessionID)
        set(.recording)
        onVolumeUpdate?(initialVolume, initialBoundary, nil)
        logger.log("capture_started", [
            "session_id": sessionID,
            "scenario": activeScenarioTag,
            "route_epoch": epoch,
            "route_verified_before_engine_start": true,
            "observed_route_type": entryVerdict.primaryObservedRouteType.rawValue,
            "initial_volume": initialVolume,
            "initial_boundary": initialBoundary.rawValue,
            "volume_stop_enabled": volumeStopEnabled,
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
        durationLimitWorkItem?.cancel()
        durationLimitWorkItem = nil

        set(.finalizing)
        lock.lock()
        gateOpen = false
        volumeDetector.reset()
        recordingStartedMono = 0
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
            "scenario": activeScenarioTag,
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

    private func scheduleDurationLimit(forGeneration capGeneration: Int, expectedSessionID: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.durationLimitWorkItem?.cancel()
            let item = DispatchWorkItem { [weak self] in
                guard let self else { return }
                self.lock.lock()
                let matches = (self.generation == capGeneration && self.sessionID == expectedSessionID && self.current == .recording)
                self.lock.unlock()
                if matches {
                    self.stop(reason: "duration_limit")
                }
            }
            self.durationLimitWorkItem = item
            DispatchQueue.main.asyncAfter(deadline: .now() + self.maxDurationSeconds, execute: item)
        }
    }

    // MARK: 音量手势

    private func handleVolumeChange(newValue: Float?, oldValue: Float?) {
        let new = Double(newValue ?? -1)
        let old = oldValue.map { Double($0) }
        let now = ProbeLogger.monoNanos()

        lock.lock()
        let isRecording = (current == .recording)
        let started = recordingStartedMono
        let enabled = volumeStopEnabledStorage
        let scenario = activeScenarioStorage
        let activeSessionID = sessionID
        volumeSequence += 1
        let seq = volumeSequence
        let eval = volumeDetector.evaluate(VolumeObservationInput(
            newVolume: new,
            previousVolume: old,
            monoNanos: now,
            isRecording: isRecording,
            recordingStartedMonoNanos: started > 0 ? started : nil,
            sequence: seq
        ))
        lock.unlock()

        var data: [String: Any] = [
            "value": new,
            "previous": old ?? -1,
            "delta": eval.delta,
            "recording": isRecording,
            "boundary": eval.boundary.rawValue,
            "decision": eval.outcome.label,
            "volume_stop_enabled": enabled,
            "scenario": scenario,
            "normalized_event": eval.normalizedEvent.dictionary,
        ]
        if let reason = eval.outcome.reason {
            data["decision_reason"] = reason
        }
        if case let .triggeredStop(first, second, dtNanos, startB, endB) = eval.outcome {
            data["direction_pair"] = "\(first.rawValue)->\(second.rawValue)"
            data["dt_ms"] = Int(dtNanos / 1_000_000)
            data["start_boundary"] = startB.rawValue
            data["end_boundary"] = endB.rawValue
        }

        ProbeLogger.shared.log("output_volume_changed", data)
        onVolumeUpdate?(new, eval.boundary, eval)

        if enabled, case let .triggeredStop(first, second, dtNanos, startB, endB) = eval.outcome {
            let pair = "\(first.rawValue)->\(second.rawValue)"
            ProbeLogger.shared.log("volume_stop_triggered", [
                "session_id": activeSessionID,
                "scenario": scenario,
                "direction_pair": pair,
                "dt_ms": Int(dtNanos / 1_000_000),
                "value": new,
                "start_boundary": startB.rawValue,
                "end_boundary": endB.rawValue,
            ])
            onVolumeStopTriggered?(scenario, new, pair, dtNanos)
            apply(.stop, platformCommand: "volume_gesture")
        }
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
        durationLimitWorkItem?.cancel()
        durationLimitWorkItem = nil
        var data: [String: Any] = ["reason": reason, "session_id": sessionID, "route": routeSnapshot()]
        if let ns = error as NSError? {
            data["error_domain"] = ns.domain
            data["error_code"] = ns.code
            data["error"] = ns.localizedDescription
        }
        lock.lock()
        gateOpen = false
        volumeDetector.reset()
        recordingStartedMono = 0
        lock.unlock()
        try? session.setActive(false, options: .notifyOthersOnDeactivation)
        set(.blocked)
        ProbeLogger.shared.log("capture_blocked", data)
    }

    private func evaluateCurrentRoute() -> RouteEvidenceVerdict {
        let ports = session.currentRoute.inputs.map {
            ObservedAudioPort(portTypeRaw: $0.portType.rawValue, portName: $0.portName, uid: $0.uid)
        }
        return RouteEvidenceGate.evaluate(inputs: ports)
    }

    private func routeIsBluetoothInput() -> Bool {
        evaluateCurrentRoute().isBluetoothOnlyVerified
    }

    private func portInfo(_ port: AVAudioSessionPortDescription) -> [String: String] {
        LogRedactor.redactPort(type: port.portType.rawValue, name: port.portName, uid: port.uid)
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
