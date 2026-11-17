import XCTest
@testable import AuriProbeIOS

final class VolumeGestureDetectorTests: XCTestCase {
    private let sec: UInt64 = 1_000_000_000

    func testIgnoresVolumeChangeWhenNotRecording() {
        var detector = VolumeGestureDetector()
        let eval = detector.evaluate(VolumeObservationInput(
            newVolume: 0.5625,
            previousVolume: 0.50,
            monoNanos: 2 * sec,
            isRecording: false,
            recordingStartedMonoNanos: nil,
            sequence: 1
        ))

        XCTAssertEqual(eval.outcome, .ignored(reason: "not_recording"))
        XCTAssertEqual(eval.normalizedEvent.command, "IGNORE")
        XCTAssertEqual(eval.normalizedEvent.source, "volume_gesture")
        XCTAssertEqual(eval.normalizedEvent.platformEvent, "volume_change")
    }

    func testIgnoresInitialVolumeSyncDuringArmGraceWindow() {
        var detector = VolumeGestureDetector()
        let startNs: UInt64 = 10 * sec
        let eval = detector.evaluate(VolumeObservationInput(
            newVolume: 0.5625,
            previousVolume: 0.50,
            monoNanos: startNs + 200_000_000,
            isRecording: true,
            recordingStartedMonoNanos: startNs,
            sequence: 1
        ))

        XCTAssertEqual(eval.outcome, .ignored(reason: "arm_grace_window"))
        XCTAssertEqual(eval.normalizedEvent.command, "IGNORE")
    }

    func testSingleVolumeIncreaseArmsFirstStepAndDoesNotStop() {
        var detector = VolumeGestureDetector()
        let startNs: UInt64 = 10 * sec
        let eval = detector.evaluate(VolumeObservationInput(
            newVolume: 0.5625,
            previousVolume: 0.50,
            monoNanos: startNs + 1 * sec,
            isRecording: true,
            recordingStartedMonoNanos: startNs,
            sequence: 1
        ))

        XCTAssertEqual(eval.outcome, .armedFirstStep(direction: .up, boundary: .middle))
        XCTAssertEqual(eval.normalizedEvent.command, "IGNORE")
    }

    func testRepeatedSameDirectionChangesDoNotTriggerStop() {
        var detector = VolumeGestureDetector()
        let startNs: UInt64 = 10 * sec

        _ = detector.evaluate(VolumeObservationInput(
            newVolume: 0.5625,
            previousVolume: 0.50,
            monoNanos: startNs + 1 * sec,
            isRecording: true,
            recordingStartedMonoNanos: startNs,
            sequence: 1
        ))
        let second = detector.evaluate(VolumeObservationInput(
            newVolume: 0.625,
            previousVolume: 0.5625,
            monoNanos: startNs + 1 * sec + 400_000_000,
            isRecording: true,
            recordingStartedMonoNanos: startNs,
            sequence: 2
        ))

        XCTAssertEqual(second.outcome, .refreshedSameDirection(direction: .up, boundary: .middle))
        XCTAssertEqual(second.normalizedEvent.command, "IGNORE")
    }

    func testUpThenDownWithinWindowTriggersStopAndNormalizesEvent() {
        var detector = VolumeGestureDetector()
        let startNs: UInt64 = 10 * sec

        _ = detector.evaluate(VolumeObservationInput(
            newVolume: 0.5625,
            previousVolume: 0.50,
            monoNanos: startNs + 1 * sec,
            isRecording: true,
            recordingStartedMonoNanos: startNs,
            sequence: 1
        ))
        let second = detector.evaluate(VolumeObservationInput(
            newVolume: 0.50,
            previousVolume: 0.5625,
            monoNanos: startNs + 1 * sec + 600_000_000,
            isRecording: true,
            recordingStartedMonoNanos: startNs,
            sequence: 2
        ))

        XCTAssertEqual(
            second.outcome,
            .triggeredStop(
                firstDirection: .up,
                secondDirection: .down,
                dtNanos: 600_000_000,
                startBoundary: .middle,
                endBoundary: .middle
            )
        )
        XCTAssertEqual(second.normalizedEvent.command, "STOP")
        XCTAssertEqual(second.normalizedEvent.source, "volume_gesture")
        XCTAssertEqual(second.normalizedEvent.platformEvent, "volume_change")
        XCTAssertEqual(second.normalizedEvent.profileID, "ios-volume-reversal")
        XCTAssertEqual(second.normalizedEvent.profileVersion, 1)
    }

    func testDownThenUpAtTopBoundaryTriggersStop() {
        var detector = VolumeGestureDetector()
        let startNs: UInt64 = 10 * sec

        let first = detector.evaluate(VolumeObservationInput(
            newVolume: 0.9375,
            previousVolume: 1.0,
            monoNanos: startNs + 1 * sec,
            isRecording: true,
            recordingStartedMonoNanos: startNs,
            sequence: 1
        ))
        XCTAssertEqual(first.outcome, .armedFirstStep(direction: .down, boundary: .top))

        let second = detector.evaluate(VolumeObservationInput(
            newVolume: 1.0,
            previousVolume: 0.9375,
            monoNanos: startNs + 1 * sec + 700_000_000,
            isRecording: true,
            recordingStartedMonoNanos: startNs,
            sequence: 2
        ))
        XCTAssertEqual(
            second.outcome,
            .triggeredStop(
                firstDirection: .down,
                secondDirection: .up,
                dtNanos: 700_000_000,
                startBoundary: .top,
                endBoundary: .top
            )
        )
        XCTAssertEqual(second.normalizedEvent.command, "STOP")
    }

    func testZeroDeltaAtTopDoesNotCountAsStepForFollowingDown() {
        var detector = VolumeGestureDetector()
        let startNs: UInt64 = 10 * sec

        let zeroEvent = detector.evaluate(VolumeObservationInput(
            newVolume: 1.0,
            previousVolume: 1.0,
            monoNanos: startNs + 1 * sec,
            isRecording: true,
            recordingStartedMonoNanos: startNs,
            sequence: 1
        ))
        XCTAssertEqual(zeroEvent.outcome, .ignored(reason: "zero_delta"))

        let ordinaryDown = detector.evaluate(VolumeObservationInput(
            newVolume: 0.9375,
            previousVolume: 1.0,
            monoNanos: startNs + 1 * sec + 500_000_000,
            isRecording: true,
            recordingStartedMonoNanos: startNs,
            sequence: 2
        ))
        XCTAssertEqual(ordinaryDown.outcome, .armedFirstStep(direction: .down, boundary: .top))
        XCTAssertEqual(ordinaryDown.normalizedEvent.command, "IGNORE")
    }

    func testUpThenDownAtBottomBoundaryTriggersStop() {
        var detector = VolumeGestureDetector()
        let startNs: UInt64 = 10 * sec

        _ = detector.evaluate(VolumeObservationInput(
            newVolume: 0.125,
            previousVolume: 0.05,
            monoNanos: startNs + 1 * sec,
            isRecording: true,
            recordingStartedMonoNanos: startNs,
            sequence: 1
        ))
        let second = detector.evaluate(VolumeObservationInput(
            newVolume: 0.05,
            previousVolume: 0.125,
            monoNanos: startNs + 1 * sec + 500_000_000,
            isRecording: true,
            recordingStartedMonoNanos: startNs,
            sequence: 2
        ))

        XCTAssertEqual(
            second.outcome,
            .triggeredStop(
                firstDirection: .up,
                secondDirection: .down,
                dtNanos: 500_000_000,
                startBoundary: .bottom,
                endBoundary: .bottom
            )
        )
        XCTAssertEqual(second.normalizedEvent.command, "STOP")
    }

    func testOppositeChangeAfterWindowExpiresReArmsInsteadOfStopping() {
        var detector = VolumeGestureDetector()
        let startNs: UInt64 = 10 * sec

        _ = detector.evaluate(VolumeObservationInput(
            newVolume: 0.5625,
            previousVolume: 0.50,
            monoNanos: startNs + 1 * sec,
            isRecording: true,
            recordingStartedMonoNanos: startNs,
            sequence: 1
        ))
        let lateOpposite = detector.evaluate(VolumeObservationInput(
            newVolume: 0.50,
            previousVolume: 0.5625,
            monoNanos: startNs + 4 * sec, // 3.0s > 2.5s window
            isRecording: true,
            recordingStartedMonoNanos: startNs,
            sequence: 2
        ))

        XCTAssertEqual(lateOpposite.outcome, .armedFirstStep(direction: .down, boundary: .middle))
        XCTAssertEqual(lateOpposite.normalizedEvent.command, "IGNORE")
    }

    func testResetClearsPendingStepBetweenSessions() {
        var detector = VolumeGestureDetector()
        let startNs: UInt64 = 10 * sec

        _ = detector.evaluate(VolumeObservationInput(
            newVolume: 0.5625,
            previousVolume: 0.50,
            monoNanos: startNs + 1 * sec,
            isRecording: true,
            recordingStartedMonoNanos: startNs,
            sequence: 1
        ))
        detector.reset()

        let nextSessionDown = detector.evaluate(VolumeObservationInput(
            newVolume: 0.50,
            previousVolume: 0.5625,
            monoNanos: startNs + 1 * sec + 600_000_000,
            isRecording: true,
            recordingStartedMonoNanos: startNs,
            sequence: 2
        ))
        XCTAssertEqual(nextSessionDown.outcome, .armedFirstStep(direction: .down, boundary: .middle))
        XCTAssertEqual(nextSessionDown.normalizedEvent.command, "IGNORE")
    }
}
