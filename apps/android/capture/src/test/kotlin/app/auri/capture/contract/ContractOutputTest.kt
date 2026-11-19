package app.auri.capture.contract

import app.auri.capture.adapter.TranscriptionOutcome
import app.auri.capture.adapter.TranscriptionRequest
import app.auri.capture.adapter.AudioFrames
import app.auri.capture.coordinator.CaptureConfig
import app.auri.capture.coordinator.SealedRecording
import app.auri.capture.library.RecordEntry
import app.auri.capture.library.Revision
import app.auri.capture.pipeline.RecordPipeline
import app.auri.capture.testing.CaptureRig
import app.auri.capture.testing.FakeTranscriptionAdapter
import app.auri.capture.testing.InMemoryRecordLibrary
import app.auri.capture.testing.Samples
import java.io.File
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 把采集 → 转录这条线产出的东西写成合同形状的 JSON 文件，交给 Python 的 contract_check.py 用真实的 contracts/schemas/ 校验
 * （execution/acceptance/android-capture/test_contract_output.py 会调用本测试再校验）。
 *
 * 写出的是“Ticket 21 之后要提交给网关的那些东西”：RecordingResult、RouteChangeEvent、asr_capabilities、asr_task_handle，
 * 以及从本地 Record / Revision 组装出的 revision.capture 与 revision.content 两段。组装代码只在这里（测试里）：
 * 它证明本地库里存的字段够用、取值对得上合同，Ticket 21 不需要再改库的结构；真正的提交组装是 Ticket 21 的事。
 *
 * 输出目录由 Gradle 的系统属性 auri.contract.out 给定（见 capture/build.gradle.kts）。
 */
class ContractOutputTest {
    private val outRoot = File(checkNotNull(System.getProperty("auri.contract.out")) { "auri.contract.out 没有设置：请经 Gradle 运行" })

    private val json = Json { prettyPrint = true }

    /** 跑一次真实的协调器 + 管线（假的系统边缘），返回封存结果、库里的 Record 与转录用的假适配器。 */
    private class Outcome(val sealed: SealedRecording, val entry: RecordEntry, val transcription: FakeTranscriptionAdapter)

    private suspend fun TestScope.record(
        endWithMismatch: Boolean = false,
        outcome: (TranscriptionRequest) -> TranscriptionOutcome = { TranscriptionOutcome.Text("你好，Auri 😀 hello", "zh", 0.93) },
        frames: List<ShortArray>? = null,
        limitStopped: Boolean = false,
    ): Outcome {
        val rig = CaptureRig(this, CaptureConfig(maxDurationMs = if (limitStopped) 120_000 else CaptureConfig().maxDurationMs))
        val library = InMemoryRecordLibrary()
        val transcription = FakeTranscriptionAdapter().also { it.outcome = outcome }
        rig.output.forwardTo = RecordPipeline(library, transcription, rig.clock, backgroundScope)

        rig.coordinator.enableControlMode()
        rig.settle()
        rig.control.press(BusinessCommand.START)
        rig.settle()
        rig.capture.verifiedRoute(epoch = 1)
        rig.settle()
        if (frames == null) rig.capture.frames(samples = 16_000, epoch = 1)
        else frames.forEach { rig.capture.sink().frames(AudioFrames(1, it)) }
        rig.settle()
        if (limitStopped) rig.advance(120_000)
        else if (endWithMismatch) rig.capture.mismatchRoute(epoch = 2)
        else rig.control.press(BusinessCommand.STOP)
        rig.settle()
        rig.settle()

        val sealed = rig.output.recordings.single()
        return Outcome(sealed, checkNotNull(library.get(sealed.result.recordId)), transcription)
    }

    private fun scenarioDir(name: String): File = File(outRoot, name).also {
        it.deleteRecursively()
        it.mkdirs()
    }

    private fun File.write(name: String, text: String) = resolve(name).writeText(text + "\n", Charsets.UTF_8)

    private inline fun <reified E : Enum<E>> enumJson(value: E): JsonElement =
        ContractJson.json.encodeToJsonElement(serializer<E>(), value)

    /** 本地 Record → revision.schema.json#/$defs/capture。platform 与 app_version 是应用的常量，headset_firmware 系统不给就是 null。 */
    private fun captureBlock(entry: RecordEntry): JsonObject = buildJsonObject {
        put("platform", "android")
        put("app_version", "0.0.1")
        put("started_at", entry.startedAt.toString())
        put("capture_duration_ms", entry.captureDurationMs)
        put("speech_duration_ms", entry.speechDurationMs)
        put(
            "speech_segments",
            JsonArray(entry.speechSegments.map { buildJsonObject { put("start_ms", it.startMs); put("end_ms", it.endMs) } }),
        )
        put("end_reason", enumJson(entry.endReason))
        put("status", enumJson(entry.status))
        put("input_policy", "bluetooth_only")
        put("input_verification", "verified")
        put("headset_display_name", entry.headsetDisplayName?.let(::JsonPrimitive) ?: JsonNull)
        put("headset_firmware", JsonNull)
        put("input_route_type", enumJson(entry.inputRouteType))
        put("route_evidence_id", entry.routeEvidenceId)
    }

    /** 本地 Revision → revision.schema.json#/$defs/content。 */
    private fun contentBlock(revision: Revision): JsonObject = buildJsonObject {
        put("text", revision.text)
        put("text_sha256", revision.textSha256)
        put("source", enumJson(revision.source))
        put("language", revision.language)
        put("review_state", enumJson(revision.reviewState))
        put("execution_location", enumJson(revision.executionLocation))
        put("engine", revision.engine?.let(::JsonPrimitive) ?: JsonNull)
        put("model_id", revision.modelId?.let(::JsonPrimitive) ?: JsonNull)
        put("model_version", revision.modelVersion?.let(::JsonPrimitive) ?: JsonNull)
        put("confidence", revision.confidence?.let(::JsonPrimitive) ?: JsonNull)
        put("segments", JsonArray(emptyList()))
    }

    private fun writeCommon(dir: File, result: Outcome) {
        dir.write("recording-result.json", ContractJson.encode(result.sealed.result))
        dir.write("route-evidence.json", ContractJson.encode(result.sealed.evidence))
        dir.write("capabilities.json", ContractJson.encode(result.transcription.capabilities))
        dir.write("task-handle.json", ContractJson.encode(Samples.job(1)))
    }

    private fun writeText(dir: File, result: Outcome) {
        dir.write("capture-block.json", json.encodeToString(JsonObject.serializer(), captureBlock(result.entry)))
        dir.write("revision-content.json", json.encodeToString(JsonObject.serializer(), contentBlock(result.entry.revisions.single())))
    }

    @Test
    fun `speech mixed silence no speech and duration limit produce contract output`() = runTest {
        val sound = ShortArray(16_000) { 1_000 }
        val silence = ShortArray(16_000)
        val scenarios = listOf(
            "speech" to record(frames = listOf(sound)),
            "mixed" to record(frames = listOf(silence, sound, silence, silence, sound, silence)),
            "no-speech" to record(frames = listOf(silence)),
            "limit" to record(frames = listOf(sound), limitStopped = true),
        )
        val root = File(outRoot.parentFile, "silence-jvm")
        for ((name, result) in scenarios) {
            val dir = File(root, name).also { it.deleteRecursively(); it.mkdirs() }
            writeCommon(dir, result)
            if (result.entry.revisions.isNotEmpty()) writeText(dir, result)
            assertEquals(result.sealed.result.verifiedRanges, result.entry.speechSegments)
        }
        assertTrue(scenarios.single { it.first == "no-speech" }.second.entry.revisions.isEmpty())
        assertTrue(scenarios.single { it.first == "no-speech" }.second.transcription.requests.isEmpty())
        assertEquals(EndReason.DURATION_LIMIT, scenarios.single { it.first == "limit" }.second.entry.endReason)
    }

    @Test
    fun `完整的一次：屏幕按钮停止，转出文字，Revision 写明模型`() = runTest {
        val result = record()

        val dir = scenarioDir("complete-with-text")
        writeCommon(dir, result)
        writeText(dir, result)

        assertEquals(CaptureStatus.COMPLETE, result.entry.status)
        assertEquals(EndReason.SCREEN_CONTROL, result.entry.endReason)
        val revision = result.entry.revisions.single()
        assertEquals("你好，Auri 😀 hello", revision.text)
        assertEquals(listOf(revision.engine, revision.modelId, revision.modelVersion), listOf("fixture-engine", "fixture-model", "1.0.0"))
        assertTrue(dir.resolve("capture-block.json").isFile)
    }

    @Test
    fun `确认了路由但没有语音：有录音结果，没有 Revision，不组装要提交的内容`() = runTest {
        val result = record(outcome = { TranscriptionOutcome.NoSpeech })

        val dir = scenarioDir("complete-no-speech")
        writeCommon(dir, result)

        assertTrue("没有语音就没有 Revision", result.entry.revisions.isEmpty())
        assertTrue(!dir.resolve("revision-content.json").exists() && !dir.resolve("capture-block.json").exists())
    }

    @Test
    fun `被截断的录音（路由丢了）：状态是 partial，截断前的音频照样转录，capture 段的 status 与结束原因一致`() = runTest {
        val result = record(endWithMismatch = true)

        val dir = scenarioDir("partial-route-lost")
        writeCommon(dir, result)
        writeText(dir, result)

        assertEquals(CaptureStatus.PARTIAL, result.entry.status)
        assertEquals(EndReason.ROUTE_LOST, result.entry.endReason)
        assertEquals(1, result.entry.revisions.size)
    }
}
