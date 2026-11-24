@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.android.destinations

import app.auri.sync.destinations.DeliveryView
import app.auri.sync.destinations.GatewayStage
import app.auri.sync.destinations.UnknownReason
import app.auri.sync.engine.JobKey
import app.auri.sync.gateway.SendMode
import app.auri.sync.policy.DataFlowState
import app.auri.sync.testing.DispatchRig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第四轮 F8：打开“发送”页（目的地列表）与目的地详情页时自己去读（列表、状态）是**自动读**：文字不能进网关
 * （没授权、或“严格仅本地”开着）时一个请求也不发，页面如实说明、不拿旧数据装作最新；用户自己点的“刷新”“刷新状态”照常联系网关。
 *
 * 服务、引擎、视图模型是真的，网关是假网关；“一个请求也不发”从假局域网的连接记录直接看（含健康检查）。
 * 同步引擎那一侧的规矩（授权是给某一台网关的、世代、每个请求交出去前一刻再比）在 :sync 的 AutomaticReadsTest 里摆过；这里证明界面层选对了入口。
 */
class AutomaticReadsViewModelTest {
    private fun DispatchRig.forgetTraffic() {
        lan.attempts.clear()
        lan.delivered.clear()
        discovery.asked.clear()
    }

    private fun DispatchRig.contacts(): List<Any> = lan.attempts.toList() + discovery.asked.toList()

    // ---- 目的地列表 ----

    private val listOpenedWithoutPermission = listOf(
        "没有授权文字进网关" to DataFlowState.Unset,
        "严格仅本地开着（哪怕授权过）" to DataFlowState(gatewayTextGranted = true, strictLocal = true),
    )

    @Test
    fun `列表页：文字不能进网关时打开页面不联系网关，页面如实说显示的是本机缓存，缓存里的目的地照常列出，也没有“没做成”的提示`() {
        for ((why, policy) in listOpenedWithoutPermission) {
            runTest {
                val rig = DispatchRig(this, policyState = policy)
                rig.createDestination("写作助手", SendMode.AUTO_SEND) // 用户自己建的：照常联系网关，缓存里有它
                rig.forgetTraffic()

                val viewModel = DestinationsViewModel(rig.service, backgroundScope, rig.log)
                settle()

                assertEquals("$why：打开页面一个请求也不发", emptyList<Any>(), rig.contacts())
                assertTrue("$why：页面要说显示的是本机缓存", viewModel.state.value.cacheOnly)
                assertNull("$why：不是“没做成”，没有错误提示", viewModel.state.value.problem)
                assertEquals(listOf("写作助手"), viewModel.state.value.destinations.map { it.name })
            }
        }
    }

    @Test
    fun `列表页：用户自己点“刷新”照常联系网关，联系成功之后不再说只有本机缓存`() = runTest {
        val rig = DispatchRig(this, policyState = DataFlowState.Unset)
        rig.server.addDestinations(1) // 别处建的：这部手机的缓存里没有
        val viewModel = DestinationsViewModel(rig.service, backgroundScope, rig.log)
        settle()
        assertTrue("前提：自动刷新被拦下", viewModel.state.value.cacheOnly)
        assertEquals("前提：缓存里没有那一个", emptyList<String>(), viewModel.state.value.destinations.map { it.name })

        viewModel.refresh()
        settle()

        assertTrue("点“刷新”是用户发起的：请求发了", rig.lan.delivered.any { it.second.path == "/v1/destinations" })
        assertFalse("联系成功，缓存就是网关上的最新情况", viewModel.state.value.cacheOnly)
        assertEquals(1, viewModel.state.value.destinations.size)
    }

    @Test
    fun `列表页：自动刷新被拦下之后用户点“刷新”但连不上网关：显示错误，不再说“现在不会自动联系网关”`() = runTest {
        val rig = DispatchRig(this, policyState = DataFlowState.Unset)
        rig.lan.hosts.clear() // 网关连不上
        val viewModel = DestinationsViewModel(rig.service, backgroundScope, rig.log)
        settle()
        assertTrue("前提：自动刷新被拦下", viewModel.state.value.cacheOnly)

        viewModel.refresh()
        settle()

        assertFalse("用户已经点过手动刷新并尝试联系网关，不再显示自动读已关闭的提示", viewModel.state.value.cacheOnly)
        assertEquals(app.auri.sync.destinations.ServiceFailure.Unreachable, viewModel.state.value.problem)
    }

    @Test
    fun `列表页：授权了、没开严格仅本地时，打开页面照常自动刷新，不说只有本机缓存`() = runTest {
        val rig = DispatchRig(this)
        rig.server.addDestinations(2)

        val viewModel = DestinationsViewModel(rig.service, backgroundScope, rig.log)
        settle()

        assertEquals(2, viewModel.state.value.destinations.size)
        assertFalse(viewModel.state.value.cacheOnly)
        assertTrue("自动刷新联系了网关", rig.lan.delivered.any { it.second.path == "/v1/destinations" })
    }

    // ---- 目的地详情页 ----

    private class Detail(val rig: DispatchRig, val destinationId: String, val key: JobKey)

    /** 授权时建好目的地、转写、入库；之后用户收回授权（或开了严格仅本地）。 */
    private suspend fun TestScope.storedThenWithdrawn(withdraw: suspend (DispatchRig) -> Unit): Detail {
        val rig = DispatchRig(this)
        val destination = rig.createDestination("写作助手", SendMode.AUTO_SEND)
        val key = rig.transcribed()
        rig.engine.runOnce()
        withdraw(rig)
        rig.forgetTraffic()
        return Detail(rig, destination.destinationId, key)
    }

    private fun Detail.view(viewModel: DestinationDetailViewModel): DeliveryView = viewModel.state.value.items.single { it.key == key }.view

    @Test
    fun `详情页：文字不能进网关时打开页面不联系网关，已入库的那条显示“没有查询”，页面进入时的自动刷新也一样`() {
        val withdrawals = listOf<Pair<String, suspend (DispatchRig) -> Unit>>(
            "收回了授权" to { rig -> rig.policy.setGatewayTextGranted(false) },
            "开了严格仅本地" to { rig -> rig.policy.setStrictLocal(true) },
        )
        for ((why, withdraw) in withdrawals) {
            runTest {
                val page = storedThenWithdrawn(withdraw)

                val viewModel = DestinationDetailViewModel(page.destinationId, page.rig.service, LibraryItemTexts(page.rig.library), backgroundScope, page.rig.log)
                settle()
                viewModel.refreshStatusesAutomatically() // 页面进入时的那一次
                settle()

                assertEquals("$why：打开页面一个请求也不发", emptyList<Any>(), page.rig.contacts())
                assertEquals("$why：说清没有查询，不是旧数据也不是成功", DeliveryView.Unknown(UnknownReason.NOT_ASKED), page.view(viewModel))
            }
        }
    }

    @Test
    fun `详情页：用户自己点“刷新状态”照常去问网关，显示网关的回答`() = runTest {
        val page = storedThenWithdrawn { it.policy.setGatewayTextGranted(false) }
        val viewModel = DestinationDetailViewModel(page.destinationId, page.rig.service, LibraryItemTexts(page.rig.library), backgroundScope, page.rig.log)
        settle()
        assertEquals("前提：自动读没问网关", DeliveryView.Unknown(UnknownReason.NOT_ASKED), page.view(viewModel))

        viewModel.refreshStatuses()
        settle()

        assertEquals("点“刷新状态”是用户发起的：网关怎么说就怎么显示", DeliveryView.OnGateway(GatewayStage.STORED), page.view(viewModel))
        assertTrue(page.rig.lan.delivered.any { it.second.path.endsWith("/status") })
    }

    @Test
    fun `详情页：授权了、没开严格仅本地时，打开页面照常问网关`() = runTest {
        val page = storedThenWithdrawn { }
        val viewModel = DestinationDetailViewModel(page.destinationId, page.rig.service, LibraryItemTexts(page.rig.library), backgroundScope, page.rig.log)
        settle()

        assertEquals(DeliveryView.OnGateway(GatewayStage.STORED), page.view(viewModel))
    }
}
