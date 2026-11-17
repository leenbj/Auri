package app.auri.asr.manifest

import app.auri.asr.contract.ErrorCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 清单是“该信什么”的唯一依据（FR-ASR-004）：解析宁可严格拒绝，也不能宽松接受。
 * 任何一种畸形清单都必须在碰引擎之前以 [ModelRefusedException] 终止，并映射到合同里的错误码。
 */
class ModelManifestTest {
    private val sha = "a".repeat(64)

    private fun baseline(): JsonObject = buildJsonObject {
        put("manifest_version", 1)
        put("model_id", "test-model")
        put("model_version", "2024-07-17+7d1efa2138a6")
        put("family", "sense_voice")
        putJsonArray("languages") { add("zh"); add("en") }
        put("sample_rate_hz", 16000)
        putJsonObject("limits") { put("max_audio_duration_ms", 30000) }
        putJsonObject("engine") { put("name", "sherpa-onnx"); put("version_range", "==2.0.1") }
        putJsonObject("license") {
            put("name", "FunASR Model Open Source License v1.1")
            put("source_url", "https://example.invalid/MODEL_LICENSE")
            put("redistribution", "unconfirmed")
            put("note", "未得到许可方的最终确认")
        }
        putJsonObject("archive") {
            put("url", "https://example.invalid/model.tar.bz2")
            put("size_bytes", 163002883)
            put("sha256", sha)
        }
        putJsonArray("files") {
            addJsonObject { put("role", "model"); put("path", "model.int8.onnx"); put("size_bytes", 10); put("sha256", sha) }
            addJsonObject { put("role", "tokens"); put("path", "tokens.txt"); put("size_bytes", 5); put("sha256", sha) }
        }
    }

    private fun with(change: MutableMap<String, JsonElement>.() -> Unit): String =
        JsonObject(baseline().toMutableMap().apply(change)).toString()

    private fun MutableMap<String, JsonElement>.put(key: String, value: Number) {
        this[key] = JsonPrimitive(value)
    }

    private fun MutableMap<String, JsonElement>.put(key: String, value: String) {
        this[key] = JsonPrimitive(value)
    }

    private fun fileEntry(role: String, path: String, size: Long = 10, hash: String = sha) = buildJsonObject {
        put("role", role); put("path", path); put("size_bytes", size); put("sha256", hash)
    }

    private fun assertRefused(json: String, expectation: String): ModelRefusedException {
        try {
            ModelManifest.parse(json)
        } catch (e: ModelRefusedException) {
            assertEquals(expectation, ModelRefusal.MANIFEST_INVALID, e.refusal)
            assertEquals(ErrorCode.MODEL_INTEGRITY_FAILED, e.errorCode)
            return e
        }
        fail("应当拒绝：$expectation")
        error("unreachable")
    }

    @Test
    fun `完整清单解析出名称 版本 文件 许可证 语言 采样率`() {
        val m = ModelManifest.parse(baseline().toString())

        assertEquals("test-model", m.modelId)
        assertEquals("2024-07-17+7d1efa2138a6", m.modelVersion)
        assertEquals("sense_voice", m.family)
        assertEquals(listOf("zh", "en"), m.languages)
        assertEquals(16000, m.sampleRateHz)
        assertEquals(30000L, m.limits.maxAudioDurationMs)
        assertEquals("sherpa-onnx", m.engine.name)
        assertEquals("==2.0.1", m.engine.versionRange)
        assertEquals(listOf("model", "tokens"), m.files.map { it.role })
        assertEquals("model.int8.onnx", m.files[0].path)
        assertEquals(10L, m.files[0].sizeBytes)
        assertEquals(sha, m.files[0].sha256)
        assertEquals(163002883L, m.archive.sizeBytes)
    }

    @Test
    fun `许可证的再分发状态必须如实声明 不允许省略`() {
        val m = ModelManifest.parse(baseline().toString())
        assertEquals(Redistribution.UNCONFIRMED, m.license.redistribution)

        val license = baseline()["license"] as JsonObject
        assertRefused(with { put("license", JsonObject(license - "redistribution")) }, "缺少 redistribution")
        assertRefused(
            with { put("license", JsonObject(license + ("redistribution" to JsonPrimitive("permitted-ish")))) },
            "redistribution 取值不在 confirmed/unconfirmed 之内",
        )
    }

    @Test
    fun `不是 JSON 或不是对象的输入被拒绝`() {
        assertRefused("", "空串")
        assertRefused("not json", "非 JSON")
        assertRefused("[]", "数组")
    }

    @Test
    fun `未知的清单版本被拒绝 因为不知道其中字段的含义`() {
        assertRefused(with { put("manifest_version", 2) }, "manifest_version=2")
        assertRefused(with { put("manifest_version", 0) }, "manifest_version=0")
    }

    @Test
    fun `未知字段被拒绝 而不是悄悄忽略`() {
        assertRefused(with { put("trust_me", JsonPrimitive(true)) }, "顶层多余字段")
        val files = baseline()["files"] as JsonArray
        val polluted = JsonObject(files[0].let { (it as JsonObject) + ("verify" to JsonPrimitive(false)) })
        assertRefused(with { put("files", JsonArray(listOf(polluted) + files.drop(1))) }, "文件条目多余字段")
    }

    @Test
    fun `缺少必填字段被拒绝`() {
        for (key in baseline().keys) {
            assertRefused(with { remove(key) }, "缺少 $key")
        }
    }

    @Test
    fun `会逃出模型目录的文件路径被拒绝`() {
        val bad = listOf(
            "../evil.onnx", "/etc/passwd", "a/../../b", "a/./b", "./model.onnx", "a//b",
            "a\\b", "", " ", "dir/", "..", "C:/x", "a/\u0000b",
        )
        for (path in bad) {
            val files = JsonArray(listOf(fileEntry("model", path), fileEntry("tokens", "tokens.txt")))
            assertRefused(with { put("files", files) }, "路径 '$path'")
        }
    }

    @Test
    fun `SHA-256 必须是 64 位小写十六进制`() {
        val bad = listOf("A".repeat(64), "a".repeat(63), "a".repeat(65), "g".repeat(64), "", " " + "a".repeat(63))
        for (hash in bad) {
            val files = JsonArray(listOf(fileEntry("model", "m.onnx", hash = hash), fileEntry("tokens", "t.txt")))
            assertRefused(with { put("files", files) }, "文件哈希 '$hash'")
        }
        val archive = baseline()["archive"] as JsonObject
        assertRefused(
            with { put("archive", JsonObject(archive + ("sha256" to JsonPrimitive("abc")))) },
            "压缩包哈希过短",
        )
    }

    @Test
    fun `文件大小必须为正`() {
        for (size in listOf(0L, -1L)) {
            val files = JsonArray(listOf(fileEntry("model", "m.onnx", size = size), fileEntry("tokens", "t.txt")))
            assertRefused(with { put("files", files) }, "大小 $size")
        }
    }

    @Test
    fun `文件列表不能为空 路径与角色都不能重复`() {
        assertRefused(with { put("files", JsonArray(emptyList())) }, "空文件列表")
        val dupPath = JsonArray(listOf(fileEntry("model", "same.bin"), fileEntry("tokens", "same.bin")))
        assertRefused(with { put("files", dupPath) }, "重复路径")
        val dupRole = JsonArray(listOf(fileEntry("model", "a.bin"), fileEntry("model", "b.bin")))
        assertRefused(with { put("files", dupRole) }, "重复角色")
    }

    @Test
    fun `语言列表必须非空 唯一 且符合合同里的语言标签格式`() {
        assertRefused(with { put("languages", JsonArray(emptyList())) }, "空语言列表")
        assertRefused(with { put("languages", JsonArray(listOf(JsonPrimitive("zh"), JsonPrimitive("zh")))) }, "重复语言")
        for (tag in listOf("ZH", "zh_CN", "z", "chinese", "zh-", "")) {
            assertRefused(with { put("languages", JsonArray(listOf(JsonPrimitive(tag)))) }, "语言标签 '$tag'")
        }
        // 合同允许带地区子标签
        val ok = ModelManifest.parse(with { put("languages", JsonArray(listOf(JsonPrimitive("zh-CN"), JsonPrimitive("yue")))) })
        assertEquals(listOf("zh-CN", "yue"), ok.languages)
    }

    @Test
    fun `采样率必须落在合同允许的范围内`() {
        for (rate in listOf(0, 7999, 192001, -16000)) {
            assertRefused(with { put("sample_rate_hz", rate) }, "采样率 $rate")
        }
    }

    @Test
    fun `时长上限为空表示没有上限 但不能为零或负数`() {
        val noLimit = ModelManifest.parse(with { put("limits", buildJsonObject { put("max_audio_duration_ms", JsonPrimitive(null as String?)) }) })
        assertEquals(null, noLimit.limits.maxAudioDurationMs)
        for (limit in listOf(0, -5)) {
            assertRefused(with { put("limits", buildJsonObject { put("max_audio_duration_ms", limit) }) }, "上限 $limit")
        }
    }

    @Test
    fun `引擎版本范围只接受精确版本 因为这是锁定依赖`() {
        assertEquals("==2.0.1", ModelManifest.parse(baseline().toString()).engine.versionRange)
        for (range in listOf(">=2.0.0", "2.0.1", "==2.0.*", "~2.0", "", "==")) {
            val engine = buildJsonObject { put("name", "sherpa-onnx"); put("version_range", range) }
            assertRefused(with { put("engine", engine) }, "版本范围 '$range'")
        }
    }

    @Test
    fun `引擎名过长被拒绝 因为任务句柄的 provider_id 受合同长度限制`() {
        val engine = buildJsonObject { put("name", "x".repeat(65)); put("version_range", "==2.0.1") }

        assertRefused(with { put("engine", engine) }, "引擎名 65 个字符")
    }

    @Test
    fun `空的或带控制字符的标识被拒绝`() {
        for (key in listOf("model_id", "model_version", "family")) {
            assertRefused(with { put(key, "") }, "$key 为空")
            assertRefused(with { put(key, "a\nb") }, "$key 含换行")
        }
        assertRefused(with { put("model_id", "Has Space") }, "model_id 含空格与大写")
    }

    @Test
    fun `拒绝原因不会泄露文件内容 只带有清单本身的信息`() {
        val e = assertRefused(with { put("manifest_version", 7) }, "版本")
        assertFalse(e.message.orEmpty().isEmpty())
        assertTrue(e.message!!.length < 400)
    }
}
