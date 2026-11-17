import XCTest
@testable import AuriProbeIOS

final class RetestBookkeeperTests: XCTestCase {
    func testCountsTriggersFalseStopsAndMissedStopsPerScenario() {
        var bookkeeper = RetestBookkeeper()

        bookkeeper.markIntendedStop(scenario: "middle")
        bookkeeper.recordVolumeStopTriggered(scenario: "middle")

        bookkeeper.markOrdinaryVolumeAdjust(scenario: "middle")
        bookkeeper.recordVolumeStopTriggered(scenario: "middle")
        bookkeeper.markFalseStop(scenario: "middle")

        bookkeeper.markIntendedStop(scenario: "bottom")
        bookkeeper.markMissedStop(scenario: "bottom")
        bookkeeper.recordManualStop(scenario: "bottom")

        let snap = bookkeeper.snapshot
        XCTAssertEqual(snap.intendedStopCount, 2)
        XCTAssertEqual(snap.volumeStopTriggeredCount, 2)
        XCTAssertEqual(snap.ordinaryVolumeAdjustCount, 1)
        XCTAssertEqual(snap.falseStopCount, 1)
        XCTAssertEqual(snap.missedStopCount, 1)
        XCTAssertEqual(snap.manualStopCount, 1)

        XCTAssertEqual(snap.byScenario["middle"]?.volumeStopTriggeredCount, 2)
        XCTAssertEqual(snap.byScenario["middle"]?.falseStopCount, 1)
        XCTAssertEqual(snap.byScenario["bottom"]?.missedStopCount, 1)
        XCTAssertEqual(snap.byScenario["bottom"]?.manualStopCount, 1)
    }

    func testResetClearsAllCounters() {
        var bookkeeper = RetestBookkeeper()
        bookkeeper.recordVolumeStopTriggered(scenario: "top")
        bookkeeper.markFalseStop(scenario: "top")
        bookkeeper.markMissedStop(scenario: "top")

        let cleared = bookkeeper.reset()
        XCTAssertEqual(cleared.volumeStopTriggeredCount, 0)
        XCTAssertEqual(cleared.falseStopCount, 0)
        XCTAssertEqual(cleared.missedStopCount, 0)
        XCTAssertTrue(cleared.byScenario.isEmpty)
    }
}
