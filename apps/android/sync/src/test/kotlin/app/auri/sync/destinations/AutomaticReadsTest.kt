@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.sync.destinations

import app.auri.sync.gateway.CreateDestinationRequest
import app.auri.sync.gateway.Destination
import app.auri.sync.gateway.Intent
import app.auri.sync.gateway.SendMode
import app.auri.sync.pairing.GatewayAddress
import app.auri.sync.policy.DataFlowState
import app.auri.sync.testing.DispatchRig
import app.auri.sync.testing.InMemoryDataFlowPolicy
import app.auri.sync.testing.SyncSamples
import app.auri.sync.testing.TlsFixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第四轮 F8：自动读取（页面打开时自己去读：列表、状态；其中每次联系都有健康检查与合同版本探测）在文字不能进网关时一个请求也不发。
 * 用户自己发起的（配对、创建、撤销、签发凭证、点“刷新”）不受影响——它们不带文字，是用户明确要做的。
 *
 * 以前打开“发送”页或目的地详情页，哪怕没授权、哪怕开着“严格仅本地”，也会自己去敲网关（健康检查、合同版本、列表、状态）。
 * 现在自动读先问放行（和同步引擎同一套：世代 + 请求交出去前一刻再比一次），不放行就回 [ServiceFailure.AutomaticReadsOff]，页面显示本机缓存并如实说明。
 * “一个请求也不发”从假局域网的连接记录直接看：[app.auri.sync.testing.FakeLan.attempts]（含健康检查）与假 mDNS 的 asked。
 */
class AutomaticReadsTest {
    private fun run(state: DataFlowState = InMemoryDataFlowPolicy.GRANTED, body: suspend TestScope.(DispatchRig) -> Unit) =
        runTest { body(DispatchRig(this, policyState = state)) }

    private fun <T> ServiceResult<T>.ok(): T = (this as? ServiceResult.Ok<T>)?.value ?: error("期望成功，实际 $this")

    private fun <T> ServiceResult<T>.failure(): ServiceFailure = (this as? ServiceResult.Failed)?.failure ?: error("期望失败，实际 $this")

    private fun DispatchRig.paths(): List<String> = lan.delivered.map { it.second.path }

    private fun assertNoContact(rig: DispatchRig) {
        assertEquals("一个连接尝试都不能有（健康检查也是一次联系）", emptyList<Any>(), rig.lan.attempts.toList())
        assertEquals("问 mDNS 也是一次联系", emptyList<String>(), rig.discovery.asked.toList())
    }

    // ---- 文字不能进网关：自动读一个请求也不发 ----

    @Test
    fun `没有授权文字进网关：自动刷新列表一个请求也不发，回自动读已关闭`() = run(DataFlowState.Unset) { rig ->
        assertEquals(ServiceFailure.AutomaticReadsOff, rig.service.refreshAutomatically().failure())

        assertNoContact(rig)
    }

    @Test
    fun `严格仅本地开着，哪怕授权过：自动刷新列表一个请求也不发`() = run(DataFlowState(gatewayTextGranted = true, strictLocal = true)) { rig ->
        assertEquals(ServiceFailure.AutomaticReadsOff, rig.service.refreshAutomatically().failure())

        assertNoContact(rig)
    }

    @Test
    fun `授权设置读不出来：按最保守的算，自动刷新列表一个请求也不发`() = run { rig ->
        rig.policy.unreadable = true

        assertEquals(ServiceFailure.AutomaticReadsOff, rig.service.refreshAutomatically().failure())

        assertNoContact(rig)
    }

    @Test
    fun `授权是给另一台网关的：对配对着的这一台不算数，自动刷新一个请求也不发`() = run { rig ->
        rig.policy.grantTo(SyncSamples.pairedGateway(pin = TlsFixtures.gatewayB.pin).copy(gatewayId = DispatchRig.GATEWAY_B_ID).identity)

        assertEquals(ServiceFailure.AutomaticReadsOff, rig.service.refreshAutomatically().failure())

        assertNoContact(rig)
    }

    @Test
    fun `还没配对：自动刷新回没配对，一个请求也不发（也不去问授权）`() = runTest {
        val rig = DispatchRig(this, gateway = null)

        assertEquals(ServiceFailure.NotPaired, rig.service.refreshAutomatically().failure())

        assertNoContact(rig)
    }

    @Test
    fun `授权了、没开严格仅本地：自动刷新照常联系网关（健康检查、合同版本、列表），本机缓存跟着更新`() = run { rig ->
        rig.server.addDestinations(2)

        val listed = rig.service.refreshAutomatically().ok()

        assertEquals(2, listed.size)
        assertEquals(2, rig.dispatch.destinations().size)
        assertTrue("三种请求都发了：${rig.paths()}", rig.paths().containsAll(listOf("/health", "/v1/capabilities", "/v1/destinations")))
    }

    // ---- 用户自己发起的不受影响 ----

    @Test
    fun `没有授权文字进网关：用户自己点刷新、创建、签发凭证、撤销都照常联系网关`() = run(DataFlowState.Unset) { rig ->
        val created = rig.service.create(CreateDestinationRequest("写作助手", SendMode.AUTO_SEND, setOf(Intent.CONTEXT))).ok()
        assertEquals(listOf(created.destinationId), rig.service.refresh().ok().map { it.destinationId })
        assertTrue("核验通过，凭证签发", rig.service.issueCredential(created.destinationId) is CredentialOutcome.Issued)
        assertTrue(rig.service.revoke(created.destinationId).ok().revokedAt != null)

        assertTrue("这些都是用户发起的：请求发了", rig.paths().isNotEmpty())
        assertTrue(rig.paths().contains("/v1/destinations"))
    }

    // ---- 状态查询（详情页）：不问网关，说清“没有查询” ----

    @Test
    fun `文字不能进网关时自动读状态：不问网关，要问才说得清的是没有查询，本机账上说得清的照常给；手动读才去问`() = run { rig ->
        val auto = rig.createDestination("写作助手", SendMode.AUTO_SEND)
        val preview = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = rig.transcribed()
        rig.engine.runOnce() // 文字已入库，两个目的地各有一条目标
        rig.policy.setGatewayTextGranted(false) // 之后用户收回了授权
        rig.lan.attempts.clear()
        rig.lan.delivered.clear()
        rig.discovery.asked.clear()

        val automatic = rig.service.deliveriesAutomatically(auto.destinationId, 10).single { it.item.key == key }.view
        val waiting = rig.service.deliveriesAutomatically(preview.destinationId, 10).single { it.item.key == key }.view

        assertEquals("自动读不问网关，说清没有查询", DeliveryView.Unknown(UnknownReason.NOT_ASKED), automatic)
        assertEquals("等用户决定是本机账上就说得清的，不受影响", DeliveryView.AwaitingYou, waiting)
        assertNoContact(rig)

        val manual = rig.service.deliveries(auto.destinationId, 10).single { it.item.key == key }.view
        assertEquals("用户点“刷新状态”才去问：网关怎么说就怎么显示", DeliveryView.OnGateway(GatewayStage.STORED), manual)
        assertTrue("手动读联系了网关", rig.lan.attempts.isNotEmpty())
    }

    @Test
    fun `授权了、没开严格仅本地：自动读状态照常问网关`() = run { rig ->
        val auto = rig.createDestination("写作助手", SendMode.AUTO_SEND)
        val key = rig.transcribed()
        rig.engine.runOnce()

        val view = rig.service.deliveriesAutomatically(auto.destinationId, 10).single { it.item.key == key }.view

        assertEquals(DeliveryView.OnGateway(GatewayStage.STORED), view)
    }

    // ---- 自动读进行中授权被收回：每个请求交出去的前一刻再比一次，后面的一个也不发 ----

    @Test
    fun `自动刷新进行中授权被收回（健康检查在路上）：合同版本与列表都不发，缓存不动`() = run { rig ->
        rig.server.addDestinations(1)
        rig.server.onRequest = { request -> if (request.path == "/health") rig.policy.setGatewayTextGranted(false) }

        assertEquals(ServiceFailure.AutomaticReadsOff, rig.service.refreshAutomatically().failure())

        assertEquals("只有那个已经交出去的健康检查：${rig.paths()}", listOf("/health"), rig.paths())
        assertEquals("列表没有请求，本机缓存不动", emptyList<Destination>(), rig.dispatch.destinations())
    }

    @Test
    fun `自动刷新进行中授权被收回（合同版本在路上）：列表请求不发，缓存不动`() = run { rig ->
        rig.server.addDestinations(1)
        rig.server.onRequest = { request -> if (request.path == "/v1/capabilities") rig.policy.setGatewayTextGranted(false) }

        assertEquals(ServiceFailure.AutomaticReadsOff, rig.service.refreshAutomatically().failure())

        assertEquals("健康检查与合同版本发了，列表没发：${rig.paths()}", listOf("/health", "/v1/capabilities"), rig.paths())
        assertEquals(emptyList<Destination>(), rig.dispatch.destinations())
    }

    @Test
    fun `自动刷新找网关时问 mDNS 的当口授权被收回：找到的地址一个也不探测`() = runTest {
        val rig = DispatchRig(this, gateway = SyncSamples.pairedGateway(addresses = listOf(SyncSamples.NAS_ADDRESS))) // 记下的地址连不上，只能靠 mDNS
        rig.discovery.result = {
            rig.policy.setGatewayTextGranted(false) // 查询在路上时用户收回授权
            listOf(SyncSamples.LAN_ADDRESS)
        }

        assertEquals(ServiceFailure.AutomaticReadsOff, rig.service.refreshAutomatically().failure())

        assertEquals("只有那个记下的（连不上的）地址被试过，mDNS 找到的地址一次探测也没有", listOf(SyncSamples.NAS_ADDRESS), rig.lan.attempts.toList())
    }

    @Test
    fun `自动读状态进行中授权被收回（第一条的状态查询在路上）：后面的状态查询不发，没问的记没有查询`() = run { rig ->
        val auto = rig.createDestination("写作助手", SendMode.AUTO_SEND)
        rig.transcribed("第一条")
        rig.transcribed("第二条")
        rig.engine.runOnce()
        rig.lan.delivered.clear()
        rig.server.onRequest = { request -> if (request.path.endsWith("/status")) rig.policy.setGatewayTextGranted(false) }

        val views = rig.service.deliveriesAutomatically(auto.destinationId, 10).map { it.view }

        assertEquals("状态查询只交出去了一个：${rig.paths()}", 1, rig.paths().count { it.endsWith("/status") })
        assertEquals("已经交出去的那个照常用它的回答", 1, views.count { it == DeliveryView.OnGateway(GatewayStage.STORED) })
        assertEquals("没来得及问的说清没有查询", listOf(DeliveryView.Unknown(UnknownReason.NOT_ASKED)), views.filter { it is DeliveryView.Unknown })
    }

    @Test
    fun `自动读状态找网关时问 mDNS 的当口授权被收回：各条记没有查询，不说连不上，找到的地址一次探测也没有`() = run { rig ->
        val auto = rig.createDestination("写作助手", SendMode.AUTO_SEND)
        val key = rig.transcribed()
        rig.engine.runOnce()
        val elsewhere = GatewayAddress("192.168.1.77", 8443) // 网关换了地方：记下的地址都没有人了，只有 mDNS 找得到
        rig.lan.hosts.remove(SyncSamples.LAN_ADDRESS)
        rig.lan.host(elsewhere, TlsFixtures.gatewayA) { rig.server.handle(it) }
        rig.discovery.result = {
            rig.policy.setGatewayTextGranted(false) // 查询在路上时用户收回授权
            listOf(elsewhere)
        }
        rig.lan.attempts.clear()

        val view = rig.service.deliveriesAutomatically(auto.destinationId, 10).single { it.item.key == key }.view

        assertEquals("不是连不上，是没有去问", DeliveryView.Unknown(UnknownReason.NOT_ASKED), view)
        assertTrue("mDNS 找到的地址一次探测也没有：${rig.lan.attempts}", rig.lan.attempts.none { it == elsewhere })
    }

    @Test
    fun `自动刷新问完授权的当口用户换了配对：请求只发给问过授权的那一台，新配对的网关一个请求也没有，回答也不写进缓存`() = run { rig ->
        rig.server.addDestinations(1)
        rig.policy.onRead = {
            rig.policy.onRead = null
            rig.repairWithAnotherGateway(grant = false) // 授权刚问完，配对被换成了没有同意过的另一台（清理授权的那一步还没跑到）
        }

        assertEquals(ServiceFailure.NotPaired, rig.service.refreshAutomatically().failure())

        assertTrue("新配对的网关一个请求也没有：${rig.lan.attempts}", rig.lan.attempts.none { it == DispatchRig.ADDRESS_B })
        assertEquals("旧配对的回答不写进新配对的缓存", emptyList<Destination>(), rig.dispatch.destinations())
    }
}
