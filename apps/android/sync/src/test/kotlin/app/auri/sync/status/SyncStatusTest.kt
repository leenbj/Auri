@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.sync.status

import app.auri.sync.engine.BlockedReason
import app.auri.sync.engine.JobKey
import app.auri.sync.engine.SyncJob
import app.auri.sync.machine.SyncState
import app.auri.sync.pairing.NeedsUser
import app.auri.sync.pairing.PairingState
import app.auri.sync.testing.FakeGateway.Fault
import app.auri.sync.testing.InMemoryDataFlowPolicy
import app.auri.sync.testing.SyncRig
import app.auri.sync.testing.SyncSamples
import app.auri.sync.testing.TlsFixtures
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 界面要区分的三种情形（Ticket 21 第 6 项）：等网关 / 稍后重试 / 需要用户处理。它们对用户的意义完全不同——
 * 前两种什么都不用做，只有第三种需要人出手——所以汇总时绝不能混成同一种提示。
 * 前半是汇总函数本身的规则，后半用真引擎把任务推到各种状态，确认“引擎做出来的状态”汇总出来的也是对的情形。
 */
class SyncStatusTest {
    private val t0 = Instant.parse("2026-11-18T08:00:00Z")
    private var next = 0

    private fun job(state: SyncState, reason: BlockedReason? = null, retryAt: Instant? = null) = SyncJob(
        key = JobKey(SyncSamples.RECORD_ID, ++next),
        state = state,
        attempts = 1,
        uncertain = false,
        retryAt = retryAt,
        blockedReason = reason,
        createdAt = t0,
        updatedAt = t0,
    )

    private val paired = PairingState.Paired(SyncSamples.pairedGateway())

    private fun status(vararg jobs: SyncJob, pairing: PairingState = paired) = SyncStatus.of(pairing, jobs.toList(), InMemoryDataFlowPolicy.GRANTED)

    @Test
    fun `没配对：只说没配对，文字留在本机，排队的条数照实报出来`() {
        val result = status(job(SyncState.PENDING), job(SyncState.PENDING), pairing = PairingState.NotPaired)

        assertEquals(SyncHeadline.NOT_PAIRED, result.headline)
        assertEquals(2, result.counts.queued)
        assertTrue(result.causes.isEmpty())
    }

    @Test
    fun `配了对而且没有未入库的任务（或全部已入库）：已是最新`() {
        assertEquals(SyncHeadline.UP_TO_DATE, status().headline)
        val result = status(job(SyncState.STORED), job(SyncState.STORED))
        assertEquals(SyncHeadline.UP_TO_DATE, result.headline)
        assertEquals(2, result.counts.stored)
    }

    @Test
    fun `三种情形互不相同：等网关、稍后重试、需要用户处理各自一个标题`() {
        val waiting = status(job(SyncState.WAITING_GATEWAY)).headline
        val retrying = status(job(SyncState.RETRY_SCHEDULED, retryAt = t0.plusSeconds(30))).headline
        val needsUser = status(job(SyncState.BLOCKED, BlockedReason.VERSION_CONFLICT)).headline

        assertEquals(SyncHeadline.WAITING_FOR_GATEWAY, waiting)
        assertEquals(SyncHeadline.RETRYING_LATER, retrying)
        assertEquals(SyncHeadline.NEEDS_USER, needsUser)
        assertEquals(3, setOf(waiting, retrying, needsUser).size)
    }

    @Test
    fun `排队与发送中是在发送，不是等待也不是出错`() {
        assertEquals(SyncHeadline.SENDING, status(job(SyncState.PENDING)).headline)
        assertEquals(SyncHeadline.SENDING, status(job(SyncState.SENDING), job(SyncState.STORED)).headline)
    }

    @Test
    fun `同时有几种任务时按“越需要人越靠前”：需要用户处理 高于 稍后重试 高于 等网关 高于 发送中`() {
        val blocked = job(SyncState.BLOCKED, BlockedReason.RECORD_DELETED)
        val retrying = job(SyncState.RETRY_SCHEDULED, retryAt = t0)
        val waiting = job(SyncState.WAITING_GATEWAY)
        val sending = job(SyncState.SENDING)
        val queued = job(SyncState.PENDING)

        assertEquals(SyncHeadline.NEEDS_USER, status(queued, sending, waiting, retrying, blocked).headline)
        assertEquals(SyncHeadline.RETRYING_LATER, status(queued, sending, waiting, retrying).headline)
        assertEquals(SyncHeadline.WAITING_FOR_GATEWAY, status(queued, sending, waiting).headline)
        assertEquals(SyncHeadline.SENDING, status(queued, sending).headline)
    }

    @Test
    fun `连接层面的问题（公钥不符、令牌被拒）即使一条任务也没有，也是需要用户处理，并说清原因`() {
        for ((needs, cause) in listOf(NeedsUser.KEY_MISMATCH to UserCause.KEY_MISMATCH, NeedsUser.AUTH_REJECTED to UserCause.AUTH_REJECTED)) {
            val result = status(pairing = PairingState.Paired(SyncSamples.pairedGateway().copy(needsUser = needs)))

            assertEquals(SyncHeadline.NEEDS_USER, result.headline)
            assertEquals(listOf(cause), result.causes)
        }
    }

    @Test
    fun `配对记录读不出来：需要用户重新配对，不能显示成“没配对”的平静状态`() {
        val result = status(job(SyncState.PENDING), pairing = PairingState.Lost)

        assertEquals(SyncHeadline.NEEDS_USER, result.headline)
        assertEquals(listOf(UserCause.PAIRING_LOST), result.causes)
    }

    @Test
    fun `每一种停下的原因都对应一个给用户看的原因，没有对不上的`() {
        for (reason in BlockedReason.entries) {
            val result = status(job(SyncState.BLOCKED, reason))

            assertEquals(reason.name, listOf(UserCause.valueOf(reason.name)), result.causes)
        }
    }

    @Test
    fun `原因去重并按固定顺序给出：连接层与任务层说的是同一件事只出现一次`() {
        val gateway = SyncSamples.pairedGateway().copy(needsUser = NeedsUser.KEY_MISMATCH)
        val result = status(
            job(SyncState.BLOCKED, BlockedReason.VERSION_CONFLICT),
            job(SyncState.BLOCKED, BlockedReason.KEY_MISMATCH),
            job(SyncState.BLOCKED, BlockedReason.KEY_MISMATCH),
            job(SyncState.BLOCKED, BlockedReason.VERSION_CONFLICT),
            pairing = PairingState.Paired(gateway),
        )

        assertEquals(listOf(UserCause.KEY_MISMATCH, UserCause.VERSION_CONFLICT), result.causes)
        assertEquals(4, result.counts.needsUser)
    }

    @Test
    fun `没有记下原因的停止按“请求被拒”算，不会因此漏掉“需要用户处理”`() {
        val result = status(job(SyncState.BLOCKED, reason = null))

        assertEquals(SyncHeadline.NEEDS_USER, result.headline)
        assertEquals(listOf(UserCause.REQUEST_REJECTED), result.causes)
    }

    @Test
    fun `下一次重试的时间是等待重试的任务里最早的那个；没有等待重试的任务就没有`() {
        val result = status(
            job(SyncState.RETRY_SCHEDULED, retryAt = t0.plusSeconds(90)),
            job(SyncState.RETRY_SCHEDULED, retryAt = t0.plusSeconds(30)),
            job(SyncState.WAITING_GATEWAY),
        )

        assertEquals(t0.plusSeconds(30), result.nextRetryAt)
        assertNull(status(job(SyncState.WAITING_GATEWAY), job(SyncState.PENDING)).nextRetryAt)
    }

    @Test
    fun `各类任务的条数分别统计`() {
        val counts = status(
            job(SyncState.PENDING),
            job(SyncState.SENDING), job(SyncState.SENDING),
            job(SyncState.WAITING_GATEWAY),
            job(SyncState.RETRY_SCHEDULED, retryAt = t0),
            job(SyncState.BLOCKED, BlockedReason.REQUEST_REJECTED),
            job(SyncState.STORED), job(SyncState.STORED), job(SyncState.STORED),
        ).counts

        assertEquals(SyncCounts(queued = 1, sending = 2, waitingForGateway = 1, retryingLater = 1, needsUser = 1, stored = 3), counts)
    }

    // ---- 真引擎做出来的状态，汇总出来的必须是对的情形 ----

    private suspend fun SyncRig.status() = SyncStatus.of(pairing.load(), jobs.all(), policy.current())

    @Test
    fun `真引擎：连不上网关 → 等网关（不是出错，也没有重试时间）`() = runTest {
        val rig = SyncRig(this, gatewayOnLan = false)
        rig.publish()

        rig.engine.runOnce()

        assertEquals(SyncHeadline.WAITING_FOR_GATEWAY, rig.status().headline)
        assertNull(rig.status().nextRetryAt)
    }

    @Test
    fun `真引擎：网关答可重试的错误 → 稍后重试，并给出下一次重试的时间`() = runTest {
        val rig = SyncRig(this)
        val key = rig.publish()
        rig.server.faults += Fault.Reject(503, "GATEWAY_UNREACHABLE", retryable = true)

        rig.engine.runOnce()

        assertEquals(SyncHeadline.RETRYING_LATER, rig.status().headline)
        assertEquals(rig.job(key).retryAt, rig.status().nextRetryAt)
    }

    @Test
    fun `真引擎：令牌被撤销 → 需要用户处理，原因是令牌被拒`() = runTest {
        val rig = SyncRig(this)
        rig.publish()
        rig.server.faults += Fault.Reject(401, "GATEWAY_AUTH_REVOKED", retryable = false)

        rig.engine.runOnce()

        assertEquals(SyncHeadline.NEEDS_USER, rig.status().headline)
        assertEquals(listOf(UserCause.AUTH_REJECTED), rig.status().causes)
    }

    @Test
    fun `真引擎：网关换了公钥 → 需要用户处理，原因是公钥不符`() = runTest {
        val rig = SyncRig(this)
        rig.lan.hosts.clear()
        rig.lan.host(SyncSamples.LAN_ADDRESS, TlsFixtures.gatewayB) { rig.server.handle(it) }
        rig.publish()

        rig.engine.runOnce()

        assertEquals(SyncHeadline.NEEDS_USER, rig.status().headline)
        assertEquals(listOf(UserCause.KEY_MISMATCH), rig.status().causes)
    }

    @Test
    fun `真引擎：同一版本号正文不同 → 需要用户处理，原因是版本冲突；入库成功 → 已是最新`() = runTest {
        val rig = SyncRig(this)
        rig.publish()
        rig.server.faults += Fault.Reject(409, "RECORD_VERSION_CONFLICT", retryable = false)
        rig.engine.runOnce()
        assertEquals(SyncHeadline.NEEDS_USER, rig.status().headline)
        assertEquals(listOf(UserCause.VERSION_CONFLICT), rig.status().causes)

        val fresh = SyncRig(this)
        fresh.publish()
        fresh.engine.runOnce()
        assertEquals(SyncHeadline.UP_TO_DATE, fresh.status().headline)
    }
}
