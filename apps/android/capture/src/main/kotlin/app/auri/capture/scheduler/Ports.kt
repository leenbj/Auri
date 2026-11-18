package app.auri.capture.scheduler

import app.auri.capture.contract.ErrorCode
import app.auri.capture.machine.Outcome
import app.auri.capture.machine.TranscriptionEvent
import app.auri.capture.machine.TranscriptionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

// 调度器碰外界的三个端口（任务库见 JobStore）。生产实现分别在 :app（模型管理器）与 :platform（Android 资源信号），
// 这里只有接口与空实现；调度器的全部规则都能用假实现在 JVM 里测。

/** 本地转录模型此刻能不能用。 */
sealed interface ModelReadiness {
    /**
     * 可用。[generation] 每次激活新模型（包括同一个版本重装）都会变：调度器只靠“值变了”知道要重新试，
     * 所以重装同一个模型也必须发出新值。
     */
    data class Ready(val generation: Long) : ModelReadiness

    /** 不可用：[code] 是 MODEL_NOT_INSTALLED（没装 / 还在下载）或 MODEL_INTEGRITY_FAILED（装了但校验没过）。 */
    data class Missing(val code: ErrorCode) : ModelReadiness
}

interface ModelAvailability {
    val readiness: StateFlow<ModelReadiness>
}

/** 没接模型管理器时的默认：当作随时可用，真正能不能转由转录适配器说了算（它会回 WaitingModel）。 */
object AlwaysReady : ModelAvailability {
    override val readiness: StateFlow<ModelReadiness> = MutableStateFlow(ModelReadiness.Ready(0))
}

sealed interface ResourceStatus {
    data object Ok : ResourceStatus

    data class Low(val reason: ResourceReason) : ResourceStatus
}

/**
 * 手机此刻转得动吗（存储、内存、温度、省电模式、是否正在录音）。
 * [check] 现取现算，可以在任意线程调用；[changes] 在可能改变答案的事情发生时发一个信号（温度回调、省电广播、录音开始或结束），
 * 首个信号表示监听注册完成；调度器收到信号就重新问 [check]。信号可能丢，所以调度器还会按有界的间隔自己重查。
 */
interface ResourceMonitor {
    fun check(): ResourceStatus

    val changes: Flow<Unit>
}

object ResourcesAlwaysOk : ResourceMonitor {
    override fun check(): ResourceStatus = ResourceStatus.Ok

    override val changes: Flow<Unit> = flowOf(Unit)
}

/** 任务库：任务在每次状态变化时落盘，进程重启后据此恢复。生产实现是 `:platform` 的 SQLite，测试用 [InMemoryJobStore]。 */
interface JobStore {
    /** 按 [TranscriptionJob.jobId] 写入（已存在就整行覆盖）。 */
    suspend fun put(job: TranscriptionJob)

    /** 全部任务，按 [TranscriptionJob.seq] 升序。 */
    suspend fun all(): List<TranscriptionJob>
}

/** 内存任务库：不落盘。测试用；调度器没传任务库时的默认（此时重启后任务靠 Record 库的状态重建，丢的只是重试次数与等待原因）。 */
class InMemoryJobStore : JobStore {
    private val rows = LinkedHashMap<String, TranscriptionJob>()

    override suspend fun put(job: TranscriptionJob) {
        synchronized(rows) { rows[job.jobId] = job }
    }

    override suspend fun all(): List<TranscriptionJob> = synchronized(rows) { rows.values.sortedBy { it.seq } }
}

/** 调度器的参数。默认值是我选的保守值（没有需求给出具体数字），都能在这里改。 */
data class SchedulerConfig(
    /** 同时转几个。真正的上限还要取转录引擎声明的 max_concurrent_jobs。默认 1：手机上并行推理只会互相拖慢。 */
    val maxConcurrent: Int = 1,
    /** 一个任务最多开始几次（含第一次）；用完仍失败就是 FAILED，由用户决定要不要重新转录。 */
    val maxAttempts: Int = 3,
    /** 第 k 次失败之后等多久再试（k 从 1 起；超出表长用最后一项）。 */
    val retryBackoffMs: List<Long> = listOf(30_000, 120_000),
    /** 等资源时的退避间隔；用完后持续使用最后一档，最多五分钟。 */
    val resourceRecheckMs: List<Long> = listOf(15_000, 30_000, 60_000, 120_000, 300_000),
) {
    init {
        require(maxConcurrent >= 1) { "maxConcurrent 至少为 1" }
        require(maxAttempts >= 1) { "maxAttempts 至少为 1" }
        require(retryBackoffMs.isNotEmpty() && retryBackoffMs.all { it >= 0 }) { "retryBackoffMs 不能为空、不能为负" }
        require(resourceRecheckMs.all { it > 0 }) { "resourceRecheckMs 必须为正" }
    }
}

/** 调度器每处理一个输入就记一条，供诊断与测试区分“为什么被拒绝/忽略”。不含任何正文、音频或设备标识。 */
data class JobDecision(
    val jobId: String,
    val trigger: TranscriptionEvent,
    val outcome: Outcome,
    val stateBefore: TranscriptionState,
    val stateAfter: TranscriptionState,
    val cause: JobCause,
)

enum class JobCause {
    /** 调度器自己决定开始或在开始前拦下（模型、资源检查）。 */
    PUMP,
    MODEL_CHANGED,
    RESOURCES_CHANGED,
    RESOURCES_RECHECK,
    RETRY_TIMER,
    RUN_RESULT,
    USER_CANCEL,
    RESUME,
    RECORD_GONE,
    LIBRARY_UNAVAILABLE,
    MODEL_REFUSED,
    RUN_INTERRUPTED,
    STALE_SIGNAL,
}
