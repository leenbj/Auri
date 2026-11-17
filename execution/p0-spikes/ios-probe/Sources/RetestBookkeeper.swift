import Foundation

struct ScenarioRetestCounts: Equatable, Sendable {
    var intendedStopCount = 0
    var volumeStopTriggeredCount = 0
    var ordinaryVolumeAdjustCount = 0
    var falseStopCount = 0
    var missedStopCount = 0
    var manualStopCount = 0

    var dictionary: [String: Int] {
        [
            "intended_stop_count": intendedStopCount,
            "volume_stop_triggered_count": volumeStopTriggeredCount,
            "ordinary_volume_adjust_count": ordinaryVolumeAdjustCount,
            "false_stop_count": falseStopCount,
            "missed_stop_count": missedStopCount,
            "manual_stop_count": manualStopCount,
        ]
    }
}

struct RetestSnapshot: Equatable, Sendable {
    var intendedStopCount = 0
    var volumeStopTriggeredCount = 0
    var ordinaryVolumeAdjustCount = 0
    var falseStopCount = 0
    var missedStopCount = 0
    var manualStopCount = 0
    var byScenario: [String: ScenarioRetestCounts] = [:]

    var dictionary: [String: Any] {
        [
            "intended_stop_count": intendedStopCount,
            "volume_stop_triggered_count": volumeStopTriggeredCount,
            "ordinary_volume_adjust_count": ordinaryVolumeAdjustCount,
            "false_stop_count": falseStopCount,
            "missed_stop_count": missedStopCount,
            "manual_stop_count": manualStopCount,
            "by_scenario": byScenario.mapValues { $0.dictionary },
        ]
    }
}

/// 音量停止复测账本：统计触发停止、误停（false stop）和漏停（missed stop）。
struct RetestBookkeeper: Sendable {
    private(set) var snapshot = RetestSnapshot()

    mutating func markIntendedStop(scenario: String) {
        snapshot.intendedStopCount += 1
        snapshot.byScenario[scenario, default: ScenarioRetestCounts()].intendedStopCount += 1
    }

    mutating func recordVolumeStopTriggered(scenario: String) {
        snapshot.volumeStopTriggeredCount += 1
        snapshot.byScenario[scenario, default: ScenarioRetestCounts()].volumeStopTriggeredCount += 1
    }

    mutating func markOrdinaryVolumeAdjust(scenario: String) {
        snapshot.ordinaryVolumeAdjustCount += 1
        snapshot.byScenario[scenario, default: ScenarioRetestCounts()].ordinaryVolumeAdjustCount += 1
    }

    mutating func markFalseStop(scenario: String) {
        snapshot.falseStopCount += 1
        snapshot.byScenario[scenario, default: ScenarioRetestCounts()].falseStopCount += 1
    }

    mutating func markMissedStop(scenario: String) {
        snapshot.missedStopCount += 1
        snapshot.byScenario[scenario, default: ScenarioRetestCounts()].missedStopCount += 1
    }

    mutating func recordManualStop(scenario: String) {
        snapshot.manualStopCount += 1
        snapshot.byScenario[scenario, default: ScenarioRetestCounts()].manualStopCount += 1
    }

    @discardableResult
    mutating func reset() -> RetestSnapshot {
        snapshot = RetestSnapshot()
        return snapshot
    }
}
