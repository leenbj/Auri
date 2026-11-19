@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.sync.engine

import app.auri.capture.machine.Outcome
import app.auri.capture.testing.RepoContracts
import app.auri.sync.http.TransportFailure
import app.auri.sync.machine.SyncEvent
import app.auri.sync.machine.SyncState
import app.auri.sync.testing.FakeGateway.Fault
import app.auri.sync.testing.SyncRig
import app.auri.sync.testing.SyncSamples
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 21 第 5 项：sync.scenarios.json 里的每一条事件序列，经假适配器回放到真正的同步引擎上，都必须得到合同写的结果；
 * 转移表里的每一条规则都必须被某个场景步骤真正走到过；少回放一个场景、漏掉一条规则，测试就失败。
 * 合同文件直接从仓库的 contracts/ 读取，不是拷贝。
 *
 * 事件怎么“造”出来（全部是真实原因，不是直接喂事件）：
 * - SEND_STARTED：引擎开始发送这条任务，而网关把它的第一个 PUT / 状态查询扣住——于是“发送中”这个状态可以被观察；
 * - STORED_CONFIRMED / RETRYABLE_FAILURE / VERSION_CONFLICT / AUTH_REVOKED：放行被扣住的请求，网关答“入库了”/503/409/401；
 * - GATEWAY_UNREACHABLE：发送中 = 被扣住的请求以“连不上”失败；其余状态 = 平台报告网络断开（networkLost）；
 * - KEY_MISMATCH：被扣住的请求以“公钥不符”失败；
 * - GATEWAY_REACHABLE：引擎探测到网关（probe）；RETRY_DUE：虚拟时间走到重试时刻（或旧计时器对已不在等待重试的任务触发）；
 * - USER_RESOLVED：用户重新配对之后（配对记录换成新的）引擎恢复因连接问题而停下的任务。
 * 唯一的例外是 STORED 状态收到重复的 STORED_CONFIRMED：串行的引擎里没有自然的原因能造出它，这一步直接调用引擎的 deliver，
 * 测试末尾断言“直接投递”恰好只有这一处。
 */
class SyncScenarioReplayTest {
    private val machine: JsonObject = RepoContracts.json("fixtures/state-machines/sync.machine.json").jsonObject
    private val scenarios = RepoContracts.json("fixtures/state-machines/sync.scenarios.json").jsonObject.getValue("scenarios").jsonArray.map { it.jsonObject }

    private data class Seen(val from: SyncState, val event: SyncEvent, val outcome: Outcome, val to: SyncState)

    private class Scene(val rig: SyncRig, val key: JobKey) {
        var held: CompletableDeferred<Fault?>? = null
        var sending: Job? = null
        val directDeliveries = mutableListOf<Pair<SyncState, SyncEvent>>()
    }

    private fun outcomeOf(name: String) = when (name) {
        "transition" -> Outcome.TRANSITION
        "ignored" -> Outcome.IGNORED
        "rejected" -> Outcome.REJECTED
        else -> error("合同里出现了没见过的 expect：$name")
    }

    /** 放行被扣住的那个请求。没有被扣住的请求就没有办法造出这个事件：直接失败，不悄悄跳过。 */
    private fun TestScope.release(scene: Scene, fault: Fault?, event: SyncEvent) {
        val gate = checkNotNull(scene.held) { "$event 需要一个正在发送的请求，但没有" }
        scene.held = null
        gate.complete(fault)
    }

    private suspend fun TestScope.stimulate(scene: Scene, event: SyncEvent, stateBefore: SyncState) {
        val rig = scene.rig
        when (event) {
            SyncEvent.SEND_STARTED -> {
                // 只有排队中的任务才会真的发出请求；其余状态里引擎应当直接拒绝，不留下被扣住的闸门
                if (stateBefore == SyncState.PENDING) {
                    val gate = CompletableDeferred<Fault?>()
                    rig.server.holdNext = gate
                    scene.held = gate
                }
                scene.sending = launch { rig.engine.send(scene.key) }
            }

            SyncEvent.STORED_CONFIRMED ->
                if (stateBefore == SyncState.SENDING) {
                    release(scene, null, event)
                } else {
                    scene.directDeliveries += stateBefore to event
                    rig.engine.deliver(scene.key, event)
                }

            SyncEvent.GATEWAY_UNREACHABLE ->
                if (stateBefore == SyncState.SENDING) release(scene, Fault.Transport(TransportFailure.Unreachable()), event) else rig.engine.networkLost()

            SyncEvent.RETRYABLE_FAILURE -> release(scene, Fault.Reject(503, "GATEWAY_UNREACHABLE", retryable = true), event)
            SyncEvent.KEY_MISMATCH -> release(scene, Fault.Transport(TransportFailure.KeyMismatch()), event)
            SyncEvent.AUTH_REVOKED -> release(scene, Fault.Reject(401, "GATEWAY_AUTH_REVOKED", retryable = false), event)
            SyncEvent.VERSION_CONFLICT -> release(scene, Fault.Reject(409, "RECORD_VERSION_CONFLICT", retryable = false), event)
            SyncEvent.GATEWAY_REACHABLE -> rig.engine.probe()

            SyncEvent.RETRY_DUE ->
                if (stateBefore == SyncState.RETRY_SCHEDULED) {
                    advanceTimeBy(Duration.between(rig.clock.now(), rig.job(scene.key).retryAt!!).toMillis())
                    rig.engine.retryDue()
                } else {
                    rig.engine.retryDue(scene.key) // 旧计时器迟到
                }

            SyncEvent.USER_RESOLVED -> {
                rig.pairing.save(SyncSamples.pairedGateway(pairedAt = Instant.parse("2026-11-18T09:00:00Z")))
                rig.engine.resolveAfterRepair()
            }
        }
        runCurrent()
    }

    /** 用真实事件走到场景声明的初始状态。这些准备动作不计入覆盖。 */
    private suspend fun TestScope.reach(scene: Scene, initial: SyncState) {
        val path = when (initial) {
            SyncState.PENDING -> emptyList()
            SyncState.SENDING -> listOf(SyncEvent.SEND_STARTED)
            SyncState.STORED -> listOf(SyncEvent.SEND_STARTED, SyncEvent.STORED_CONFIRMED)
            SyncState.WAITING_GATEWAY -> listOf(SyncEvent.GATEWAY_UNREACHABLE)
            SyncState.RETRY_SCHEDULED -> listOf(SyncEvent.SEND_STARTED, SyncEvent.RETRYABLE_FAILURE)
            SyncState.BLOCKED -> listOf(SyncEvent.SEND_STARTED, SyncEvent.VERSION_CONFLICT)
        }
        for (event in path) stimulate(scene, event, scene.rig.job(scene.key).state)
        assertEquals("没能到达场景的初始状态 $initial", initial, scene.rig.job(scene.key).state)
    }

    @Test
    fun `合同里的每个场景都被回放通过，转移表的每条规则都被真正走到过`() {
        val replayed = mutableListOf<String>()
        val seen = mutableSetOf<Seen>()
        val direct = mutableListOf<Pair<SyncState, SyncEvent>>()
        var rejections = 0

        for (scenario in scenarios) {
            val name = scenario.getValue("name").jsonPrimitive.content
            runTest {
                val rig = SyncRig(this)
                val scene = Scene(rig, rig.publish())
                reach(scene, SyncState.valueOf(scenario.getValue("initial_state").jsonPrimitive.content))

                scenario.getValue("steps").jsonArray.forEachIndexed { index, stepElement ->
                    val step = stepElement.jsonObject
                    val event = SyncEvent.valueOf(step.getValue("event").jsonPrimitive.content)
                    val expected = outcomeOf(step.getValue("expect").jsonPrimitive.content)
                    val stateAfter = SyncState.valueOf(step.getValue("state_after").jsonPrimitive.content)
                    val context = "场景 $name 第 ${index + 1} 步 $event"

                    val stateBefore = rig.job(scene.key).state
                    val before = rig.decisions.size
                    val attemptsBefore = rig.lan.attempts.size
                    stimulate(scene, event, stateBefore)
                    val produced = rig.decisions.drop(before)

                    assertEquals("$context：应当恰好产生一个决定，实际 $produced", 1, produced.size)
                    val decision = produced.single()
                    assertEquals("$context：决定里记的原状态", stateBefore, decision.from)
                    assertEquals("$context：触发事件", event, decision.event)
                    assertEquals("$context：结果", expected, decision.outcome)
                    assertEquals("$context：决定里记的新状态", stateAfter, decision.to)
                    assertEquals("$context：任务表里的状态", stateAfter, rig.job(scene.key).state)
                    if (decision.outcome == Outcome.REJECTED) {
                        rejections += 1
                        assertEquals("$context：被拒绝的事件不能引出任何网络往来", attemptsBefore, rig.lan.attempts.size)
                    } else {
                        seen += Seen(decision.from, event, decision.outcome, decision.to)
                    }
                }
                direct += scene.directDeliveries
            }
            replayed += name
        }

        assertEquals("合同里的场景必须全部回放", scenarios.map { it.getValue("name").jsonPrimitive.content }, replayed)
        assertTrue("场景里应当有被拒绝的步骤", rejections > 0)
        assertEquals("只有“已入库后又收到入库确认”这一步没有自然原因、直接投递", listOf(SyncState.STORED to SyncEvent.STORED_CONFIRMED), direct)

        val uncovered = machine.getValue("transitions").jsonArray.map { it.jsonObject }.filter { rule ->
            val from = SyncState.valueOf(rule.getValue("from").jsonPrimitive.content)
            val event = SyncEvent.valueOf(rule.getValue("event").jsonPrimitive.content)
            val outcome = outcomeOf(rule.getValue("outcome").jsonPrimitive.content)
            val to = SyncState.valueOf(rule.getValue("to").jsonPrimitive.content)
            Seen(from, event, outcome, to) !in seen
        }
        assertEquals("这些规则没有被任何场景步骤走到：$uncovered", 0, uncovered.size)
        assertNotNull(seen.firstOrNull())
    }
}
