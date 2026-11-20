package app.auri.sync.resume

import app.auri.capture.testing.FakeClock
import app.auri.sync.engine.JobKey
import app.auri.sync.engine.RetryPolicy
import app.auri.sync.engine.SyncJob
import app.auri.sync.machine.SyncState
import app.auri.sync.pairing.NeedsUser
import app.auri.sync.pairing.ObservablePairingStore
import app.auri.sync.pairing.PairingState
import app.auri.sync.policy.DataFlowState
import app.auri.sync.testing.CollectingLog
import app.auri.sync.testing.InMemoryDataFlowPolicy
import app.auri.sync.testing.InMemoryPairingStore
import app.auri.sync.testing.SyncSamples
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进程被系统杀掉之后谁来接着发？答案是系统的“稍后叫醒”（Android 上是 JobScheduler）：有活就登记一个，没活就取消。
 * 这里测“什么时候该有这个叫醒”的决策，调度器是假的；真正向系统登记、被叫醒后跑一轮，是真机才能验证的部分（见验收 README，NOT_RUN）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResumePlannerTest {
    private class FakeScheduler : ResumeScheduler {
        val calls = CopyOnWriteArrayList<String>()

        /** 前几次 ensureScheduled 抛异常（JobScheduler 暂时拒绝）。 */
        @Volatile var failures = 0

        override fun ensureScheduled() {
            calls += "ensure"
            if (failures > 0) {
                failures -= 1
                throw IllegalStateException("JobScheduler refused: secret-looking-detail")
            }
        }

        override fun cancel() {
            calls += "cancel"
        }
    }

    private val t0 = Instant.parse("2026-11-18T10:00:00Z")
    private val paired = PairingState.Paired(SyncSamples.pairedGateway())

    private fun job(state: SyncState, record: String = SyncSamples.RECORD_ID, blocked: Boolean = false) = SyncJob(
        JobKey(record, 1), state, attempts = 0, uncertain = false, retryAt = null, blockedReason = null, createdAt = t0, updatedAt = t0,
    ).let { if (blocked) it.copy(blockedReason = app.auri.sync.engine.BlockedReason.VERSION_CONFLICT) else it }

    private class Rig(scope: TestScope, pairing: PairingState, jobs: List<SyncJob>) {
        val scheduler = FakeScheduler()
        val pairing = MutableStateFlow(pairing)
        val jobs = MutableStateFlow(jobs)
        val policy = MutableStateFlow(InMemoryDataFlowPolicy.GRANTED)
        val additionsDue = MutableStateFlow(false)
        val log = CollectingLog()
        val clock = FakeClock(scope.testScheduler)
        val planner = ResumePlanner(this.pairing, this.jobs, policy, scheduler, clock, RetryPolicy(), log, { additionsDue.value }, additionsDue)
    }

    private fun TestScope.rig(pairing: PairingState, jobs: List<SyncJob>) = Rig(this, pairing, jobs).also {
        it.planner.start(backgroundScope)
        runCurrent()
    }

    @Test
    fun `已配对并且有任务没发完（排队、发送中、等网关、等重试）：登记一个叫醒`() = runTest {
        for (state in listOf(SyncState.PENDING, SyncState.SENDING, SyncState.WAITING_GATEWAY, SyncState.RETRY_SCHEDULED)) {
            val rig = rig(paired, listOf(job(state)))
            assertEquals("$state", listOf("ensure"), rig.scheduler.calls.toList())
        }
    }

    @Test
    fun `文字不许进网关（没授权、严格仅本地、读不出来）：有任务也不叫醒，授权之后才登记（评审 B1）`() = runTest {
        for (closed in listOf(DataFlowState.Unset, DataFlowState(gatewayTextGranted = true, strictLocal = true), DataFlowState.Closed)) {
            val rig = Rig(this, paired, listOf(job(SyncState.PENDING))).also { it.policy.value = closed }
            rig.planner.start(backgroundScope)
            runCurrent()
            assertEquals("$closed：醒来也发不出去", listOf("cancel"), rig.scheduler.calls.toList())

            rig.policy.value = InMemoryDataFlowPolicy.GRANTED
            runCurrent()
            assertEquals("$closed → 授权", listOf("cancel", "ensure"), rig.scheduler.calls.toList())
        }
    }

    @Test
    fun `任务都已入库：没有叫醒的必要，取消`() = runTest {
        val rig = rig(paired, listOf(job(SyncState.STORED)))

        assertEquals(listOf("cancel"), rig.scheduler.calls.toList())
    }

    @Test
    fun `没有任何任务：取消`() = runTest {
        assertEquals(listOf("cancel"), rig(paired, emptyList()).scheduler.calls.toList())
    }

    @Test
    fun `只剩停下等用户处理的任务：叫醒也发不出去，不叫醒`() = runTest {
        val rig = rig(paired, listOf(job(SyncState.BLOCKED, blocked = true), job(SyncState.STORED, "b5b8d19e-3878-5ae7-a349-7fb388144b5a")))

        assertEquals(listOf("cancel"), rig.scheduler.calls.toList())
    }

    @Test
    fun `有一条停下等用户处理的，另一条还在排队：照样要叫醒（别的任务不受它拖累）`() = runTest {
        val rig = rig(paired, listOf(job(SyncState.BLOCKED, blocked = true), job(SyncState.PENDING, "b5b8d19e-3878-5ae7-a349-7fb388144b5a")))

        assertEquals(listOf("ensure"), rig.scheduler.calls.toList())
    }

    @Test
    fun `没配对、配对记录丢了、连接要用户处理：任务再多也不叫醒（醒来什么也发不出去）`() = runTest {
        val pending = listOf(job(SyncState.PENDING))
        for (state in listOf(PairingState.NotPaired, PairingState.Lost, PairingState.Paired(SyncSamples.pairedGateway(needsUser = NeedsUser.KEY_MISMATCH)), PairingState.Paired(SyncSamples.pairedGateway(needsUser = NeedsUser.AUTH_REJECTED)))) {
            val rig = rig(state, pending)
            assertEquals("$state", listOf("cancel"), rig.scheduler.calls.toList())
        }
    }

    @Test
    fun `配对记录还没读出来：什么也不做，不能把已经登记的叫醒取消掉；读出来之后才决定`() = runTest {
        // 用真的 ObservablePairingStore：它读过一次之前不发出任何状态（不猜一个“未配对”）
        val store = ObservablePairingStore(InMemoryPairingStore(SyncSamples.pairedGateway()))
        val scheduler = FakeScheduler()
        ResumePlanner(store.states, MutableStateFlow(listOf(job(SyncState.PENDING))), MutableStateFlow(InMemoryDataFlowPolicy.GRANTED), scheduler, FakeClock(testScheduler), RetryPolicy(), CollectingLog()).start(backgroundScope)
        runCurrent()
        assertEquals("还没读：不能当成没活取消掉已登记的叫醒", emptyList<String>(), scheduler.calls.toList())

        store.refresh()
        runCurrent()

        assertEquals(listOf("ensure"), scheduler.calls.toList())
    }

    @Test
    fun `跟着变：新任务出现就登记，发完了就取消，配对被撤销就取消`() = runTest {
        val rig = rig(paired, emptyList())
        assertEquals(listOf("cancel"), rig.scheduler.calls.toList())

        rig.jobs.value = listOf(job(SyncState.PENDING))
        runCurrent()
        assertEquals(listOf("cancel", "ensure"), rig.scheduler.calls.toList())

        rig.jobs.value = listOf(job(SyncState.STORED))
        runCurrent()
        assertEquals(listOf("cancel", "ensure", "cancel"), rig.scheduler.calls.toList())

        rig.jobs.value = listOf(job(SyncState.PENDING))
        runCurrent()
        rig.pairing.value = PairingState.Lost
        runCurrent()
        assertEquals(listOf("cancel", "ensure", "cancel", "ensure", "cancel"), rig.scheduler.calls.toList())
    }

    @Test
    fun `结论没变就不重复打扰系统：任务在排队、发送中、等网关之间换来换去，只登记一次`() = runTest {
        val rig = rig(paired, listOf(job(SyncState.PENDING)))

        for (state in listOf(SyncState.SENDING, SyncState.WAITING_GATEWAY, SyncState.PENDING, SyncState.RETRY_SCHEDULED)) {
            rig.jobs.value = listOf(job(state))
            runCurrent()
        }

        assertEquals(listOf("ensure"), rig.scheduler.calls.toList())
    }

    @Test
    fun `系统暂时拒绝登记（比如 JobScheduler 抛异常）：记一条不含内容的日志，退避之后再登记，不丢掉这次请求`() = runTest {
        val scheduler = FakeScheduler().apply { failures = 1 }
        val pairing = MutableStateFlow<PairingState>(paired)
        val jobs = MutableStateFlow(listOf(job(SyncState.PENDING)))
        val log = CollectingLog()
        ResumePlanner(pairing, jobs, MutableStateFlow(InMemoryDataFlowPolicy.GRANTED), scheduler, FakeClock(testScheduler), RetryPolicy(), log).start(backgroundScope)
        runCurrent()
        assertEquals(listOf("ensure"), scheduler.calls.toList())
        assertEquals(listOf("loop.resume.failed IllegalStateException"), log.lines.toList())

        advanceTimeBy(RetryPolicy().delayMs(1))
        runCurrent()

        assertEquals("退避之后再登记一次", listOf("ensure", "ensure"), scheduler.calls.toList())
    }

    @Test
    fun `任务都已入库，但还欠网关追加的 Delivery（用户确认过预览）：登记叫醒，追加做完就取消（评审 B2）`() = runTest {
        val rig = rig(paired, listOf(job(SyncState.STORED)))
        assertEquals("只有入库的任务、没有欠的追加：不叫醒", listOf("cancel"), rig.scheduler.calls.toList())

        rig.additionsDue.value = true
        runCurrent()
        assertEquals("用户确认了预览，网关上还没有它的 Delivery：进程被杀也要被叫醒做完", listOf("cancel", "ensure"), rig.scheduler.calls.toList())

        rig.additionsDue.value = false
        runCurrent()
        assertEquals(listOf("cancel", "ensure", "cancel"), rig.scheduler.calls.toList())
    }

    @Test
    fun `欠着追加，但文字不许进网关或连接要用户处理：叫醒也做不成，不叫醒`() = runTest {
        for (closed in listOf(DataFlowState.Unset, DataFlowState(gatewayTextGranted = true, strictLocal = true), DataFlowState.Closed)) {
            val rig = Rig(this, paired, listOf(job(SyncState.STORED))).also { it.policy.value = closed; it.additionsDue.value = true }
            rig.planner.start(backgroundScope)
            runCurrent()
            assertEquals("$closed", listOf("cancel"), rig.scheduler.calls.toList())
        }
        val needsRepair = rig(PairingState.Paired(SyncSamples.pairedGateway(needsUser = NeedsUser.AUTH_REJECTED)), listOf(job(SyncState.STORED)))
        needsRepair.additionsDue.value = true
        runCurrent()
        assertEquals(listOf("cancel"), needsRepair.scheduler.calls.toList())
    }

    @Test
    fun `决策表：只有已配对、连接可用、并且有没入库也没被永久拒绝的任务才需要叫醒`() {
        val states = SyncState.entries
        val pairings = listOf(
            PairingState.NotPaired to false,
            PairingState.Lost to false,
            PairingState.Paired(SyncSamples.pairedGateway(needsUser = NeedsUser.KEY_MISMATCH)) to false,
            paired to true,
        )
        for ((pairing, usable) in pairings) {
            for (state in states) {
                val expected = usable && state != SyncState.STORED && state != SyncState.BLOCKED
                assertEquals("$pairing / $state", expected, needsResume(pairing, listOf(job(state)), InMemoryDataFlowPolicy.GRANTED))
            }
            assertFalse("$pairing 没有任务", needsResume(pairing, emptyList(), InMemoryDataFlowPolicy.GRANTED))
        }
        assertTrue(needsResume(paired, listOf(job(SyncState.STORED), job(SyncState.WAITING_GATEWAY, "b5b8d19e-3878-5ae7-a349-7fb388144b5a")), InMemoryDataFlowPolicy.GRANTED))
        assertTrue("欠着追加的 Delivery：即使任务都已入库也要叫醒", needsResume(paired, listOf(job(SyncState.STORED)), InMemoryDataFlowPolicy.GRANTED, additionsDue = true))
        assertFalse("没配对就没法追加", needsResume(PairingState.NotPaired, emptyList(), InMemoryDataFlowPolicy.GRANTED, additionsDue = true))
    }
}
