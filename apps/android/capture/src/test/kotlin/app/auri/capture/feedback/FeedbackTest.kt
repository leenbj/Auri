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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反馈：用户戴着耳机、手机在口袋里，要能不看屏幕分清“开始了 / 停止了 / 没能开始”。
 * 为什么只有振动和文字：提示音会进入蓝牙通路（耳机麦克风正在录音），所以反馈里根本没有声音这一项。
 */
class FeedbackTest {
    private fun decision(
        trigger: CaptureEvent?,
        before: CaptureState,
        after: CaptureState,
        outcome: Outcome = Outcome.TRANSITION,
    ) = CaptureDecision(trigger, outcome, before, after, Cause.MACHINE)

    @Test
    fun `路由确认、录音真的开始了才是“已开始”：只是进入准备不算`() {
        assertEquals(FeedbackKind.STARTED, feedbackFor(decision(CaptureEvent.ROUTE_VERIFIED, ROUTING, RECORDING)))
        assertNull("还在确认蓝牙输入，录音没开始，不能先告诉用户已开始", feedbackFor(decision(CaptureEvent.TOGGLE, ARMED, ROUTING)))
        assertNull(feedbackFor(decision(CaptureEvent.START, BLOCKED, ROUTING)))
    }

    @Test
    fun `录音停止就是“已停止”，不论是手势、屏幕、时长上限还是路由丢失`() {
        listOf(CaptureEvent.STOP, CaptureEvent.TOGGLE, CaptureEvent.DURATION_LIMIT, CaptureEvent.ROUTE_LOST, CaptureEvent.SYSTEM_INTERRUPTION, CaptureEvent.PERMISSION_REVOKED)
            .forEach { assertEquals("$it", FeedbackKind.STOPPED, feedbackFor(decision(it, RECORDING, FINALIZING))) }
    }

    @Test
    fun `准备阶段受阻是“没能开始”：路由没确认、超时、前台服务起不来、权限被撤回`() {
        listOf(CaptureEvent.ROUTE_UNVERIFIED, CaptureEvent.ROUTE_TIMEOUT, CaptureEvent.PERMISSION_REVOKED)
            .forEach { assertEquals("$it", FeedbackKind.START_FAILED, feedbackFor(decision(it, ROUTING, BLOCKED))) }
    }

    @Test
    fun `其余情况都没有反馈：取消准备、封存完成、被忽略或被拒绝的命令、没有状态变化`() {
        assertNull(feedbackFor(decision(CaptureEvent.USER_CANCEL, ROUTING, ARMED)))
        assertNull("停止准备中的录音是用户自己取消，不是失败", feedbackFor(decision(CaptureEvent.STOP, ROUTING, ARMED)))
        assertNull(feedbackFor(decision(CaptureEvent.FINALIZE_COMPLETE, FINALIZING, ARMED)))
        assertNull("封存中的过时切换命令被忽略，不能让用户以为又开始了", feedbackFor(decision(CaptureEvent.TOGGLE, FINALIZING, FINALIZING, Outcome.IGNORED)))
        assertNull(feedbackFor(decision(CaptureEvent.START, RECORDING, RECORDING, Outcome.IGNORED)))
        assertNull(feedbackFor(decision(CaptureEvent.STOP, ARMED, ARMED, Outcome.REJECTED)))
        assertNull(feedbackFor(decision(null, ARMED, ARMED, Outcome.IGNORED)))
        assertNull(feedbackFor(decision(CaptureEvent.PERMISSION_REVOKED, ARMED, BLOCKED)))
    }

    @Test
    fun `四种反馈互相可以区分：振动的脉冲数或长度不同，屏幕文字和通知文字也各不相同`() {
        val all = FeedbackKind.entries.map { Feedbacks.of(it) }
        assertEquals(4, all.size)
        assertEquals(all.size, all.map { it.vibrationPattern }.toSet().size)
        assertEquals(all.size, all.map { it.screenText }.toSet().size)
        assertEquals(all.size, all.map { it.notificationText }.toSet().size)
        all.forEach {
            assertTrue(it.screenText.isNotBlank() && it.notificationText.isNotBlank())
            assertTrue("振动波形从“关”开始，关与开交替，长度为偶数", it.vibrationPattern.size % 2 == 0 && it.vibrationPattern.first() == 0L)
            assertTrue("每个脉冲都真的在振", it.pulses.all { pulse -> pulse > 0 })
        }
        // 不看屏幕也能分清：任意两种之间，脉冲个数不同，或最长的脉冲至少相差三倍
        for (a in all) for (b in all) {
            if (a.kind == b.kind) continue
            val countDiffers = a.pulses.size != b.pulses.size
            val lengthDiffers = a.pulses.max() >= 3 * b.pulses.max() || b.pulses.max() >= 3 * a.pulses.max()
            assertTrue("${a.kind} 与 ${b.kind} 手感上要能分清", countDiffers || lengthDiffers)
        }
    }

    @Test
    fun `没能开始的振动最长，它是要用户马上处理的`() {
        val failed = Feedbacks.of(FeedbackKind.START_FAILED).pulses.max()
        assertTrue(failed > Feedbacks.of(FeedbackKind.STARTED).pulses.max())
        assertTrue(failed > Feedbacks.of(FeedbackKind.STOPPED).pulses.max())
    }
}
