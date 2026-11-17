package app.auri.asr

import app.auri.asr.audio.PcmAudio
import app.auri.asr.engine.AsrEngine
import app.auri.asr.engine.AsrEngineFactory
import app.auri.asr.engine.EngineOutput
import app.auri.asr.manifest.VerifiedModel
import java.io.File
import java.io.RandomAccessFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 转录结果落成合同 JSON 文件。这里只检查文件的形状与取值；文件是否满足 contracts/schemas/ 由
 * execution/acceptance/asr-android/ 的 Python 测试（固定版本的 jsonschema）按真实 schema 校验。
 *
 * 系统属性 auri.contract.out 指向目录时，[把全部场景导出给 Python 合同校验] 把文件写到那里。
 */
class ContractOutputTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val textWithAstralChar = "你好，Auri 😀 hello"

    private class ScriptedEngine(private val reply: () -> EngineOutput) : AsrEngine {
        override fun recognize(samples: FloatArray) = reply()
        override fun close() {}
    }

    private fun factory(engine: AsrEngine) = AsrEngineFactory { _: VerifiedModel -> engine }

    private fun audio(ms: Long, rate: Int = 48000) =
        PcmAudio(ShortArray((ms * rate / 1000).toInt()) { (it % 50).toShort() }, rate, 1)

    /** 在 [out]/[name] 下跑一个场景并写出合同文件。 */
    private fun scenario(
        out: File,
        name: String,
        modelDir: File = TestModel.install(tmp.newFolder("model-$name")).dir,
        audio: PcmAudio = audio(2000),
        reply: () -> EngineOutput = { EngineOutput(textWithAstralChar, "<|zh|>") },
        prepare: (File) -> Unit = {},
    ): File {
        val manifest = TestModel.install(tmp.newFolder("manifest-$name")).manifest
        prepare(modelDir)
        LocalTranscriber(manifest, modelDir, factory(ScriptedEngine(reply))).use { t ->
            val result = t.transcribe(audio)
            return File(out, name).also { ContractOutput.write(it, t.capabilities, result) }
        }
    }

    private fun generateAll(out: File): Map<String, File> {
        val installed = { n: String -> TestModel.install(tmp.newFolder("installed-$n")).dir }
        return linkedMapOf(
            "succeeded" to scenario(out, "succeeded", installed("ok")),
            "no-speech-empty-audio" to scenario(out, "no-speech-empty-audio", installed("empty"), PcmAudio(ShortArray(0), 16000, 1)),
            "no-speech-blank-text" to scenario(out, "no-speech-blank-text", installed("blank"), reply = { EngineOutput("  \n", null) }),
            "waiting-model-not-installed" to scenario(out, "waiting-model-not-installed", File(tmp.root, "never-installed")),
            "waiting-model-integrity-failed" to scenario(
                out, "waiting-model-integrity-failed", installed("flip"),
                prepare = { dir ->
                    RandomAccessFile(File(dir, "model.int8.onnx"), "rw").use { f ->
                        f.seek(10)
                        f.write(f.read().xor(0xFF)) // 同样大小，只翻一个字节
                    }
                },
            ),
            "failed-duration-limit" to scenario(out, "failed-duration-limit", installed("long"), audio = audio(31_000)),
            "failed-engine-exception" to scenario(
                out, "failed-engine-exception", installed("boom"),
                reply = { throw IllegalStateException("引擎内部错误：不应出现在文件里") },
            ),
        )
    }

    private fun File.json(name: String): JsonObject =
        Json.parseToJsonElement(File(this, name).readText(Charsets.UTF_8)).jsonObject

    @Test
    fun `每个场景都写出能力声明与任务句柄 provider_job_id 显式为 null`() {
        generateAll(tmp.newFolder("out")).forEach { (name, dir) ->
            val caps = dir.json("capabilities.json")
            assertEquals(name, "on_device", caps.getValue("execution_location").jsonPrimitive.content)
            assertTrue(name, caps.getValue("formats").jsonArray.isNotEmpty())

            val handle = dir.json("task-handle.json")
            assertTrue("$name：provider_job_id 必须写出并且是 null", handle.getValue("provider_job_id") is JsonNull)
            assertEquals(name, "local-sherpa-onnx", handle.getValue("provider_id").jsonPrimitive.content)
        }
    }

    @Test
    fun `成功场景的修订内容取合同允许的固定值 置信度与分段如实为空`() {
        val content = generateAll(tmp.newFolder("out")).getValue("succeeded").json("revision-content.json")

        assertEquals(textWithAstralChar, content.getValue("text").jsonPrimitive.content)
        assertEquals("asr", content.getValue("source").jsonPrimitive.content)
        assertEquals("not_reviewed", content.getValue("review_state").jsonPrimitive.content)
        assertEquals("on_device", content.getValue("execution_location").jsonPrimitive.content)
        assertEquals("zh", content.getValue("language").jsonPrimitive.content)
        assertTrue("置信度没有来源，必须显式写 null 而不是省略或编一个", content.getValue("confidence") is JsonNull)
        assertEquals(JsonArray(emptyList()), content.getValue("segments"))
        assertEquals(64, content.getValue("text_sha256").jsonPrimitive.content.length)
    }

    @Test
    fun `没有语音与等待模型的场景写出错误文件 错误里的 operation_id 就是任务编号`() {
        val all = generateAll(tmp.newFolder("out"))
        val expectedCodes = mapOf(
            "no-speech-empty-audio" to "ASR_NO_SPEECH",
            "no-speech-blank-text" to "ASR_NO_SPEECH",
            "waiting-model-not-installed" to "MODEL_NOT_INSTALLED",
            "waiting-model-integrity-failed" to "MODEL_INTEGRITY_FAILED",
            "failed-duration-limit" to "ASR_RESOURCE_LIMIT",
        )

        expectedCodes.forEach { (name, code) ->
            val dir = all.getValue(name)
            val error = dir.json("error.json")
            assertEquals(name, code, error.getValue("code").jsonPrimitive.content)
            assertEquals(name, "1.1", error.getValue("schema_version").jsonPrimitive.content)
            assertEquals(name, dir.json("task-handle.json").getValue("job_id"), error.getValue("operation_id"))
            assertFalse("$name：没有产出文本就不该有修订内容", File(dir, "revision-content.json").exists())
        }
    }

    @Test
    fun `引擎内部失败在合同里没有对应错误码 所以只有轨迹没有错误文件 异常消息不落盘`() {
        val dir = generateAll(tmp.newFolder("out")).getValue("failed-engine-exception")

        assertFalse(File(dir, "error.json").exists())
        assertFalse(File(dir, "revision-content.json").exists())
        val all = dir.listFiles()!!.joinToString("\n") { it.readText(Charsets.UTF_8) }
        assertFalse("异常消息可能带口述内容，不能进文件", all.contains("不应出现在文件里"))
    }

    @Test
    fun `轨迹从 QUEUED 出发 逐步记录事件与到达的状态`() {
        val all = generateAll(tmp.newFolder("out"))

        fun steps(name: String) = all.getValue(name).json("trace.json").getValue("steps").jsonArray
            .map { it.jsonObject.getValue("event").jsonPrimitive.content to it.jsonObject.getValue("state_after").jsonPrimitive.content }

        assertEquals("QUEUED", all.getValue("succeeded").json("trace.json").getValue("initial_state").jsonPrimitive.content)
        assertEquals("transcription", all.getValue("succeeded").json("trace.json").getValue("machine").jsonPrimitive.content)
        assertEquals(listOf("RUN_STARTED" to "RUNNING", "RESULT_TEXT" to "SUCCEEDED"), steps("succeeded"))
        assertEquals(listOf("MODEL_MISSING" to "WAITING_MODEL"), steps("waiting-model-not-installed"))
        assertEquals(listOf("MODEL_MISSING" to "WAITING_MODEL"), steps("waiting-model-integrity-failed"))
        assertEquals(listOf("RUN_STARTED" to "RUNNING", "RESULT_NO_SPEECH" to "NO_SPEECH"), steps("no-speech-blank-text"))
        assertEquals(listOf("RUN_STARTED" to "RUNNING", "PERMANENT_ERROR" to "FAILED"), steps("failed-duration-limit"))
        assertEquals(listOf("RUN_STARTED" to "RUNNING", "PERMANENT_ERROR" to "FAILED"), steps("failed-engine-exception"))
    }

    @Test
    fun `错误文件不含本机路径`() {
        val all = generateAll(tmp.newFolder("out"))

        all.filterKeys { it.startsWith("waiting-model") }.values.forEach { dir ->
            assertFalse(dir.json("error.json").toString().contains(tmp.root.path))
        }
    }

    @Test
    fun `把全部场景导出给 Python 合同校验`() {
        val out = System.getProperty("auri.contract.out")?.let(::File) ?: tmp.newFolder("export")
        out.deleteRecursively()
        out.mkdirs()

        val all = generateAll(out)

        assertEquals(7, all.size)
        all.values.forEach { assertTrue(it.path, File(it, "trace.json").isFile) }
    }
}
