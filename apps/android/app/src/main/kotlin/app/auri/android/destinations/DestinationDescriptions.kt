package app.auri.android.destinations

import app.auri.sync.destinations.AuthResult
import app.auri.sync.destinations.DeliveryView
import app.auri.sync.destinations.GatewayStage
import app.auri.sync.destinations.QueuedPhase
import app.auri.sync.destinations.ServiceFailure
import app.auri.sync.destinations.TargetDecision
import app.auri.sync.destinations.UnavailableReason
import app.auri.sync.destinations.UnknownReason
import app.auri.sync.gateway.Intent
import app.auri.sync.gateway.SendMode
import app.auri.sync.pairing.NeedsUser

/*
 * 这个页面上所有会说“事情到哪一步了”的句子都在这里，界面不自己拼。几条规矩：
 *  - 网关只证明三件事：已入库、Agent 已领取、Agent 已确认。**绝不说“已处理 / 已完成 / 已读”**——Agent 领走并回了确认之后有没有读懂、有没有做，网关不知道。
 *    DestinationTextTest 逐句检查这几个词不出现。
 *  - 读不出状态就说“未知”，不说成功；连不上网关与网关答非所问分开说。
 *  - 创建、撤销、签发凭证“结果不确定”时，让用户先刷新确认，不叫人直接重试（创建没有幂等键，重试会多出一个同名目的地）。
 *  - 凭证只出现在“生成”的那一屏，这里的句子不包含凭证本身。
 */

internal fun describeSendMode(mode: SendMode): String = when (mode) {
    SendMode.AUTO_SEND -> "自动发送"
    SendMode.PREVIEW_CONFIRM -> "预览确认"
}

internal fun describeSendModeDetail(mode: SendMode): String = when (mode) {
    SendMode.AUTO_SEND -> "自动发送：转录一完成就发给它，不再问你。发出去的是还没有审核过的初稿。"
    SendMode.PREVIEW_CONFIRM -> "预览确认：每一条先给你看，你点了“确认发送”才发给它；不点就不会发。"
}

internal fun describeIntent(intent: Intent): String = when (intent) {
    Intent.CONTEXT -> "备忘"
    Intent.ACTION_REQUEST -> "请求执行"
}

/** 意图标签只是数据上的一个标记（SEC-004）：不会执行任何东西，也不给 Agent 额外权限。 */
internal fun describeIntentNote(intent: Intent): String = when (intent) {
    Intent.CONTEXT -> "备忘：给 Agent 当背景资料。"
    Intent.ACTION_REQUEST -> "请求执行：只是告诉 Agent 你的本意。它不会被自动执行，Agent 也不会因此多得到任何权限。"
}

/** 每一条自动发送的初稿都还没有人审核过：发出去的请求里就是这个状态（review_state=not_reviewed）。 */
internal const val UNREVIEWED_LABEL = "未审核"

internal fun describeFailure(failure: ServiceFailure): String = when (failure) {
    ServiceFailure.NotPaired -> "还没有配对：请先在“同步与配对”里扫码配对网关。"
    is ServiceFailure.NeedsRepair -> when (failure.needs) {
        NeedsUser.KEY_MISMATCH -> "这台网关的身份与配对时记录的不一样，没有发出任何请求。请在网关上重新生成二维码，重新配对。"
        NeedsUser.AUTH_REJECTED -> "网关不再承认这台手机（可能被撤销了）。请重新配对。"
    }
    ServiceFailure.Unreachable -> "现在连不上网关（可能不在同一个网络，或网关没开机）。请求没有发出，连上之后再试。"
    is ServiceFailure.Rejected -> "网关拒绝了这次请求（${failure.status}）。" + if (failure.retryable) "稍后可以再试。" else "重试不会有用。"
    ServiceFailure.Unreadable -> "请求已经发到网关，但回答读不懂：结果不确定。请先点“刷新”看看目的地列表，不要直接重试。"
    ServiceFailure.Interrupted -> "请求可能已经发出，但没有拿到完整的回答：结果不确定。请先点“刷新”看看目的地列表，不要直接重试。"
    ServiceFailure.DestinationUnavailable -> "这个目的地已经被撤销，或这台手机已经不认识它了。"
    ServiceFailure.Busy -> "上一次生成凭证还没结束，请稍等。"
    ServiceFailure.LimitReached -> "有效的目的地已经有 50 个，一条文字最多只能发给这么多个。请先撤销用不到的。"
}

/** 生成或轮换凭证前的本机核验没通过时，用户该做什么。没通过时凭证根本没有生成：一个请求都没发给网关。 */
internal fun describeNotAuthenticated(result: AuthResult): String = when (result) {
    AuthResult.Authenticated -> error("通过了核验就不该走到这里")
    AuthResult.Cancelled -> "你取消了核验，没有生成凭证。"
    AuthResult.Denied -> "没有通过核验（或尝试次数太多被系统暂时锁定），没有生成凭证。请稍后再试。"
    is AuthResult.Unavailable -> when (result.reason) {
        UnavailableReason.NO_HARDWARE -> "这台设备没有可用的生物识别或锁屏方式，没法核验，所以不能生成凭证。"
        UnavailableReason.NONE_ENROLLED -> "还没有登记指纹或人脸，也没有设置锁屏密码，没法核验，所以没有生成凭证。请先在系统设置里设置，再来生成。"
        UnavailableReason.TEMPORARILY_UNAVAILABLE -> "生物识别暂时用不了，没有生成凭证。请稍后再试。"
    }
}

internal fun describeCredentialRules(rotating: Boolean): String =
    if (rotating) "轮换会让旧凭证立刻失效：正在用旧凭证的 Agent 需要换成新的。新凭证只显示这一次。"
    else "凭证只显示这一次，关掉之后找不回来（需要时可以轮换出新的）。拿到凭证的 Agent 可以读取这个目的地的文字，请只交给你信任的 Agent。"

internal fun describeDecision(decision: TargetDecision, eligible: Boolean): String = when {
    !eligible -> "不会发给它（这个目的地已被撤销或不接收这种标签）"
    decision == TargetDecision.AUTO -> "自动发送"
    decision == TargetDecision.AWAITING -> "等你确认"
    decision == TargetDecision.CONFIRMED -> "你已确认发送"
    else -> "你选择了不发送"
}

/** 一条文字在一个目的地上现在是什么情形。只说网关能证明的。 */
internal fun describeDelivery(view: DeliveryView): String = when (view) {
    DeliveryView.AwaitingYou -> "等你确认，还没有发出去"
    DeliveryView.Skipped -> "你选择了不发给它"
    DeliveryView.NotTargeted -> "不会发给它（这个目的地已被撤销，或不接收这种标签）"
    is DeliveryView.Queued -> when (view.phase) {
        QueuedPhase.SENDING -> "正在发往网关…"
        QueuedPhase.WAITING_FOR_GATEWAY -> "已决定发送，现在连不上网关，连上后自动发"
        QueuedPhase.RETRYING_LATER -> "已决定发送，网关暂时没收下，稍后自动重试"
        QueuedPhase.NEEDS_YOU -> "已决定发送，但卡住了，需要你到“同步与配对”里处理"
    }
    is DeliveryView.OnGateway -> describeStage(view.stage)
    is DeliveryView.Unknown -> "状态未知：" + when (view.reason) {
        UnknownReason.GATEWAY_UNREACHABLE -> "现在连不上网关，没法问"
        UnknownReason.GATEWAY_ANSWER_UNUSABLE -> "网关的回答读不懂，或这次查询没成功"
        UnknownReason.NO_DELIVERY_RECORD -> "网关上没有这一条在这个目的地的投递记录"
        UnknownReason.UNRECOGNIZED_STATE -> "网关给了这个版本的 App 不认识的状态"
    }
}

/** 网关证明了什么，一个阶段一句话；“已确认”后面不加任何推断。 */
internal fun describeStage(stage: GatewayStage): String = when (stage) {
    GatewayStage.STORED -> "已入库，等 Agent 来领取"
    GatewayStage.CLAIMED -> "Agent 已领取，还没回确认"
    GatewayStage.ACKNOWLEDGED -> "Agent 已确认（网关只知道它回了确认）"
    GatewayStage.REJECTED -> "Agent 拒收了这一条"
    GatewayStage.CANCELLED -> "已取消（被更新的版本取代，或这个目的地被撤销了）"
}

internal fun describeCreateHint(): String =
    "创建时必须选一种发送方式，没有默认值：这是你对“转录出来的文字可以发给这个 Agent”的授权。"
