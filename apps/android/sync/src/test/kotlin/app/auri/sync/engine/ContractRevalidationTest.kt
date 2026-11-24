@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.sync.engine

import app.auri.sync.destinations.Choice
import app.auri.sync.destinations.GatewayDelivery
import app.auri.sync.destinations.ServiceFailure
import app.auri.sync.destinations.ServiceResult
import app.auri.sync.gateway.ContractAnswer
import app.auri.sync.gateway.ContractGuard
import app.auri.sync.gateway.GatewayClient
import app.auri.sync.gateway.SendMode
import app.auri.sync.machine.SyncState
import app.auri.sync.pairing.NeedsUser
import app.auri.sync.testing.DispatchRig
import app.auri.sync.testing.FakeGateway
import app.auri.sync.testing.FakeGateway.Fault
import app.auri.sync.testing.ScriptedExchange
import app.auri.sync.testing.SyncRig
import app.auri.sync.testing.SyncSamples
import app.auri.sync.testing.TlsFixtures
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 再评 S6（MEDIUM）：“网关兼容”的结论不能在进程里永远有效。
 *
 * 以前 [app.auri.sync.gateway.ContractGuard] 把“兼容”记在内存里到进程结束：兼容过的网关后来被降级（换回只会 1.1 的旧版本）而手机没重启，
 * 之后的每个请求带着 1.2 过去、被旧网关 422 拒绝，引擎把 422 当成“请求不合规”——任务被**永久**拒绝，更新网关也救不回来。
 * 现在：网关断过线（连不上、中断、平台说没网）之后重新问；任何“请求不合规”的拒绝在判成永久之前，先作废记忆、再问一次网关会哪些版本——
 * 问出来太旧就按“网关太旧”处理（可重试，更新网关后自动恢复），问不出来也只是等，**永远不会因此永久拒绝**；
 * 只有网关刚刚确认还说 1.2、请求仍被拒，才是请求本身的问题，照旧永久拒绝。
 *
 * 交错是确定的，不靠睡眠：用假网关的 [supportedVersions] 在两次请求之间改版本，用假局域网摘掉/装回网关表示断线与重连。
 */
class ContractRevalidationTest {
    private val oldGateway = listOf("1.1")
    private val currentGateway = listOf("1.2")

    private fun SyncRig.gatewayGoesOffline() {
        lan.hosts.remove(SyncSamples.LAN_ADDRESS)
    }

    private fun SyncRig.gatewayComesBack() {
        lan.host(SyncSamples.LAN_ADDRESS, TlsFixtures.gatewayA) { server.handle(it) }
    }

    @Test
    fun `兼容过的网关在两次请求之间被降级：旧网关的 422 不是永久拒绝，重新问出太旧，文字回到等待，网关升级后照常入库`() = runTest {
        val rig = SyncRig(this)
        val first = rig.publish(SyncSamples.RECORD_ID)
        rig.engine.runOnce()
        assertEquals("前提：兼容，已经记住了", SyncState.STORED, rig.job(first).state)
        rig.server.supportedVersions = oldGateway // 网关被降级，手机进程没有重启
        val second = rig.publish(SyncSamples.OTHER_RECORD_ID)

        rig.engine.runOnce()

        val job = rig.job(second)
        assertNotEquals("旧网关的 422 不能把文字永久拒绝：更新网关之后它还要能发出去（拒绝原因：${job.blockedReason}）", SyncState.BLOCKED, job.state)
        assertEquals(SyncState.WAITING_GATEWAY, job.state)
        assertNull(job.blockedReason)
        assertFalse("被明确拒绝的请求没有入库：不留“可能已送到”", job.uncertain)
        assertEquals("拒绝之后作废了兼容的记忆、重新问了版本", 2, rig.server.capabilityCalls.get())
        assertEquals("配对记录标成网关太旧（要用户更新网关，不是重新配对）", NeedsUser.GATEWAY_TOO_OLD, rig.pairing.gateway!!.needsUser)
        assertEquals("拒绝它的那一次 PUT 是发现降级的代价，之后不再往旧网关发", 2, rig.server.putCount)

        rig.server.supportedVersions = currentGateway
        rig.engine.runOnce()

        assertEquals(SyncState.STORED, rig.job(second).state)
        assertNull("标记自己清掉，不需要重新配对", rig.pairing.gateway!!.needsUser)
        assertEquals(3, rig.server.putCount)
    }

    @Test
    fun `网关断过线再回来、回来时已经是旧版本：不拿记着的兼容去发，先重新问，一个数据请求都不发给旧网关`() = runTest {
        val rig = SyncRig(this)
        val first = rig.publish(SyncSamples.RECORD_ID)
        rig.engine.runOnce()
        assertEquals(SyncState.STORED, rig.job(first).state)
        val second = rig.publish(SyncSamples.OTHER_RECORD_ID)
        rig.gatewayGoesOffline()
        rig.engine.runOnce() // 连不上：第二条等待网关
        assertEquals(SyncState.WAITING_GATEWAY, rig.job(second).state)
        rig.server.supportedVersions = oldGateway
        rig.gatewayComesBack()

        rig.engine.runOnce()

        assertEquals("断线重连之后重新问了版本（第一次 + 这一次）", 2, rig.server.capabilityCalls.get())
        assertEquals("没有任何数据请求发给降级后的网关", 1, rig.server.putCount)
        assertEquals(SyncState.WAITING_GATEWAY, rig.job(second).state)
        assertNull(rig.job(second).blockedReason)
        assertEquals(NeedsUser.GATEWAY_TOO_OLD, rig.pairing.gateway!!.needsUser)
    }

    @Test
    fun `平台报告没网又恢复：同样重新问，降级后的网关一个数据请求都收不到`() = runTest {
        val rig = SyncRig(this)
        rig.publish(SyncSamples.RECORD_ID)
        rig.engine.runOnce()
        val second = rig.publish(SyncSamples.OTHER_RECORD_ID)
        rig.engine.networkLost()
        rig.server.supportedVersions = oldGateway
        rig.engine.networkChanged()

        rig.engine.runOnce()

        assertEquals("没网的那一下作废了记忆，恢复后重新问", 2, rig.server.capabilityCalls.get())
        assertEquals(1, rig.server.putCount)
        assertNotEquals(SyncState.BLOCKED, rig.job(second).state)
    }

    @Test
    fun `网关仍然说 1_2、请求照样被 422：重新问一次确认之后才是永久拒绝（请求本身有问题），不循环、不冤枉网关太旧`() = runTest {
        val rig = SyncRig(this)
        rig.publish(SyncSamples.RECORD_ID)
        rig.engine.runOnce() // 兼容，记住了
        val second = rig.publish(SyncSamples.OTHER_RECORD_ID)
        rig.server.faults += Fault.Reject(422, "REQUEST_INVALID", retryable = false)

        rig.engine.runOnce()

        assertEquals(SyncState.BLOCKED, rig.job(second).state)
        assertEquals(BlockedReason.REQUEST_REJECTED, rig.job(second).blockedReason)
        assertEquals("拒绝之后问了一次，网关确认还说 1.2", 2, rig.server.capabilityCalls.get())
        assertNull("网关没有旧：不能打“网关太旧”的标记", rig.pairing.gateway!!.needsUser)
        assertEquals("只发了这一次，没有循环重发", 2, rig.server.putCount)
    }

    @Test
    fun `被 422 拒绝后重新问版本也没问成：只是等，不永久拒绝、也不说网关太旧；问得到了、请求不再被拒就入库`() = runTest {
        val rig = SyncRig(this)
        rig.publish(SyncSamples.RECORD_ID)
        rig.engine.runOnce()
        val second = rig.publish(SyncSamples.OTHER_RECORD_ID)
        rig.server.faults += Fault.Reject(422, "REQUEST_INVALID", retryable = false)
        rig.server.capabilitiesFault = Fault.Reject(503, "GATEWAY_UNREACHABLE", retryable = true)

        rig.engine.runOnce()

        assertEquals("没问成：不能当永久拒绝", SyncState.WAITING_GATEWAY, rig.job(second).state)
        assertNull(rig.job(second).blockedReason)
        assertNull("没确认不是太旧：不冤枉网关", rig.pairing.gateway!!.needsUser)

        rig.engine.runOnce()

        assertEquals(SyncState.STORED, rig.job(second).state)
    }

    @Test
    fun `追加 Delivery 被旧网关拒绝：不记成永久拒绝，欠的追加还在，网关升级后补上`() = runTest {
        val rig = DispatchRig(this)
        val preview = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = rig.transcribed()
        rig.engine.runOnce()
        assertEquals("前提：文字入库了，兼容已经记住", 1, rig.server.putCount)
        rig.dispatch.decide(key, preview.destinationId, Choice.CONFIRM, rig.clock.now())
        rig.server.supportedVersions = oldGateway // 用户确认之后、追加之前，网关被降级

        rig.engine.runOnce()

        val target = rig.dispatch.item(key)!!.targets.single { it.destinationId == preview.destinationId }
        assertNotEquals("旧网关的拒绝不能让这个目标永久没有 Delivery（原因：${target.refusal}）", GatewayDelivery.REFUSED, target.delivery)
        assertEquals(listOf(preview.destinationId), rig.dispatch.due().map { it.destinationId })
        assertEquals(NeedsUser.GATEWAY_TOO_OLD, rig.pairing.gateway!!.needsUser)
        assertEquals("被拒绝的那一次追加是发现降级的代价", 1, rig.server.additionCount)

        rig.server.supportedVersions = currentGateway
        rig.engine.runOnce()

        assertEquals("升级之后补上了：Delivery 建出来了", listOf(preview.destinationId), rig.server.deliveries.map { it.destinationId })
        assertTrue(rig.dispatch.due().isEmpty())
    }

    @Test
    fun `已经在路上的版本询问遇到作废：答复交给提问的人，但不被记住——否则作废之前的网关说的话会把记忆又写回去`() = runTest {
        val server = FakeGateway(now = { Instant.EPOCH })
        val gate = CompletableDeferred<Unit>()
        val exchange = ScriptedExchange { _, request ->
            if (!gate.isCompleted) gate.await() // 第一次询问停在网关回答之前，放行之后的都直通
            server.handle(request)
        }
        val guard = ContractGuard()
        val client = GatewayClient(exchange)
        val gateway = SyncSamples.pairedGateway()
        val inFlight = async { guard.check(gateway, SyncSamples.LAN_ADDRESS, client) }
        runCurrent()
        assertEquals("前提：第一次询问已经发出、还没有回答", 1, exchange.calls.size)

        guard.invalidate()
        gate.complete(Unit)

        assertEquals("提问的人照常拿到答复", ContractAnswer.COMPATIBLE, inFlight.await())
        guard.check(gateway, SyncSamples.LAN_ADDRESS, client)
        assertEquals("作废之前发出的询问，答复不被记住：这一次又问了一遍", 2, server.capabilityCalls.get())
    }

    // ---- 管理页与同步引擎共用同一个 ContractGuard：管理页也不能拿过期的“兼容”继续发 ----

    @Test
    fun `管理页：兼容之后网关被降级，第一次请求失败就作废记忆，下一次操作重新问出太旧，不再发管理请求`() = runTest {
        val rig = DispatchRig(this)
        rig.createDestination("写作助手", SendMode.AUTO_SEND) // 兼容，记住了
        rig.server.supportedVersions = oldGateway
        val first = rig.service.refresh()
        assertTrue("前提：记着兼容，请求发出去了，旧网关回的东西读不懂：$first", first is ServiceResult.Failed)
        val sent = rig.server.adminCalls.size

        val second = rig.service.refresh()

        assertEquals(ServiceFailure.NeedsRepair(NeedsUser.GATEWAY_TOO_OLD), (second as ServiceResult.Failed).failure)
        assertEquals("重新问了版本，没有再发管理请求", sent, rig.server.adminCalls.size)
    }

    @Test
    fun `管理页：连不上过再回来、回来时是旧网关，不拿记着的兼容去发`() = runTest {
        val rig = DispatchRig(this)
        rig.createDestination("写作助手", SendMode.AUTO_SEND)
        rig.lan.hosts.remove(SyncSamples.LAN_ADDRESS)
        assertEquals(ServiceFailure.Unreachable, (rig.service.refresh() as ServiceResult.Failed).failure)
        rig.server.supportedVersions = oldGateway
        rig.lan.host(SyncSamples.LAN_ADDRESS, TlsFixtures.gatewayA) { rig.server.handle(it) }
        val sent = rig.server.adminCalls.size

        val result = rig.service.refresh()

        assertEquals(ServiceFailure.NeedsRepair(NeedsUser.GATEWAY_TOO_OLD), (result as ServiceResult.Failed).failure)
        assertEquals("重新问了版本，没有再发管理请求", sent, rig.server.adminCalls.size)
    }
}
