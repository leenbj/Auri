package app.auri.sync.destinations

import app.auri.sync.engine.DeliveryAdditions
import app.auri.sync.engine.JobKey
import app.auri.sync.engine.PublicationSource
import app.auri.sync.engine.Refusal
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
    /** 自动发送的 Destination：用户创建它时已经显式授权，发布时就定了，不需要任何操作；Delivery 随文字入库一起创建。 */
    AUTO,

    /** 预览确认的 Destination：用户还没决定。**在用户确认之前网关上不会有它的 Delivery**（文字本身照常入库，不等它）。 */
    AWAITING,

    /** 用户在预览里确认发送：确认之后向网关给这个已入库的 Revision 追加它的 Delivery（评审 B2）。 */
    CONFIRMED,

    /** 用户选择不发给它：网关上永远不会有它的 Delivery。 */
    SKIPPED,
}

/** 网关上有没有这个目标的 Delivery（本机记的账；以网关的回答为准，见 [app.auri.sync.engine.DeliveryAdditions]）。 */
enum class GatewayDelivery {
    /** 还没有（或还不知道有）：自动发送的随文字入库而来、预览确认的在追加之后才有。 */
    NOT_YET,

    /** 网关回答说有了。 */
    CREATED,

    /** 网关明确不给它建 Delivery（或这条追加被永久拒绝）：不再重试，界面如实说“没有送达”。 */
    REFUSED,
}

/**
 * 一个目标的现状。[eligible] = 这个 Destination 还在（没被撤销、缓存里仍有）并且接收这条文字的意图：
 * 不满足的目标不会收到文字，也不会拦着别的目标发送（每个目标各管各的，互不等待）。
 */
data class DispatchTarget(
    val destinationId: String,
    /** 缓存里已经没有这个 Destination（网关的权威列表里没有了）时为 null。 */
    val destinationName: String?,
    val sendMode: SendMode,
    val decision: TargetDecision,
    val eligible: Boolean,
    val delivery: GatewayDelivery = GatewayDelivery.NOT_YET,
    /** [delivery] 是 [GatewayDelivery.REFUSED] 时的原因；否则为 null。 */
    val refusal: Refusal? = null,
) {
    /** 等着用户做决定，并且这个决定真的会影响发送（已经被拒绝建 Delivery 的，没有什么可决定的）。 */
    val awaitingUser: Boolean get() = decision == TargetDecision.AWAITING && eligible && delivery != GatewayDelivery.REFUSED

    /** 会收到这条文字（已授权或已确认，并且目的地还在、接收这种意图、网关没拒绝为它建 Delivery——暂停后恢复也不补发）。 */
    val willReceive: Boolean get() = eligible && delivery != GatewayDelivery.REFUSED && (decision == TargetDecision.AUTO || decision == TargetDecision.CONFIRMED)
}

/**
 * 一条文字在发送上的全部：意图标签与每个目标的处置。[intentLocked] = 标签已经冻结：引擎开始为它构造提交（网关马上就要拿到它）的那一刻起不能再改，
 * 在那之前（等网关、没网、排队）都可以改（评审 M3）。
 */
data class DispatchItem(val key: JobKey, val intent: Intent, val createdAt: Instant, val intentLocked: Boolean, val targets: List<DispatchTarget>) {
    val awaitingUser: Boolean get() = targets.any { it.awaitingUser }
}

enum class Choice { CONFIRM, SKIP }

sealed interface DecideResult {
    /** 记下了这个决定。确认的话，之后同步会给已入库的 Revision 追加这个 Destination 的 Delivery。 */
    data object Decided : DecideResult

    /** 早就决定过了（双击、进程被杀后重试）：什么也没变，不会再产生第二份。[decision] 是现有的决定。 */
    data class AlreadyDecided(val decision: TargetDecision) : DecideResult

    /** 没有这条文字、或这条文字没有这个目标。 */
    data object NotFound : DecideResult

    /** 这个 Destination 已经被撤销、暂停（凭证被撤销）或不接收这条文字的意图：没有什么可决定的。 */
    data object NotEligible : DecideResult
}

sealed interface IntentResult {
    data object Changed : IntentResult

    data object Unchanged : IntentResult

    /** 标签已经冻结（网关已经或正要拿到这一版）：网关上这一版的标签不可更改，这里也不改。 */
    data object Locked : IntentResult

    data object NotFound : IntentResult
}

/**
 * 发送的本地账：Destination 缓存、每条文字的目标快照与用户的决定、意图标签、每个目标在网关上有没有 Delivery。
 *
 * **文字照常入库，Delivery 各管各的**（评审 B2）：发布 Revision 的事务里一定创建同步任务，不等任何决定；
 * 自动发送的目标随文字入库、由网关当场建 Delivery；预览确认的目标在用户确认之后，才向网关给这个**已入库的、不可变的** Revision 追加 Delivery
 * （[DeliveryAdditions]；网关侧见 contracts/README.md 的追加端点，幂等）。一个目标等用户、不拦着别的目标。
 * 预览确认保护的是“Agent 在用户确认之前看不到这条文字”；文字本身存在用户自己的网关里（那是另一道独立授权，见 DataFlowPolicy）。
 *
 * 标签的冻结：[publicationFor] 在引擎构造提交的那一刻把意图原子地冻结并交出，之后 [setIntent] 一律 [IntentResult.Locked]。
 * 决定是持久的、幂等的：同一个决定重复多少次，只算一次。
 * 实现：内存版（测试）与 :syncplatform 的 SQLite 版，共用 DispatchStoreContract。
 */
interface DispatchStore : PublicationSource, DeliveryAdditions {
    /** 当前网关的 Destination 缓存（含已撤销的），最早创建的在前。 */
    suspend fun destinations(): List<Destination>

    /**
     * 把网关的回答存进缓存。[authoritative] = 这是网关给的完整列表（缓存里有而它没有的，就当不存在了）；
     * 否则只是新增或更新这几个（创建、撤销的响应）。
     * [gatewayId] 与缓存记着的网关不同时，旧网关的缓存整个作废，按它规划的、网关上还没有 Delivery 的目标一并撤回
     * （与 [DeliveryAdditions.useGateway] 同一件事，评审 H2）。
     */
    suspend fun applyDestinations(gatewayId: String, destinations: List<Destination>, authoritative: Boolean, now: Instant)

    /** 取消配对：缓存作废，按它规划的、网关上还没有 Delivery 的目标一并撤回；在重新读取之前不给新文字规划任何目标。 */
    suspend fun forgetDestinations(now: Instant)

    suspend fun item(key: JobKey): DispatchItem?

    /** 最新的在前。 */
    suspend fun items(limit: Int): List<DispatchItem>

    /**
     * 所有还有目标在等用户决定的文字（[DispatchItem.awaitingUser]），最新的在前。**不受条数限制**（评审 M1）：
     * 等用户决定的文字不能因为后面又来了很多新文字就够不着——够不着就没法确认或选择不发送，它的 Agent 永远领不到。
     */
    suspend fun awaiting(): List<DispatchItem>

    /** 有这个 Destination 作为目标的文字，最新的在前。 */
    suspend fun itemsFor(destinationId: String, limit: Int): List<DispatchItem>

    /** 用户对一个预览确认目标做决定。持久、幂等：见 [DecideResult]。 */
    suspend fun decide(key: JobKey, destinationId: String, choice: Choice, now: Instant): DecideResult

    /** 改这条文字的意图标签。只在它还没冻结（引擎还没开始为它构造提交）时可以改。 */
    suspend fun setIntent(key: JobKey, intent: Intent, now: Instant): IntentResult

    /**
     * 这条文字入库时的 publication：意图加上随这次入库就要建 Delivery 的 Destination（空 = 只入库），最多一次提交的合同上限，其余的留给追加。
     * **调用它就是冻结意图**：引擎在构造提交时调用，从这一刻起标签不能再改（评审 M3）。没有为它规划过的文字（升级前的旧文字、用户修订）只入库。
     */
    override suspend fun publicationFor(key: JobKey): Publication

    /** 新录出来的文字用的意图标签；用户可以在录音前改。默认 [Intent.CONTEXT]（备忘）。 */
    suspend fun defaultIntent(): Intent

    suspend fun setDefaultIntent(intent: Intent)

    /** 任何变化（缓存、决定、意图、新文字、网关上有没有 Delivery 的账）之后发出一个递增的数；界面据此重读，同步据此重算还欠哪些 Delivery。 */
    override fun changes(): Flow<Long>
}
