package app.auri.capture.coordinator

import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.CaptureState
import app.auri.capture.contract.ControlSource
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
 * Ticket 16 第 2 项：capture.scenarios.json 里的每一条事件序列，经假适配器回放到真正的协调器上，都必须得到合同写的结果；
 * 转移表里的每一条规则都必须被某个场景步骤真正走到过；少回放一个场景、漏掉一条规则，测试就失败。
 * 合同文件直接从仓库的 contracts/ 读取，不是拷贝。
 *
 * 事件怎么“造”出来：命令来自控制适配器；路由观测、帧、中断来自第 N 个会话的 sink（迟到的旧回调就是对旧会话的 sink 说话）；
 * ROUTE_TIMEOUT / DURATION_LIMIT 靠推进虚拟时间；FINALIZE_COMPLETE 靠放行存储的封存；权限撤销来自采集适配器的全局回调。
 */
class CaptureScenarioReplayTest {
    private val machine: JsonObject = RepoContracts.json("fixtures/state-machines/capture.machine.json").jsonObject
    private val scenarios = RepoContracts.json("fixtures/state-machines/capture.scenarios.json").jsonObject.getValue("scenarios").jsonArray.map { it.jsonObject }

    private data class Seen(val from: CaptureState, val event: CaptureEvent, val outcome: Outcome, val to: CaptureState)

    private fun outcomeOf(name: String) = when (name) {
        "transition" -> Outcome.TRANSITION
        "ignored" -> Outcome.IGNORED
        "rejected" -> Outcome.REJECTED
        else -> error("合同里出现了没见过的 expect：$name")
    }

    /** 造出一个机器事件。每个事件只用一种办法，办不到就失败（不悄悄跳过）。 */
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
                // 开闸之后给一点真实音频：有音频可封存，FINALIZING 才会停留到测试放行（没有音频时封存是瞬间完成的）
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

    /** 用真实事件走到场景声明的初始状态。这些准备动作不计入覆盖。 */
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
    fun `合同里的每个场景都被回放通过，转移表的每条规则都被真正走到过`() {
        val replayed = mutableListOf<String>()
        val seen = mutableSetOf<Seen>()
        var rejections = 0

        for (scenario in scenarios) {
            val name = scenario.getValue("name").jsonPrimitive.content
            runTest {
                val rig = CaptureRig(this)
                // 封存在测试放行之前一直挂着：FINALIZING 可以被观察，FINALIZE_COMPLETE 由场景步骤触发
                rig.store.sealGate = CompletableDeferred()
                reach(rig, CaptureState.valueOf(scenario.getValue("initial_state").jsonPrimitive.content))

                scenario.getValue("steps").jsonArray.forEachIndexed { index, stepElement ->
                    val step = stepElement.jsonObject
                    val event = CaptureEvent.valueOf(step.getValue("event").jsonPrimitive.content)
                    val expected = outcomeOf(step.getValue("expect").jsonPrimitive.content)
                    val stateAfter = CaptureState.valueOf(step.getValue("state_after").jsonPrimitive.content)
                    val context = "场景 $name 第 ${index + 1} 步 $event"

                    val before = rig.decisions.size
                    stimulate(rig, event)
                    val observed = rig.decisions.drop(before)
                    val warnings = observed.filter { it.cause == Cause.DURATION_WARNING }
                    if (event == CaptureEvent.DURATION_LIMIT) {
                        assertEquals("$context：到上限前恰好一次提醒", 1, warnings.size)
                        assertEquals(CaptureState.RECORDING, warnings.single().stateAfter)
                    } else {
                        assertTrue("$context：没有多余的时长提醒", warnings.isEmpty())
                    }
                    val produced = observed.filterNot { it.cause == Cause.DURATION_WARNING }

                    assertEquals("$context：应当恰好产生一个决定，实际 $produced", 1, produced.size)
                    val decision = produced.single()
                    assertEquals("$context：触发事件", event, decision.trigger)
                    assertEquals("$context：结果", expected, decision.outcome)
                    assertEquals("$context：处理之后的状态", stateAfter, rig.state)
                    assertEquals("$context：决定里记的状态", stateAfter, decision.stateAfter)
                    if (decision.outcome == Outcome.REJECTED) rejections += 1 else seen += Seen(decision.stateBefore, event, decision.outcome, decision.stateAfter)
                }
            }
            replayed += name
        }

        assertEquals("合同里的场景必须全部回放", scenarios.map { it.getValue("name").jsonPrimitive.content }, replayed)
        assertTrue("场景里应当有被拒绝的步骤", rejections > 0)

        val uncovered = machine.getValue("transitions").jsonArray.map { it.jsonObject }.filter { rule ->
            val from = CaptureState.valueOf(rule.getValue("from").jsonPrimitive.content)
            val event = CaptureEvent.valueOf(rule.getValue("event").jsonPrimitive.content)
            val outcome = outcomeOf(rule.getValue("outcome").jsonPrimitive.content)
            val to = CaptureState.valueOf(rule.getValue("to").jsonPrimitive.content)
            Seen(from, event, outcome, to) !in seen
        }
        assertEquals("这些规则没有被任何场景步骤走到：$uncovered", 0, uncovered.size)
    }
}
