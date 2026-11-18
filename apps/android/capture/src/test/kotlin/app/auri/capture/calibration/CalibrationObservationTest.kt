package app.auri.capture.calibration

import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.CaptureState
import app.auri.capture.contract.CaptureState.ARMED
import app.auri.capture.contract.CaptureState.FINALIZING
import app.auri.capture.contract.CaptureState.RECORDING
import app.auri.capture.contract.CaptureState.ROUTING
import app.auri.capture.contract.ControlSource
import app.auri.capture.contract.NormalizedControlEvent
import app.auri.capture.contract.PlatformEvent
import app.auri.capture.coordinator.CaptureDecision
import app.auri.capture.coordinator.Cause
import app.auri.capture.machine.CaptureEvent
import app.auri.capture.machine.Outcome
import app.auri.capture.testing.SequentialIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 构造“事件先到，协调器的决定随后”的输入，并替测试记着协调器当前的状态。 */
internal class Feed(val wizard: CalibrationWizard, var state: CaptureState = ARMED) {
    private val ids = SequentialIds(7)
    private var ns = 0L
    private var seq = 0L

    fun event(platformEvent: PlatformEvent, command: BusinessCommand, source: ControlSource = ControlSource.HEADSET_REMOTE): NormalizedControlEvent {
        val screen = source == ControlSource.SCREEN_CONTROL
        return NormalizedControlEvent(
            eventId = ids.newUuid(), source = source, platformEvent = if (screen) PlatformEvent.SCREEN_BUTTON else platformEvent, command = command,
            monotonicNs = (++ns) * 1_000_000L, sequence = ++seq,
            profileId = if (screen) null else "calibration-toggle-all", profileVersion = if (screen) null else 1, rawEventId = null,
        )
    }

    /** 协调器对一个命令做出的决定。 */
    fun decision(trigger: CaptureEvent?, outcome: Outcome, after: CaptureState, cause: Cause = Cause.MACHINE): CaptureDecision {
        val to = if (outcome == Outcome.TRANSITION) after else state
        return CaptureDecision(trigger, outcome, state, to, cause).also { state = to }
    }

    /** 一个控制事件到达并被协调器处理（转移到 [after]）。 */
    fun gesture(
        platformEvent: PlatformEvent,
        command: BusinessCommand,
        after: CaptureState,
        locked: Boolean? = false,
        source: ControlSource = ControlSource.HEADSET_REMOTE,
    ) {
        wizard.onControlEvent(event(platformEvent, command, source))
        wizard.onDecision(decision(trigger(command), Outcome.TRANSITION, after), locked)
    }

    /** 一个被映射成 IGNORE 的事件（第一步的“只观察”）。 */
    fun ignoredGesture(platformEvent: PlatformEvent, locked: Boolean? = false) {
        wizard.onControlEvent(event(platformEvent, BusinessCommand.IGNORE))
        wizard.onDecision(decision(null, Outcome.IGNORED, state, Cause.IGNORE_COMMAND), locked)
    }

    /** 不是控制命令引起的决定：路由确认、封存完成、路由丢失…… */
    fun other(trigger: CaptureEvent, after: CaptureState, locked: Boolean? = false) {
        wizard.onDecision(decision(trigger, Outcome.TRANSITION, after), locked)
    }

    private fun trigger(command: BusinessCommand): CaptureEvent? = when (command) {
        BusinessCommand.START -> CaptureEvent.START
        BusinessCommand.STOP -> CaptureEvent.STOP
        BusinessCommand.TOGGLE -> CaptureEvent.TOGGLE
        BusinessCommand.IGNORE -> null
    }
}

class CalibrationObservationTest {
    private fun wizardAt(index: Int): CalibrationWizard = CalibrationWizard("run-1", EvidenceSetting.UNIT_TEST).also { w ->
        w.start()
        repeat(index) { w.next() }
    }

    private val CalibrationWizard.summary get() = state.value.summary

    @Test
    fun `没开始、放弃之后的输入不记录；开始之后从零计起`() {
        val w = CalibrationWizard("run-1", EvidenceSetting.UNIT_TEST)
        Feed(w).gesture(PlatformEvent.TOGGLE, BusinessCommand.TOGGLE, ROUTING)
        w.onScreenLockChanged(true)
        w.start()
        assertEquals(StepSummary(), w.summary)

        val aborted = wizardAt(0)
        aborted.abort()
        Feed(aborted).gesture(PlatformEvent.TOGGLE, BusinessCommand.TOGGLE, ROUTING)
        assertEquals(StepSummary(), aborted.summary)
    }

    @Test
    fun `第一步按种类给耳机事件计数；屏幕按钮的事件不算耳机手势`() {
        val w = wizardAt(0)
        val feed = Feed(w)
        feed.ignoredGesture(PlatformEvent.PLAY)
        feed.ignoredGesture(PlatformEvent.PLAY)
        feed.ignoredGesture(PlatformEvent.TOGGLE)
        feed.ignoredGesture(PlatformEvent.NEXT)
        feed.gesture(PlatformEvent.SCREEN_BUTTON, BusinessCommand.START, ROUTING, source = ControlSource.SCREEN_CONTROL)

        assertEquals(mapOf(PlatformEvent.PLAY to 2, PlatformEvent.TOGGLE to 1, PlatformEvent.NEXT to 1), w.summary.eventCounts)
        assertFalse("屏幕上点开始不是耳机手势", w.summary.startedByGesture)
    }

    @Test
    fun `来源不明的遥控事件也计数：系统没说它来自哪里，不等于它不是耳机`() {
        val w = wizardAt(0)
        val feed = Feed(w)
        feed.wizard.onControlEvent(feed.event(PlatformEvent.PAUSE, BusinessCommand.IGNORE, ControlSource.UNKNOWN_REMOTE))
        assertEquals(mapOf(PlatformEvent.PAUSE to 1), w.summary.eventCounts)
    }

    @Test
    fun `耳机手势让录音开始、再让它停止，分别被记下`() {
        val w = wizardAt(2)
        val feed = Feed(w)
        feed.gesture(PlatformEvent.TOGGLE, BusinessCommand.TOGGLE, ROUTING)
        assertTrue(w.summary.startedByGesture)
        assertFalse(w.summary.stoppedByGesture)

        feed.other(CaptureEvent.ROUTE_VERIFIED, RECORDING)
        assertFalse("路由确认不是手势", w.summary.stoppedByGesture)

        feed.gesture(PlatformEvent.TOGGLE, BusinessCommand.TOGGLE, FINALIZING)
        assertTrue(w.summary.stoppedByGesture)
        assertEquals(mapOf(PlatformEvent.TOGGLE to 2), w.summary.eventCounts)
    }

    @Test
    fun `屏幕按钮开始和停止都不算耳机手势`() {
        val w = wizardAt(1)
        val feed = Feed(w)
        feed.gesture(PlatformEvent.SCREEN_BUTTON, BusinessCommand.START, ROUTING, source = ControlSource.SCREEN_CONTROL)
        feed.other(CaptureEvent.ROUTE_VERIFIED, RECORDING)
        feed.gesture(PlatformEvent.SCREEN_BUTTON, BusinessCommand.STOP, FINALIZING, source = ControlSource.SCREEN_CONTROL)
        assertEquals(StepSummary(), w.summary)
    }

    @Test
    fun `被忽略的命令和被拒绝的转移不算开始也不算停止`() {
        val w = wizardAt(1)
        val feed = Feed(w, state = RECORDING)
        // 耳机事件到了，但协调器忽略了它（例如开始遇到正在录音）
        w.onControlEvent(feed.event(PlatformEvent.PLAY, BusinessCommand.START))
        w.onDecision(feed.decision(CaptureEvent.START, Outcome.IGNORED, RECORDING), false)
        w.onControlEvent(feed.event(PlatformEvent.PLAY, BusinessCommand.TOGGLE))
        w.onDecision(feed.decision(CaptureEvent.TOGGLE, Outcome.REJECTED, RECORDING), false)

        assertFalse(w.summary.startedByGesture)
        assertFalse(w.summary.stoppedByGesture)
        assertEquals(mapOf(PlatformEvent.PLAY to 2), w.summary.eventCounts)
    }

    @Test
    fun `非控制引起的决定不消耗待配对的事件`() {
        val w = wizardAt(2)
        val feed = Feed(w)
        w.onControlEvent(feed.event(PlatformEvent.TOGGLE, BusinessCommand.TOGGLE))
        // 路由确认的决定恰好先到：它不是这个手势的决定
        w.onDecision(feed.decision(CaptureEvent.ROUTE_VERIFIED, Outcome.IGNORED, ARMED), false)
        w.onDecision(feed.decision(CaptureEvent.TOGGLE, Outcome.TRANSITION, ROUTING), false)
        assertTrue(w.summary.startedByGesture)
    }

    @Test
    fun `丢了一个决定也不会把屏幕上的点击记成耳机手势`() {
        val w = wizardAt(2)
        val feed = Feed(w)
        // 耳机的切换事件没有等到决定；随后用户在屏幕上点了开始，它的决定是 START
        w.onControlEvent(feed.event(PlatformEvent.TOGGLE, BusinessCommand.TOGGLE))
        w.onControlEvent(feed.event(PlatformEvent.SCREEN_BUTTON, BusinessCommand.START, ControlSource.SCREEN_CONTROL))
        w.onDecision(feed.decision(CaptureEvent.START, Outcome.TRANSITION, ROUTING), false)

        assertFalse("决定对不上最早的事件，就不能记在它头上", w.summary.startedByGesture)
    }

    @Test
    fun `重做清掉这一步的观察；下一步从空白开始`() {
        val w = wizardAt(0)
        val feed = Feed(w)
        feed.ignoredGesture(PlatformEvent.PLAY)
        w.repeat()
        assertEquals(StepSummary(), w.summary)
        feed.ignoredGesture(PlatformEvent.PAUSE)
        assertEquals(mapOf(PlatformEvent.PAUSE to 1), w.summary.eventCounts)
        w.next()
        assertEquals(StepSummary(), w.summary)
    }

    @Test
    fun `跳过这一步，它的观察作废`() {
        val w = wizardAt(0)
        Feed(w).ignoredGesture(PlatformEvent.PLAY)
        w.skip()
        assertEquals(StepSummary(), w.summary)
    }

    @Test
    fun `步骤边界：上一步里还没等到决定的屏幕停止，它的决定晚到时，不能配给下一步里耳机发的停止键`() {
        val w = wizardAt(1) // 第二步
        val feed = Feed(w, state = RECORDING)
        w.onControlEvent(feed.event(PlatformEvent.SCREEN_BUTTON, BusinessCommand.STOP, ControlSource.SCREEN_CONTROL)) // 屏幕停止：决定还没送到
        w.next() // 翻到第三步
        w.onControlEvent(feed.event(PlatformEvent.PAUSE, BusinessCommand.STOP)) // 第三步里耳机发来停止键（录音其实已经在封存）
        w.onDecision(feed.decision(CaptureEvent.STOP, Outcome.TRANSITION, FINALIZING), false) // 先到的是屏幕停止的决定
        w.onDecision(feed.decision(CaptureEvent.STOP, Outcome.IGNORED, FINALIZING), false) // 后到耳机停止的决定：已在封存，被忽略
        assertFalse("停下录音的是屏幕按钮，不是耳机", w.summary.stoppedByGesture)
    }

    @Test
    fun `有触发器的内部错误（比如封存失败）不是“命令处理时抛了异常”：不清空队列，待决事件的决定照常配对`() {
        val w = wizardAt(1) // 第二步
        val feed = Feed(w, state = RECORDING)
        w.onControlEvent(feed.event(PlatformEvent.PAUSE, BusinessCommand.STOP)) // 耳机的停止键到了，决定还没送到
        w.onDecision(feed.decision(CaptureEvent.FINALIZE_COMPLETE, Outcome.REJECTED, RECORDING, Cause.INTERNAL_ERROR), false) // 与它无关的一个封存失败
        w.onDecision(feed.decision(CaptureEvent.STOP, Outcome.TRANSITION, FINALIZING), false) // 停止键自己的决定
        assertTrue("停止键的决定仍然配得上它的事件", w.summary.stoppedByGesture)
    }

    @Test
    fun `声明没效果之后又收到一次耳机事件：声明作废，它说的是更早那次尝试；屏幕按钮的事件不作废它`() {
        val w = wizardAt(1)
        val feed = Feed(w)
        w.reportNoEffect()

        feed.gesture(PlatformEvent.SCREEN_BUTTON, BusinessCommand.START, ROUTING, source = ControlSource.SCREEN_CONTROL)
        assertTrue("屏幕按钮不是耳机手势，不能作废“耳机手势没反应”的声明", w.summary.ownerReportedNoEffect)

        feed.gesture(PlatformEvent.TOGGLE, BusinessCommand.TOGGLE, ARMED)
        assertFalse("用户又试了一次耳机手势：之前的声明说的是更早的那次", w.summary.ownerReportedNoEffect)

        w.reportNoEffect()
        assertTrue("之后再声明一次，是对最近这次尝试的声明", w.summary.ownerReportedNoEffect)
    }
}

class ControlDecisionPairingTest {
    private val ids = SequentialIds(9)
    private var ns = 0L
    private var seq = 0L

    private fun event(command: BusinessCommand): NormalizedControlEvent = NormalizedControlEvent(
        eventId = ids.newUuid(), source = ControlSource.HEADSET_REMOTE, platformEvent = PlatformEvent.TOGGLE, command = command,
        monotonicNs = (++ns) * 1_000_000L, sequence = ++seq, profileId = "calibration-toggle-all", profileVersion = 1, rawEventId = null,
    )

    private fun decision(trigger: CaptureEvent?, cause: Cause = Cause.MACHINE) = CaptureDecision(trigger, Outcome.TRANSITION, ARMED, ROUTING, cause)

    @Test
    fun `按先后顺序一对一配对`() {
        val pairing = ControlDecisionPairing()
        val first = event(BusinessCommand.TOGGLE)
        val second = event(BusinessCommand.TOGGLE)
        pairing.offer(first)
        pairing.offer(second)
        assertSame(first, pairing.match(decision(CaptureEvent.TOGGLE)))
        assertSame(second, pairing.match(decision(CaptureEvent.TOGGLE)))
        assertNull("没有待配对的事件了", pairing.match(decision(CaptureEvent.TOGGLE)))
    }

    @Test
    fun `只有开始、停止、切换和被忽略的命令才是控制决定；其余的不配对也不消耗`() {
        val pairing = ControlDecisionPairing()
        val toggle = event(BusinessCommand.TOGGLE)
        pairing.offer(toggle)
        listOf(CaptureEvent.ROUTE_VERIFIED, CaptureEvent.ROUTE_LOST, CaptureEvent.FINALIZE_COMPLETE, CaptureEvent.DURATION_LIMIT, CaptureEvent.USER_CANCEL, CaptureEvent.ENABLE_CONTROL_MODE)
            .forEach { assertNull("$it", pairing.match(decision(it))) }
        assertNull("内部错误无法确定对应哪个命令", pairing.match(decision(null, Cause.INTERNAL_ERROR)))
        assertEquals(1, pairing.size)
        assertSame(toggle, pairing.match(decision(CaptureEvent.TOGGLE)))
    }

    @Test
    fun `内部错误没有触发器，也不能消耗一个被忽略的命令的事件`() {
        val pairing = ControlDecisionPairing()
        val ignored = event(BusinessCommand.IGNORE)
        pairing.offer(ignored)
        assertNull(pairing.match(decision(null, Cause.INTERNAL_ERROR)))
        assertNull(pairing.match(decision(null, Cause.MACHINE)))
        assertEquals(1, pairing.size)
        assertSame(ignored, pairing.match(decision(null, Cause.IGNORE_COMMAND)))
    }

    @Test
    fun `被忽略的命令（IGNORE）对应没有触发器的决定`() {
        val pairing = ControlDecisionPairing()
        val ignored = event(BusinessCommand.IGNORE)
        pairing.offer(ignored)
        assertSame(ignored, pairing.match(decision(null, Cause.IGNORE_COMMAND)))
    }

    @Test
    fun `决定对不上队首的事件时，丢掉没等到决定的事件，找到对得上的再配对`() {
        val pairing = ControlDecisionPairing()
        val lost = event(BusinessCommand.TOGGLE)
        val screenStart = event(BusinessCommand.START)
        pairing.offer(lost)
        pairing.offer(screenStart)
        assertSame(screenStart, pairing.match(decision(CaptureEvent.START)))
        assertEquals("丢失的事件已经清走，不会再错配给后面的决定", 0, pairing.size)
    }

    @Test
    fun `完全对不上的决定不配对，也不动待配对的事件`() {
        val pairing = ControlDecisionPairing()
        val toggle = event(BusinessCommand.TOGGLE)
        pairing.offer(toggle)
        assertNull(pairing.match(decision(CaptureEvent.STOP)))
        assertEquals(1, pairing.size)
        assertSame(toggle, pairing.match(decision(CaptureEvent.TOGGLE)))
    }

    @Test
    fun `待配对的事件有上限，丢的是最老的`() {
        val pairing = ControlDecisionPairing(capacity = 4)
        val all = List(10) { event(BusinessCommand.TOGGLE) }
        all.forEach { pairing.offer(it) }
        assertEquals(4, pairing.size)
        assertSame(all[6], pairing.match(decision(CaptureEvent.TOGGLE)))
    }

    @Test
    fun `清空之后什么都配不上`() {
        val pairing = ControlDecisionPairing()
        pairing.offer(event(BusinessCommand.TOGGLE))
        pairing.clear()
        assertEquals(0, pairing.size)
        assertNull(pairing.match(decision(CaptureEvent.TOGGLE)))
    }

    @Test
    fun `放弃归属之后，旧事件的决定晚到时由它们自己吸收：不会错配给之后到的新事件，也不会记到任何手势头上`() {
        val pairing = ControlDecisionPairing()
        pairing.offer(event(BusinessCommand.STOP))
        pairing.discard()
        assertEquals("放弃归属的事件不再算待配对", 0, pairing.size)
        val fresh = event(BusinessCommand.STOP)
        pairing.offer(fresh)
        assertEquals(1, pairing.size)
        assertNull("旧事件的决定先到：被旧事件吸收，不归因给任何事件", pairing.match(decision(CaptureEvent.STOP)))
        assertSame("新事件自己的决定", fresh, pairing.match(decision(CaptureEvent.STOP)))
    }

    @Test
    fun `放弃归属的事件，对不上触发器时照样当作丢了决定清走，不会一直占着位置`() {
        val pairing = ControlDecisionPairing()
        pairing.offer(event(BusinessCommand.TOGGLE))
        pairing.discard()
        val fresh = event(BusinessCommand.START)
        pairing.offer(fresh)
        assertSame(fresh, pairing.match(decision(CaptureEvent.START)))
        assertNull("旧的切换事件已经被当作丢了决定清走，不会再吸收后面的切换决定", pairing.match(decision(CaptureEvent.TOGGLE)))
    }
}
