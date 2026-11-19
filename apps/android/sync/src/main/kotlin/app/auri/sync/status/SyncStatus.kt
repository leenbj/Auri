package app.auri.sync.status

import app.auri.sync.engine.BlockedReason
import app.auri.sync.engine.SyncJob
import app.auri.sync.machine.SyncState
import app.auri.sync.pairing.NeedsUser
import app.auri.sync.pairing.PairingState
import java.time.Instant

/**
 * 界面要区分的情形，互斥，越靠前越需要人出手。关键在三种“同步没完成”的情形（Ticket 21 第 6 项）：
 * 等网关与稍后重试都不需要用户做任何事，只有 [NEEDS_USER] 需要——混成一种提示，用户要么白担心、要么漏掉真正的问题。
 */
enum class SyncHeadline {
    /** 还没配对：文字只留在本机，不会发出去。 */
    NOT_PAIRED,

    /** 需要用户处理：公钥不符、令牌被拒、配对记录丢失，或有 Revision 被网关永久拒绝。具体原因见 [SyncStatus.causes]。 */
    NEEDS_USER,

    /** 稍后自动重试：网关答了可重试的错误，或请求发出后没拿到结果。 */
    RETRYING_LATER,

    /** 等网关：这会儿连不上（不在同一网络、网关没开）；连得上时自动继续。 */
    WAITING_FOR_GATEWAY,

    /** 排队或正在发送。 */
    SENDING,

    /** 配了对，并且没有没入库的东西。 */
    UP_TO_DATE,
}

/** 需要用户处理的原因：前三项是连接层面的（对所有任务都成立），其余与 [BlockedReason] 一一对应。顺序即显示顺序。 */
enum class UserCause {
    KEY_MISMATCH,
    AUTH_REJECTED,
    PAIRING_LOST,
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
) {
    companion object {
        /** 纯函数：同一份配对状态和任务表永远汇总出同一个结果。 */
        fun of(pairing: PairingState, jobs: List<SyncJob>): SyncStatus {
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
                    null -> Unit
                }
            }
            jobs.filter { it.state == SyncState.BLOCKED }.forEach { causes += (it.blockedReason ?: BlockedReason.REQUEST_REJECTED).toCause() }

            val headline = when {
                pairing == PairingState.NotPaired -> SyncHeadline.NOT_PAIRED
                causes.isNotEmpty() -> SyncHeadline.NEEDS_USER
                counts.retryingLater > 0 -> SyncHeadline.RETRYING_LATER
                counts.waitingForGateway > 0 -> SyncHeadline.WAITING_FOR_GATEWAY
                counts.queued + counts.sending > 0 -> SyncHeadline.SENDING
                else -> SyncHeadline.UP_TO_DATE
            }
            val nextRetryAt = jobs.filter { it.state == SyncState.RETRY_SCHEDULED }.mapNotNull { it.retryAt }.minOrNull()
            return SyncStatus(headline, causes.toList(), counts, nextRetryAt)
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
