package app.auri.capture.machine

import app.auri.capture.contract.CaptureState
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
 * Ticket 16 第 1、2 项：Kotlin 里的转移表必须和合同冻结的 `capture.machine.json` 逐条一致。
 * 表是手写在产品代码里的（运行时不能读仓库文件），所以这里和仓库里的原始 JSON 对一遍：
 * 多一条、少一条、目标状态不同、结果类型不同、end_reason 不同，都会失败。
 */
class CaptureMachineTableTest {
    private val machine: JsonObject = RepoContracts.json("fixtures/state-machines/capture.machine.json").jsonObject

    private data class JsonRule(val from: String, val event: String, val outcome: String, val to: String, val endReason: String?)

    private fun jsonRules(): List<JsonRule> = machine.getValue("transitions").jsonArray.map {
        val rule = it.jsonObject
        JsonRule(
            from = rule.getValue("from").jsonPrimitive.content,
            event = rule.getValue("event").jsonPrimitive.content,
            outcome = rule.getValue("outcome").jsonPrimitive.content,
            to = rule.getValue("to").jsonPrimitive.content,
            endReason = rule["end_reason"]?.jsonPrimitive?.content,
        )
    }

    private fun kotlinRules(): List<JsonRule> = CaptureMachine.rules.map {
        JsonRule(
            from = it.from.name,
            event = it.event.name,
            outcome = if (it.outcome == Outcome.TRANSITION) "transition" else "ignored",
            to = it.to.name,
            endReason = it.endReason?.name?.lowercase(),
        )
    }

    @Test
    fun `状态集合与事件集合和合同一致`() {
        assertEquals(
            machine.getValue("states").jsonArray.map { it.jsonPrimitive.content }.toSet(),
            CaptureState.entries.map { it.name }.toSet(),
        )
        assertEquals(
            machine.getValue("events").jsonArray.map { it.jsonPrimitive.content }.toSet(),
            CaptureEvent.entries.map { it.name }.toSet(),
        )
        assertEquals(machine.getValue("initial_state").jsonPrimitive.content, CaptureMachine.initialState.name)
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
        val unlisted = CaptureState.entries.flatMap { s -> CaptureEvent.entries.map { e -> s to e } }.filter { (s, e) ->
            (s.name to e.name) !in listed
        }

        assertTrue("合同里应当存在被拒绝的组合", unlisted.isNotEmpty())
        unlisted.forEach { (state, event) -> assertNull("$state + $event 不在合同里，不应有规则", CaptureMachine.resolve(state, event)) }
    }
}
