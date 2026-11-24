package app.auri.android.sync

import app.auri.android.netguard.SourceText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第四轮 F7 的接线守卫：行为（门不放行就停、哨兵、取消）在 :sync 的 ResolveWhileOpenTest 与 DiscoveryGateHandoffTest 里摆过；
 * NsdManager 要真机，JVM 里跑不了，所以这里钉住 JVM 看不到、又一旦忘了就没有任何测试会红的接线（做法同 PolicyGateWiringTest：读去掉注释与字符串的生产源码）：
 * 1. NSD 里 `resolveService`（会向局域网发请求）只有一处，且只在 `resolve` 函数里；监听用 `resolveWhileOpen`，每次解析各过一次门——
 *    不是在 `services().collect` 里自己循环（那样第一次过门之后，4 秒窗口里授权被收回也不停）；
 * 2. 定位把自己的门交给带门的 `find`（`discovery.find(gatewayId, gate)`），不是不带门的那个；不带门的 `find` 只是“不设防”的入口。
 */
class NsdGateWiringTest {
    private val androidDir = File("..")

    private fun code(path: String) = SourceText.withoutCommentsAndStrings(File(androidDir, path).readText()).replace(Regex("""\s+"""), " ")

    private val nsd get() = code("syncplatform/src/main/kotlin/app/auri/syncplatform/discovery/NsdGatewayDiscovery.kt")

    @Test
    fun `NSD 里 resolveService 只有一处而且只在 resolve 函数里：每次解析都是 resolveWhileOpen 交来的那一个`() {
        val source = nsd
        val resolveFunction = source.substringAfter("private suspend fun resolve(").substringBefore("private fun textAttributes(")

        assertEquals("NSD 里 resolveService 只许出现一次（它会向局域网发请求）", 1, Regex("""\.resolveService\(""").findAll(source).count())
        assertTrue("resolveService 必须在 resolve 函数里，不能在别处直接调：$resolveFunction", "manager.resolveService(service, listener)" in resolveFunction)
        assertTrue("监听必须经 resolveWhileOpen，每次解析各过一次门", "resolveWhileOpen(services(), gate, resolve = ::resolve)" in source)
    }

    @Test
    fun `NSD 不再自己在 collect 里循环：授权被收回之后窗口里剩下的服务不能再解析`() {
        val source = nsd

        assertFalse("services 不能直接 collect：门只在整个 find 开头过一次，之后每个服务都绕过了检查", Regex("""services\(\)\s*\.\s*collect""").containsMatchIn(source))
        assertFalse("NSD 里不应再有 collect", ".collect" in source)
    }

    @Test
    fun `不带门的 find 只是不设防的入口：真实现在带门的 find 里，定位把自己的门交给它`() {
        val source = nsd
        val walk = code("sync/src/main/kotlin/app/auri/sync/discovery/GatewayDiscovery.kt")

        assertTrue("不带门的 find 只能转给带门的、并明说不设防", "override suspend fun find(gatewayId: String): List<GatewayAddress> = find(gatewayId, RequestGate.Open)" in source)
        assertTrue("NSD 必须实现带门的 find（否则默认实现会退回不带门的）", "override suspend fun find(gatewayId: String, gate: RequestGate): List<GatewayAddress>" in source)
        assertTrue("walkAddresses 必须把自己的门交给 find：$walk", "gate.gated { discovery.find(gatewayId, gate) }" in walk)
    }
}
