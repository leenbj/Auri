import MediaPlayer

/// 业务命令（PRD FR-HS-001 第三层）：由平台媒体事件映射而来。
enum BusinessCommand: String {
    case start = "START"
    case stop = "STOP"
    case toggle = "TOGGLE"
    case ignore = "IGNORE"
}

/// 平台事件层探针：注册 MPRemoteCommandCenter，记录系统实际送来了哪些媒体命令。
/// 不播放任何音频；Now Playing 元数据只作为对照变量，由开关控制。
final class RemoteCommandProbe {
    enum Mapping: String, CaseIterable, Identifiable {
        case logOnly
        case semantic
        case toggle

        var id: String { rawValue }
    }

    var mapping: Mapping = .semantic
    var onCommand: (@Sendable (BusinessCommand, String) -> Void)?

    private var registered: [(MPRemoteCommand, Any)] = []

    func arm() {
        guard registered.isEmpty else { return }
        let center = MPRemoteCommandCenter.shared()
        let commands: [(String, MPRemoteCommand)] = [
            ("play", center.playCommand),
            ("pause", center.pauseCommand),
            ("togglePlayPause", center.togglePlayPauseCommand),
            ("stop", center.stopCommand),
            ("nextTrack", center.nextTrackCommand),
            ("previousTrack", center.previousTrackCommand),
        ]
        for (name, command) in commands {
            command.isEnabled = true
            let token = command.addTarget { [weak self] event in
                self?.handle(name, event)
                return .success
            }
            registered.append((command, token))
        }
        ProbeLogger.shared.log("remote_armed", ["mapping": mapping.rawValue])
    }

    func disarm() {
        for (command, token) in registered {
            command.removeTarget(token)
            command.isEnabled = false
        }
        registered.removeAll()
        ProbeLogger.shared.log("remote_disarmed")
    }

    enum NowPlayingMode: String, CaseIterable, Identifiable {
        case off, paused, playing

        var id: String { rawValue }
    }

    /// 只发布元数据作为对照变量：不创建播放器，不播放任何音频。
    func publishNowPlaying(_ mode: NowPlayingMode) {
        let center = MPNowPlayingInfoCenter.default()
        switch mode {
        case .off:
            center.nowPlayingInfo = nil
            center.playbackState = .unknown
        case .paused:
            center.nowPlayingInfo = [
                MPMediaItemPropertyTitle: "Auri Probe",
                MPNowPlayingInfoPropertyPlaybackRate: 0.0,
            ]
            center.playbackState = .paused
        case .playing:
            center.nowPlayingInfo = [
                MPMediaItemPropertyTitle: "Auri Probe",
                MPNowPlayingInfoPropertyPlaybackRate: 1.0,
            ]
            center.playbackState = .playing
        }
        ProbeLogger.shared.log("now_playing_info", ["mode": mode.rawValue])
    }

    private func handle(_ name: String, _ event: MPRemoteCommandEvent) {
        let command = translate(name)
        ProbeLogger.shared.log("remote_command", [
            "platform_command": name,
            "business_command": command.rawValue,
            "event_timestamp": event.timestamp,
            "mapping": mapping.rawValue,
        ])
        guard command != .ignore else { return }
        onCommand?(command, name)
    }

    private func translate(_ name: String) -> BusinessCommand {
        guard mapping != .logOnly else { return .ignore }
        switch (mapping, name) {
        case (.semantic, "play"):
            return .start
        case (.semantic, "pause"), (.semantic, "stop"):
            return .stop
        case (_, "togglePlayPause"):
            return .toggle
        default:
            return .ignore
        }
    }
}
