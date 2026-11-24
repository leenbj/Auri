package app.auri.asr.contract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 序列化出来的字段名与取值必须与合同逐字一致。
 * 这里钉住形状（缺字段、改名、省略 null 都会失败）；与真实 schema 文件的校验在
 * execution/acceptance/asr-android/test_contract_output.py 里完成。
 */
class ContractJsonTest {
    private fun parse(text: String) = Json.parseToJsonElement(text)

    @Test
    fun `能力声明的字段名与取值与合同一致`() {
        val capabilities = AsrCapabilities(
            executionLocation = ExecutionLocation.ON_DEVICE,
            engine = "sherpa-onnx",
            modelId = "m",
            modelVersion = "v1",
            languages = listOf("zh", "en"),
            acceptsRecordedFile = true,
            formats = listOf(AsrFormat(AudioEncoding.PCM_S16LE, 16000, 1)),
            streaming = false,
            timestampGranularity = TimestampGranularity.NONE,
            limits = AsrLimits(maxAudioDurationMs = 30000, maxConcurrentJobs = 1),
            cancelSupport = CancelSupport.NONE,
        )

        val expected = parse(
            """
            {
              "execution_location": "on_device",
              "engine": "sherpa-onnx",
              "model_id": "m",
              "model_version": "v1",
              "languages": ["zh", "en"],
              "accepts_recorded_file": true,
              "formats": [{ "encoding": "pcm_s16le", "sample_rate_hz": 16000, "channels": 1 }],
              "streaming": false,
              "timestamp_granularity": "none",
              "limits": { "max_audio_duration_ms": 30000, "max_concurrent_jobs": 1 },
              "cancel_support": "none"
            }
            """,
        )
        assertEquals(expected, parse(ContractJson.encode(capabilities)))
    }

    @Test
    fun `没有时长上限时 max_audio_duration_ms 显式写 null`() {
        val limits = parse(ContractJson.encode(AsrLimits(maxAudioDurationMs = null, maxConcurrentJobs = 1))).jsonObject

        assertTrue("合同要求键存在", limits.containsKey("max_audio_duration_ms"))
        assertEquals(JsonNull, limits["max_audio_duration_ms"])
    }

    @Test
    fun `本地任务句柄的 provider_job_id 显式写 null 而不是省略`() {
        val handle = AsrTaskHandle(
            jobId = "7d76cbc4-6c81-551c-807c-14fa4691df3a",
            providerId = "local-sherpa-onnx",
            providerJobId = null,
            configRevision = 1,
        )

        val json = parse(ContractJson.encode(handle)).jsonObject

        assertEquals(setOf("job_id", "provider_id", "provider_job_id", "config_revision"), json.keys)
        assertEquals(JsonNull, json["provider_job_id"])
        assertEquals("7d76cbc4-6c81-551c-807c-14fa4691df3a", json["job_id"]!!.jsonPrimitive.content)
        assertEquals("1", json["config_revision"]!!.jsonPrimitive.content)
    }

    @Test
    fun `修订内容固定为 asr 来源 on_device 位置 未审核 且置信度显式为 null`() {
        val content = RevisionContent(
            text = "你好",
            textSha256 = "a".repeat(64),
            source = RevisionSource.ASR,
            language = "zh",
            reviewState = ReviewState.NOT_REVIEWED,
            executionLocation = ExecutionLocation.ON_DEVICE,
            engine = "sherpa-onnx",
            modelId = "m",
            modelVersion = "v1",
            confidence = null,
            segments = emptyList(),
        )

        val json = parse(ContractJson.encode(content)).jsonObject

        assertEquals(
            setOf(
                "text", "text_sha256", "source", "language", "review_state", "execution_location",
                "engine", "model_id", "model_version", "confidence", "segments",
            ),
            json.keys,
        )
        assertEquals("asr", json["source"]!!.jsonPrimitive.content)
        assertEquals("on_device", json["execution_location"]!!.jsonPrimitive.content)
        assertEquals("not_reviewed", json["review_state"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, json["confidence"])
        assertEquals("[]", json["segments"].toString())
    }

    @Test
    fun `错误结构的字段与合同一致 并且可空字段显式写出`() {
        val error = AsrError(
            schemaVersion = ContractJson.SCHEMA_VERSION,
            code = ErrorCode.MODEL_NOT_INSTALLED,
            message = "没有本地转录模型",
            retryable = true,
            requiredAction = null,
            operationId = null,
        )

        val json = parse(ContractJson.encode(error)).jsonObject

        assertEquals(
            setOf("schema_version", "code", "message", "retryable", "required_action", "operation_id"),
            json.keys,
        )
        assertEquals("1.2", json["schema_version"]!!.jsonPrimitive.content)
        assertEquals("MODEL_NOT_INSTALLED", json["code"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, json["required_action"])
        assertEquals(JsonNull, json["operation_id"])
    }
}
