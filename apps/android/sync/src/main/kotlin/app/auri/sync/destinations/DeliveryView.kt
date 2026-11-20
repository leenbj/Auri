package app.auri.sync.destinations

import app.auri.sync.machine.SyncState

/**
 * 网关上一次投递（Delivery）走到哪一步。名字照实说它**证明了什么**：
 * 网关只知道 Agent 领走了、回了确认；Agent 之后有没有读懂、执行了没有，网关不知道，所以这里没有“已处理”这一档，界面也不许说。
 */
enum class GatewayStage {
    /** 网关 PENDING：已入库，等 Agent 来领。 */
    STORED,

    /** 网关 LEASED：Agent 领走了（在租约期内）；还没回确认。 */
    CLAIMED,

    /** 网关 ACKNOWLEDGED：Agent 回了确认。仅此而已。 */
    ACKNOWLEDGED,

    /** 网关 REJECTED：Agent 明确拒收（永久），不会再投递。 */
    REJECTED,

    /** 网关 CANCELLED：取消了——被这个 Destination 上更新的版本取代，或 Destination 被撤销。 */
    CANCELLED,
    ;

    companion object {
        /** 网关的 delivery_state 原话 → 阶段。没见过的值返回 null：调用方必须当“未知”，不能悄悄算成功。 */
        fun fromGateway(state: String): GatewayStage? = when (state) {
            "PENDING" -> STORED
            "LEASED" -> CLAIMED
            "ACKNOWLEDGED" -> ACKNOWLEDGED
            "REJECTED" -> REJECTED
            "CANCELLED" -> CANCELLED
            else -> null
        }
    }
}

/** 已决定要发、但网关上还没有它的 Delivery 时，卡在哪（与同步页的三种情形一致：不需要用户做什么的两种与需要处理的一种分开）。 */
enum class QueuedPhase {
    SENDING,
    WAITING_FOR_GATEWAY,
    RETRYING_LATER,
    NEEDS_YOU,

    /** 文字已经在网关（任务已入库）、用户也确认了，但网关上还没有给这个 Destination 的 Delivery：正在（或稍后）请网关追加（评审 B2）。 */
    REQUESTING_DELIVERY,
    ;

    companion object {
        /** [SyncState.STORED] 不是排队：已经入库，之后的状态要问网关。 */
        fun of(state: SyncState): QueuedPhase? = when (state) {
            SyncState.PENDING, SyncState.SENDING -> SENDING
            SyncState.WAITING_GATEWAY -> WAITING_FOR_GATEWAY
            SyncState.RETRY_SCHEDULED -> RETRYING_LATER
            SyncState.BLOCKED -> NEEDS_YOU
            SyncState.STORED -> null
        }
    }
}

/** 为什么说不出网关上的状态。统统显示成“未知”，不显示成功。 */
enum class UnknownReason {
    /** 这会儿连不上网关，没法问。 */
    GATEWAY_UNREACHABLE,

    /** 网关回了，但这一条的状态读不懂或这次查询没成功。 */
    GATEWAY_ANSWER_UNUSABLE,

    /** 网关没有这一版在这个 Destination 上的投递记录，而本机认为它该有。 */
    NO_DELIVERY_RECORD,

    /** 网关给的状态值这个版本的 App 不认识。 */
    UNRECOGNIZED_STATE,

    /** 网关明确不给它建 Delivery（或这条追加被永久拒绝）：文字没有送给它。 */
    DELIVERY_REFUSED,
}

/** 一条文字在一个 Destination 上现在是什么情形。 */
sealed interface DeliveryView {
    /** 预览确认：等用户决定。文字可能已经在网关里，但**网关上还没有给它的 Delivery**，Agent 看不到这条文字。 */
    data object AwaitingYou : DeliveryView

    /** 用户选择不发给这个 Destination。 */
    data object Skipped : DeliveryView

    /** 不会发给它：这个 Destination 已被撤销、不再接收这种意图，或这台手机已经不认识它了。 */
    data object NotTargeted : DeliveryView

    /** 这个 Destination 的凭证被撤销、暂停了：这条文字没有送给它，之后签发新凭证也不会补发（评审 B3）。 */
    data object Suspended : DeliveryView

    /** 已决定要发，网关上还没有它的 Delivery（本机在排队 / 等网关 / 稍后重试 / 需要用户处理 / 正在请网关追加）。 */
    data class Queued(val phase: QueuedPhase) : DeliveryView

    /** 网关上的状态，原样。 */
    data class OnGateway(val stage: GatewayStage) : DeliveryView

    data class Unknown(val reason: UnknownReason) : DeliveryView
}

/** 详情页的一行：这条文字（意图、各目标的处置）加上它在这个 Destination 上的情形。 */
data class ItemDelivery(val item: DispatchItem, val view: DeliveryView)
