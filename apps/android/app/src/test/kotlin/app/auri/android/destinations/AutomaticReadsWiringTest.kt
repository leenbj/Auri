package app.auri.android.destinations

import app.auri.android.netguard.SourceText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第四轮 F8 的接线守卫：行为在 :sync 的 AutomaticReadsTest 与本目录的 AutomaticReadsViewModelTest 里摆过；
 * 这里钉住 JVM 里看不到、又一旦忘了就没有任何测试会红的接线（做法同 PolicyGateWiringTest：读去掉注释与字符串的生产源码）：
 * 1. 页面进入时的刷新走自动入口（`refreshStatusesAutomatically`），两个“刷新”按钮才走用户发起的入口——入口接反，要么打开页面照样敲网关，要么按钮在没授权时失灵；
 * 2. 目的地列表打开页面时是自动刷新（`startRefresh(automatic = true)`），`refresh()` 才是用户发起的；
 * 3. 运行时装配把真正的授权设置交给服务（和同步引擎用的是同一个对象，授权世代才是同一个）；
 * 4. 自动刷新被拦下时，列表页屏幕把“显示的是本机缓存”那一句话摆出来（Compose 屏幕 JVM 里不跑，只钉接线）；
 * 5. 服务里只有两个自动入口问放行（`permitFor(` 恰好两处调用），用户发起的入口用不设防的门（`RequestGate.Open`）——
 *    新增一条自动读的路径忘了问放行，这里红。
 */
class AutomaticReadsWiringTest {
    private val androidDir = File("..")

    private fun code(path: String) = SourceText.withoutCommentsAndStrings(File(androidDir, path).readText()).replace(Regex("""\s+"""), " ")

    @Test
    fun `详情页：页面进入走自动入口，刷新状态按钮走用户发起的入口；详情页的读按触发来源选入口`() {
        val page = code("app/src/main/kotlin/app/auri/android/ui/DestinationsPage.kt")
        val detail = code("app/src/main/kotlin/app/auri/android/destinations/DestinationDetailViewModel.kt")

        assertTrue("页面进入必须走自动入口：$page", "LaunchedEffect(viewModel) { viewModel.refreshStatusesAutomatically() }" in page)
        assertTrue("“刷新状态”按钮必须是用户发起的入口：$page", "onRefresh = viewModel::refreshStatuses" in page)
        assertTrue("按钮入口要记下“这一次是用户点的”：$detail", "fun refreshStatuses() { manualPending.set(true) triggers.trySend(Unit) }" in detail)
        assertTrue("自动入口不能记“用户点的”：$detail", "fun refreshStatusesAutomatically() { triggers.trySend(Unit) }" in detail)
        assertTrue("循环里每次读按“是不是用户点的”选入口：$detail", "loadItems(automatic = !manualPending.getAndSet(false))" in detail)
        assertTrue("自动读用自动入口、用户发起的用普通入口：$detail", "if (automatic) controller.deliveriesAutomatically(destinationId, ITEM_LIMIT) else controller.deliveries(destinationId, ITEM_LIMIT)" in detail)
    }

    @Test
    fun `列表页：打开页面是自动刷新，刷新按钮才是用户发起的`() {
        val page = code("app/src/main/kotlin/app/auri/android/ui/DestinationsPage.kt")
        val list = code("app/src/main/kotlin/app/auri/android/destinations/DestinationsViewModel.kt")

        assertTrue("打开页面必须是自动刷新：$list", "init { startRefresh(automatic = true)" in list)
        assertTrue("refresh() 是用户点“刷新”：$list", "fun refresh() = startRefresh(automatic = false)" in list)
        assertTrue("自动刷新用自动入口、用户发起的用普通入口：$list", "if (automatic) controller.refreshAutomatically() else controller.refresh()" in list)
        assertTrue("“刷新”按钮必须是用户发起的入口：$page", "onRefresh = destinations::refresh" in page)
    }

    @Test
    fun `运行时把真正的授权设置交给服务：和同步引擎是同一个对象`() {
        val runtime = code("syncplatform/src/main/kotlin/app/auri/syncplatform/SyncRuntime.kt")

        assertTrue("装配必须把 policy 交给 DestinationService：$runtime", "DestinationService(pairing, http, locator, dispatch, jobs, authenticator, policy, clock, log, contract)" in runtime)
    }

    @Test
    fun `服务里只有两个自动入口问放行，用户发起的入口用不设防的门`() {
        val service = code("sync/src/main/kotlin/app/auri/sync/destinations/DestinationService.kt")

        val calls = Regex("""\bpermitFor\(""").findAll(service).count() - Regex("""fun permitFor\(""").findAll(service).count() // 总数减去声明
        assertEquals("permitFor 的调用只许在两个自动入口里（新增自动读要问放行，并更新这里的数）", 2, calls)
        assertTrue("自动刷新要问放行：$service", "val permit = permitFor(gateway) ?: return ServiceResult.Failed(ServiceFailure.AutomaticReadsOff) return list(permit, only = gateway)" in service)
        assertTrue("自动读状态要问放行：$service", "deliveriesThrough(destinationId, limit, gateway?.let { permitFor(it) }, only = gateway)" in service)
        assertTrue("用户点的刷新用不设防的门：$service", "override suspend fun refresh(): ServiceResult<List<Destination>> = list(RequestGate.Open, only = null)" in service)
        assertTrue("用户点的刷新状态用不设防的门：$service", "deliveriesThrough(destinationId, limit, RequestGate.Open, only = null)" in service)
    }

    @Test
    fun `列表页屏幕：自动刷新被拦下时把“显示的是本机缓存”那一句话摆出来，不是悄悄显示旧数据`() {
        val screen = code("app/src/main/kotlin/app/auri/android/ui/DestinationsScreen.kt")

        assertTrue("cacheOnly 时必须显示 describeAutomaticReadsOff：$screen", "if (state.cacheOnly) item { Text(describeAutomaticReadsOff()) }" in screen)
    }
}
