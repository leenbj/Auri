@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.sync.policy

import app.auri.sync.destinations.Choice
import app.auri.sync.destinations.GatewayDelivery
import app.auri.sync.engine.JobKey
import app.auri.sync.gateway.SendMode
import app.auri.sync.machine.SyncState
import app.auri.sync.testing.DispatchRig
import app.auri.sync.testing.FakeGateway.Fault
import app.auri.sync.testing.SyncRig
import java.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复评 R4（BLOCKER）：放行不能只在“发请求之前”问一次。
 * 一个网络往返要花时间，用户随时可能收回授权（或打开严格仅本地）。以前引擎只在联系网关之前问了几次，
 * 之后的状态查询、PUT 全凭“更早的那次放行”发出去：回答在路上时用户点了收回，回答一到，文字照样发了。
 * 现在：每个网络回答回来之后、下一步之前都重新问一遍；PUT 与追加请求的前一步就是问，中间只有本地计算。
 *
 * 交错是确定的，不靠睡眠：假网关在“请求已经到了、回答还在路上”的这一刻（[app.auri.sync.testing.FakeGateway.onRequest]）
 * 替用户收回授权，所以手机收到回答的时候，授权已经没了。
 */
class PolicyAfterResponseTest {
    private fun runSync(body: suspend TestScope.(SyncRig) -> Unit) = runTest { body(SyncRig(this)) }

    private fun runDispatch(body: suspend TestScope.(DispatchRig) -> Unit) = runTest { body(DispatchRig(this)) }

    /** 第一次 PUT 在路上丢了：任务记成“结果不确定”，下一次发送之前要先问网关；走到重试时间。 */
    private suspend fun SyncRig.becomeUncertain(key: JobKey) {
        server.faults += Fault.LoseRequest
        engine.runOnce()
        assertTrue("请求发出去了却没拿到结果：必须记成不确定", job(key).uncertain)
        scope.advanceTimeBy(Duration.between(clock.now(), job(key).retryAt!!).toMillis())
    }

    // ---- 同步任务：每个回答回来之后都重新问 ----

    @Test
    fun `问网关结果的回答在路上时授权被收回：回答回来之后一个 PUT 也不再发，任务退回等待，仍标着不确定`() = runSync { rig ->
        val key = rig.publish()
        rig.becomeUncertain(key)
        rig.server.onRequest = { request -> if (request.path.endsWith("/status")) rig.policy.setGatewayTextGranted(false) }

        rig.engine.runOnce()

        assertEquals("状态查询发出去了（那时授权还在）", 1, rig.server.statusCount)
        assertEquals("回答回来时授权已经收回：不能再 PUT（只有最初那一次，它还在路上丢了）", 1, rig.server.putCount)
        val job = rig.job(key)
        assertEquals(SyncState.WAITING_GATEWAY, job.state)
        assertTrue("上一次可能已经送到的事实没有变：仍然不确定，之后还要先问网关", job.uncertain)

        rig.server.onRequest = null
        rig.policy.setGatewayTextGranted(true)
        rig.engine.runOnce()

        assertEquals(SyncState.STORED, rig.job(key).state)
        assertEquals("重新授权之后先问、网关说没有，才重新提交：一共两次 PUT", 2, rig.server.putCount)
        assertEquals(2, rig.server.statusCount)
    }

    @Test
    fun `定位网关的回答在路上时授权被收回：不再发合同版本探测，也不发状态查询和 PUT，网关那边什么也没收到`() = runSync { rig ->
        val key = rig.publish()
        rig.server.onRequest = { request -> if (request.path == "/health") rig.policy.setGatewayTextGranted(false) }

        rig.engine.runOnce()

        assertEquals("离开手机的只有那一次健康检查：${rig.lan.delivered.map { it.second.path }}", listOf("/health"), rig.lan.delivered.map { it.second.path })
        assertEquals("连合同版本探测都不该发", 0, rig.server.capabilityCalls.get())
        assertTrue("网关不该收到任何入库调用：${rig.server.calls}", rig.server.calls.isEmpty())
        val job = rig.job(key)
        assertEquals(SyncState.WAITING_GATEWAY, job.state)
        assertTrue("请求根本没离开手机：不是“可能已送到”", !job.uncertain)

        rig.server.onRequest = null
        rig.policy.setGatewayTextGranted(true)
        rig.engine.runOnce()

        assertEquals(SyncState.STORED, rig.job(key).state)
        assertEquals("重新授权后正好发一次", 1, rig.server.putCount)
    }

    @Test
    fun `合同版本探测的回答在路上时授权被收回（结果不确定的任务）：不再问状态、不再 PUT，仍标着不确定`() = runSync { rig ->
        val key = rig.publish()
        rig.becomeUncertain(key)
        val restarted = rig.newEngine() // 进程重启：合同版本要重新问一次
        rig.server.onRequest = { request -> if (request.path == "/v1/capabilities") rig.policy.setGatewayTextGranted(false) }

        restarted.runOnce()

        assertEquals("合同版本探测发出去了（那时授权还在）", 2, rig.server.capabilityCalls.get())
        assertEquals("之后一个状态查询也不该有", 0, rig.server.statusCount)
        assertEquals("也不该再 PUT（只有最初丢了的那一次）", 1, rig.server.putCount)
        val job = rig.job(key)
        assertEquals(SyncState.WAITING_GATEWAY, job.state)
        assertTrue("仍然不确定", job.uncertain)
    }

    // ---- 追加 Delivery：每一个追加请求前都问 ----

    @Test
    fun `追加：定位网关的回答在路上时授权被收回：一个追加请求也不发，账上仍欠着，授权恢复后补发恰好一份`() = runDispatch { rig ->
        val preview = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = rig.transcribed()
        rig.engine.runOnce()
        rig.dispatch.decide(key, preview.destinationId, Choice.CONFIRM, rig.clock.now())
        rig.restart() // 进程重启：合同版本要重新问一次，所以“定位之后收回”的缝里，引擎若照发探测就会露出来
        val probesBefore = rig.server.capabilityCalls.get()
        rig.server.onRequest = { request -> if (request.path == "/health") rig.policy.setGatewayTextGranted(false) }

        rig.engine.runOnce()

        assertEquals("定位网关的回答回来时授权已经收回：连合同版本探测都不该发", probesBefore, rig.server.capabilityCalls.get())
        assertEquals("授权收回之后一个追加请求也不许发", 0, rig.server.additionCount)
        assertTrue(rig.server.deliveries.isEmpty())
        assertEquals("账上仍欠着", GatewayDelivery.NOT_YET, rig.dispatch.item(key)!!.targets.single().delivery)

        rig.server.onRequest = null
        rig.policy.setGatewayTextGranted(true)
        rig.engine.runOnce()

        assertEquals(1, rig.server.additionCount)
        assertEquals(GatewayDelivery.CREATED, rig.dispatch.item(key)!!.targets.single().delivery)
    }

    @Test
    fun `追加：第一个追加请求的回答在路上时授权被收回：第二个不再发，授权恢复后补上`() = runDispatch { rig ->
        val first = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val second = rig.createDestination("Hermes", SendMode.PREVIEW_CONFIRM)
        val key = rig.transcribed()
        rig.engine.runOnce()
        rig.dispatch.decide(key, first.destinationId, Choice.CONFIRM, rig.clock.now())
        rig.dispatch.decide(key, second.destinationId, Choice.CONFIRM, rig.clock.now())
        rig.server.onRequest = { request -> if (request.method == "POST" && request.path.endsWith("/deliveries")) rig.policy.setGatewayTextGranted(false) }

        rig.engine.runOnce()

        assertEquals("第一个已经在路上（那时授权还在），第二个不许发", 1, rig.server.additionCount)

        rig.server.onRequest = null
        rig.policy.setGatewayTextGranted(true)
        rig.engine.runOnce()

        assertEquals("授权恢复后只补第二个", 2, rig.server.additionCount)
        assertEquals(2, rig.server.deliveries.size)
    }
}
