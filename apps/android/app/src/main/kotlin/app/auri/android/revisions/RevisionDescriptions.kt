package app.auri.android.revisions

import app.auri.sync.destinations.DeliveryView
import app.auri.sync.destinations.GatewayStage
import app.auri.sync.destinations.ResendResult
import app.auri.sync.revisions.EditError
import app.auri.sync.revisions.ResendState
import app.auri.sync.revisions.SendStatusFilter

/**
 * Ticket 23 界面说明文案（FR-TXT-002 / FR-TXT-005）：
 * - 明确说明删除整条 Record 会移除本机与网关副本、取消未领取的投递，但 Agent 已读走并另存的内容无法收回；
 * - 绝不声称“撤回所有 Agent 记忆”，绝不把确认说成“已处理”；
 * - 明确说明保存修订产生新版本，不会自动重发给已收到旧版本的 Agent。
 */
object RevisionDescriptions {
    const val DELETE_RECORD_NOTICE =
        "删除整条记录后，本机和网关都将移除它并取消尚未领取的投递，离线补传也不会让它复活；但 Agent 已读走并另存的内容无法收回。"

    const val DELETE_AUDIO_NOTICE =
        "仅删除本地音频文件以释放空间，记录本身与全部文字修订版本都会保留。"

    const val NO_AUTO_RESEND_NOTICE =
        "每次保存都会产生新的不可变版本，旧版本仍可回看；新版本不会自动重发给已收到旧版本的 Agent，请逐个确认后再重发。"

    const val AUDIO_RETENTION_NOTICE =
        "音频保留期从转录成功后起算，默认 30 天；到期自动清理本地音频并保留文字。"

    fun describeEditError(error: EditError): String = when (error) {
        EditError.EMPTY_TEXT -> "正文不能为空，无法保存新版本"
        EditError.TEXT_TOO_LONG -> "正文超过 1 MiB 限制，无法保存"
    }

    fun describeSendStatusFilter(filter: SendStatusFilter): String = when (filter) {
        SendStatusFilter.ALL -> "全部"
        SendStatusFilter.STORED -> "已入库"
        SendStatusFilter.WAITING_GATEWAY -> "等待网关"
        SendStatusFilter.NEEDS_USER -> "需要处理"
        SendStatusFilter.AWAITING_CONFIRM -> "待确认发送"
        SendStatusFilter.LOCAL_ONLY -> "仅本地"
    }

    fun describeOlderDelivery(view: DeliveryView): String = when (view) {
        is DeliveryView.OnGateway -> when (view.stage) {
            GatewayStage.STORED -> "已入库（待领取）"
            GatewayStage.CLAIMED -> "Agent 已领取（未确认）"
            GatewayStage.ACKNOWLEDGED -> "Agent 已确认（网关仅确认回执）"
            GatewayStage.REJECTED -> "Agent 已拒收"
            GatewayStage.CANCELLED -> "已取消"
        }
        is DeliveryView.Unknown -> "状态未知（离线或未查询）"
        DeliveryView.AwaitingYou -> "等待确认"
        DeliveryView.Skipped -> "已跳过"
        DeliveryView.NotTargeted -> "未投递"
        DeliveryView.Suspended -> "凭证已撤销（暂停）"
        is DeliveryView.Queued -> "排队中"
    }

    fun describeResendState(state: ResendState): String = when (state) {
        ResendState.NOT_REQUESTED -> "未重发此版"
        ResendState.PENDING_RESEND -> "已确认重发（等待同步）"
        ResendState.SENT_TO_GATEWAY -> "此版已入库给该目的地"
        ResendState.NOT_ELIGIBLE -> "该目的地当前不可接收"
    }

    fun describeResendResult(result: ResendResult): String = when (result) {
        ResendResult.Requested -> "已确认重发，同步时将发送给该目的地"
        ResendResult.AlreadyRequested -> "已经确认过重发给该目的地"
        ResendResult.NotEligible -> "该目的地不可接收此版本（已撤销、暂停或不接收该意图）"
        ResendResult.WakeUnavailable -> "系统暂不允许后台同步登记，请稍后重试"
    }
}
