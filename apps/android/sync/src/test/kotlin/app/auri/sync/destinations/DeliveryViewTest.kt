package app.auri.sync.destinations

import app.auri.capture.testing.RepoContracts
import app.auri.sync.gateway.SendMode
import app.auri.sync.machine.SyncState
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 状态映射的两张表。网关的投递状态到底“证明了什么”，是检查项 6：已确认不许说成已处理。 */
class DeliveryViewTest {
    private fun contractEnum(file: String, path: List<String>): List<String> {
        var node = RepoContracts.json(file)
        for (step in path) node = node.jsonObject.getValue(step)
        return node.jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }
    }

    @Test
    fun `合同里的每一个投递状态都有对应的阶段，一个都不能漏（合同加了状态，这里先变红再决定怎么说）`() {
        val states = contractEnum("schemas/common.schema.json", listOf("\$defs", "delivery_state"))

        assertEquals(setOf("PENDING", "LEASED", "ACKNOWLEDGED", "REJECTED", "CANCELLED"), states.toSet())
        val stages = states.map { GatewayStage.fromGateway(it) ?: error("合同里的 $it 没有对应阶段") }
        assertEquals("一个合同状态对一个阶段，不合并", states.size, stages.toSet().size)
        assertEquals(GatewayStage.values().toSet(), stages.toSet())
    }

    @Test
    fun `已入库、已领取、已确认：三个阶段各对应网关的一个状态`() {
        assertEquals(GatewayStage.STORED, GatewayStage.fromGateway("PENDING"))
        assertEquals(GatewayStage.CLAIMED, GatewayStage.fromGateway("LEASED"))
        assertEquals(GatewayStage.ACKNOWLEDGED, GatewayStage.fromGateway("ACKNOWLEDGED"))
    }

    @Test
    fun `不认识的状态值不是任何一档：既不是成功也不是失败，调用方必须当未知`() {
        for (unknown in listOf("PROCESSED", "DONE", "COMPLETED", "acknowledged", "Acknowledged", "ACK", " ACKNOWLEDGED", "")) {
            assertNull("“$unknown”不该被认成已知状态", GatewayStage.fromGateway(unknown))
        }
    }

    @Test
    fun `阶段的名字里没有“已处理”这一档：网关只知道 Agent 领走了、回了确认，不知道它有没有读懂或执行`() {
        for (stage in GatewayStage.values()) {
            assertFalse("${stage.name} 夸大了网关能证明的事", listOf("PROCESS", "DONE", "HANDLED", "CONSUMED", "EXECUTED", "COMPLETE", "READ").any { stage.name.contains(it) })
        }
        val views = listOf(
            DeliveryView.AwaitingYou::class.java,
            DeliveryView.Skipped::class.java,
            DeliveryView.NotTargeted::class.java,
            DeliveryView.Queued::class.java,
            DeliveryView.OnGateway::class.java,
            DeliveryView.Unknown::class.java,
        )
        for (view in views) {
            assertFalse("${view.simpleName} 夸大了网关能证明的事", listOf("Process", "Done", "Handled", "Consumed", "Executed", "Complete").any { view.simpleName.contains(it) })
        }
    }

    @Test
    fun `本机的同步状态：已入库不算排队（之后问网关），其余每一种都有排队阶段，新增状态必须在这里表态`() {
        assertNull(QueuedPhase.of(SyncState.STORED))
        for (state in SyncState.values().filter { it != SyncState.STORED }) {
            assertNotNull("$state 没有对应的排队阶段", QueuedPhase.of(state))
        }
        assertEquals(QueuedPhase.NEEDS_YOU, QueuedPhase.of(SyncState.BLOCKED))
        assertEquals(QueuedPhase.WAITING_FOR_GATEWAY, QueuedPhase.of(SyncState.WAITING_GATEWAY))
        assertEquals(QueuedPhase.RETRYING_LATER, QueuedPhase.of(SyncState.RETRY_SCHEDULED))
    }

    @Test
    fun `发送方式的合同取值与本机一致，且只有这两种（没有“未选择”这一档）`() {
        val wire = contractEnum("schemas/common.schema.json", listOf("\$defs", "send_mode"))

        assertEquals(wire.toSet(), SendMode.values().map { it.wire }.toSet())
        assertEquals(setOf("auto_send", "preview_confirm"), wire.toSet())
        assertTrue(SendMode.values().none { it.wire.isBlank() })
    }
}
