import AVFoundation
import SwiftUI
import UIKit

/// 会话模式的界面选项（对照变量）。
enum SessionModeChoice: String, CaseIterable, Identifiable {
    case standard, voiceChat, videoChat, spokenAudio

    var id: String { rawValue }

    var mode: AVAudioSession.Mode {
        switch self {
        case .standard: .default
        case .voiceChat: .voiceChat
        case .videoChat: .videoChat
        case .spokenAudio: .spokenAudio
        }
    }
}

/// 旧式遥控事件通道（UIResponder.remoteControlReceived），与 MPRemoteCommandCenter 是不同的接收路径。
final class RemoteEventViewController: UIViewController {
    override var canBecomeFirstResponder: Bool { true }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        becomeFirstResponder()
    }

    override func remoteControlReceived(with event: UIEvent?) {
        ProbeLogger.shared.log("legacy_remote_control_event", [
            "subtype": event?.subtype.rawValue ?? -1,
            "event_timestamp": event?.timestamp ?? 0,
        ])
    }
}

struct RemoteEventResponder: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> RemoteEventViewController { RemoteEventViewController() }
    func updateUIViewController(_ controller: RemoteEventViewController, context: Context) {}
}

@MainActor
final class ProbeModel: ObservableObject {
    @Published var armed = false {
        didSet {
            if armed {
                remote.arm()
                UIApplication.shared.beginReceivingRemoteControlEvents()
            } else {
                remote.disarm()
                UIApplication.shared.endReceivingRemoteControlEvents()
            }
        }
    }
    @Published var sessionMode: SessionModeChoice = .standard {
        didSet {
            capture.sessionMode = sessionMode.mode
            ProbeLogger.shared.log("session_mode_selected", ["mode": sessionMode.rawValue])
        }
    }
    @Published var nowPlayingMode: RemoteCommandProbe.NowPlayingMode = .off {
        didSet { remote.publishNowPlaying(nowPlayingMode) }
    }
    @Published var mapping: RemoteCommandProbe.Mapping = .semantic {
        didSet { remote.mapping = mapping }
    }
    @Published var captureState = "idle"
    @Published var tail: [String] = []

    let remote = RemoteCommandProbe()
    let capture = CaptureProbe()
    let ble = BleProbe()
    private let lifecycle = LifecycleProbe()

    init() {
        let logger = ProbeLogger.shared
        logger.setAppState(UIApplication.shared.applicationState == .background ? "background" : "launching")
        lifecycle.start()
        logger.onLine = { [weak self] line in
            Task { @MainActor in self?.append(line) }
        }
        remote.onCommand = { [weak self] command, platform in
            DispatchQueue.main.async { self?.capture.apply(command, platformCommand: platform) }
        }
        capture.onStateChange = { [weak self] state, _ in
            Task { @MainActor in self?.captureState = state.rawValue }
        }
        logger.log("app_launch", [
            "os_version": UIDevice.current.systemVersion,
            "model": UIDevice.current.model,
        ])
        capture.requestMicPermission()
    }

    /// 用户做完一个手势后立刻点标记，日志里就有实际操作的精确时间点。
    func mark(_ gesture: String) {
        ProbeLogger.shared.log("user_marker", ["gesture": gesture, "capture_state": captureState])
    }

    private func append(_ line: String) {
        tail.insert(line, at: 0)
        if tail.count > 40 { tail.removeLast() }
    }
}

struct ContentView: View {
    @ObservedObject var model: ProbeModel

    var body: some View {
        NavigationStack {
            Form {
                Section("控制（平台事件层）") {
                    Toggle("Armed：注册耳机媒体命令", isOn: $model.armed)
                    Picker("命令映射", selection: $model.mapping) {
                        ForEach(RemoteCommandProbe.Mapping.allCases) { Text($0.rawValue).tag($0) }
                    }
                    Picker("Now Playing 元数据（不播放任何音频）", selection: $model.nowPlayingMode) {
                        ForEach(RemoteCommandProbe.NowPlayingMode.allCases) { Text($0.rawValue).tag($0) }
                    }
                }
                Section("采集（严格蓝牙输入）") {
                    Picker("会话模式（对照变量）", selection: $model.sessionMode) {
                        ForEach(SessionModeChoice.allCases) { Text($0.rawValue).tag($0) }
                    }
                    Text("状态：\(model.captureState)")
                    HStack {
                        Button("手动开始") { model.capture.start(trigger: "manual") }
                        Spacer()
                        Button("手动停止") { model.capture.stop(reason: "manual") }
                    }
                    .buttonStyle(.borderless)
                }
                Section("BLE 只读探索（不写入任何数据）") {
                    HStack {
                        Button("开始 BLE 探索") { model.ble.start() }
                        Spacer()
                        Button("停止") { model.ble.stop() }
                    }
                    .buttonStyle(.borderless)
                }
                Section("手势标记（做完耳机手势后立刻点）") {
                    HStack {
                        Button("双击了") { model.mark("double_tap") }
                        Spacer()
                        Button("三击了") { model.mark("triple_tap") }
                        Spacer()
                        Button("长按了") { model.mark("long_press") }
                        Spacer()
                        Button("滑动了") { model.mark("swipe") }
                    }
                    .buttonStyle(.borderless)
                }
                Section("日志尾部（最新在上）") {
                    ForEach(Array(model.tail.enumerated()), id: \.offset) { _, line in
                        Text(line).font(.system(size: 9, design: .monospaced))
                    }
                }
            }
            .navigationTitle("Auri Probe iOS")
            .background(RemoteEventResponder())
        }
    }
}

@main
struct AuriProbeApp: App {
    @StateObject private var model = ProbeModel()

    var body: some Scene {
        WindowGroup {
            ContentView(model: model)
        }
    }
}
