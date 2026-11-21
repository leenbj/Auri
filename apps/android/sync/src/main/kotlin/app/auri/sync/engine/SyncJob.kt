package app.auri.sync.engine

import app.auri.sync.gateway.RevisionSubmission
import app.auri.sync.machine.SyncState
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/** 一条待同步任务的逻辑键：一个 Record 的一个 Revision（与网关的幂等键 record_id + revision 一致）。 */
data class JobKey(val recordId: String, val revision: Int)

/** 任务为什么停在 BLOCKED（需要用户处理）。只给界面做提示，状态机本身只有 BLOCKED 一个状态。 */
enum class BlockedReason {
    /** 网关公钥与配对时不符。重新配对后自动解除。 */
    KEY_MISMATCH,

    /** 设备令牌被网关拒绝（撤销或无效）。重新配对后自动解除。 */
    AUTH_REJECTED,

    /** 同一版本号的正文不同（RECORD_VERSION_CONFLICT）。 */
    VERSION_CONFLICT,

    /** 网关上这条 Record 已被删除（RECORD_DELETED）。 */
    RECORD_DELETED,

    /** 不是这条 Record 的来源设备（RECORD_NOT_ORIGIN_DEVICE，ADR-0020）。 */
    NOT_ORIGIN_DEVICE,

    /** 网关以别的非重试性错误拒绝（如请求不合规）。 */
    REQUEST_REJECTED,

    /** 本机已经读不到这条 Revision 的内容，没有可发的东西。 */
    LOCAL_DATA_MISSING,
}

/**
 * 待同步任务（同步任务表里的一行）。[uncertain] = 上一次提交的请求可能已经送到网关、但没拿到明确的结果：
 * 下次发送前必须先问网关，不能直接重发。它在请求发出之前就已落盘，所以进程在发送途中被杀也不会丢。
 */
data class SyncJob(
    val key: JobKey,
    val state: SyncState,
    /** 已经开始过几次发送（SEND_STARTED）；决定退避时间。 */
    val attempts: Int,
    val uncertain: Boolean,
    val retryAt: Instant?,
    val blockedReason: BlockedReason?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

interface SyncJobStore {
    /** 新建一条 PENDING 任务；同一个键已存在则什么都不做并返回 false（幂等）。 */
    suspend fun enqueue(key: JobKey, now: Instant): Boolean

    suspend fun get(key: JobKey): SyncJob?

    /** 全部任务，最早创建的在前。 */
    suspend fun all(): List<SyncJob>

    /** 按键整行替换。任务不存在抛 [NoSuchJobException]。 */
    suspend fun update(job: SyncJob)

    /** 每次变化发出最新的全部任务。 */
    fun observe(): Flow<List<SyncJob>>
}

class NoSuchJobException(val key: JobKey) : IllegalArgumentException("没有这条同步任务：$key")

/**
 * 读出要发的那条 Revision 的请求（来自本地 Record 库）。本机已经没有它时返回 null。
 *
 * 两个入口的区别是**有没有副作用**（复评 R13）：[submissionFor] 构造的请求带着意图标签，带标签的实现（发送账）在这一刻把它冻结（评审 M3）——
 * 所以它只在请求真的要离开手机的那一刻调用：最后一道放行问过之后、紧挨着 PUT 之前。之前的任何一步（找网关、问“上次那份到了没有”）
 * 只许用不冻结任何东西的 [textSha256For]，这样被拦下或中途作废的尝试不会把用户还想改的标签锁死。
 */
fun interface SubmissionSource {
    suspend fun submissionFor(key: JobKey): RevisionSubmission?

    /**
     * 这条文字的 SHA-256，不冻结任何东西；本机已经没有它或不合规时返回 null。
     * 默认实现经 [submissionFor]：没有副作用的来源（测试里的 lambda）这样就够；[submissionFor] 有副作用的实现必须覆盖它。
     */
    suspend fun textSha256For(key: JobKey): String? = submissionFor(key)?.textSha256
}
