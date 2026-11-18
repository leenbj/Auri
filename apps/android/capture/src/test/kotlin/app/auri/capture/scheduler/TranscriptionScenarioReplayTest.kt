package app.auri.capture.scheduler

import app.auri.capture.adapter.TranscriptionOutcome
import app.auri.capture.adapter.TranscriptionRequest
import app.auri.capture.contract.CancelSupport
import app.auri.capture.contract.ErrorCode
import app.auri.capture.machine.Outcome
import app.auri.capture.machine.TranscriptionEvent
import app.auri.capture.machine.TranscriptionEvent.CANCEL
import app.auri.capture.machine.TranscriptionEvent.MODEL_MISSING
import app.auri.capture.machine.TranscriptionEvent.MODEL_READY
import app.auri.capture.machine.TranscriptionEvent.PERMANENT_ERROR
import app.auri.capture.machine.TranscriptionEvent.RESOURCES_LOW
import app.auri.capture.machine.TranscriptionEvent.RESOURCES_OK
import app.auri.capture.machine.TranscriptionEvent.RESULT_NO_SPEECH
import app.auri.capture.machine.TranscriptionEvent.RESULT_TEXT
import app.auri.capture.machine.TranscriptionEvent.RETRYABLE_ERROR
import app.auri.capture.machine.TranscriptionEvent.RETRY_DUE
import app.auri.capture.machine.TranscriptionEvent.RUN_STARTED
import app.auri.capture.machine.TranscriptionState
import app.auri.capture.machine.TranscriptionState.QUEUED
import app.auri.capture.machine.TranscriptionState.RETRY_SCHEDULED
import app.auri.capture.machine.TranscriptionState.RUNNING
import app.auri.capture.machine.TranscriptionState.SUCCEEDED
import app.auri.capture.machine.TranscriptionState.WAITING_MODEL
import app.auri.capture.testing.BoundedSchedulerTest
import app.auri.capture.testing.RepoContracts
import app.auri.capture.testing.Samples
import app.auri.capture.testing.SchedulerRig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 20 第 3 项：合同冻结的 `transcription.scenarios.json` 里**每一个**场景都要通过调度器和假适配器真的重放一遍，
 * 并且和 `transcription.machine.json` 对得上。
 *
 * 重放不是拿转移表自己算一遍：每个场景的每一步，都由假的模型管理器、资源监视器、转录引擎或用户操作去触发，
 * 然后读调度器**实际记录**的决定（事件、转移前后状态、被转移/忽略/拒绝）与场景逐步比对。这个测试读的是仓库 `contracts/` 里的原始 JSON，
 * 并且在下面三种情况下失败：
 *  - 某个场景没有被重放（包括 JSON 里新增了场景、而这里没有办法为它的初始状态或事件造出触发）；
 *  - 某一步没产生决定、产生的决定与场景不符、或者场景走完之后还有没被任何一步认领的决定；
 *  - 状态机里的某条规则没有被任何场景的真实决定覆盖，或者没有出现过被拒绝的决定。
 *
 * 唯一的接缝：场景 finished-job-cannot-rerun 的两步（对已经成功的任务再发 RUN_STARTED、RESULT_TEXT）在正常运行时没有任何来源——
 * 那正是“已经结束的任务不能复活”要挡住的 bug 或竞态——所以只能用测试接缝 [TranscriptionScheduler.injectStaleSignal] 硬塞进去。
 * 测试最后断言接缝只被这两步用过，防止别的场景悄悄绕开真实触发。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionScenarioReplayTest : BoundedSchedulerTest() {
    private val machine: JsonObject = RepoContracts.json("fixtures/state-machines/transcription.machine.json").jsonObject
    private val scenarioFile: JsonObject = RepoContracts.json("fixtures/state-machines/transcription.scenarios.json").jsonObject

    private data class Step(val event: TranscriptionEvent, val expect: Outcome, val stateAfter: TranscriptionState)

    private data class Scenario(val name: String, val initial: TranscriptionState, val steps: List<Step>)

    private fun outcomeOf(name: String) = when (name) {
        "transition" -> Outcome.TRANSITION
        "ignored" -> Outcome.IGNORED
        "rejected" -> Outcome.REJECTED
        else -> error("合同里出现了不认识的结果类型 $name")
    }

    private fun scenarios(): List<Scenario> = scenarioFile.getValue("scenarios").jsonArray.map {
        val scenario = it.jsonObject
        Scenario(
            name = scenario.getValue("name").jsonPrimitive.content,
            initial = TranscriptionState.valueOf(scenario.getValue("initial_state").jsonPrimitive.content),
            steps = scenario.getValue("steps").jsonArray.map { raw ->
                val step = raw.jsonObject
                Step(
                    event = TranscriptionEvent.valueOf(step.getValue("event").jsonPrimitive.content),
                    expect = outcomeOf(step.getValue("expect").jsonPrimitive.content),
                    stateAfter = TranscriptionState.valueOf(step.getValue("state_after").jsonPrimitive.content),
                )
            },
        )
    }

    /** 假转录引擎：每次调用都挂起，直到场景的某一步给它结果。可中断时协程取消能打断它；不可中断时像原生推理一样只能等它返回。 */
    private class ScriptedEngine(private val rig: SchedulerRig, interruptible: Boolean) {
        private var latest: CompletableDeferred<TranscriptionOutcome>? = null

        val hasPendingCall: Boolean get() = latest?.isCompleted == false

        init {
            if (interruptible) rig.adapter.capabilities = Samples.capabilities.copy(cancelSupport = CancelSupport.IMMEDIATE)
            val results = HashMap<TranscriptionRequest, TranscriptionOutcome>()
            rig.adapter.hook = { request ->
                val gate = CompletableDeferred<TranscriptionOutcome>()
                latest = gate
                results[request] = if (interruptible) gate.await() else withContext(NonCancellable) { gate.await() }
            }
            rig.adapter.outcome = { request -> results.remove(request) ?: error("没有为这次调用准备结果") }
        }

        fun finish(outcome: TranscriptionOutcome) {
            check(latest!!.complete(outcome)) { "最近的一次调用已经有结果了" }
            rig.settle()
        }
    }

    private class Observed(val before: TranscriptionState, val event: TranscriptionEvent, val outcome: Outcome)

    private class Replayed(val observed: List<Observed>, val seamUses: List<String>)

    private suspend fun TestScope.replay(scenario: Scenario): Replayed {
        val rig = SchedulerRig(this)
        // 某一步要求“运行中被资源打断”，引擎就必须声明能中途停；其余场景用真引擎的声明（不能中途停）
        var before = scenario.initial
        val interruptible = scenario.steps.any { step ->
            (step.event == RESOURCES_LOW && before == RUNNING).also { before = step.stateAfter }
        }
        val engine = ScriptedEngine(rig, interruptible)
        val target = rig.addRecord(1)
        val first = scenario.steps.first().event

        // ---- 造出初始状态；这一段的决定不属于场景
        var created = false
        fun create() {
            rig.scheduler.submit(target)
            rig.settle()
            created = true
        }
        when (scenario.initial) {
            QUEUED -> when (first) {
                RUN_STARTED -> Unit // 条件都满足，提交之后第一个决定就是开始
                MODEL_MISSING -> { rig.models.missing(); rig.settle() }
                RESOURCES_LOW -> { rig.resources.low(ResourceReason.THERMAL); rig.settle() }
                CANCEL -> {
                    // 让目标任务排在一个正在转的任务后面，停在排队里
                    val blocker = rig.addRecord(2)
                    rig.scheduler.submit(blocker)
                    rig.settle()
                    create()
                }
                else -> error("场景 ${scenario.name}：没有办法让排队中的任务以 $first 开头")
            }
            WAITING_MODEL -> { rig.models.missing(); rig.settle(); create() }
            RUNNING -> create()
            RETRY_SCHEDULED -> { create(); engine.finish(TranscriptionOutcome.Failed(ErrorCode.ASR_RESOURCE_LIMIT, retryable = true)) }
            SUCCEEDED -> { create(); engine.finish(TranscriptionOutcome.Text("场景里的文字", "zh", null)) }
            else -> error("场景 ${scenario.name}：没有办法造出初始状态 ${scenario.initial}")
        }
        if (created) assertEquals("${scenario.name}：初始状态", scenario.initial, rig.jobOf(target).state)

        val seamUses = mutableListOf<String>()
        val observed = mutableListOf<Observed>()
        var pointer = rig.decisions.size
        var previous = scenario.initial
        scenario.steps.forEachIndexed { index, step ->
            val label = "${scenario.name} 第 ${index + 1} 步 ${step.event}"
            if (rig.decisions.size == pointer) {
                // 上一个触发没有顺带产生这一步的决定：现在去触发它
                if (!created) {
                    create()
                } else {
                    when (step.event) {
                        MODEL_MISSING -> rig.models.missing()
                        MODEL_READY -> rig.models.ready()
                        RESOURCES_LOW -> rig.resources.low(ResourceReason.THERMAL)
                        RESOURCES_OK -> rig.resources.ok()
                        RETRY_DUE -> rig.advance(rig.config.retryBackoffMs.max())
                        CANCEL -> rig.scheduler.cancel(rig.jobOf(target).jobId)
                        RESULT_TEXT, RESULT_NO_SPEECH, RETRYABLE_ERROR, PERMANENT_ERROR, RUN_STARTED ->
                            if (engine.hasPendingCall && step.event != RUN_STARTED) {
                                engine.finish(
                                    when (step.event) {
                                        RESULT_TEXT -> TranscriptionOutcome.Text("场景里的文字", "zh", null)
                                        RESULT_NO_SPEECH -> TranscriptionOutcome.NoSpeech
                                        RETRYABLE_ERROR -> TranscriptionOutcome.Failed(ErrorCode.ASR_RESOURCE_LIMIT, retryable = true)
                                        else -> TranscriptionOutcome.Failed(ErrorCode.ASR_RESOURCE_LIMIT)
                                    },
                                )
                            } else {
                                seamUses += "${scenario.name}:${step.event}"
                                rig.scheduler.injectStaleSignal(rig.jobOf(target).jobId, step.event)
                            }
                    }
                }
                rig.settle()
            }
            assertTrue("$label：没有产生决定", rig.decisions.size > pointer)
            val decision = rig.decisions[pointer++]
            assertEquals("$label：事件", step.event, decision.trigger)
            assertEquals("$label：结果", step.expect, decision.outcome)
            assertEquals("$label：转移前的状态", previous, decision.stateBefore)
            assertEquals("$label：转移后的状态", step.stateAfter, decision.stateAfter)
            observed += Observed(decision.stateBefore, decision.trigger, decision.outcome)
            previous = step.stateAfter
        }

        // 让所有还没到期的定时器都走完：之后不应再有任何没被场景认领的决定
        rig.advance(60 * 60 * 1000L)
        assertEquals("${scenario.name}：场景走完之后还有没被认领的决定：${rig.decisions.drop(pointer).map { it.trigger }}", pointer, rig.decisions.size)
        assertEquals("${scenario.name}：最终状态", previous, rig.jobOf(target).state)
        val revisions = rig.library.get(target)!!.revisions.size
        assertEquals("${scenario.name}：只有成功的任务才有 Revision（取消之后迟到的文字不能写入）", if (previous == SUCCEEDED) 1 else 0, revisions)
        return Replayed(observed, seamUses)
    }

    @Test
    fun `合同里的每个场景都通过调度器重放，每条规则都被真实的决定覆盖，也出现过被拒绝的`() = runTest {
        val all = scenarios()
        assertTrue("合同里应当有场景", all.isNotEmpty())

        val observed = mutableListOf<Observed>()
        val seams = mutableListOf<String>()
        val replayed = mutableListOf<String>()
        for (scenario in all) {
            val result = replay(scenario)
            observed += result.observed
            seams += result.seamUses
            replayed += scenario.name
        }

        assertEquals("每个场景都重放了，一个不多一个不少", all.map { it.name }, replayed)
        val covered = observed.map { Triple(it.before, it.event, it.outcome) }.toSet()
        val rules = machine.getValue("transitions").jsonArray.map {
            val rule = it.jsonObject
            Triple(
                TranscriptionState.valueOf(rule.getValue("from").jsonPrimitive.content),
                TranscriptionEvent.valueOf(rule.getValue("event").jsonPrimitive.content),
                outcomeOf(rule.getValue("outcome").jsonPrimitive.content),
            )
        }
        assertEquals("合同里的规则条数", 20, rules.size)
        val uncovered = rules.filter { it !in covered }
        assertTrue("这些规则没有被任何场景的真实决定覆盖：$uncovered", uncovered.isEmpty())
        assertTrue("应当出现过被拒绝的决定", observed.any { it.outcome == Outcome.REJECTED })
        assertTrue("应当出现过被忽略的决定", observed.any { it.outcome == Outcome.IGNORED })
        assertEquals(
            "测试接缝只用于“已结束的任务不能复活”这两步",
            listOf("finished-job-cannot-rerun:RUN_STARTED", "finished-job-cannot-rerun:RESULT_TEXT"),
            seams,
        )
    }

    @Test
    fun `重放用的接缝本身守规矩：对运行中的任务硬塞一个转移，状态机照样按表办事`() = runTest {
        val rig = SchedulerRig(this)
        val id = rig.addRecord(1)
        rig.hold(id)
        rig.scheduler.submit(id)
        rig.settle()
        val jobId = rig.jobOf(id).jobId

        rig.scheduler.injectStaleSignal(jobId, RUN_STARTED) // RUNNING + RUN_STARTED：表里没有这条
        rig.settle()

        assertEquals(RUNNING, rig.jobOf(id).state)
        val last = rig.decisionsOf(jobId).last()
        assertEquals(RUN_STARTED to Outcome.REJECTED, last.trigger to last.outcome)
    }
}
