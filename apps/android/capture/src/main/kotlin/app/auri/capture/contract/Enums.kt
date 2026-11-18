package app.auri.capture.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 本文件的枚举名与取值逐个对应 contracts/schemas/common.schema.json 与 adapter.schema.json。
// 线上取值写在 @SerialName 里（grep 得到）；取值全大写的枚举（命令、状态、错误码）常量名就是线上取值。
// 合同是冻结的（1.1）：要改取值先走 contracts/README.md 的“修改规则”，不在这里改。

/** common.schema.json#/$defs/business_command */
@Serializable
enum class BusinessCommand { START, STOP, TOGGLE, IGNORE }

/** adapter.schema.json normalized_control_event.source */
@Serializable
enum class ControlSource {
    @SerialName("headset_remote") HEADSET_REMOTE,
    @SerialName("unknown_remote") UNKNOWN_REMOTE,
    @SerialName("volume_gesture") VOLUME_GESTURE,
    @SerialName("screen_control") SCREEN_CONTROL,
}

/** common.schema.json#/$defs/platform_event：平台实际给出的事件，不存在 doubleTap 这类原始手势。 */
@Serializable
enum class PlatformEvent {
    @SerialName("play") PLAY,
    @SerialName("pause") PAUSE,
    @SerialName("toggle") TOGGLE,
    @SerialName("next") NEXT,
    @SerialName("previous") PREVIOUS,
    @SerialName("volume_change") VOLUME_CHANGE,
    @SerialName("screen_button") SCREEN_BUTTON,
}

/** common.schema.json#/$defs/capture_state */
@Serializable
enum class CaptureState { DISARMED, ARMED, ROUTING, RECORDING, FINALIZING, BLOCKED }

/** common.schema.json#/$defs/error_code：PRD 第 14 章的错误码清单（全集，包括 V1 手机端不会产生的云端码）。 */
@Serializable
enum class ErrorCode {
    PERMISSION_MIC_DENIED,
    BACKGROUND_START_DENIED,
    CONTROL_NOT_AVAILABLE,
    INPUT_DEVICE_MISSING,
    INPUT_ROUTE_UNVERIFIED,
    INPUT_ROUTE_CHANGED,
    AUDIO_INTERRUPTED,
    STORAGE_EXHAUSTED,
    MODEL_NOT_INSTALLED,
    MODEL_INTEGRITY_FAILED,
    ASR_RESOURCE_LIMIT,
    CLOUD_CONSENT_REQUIRED,
    CLOUD_AUTH_FAILED,
    CLOUD_RATE_LIMITED,
    CLOUD_RESULT_UNCERTAIN,
    ASR_NO_SPEECH,
    GATEWAY_UNREACHABLE,
    GATEWAY_AUTH_REVOKED,
    GATEWAY_AUTH_INVALID,
    GATEWAY_KEY_MISMATCH,
    PAIRING_CODE_INVALID,
    REQUEST_INVALID,
    RECORD_VERSION_CONFLICT,
    RECORD_NOT_ORIGIN_DEVICE,
    RECORD_DELETED,
    MCP_VERSION_UNSUPPORTED,
    DELIVERY_LEASE_EXPIRED,
}

/** capture_eligibility.permission */
@Serializable
enum class PermissionState {
    @SerialName("granted") GRANTED,
    @SerialName("denied") DENIED,
    @SerialName("undetermined") UNDETERMINED,
    @SerialName("restricted") RESTRICTED,
    @SerialName("unknown") UNKNOWN,
}

/** capture_eligibility.lifecycle */
@Serializable
enum class LifecycleState {
    @SerialName("foreground") FOREGROUND,
    @SerialName("background") BACKGROUND,
    @SerialName("locked") LOCKED,
    @SerialName("unknown") UNKNOWN,
}

/** capture_eligibility.can_start：未知必须显式表达，未知不等于允许。 */
@Serializable
enum class CanStart {
    @SerialName("yes") YES,
    @SerialName("no") NO,
    @SerialName("unknown") UNKNOWN,
}

/** prepared_input.request_status：只说明请求发出去了，不代表实际来源已验证。 */
@Serializable
enum class RequestStatus {
    @SerialName("requested") REQUESTED,
    @SerialName("accepted_by_system") ACCEPTED_BY_SYSTEM,
    @SerialName("rejected_by_system") REJECTED_BY_SYSTEM,
    @SerialName("unknown") UNKNOWN,
}

/** capture_handle.gate_state */
@Serializable
enum class GateState {
    @SerialName("open") OPEN,
    @SerialName("closed") CLOSED,
}

/** route_change_event.verification_state：mismatch = 实际输入是别的来源；unknown = 无法确认。 */
@Serializable
enum class VerificationState {
    @SerialName("verified") VERIFIED,
    @SerialName("mismatch") MISMATCH,
    @SerialName("unknown") UNKNOWN,
}

/** common.schema.json#/$defs/observed_input_route_type：前三个是蓝牙输入（= input_route_type），其余都不满足严格蓝牙输入。 */
@Serializable
enum class ObservedInputRouteType(val isBluetooth: Boolean) {
    @SerialName("bluetooth_hfp") BLUETOOTH_HFP(true),
    @SerialName("bluetooth_le_audio") BLUETOOTH_LE_AUDIO(true),
    @SerialName("bluetooth_unknown_profile") BLUETOOTH_UNKNOWN_PROFILE(true),
    @SerialName("built_in_mic") BUILT_IN_MIC(false),
    @SerialName("wired_headset") WIRED_HEADSET(false),
    @SerialName("usb_audio") USB_AUDIO(false),
    @SerialName("other") OTHER(false),
    @SerialName("unknown") UNKNOWN(false),
}

/** common.schema.json#/$defs/capture_status */
@Serializable
enum class CaptureStatus {
    @SerialName("complete") COMPLETE,
    @SerialName("partial") PARTIAL,
}

/** common.schema.json#/$defs/end_reason：一次 Capture Session 是怎么结束的；后四种表示录音被截断。 */
@Serializable
enum class EndReason(val truncated: Boolean) {
    @SerialName("headset_command") HEADSET_COMMAND(false),
    @SerialName("volume_gesture") VOLUME_GESTURE(false),
    @SerialName("screen_control") SCREEN_CONTROL(false),
    @SerialName("duration_limit") DURATION_LIMIT(false),
    @SerialName("route_lost") ROUTE_LOST(true),
    @SerialName("system_interruption") SYSTEM_INTERRUPTION(true),
    @SerialName("permission_revoked") PERMISSION_REVOKED(true),
    @SerialName("process_terminated") PROCESS_TERMINATED(true),
}

/** recording_result.discarded_ranges[].reason */
@Serializable
enum class DiscardReason {
    @SerialName("silence") SILENCE,
    @SerialName("route_unverified") ROUTE_UNVERIFIED,
}

/** common.schema.json#/$defs/execution_location：V1 只允许 on_device；另两个取值为云端转录保留（ADR-0019），这里不提供，解码云端取值会失败（SR-05）。 */
@Serializable
enum class ExecutionLocation {
    @SerialName("on_device") ON_DEVICE,
}

/** asr_capabilities.formats[].encoding */
@Serializable
enum class AudioEncoding {
    @SerialName("pcm_s16le") PCM_S16LE,
    @SerialName("pcm_f32le") PCM_F32LE,
}

/** asr_capabilities.timestamp_granularity */
@Serializable
enum class TimestampGranularity {
    @SerialName("none") NONE,
    @SerialName("segment") SEGMENT,
    @SerialName("word") WORD,
}

/** asr_capabilities.cancel_support */
@Serializable
enum class CancelSupport {
    @SerialName("none") NONE,
    @SerialName("best_effort") BEST_EFFORT,
    @SerialName("immediate") IMMEDIATE,
}

/** common.schema.json#/$defs/revision_source：文字来自转录还是用户修订（用户修订在后续 Ticket 才会出现）。 */
@Serializable
enum class RevisionSource {
    @SerialName("asr") ASR,
    @SerialName("user_edit") USER_EDIT,
}

/** common.schema.json#/$defs/review_state */
@Serializable
enum class ReviewState {
    @SerialName("not_reviewed") NOT_REVIEWED,
    @SerialName("reviewed") REVIEWED,
}
