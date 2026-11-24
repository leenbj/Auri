@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.sync.destinations

import app.auri.sync.engine.DeliveryAddition
import app.auri.sync.gateway.SendMode
import app.auri.sync.machine.SyncState
import app.auri.sync.pairing.GatewayAddress
import app.auri.sync.testing.DispatchRig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评审 H2：用户把手机重新配对到另一个网关（B）。账上的 Destination 缓存和每条文字的目标，都是按旧网关（A）的 Destination 规划的，
 * 那些编号在 B 上不存在：B 会以 422 拒绝整个提交，文字永远停在 BLOCKED。引擎向新网关发请求之前必须先对账：A 的编号一个也不许进 B 的请求。
 * 引擎与发送账是真的，两个网关与局域网是假的。
 */
class GatewaySwitchTest {
    private val addressB = DispatchRig.ADDRESS_B

    private fun DispatchRig.requestsTo(address: GatewayAddress): List<String> =
        lan.delivered.filter { it.first == address }.map { (_, request) -> request.body?.toString(Charsets.UTF_8).orEmpty() }

    @Test
    fun `换配对：排着的文字不带旧网关的 Destination 编号去新网关，照常入库，而不是被 422 拒绝后卡死`() = runTest {
        val rig = DispatchRig(this)
        val old = rig.createDestination("Hermes", SendMode.AUTO_SEND)
        val key = rig.transcribed()
        val gatewayB = rig.repairWithAnotherGateway()

        rig.engine.runOnce()

        assertEquals("文字照常进了新网关", SyncState.STORED, rig.jobs.get(key)!!.state)
        assertEquals(1, gatewayB.putCount)
        assertTrue("新网关从没见过旧网关的 Destination 编号", rig.requestsTo(addressB).none { it.contains(old.destinationId) })
        assertEquals("它不认识旧网关的 Destination，当然没为它们建 Delivery", 0, gatewayB.deliveries.size)
    }

    @Test
    fun `已入库、用户也确认了、追加还没做就换了配对：旧网关的 Destination 编号不会被追加给新网关`() = runTest {
        val rig = DispatchRig(this)
        val old = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = rig.transcribed()
        rig.engine.runOnce() // 文字入库旧网关；预览目标在等用户
        rig.dispatch.decide(key, old.destinationId, Choice.CONFIRM, rig.clock.now()) // 确认了，追加还欠着
        assertEquals(listOf(DeliveryAddition(key, rig.dispatch.item(key)!!.intent, old.destinationId)), rig.dispatch.due())
        val gatewayB = rig.repairWithAnotherGateway()

        rig.engine.runOnce()

        assertEquals("一个追加请求也没有发给新网关", 0, gatewayB.additionCount)
        assertTrue("新网关从没见过旧网关的 Destination 编号", rig.requestsTo(addressB).none { it.contains(old.destinationId) })
        assertEquals("账上也不再欠它", emptyList<DeliveryAddition>(), rig.dispatch.due())
    }

    @Test
    fun `用户先去发送页（读了新网关的名单）再同步：旧文字的旧目标撤回，新文字按新网关规划`() = runTest {
        val rig = DispatchRig(this)
        rig.createDestination("Hermes", SendMode.AUTO_SEND)
        val first = rig.transcribed()
        val gatewayB = rig.repairWithAnotherGateway()
        val fresh = rig.createDestination("Claude Code", SendMode.AUTO_SEND) // 对着新网关创建：缓存换成新网关的
        val second = rig.transcribed()

        rig.engine.runOnce()

        assertEquals(listOf(SyncState.STORED, SyncState.STORED), listOf(first, second).map { rig.jobs.get(it)!!.state })
        assertEquals("旧文字当初是照旧网关的名单规划的：旧目标撤回，不补规划新网关的", emptyList<String>(), rig.dispatch.item(first)!!.targets.map { it.destinationId })
        assertEquals("新文字按新网关规划，并建了 Delivery", listOf(fresh.destinationId), gatewayB.deliveries.map { it.destinationId })
    }
}
