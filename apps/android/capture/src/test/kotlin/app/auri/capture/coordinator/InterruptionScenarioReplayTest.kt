package app.auri.capture.coordinator

import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.CaptureState
import app.auri.capture.contract.ControlSource
import app.auri.capture.contract.EndReason
import app.auri.capture.contract.ErrorCode
import app.auri.capture.machine.CaptureEvent
import app.auri.capture.machine.Outcome
import app.auri.capture.testing.CaptureRig
import app.auri.capture.testing.RepoContracts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 19：capture.scenarios.json 里所有与中断有关的事件序列（出现了路由丢失、系统中断、权限撤销、进程恢复的场景），
 * 经假适配器回放到真正的协调器上，除了合同写的状态之外还要满足三条中断规则：
 *
 *  1. 结束原因：每一次从录音中收场的转移，交出的录音带的就是合同转移表里写的 end_reason（表里没写的由控制来源决定）；
 *  2. 永不自己续录：采集请求（开麦）只会出现在用户的 START/TOGGLE 命令之后，中断、封存完成、进程恢复都不会引出新的请求；
 *  3. 进程恢复之后一律是 DISARMED、控制模式关着。
 *
 * 相关场景由合同文件的内容挑出来（不是写死的名字）：合同里加了新的中断场景会自动纳入；挑出来的场景少回放一个，测试就失败。
 * 回放办法与 CaptureScenarioReplayTest 相同（那份是全部场景 + 转移表覆盖，这份只管上面三条规则）；两处各有一份是为了不去改别人的测试文件，
 * 合同新增事件时两处都要补。
 */
class InterruptionScenarioReplayTest {
    private val machineRules = RepoContracts.json("fixtures/state-machines/capture.machine.json").jsonObject.getValue("transitions").jsonArray.map { it.jsonObject }
    private val scenarios = RepoContracts.json("fixtures/state-machines/capture.scenarios.json").jsonObject.getValue("scenarios").jsonArray.map { it.jsonObject }

    private val interruptionEvents = setOf(
        CaptureEvent.ROUTE_LOST,
        CaptureEvent.SYSTEM_INTERRUPTION,
        CaptureEvent.PERMISSION_REVOKED,
        CaptureEvent.PROCESS_RESTORED,
    )

    private fun JsonObject.name() = getValue("name").jsonPrimitive.content

    private fun JsonObject.events() = getValue("steps").jsonArray.map { CaptureEvent.valueOf(it.jsonObject.getValue("event").jsonPrimitive.content) }

    private fun outcomeOf(name: String) = when (name) {
        "transition" -> Outcome.TRANSITION
        "ignored" -> Outcome.IGNORED
        "rejected" -> Outcome.REJECTED
        else -> error("合同里出现了没见过的 expect：$name")
    }

    /** 转移表里 (from, event) 这一条写的 end_reason（合同的蛇形名字）；没写返回 null。 */
    private fun endReasonInTable(from: CaptureState, event: CaptureEvent): String? =
        machineRules.single {
            it.getValue("from").jsonPrimitive.content == from.name &&
                it.getValue("event").jsonPrimitive.content == event.name &&
                it.getValue("outcome").jsonPrimitive.content == "transition"
        }["end_reason"]?.jsonPrimitive?.content

    private fun serialName(reason: EndReason) = EndReason.serializer().descriptor.getElementName(reason.ordinal)

    private fun TestScope.stimulate(rig: CaptureRig, event: CaptureEvent) {
        when (event) {
            CaptureEvent.ENABLE_CONTROL_MODE -> rig.coordinator.enableControlMode()
            CaptureEvent.DISABLE_CONTROL_MODE -> rig.coordinator.disableControlMode()
            CaptureEvent.START -> rig.control.press(BusinessCommand.START, ControlSource.HEADSET_REMOTE)
            CaptureEvent.STOP -> rig.control.press(BusinessCommand.STOP, ControlSource.HEADSET_REMOTE)
            CaptureEvent.TOGGLE -> rig.control.press(BusinessCommand.TOGGLE, ControlSource.HEADSET_REMOTE)
            CaptureEvent.USER_CANCEL -> rig.coordinator.userCancel()
            CaptureEvent.PERMISSION_REVOKED -> rig.capture.revokePermission()
            CaptureEvent.PROCESS_RESTORED -> rig.coordinator.processRestored()
            CaptureEvent.ROUTE_VERIFIED -> {
                val verified = rig.capture.verifiedRoute()
                rig.settle()
                // 开闸之后给一点真实音频：有音频可封存，FINALIZING 才会停留到测试放行
                if (rig.state == CaptureState.RECORDING) rig.capture.frames(samples = 1600, epoch = verified.epoch)
            }

            CaptureEvent.ROUTE_UNVERIFIED -> rig.capture.sink().inputUnavailable(ErrorCode.INPUT_DEVICE_MISSING)
            CaptureEvent.ROUTE_LOST -> rig.capture.mismatchRoute()
            CaptureEvent.SYSTEM_INTERRUPTION -> rig.capture.interrupt()
            CaptureEvent.ROUTE_TIMEOUT -> {
                rig.advance(rig.config.routeTimeoutMs)
                return
            }

            CaptureEvent.DURATION_LIMIT -> {
                rig.advance(rig.config.maxDurationMs)
                return
            }

            CaptureEvent.FINALIZE_COMPLETE -> rig.store.sealGate!!.complete(Unit)
        }
        rig.settle()
    }

    private fun TestScope.reach(rig: CaptureRig, initial: CaptureState) {
        val path = when (initial) {
            CaptureState.DISARMED -> emptyList()
            CaptureState.ARMED -> listOf(CaptureEvent.ENABLE_CONTROL_MODE)
            CaptureState.ROUTING -> listOf(CaptureEvent.ENABLE_CONTROL_MODE, CaptureEvent.START)
            CaptureState.RECORDING -> listOf(CaptureEvent.ENABLE_CONTROL_MODE, CaptureEvent.START, CaptureEvent.ROUTE_VERIFIED)
            CaptureState.FINALIZING -> listOf(CaptureEvent.ENABLE_CONTROL_MODE, CaptureEvent.START, CaptureEvent.ROUTE_VERIFIED, CaptureEvent.STOP)
            CaptureState.BLOCKED -> listOf(CaptureEvent.ENABLE_CONTROL_MODE, CaptureEvent.START, CaptureEvent.ROUTE_UNVERIFIED)
        }
        path.forEach { stimulate(rig, it) }
        assertEquals("没能到达场景的初始状态 $initial", initial, rig.state)
    }

    @Test
    fun `与中断有关的场景选得出来：四种打断事件每种至少出现在一个场景里`() {
        val relevant = scenarios.filter { scenario -> scenario.events().any { it in interruptionEvents } }

        for (event in interruptionEvents) {
            assertTrue("没有任何场景含有 $event：中断规则就没有被回放", relevant.any { event in it.events() })
        }
    }

    @Test
    fun `回放每一个与中断有关的场景：结束原因与合同一致，永不自己续录，进程恢复之后是 DISARMED`() {
        val relevant = scenarios.filter { scenario -> scenario.events().any { it in interruptionEvents } }
        val replayed = mutableListOf<String>()
        var restoredSteps = 0
        var interruptedRecordings = 0

        for (scenario in relevant) {
            val name = scenario.name()
            runTest {
                val rig = CaptureRig(this)
                rig.store.sealGate = CompletableDeferred() // 封存挂着，FINALIZING 可以被观察；FINALIZE_COMPLETE 步骤放行
                reach(rig, CaptureState.valueOf(scenario.getValue("initial_state").jsonPrimitive.content))

                scenario.getValue("steps").jsonArray.forEachIndexed { index, stepElement ->
                    val step = stepElement.jsonObject
                    val event = CaptureEvent.valueOf(step.getValue("event").jsonPrimitive.content)
                    val context = "场景 $name 第 ${index + 1} 步 $event"
                    val requestsBefore = rig.capture.requests.size
                    val decisionsBefore = rig.decisions.size

                    stimulate(rig, event)

                    val produced = rig.decisions.drop(decisionsBefore).filterNot { it.cause == Cause.DURATION_WARNING }
                    assertEquals("$context：应当恰好产生一个决定，实际 $produced", 1, produced.size)
                    val decision = produced.single()
                    assertEquals("$context：结果", outcomeOf(step.getValue("expect").jsonPrimitive.content), decision.outcome)
                    assertEquals("$context：处理之后的状态", CaptureState.valueOf(step.getValue("state_after").jsonPrimitive.content), rig.state)

                    // 规则 2：新的采集请求（开麦）只会跟在用户的 START/TOGGLE 命令后面，并且这条命令真的开始了准备
                    val newRequests = rig.capture.requests.size - requestsBefore
                    if (newRequests > 0) {
                        assertTrue("$context：只有用户的 START/TOGGLE 能引出新的采集请求，实际是 $event", event == CaptureEvent.START || event == CaptureEvent.TOGGLE)
                        assertEquals("$context：这条命令一次只开始一个会话", 1, newRequests)
                        assertEquals("$context：准备中", CaptureState.ROUTING, rig.state)
                        assertEquals("$context：是一次有效的转移", Outcome.TRANSITION, decision.outcome)
                    }

                    // 规则 3：进程恢复之后一律 DISARMED，控制模式关着
                    if (event == CaptureEvent.PROCESS_RESTORED) {
                        restoredSteps += 1
                        assertEquals("$context：回到 DISARMED", CaptureState.DISARMED, rig.state)
                        assertTrue("$context：控制模式必须是关的，不自动待命", !rig.coordinator.snapshot.value.controlModeEnabled)
                    }
                }

                // 规则 1：封存全部放行之后，交出的录音的结束原因，等于每一次“从录音中收场”的转移在合同里写的 end_reason
                rig.store.sealGate!!.complete(Unit)
                rig.settle()
                val expected = rig.decisions
                    .filter { it.outcome == Outcome.TRANSITION && it.stateBefore == CaptureState.RECORDING }
                    .map { endReasonInTable(CaptureState.RECORDING, it.trigger!!) ?: "headset_command" } // 表里没写的（START/STOP）由控制来源决定，这里的来源是耳机
                val actual = rig.output.recordings.map { serialName(it.result.endReason) }
                assertEquals("场景 $name：交出的录音的结束原因必须与合同转移表一致", expected, actual)
                interruptedRecordings += rig.output.recordings.count { it.result.endReason.truncated }
            }
            replayed += name
        }

        assertEquals("与中断有关的场景必须全部回放", relevant.map { it.name() }, replayed)
        assertTrue("至少有一个进程恢复的步骤被回放", restoredSteps > 0)
        assertTrue("至少有一个被截断的录音被检查过结束原因", interruptedRecordings > 0)
    }
}
