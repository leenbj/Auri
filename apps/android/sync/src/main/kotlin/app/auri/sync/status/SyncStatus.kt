package app.auri.sync.status

import app.auri.sync.destinations.DispatchStore
import app.auri.sync.engine.BlockedReason
import app.auri.sync.engine.SyncJob
import app.auri.sync.machine.SyncState
import app.auri.sync.pairing.NeedsUser
import app.auri.sync.pairing.PairingState
import app.auri.sync.policy.DataFlowState
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * 界面要区分的情形，互斥，越靠前越需要人出手。关键在三种“同步没完成”的情形（Ticket 21 第 6 项）：
 * 等网关与稍后重试都不需要用户做任何事，只有 [NEEDS_USER] 需要——混成一种提示，用户要么白担心、要么漏掉真正的问题。
 */
enum class SyncHeadline {
    /** 还没配对：文字只留在本机，不会发出去。 */
    NOT_PAIRED,

    /** 文字被留在本机：用户没有授权“文字可以进网关”，或开着严格仅本地。不是故障，是用户的选择；具体是哪一道看 [SyncStatus.dataFlow]。 */
    HELD_BY_POLICY,

    /** 需要用户处理：公钥不符、令牌被拒、配对记录丢失，或有 Revision 被网关永久拒绝。具体原因见 [SyncStatus.causes]。 */
    NEEDS_USER,

    /** 稍后自动重试：网关答了可重试的错误，或请求发出后没拿到结果。 */
    RETRYING_LATER,

    /** 等网关：这会儿连不上（不在同一网络、网关没开）；连得上时自动继续。 */
    WAITING_FOR_GATEWAY,

    /** 排队或正在发送。 */
    SENDING,

    /** 配了对，并且没有没入库的东西（文字都在网关里；预览确认的投递可能还在等用户，数见 [SyncStatus.awaitingYou]，不改变这一句）。 */
    UP_TO_DATE,
}

/** 需要用户处理的原因：前四项是连接层面的（对所有任务都成立），其余与 [BlockedReason] 一一对应。顺序即显示顺序。 */
enum class UserCause {
    KEY_MISMATCH,
    AUTH_REJECTED,
    PAIRING_LOST,

    /** 网关说的合同版本比手机要的旧（评审 R7）：处置是更新网关，不用重新配对；没有任何一条文字被拒绝。 */
    GATEWAY_TOO_OLD,
    VERSION_CONFLICT,
    RECORD_DELETED,
    NOT_ORIGIN_DEVICE,
    REQUEST_REJECTED,
    LOCAL_DATA_MISSING,
}

data class SyncCounts(
    /** 排队中，还没开始发。 */
    val queued: Int,
    val sending: Int,
    val waitingForGateway: Int,
    val retryingLater: Int,
    /** 停下等用户处理的任务数。 */
    val needsUser: Int,
    val stored: Int,
)

/** 同步状态的汇总，界面直接用它（见 :app 的 SyncUiState）。[nextRetryAt] 是等待重试的任务里最早的重试时间。 */
data class SyncStatus(
    val headline: SyncHeadline,
    val causes: List<UserCause>,
    val counts: SyncCounts,
    val nextRetryAt: Instant?,
    /** 文字能不能进网关的两道开关（界面上要显示、要能改）。 */
    val dataFlow: DataFlowState,
    /**
     * 有多少条文字的发送在等用户确认（预览确认的目标，见 DispatchItem.awaitingUser）。文字本身照常入库（评审 B2），
     * 但对应的 Agent 在用户确认之前领不到它；页面只说“已同步”会让人以为 Agent 早就有了（评审 M2）。
     * 这是发送账里的数，不是任务状态，所以不在 [counts] 里。
     */
    val awaitingYou: Int = 0,
) {
    companion object {
        /**
         * 界面要的同步状态流：配对状态、任务表、数据流开关与发送账里等用户确认的文字，任何一边变了都重新汇总。
         * shortcut: 数等确认的文字是把它们读出来再数；攒到成百上千条时换成一条 COUNT 查询。
         */
        fun watch(pairing: Flow<PairingState>, jobs: Flow<List<SyncJob>>, dataFlow: Flow<DataFlowState>, dispatch: DispatchStore): Flow<SyncStatus> {
            val awaiting = dispatch.changes().onStart { emit(0L) }.map { dispatch.awaiting().size }.distinctUntilChanged()
            return combine(pairing, jobs, dataFlow, awaiting) { pairingState, list, flow, waiting -> of(pairingState, list, flow, waiting) }.distinctUntilChanged()
        }

        /** 纯函数：同一份配对状态、任务表和等确认的条数永远汇总出同一个结果。 */
        fun of(pairing: PairingState, jobs: List<SyncJob>, dataFlow: DataFlowState, awaitingYou: Int = 0): SyncStatus {
            fun count(state: SyncState) = jobs.count { it.state == state }
            val counts = SyncCounts(
                queued = count(SyncState.PENDING),
                sending = count(SyncState.SENDING),
                waitingForGateway = count(SyncState.WAITING_GATEWAY),
                retryingLater = count(SyncState.RETRY_SCHEDULED),
                needsUser = count(SyncState.BLOCKED),
                stored = count(SyncState.STORED),
            )
            val causes = sortedSetOf<UserCause>()
            when (pairing) {
                PairingState.NotPaired -> Unit
                PairingState.Lost -> causes += UserCause.PAIRING_LOST
                is PairingState.Paired -> when (pairing.gateway.needsUser) {
                    NeedsUser.KEY_MISMATCH -> causes += UserCause.KEY_MISMATCH
                    NeedsUser.AUTH_REJECTED -> causes += UserCause.AUTH_REJECTED
                    NeedsUser.GATEWAY_TOO_OLD -> causes += UserCause.GATEWAY_TOO_OLD
                    null -> Unit
                }
            }
            jobs.filter { it.state == SyncState.BLOCKED }.forEach { causes += (it.blockedReason ?: BlockedReason.REQUEST_REJECTED).toCause() }

            val headline = when {
                pairing == PairingState.NotPaired -> SyncHeadline.NOT_PAIRED
                pairing == PairingState.Lost -> SyncHeadline.NEEDS_USER
                !dataFlow.textMayEnterGateway -> SyncHeadline.HELD_BY_POLICY // 引擎一个字节也不会发：不能说“正在发送”或“已同步”
                causes.isNotEmpty() -> SyncHeadline.NEEDS_USER
                counts.retryingLater > 0 -> SyncHeadline.RETRYING_LATER
                counts.waitingForGateway > 0 -> SyncHeadline.WAITING_FOR_GATEWAY
                counts.queued + counts.sending > 0 -> SyncHeadline.SENDING
                else -> SyncHeadline.UP_TO_DATE
            }
            val nextRetryAt = jobs.filter { it.state == SyncState.RETRY_SCHEDULED }.mapNotNull { it.retryAt }.minOrNull()
            return SyncStatus(headline, causes.toList(), counts, nextRetryAt, dataFlow, awaitingYou)
        }

        private fun BlockedReason.toCause(): UserCause = when (this) {
            BlockedReason.KEY_MISMATCH -> UserCause.KEY_MISMATCH
            BlockedReason.AUTH_REJECTED -> UserCause.AUTH_REJECTED
            BlockedReason.VERSION_CONFLICT -> UserCause.VERSION_CONFLICT
            BlockedReason.RECORD_DELETED -> UserCause.RECORD_DELETED
            BlockedReason.NOT_ORIGIN_DEVICE -> UserCause.NOT_ORIGIN_DEVICE
            BlockedReason.REQUEST_REJECTED -> UserCause.REQUEST_REJECTED
            BlockedReason.LOCAL_DATA_MISSING -> UserCause.LOCAL_DATA_MISSING
        }
    }
}
