package app.auri.asr.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 合同 1.1 里本模块会产生的那部分类型。字段名、取值与 contracts/schemas/ 逐字一致；
 * 只放得进合同允许的取值（例如执行位置只有 on_device），写不出云端取值（ADR-0019、SR-05）。
 * 合同里的错误码只列出本模块真的会发出的几个。
 */
enum class ErrorCode {
    MODEL_NOT_INSTALLED,
    MODEL_INTEGRITY_FAILED,
    ASR_RESOURCE_LIMIT,
    ASR_NO_SPEECH,
}

@Serializable
enum class ExecutionLocation { @SerialName("on_device") ON_DEVICE }

@Serializable
enum class AudioEncoding {
    @SerialName("pcm_s16le") PCM_S16LE,
}

@Serializable
enum class TimestampGranularity {
    @SerialName("none") NONE,
}

@Serializable
enum class CancelSupport {
    @SerialName("none") NONE,
}

@Serializable
enum class RevisionSource { @SerialName("asr") ASR }

@Serializable
enum class ReviewState { @SerialName("not_reviewed") NOT_REVIEWED }

@Serializable
data class AsrFormat(
    val encoding: AudioEncoding,
    @SerialName("sample_rate_hz") val sampleRateHz: Int,
    val channels: Int,
)

@Serializable
data class AsrLimits(
    @SerialName("max_audio_duration_ms") val maxAudioDurationMs: Long?,
    @SerialName("max_concurrent_jobs") val maxConcurrentJobs: Int,
)

/** adapter.schema.json#/$defs/asr_capabilities（FR-ASR-001）。 */
@Serializable
data class AsrCapabilities(
    @SerialName("execution_location") val executionLocation: ExecutionLocation,
    val engine: String,
    @SerialName("model_id") val modelId: String,
    @SerialName("model_version") val modelVersion: String,
    val languages: List<String>,
    @SerialName("accepts_recorded_file") val acceptsRecordedFile: Boolean,
    val formats: List<AsrFormat>,
    val streaming: Boolean,
    @SerialName("timestamp_granularity") val timestampGranularity: TimestampGranularity,
    val limits: AsrLimits,
    @SerialName("cancel_support") val cancelSupport: CancelSupport,
) {
    companion object {
        /** 录音常见的输入采样率：HFP 窄带 8k、宽带 16k、手机侧 44.1k 与 48k；其余 8k–192k 也能转，但不在此声明。 */
        val DECLARED_INPUT_RATES_HZ = listOf(8000, 16000, 44100, 48000)
    }
}

/** adapter.schema.json#/$defs/asr_task_handle。本地转录没有远端任务标识，provider_job_id 必须显式为 null。 */
@Serializable
data class AsrTaskHandle(
    @SerialName("job_id") val jobId: String,
    @SerialName("provider_id") val providerId: String,
    @SerialName("provider_job_id") val providerJobId: String?,
    @SerialName("config_revision") val configRevision: Int,
)

@Serializable
data class TextSegment(
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    val text: String,
)

/** revision.schema.json#/$defs/content，来源固定为 asr、位置固定为 on_device。 */
@Serializable
data class RevisionContent(
    val text: String,
    @SerialName("text_sha256") val textSha256: String,
    val source: RevisionSource,
    val language: String,
    @SerialName("review_state") val reviewState: ReviewState,
    @SerialName("execution_location") val executionLocation: ExecutionLocation,
    val engine: String,
    @SerialName("model_id") val modelId: String,
    @SerialName("model_version") val modelVersion: String,
    val confidence: Double?,
    val segments: List<TextSegment>,
)

/** error.schema.json：不含密钥、正文或堆栈。 */
@Serializable
data class AsrError(
    @SerialName("schema_version") val schemaVersion: String,
    val code: ErrorCode,
    val message: String,
    val retryable: Boolean,
    @SerialName("required_action") val requiredAction: String?,
    @SerialName("operation_id") val operationId: String?,
)

object ContractJson {
    const val SCHEMA_VERSION = "1.1"

    @PublishedApi
    internal val json = Json {
        encodeDefaults = true
        explicitNulls = true // 合同要求 provider_job_id、confidence 等显式写 null，不能省略
        prettyPrint = true
    }

    inline fun <reified T> encode(value: T): String = json.encodeToString(value)
}
