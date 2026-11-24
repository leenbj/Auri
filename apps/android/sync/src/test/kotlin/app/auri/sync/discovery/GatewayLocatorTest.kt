package app.auri.sync.discovery

import app.auri.sync.http.HttpReply
import app.auri.sync.pairing.GatewayAddress
import app.auri.sync.policy.RequestGate
import app.auri.sync.testing.FakeDiscovery
import app.auri.sync.testing.FakeLan
import app.auri.sync.testing.ScriptedExchange
import app.auri.sync.testing.SyncSamples
import app.auri.sync.testing.TlsFixtures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 找网关：先连配对时记下的地址，连不上才用 mDNS；公钥不符的地址永远不会被当成网关。 */
class GatewayLocatorTest {
    private val nas = SyncSamples.NAS_ADDRESS
    private val lan = SyncSamples.LAN_ADDRESS
    private val moved = GatewayAddress("192.168.1.77", 8443)
    private val lanHost = LanFixture()

    private class LanFixture {
        val lan = FakeLan()
        val discovery = FakeDiscovery()
        val locator = GatewayLocator(lan.factory, discovery)
    }

    private fun locate(gateway: app.auri.sync.pairing.PairedGateway = SyncSamples.pairedGateway()) = runBlocking { lanHost.locator.locate(gateway) }

    @Test
    fun `先连最近一次连上的地址；它通就不再试别的，也不去问 mDNS`() {
        lanHost.lan.host(lan, TlsFixtures.gatewayA)
        lanHost.lan.host(nas, TlsFixtures.gatewayA)

        val result = locate(SyncSamples.pairedGateway(lastGoodAddress = lan))

        assertEquals(Located.Reachable(lan), result)
        assertEquals(listOf(lan), lanHost.lan.attempts.toList())
        assertTrue("记下的地址能用时不该用 mDNS", lanHost.discovery.asked.isEmpty())
    }

    @Test
    fun `没有最近地址时按配对时记下的顺序试，第一个不通就试第二个`() {
        lanHost.lan.host(lan, TlsFixtures.gatewayA) // nas 这个地址上没人

        val result = locate()

        assertEquals(Located.Reachable(lan), result)
        assertEquals(listOf(nas, lan), lanHost.lan.attempts.toList())
        assertTrue(lanHost.discovery.asked.isEmpty())
    }

    @Test
    fun `记下的地址都连不上才用 mDNS，查的是配对时的网关；找到的新地址能用就用它`() {
        lanHost.lan.host(moved, TlsFixtures.gatewayA)
        lanHost.discovery.result = { listOf(moved) }

        val result = locate(SyncSamples.pairedGateway(lastGoodAddress = lan))

        assertEquals(Located.Reachable(moved), result)
        assertEquals(listOf(SyncSamples.GATEWAY_ID), lanHost.discovery.asked.toList())
        assertEquals("先把记下的地址都试过一遍，才轮到 mDNS 给的地址", listOf(lan, nas, moved), lanHost.lan.attempts.toList())
    }

    @Test
    fun `mDNS 返回的地址里已经试过的不重复尝试`() {
        lanHost.discovery.result = { listOf(lan, nas) }

        assertEquals(Located.Unreachable, locate())

        assertEquals("每个地址只试一次", listOf(nas, lan), lanHost.lan.attempts.toList())
    }

    @Test
    fun `某个地址上换成了别的设备（公钥不符）：不信它，继续找，下一个对的就用`() {
        lanHost.lan.host(nas, TlsFixtures.gatewayB) // 冒充者
        lanHost.lan.host(lan, TlsFixtures.gatewayA)

        assertEquals(Located.Reachable(lan), locate())
        assertEquals(listOf(nas, lan), lanHost.lan.attempts.toList())
    }

    @Test
    fun `所有地址都是不符的公钥：结论是公钥不符（需要重新配对），不是不可达；mDNS 里的冒充者也一样`() {
        lanHost.lan.host(nas, TlsFixtures.gatewayB)
        lanHost.lan.host(lan, TlsFixtures.gatewayB)
        lanHost.lan.host(moved, TlsFixtures.gatewayB) // 冒充者在 mDNS 里冒用网关标识
        lanHost.discovery.result = { listOf(moved) }

        assertEquals(Located.KeyMismatch, locate())
        assertEquals(listOf(nas, lan, moved), lanHost.lan.attempts.toList())
        assertTrue("冒充者收不到任何请求", lanHost.lan.delivered.isEmpty())
    }

    @Test
    fun `什么都找不到是不可达；mDNS 抛异常当作没找到，不往外抛`() {
        lanHost.discovery.result = { throw IllegalStateException("NSD: FAILURE_INTERNAL_ERROR") }

        assertEquals(Located.Unreachable, locate())
        assertEquals(listOf(SyncSamples.GATEWAY_ID), lanHost.discovery.asked.toList())
    }

    @Test
    fun `取消不能被吞掉：mDNS 查找中被取消要继续向上抛`() {
        lanHost.discovery.result = { throw CancellationException("cancelled") }

        try {
            locate()
            fail("取消必须向上抛")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun `指纹对上了但回的不是存活响应（不是我们的网关服务），不算找到`() {
        lanHost.lan.host(nas, TlsFixtures.gatewayA) { HttpReply(503, emptyMap(), "busy".toByteArray()) }
        lanHost.lan.host(lan, TlsFixtures.gatewayA) { ScriptedExchange.json(200, """{"status":"starting"}""") }

        assertEquals(Located.Unreachable, locate())
    }

    /**
     * 找网关是一串请求（逐个地址探测、再问 mDNS）：每一个各自过一次门，不是整串只在第一个之前过一次（第三轮 S12）。
     * 为什么要紧：第一个探测在路上的时候用户收回了授权，后面的请求不该再发；这里的门放行前 [allowed] 个请求，之后一律不放行，并数着被拦了几次。
     */
    private class AllowsFirst(private var allowed: Int) : RequestGate {
        var withheld = 0

        override suspend fun <T : Any> gated(request: suspend () -> T): T? =
            if (allowed-- > 0) request() else { withheld++; null }
    }

    private fun locate(gateway: app.auri.sync.pairing.PairedGateway, gate: RequestGate) = runBlocking { lanHost.locator.locate(gateway, gate) }

    @Test
    fun `第一个探测放行之后门不放行：不试下一个地址，结果是 null，之后一个请求也没发，也不再问门`() {
        lanHost.lan.host(lan, TlsFixtures.gatewayA) // nas 上没人；lan 是通的，但门不让探
        val gate = AllowsFirst(1)

        assertNull(locate(SyncSamples.pairedGateway(), gate))

        assertEquals("第一个探测放行了；第二个地址在门口被拦下", listOf(nas), lanHost.lan.attempts.toList())
        assertTrue(lanHost.lan.delivered.isEmpty())
        assertEquals("被拦下就停：不再往后一个一个问门", 1, gate.withheld)
    }

    @Test
    fun `记下的地址都试过之后门不放行：不问 mDNS`() {
        val gate = AllowsFirst(1)

        assertNull(locate(SyncSamples.pairedGateway(addresses = listOf(nas)), gate))

        assertEquals(listOf(nas), lanHost.lan.attempts.toList())
        assertTrue("被门拦下的 mDNS 查询没有发出", lanHost.discovery.asked.isEmpty())
    }

    @Test
    fun `mDNS 问过之后门不放行：找到的地址一个也不探`() {
        lanHost.lan.host(moved, TlsFixtures.gatewayA)
        lanHost.discovery.result = { listOf(moved) }
        val gate = AllowsFirst(2) // 记下的地址的探测、mDNS 查询放行；mDNS 找到的地址的探测被拦

        assertNull(locate(SyncSamples.pairedGateway(addresses = listOf(nas)), gate))

        assertEquals(listOf(SyncSamples.GATEWAY_ID), lanHost.discovery.asked.toList())
        assertEquals(listOf(nas), lanHost.lan.attempts.toList())
        assertTrue(lanHost.lan.delivered.isEmpty())
    }

    @Test
    fun `一直放行的门：每个地址与 mDNS 照常走完，结论与不设门一样`() {
        lanHost.lan.host(moved, TlsFixtures.gatewayA)
        lanHost.discovery.result = { listOf(moved) }
        val gate = AllowsFirst(Int.MAX_VALUE)

        assertEquals(Located.Reachable(moved), locate(SyncSamples.pairedGateway(), gate))

        assertEquals(listOf(nas, lan, moved), lanHost.lan.attempts.toList())
        assertEquals(0, gate.withheld)
    }

    @Test
    fun `找网关时发出的请求都不带认证头（令牌只发给指纹核对过的网关做正事，不发给探测）`() {
        lanHost.lan.host(lan, TlsFixtures.gatewayA)

        locate()

        assertTrue(lanHost.lan.delivered.isNotEmpty())
        lanHost.lan.delivered.forEach { (_, request) ->
            assertTrue("探测请求带了认证头", request.headers.keys.none { it.equals("authorization", ignoreCase = true) })
            assertEquals("/health", request.path)
        }
        assertEquals("每次连接都用配对时记下的指纹", setOf(TlsFixtures.gatewayA.pin), lanHost.lan.pinsUsed.toSet())
    }
}
