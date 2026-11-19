package app.auri.capture.feedback

import app.auri.capture.contract.CaptureState
import app.auri.capture.contract.CaptureState.ARMED
import app.auri.capture.contract.CaptureState.BLOCKED
import app.auri.capture.contract.CaptureState.FINALIZING
import app.auri.capture.contract.CaptureState.RECORDING
import app.auri.capture.contract.CaptureState.ROUTING
import app.auri.capture.coordinator.CaptureDecision
import app.auri.capture.coordinator.Cause
import app.auri.capture.machine.CaptureEvent
import app.auri.capture.machine.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 反馈泵：协调器的每个决定问一次 [feedbackFor]，有对应的反馈就交给播放器、并留给界面显示屏幕文字。
 * 要钉住的是两件事：只有真的“开始 / 停止 / 没能开始”才出声响（准确）；播放器出任何问题都不影响录音与后面的反馈（隔离）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedbackHubTest {
    private class RecordingPlayer : FeedbackPlayer {
        val played = mutableListOf<FeedbackKind>()
        var failWith: RuntimeException? = null

        override fun play(feedback: Feedback) {
            played += feedback.kind
            failWith?.let { throw it }
        }
    }

    private class Rig(scope: TestScope) {
        val decisions = MutableSharedFlow<CaptureDecision>(extraBufferCapacity = 64)
        val player = RecordingPlayer()
        val logs = mutableListOf<String>()
        val hub = FeedbackHub(decisions, player, scope.backgroundScope) { logs += it }

        fun decide(
            trigger: CaptureEvent?,
            before: CaptureState,
            after: CaptureState,
            outcome: Outcome = Outcome.TRANSITION,
            cause: Cause = Cause.MACHINE,
        ) {
            decisions.tryEmit(CaptureDecision(trigger, outcome, before, after, cause))
        }
    }

    private fun runUnconfined(block: suspend TestScope.() -> Unit) = runTest(UnconfinedTestDispatcher()) { block() }

    @Test
    fun `没有任何决定时没有反馈`() = runUnconfined {
        val rig = Rig(this)

        assertNull(rig.hub.latest.value)
        assertEquals(emptyList<FeedbackKind>(), rig.player.played)
    }

    @Test
    fun `一次完整的录音：开始、停止各给一次，顺序与发生的顺序一致，屏幕文字是最近一次的`() = runUnconfined {
        val rig = Rig(this)

        rig.decide(CaptureEvent.TOGGLE, ARMED, ROUTING) // 只是进入准备，输入还没确认
        assertEquals(emptyList<FeedbackKind>(), rig.player.played)
        rig.decide(CaptureEvent.ROUTE_VERIFIED, ROUTING, RECORDING)
        assertEquals(Feedbacks.of(FeedbackKind.STARTED), rig.hub.latest.value)
        rig.decide(CaptureEvent.TOGGLE, RECORDING, FINALIZING)
        rig.decide(CaptureEvent.FINALIZE_COMPLETE, FINALIZING, ARMED)

        assertEquals(listOf(FeedbackKind.STARTED, FeedbackKind.STOPPED), rig.player.played)
        assertEquals("封存完成没有反馈，屏幕上仍是“已停止”", Feedbacks.of(FeedbackKind.STOPPED), rig.hub.latest.value)
    }

    @Test
    fun `没能开始：准备阶段受阻给“没能开始”`() = runUnconfined {
        val rig = Rig(this)

        rig.decide(CaptureEvent.START, ARMED, ROUTING)
        rig.decide(CaptureEvent.ROUTE_UNVERIFIED, ROUTING, BLOCKED)

        assertEquals(listOf(FeedbackKind.START_FAILED), rig.player.played)
        assertEquals(Feedbacks.of(FeedbackKind.START_FAILED), rig.hub.latest.value)
    }

    @Test
    fun `被忽略、被拒绝的命令和用户自己取消准备都不出声响，也不改屏幕上的提示`() = runUnconfined {
        val rig = Rig(this)
        rig.decide(CaptureEvent.ROUTE_VERIFIED, ROUTING, RECORDING)
        rig.decide(CaptureEvent.TOGGLE, RECORDING, FINALIZING)
        val before = rig.hub.latest.value

        rig.decide(CaptureEvent.TOGGLE, FINALIZING, FINALIZING, Outcome.IGNORED, Cause.STALE_COMMAND) // 封存中过时的切换
        rig.decide(CaptureEvent.STOP, ARMED, ARMED, Outcome.REJECTED)
        rig.decide(null, ARMED, ARMED, Outcome.IGNORED, Cause.IGNORE_COMMAND)
        rig.decide(CaptureEvent.STOP, ROUTING, ARMED) // 准备中取消

        assertEquals(listOf(FeedbackKind.STARTED, FeedbackKind.STOPPED), rig.player.played)
        assertEquals(before, rig.hub.latest.value)
    }

    @Test
    fun `播放器抛异常：屏幕提示照样给出，错误被记下（只记类型），后面的反馈照常播放`() = runUnconfined {
        val rig = Rig(this)
        rig.player.failWith = IllegalStateException("振动器坏了，设备名 FreeClip")

        rig.decide(CaptureEvent.ROUTE_VERIFIED, ROUTING, RECORDING)

        assertEquals("振动失败也要让用户在屏幕上看到", Feedbacks.of(FeedbackKind.STARTED), rig.hub.latest.value)
        assertEquals(1, rig.logs.size)
        assertEquals("日志只写失败的步骤与异常类型，不写异常消息", "feedback_play_failed reason=IllegalStateException", rig.logs.single())

        rig.player.failWith = null
        rig.decide(CaptureEvent.TOGGLE, RECORDING, FINALIZING)

        assertEquals(listOf(FeedbackKind.STARTED, FeedbackKind.STOPPED), rig.player.played)
        assertEquals(Feedbacks.of(FeedbackKind.STOPPED), rig.hub.latest.value)
    }

    @Test
    fun `订阅在创建时就登记好：创建之后立刻发出的第一个决定也不会漏`() = runUnconfined {
        // 与协调器的决定流一样：有缓冲、没有回放，没人订阅时发出的决定直接丢
        val decisions = MutableSharedFlow<CaptureDecision>(extraBufferCapacity = 8)
        val player = RecordingPlayer()
        // 标准调度器不会立刻运行新协程：如果订阅要等调度器，下面的决定发出时还没有人订阅，就丢了
        val scope = CoroutineScope(backgroundScope.coroutineContext + StandardTestDispatcher(testScheduler))
        val hub = FeedbackHub(decisions, player, scope)

        decisions.tryEmit(CaptureDecision(CaptureEvent.ROUTE_VERIFIED, Outcome.TRANSITION, ROUTING, RECORDING, Cause.MACHINE))
        testScheduler.runCurrent()

        assertEquals(listOf(FeedbackKind.STARTED), player.played)
        assertEquals(Feedbacks.of(FeedbackKind.STARTED), hub.latest.value)
    }
}
