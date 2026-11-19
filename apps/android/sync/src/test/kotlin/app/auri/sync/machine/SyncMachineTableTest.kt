package app.auri.sync.machine

import app.auri.capture.machine.Outcome
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
 * Ticket 21 第 5 项：Kotlin 里的转移表必须和合同冻结的 `sync.machine.json` 逐条一致。
 * 表是手写在产品代码里的（运行时不能读仓库文件），所以这里与仓库里的原始 JSON 对一遍：
 * 多一条、少一条、目标状态不同、结果类型不同，都会失败。
 */
class SyncMachineTableTest {
    private val machine: JsonObject = RepoContracts.json("fixtures/state-machines/sync.machine.json").jsonObject

    private data class Rule(val from: String, val event: String, val outcome: String, val to: String)

    private fun jsonRules() = machine.getValue("transitions").jsonArray.map {
        val rule = it.jsonObject
        Rule(
            rule.getValue("from").jsonPrimitive.content,
            rule.getValue("event").jsonPrimitive.content,
            rule.getValue("outcome").jsonPrimitive.content,
            rule.getValue("to").jsonPrimitive.content,
        )
    }

    private fun kotlinRules() = SyncMachine.rules.map {
        Rule(it.from.name, it.event.name, if (it.outcome == Outcome.TRANSITION) "transition" else "ignored", it.to.name)
    }

    @Test
    fun `状态、事件、初始与终态和合同一致`() {
        fun names(key: String) = machine.getValue(key).jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(names("states"), SyncState.entries.map { it.name }.toSet())
        assertEquals(names("events"), SyncEvent.entries.map { it.name }.toSet())
        assertEquals(names("terminal_states"), SyncMachine.terminalStates.map { it.name }.toSet())
        assertEquals(machine.getValue("initial_state").jsonPrimitive.content, SyncMachine.initialState.name)
        assertEquals("合同没有保留状态", 0, machine.getValue("reserved_states").jsonArray.size)
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
    fun `没有列出的组合查不到规则，也就是被拒绝；终态 STORED 除了重复确认什么都不接受`() {
        val listed = jsonRules().map { it.from to it.event }.toSet()
        val unlisted = SyncState.entries.flatMap { s -> SyncEvent.entries.map { e -> s to e } }.filter { (s, e) -> (s.name to e.name) !in listed }

        assertTrue("合同里应当存在被拒绝的组合", unlisted.isNotEmpty())
        unlisted.forEach { (state, event) -> assertNull("$state + $event 不在合同里，不应有规则", SyncMachine.resolve(state, event)) }
        assertEquals(listOf(SyncEvent.STORED_CONFIRMED), SyncEvent.entries.filter { SyncMachine.resolve(SyncState.STORED, it) != null })
    }

    @Test
    fun `BLOCKED 只有用户处理才能离开：其余九个事件都被拒绝`() {
        val accepted = SyncEvent.entries.filter { SyncMachine.resolve(SyncState.BLOCKED, it) != null }
        assertEquals(listOf(SyncEvent.USER_RESOLVED), accepted)
    }
}
