package app.auri.probe.core

data class ScenarioKey(
    val headset: String,
    val phone: String,
    val osVersion: String,
    val scenarioId: String,
)

data class ScenarioResultEntry(
    val key: ScenarioKey,
    val status: CompatStatus,
    val evidenceLevel: EvidenceLevel?,
    val evidenceIds: List<String>,
    val observedRoute: ObservedInputRouteType?,
    val notes: String,
)

/**
 * Maintains per-(headset × phone × osVersion × scenario) compatibility entries
 * enforcing contract rule: any status beyond NOT_TESTED/CALIBRATION_REQUIRED requires E3/E4 + evidence_ids.
 */
class ScenarioBookkeeper {
    private val rows = linkedMapOf<ScenarioKey, ScenarioResultEntry>()

    fun seed(
        headset: String,
        phone: String,
        osVersion: String,
        scenarios: List<String>,
    ) {
        scenarios.forEach { scenario ->
            val key = ScenarioKey(headset, phone, osVersion, scenario)
            rows.putIfAbsent(
                key,
                ScenarioResultEntry(
                    key = key,
                    status = CompatStatus.NOT_TESTED,
                    evidenceLevel = null,
                    evidenceIds = emptyList(),
                    observedRoute = null,
                    notes = "",
                ),
            )
        }
    }

    fun record(
        key: ScenarioKey,
        status: CompatStatus,
        evidenceLevel: EvidenceLevel?,
        evidenceIds: List<String>,
        observedRoute: ObservedInputRouteType?,
        notes: String,
    ) {
        val requiresDeviceEvidence =
            status != CompatStatus.NOT_TESTED && status != CompatStatus.CALIBRATION_REQUIRED
        if (requiresDeviceEvidence) {
            require(evidenceLevel == EvidenceLevel.E3 || evidenceLevel == EvidenceLevel.E4) {
                "CompatStatus $status requires E3 or E4 device evidence (was $evidenceLevel)"
            }
            require(evidenceIds.any { it.isNotBlank() }) {
                "CompatStatus $status requires at least one non-blank evidenceId"
            }
        }
        rows[key] = ScenarioResultEntry(
            key = key,
            status = status,
            evidenceLevel = evidenceLevel,
            evidenceIds = evidenceIds.filter { it.isNotBlank() },
            observedRoute = observedRoute,
            notes = notes,
        )
    }

    fun entries(): List<ScenarioResultEntry> = rows.values.toList()

    fun toMarkdownTable(): String {
        val sb = StringBuilder()
        sb.appendLine("| Headset | Phone | OS Version | Scenario | Status | Evidence | Observed Route | Notes |")
        sb.appendLine("|---|---|---|---|---|---|---|---|")
        rows.values.forEach { entry ->
            val level = entry.evidenceLevel?.name ?: "-"
            val route = entry.observedRoute?.wireValue ?: "-"
            sb.appendLine(
                "| ${entry.key.headset} | ${entry.key.phone} | ${entry.key.osVersion} | ${entry.key.scenarioId} | ${entry.status.name} | $level | $route | ${entry.notes} |",
            )
        }
        return sb.toString().trimEnd()
    }
}
