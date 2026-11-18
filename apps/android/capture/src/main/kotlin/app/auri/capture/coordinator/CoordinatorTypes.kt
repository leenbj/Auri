package app.auri.capture.coordinator

import app.auri.capture.adapter.PcmFormat
import app.auri.capture.adapter.SealedAudio
import app.auri.capture.contract.CaptureState
import app.auri.capture.contract.ErrorCode
import app.auri.capture.contract.GateState
import app.auri.capture.contract.ObservedInput
import app.auri.capture.contract.RecordingResult
import app.auri.capture.contract.RouteChangeEvent
import app.auri.capture.machine.CaptureEvent
import app.auri.capture.machine.Outcome
import java.time.Instant

/** 时间与标识的来源，注入以便测试用虚拟时间和确定的标识。 */
interface Clock {
    /** 单调时钟（纳秒），只用于排序和算间隔。 */
    fun monotonicNs(): Long

    /** 墙上时间，只用于记录 started_at。 */
    fun now(): Instant
}

fun interface IdGenerator {
    /** 小写 UUID 字符串（contracts common.schema.json#/$defs/uuid）。 */
    fun newUuid(): String
}

/**
 * 协调器的参数。默认值来自 Ticket 16 的约定：路由确认最多等 5 秒；时长上限 30 分钟（Ticket 18 才做可配置与提前提醒）；
 * Quarantine 最多留 10 秒音频，超出就丢最旧的；16 kHz 单声道 16 位 PCM。
 */
data class CaptureConfig(
    val routeTimeoutMs: Long = 5_000,
    val maxDurationMs: Long = 30L * 60_000,
    val quarantineMaxMs: Long = 10_000,
    val format: PcmFormat = PcmFormat(sampleRateHz = 16_000),
) {
    init {
        require(routeTimeoutMs > 0 && maxDurationMs > 0 && quarantineMaxMs > 0) { "时间参数必须为正" }
    }
}

/** 协调器的产出：一个路由已确认的 Capture Session 封存后的结果，或者封存失败的通知。接收方必须不阻塞（只入队）。 */
interface CaptureOutput {
    fun recordingSealed(sealed: SealedRecording)

    /**
     * 路由已确认、开始录了，但音频没能保存（封存失败）。录音丢了必须让用户知道，所以单独通知，不悄悄吞掉。
     * [recordId] 是那次录音本来的标识；存储已被要求清理残留，不会留下半截文件。
     */
    fun recordingLost(recordId: String, reason: ErrorCode)
}

/**
 * 一次封存的完整信息。[result] 是合同里的 RecordingResult；[evidence] 是确认路由的那次系统观测，[routeEvidenceId] 是它的标识
 * （之后 Revision 的 capture.route_evidence_id）；[audio] 在没有保留任何音频时为 null。
 */
class SealedRecording(
    val result: RecordingResult,
    val evidence: RouteChangeEvent,
    val routeEvidenceId: String,
    val startedAt: Instant,
    val audio: SealedAudio?,
)

/** 一次输入被处理的原因，供诊断与测试区分“为什么被拒绝/忽略”。不含任何正文、音频或设备标识。 */
enum class Cause {
    /** 按转移表处理（转移、忽略、或表里没有这个组合而被拒绝）。 */
    MACHINE,

    /** 没有当前会话（已取消、已结束、从未开始）：迟到的旧回调。 */
    STALE_SESSION,

    /** 回调属于更旧的 generation：另一个会话已经在进行。 */
    STALE_GENERATION,

    /** route_epoch 不比当前会话已见过的更新。 */
    STALE_EPOCH,

    /** 命令发出的时间落在上一次封存期间：过时的命令，封存之后不得补执行。 */
    STALE_COMMAND,

    /** 控制适配器提供不了任何控制来源。 */
    CONTROL_NOT_AVAILABLE,

    /** 开始前的资格检查没通过（权限、前台条件）。 */
    NOT_ELIGIBLE,

    /** 存储写入失败。 */
    STORAGE_FAILURE,

    /** 命令本身就是 IGNORE（平台事件归一化后确认与录音无关）。 */
    IGNORE_COMMAND,

    /** 适配器抛出了没有预料到的异常：这条输入被放弃，协调器继续处理后面的输入，不会因此停摆。 */
    INTERNAL_ERROR,
}

/** 协调器处理一个输入的结果。[trigger] 为 null 表示这个输入不对应任何状态机事件（例如 IGNORE 命令）。 */
data class CaptureDecision(
    val trigger: CaptureEvent?,
    val outcome: Outcome,
    val stateBefore: CaptureState,
    val stateAfter: CaptureState,
    val cause: Cause,
    val errorCode: ErrorCode? = null,
)

/** 当前会话的公开信息。 */
data class SessionInfo(
    val captureSessionId: String,
    val generation: Long,
    val verifiedRouteEpoch: Long?,
    val gate: GateState,
)

/** 帧的去向计数。只有数量，没有内容。 */
data class FrameCounters(
    /** 当前 Quarantine 里的采样数（只在内存里）。 */
    val quarantinedSamples: Long = 0,
    /** 累计被丢弃的 Quarantine 采样数（路由确认后丢弃，或确认失败丢弃，或超出上限丢弃）。 */
    val discardedQuarantineSamples: Long = 0,
    /** 累计写入存储的采样数。 */
    val writtenSamples: Long = 0,
    /** 因旧会话/旧 generation 被丢弃的帧批数。 */
    val droppedStale: Long = 0,
    /** 因闸门已关或 route_epoch 不符被丢弃的帧批数。 */
    val droppedGate: Long = 0,
)

/**
 * 协调器对外发布的快照（一个 StateFlow）。界面的“采集状态”和“实际输入来源”来自这里：
 * [actualInput] 只来自系统的观测，没有观测到就是 null；“已配对/已连接”不会出现在这里。
 */
data class CaptureSnapshot(
    val state: CaptureState,
    /** 状态为 BLOCKED 时的原因，其余状态为 null。 */
    val blockedReason: ErrorCode?,
    val session: SessionInfo?,
    /** 当前会话里系统最近一次观测到的实际输入；没有会话或还没观测到时为 null。 */
    val actualInput: ObservedInput?,
    val counters: FrameCounters,
    /** 每次发布递增，便于测试等待“这次变化已发布”。 */
    val version: Long,
    /** 最近一次因时长上限停止时的上限，毫秒；下一次开始时清除。 */
    val durationLimitMs: Long? = null,
)
