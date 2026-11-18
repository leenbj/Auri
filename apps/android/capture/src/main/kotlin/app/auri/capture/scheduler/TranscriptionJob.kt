package app.auri.capture.scheduler

import app.auri.capture.contract.ErrorCode
import app.auri.capture.machine.TranscriptionMachine
import app.auri.capture.machine.TranscriptionState
import java.time.Instant

/** 任务从哪来：[INITIAL] 是录完之后的第一次转录；[RETRANSCRIBE] 是用户明确要求重转（一次重转录是一个新任务）。 */
enum class JobKind { INITIAL, RETRANSCRIBE }

/**
 * 为什么此刻不能转（WAITING_RESOURCES 的原因）。不是合同枚举：合同只定义状态与事件，原因文字是应用自己的事。
 * 同时出现多个原因时按这里的顺序取第一个（见 [ResourcePolicy]）：录音优先，其次是会伤到手机的，再其次是省电，最后是空间。
 */
enum class ResourceReason { RECORDING_ACTIVE, THERMAL, BATTERY_SAVER, LOW_MEMORY, LOW_STORAGE }

/** 任务在等什么：界面据此告诉用户原因。 */
sealed interface WaitReason {
    /** 等模型。[code] 是 MODEL_NOT_INSTALLED（没装）或 MODEL_INTEGRITY_FAILED（装了但校验没过）。 */
    data class Model(val code: ErrorCode) : WaitReason

    data class Resources(val reason: ResourceReason) : WaitReason
}

/**
 * 一个转录任务。持久化的就是这些字段（SQLite 任务库逐字段存），所以进程重启之后任务与它的等待原因、
 * 已经试了几次、重试时间都还在。状态变化只经 [TranscriptionMachine]；这里不含正文、音频或任何设备标识。
 */
data class TranscriptionJob(
    val jobId: String,
    val recordId: String,
    val kind: JobKind,
    /** 创建顺序，单调递增：同类任务按它先到先做。 */
    val seq: Long,
    val state: TranscriptionState,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** 已经开始跑的次数（RUN_STARTED 时先落盘再跑：转到一半进程被杀也算一次，崩溃循环因此有上限）。 */
    val attempts: Int = 0,
    /** 本次运行开始时已有的 Revision 数，用来识别无法归属的旧版发布。 */
    val baseRevisions: Int? = null,
    /** 只在 WAITING_MODEL / WAITING_RESOURCES 时有值。 */
    val wait: WaitReason? = null,
    /** 等资源时已经重查过几次。 */
    val waitChecks: Int = 0,
    /** 重查次数用完了仍然不行：不再自己重试，界面提示用户处理（用户点“重新检查”或条件发生变化才会继续）。 */
    val needsUser: Boolean = false,
    /** RETRY_SCHEDULED 的重试时间。 */
    val retryAt: Instant? = null,
    /** 最近一次失败的原因（FAILED 时是终局原因）；null 表示原因未知。 */
    val failure: ErrorCode? = null,
    /** SUCCEEDED 时产生的 Revision 编号。 */
    val resultRevision: Int? = null,
    /** SUCCEEDED 时写进 Revision 的模型（Revision 必须写明用了哪个模型）。 */
    val modelId: String? = null,
    val modelVersion: String? = null,
) {
    val isTerminal: Boolean get() = state in TranscriptionMachine.terminalStates
}
