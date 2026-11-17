import Foundation

enum VolumeDirection: String, Equatable, Sendable {
    case up
    case down
}

enum VolumeBoundary: String, Equatable, Sendable {
    case bottom
    case middle
    case top
}

struct VolumeGestureConfig: Equatable, Sendable {
    var profileID: String = "ios-volume-reversal"
    var profileVersion: Int = 1
    var armGraceNanos: UInt64 = 500_000_000
    var reversalWindowNanos: UInt64 = 2_500_000_000
    var minStepGapNanos: UInt64 = 50_000_000
    var minDelta: Double = 0.001
    var bottomThreshold: Double = 0.065
    var topThreshold: Double = 0.935
}

/// 对应合同 adapter.schema.json 中的 NormalizedControlEvent（volume_change 仅允许 STOP / IGNORE）。
struct NormalizedControlEventRecord: Equatable, Sendable {
    let eventID: String
    let source: String
    let platformEvent: String
    let command: String
    let monotonicNs: UInt64
    let sequence: Int
    let profileID: String
    let profileVersion: Int
    let rawEventID: String?

    var dictionary: [String: Any] {
        [
            "event_id": eventID,
            "source": source,
            "platform_event": platformEvent,
            "command": command,
            "monotonic_ns": monotonicNs,
            "sequence": sequence,
            "profile_id": profileID,
            "profile_version": profileVersion,
            "raw_event_id": rawEventID as Any,
        ]
    }
}

struct VolumeObservationInput: Equatable, Sendable {
    let newVolume: Double
    let previousVolume: Double?
    let monoNanos: UInt64
    let isRecording: Bool
    let recordingStartedMonoNanos: UInt64?
    let sequence: Int
}

enum VolumeGestureOutcome: Equatable, Sendable {
    case ignored(reason: String)
    case armedFirstStep(direction: VolumeDirection, boundary: VolumeBoundary)
    case refreshedSameDirection(direction: VolumeDirection, boundary: VolumeBoundary)
    case triggeredStop(
        firstDirection: VolumeDirection,
        secondDirection: VolumeDirection,
        dtNanos: UInt64,
        startBoundary: VolumeBoundary,
        endBoundary: VolumeBoundary
    )

    var label: String {
        switch self {
        case .ignored: "ignored"
        case .armedFirstStep: "armed_first_step"
        case .refreshedSameDirection: "refreshed_same_direction"
        case .triggeredStop: "triggered_stop"
        }
    }

    var reason: String? {
        if case let .ignored(reason) = self { return reason }
        return nil
    }
}

struct VolumeGestureEvaluation: Equatable, Sendable {
    let outcome: VolumeGestureOutcome
    let delta: Double
    let boundary: VolumeBoundary
    let normalizedEvent: NormalizedControlEventRecord
}

/// 音量往返停止判定器（ADR-0021 提议规则）。
/// 仅在录音中生效：时间窗 W（默认 2.5s）内观测到反向有效变化（上→下 或 下→上）判为 STOP；
/// 单向或连续同向调音量、零增量事件（到顶）及启动保护期事件一律判为 IGNORE。
struct VolumeGestureDetector: Sendable {
    struct PendingStep: Equatable, Sendable {
        let direction: VolumeDirection
        let monoNanos: UInt64
        let startBoundary: VolumeBoundary
    }

    let config: VolumeGestureConfig
    private(set) var pendingStep: PendingStep?

    init(config: VolumeGestureConfig = VolumeGestureConfig()) {
        self.config = config
    }

    mutating func reset() {
        pendingStep = nil
    }

    func classifyBoundary(volume: Double) -> VolumeBoundary {
        if volume <= config.bottomThreshold { return .bottom }
        if volume >= config.topThreshold { return .top }
        return .middle
    }

    mutating func evaluate(_ input: VolumeObservationInput) -> VolumeGestureEvaluation {
        let endBoundary = classifyBoundary(volume: input.newVolume)

        guard input.isRecording else {
            pendingStep = nil
            return makeEvaluation(input: input, delta: 0, boundary: endBoundary, outcome: .ignored(reason: "not_recording"), command: "IGNORE")
        }

        guard let previous = input.previousVolume,
              input.newVolume >= 0.0, input.newVolume <= 1.0,
              previous >= 0.0, previous <= 1.0
        else {
            return makeEvaluation(input: input, delta: 0, boundary: endBoundary, outcome: .ignored(reason: "invalid_volume"), command: "IGNORE")
        }

        let delta = input.newVolume - previous

        if let started = input.recordingStartedMonoNanos,
           input.monoNanos >= started,
           input.monoNanos - started < config.armGraceNanos {
            return makeEvaluation(input: input, delta: delta, boundary: endBoundary, outcome: .ignored(reason: "arm_grace_window"), command: "IGNORE")
        }

        guard abs(delta) >= config.minDelta else {
            return makeEvaluation(input: input, delta: delta, boundary: endBoundary, outcome: .ignored(reason: "zero_delta"), command: "IGNORE")
        }

        let direction: VolumeDirection = delta > 0 ? .up : .down
        let startBoundary = classifyBoundary(volume: previous)

        guard let pending = pendingStep else {
            pendingStep = PendingStep(direction: direction, monoNanos: input.monoNanos, startBoundary: startBoundary)
            return makeEvaluation(input: input, delta: delta, boundary: endBoundary, outcome: .armedFirstStep(direction: direction, boundary: endBoundary), command: "IGNORE")
        }

        let dt = input.monoNanos >= pending.monoNanos ? input.monoNanos - pending.monoNanos : 0

        if dt > config.reversalWindowNanos {
            pendingStep = PendingStep(direction: direction, monoNanos: input.monoNanos, startBoundary: startBoundary)
            return makeEvaluation(input: input, delta: delta, boundary: endBoundary, outcome: .armedFirstStep(direction: direction, boundary: endBoundary), command: "IGNORE")
        }

        if direction == pending.direction {
            pendingStep = PendingStep(direction: direction, monoNanos: input.monoNanos, startBoundary: pending.startBoundary)
            return makeEvaluation(input: input, delta: delta, boundary: endBoundary, outcome: .refreshedSameDirection(direction: direction, boundary: endBoundary), command: "IGNORE")
        }

        if dt < config.minStepGapNanos {
            return makeEvaluation(input: input, delta: delta, boundary: endBoundary, outcome: .ignored(reason: "debounce_min_gap"), command: "IGNORE")
        }

        pendingStep = nil
        return makeEvaluation(
            input: input,
            delta: delta,
            boundary: endBoundary,
            outcome: .triggeredStop(
                firstDirection: pending.direction,
                secondDirection: direction,
                dtNanos: dt,
                startBoundary: pending.startBoundary,
                endBoundary: endBoundary
            ),
            command: "STOP"
        )
    }

    private func makeEvaluation(
        input: VolumeObservationInput,
        delta: Double,
        boundary: VolumeBoundary,
        outcome: VolumeGestureOutcome,
        command: String
    ) -> VolumeGestureEvaluation {
        let normalized = NormalizedControlEventRecord(
            eventID: UUID().uuidString.lowercased(),
            source: "volume_gesture",
            platformEvent: "volume_change",
            command: command,
            monotonicNs: input.monoNanos,
            sequence: input.sequence,
            profileID: config.profileID,
            profileVersion: config.profileVersion,
            rawEventID: "vol-\(input.sequence)"
        )
        return VolumeGestureEvaluation(
            outcome: outcome,
            delta: delta,
            boundary: boundary,
            normalizedEvent: normalized
        )
    }
}
