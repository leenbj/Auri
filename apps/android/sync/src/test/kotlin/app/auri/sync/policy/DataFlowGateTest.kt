@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.sync.policy

import app.auri.sync.engine.SendResult
import app.auri.sync.engine.SyncJob
import app.auri.sync.machine.SyncEvent
import app.auri.sync.machine.SyncState
import app.auri.sync.resume.ResumeRunner
import app.auri.sync.resume.needsResume
import app.auri.sync.status.SyncHeadline
import app.auri.sync.status.SyncStatus
import app.auri.sync.testing.FakeGateway.Fault
import app.auri.sync.testing.SyncRig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评审 B1（红线：文字进网关、Agent 读取、自动发送各自独立授权；“严格仅本地”是统一门禁）。
 * 以前：只要配了对，每一条新 Revision 都会入队并 PUT，没有 Destination、没有任何文字授权也一样，严格仅本地更没有地方拦。
 * 现在：引擎在每一个可能发请求的地方先问 [DataFlowPolicy]；不放行就一个字节也不发（包括探测网关），任务原样留在手机上。
 * 所以这里每个测试都看“网上有没有东西”：假局域网收到的连接尝试、假 mDNS 被问的次数、网关收到的调用，都得是零。
 */
class DataFlowGateTest {
    private val notGranted = DataFlowState.Unset
    private val grantedButStrictLocal = DataFlowState(gatewayTextGranted = true, strictLocal = true)

    private fun runRig(policy: DataFlowState, body: suspend TestScope.(SyncRig) -> Unit) = runTest { body(SyncRig(this, policyState = policy)) }

    private fun SyncRig.assertNothingLeftThePhone() {
        assertTrue("网关不该收到任何调用：${server.calls}", server.calls.isEmpty())
        assertTrue("连网关的连接都不该尝试：${lan.attempts}", lan.attempts.isEmpty())
        assertTrue("不该有任何请求发出：${lan.delivered.map { it.second.path }}", lan.delivered.isEmpty())
        assertTrue("连 mDNS 也不该去问：${discovery.asked}", discovery.asked.isEmpty())
    }

    // ---- 配了对也不等于同意：没有授权就不发 ----

    @Test
    fun `配了对、没有授权文字进网关：一轮同步什么都不发，任务原样排队`() = runRig(notGranted) { rig ->
        val key = rig.publish()

        rig.engine.runOnce()

        rig.assertNothingLeftThePhone()
        assertEquals(SyncState.PENDING, rig.job(key).state)
        assertEquals("没有开始发送：连 SEND_STARTED 都不该落盘", 0, rig.job(key).attempts)
        assertTrue(rig.decisionsFor(key).isEmpty())
    }

    @Test
    fun `授权了文字进网关、但开着严格仅本地：同样一个字节也不发（统一门禁压过授权）`() = runRig(grantedButStrictLocal) { rig ->
        val key = rig.publish()

        rig.engine.runOnce()

        rig.assertNothingLeftThePhone()
        assertEquals(SyncState.PENDING, rig.job(key).state)
    }

    @Test
    fun `设置读不出来：按没授权处理（不确定就是不许），不抛出`() = runRig(InMemoryGranted) { rig ->
        val key = rig.publish()
        rig.policy.unreadable = true

        rig.engine.runOnce()

        rig.assertNothingLeftThePhone()
        assertEquals(SyncState.PENDING, rig.job(key).state)
    }

    @Test
    fun `没有授权时直接调 send 也一样：返回什么也没发，状态不动，一条决定记录都没有`() = runRig(notGranted) { rig ->
        val key = rig.publish()

        assertEquals(SendResult.SKIPPED, rig.engine.send(key))

        rig.assertNothingLeftThePhone()
        assertEquals(SyncState.PENDING, rig.job(key).state)
        assertTrue(rig.decisionsFor(key).isEmpty())
    }

    @Test
    fun `没有授权时单独探测网关也不行：探测本身就是一次联系`() = runRig(notGranted) { rig ->
        val key = rig.publish()
        rig.jobs.update(rig.job(key).copy(state = SyncState.WAITING_GATEWAY))

        rig.engine.probe()

        rig.assertNothingLeftThePhone()
        assertEquals("探测没做，任务还停在等待网关", SyncState.WAITING_GATEWAY, rig.job(key).state)
    }

    // ---- 授权是用户随时可以给、可以收的 ----

    @Test
    fun `没授权期间积压的文字，授权之后下一轮按先后顺序全部发出去，一条不丢`() = runRig(notGranted) { rig ->
        val first = rig.publish(recordId = "a4a7c08d-2767-50d6-9238-6fa277033a49")
        rig.scope.advanceTimeBy(1_000)
        val second = rig.publish(recordId = "b5b8d19e-3878-5ae7-a349-7fb388144b5a")
        rig.engine.runOnce()
        rig.assertNothingLeftThePhone()

        rig.policy.setGatewayTextGranted(true)
        rig.engine.runOnce()

        assertEquals(listOf("PUT ${first.recordId}#1", "PUT ${second.recordId}#1"), rig.server.calls.toList())
        assertEquals(SyncState.STORED, rig.job(first).state)
        assertEquals(SyncState.STORED, rig.job(second).state)
    }

    @Test
    fun `收回授权之后：已经入库的不动，之后新来的不发`() = runRig(InMemoryGranted) { rig ->
        val sent = rig.publish(recordId = "a4a7c08d-2767-50d6-9238-6fa277033a49")
        rig.engine.runOnce()
        assertEquals(SyncState.STORED, rig.job(sent).state)

        rig.policy.setGatewayTextGranted(false)
        val held = rig.publish(recordId = "b5b8d19e-3878-5ae7-a349-7fb388144b5a")
        rig.engine.runOnce()

        assertEquals("只有第一条发过", listOf("PUT ${sent.recordId}#1"), rig.server.calls.toList())
        assertEquals(SyncState.STORED, rig.job(sent).state)
        assertEquals(SyncState.PENDING, rig.job(held).state)
    }

    @Test
    fun `稍后重试的任务到点了也受门禁管：开着严格仅本地时不重发，关掉之后才发`() = runRig(InMemoryGranted) { rig ->
        val key = rig.publish()
        rig.server.faults += Fault.Reject(503, "GATEWAY_UNREACHABLE", retryable = true)
        rig.engine.runOnce()
        assertEquals(SyncState.RETRY_SCHEDULED, rig.job(key).state)
        rig.scope.advanceTimeBy(60_000)
        val callsBefore = rig.server.calls.size

        rig.policy.setStrictLocal(true)
        rig.engine.runOnce()

        assertEquals("重试的请求也不能发", callsBefore, rig.server.calls.size)
        rig.policy.setStrictLocal(false)
        rig.engine.runOnce()
        assertEquals(SyncState.STORED, rig.job(key).state)
    }

    // ---- 发请求之前的最后一道：问过之后、发送之前被收回 ----

    @Test
    fun `问过之后、请求发出之前授权被收回：什么都没发出去，任务退回等待，不确定的标记不变`() = runRig(InMemoryGranted) { rig ->
        val key = rig.publish()
        // 引擎在 SEND_STARTED 落盘之后再问的那一次，看到的是已经收回的授权
        rig.policy.onRead = {
            if (rig.decisionsFor(key).any { it.event == SyncEvent.SEND_STARTED }) rig.policy.setGatewayTextGranted(false)
        }

        rig.engine.runOnce()

        rig.assertNothingLeftThePhone()
        val job: SyncJob = rig.job(key)
        assertEquals(SyncState.WAITING_GATEWAY, job.state)
        assertFalse("请求根本没离开手机：不是“可能已送到”", job.uncertain)

        rig.policy.onRead = null
        rig.policy.setGatewayTextGranted(true)
        rig.engine.runOnce()
        assertEquals(SyncState.STORED, rig.job(key).state)
        assertEquals("重新授权后正好发一次", 1, rig.server.putCount)
    }

    @Test
    fun `联系过网关、取到要发的内容之后、请求发出之前授权被收回：PUT 没发出去，任务退回等待，不确定的标记不变`() = runRig(InMemoryGranted) { rig ->
        val key = rig.publish()
        // SEND_STARTED 之后第一次问（联系网关之前）还放行；第二次问（取到要发的内容之后、请求发出之前）看到的是已经收回的授权
        var readsAfterStarted = 0
        rig.policy.onRead = {
            if (rig.decisionsFor(key).any { it.event == SyncEvent.SEND_STARTED } && ++readsAfterStarted == 2) rig.policy.setGatewayTextGranted(false)
        }

        rig.engine.runOnce()

        assertEquals("联系网关之前那一道放行了，之后这一道才拦的", 2, readsAfterStarted)
        assertTrue("PUT 不能发出去：${rig.lan.delivered.map { it.second.path }}", rig.lan.delivered.none { it.second.method == "PUT" })
        assertTrue("网关不该收到任何入库调用：${rig.server.calls}", rig.server.calls.isEmpty())
        val job: SyncJob = rig.job(key)
        assertEquals(SyncState.WAITING_GATEWAY, job.state)
        assertFalse("请求根本没离开手机：不是“可能已送到”", job.uncertain)

        rig.policy.onRead = null
        rig.policy.setGatewayTextGranted(true)
        rig.engine.runOnce()
        assertEquals(SyncState.STORED, rig.job(key).state)
        assertEquals("重新授权后正好发一次", 1, rig.server.putCount)
    }

    // ---- 被系统叫醒的那一轮 ----

    @Test
    fun `被系统叫醒的补发也过同一道门：没放行就不发，也不再要求系统叫醒`() = runRig(notGranted) { rig ->
        rig.publish()
        val runner = ResumeRunner(
            ready = {},
            runPass = rig.engine::runOnce,
            stillNeeded = { needsResume(rig.pairing.load(), rig.jobs.all(), rig.policy.current()) },
        )

        val reschedule = runner.run()

        rig.assertNothingLeftThePhone()
        assertFalse("发不出去的活，叫醒了也没用；一直要求叫醒只会白白打扰系统", reschedule)
    }

    @Test
    fun `needsResume：授权并且没开严格仅本地才需要叫醒`() = runRig(InMemoryGranted) { rig ->
        rig.publish()
        val jobs = rig.jobs.all()
        val paired = rig.pairing.load()

        assertTrue(needsResume(paired, jobs, InMemoryGranted))
        assertFalse("没授权", needsResume(paired, jobs, notGranted))
        assertFalse("严格仅本地", needsResume(paired, jobs, grantedButStrictLocal))
        assertFalse("读不出来", needsResume(paired, jobs, DataFlowState.Closed))
    }

    // ---- 界面要如实说“文字没有发出去，是因为没授权/严格仅本地” ----

    @Test
    fun `同步状态：配了对但没放行时，标题是“文字被留在本机”，而不是“已同步”或“正在发送”`() = runRig(notGranted) { rig ->
        rig.publish()

        val held = SyncStatus.of(rig.pairing.load(), rig.jobs.all(), notGranted)
        val strict = SyncStatus.of(rig.pairing.load(), rig.jobs.all(), grantedButStrictLocal)
        val open = SyncStatus.of(rig.pairing.load(), rig.jobs.all(), InMemoryGranted)

        assertEquals(SyncHeadline.HELD_BY_POLICY, held.headline)
        assertEquals(SyncHeadline.HELD_BY_POLICY, strict.headline)
        assertEquals(SyncHeadline.SENDING, open.headline)
        assertEquals("界面要据此显示两个开关现在的位置", notGranted, held.dataFlow)
    }

    @Test
    fun `同步状态：没配对时仍是“还没配对”（先要配对，授权才有意义）`() = runRig(notGranted) { rig ->
        val status = SyncStatus.of(app.auri.sync.pairing.PairingState.NotPaired, emptyList(), notGranted)

        assertEquals(SyncHeadline.NOT_PAIRED, status.headline)
    }

    private companion object {
        val InMemoryGranted = app.auri.sync.testing.InMemoryDataFlowPolicy.GRANTED
    }
}
