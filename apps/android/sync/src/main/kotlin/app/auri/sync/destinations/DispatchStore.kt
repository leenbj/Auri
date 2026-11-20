package app.auri.sync.destinations

import app.auri.sync.engine.JobKey
import app.auri.sync.gateway.Destination
import app.auri.sync.gateway.Intent
import app.auri.sync.gateway.Publication
import app.auri.sync.gateway.SendMode
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * 一条文字（一个 Record 的一个 ASR 初稿 Revision）对一个 Destination 的处置。
 * 发布那一刻按当时的目的地缓存定下有哪些目标（快照）：之后才创建的 Destination 不会收到更早的文字。
 */
enum class TargetDecision {
    /** 自动发送的 Destination：用户创建它时已经显式授权，发布时就定了，不需要任何操作。 */
    AUTO,

    /** 预览确认的 Destination：用户还没决定。**在用户确认之前不会为它创建任何 Delivery。** */
    AWAITING,

    /** 用户在预览里确认发送。 */
    CONFIRMED,

    /** 用户选择不发给它（决定是终局：Delivery 只能在第一次入库时创建，见 README 的网关缺口 G13）。 */
    SKIPPED,
}

/**
 * 一个目标的现状。[eligible] = 这个 Destination 还在（没被撤销、缓存里仍有）并且接收这条文字的意图：
 * 不满足的目标不会收到文字，也不会拦着别的目标发送（否则一个被撤销的预览目标会让文字永远发不出去）。
 */
data class DispatchTarget(
    val destinationId: String,
    /** 缓存里已经没有这个 Destination（网关的权威列表里没有了）时为 null。 */
    val destinationName: String?,
    val sendMode: SendMode,
    val decision: TargetDecision,
    val eligible: Boolean,
) {
    /** 等着用户做决定，并且这个决定真的会影响发送。 */
    val awaitingUser: Boolean get() = decision == TargetDecision.AWAITING && eligible

    /** 会随这次发送送达（已授权或已确认，并且目的地还在、接收这种意图）。 */
    val willReceive: Boolean get() = eligible && (decision == TargetDecision.AUTO || decision == TargetDecision.CONFIRMED)
}

/**
 * 一条文字在发送上的全部：意图标签与每个目标的处置。[sent] = 已经交给同步（有同步任务了）；
 * 在那之前（等着用户确认）它不在网关上，意图标签还可以改。
 */
data class DispatchItem(val key: JobKey, val intent: Intent, val createdAt: Instant, val sent: Boolean, val targets: List<DispatchTarget>) {
    val awaitingUser: Boolean get() = targets.any { it.awaitingUser }
}

enum class Choice { CONFIRM, SKIP }

sealed interface DecideResult {
    /** 记下了这个决定。[releasedForSending] = 这是最后一个要等的决定，文字现在交给同步了。 */
    data class Decided(val releasedForSending: Boolean) : DecideResult

    /** 早就决定过了（双击、进程被杀后重试）：什么也没变，不会再产生第二份。[decision] 是现有的决定。 */
    data class AlreadyDecided(val decision: TargetDecision) : DecideResult

    /** 没有这条文字、或这条文字没有这个目标。 */
    data object NotFound : DecideResult

    /** 这个 Destination 已经被撤销或不接收这条文字的意图：没有什么可决定的。 */
    data object NotEligible : DecideResult
}

sealed interface IntentResult {
    data class Changed(val releasedForSending: Boolean) : IntentResult

    data object Unchanged : IntentResult

    /** 文字已经交给同步（排队、已发或要处理）：网关上这一版的意图不可更改，这里也不改。 */
    data object Locked : IntentResult

    data object NotFound : IntentResult
}

/**
 * 发送的本地账：Destination 缓存、每条文字的目标快照与用户的决定、意图标签。
 *
 * **谁也不会在用户确认之前把预览确认的文字交给同步**：发布 Revision 的那个事务里，只要还有“等着用户决定”的目标，就不创建同步任务；
 * 最后一个决定（或目标被撤销、或意图改到它不接收）落下的同一个事务里才创建。任务不存在，同步引擎就看不见它，网关上也就不会有它的 Delivery——
 * 网关只在 (record_id, revision) 第一次入库时创建 Delivery（G13），所以这是唯一不需要改网关的做法。
 *
 * 决定是持久的、幂等的：同一个决定重复多少次，只会放行一次。
 * 实现：内存版（测试）与 :syncplatform 的 SQLite 版，共用 DispatchStoreContract。
 */
interface DispatchStore {
    /** 当前网关的 Destination 缓存（含已撤销的），最早创建的在前。 */
    suspend fun destinations(): List<Destination>

    /**
     * 把网关的回答存进缓存。[authoritative] = 这是网关给的完整列表（缓存里有而它没有的，就当不存在了）；
     * 否则只是新增或更新这几个（创建、撤销的响应）。然后把因此不再被拦着的文字放行。
     * [gatewayId] 与缓存记着的网关不同时，旧网关的缓存整个作废。
     */
    suspend fun applyDestinations(gatewayId: String, destinations: List<Destination>, authoritative: Boolean, now: Instant)

    /** 换了网关或取消配对：缓存作废，在重新读取之前不给新文字规划任何目标。 */
    suspend fun forgetDestinations(now: Instant)

    suspend fun item(key: JobKey): DispatchItem?

    /** 最新的在前。 */
    suspend fun items(limit: Int): List<DispatchItem>

    /** 有这个 Destination 作为目标的文字，最新的在前。 */
    suspend fun itemsFor(destinationId: String, limit: Int): List<DispatchItem>

    /** 用户对一个预览确认目标做决定。持久、幂等：见 [DecideResult]。 */
    suspend fun decide(key: JobKey, destinationId: String, choice: Choice, now: Instant): DecideResult

    /** 改这条文字的意图标签。只在它还没交给同步时可以改。 */
    suspend fun setIntent(key: JobKey, intent: Intent, now: Instant): IntentResult

    /** 这条文字发送时的 publication：意图加上会收到它的 Destination（空 = 只入库）。没有为它规划过的文字（升级前的旧文字、用户修订）只入库。 */
    suspend fun publicationFor(key: JobKey): Publication

    /** 新录出来的文字用的意图标签；用户可以在录音前改。默认 [Intent.CONTEXT]（备忘）。 */
    suspend fun defaultIntent(): Intent

    suspend fun setDefaultIntent(intent: Intent)

    /** 任何变化（缓存、决定、意图、新文字）之后发出一个递增的数；界面据此重读。 */
    fun changes(): Flow<Long>
}
