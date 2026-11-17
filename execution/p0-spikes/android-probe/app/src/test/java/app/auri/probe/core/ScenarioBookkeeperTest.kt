package app.auri.probe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioBookkeeperTest {

    @Test
    fun seededMatrixStartsAllCellsAsNotTestedWithNullEvidence() {
        val bookkeeper = ScenarioBookkeeper()
        bookkeeper.seed(
            headset = "HUAWEI FreeClip 2",
            phone = "Android Phone (TBD)",
            osVersion = "Android TBD",
            scenarios = listOf("S1", "S2", "S3"),
        )

        val entries = bookkeeper.entries()
        assertEquals(3, entries.size)
        entries.forEach { entry ->
            assertEquals(CompatStatus.NOT_TESTED, entry.status)
            assertNull(entry.evidenceLevel)
            assertTrue(entry.evidenceIds.isEmpty())
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCompatibilityConclusionWithoutRealDeviceE3OrE4Evidence() {
        val bookkeeper = ScenarioBookkeeper()
        bookkeeper.record(
            ScenarioKey(
                headset = "HUAWEI FreeClip 2",
                phone = "Pixel 9",
                osVersion = "Android 15",
                scenarioId = "S2",
            ),
            status = CompatStatus.SUPPORTED_FOREGROUND,
            evidenceLevel = EvidenceLevel.E2,
            evidenceIds = listOf("unit-test"),
            observedRoute = ObservedInputRouteType.BLUETOOTH_HFP,
            notes = "invalid promotion",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCompatibilityConclusionWithoutEvidenceIds() {
        val bookkeeper = ScenarioBookkeeper()
        bookkeeper.record(
            ScenarioKey(
                headset = "HUAWEI FreeClip 2",
                phone = "Pixel 9",
                osVersion = "Android 15",
                scenarioId = "S2",
            ),
            status = CompatStatus.UNSUPPORTED,
            evidenceLevel = EvidenceLevel.E3,
            evidenceIds = emptyList(),
            observedRoute = ObservedInputRouteType.UNKNOWN,
            notes = "missing evidence path",
        )
    }

    @Test
    fun acceptsRealDeviceE3ConclusionAndRendersMarkdownRow() {
        val bookkeeper = ScenarioBookkeeper()
        val key = ScenarioKey(
            headset = "HUAWEI FreeClip",
            phone = "Pixel 9",
            osVersion = "Android 15",
            scenarioId = "S3",
        )
        bookkeeper.seed("HUAWEI FreeClip", "Pixel 9", "Android 15", listOf("S3"))
        bookkeeper.record(
            key = key,
            status = CompatStatus.SUPPORTED_FOREGROUND,
            evidenceLevel = EvidenceLevel.E3,
            evidenceIds = listOf("execution/p0-spikes/android-probe/results/raw/run1.jsonl"),
            observedRoute = ObservedInputRouteType.BLUETOOTH_HFP,
            notes = "stopped on double tap",
        )

        val markdown = bookkeeper.toMarkdownTable()
        assertTrue(markdown.contains("| HUAWEI FreeClip | Pixel 9 | Android 15 | S3 | SUPPORTED_FOREGROUND | E3 | bluetooth_hfp |"))
    }
}
