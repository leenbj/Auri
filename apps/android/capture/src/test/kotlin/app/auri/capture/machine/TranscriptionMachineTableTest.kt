package app.auri.capture.machine

import app.auri.capture.testing.RepoContracts
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 20 第 3 项（表这一半）：Kotlin 里的转录状态机转移表必须和合同冻结的 `transcription.machine.json` 逐条一致。
 * 表手写在产品代码里（运行时读不到仓库文件），所以这里和仓库里的原始 JSON 对一遍：
 * 多一条、少一条、目标状态不同、结果类型不同、状态或事件集合不同、终态集合不同，都会失败。
 */
class TranscriptionMachineTableTest {
    private val machine: JsonObject = RepoContracts.json("fixtures/state-machines/transcription.machine.json").jsonObject

    private data class JsonRule(val from: String, val event: String, val outcome: String, val to: String)

    private fun strings(key: String) = machine.getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private fun jsonRules(): List<JsonRule> = machine.getValue("transitions").jsonArray.map {
        val rule = it.jsonObject
        JsonRule(
            from = rule.getValue("from").jsonPrimitive.content,
            event = rule.getValue("event").jsonPrimitive.content,
            outcome = rule.getValue("outcome").jsonPrimitive.content,
            to = rule.getValue("to").jsonPrimitive.content,
        )
    }

    private fun kotlinRules(): List<JsonRule> = TranscriptionMachine.rules.map {
        JsonRule(
            from = it.from.name,
            event = it.event.name,
            outcome = if (it.outcome == Outcome.TRANSITION) "transition" else "ignored",
            to = it.to.name,
        )
    }

    @Test
    fun `状态集合、事件集合、初始状态、终态与保留状态和合同一致`() {
        assertEquals(strings("states").toSet(), TranscriptionState.entries.map { it.name }.toSet())
        assertEquals(strings("events").toSet(), TranscriptionEvent.entries.map { it.name }.toSet())
        assertEquals(machine.getValue("initial_state").jsonPrimitive.content, TranscriptionMachine.initialState.name)
        assertEquals(strings("terminal_states").toSet(), TranscriptionMachine.terminalStates.map { it.name }.toSet())
        assertEquals(strings("reserved_states").toSet(), TranscriptionMachine.reservedStates.map { it.name }.toSet())
    }

    @Test
    fun `每一条转移规则都和合同逐条一致`() {
        val expected = jsonRules()
        val actual = kotlinRules()

        assertEquals("规则条数", expected.size, actual.size)
        assertEquals(expected.toSet(), actual.toSet())
        assertEquals("同一对（状态，事件）不能写两遍", actual.size, actual.map { it.from to it.event }.toSet().size)
    }

    @Test
    fun `没有列出的组合查不到规则，也就是被拒绝`() {
        val listed = jsonRules().map { it.from to it.event }.toSet()
        val unlisted = TranscriptionState.entries.flatMap { s -> TranscriptionEvent.entries.map { e -> s to e } }.filter { (s, e) ->
            (s.name to e.name) !in listed
        }

        assertTrue("合同里应当存在被拒绝的组合", unlisted.isNotEmpty())
        unlisted.forEach { (state, event) -> assertNull("$state + $event 不在合同里，不应有规则", TranscriptionMachine.resolve(state, event)) }
    }

    @Test
    fun `终态只会忽略取消：不会再走到任何别的状态（一个结束的任务不能复活）`() {
        for (terminal in TranscriptionMachine.terminalStates) {
            val outgoing = TranscriptionMachine.rules.filter { it.from == terminal }
            assertTrue("$terminal 的所有规则都是忽略且留在原地：$outgoing", outgoing.all { it.outcome == Outcome.IGNORED && it.to == terminal })
            assertEquals("$terminal 只处理取消", setOf(TranscriptionEvent.CANCEL), outgoing.map { it.event }.toSet())
        }
    }

    @Test
    fun `V1 只做本地转录：没有任何规则出入保留状态（网络、授权、远端提交）`() {
        val reserved = TranscriptionMachine.reservedStates
        assertTrue(reserved.isNotEmpty())
        assertTrue(TranscriptionMachine.rules.none { it.from in reserved || it.to in reserved })
    }
}
