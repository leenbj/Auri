@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.sync.destinations

import app.auri.sync.gateway.Destination
import app.auri.sync.gateway.SendMode
import app.auri.sync.machine.SyncState
import app.auri.sync.testing.DispatchRig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 再评 S4（HIGH）：发送账里的 Destination 缓存与排着的目标，按**完整的网关身份**（编号 + 配对时固定的公钥）认人，不只认编号。
 *
 * 编号相同、公钥不同是另一台网关（GatewayIdentity 本来就这样规定：同一个编号换了公钥，原来的同意都不算数）——
 * 它上面没有手机缓存里的那些 Destination。以前账只记编号：改配成“同编号、另一把公钥”的网关，A 的缓存和排着的目标原样留着，
 * A 的 Destination 编号进了新网关的请求，整个 PUT 被 422 拒绝，任务永久停下，文字永远到不了。
 */
class SameIdOtherKeyTest {
    private fun JsonObject.destinationIds(): List<String> =
        getValue("publication").jsonObject["destination_ids"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    @Test
    fun `改配成同编号、另一把公钥的网关：A 排着的目标全部撤回，文字不带 A 的编号入库，不被永久拒绝`() = runTest {
        val rig = DispatchRig(this)
        val hermes = rig.createDestination("Hermes", SendMode.AUTO_SEND)
        val key = rig.transcribed("给 A 的文字") // 按 A 的名单规划
        assertEquals("前提：规划了 A 的 Destination", listOf(hermes.destinationId), rig.dispatch.item(key)!!.targets.map { it.destinationId })
        val repaired = rig.repairWithSameIdAndAnotherKey()

        rig.engine.runOnce()

        assertEquals("同编号不同公钥是另一台网关：文字照常入库，不是被它以“未知 Destination”整个拒收", SyncState.STORED, rig.jobs.get(key)!!.state)
        assertEquals(1, repaired.putCount)
        assertEquals("A 的 Destination 编号不许进另一台网关的请求", emptyList<String>(), repaired.putBodies.single().destinationIds())
        assertEquals("A 的缓存作废", emptyList<Destination>(), rig.dispatch.destinations())
        assertEquals("A 排着的目标撤回", emptyList<String>(), rig.dispatch.item(key)!!.targets.map { it.destinationId })
    }

    @Test
    fun `用户先去发送页读了同编号另一台网关的名单：A 排着的目标同样撤回，之后的新文字按新名单规划`() = runTest {
        val rig = DispatchRig(this)
        rig.createDestination("Hermes", SendMode.AUTO_SEND)
        val first = rig.transcribed("给 A 的文字")
        val repaired = rig.repairWithSameIdAndAnotherKey()
        val claude = rig.createDestination("Claude Code", SendMode.AUTO_SEND) // 发送页在新网关上创建、读名单：这就是对账
        val second = rig.transcribed("给新网关的文字")

        assertEquals("缓存只剩新网关的", listOf("Claude Code"), rig.dispatch.destinations().map { it.displayName })
        assertEquals("A 排着的目标撤回", emptyList<String>(), rig.dispatch.item(first)!!.targets.map { it.destinationId })
        assertEquals("新文字按新网关的名单规划", listOf(claude.destinationId), rig.dispatch.item(second)!!.targets.map { it.destinationId })

        rig.engine.runOnce()

        assertEquals(listOf(SyncState.STORED, SyncState.STORED), listOf(first, second).map { rig.jobs.get(it)!!.state })
        assertEquals("两条文字都进了新网关", 2, repaired.putCount)
        assertEquals("只有新网关自己的 Destination 建了 Delivery", listOf(claude.destinationId), repaired.deliveries.map { it.destinationId })
    }
}
