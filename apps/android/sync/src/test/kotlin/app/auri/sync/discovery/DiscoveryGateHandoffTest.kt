package app.auri.sync.discovery

import app.auri.sync.SyncLog
import app.auri.sync.pairing.GatewayAddress
import app.auri.sync.policy.RequestGate
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 第四轮 F7：`walkAddresses` 把**自己的门**交给发现，发现过程中每个会发出请求的步骤（NSD 的每次解析）才有机会各过一次门。
 * 原来只在 `find` 整体之外过一道门：门一放行，整个监听窗口里想解析什么就解析什么，授权被收回也拦不住。
 * 没有自己过门能力的发现（脚本化的假发现、旧的实现）只实现不带门的 `find`，仍由 walkAddresses 在外面过一道，行为不变。
 */
class DiscoveryGateHandoffTest {
    private class SwitchGate : RequestGate {
        @Volatile var open = true

        override suspend fun <T : Any> gated(request: suspend () -> T): T? = if (open) request() else null
    }

    /** 自己在发现过程中过门的发现（NSD 的做法）：只有带门的 find 是入口。 */
    private class GateAwareDiscovery(private val body: suspend (RequestGate) -> List<GatewayAddress>) : GatewayDiscovery {
        var seenGate: RequestGate? = null

        override suspend fun find(gatewayId: String): List<GatewayAddress> = error("会自己过门的发现，入口是带门的 find")

        override suspend fun find(gatewayId: String, gate: RequestGate): List<GatewayAddress> {
            seenGate = gate
            return body(gate)
        }
    }

    @Test
    fun `walkAddresses 把自己的门原样交给发现，不是另造一道也不是不设防的门`() = runBlocking {
        val gate = SwitchGate()
        val discovery = GateAwareDiscovery { emptyList() }

        walkAddresses<String>(emptyList(), discovery, "gateway-1", SyncLog.None, gate) { Step.Unreachable }

        assertSame(gate, discovery.seenGate)
    }

    @Test
    fun `发现过程中门关上了：已经解析出来的候选一个也不探测，结果是被拦下`() = runBlocking {
        val gate = SwitchGate()
        val discovery = GateAwareDiscovery { given ->
            val found = mutableListOf<GatewayAddress>()
            resolveWhileOpen(
                flowOf("a", "b"),
                given,
                resolve = { service ->
                    if (service == "a") gate.open = false // 用户在第一个服务解析的当口收回了授权
                    GatewayAddress("192.168.1.${service[0].code}", 8443)
                },
                accept = { found += it },
            )
            found
        }
        var probes = 0

        val walk = walkAddresses(emptyList(), discovery, "gateway-1", SyncLog.None, gate) {
            probes += 1
            Step.Done("reached")
        }

        assertEquals(Walk.Withheld, walk)
        assertEquals("授权被收回之后，连探测都不能发", 0, probes)
    }
}
