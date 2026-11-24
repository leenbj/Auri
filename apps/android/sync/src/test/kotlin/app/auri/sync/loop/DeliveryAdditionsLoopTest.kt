@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.sync.loop

import app.auri.sync.destinations.Choice
import app.auri.sync.destinations.GatewayDelivery
import app.auri.sync.engine.RetryPolicy
import app.auri.sync.gateway.SendMode
import app.auri.sync.testing.DispatchRig
import app.auri.sync.testing.FakeGateway.Fault
import app.auri.sync.testing.FakeNetwork
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评审 B2：预览确认之后要给已入库的 Revision 追加 Delivery。追加不改任务表，所以“任务表变了”这个老触发叫不醒它：
 * 同步循环必须自己看账上欠什么（确认、重启后的账）、自己按引擎排好的重试时间再来，而且断网期间不碰网关。
 * 全部用虚拟时间，引擎与发送账是真的，网关、网络、局域网是假的。
 */
class DeliveryAdditionsLoopTest {
    private fun loopRig(body: suspend TestScope.(DispatchRig, FakeNetwork, SyncLoop) -> Unit) = runTest {
        val rig = DispatchRig(this)
        val network = FakeNetwork()
        body(rig, network, SyncLoop(rig.engine, rig.jobs, network, rig.clock, RetryPolicy(), rig.log, rig.dispatch))
    }

    private suspend fun TestScope.after(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun `用户确认之后循环自己醒来做追加：任务表一点变化都没有也一样`() = loopRig { rig, _, loop ->
        val preview = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = rig.transcribed()
        loop.start(backgroundScope)
        runCurrent()
        assertEquals("文字先入库了", 1, rig.server.putCount)
        assertEquals(0, rig.server.additionCount)

        rig.dispatch.decide(key, preview.destinationId, Choice.CONFIRM, rig.clock.now())
        runCurrent()

        assertEquals(1, rig.server.additionCount)
        assertEquals(1, rig.server.deliveries.size)
        assertEquals(GatewayDelivery.CREATED, rig.dispatch.item(key)!!.targets.single().delivery)
    }

    @Test
    fun `追加失败：引擎排好重试时间，到点循环自己再试，不需要任何别的触发；做成之后不再请求`() = loopRig { rig, _, loop ->
        val preview = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = rig.transcribed()
        loop.start(backgroundScope)
        runCurrent()
        rig.server.faults += Fault.Reject(503, "GATEWAY_UNREACHABLE", retryable = true)

        rig.dispatch.decide(key, preview.destinationId, Choice.CONFIRM, rig.clock.now())
        runCurrent()
        assertTrue("第一次追加被网关暂时拒绝", rig.server.deliveries.isEmpty())
        assertEquals(1, rig.server.additionCount)

        after(20 * 60_000L)

        assertEquals("到点自己重试，做成了", 1, rig.server.deliveries.size)
        assertEquals(2, rig.server.additionCount)
        after(2 * 60 * 60_000L)
        assertEquals("做成之后循环不再为它请求网关", 2, rig.server.additionCount)
    }

    @Test
    fun `断网期间不做追加，不碰网关；网络回来才做`() = loopRig { rig, network, loop ->
        val preview = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = rig.transcribed()
        loop.start(backgroundScope)
        runCurrent()
        network.set(false)
        runCurrent()
        val before = rig.lan.attempts.size

        rig.dispatch.decide(key, preview.destinationId, Choice.CONFIRM, rig.clock.now())
        runCurrent()
        after(60 * 60_000L)
        assertEquals("断网期间一次连接尝试都没有", before, rig.lan.attempts.size)
        assertEquals(0, rig.server.additionCount)

        network.set(true)
        runCurrent()

        assertEquals(1, rig.server.additionCount)
        assertEquals(1, rig.server.deliveries.size)
    }

    @Test
    fun `进程重启后循环一启动就把账上欠的追加做完`() = loopRig { rig, _, _ ->
        val preview = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = rig.transcribed()
        rig.engine.runOnce()
        rig.dispatch.decide(key, preview.destinationId, Choice.CONFIRM, rig.clock.now())
        rig.restart() // 确认已经落盘，追加一个请求都还没发
        assertEquals(0, rig.server.additionCount)

        SyncLoop(rig.engine, rig.jobs, FakeNetwork(), rig.clock, RetryPolicy(), rig.log, rig.dispatch).start(backgroundScope)
        runCurrent()

        assertEquals(1, rig.server.additionCount)
        assertEquals(1, rig.server.deliveries.size)
    }
}
