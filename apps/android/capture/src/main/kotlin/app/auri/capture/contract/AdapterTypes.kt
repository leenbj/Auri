package app.auri.capture.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// 本文件是 contracts/schemas/adapter.schema.json（合同 1.1）八个类型的 Kotlin 镜像：字段名、取值、约束一一对应。
// 这些类型在进程内传递，不带 schema_version，也不是网络消息；序列化只用于样本对表与验收输出。
// “原生层与共享层都要校验，不信任另一端已经检查”：约束写在构造函数里，构造不出非法值；
// JSON Schema 表达不了的规则（SR-02/03/04）在 ContractRules 里，和 tests/contract/semantic_rules.py 同一套规则。

/** 适配器八个类型的共同父接口，只为让测试与 [ContractRules] 统一处理。 */
sealed interface AdapterMessage

/** 解码严格：多余字段拒绝（additionalProperties: false）；“必填但可为 null”的字段必须显式写出 null。 */
object ContractJson {
    val json: Json = Json {
        ignoreUnknownKeys = false
        explicitNulls = true
        encodeDefaults = true
        isLenient = false
        coerceInputValues = false
        allowSpecialFloatingPointValues = false
    }

    inline fun <reified T : AdapterMessage> decode(text: String): T = json.decodeFromString(text)

    fun encode(message: AdapterMessage): String = when (message) {
        is NormalizedControlEvent -> json.encodeToString(NormalizedControlEvent.serializer(), message)
        is CaptureEligibility -> json.encodeToString(CaptureEligibility.serializer(), message)
        is PreparedInput -> json.encodeToString(PreparedInput.serializer(), message)
        is CaptureHandle -> json.encodeToString(CaptureHandle.serializer(), message)
        is RouteChangeEvent -> json.encodeToString(RouteChangeEvent.serializer(), message)
        is RecordingResult -> json.encodeToString(RecordingResult.serializer(), message)
        is AsrCapabilities -> json.encodeToString(AsrCapabilities.serializer(), message)
        is AsrTaskHandle -> json.encodeToString(AsrTaskHandle.serializer(), message)
    }
}

private val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
private val LANGUAGE_PATTERN = Regex("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$")

/** common.schema.json#/$defs/uuid：小写 UUID 字符串。 */
fun isContractUuid(value: String): Boolean = UUID_PATTERN.matches(value)

internal fun requireUuid(value: String, field: String) = require(isContractUuid(value)) { "$field 必须是小写 UUID" }

internal fun requireNonNegative(value: Long, field: String) = require(value >= 0) { "$field 不能为负数" }

internal fun requireMaxLength(value: String?, max: Int, field: String) =
    require(value == null || value.length <= max) { "$field 超过 $max 个字符" }

/** normalized_control_event：一个平台事件归一化后的业务命令。只描述平台实际给出的事件，不猜测物理手势，也不猜测左右耳（FR-HS-001）。 */
@Serializable
data class NormalizedControlEvent(
    @SerialName("event_id") val eventId: String,
    val source: ControlSource,
    @SerialName("platform_event") val platformEvent: PlatformEvent,
    val command: BusinessCommand,
    @SerialName("monotonic_ns") val monotonicNs: Long,
    /** 命令进入串行调度器的单调递增序号。 */
    val sequence: Long,
    /** 做出这次映射的 Headset Profile；屏幕操作不经过档案，为 null。 */
    @SerialName("profile_id") val profileId: String?,
    @SerialName("profile_version") val profileVersion: Int?,
    /** 可获得时关联的平台原始事件标识，用于去重；没有则为 null。 */
    @SerialName("raw_event_id") val rawEventId: String?,
) : AdapterMessage {
    init {
        requireUuid(eventId, "event_id")
        requireNonNegative(monotonicNs, "monotonic_ns")
        requireNonNegative(sequence, "sequence")
        requireMaxLength(profileId, 100, "profile_id")
        require(profileVersion == null || profileVersion >= 1) { "profile_version 至少为 1" }
        requireMaxLength(rawEventId, 200, "raw_event_id")
        if (platformEvent == PlatformEvent.VOLUME_CHANGE) {
            require(command == BusinessCommand.STOP || command == BusinessCommand.IGNORE) { "音量变化只能用来停止，不能用来开始" }
            require(source == ControlSource.VOLUME_GESTURE) { "音量变化的来源只能是 volume_gesture" }
        }
        if (platformEvent == PlatformEvent.SCREEN_BUTTON) {
            require(source == ControlSource.SCREEN_CONTROL) { "屏幕按钮的来源只能是 screen_control" }
        }
        if (source != ControlSource.SCREEN_CONTROL) {
            require(!profileId.isNullOrEmpty() && profileVersion != null) { "经过耳机档案映射的事件必须写明档案与版本" }
        }
    }
}

/** capture_eligibility：当前能否开始录音。未知必须显式表达，未知不等于允许。 */
@Serializable
data class CaptureEligibility(
    val permission: PermissionState,
    val lifecycle: LifecycleState,
    @SerialName("can_start") val canStart: CanStart,
    /** 不能开始时的原因；能开始时为 null。 */
    val reason: ErrorCode?,
) : AdapterMessage {
    init {
        if (canStart == CanStart.YES) {
            require(permission == PermissionState.GRANTED) { "权限不是 granted 时不能写 can_start=yes" }
            require(reason == null) { "能开始时不写原因" }
        }
        if (canStart == CanStart.NO) require(reason != null) { "can_start=no 必须带错误码原因" }
    }
}

/** prepared_input.requested_device */
@Serializable
data class RequestedDevice(
    @SerialName("display_name") val displayName: String?,
    /** 平台给出的设备引用；显示名称和 MAC 地址都不是跨平台可靠身份。 */
    @SerialName("platform_device_ref") val platformDeviceRef: String?,
) {
    init {
        requireMaxLength(displayName, 200, "display_name")
        requireMaxLength(platformDeviceRef, 200, "platform_device_ref")
    }
}

/** prepared_input：向系统请求蓝牙输入后得到的准备句柄。它只说明请求发出去了，不代表实际来源已验证；验证结论只来自 RouteChangeEvent。 */
@Serializable
data class PreparedInput(
    @SerialName("operation_id") val operationId: String,
    /** 本次准备操作的代数；已取消或更旧代数的回调不得生效。 */
    val generation: Long,
    @SerialName("requested_device") val requestedDevice: RequestedDevice,
    @SerialName("request_status") val requestStatus: RequestStatus,
) : AdapterMessage {
    init {
        requireUuid(operationId, "operation_id")
        requireNonNegative(generation, "generation")
    }
}

/** capture_handle：一次已开闸的采集的句柄。只有 generation 与 verified_route_epoch 都匹配时才允许写入音频。 */
@Serializable
data class CaptureHandle(
    @SerialName("capture_session_id") val captureSessionId: String,
    val generation: Long,
    /** 路由被确认时的 route_epoch；路由一变化，epoch 递增，这个句柄随即失效。 */
    @SerialName("verified_route_epoch") val verifiedRouteEpoch: Long,
    @SerialName("gate_state") val gateState: GateState,
) : AdapterMessage {
    init {
        requireUuid(captureSessionId, "capture_session_id")
        requireNonNegative(generation, "generation")
        requireNonNegative(verifiedRouteEpoch, "verified_route_epoch")
    }

    fun closed(): CaptureHandle = copy(gateState = GateState.CLOSED)
}

/** route_change_event.observed_input */
@Serializable
data class ObservedInput(
    @SerialName("route_type") val routeType: ObservedInputRouteType,
    @SerialName("display_name") val displayName: String?,
) {
    init {
        requireMaxLength(displayName, 200, "display_name")
    }
}

/**
 * route_change_event：系统观测到的一次输入路由变化，即 Route Evidence 的来源。
 * 记录系统观测，不伪造固件或设备标识。验证状态不是 verified 时必须立即关闸。
 */
@Serializable
data class RouteChangeEvent(
    @SerialName("capture_session_id") val captureSessionId: String,
    val epoch: Long,
    @SerialName("observed_input") val observedInput: ObservedInput,
    @SerialName("observed_at_monotonic_ns") val observedAtMonotonicNs: Long,
    @SerialName("verification_state") val verificationState: VerificationState,
) : AdapterMessage {
    init {
        requireUuid(captureSessionId, "capture_session_id")
        requireNonNegative(epoch, "epoch")
        requireNonNegative(observedAtMonotonicNs, "observed_at_monotonic_ns")
        if (verificationState == VerificationState.VERIFIED) {
            require(observedInput.routeType.isBluetooth) { "只有蓝牙输入才可能是 verified；手机内置麦克风永远不是" }
        }
    }
}

/** recording_result.chunks[] */
@Serializable
data class AudioChunk(
    @SerialName("chunk_id") val chunkId: String,
    val sequence: Long,
    val sha256: String,
    /** 写入这段音频时已确认的 route_epoch；未确认路由的帧不得进入任何 chunk。 */
    @SerialName("route_epoch") val routeEpoch: Long,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
) {
    init {
        requireUuid(chunkId, "chunk_id")
        requireNonNegative(sequence, "sequence")
        require(SHA256_PATTERN.matches(sha256)) { "sha256 必须是小写十六进制的 SHA-256" }
        requireNonNegative(routeEpoch, "route_epoch")
        requireNonNegative(startMs, "start_ms")
        requireNonNegative(endMs, "end_ms")
    }
}

/** Capture Session 原始时间线上的一段，毫秒。 */
@Serializable
data class TimeRange(
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
) {
    init {
        requireNonNegative(startMs, "start_ms")
        requireNonNegative(endMs, "end_ms")
    }
}

/** recording_result.discarded_ranges[]：没有保留的时间段及原因。 */
@Serializable
data class DiscardedRange(
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    val reason: DiscardReason,
) {
    init {
        requireNonNegative(startMs, "start_ms")
        requireNonNegative(endMs, "end_ms")
    }
}

/** recording_result：一次路由已确认的 Capture Session 封存后的结果，对应一个 Record。整段无人说话时 chunks 和 verified_ranges 为空（ADR-0016）。 */
@Serializable
data class RecordingResult(
    @SerialName("record_id") val recordId: String,
    @SerialName("capture_session_id") val captureSessionId: String,
    val status: CaptureStatus,
    @SerialName("end_reason") val endReason: EndReason,
    @SerialName("capture_duration_ms") val captureDurationMs: Long,
    /** 等于各 verified_ranges 时长之和。 */
    @SerialName("speech_duration_ms") val speechDurationMs: Long,
    val chunks: List<AudioChunk>,
    /** 保留下来的音频在原始时间线上的位置：路由已确认且有人声。 */
    @SerialName("verified_ranges") val verifiedRanges: List<TimeRange>,
    @SerialName("discarded_ranges") val discardedRanges: List<DiscardedRange>,
) : AdapterMessage {
    init {
        requireUuid(recordId, "record_id")
        requireUuid(captureSessionId, "capture_session_id")
        requireNonNegative(captureDurationMs, "capture_duration_ms")
        requireNonNegative(speechDurationMs, "speech_duration_ms")
        // 被截断的录音不得冒充 complete；正常结束的也不得标成 partial
        require(status == if (endReason.truncated) CaptureStatus.PARTIAL else CaptureStatus.COMPLETE) {
            "status 必须与 end_reason 一致：被截断（route_lost 等四种）是 partial，其余是 complete"
        }
    }
}

/** asr_capabilities.formats[] */
@Serializable
data class AsrFormat(
    val encoding: AudioEncoding,
    @SerialName("sample_rate_hz") val sampleRateHz: Int,
    val channels: Int,
) {
    init {
        require(sampleRateHz in 8000..192000) { "sample_rate_hz 必须在 8000..192000" }
        require(channels in 1..2) { "channels 必须是 1 或 2" }
    }
}

/** asr_capabilities.limits */
@Serializable
data class AsrLimits(
    /** 单次任务能接受的最长音频；没有限制为 null。 */
    @SerialName("max_audio_duration_ms") val maxAudioDurationMs: Long?,
    @SerialName("max_concurrent_jobs") val maxConcurrentJobs: Int,
) {
    init {
        require(maxAudioDurationMs == null || maxAudioDurationMs >= 1) { "max_audio_duration_ms 至少为 1" }
        require(maxConcurrentJobs >= 1) { "max_concurrent_jobs 至少为 1" }
    }
}

/** asr_capabilities：一个转录引擎声明的能力（FR-ASR-001）。V1 只有本地转录，execution_location 只能是 on_device（SR-05）。 */
@Serializable
data class AsrCapabilities(
    @SerialName("execution_location") val executionLocation: ExecutionLocation,
    val engine: String,
    @SerialName("model_id") val modelId: String,
    @SerialName("model_version") val modelVersion: String,
    val languages: List<String>,
    /** 能否转录已经录好的音频。不能的引擎不得通过重新打开麦克风来代替。 */
    @SerialName("accepts_recorded_file") val acceptsRecordedFile: Boolean,
    val formats: List<AsrFormat>,
    val streaming: Boolean,
    @SerialName("timestamp_granularity") val timestampGranularity: TimestampGranularity,
    val limits: AsrLimits,
    @SerialName("cancel_support") val cancelSupport: CancelSupport,
) : AdapterMessage {
    init {
        require(engine.length in 1..100) { "engine 长度必须在 1..100" }
        require(modelId.length in 1..200) { "model_id 长度必须在 1..200" }
        require(modelVersion.length in 1..200) { "model_version 长度必须在 1..200" }
        require(languages.isNotEmpty()) { "languages 不能为空" }
        require(languages.toSet().size == languages.size) { "languages 不能重复" }
        require(languages.all { LANGUAGE_PATTERN.matches(it) }) { "languages 里有不合格的语言标签" }
        require(formats.isNotEmpty()) { "formats 不能为空" }
    }
}

/** asr_task_handle：一个已提交的转录任务的句柄。一次重转录是一个新的 job_id。 */
@Serializable
data class AsrTaskHandle(
    @SerialName("job_id") val jobId: String,
    @SerialName("provider_id") val providerId: String,
    /** 远端服务返回的任务标识，与内部 job_id 分离；本地转录没有，必须显式写 null。 */
    @SerialName("provider_job_id") val providerJobId: String?,
    /** 提交任务时所用配置快照的版本。 */
    @SerialName("config_revision") val configRevision: Int,
) : AdapterMessage {
    init {
        requireUuid(jobId, "job_id")
        require(providerId.length in 1..100) { "provider_id 长度必须在 1..100" }
        requireMaxLength(providerJobId, 200, "provider_job_id")
        require(configRevision >= 1) { "config_revision 至少为 1" }
    }
}

/**
 * JSON Schema 表达不了、必须由实现另行校验的规则，对应 tests/contract/semantic_rules.py 里适配器类型那几条
 * （contracts/README.md 的规则清单）。只对已通过结构校验的值调用；返回被违反的规则编号，已排序去重。
 */
object ContractRules {
    fun violations(message: AdapterMessage): List<String> = when (message) {
        is RecordingResult -> rangeViolations(message).sorted()
        is NormalizedControlEvent, is CaptureEligibility, is PreparedInput, is CaptureHandle,
        is RouteChangeEvent, is AsrCapabilities, is AsrTaskHandle -> emptyList()
    }

    /** SR-02：保留的时间段升序不重叠；SR-03：不超出采集时长；SR-04：时长之和等于语音时长。 */
    private fun rangeViolations(result: RecordingResult): Set<String> {
        val ranges = result.verifiedRanges
        val violations = mutableSetOf<String>()
        val ordered = ranges.all { it.startMs < it.endMs } &&
            ranges.zipWithNext().all { (earlier, later) -> earlier.endMs <= later.startMs }
        if (!ordered) violations += "SR-02"
        if (ranges.any { it.endMs > result.captureDurationMs }) violations += "SR-03"
        if (ranges.sumOf { it.endMs - it.startMs } != result.speechDurationMs) violations += "SR-04"
        return violations
    }
}
