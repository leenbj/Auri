package app.auri.platform.asr

import app.auri.asr.manifest.ModelManifest
import app.auri.capture.contract.ErrorCode
import app.auri.capture.scheduler.ModelReadiness
import app.auri.modelmgmt.ActiveModel
import app.auri.modelmgmt.ActiveModelStatus
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Ticket 20：模型管理器的三种启用状态在调度器眼里是什么；“代”原样带过去，换代才算变化。 */
class ModelManagerAvailabilityTest {
    private val manifest = ModelManifest.parse(
        File(System.getProperty("auri.manifests.dir"), "sensevoice-small-int8-2024-07-17.manifest.json").readText(),
    )

    private fun ready(generation: Long) = ActiveModelStatus.Ready(ActiveModel(manifest, File("model-$generation"), generation))

    @Test
    fun `三种状态：没装、损坏、可用（带代）`() {
        val source = MutableStateFlow<ActiveModelStatus>(ActiveModelStatus.NotInstalled)
        val availability = ModelManagerAvailability(source)

        assertEquals(ModelReadiness.Missing(ErrorCode.MODEL_NOT_INSTALLED), availability.readiness.value)
        source.value = ActiveModelStatus.Damaged(manifest.modelId)
        assertEquals(ModelReadiness.Missing(ErrorCode.MODEL_INTEGRITY_FAILED), availability.readiness.value)
        source.value = ready(5)
        assertEquals(ModelReadiness.Ready(5), availability.readiness.value)
    }

    @Test
    fun `订阅者按顺序看到每一次真正的变化：换代是变化，同样的“损坏”不重复发`() = runBlocking {
        val source = MutableStateFlow<ActiveModelStatus>(ActiveModelStatus.NotInstalled)
        val seen = CopyOnWriteArrayList<ModelReadiness>()
        val collector = launch(Dispatchers.Unconfined) { ModelManagerAvailability(source).readiness.collect { seen += it } }

        source.value = ActiveModelStatus.Damaged("a")
        source.value = ActiveModelStatus.Damaged("b")
        source.value = ready(1)
        source.value = ready(2)
        collector.cancel()

        assertEquals(
            listOf(
                ModelReadiness.Missing(ErrorCode.MODEL_NOT_INSTALLED),
                ModelReadiness.Missing(ErrorCode.MODEL_INTEGRITY_FAILED),
                ModelReadiness.Ready(1),
                ModelReadiness.Ready(2),
            ),
            seen.toList(),
        )
    }
}
