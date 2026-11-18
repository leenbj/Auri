package app.auri.android.control

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import app.auri.android.home.CaptureFact
import app.auri.capture.adapter.Subscription
import app.auri.capture.calibration.CalibrationStep
import app.auri.capture.calibration.CapabilityRecord
import app.auri.capture.calibration.CompatStatus
import app.auri.capture.calibration.EvidenceLevel
import app.auri.capture.calibration.EvidenceSetting
import app.auri.capture.calibration.HeadsetProfile
import app.auri.capture.calibration.ProfileStore
import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.CaptureState
import app.auri.capture.contract.ControlSource
import app.auri.capture.contract.NormalizedControlEvent
import app.auri.capture.contract.PlatformEvent
import app.auri.capture.control.ActiveControlProfile
import app.auri.capture.control.ControlProfile
import app.auri.capture.control.HeadsetControlStatus
import app.auri.capture.coordinator.Cause
import app.auri.capture.coordinator.CaptureDecision
import app.auri.capture.coordinator.CaptureSnapshot
import app.auri.capture.coordinator.FrameCounters
import app.auri.capture.feedback.Feedback
import app.auri.capture.feedback.FeedbackKind
import app.auri.capture.feedback.Feedbacks
import app.auri.capture.machine.CaptureEvent
import app.auri.capture.machine.Outcome
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 耳机控制界面的数据层。要钉住的是这些规矩：
 *  - 控制模式开不开、耳机通道什么状态、采集到哪一步，是三个互相独立的事实；
 *  - 屏幕上的开始 / 停止按钮永远在，能不能按只由采集状态决定，耳机通道出什么问题都不会让它们消失；
 *  - 关闭控制模式不会把正在进行的录音丢掉；
 *  - 校准的证据等级由环境决定：模拟器永远是 E2，没确认是真机也是 E2，只有用户明确确认才是 E3；
 *  - 没有任何结论的校准不会被保存，也不会顶掉以前的档案；保存失败如实告诉用户。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ControlViewModelTest {
    private class FakeControls(initial: CaptureState) : ControlModeControls {
        val flow = MutableStateFlow(snapshot(initial))
        override val snapshot: StateFlow<CaptureSnapshot> = flow
        override val decisions = MutableSharedFlow<CaptureDecision>(extraBufferCapacity = 64)
        val calls = mutableListOf<String>()
        private var version = 0L
        private var closing = false
        private var disableSent = false

        fun move(state: CaptureState) {
            if (closing && state == CaptureState.ARMED && !disableSent) {
                calls += "disable"
                disableSent = true
            }
            if (state == CaptureState.DISARMED) closing = false
            flow.value = snapshot(state, ++version)
        }

        override fun start() {
            calls += "start"
        }

        override fun stop() {
            calls += "stop"
        }

        override fun enableControlMode() {
            calls += "enable"
        }

        override fun disableControlMode() {
            calls += "disable"
        }

        override fun stopAndDisableControlMode() {
            closing = true
            disableSent = false
            when (snapshot.value.state) {
                CaptureState.RECORDING -> calls += "stop"
                CaptureState.FINALIZING -> Unit
                else -> { calls += "disable"; disableSent = true }
            }
        }
    }

    private class FakeEvents {
        private val observers = mutableListOf<(NormalizedControlEvent) -> Unit>()
        val observerCount: Int get() = observers.size

        /** 撤销旁听（放弃校准的过程中）时顺手执行一次：模拟“正在放弃校准的这一瞬间，别处的状态变了”。 */
        var afterUnsubscribe: (() -> Unit)? = null

        fun observe(observer: (NormalizedControlEvent) -> Unit): Subscription {
            observers += observer
            return Subscription {
                observers -= observer
                afterUnsubscribe?.let { hook ->
                    afterUnsubscribe = null
                    hook()
                }
            }
        }

        fun emit(event: NormalizedControlEvent) = observers.toList().forEach { it(event) }
    }

    private class FakeStore(var stored: HeadsetProfile? = null) : ProfileStore {
        val saves = mutableListOf<HeadsetProfile>()
        var failWith: IOException? = null

        override fun load(): HeadsetProfile? = stored

        override fun save(profile: HeadsetProfile) {
            failWith?.let { throw it }
            saves += profile
            stored = profile
        }
    }

    /** 读数刚变、通知还没送到收集者：`value` 已经是新读数，收集者收到的还是旧的。 */
    private class LaggingLock(private val delivered: Boolean?) : StateFlow<Boolean?> {
        @Volatile
        var current: Boolean? = delivered
        override val value: Boolean? get() = current
        override val replayCache: List<Boolean?> get() = listOf(delivered)
        override suspend fun collect(collector: FlowCollector<Boolean?>): Nothing {
            collector.emit(delivered)
            awaitCancellation()
        }
    }

    private class Rig(
        scope: TestScope,
        initial: CaptureState,
        isEmulator: Boolean,
        val store: FakeStore,
        val active: ActiveControlProfile,
        lockSource: StateFlow<Boolean?>? = null,
    ) {
        val controls = FakeControls(initial)
        val headset = MutableStateFlow(HeadsetControlStatus.OFF)
        val feedback = MutableStateFlow<Feedback?>(null)
        val lock = MutableStateFlow<Boolean?>(false)
        val events = FakeEvents()
        private var runs = 0
        private var ns = 0L
        private var seq = 0L
        val viewModel = ControlViewModel(
            controls, headset, feedback, lockSource ?: lock, events::observe, active, store, isEmulator, { "cal-${++runs}" }, scope.backgroundScope,
        )
        val state: ControlUiState get() = viewModel.state.value

        /** 耳机发来一个事件（经合成适配器的旁听口递送给向导）。 */
        fun gesture(platformEvent: PlatformEvent, command: BusinessCommand, source: ControlSource = ControlSource.HEADSET_REMOTE) {
            events.emit(
                NormalizedControlEvent(
                    eventId = "%08x-0000-4000-8000-%012x".format(0x17, ++seq), source = source,
                    platformEvent = if (source == ControlSource.SCREEN_CONTROL) PlatformEvent.SCREEN_BUTTON else platformEvent, command = command,
                    monotonicNs = (++ns) * 1_000_000L, sequence = seq,
                    profileId = if (source == ControlSource.SCREEN_CONTROL) null else "calibration-toggle-all",
                    profileVersion = if (source == ControlSource.SCREEN_CONTROL) null else 1, rawEventId = null,
                ),
            )
        }

        /** 协调器对一个输入做出决定，状态随之移动。 */
        fun decide(trigger: CaptureEvent?, after: CaptureState, outcome: Outcome = Outcome.TRANSITION, cause: Cause = Cause.MACHINE) {
            val before = controls.flow.value.state
            controls.decisions.tryEmit(CaptureDecision(trigger, outcome, before, if (outcome == Outcome.TRANSITION) after else before, cause))
            if (outcome == Outcome.TRANSITION) controls.move(after)
        }
    }

    private fun TestScope.rig(
        initial: CaptureState = CaptureState.DISARMED,
        isEmulator: Boolean = false,
        store: FakeStore = FakeStore(),
        active: ActiveControlProfile = ActiveControlProfile(),
        lockSource: StateFlow<Boolean?>? = null,
    ) = Rig(this, initial, isEmulator, store, active, lockSource)

    private fun runUnconfined(block: suspend TestScope.() -> Unit) = runTest(UnconfinedTestDispatcher()) { block() }

    // ---- 三个事实互相独立 ----

    @Test
    fun `控制模式开不开只看采集状态：未开启是关，其余状态都是开`() = runUnconfined {
        val rig = rig()
        CaptureState.entries.forEach { state ->
            rig.controls.move(state)

            assertEquals("$state", state != CaptureState.DISARMED, rig.state.controlMode.on)
            assertEquals(CaptureFact(state, null), rig.state.capture)
        }
    }

    @Test
    fun `耳机通道的状态原样给出，包括系统没给出按键通道；它不改变控制模式的开关`() = runUnconfined {
        val rig = rig(CaptureState.ARMED)
        HeadsetControlStatus.entries.forEach { status ->
            rig.headset.value = status

            assertEquals(status, rig.state.controlMode.headset)
            assertTrue("通道状态不能让控制模式显示成关", rig.state.controlMode.on)
        }
    }

    @Test
    fun `屏幕上的开始与停止按钮在任何耳机状态、任何校准阶段下都在，能不能按只由采集状态决定`() = runUnconfined {
        val rig = rig()
        val startable = setOf(CaptureState.DISARMED, CaptureState.ARMED, CaptureState.BLOCKED)
        val stoppable = setOf(CaptureState.ROUTING, CaptureState.RECORDING)

        listOf(false, true).forEach { calibrating ->
            if (calibrating) rig.viewModel.startCalibration()
            HeadsetControlStatus.entries.forEach { headset ->
                CaptureState.entries.forEach { capture ->
                    rig.headset.value = headset
                    rig.controls.move(capture)

                    val where = "校准=$calibrating 耳机=$headset 采集=$capture"
                    assertEquals(where, capture in startable, rig.state.manual.startEnabled)
                    assertEquals(where, capture in stoppable, rig.state.manual.stopEnabled)
                }
            }
        }
        assertTrue("校准确实在进行", rig.state.calibration is CalibrationUi.Running)
    }

    @Test
    fun `开始与停止按钮把命令交给采集控制`() = runUnconfined {
        val rig = rig(CaptureState.ARMED)

        rig.viewModel.start()
        rig.viewModel.stop()

        assertEquals(listOf("start", "stop"), rig.controls.calls)
    }

    // ---- 控制模式开关 ----

    @Test
    fun `打开控制模式：没开时发出开启，已经开着就什么都不做`() = runUnconfined {
        val rig = rig()
        rig.viewModel.setControlMode(true)
        rig.controls.move(CaptureState.ARMED)
        rig.viewModel.setControlMode(true)

        assertEquals(listOf("enable"), rig.controls.calls)
    }

    @Test
    fun `关闭控制模式：待命、准备中、受阻时直接关`() = runUnconfined {
        listOf(CaptureState.ARMED, CaptureState.ROUTING, CaptureState.BLOCKED).forEach { state ->
            val rig = rig(state)

            rig.viewModel.setControlMode(false)

            assertEquals("$state", listOf("disable"), rig.controls.calls)
        }
    }

    @Test
    fun `关闭控制模式不丢录音：录音中先停止，等这次录音保存完（回到待命）才关`() = runUnconfined {
        val rig = rig(CaptureState.RECORDING)

        rig.viewModel.setControlMode(false)
        assertEquals("先停止，不能直接关", listOf("stop"), rig.controls.calls)
        assertTrue("界面显示正在关闭", rig.state.controlMode.turningOff)

        rig.controls.move(CaptureState.FINALIZING)
        assertEquals("保存中还不能关", listOf("stop"), rig.controls.calls)

        rig.controls.move(CaptureState.ARMED)
        assertEquals(listOf("stop", "disable"), rig.controls.calls)
        assertTrue(rig.state.controlMode.turningOff)

        rig.controls.move(CaptureState.ARMED)
        assertEquals("关闭只发出一次", listOf("stop", "disable"), rig.controls.calls)
        rig.controls.move(CaptureState.DISARMED)
        assertFalse(rig.state.controlMode.turningOff)
    }

    @Test
    fun `turning off stays pending until disarmed even when a new recording races the disable`() = runUnconfined {
        val rig = rig(CaptureState.RECORDING)
        rig.viewModel.setControlMode(false)
        rig.controls.move(CaptureState.FINALIZING)
        rig.controls.move(CaptureState.ARMED)

        assertTrue("the asynchronous disable is not yet confirmed", rig.state.controlMode.turningOff)
        rig.controls.move(CaptureState.RECORDING)
        assertTrue("a racing start must not forget the off request", rig.state.controlMode.turningOff)
        rig.controls.move(CaptureState.FINALIZING)
        rig.controls.move(CaptureState.DISARMED)
        assertFalse(rig.state.controlMode.turningOff)
    }

    @Test
    fun `保存中请求关闭：不再重复停止，保存完才关`() = runUnconfined {
        val rig = rig(CaptureState.FINALIZING)

        rig.viewModel.setControlMode(false)
        rig.controls.move(CaptureState.ARMED)

        assertEquals(listOf("disable"), rig.controls.calls)
    }

    @Test
    fun `等待关闭期间用户又打开控制模式：确认关闭之后再打开`() = runUnconfined {
        val rig = rig(CaptureState.RECORDING)
        rig.viewModel.setControlMode(false)

        rig.viewModel.setControlMode(true)
        rig.controls.move(CaptureState.FINALIZING)
        rig.controls.move(CaptureState.ARMED)
        assertEquals(listOf("stop", "disable"), rig.controls.calls)
        rig.controls.move(CaptureState.DISARMED)

        assertEquals(listOf("stop", "disable", "enable"), rig.controls.calls)
        assertFalse(rig.state.controlMode.turningOff)
    }

    @Test
    fun `请求关闭的瞬间，保存正好完成（读状态与登记请求之间状态变了）：不能卡在“正在关闭”，要直接关`() = runUnconfined {
        val rig = rig(CaptureState.ARMED)
        rig.viewModel.startCalibration() // 关闭会先放弃这一轮校准，放弃的过程里撤旁听
        rig.controls.move(CaptureState.FINALIZING) // 用户点关闭时正在保存
        rig.events.afterUnsubscribe = { rig.controls.move(CaptureState.ARMED) } // 放弃校准的这一瞬间，保存完成回到待命

        rig.viewModel.setControlMode(false)

        assertEquals(listOf("disable"), rig.controls.calls)
        assertTrue(rig.state.controlMode.turningOff)
        rig.controls.move(CaptureState.DISARMED)
        assertFalse(rig.state.controlMode.turningOff)
    }

    @Test
    fun `正在等录音保存完再关闭控制模式：此时开始校准不理会，不会开出一轮注定没有控制模式的校准`() = runUnconfined {
        val rig = rig(CaptureState.RECORDING)
        rig.viewModel.setControlMode(false)

        rig.viewModel.startCalibration()

        assertTrue(rig.state.calibration is CalibrationUi.Setup)
        assertEquals("没有开出向导，也没有多出一路旁听", 0, rig.events.observerCount)
        rig.controls.move(CaptureState.FINALIZING)
        rig.controls.move(CaptureState.ARMED)
        assertEquals("关闭照常兑现", listOf("stop", "disable"), rig.controls.calls)
    }

    @Test
    fun `关闭控制模式会放弃正在进行的校准`() = runUnconfined {
        val rig = rig(CaptureState.ARMED)
        rig.viewModel.startCalibration()

        rig.viewModel.setControlMode(false)

        assertTrue(rig.state.calibration is CalibrationUi.Setup)
        assertEquals(ControlNotice.CalibrationAborted, rig.state.notice)
        assertEquals("向导的旁听已撤销", 0, rig.events.observerCount)
    }

    // ---- 反馈 ----

    @Test
    fun `三种反馈的屏幕文字都能读到，互相不同；没有反馈时没有文字`() = runUnconfined {
        val rig = rig(CaptureState.ARMED)
        assertNull(rig.state.feedback)

        val texts = FeedbackKind.entries.map { kind ->
            rig.feedback.value = Feedbacks.of(kind)
            checkNotNull(rig.state.feedback).also { assertEquals(kind, it.kind) }.screenText
        }

        assertEquals(3, texts.toSet().size)
    }

    // ---- 校准：向导接线 ----

    @Test
    fun `开始校准：控制模式没开就先开；第一步只观察，之后的步骤换成全按切换`() = runUnconfined {
        val active = ActiveControlProfile()
        val rig = rig(CaptureState.DISARMED, active = active)

        rig.viewModel.startCalibration()

        assertEquals(listOf("enable"), rig.controls.calls)
        val running = rig.state.calibration as CalibrationUi.Running
        assertEquals(CalibrationStep.IDLE_EVENTS, running.step)
        assertEquals(1, running.stepNumber)
        assertEquals("第一步只观察：命令全是忽略，按耳机不会开始录音", BusinessCommand.IGNORE, active.current.commandFor(PlatformEvent.TOGGLE))

        rig.viewModel.nextStep()

        assertEquals(CalibrationStep.GESTURE_WHILE_RECORDING, (rig.state.calibration as CalibrationUi.Running).step)
        assertEquals(BusinessCommand.TOGGLE, active.current.commandFor(PlatformEvent.PLAY))
        assertEquals(BusinessCommand.TOGGLE, active.current.commandFor(PlatformEvent.PAUSE))
    }

    @Test
    fun `向导收到耳机事件，界面状态里的计数跟着变；屏幕按钮的事件不算`() = runUnconfined {
        val rig = rig(CaptureState.ARMED)
        rig.viewModel.startCalibration()

        rig.gesture(PlatformEvent.PLAY, BusinessCommand.IGNORE, ControlSource.UNKNOWN_REMOTE)
        rig.gesture(PlatformEvent.PLAY, BusinessCommand.IGNORE)
        rig.gesture(PlatformEvent.SCREEN_BUTTON, BusinessCommand.START, ControlSource.SCREEN_CONTROL)

        val running = rig.state.calibration as CalibrationUi.Running
        assertEquals(mapOf(PlatformEvent.PLAY to 2), running.summary.eventCounts)
        assertEquals(1, running.summary.unattributedEventCount)
        repeat(4) { rig.viewModel.nextStep() }
        assertEquals(1, (rig.state.calibration as CalibrationUi.Finished).unattributedEventCount)
    }

    @Test
    fun `锁屏状态的变化传给向导，也显示在界面上`() = runUnconfined {
        val rig = rig(CaptureState.ARMED)
        rig.lock.value = null
        rig.viewModel.startCalibration()
        assertNull((rig.state.calibration as CalibrationUi.Running).screenLocked)

        rig.lock.value = true

        assertEquals(true, (rig.state.calibration as CalibrationUi.Running).screenLocked)
    }

    @Test
    fun `放弃校准：临时映射撤掉、向导不再收到事件，回到设置页并提示，没有保存任何档案`() = runUnconfined {
        val saved = ControlProfile.generic().copy(profileId = "android-old", profileVersion = 4)
        val active = ActiveControlProfile(saved)
        val rig = rig(CaptureState.ARMED, active = active)
        rig.viewModel.startCalibration()
        rig.viewModel.nextStep()
        assertEquals("校准期间用的是临时映射", "calibration-toggle-all", active.current.profileId)

        rig.viewModel.abortCalibration()

        assertEquals("以前的档案原样回来", saved, active.current)
        assertEquals(0, rig.events.observerCount)
        assertTrue(rig.state.calibration is CalibrationUi.Setup)
        assertEquals(ControlNotice.CalibrationAborted, rig.state.notice)
        assertTrue(rig.store.saves.isEmpty())
    }

    @Test
    fun `没有任何观察地走完四步：五项都是未测试，没有可保存的结论，保存什么都不做，以前的档案不被顶掉`() = runUnconfined {
        val earlier = ControlProfile.generic().copy(profileId = "android-old", profileVersion = 4)
        val active = ActiveControlProfile(earlier)
        val rig = rig(CaptureState.ARMED, active = active)
        rig.viewModel.setRealDeviceConfirmed(true)
        rig.viewModel.startCalibration()
        repeat(4) { rig.viewModel.nextStep() }

        val finished = rig.state.calibration as CalibrationUi.Finished
        assertEquals(5, finished.lines.size)
        assertTrue(finished.lines.all { it.status == CompatStatus.NOT_TESTED && it.evidenceLevel == null })
        assertFalse(finished.canSave)

        rig.viewModel.saveProfile()

        assertTrue(rig.store.saves.isEmpty())
        assertEquals(earlier, active.current)
    }

    @Test
    fun `证据等级由环境决定：模拟器永远是模拟器，没确认真机就是未确认，只有明确确认才是真机加耳机`() = runUnconfined {
        val emulator = rig(isEmulator = true)
        emulator.viewModel.setRealDeviceConfirmed(true)
        assertEquals("模拟器上勾了“真机”也不算", EvidenceSetting.EMULATOR, (emulator.state.calibration as CalibrationUi.Setup).setting)
        assertFalse((emulator.state.calibration as CalibrationUi.Setup).canConfirmRealDevice)

        val phone = rig(isEmulator = false)
        val setup = phone.state.calibration as CalibrationUi.Setup
        assertEquals(EvidenceSetting.UNCONFIRMED, setup.setting)
        assertTrue(setup.canConfirmRealDevice)

        phone.viewModel.setRealDeviceConfirmed(true)
        assertEquals(EvidenceSetting.REAL_DEVICE_AND_HEADSET, (phone.state.calibration as CalibrationUi.Setup).setting)
        phone.viewModel.setRealDeviceConfirmed(false)
        assertEquals(EvidenceSetting.UNCONFIRMED, (phone.state.calibration as CalibrationUi.Setup).setting)
    }

    @Test
    fun `没有确认真机时，即使观察到手势能停止录音，结论也只是未测试加 E2，不能保存`() = runUnconfined {
        val rig = rig(CaptureState.ARMED, isEmulator = false)
        rig.viewModel.startCalibration()
        rig.stopByGestureStep()
        rig.viewModel.skipStep()
        rig.viewModel.skipStep()

        val finished = rig.state.calibration as CalibrationUi.Finished
        assertTrue(finished.lines.all { it.status == CompatStatus.NOT_TESTED })
        assertTrue("观察到的只算 E2 证据", finished.lines.any { it.evidenceLevel == EvidenceLevel.E2 })
        assertFalse(finished.canSave)
        assertEquals(EvidenceSetting.UNCONFIRMED, finished.setting)
    }

    /** 第一步没动作就完成，进入第二步：用屏幕按钮开始录音，再用耳机手势停止。 */
    private fun Rig.stopByGestureStep() {
        viewModel.nextStep()
        gesture(PlatformEvent.SCREEN_BUTTON, BusinessCommand.START, ControlSource.SCREEN_CONTROL)
        decide(CaptureEvent.START, CaptureState.ROUTING)
        decide(CaptureEvent.ROUTE_VERIFIED, CaptureState.RECORDING)
        gesture(PlatformEvent.TOGGLE, BusinessCommand.TOGGLE)
        decide(CaptureEvent.TOGGLE, CaptureState.FINALIZING)
        decide(CaptureEvent.FINALIZE_COMPLETE, CaptureState.ARMED)
        viewModel.nextStep()
    }

    @Test
    fun `真机加真实耳机、观察到手势能停止：能保存，档案带 E3 证据，当前映射换成档案里的`() = runUnconfined {
        val active = ActiveControlProfile()
        val rig = rig(CaptureState.ARMED, active = active)
        rig.viewModel.setRealDeviceConfirmed(true)
        rig.viewModel.setModelText(" FreeClip 2 ")
        rig.viewModel.startCalibration()
        rig.stopByGestureStep()
        rig.viewModel.skipStep()
        rig.viewModel.skipStep()

        val finished = rig.state.calibration as CalibrationUi.Finished
        assertTrue(finished.canSave)
        assertFalse(finished.saved)
        rig.viewModel.saveProfile()

        val profile = rig.store.saves.single()
        assertEquals("android-freeclip-2", profile.profileId)
        assertEquals("FreeClip 2", profile.modelHint)
        assertEquals(1, profile.profileVersion)
        val stop = profile.capabilities.stopWhileBluetoothMicActive
        assertEquals(CompatStatus.SUPPORTED_FOREGROUND, stop.status)
        assertEquals(EvidenceLevel.E3, stop.evidenceLevel)
        assertTrue(stop.evidenceIds.isNotEmpty())
        assertTrue("跳过的场景保持未测试", profile.capabilities.continueRecordingWhenLocked.isUntested)
        assertEquals("android-freeclip-2", active.current.profileId)
        assertTrue((rig.state.calibration as CalibrationUi.Finished).saved)
        assertEquals(ProfileFact.Calibrated("android-freeclip-2", 1), rig.state.profile)
    }

    @Test
    fun `同一型号再次校准：档案版本加一；换了型号从 1 开始`() = runUnconfined {
        val store = FakeStore()
        suspend fun TestScope.calibrate(model: String): HeadsetProfile {
            val rig = rig(CaptureState.ARMED, store = store)
            rig.viewModel.setRealDeviceConfirmed(true)
            rig.viewModel.setModelText(model)
            rig.viewModel.startCalibration()
            rig.stopByGestureStep()
            rig.viewModel.skipStep()
            rig.viewModel.skipStep()
            rig.viewModel.saveProfile()
            return store.saves.last()
        }

        assertEquals(1, calibrate("FreeClip 2").profileVersion)
        assertEquals(2, calibrate("FreeClip 2").profileVersion)
        assertEquals(1, calibrate("FreeClip").profileVersion)
    }

    @Test
    fun `保存失败：告诉用户没保存上，当前映射不变，结果还在可以再试`() = runUnconfined {
        val active = ActiveControlProfile()
        val store = FakeStore().also { it.failWith = IOException("磁盘满") }
        val rig = rig(CaptureState.ARMED, store = store, active = active)
        rig.viewModel.setRealDeviceConfirmed(true)
        rig.viewModel.startCalibration()
        rig.stopByGestureStep()
        rig.viewModel.skipStep()
        rig.viewModel.skipStep()
        val before = active.current

        rig.viewModel.saveProfile()

        assertEquals(ControlNotice.ProfileSaveFailed, rig.state.notice)
        assertEquals(before, active.current)
        assertFalse((rig.state.calibration as CalibrationUi.Finished).saved)

        store.failWith = null
        rig.viewModel.dismissNotice()
        rig.viewModel.saveProfile()

        assertEquals(1, store.saves.size)
        assertNull(rig.state.notice)
    }

    @Test
    fun `完成后关闭结果页回到设置页，向导不再收到事件`() = runUnconfined {
        val rig = rig(CaptureState.ARMED)
        rig.viewModel.startCalibration()
        repeat(4) { rig.viewModel.nextStep() }

        rig.viewModel.closeCalibration()

        assertTrue(rig.state.calibration is CalibrationUi.Setup)
        assertEquals(0, rig.events.observerCount)
    }

    @Test
    fun `重做与没有反应的声明传给向导；没有反应的声明只在需要的步骤可用`() = runUnconfined {
        val rig = rig(CaptureState.ARMED)
        rig.viewModel.startCalibration()
        assertFalse("第一步只观察，没有“没反应”这一说", (rig.state.calibration as CalibrationUi.Running).canReportNoEffect)

        rig.viewModel.nextStep()
        assertTrue((rig.state.calibration as CalibrationUi.Running).canReportNoEffect)
        rig.viewModel.reportNoEffect()
        assertTrue((rig.state.calibration as CalibrationUi.Running).summary.ownerReportedNoEffect)

        rig.viewModel.repeatStep()
        val running = rig.state.calibration as CalibrationUi.Running
        assertEquals(2, running.attempt)
        assertFalse("重做清掉了这一步的声明", running.summary.ownerReportedNoEffect)
    }

    @Test
    fun `锁屏的变化真的到了向导：第四步里录音撑过锁屏，档案里就有这条结论`() = runUnconfined {
        val rig = rig(CaptureState.ARMED)
        rig.viewModel.setRealDeviceConfirmed(true)
        rig.viewModel.startCalibration()
        repeat(3) { rig.viewModel.skipStep() }
        assertEquals(CalibrationStep.SCREEN_LOCKED, (rig.state.calibration as CalibrationUi.Running).step)
        rig.gesture(PlatformEvent.SCREEN_BUTTON, BusinessCommand.START, ControlSource.SCREEN_CONTROL)
        rig.decide(CaptureEvent.START, CaptureState.ROUTING)
        rig.decide(CaptureEvent.ROUTE_VERIFIED, CaptureState.RECORDING)

        rig.lock.value = true
        rig.lock.value = false
        rig.viewModel.nextStep()
        rig.viewModel.saveProfile()

        val survived = rig.store.saves.single().capabilities.continueRecordingWhenLocked
        assertEquals(CompatStatus.SUPPORTED_RECORDING_IN_BACKGROUND, survived.status)
        assertEquals(EvidenceLevel.E3, survived.evidenceLevel)
    }

    @Test
    fun `锁屏读数变成读不出来：向导不再沿用之前的“锁着”，之后声明手势开始没效果，档案里不写“锁屏时重新开始”不支持`() = runUnconfined {
        val rig = rig(CaptureState.ARMED)
        rig.viewModel.setRealDeviceConfirmed(true)
        rig.viewModel.startCalibration()
        repeat(3) { rig.viewModel.skipStep() }
        // 第四步：录音中锁屏，锁屏时耳机手势停下了录音，封存完成
        rig.gesture(PlatformEvent.SCREEN_BUTTON, BusinessCommand.START, ControlSource.SCREEN_CONTROL)
        rig.decide(CaptureEvent.START, CaptureState.ROUTING)
        rig.decide(CaptureEvent.ROUTE_VERIFIED, CaptureState.RECORDING)
        rig.lock.value = true
        rig.gesture(PlatformEvent.TOGGLE, BusinessCommand.TOGGLE)
        rig.decide(CaptureEvent.TOGGLE, CaptureState.FINALIZING)
        rig.decide(CaptureEvent.FINALIZE_COMPLETE, CaptureState.ARMED)
        rig.lock.value = null // 解锁了，系统这次没读出来
        rig.viewModel.reportNoEffect() // 之后手势再开始，没反应——不知道是不是还在锁屏
        rig.viewModel.nextStep()
        rig.viewModel.saveProfile()

        val capabilities = rig.store.saves.single().capabilities
        assertEquals("锁屏时手势停下了录音，是观察到的", CompatStatus.SUPPORTED_RECORDING_IN_BACKGROUND, capabilities.continueRecordingWhenLocked.status)
        assertEquals(CapabilityRecord.untested, capabilities.restartAfterStopWhenLocked)
    }

    @Test
    fun `声明没效果时，界面把实际的采集状态交给向导：向导所知的状态落后于实际，就不算“开始手势没效果”`() = runUnconfined {
        val rig = rig(CaptureState.ARMED)
        rig.viewModel.setRealDeviceConfirmed(true)
        rig.viewModel.startCalibration()
        rig.viewModel.skipStep()
        // 第二步：屏幕按钮开始录音，耳机手势停止成功
        rig.gesture(PlatformEvent.SCREEN_BUTTON, BusinessCommand.START, ControlSource.SCREEN_CONTROL)
        rig.decide(CaptureEvent.START, CaptureState.ROUTING)
        rig.decide(CaptureEvent.ROUTE_VERIFIED, CaptureState.RECORDING)
        rig.gesture(PlatformEvent.TOGGLE, BusinessCommand.TOGGLE)
        rig.decide(CaptureEvent.TOGGLE, CaptureState.FINALIZING)
        rig.decide(CaptureEvent.FINALIZE_COMPLETE, CaptureState.ARMED)
        rig.viewModel.nextStep()
        // 第三步：用户用屏幕按钮开始了录音（实际状态已是录音中），那个决定还没送到向导
        rig.controls.move(CaptureState.RECORDING)
        rig.viewModel.reportNoEffect() // 随后耳机手势没反应：那是一次停止手势
        rig.viewModel.nextStep()
        rig.viewModel.skipStep()
        rig.viewModel.saveProfile()

        val capabilities = rig.store.saves.single().capabilities
        assertEquals("第二步手势停止是观察到的", CompatStatus.SUPPORTED_FOREGROUND, capabilities.stopWhileBluetoothMicActive.status)
        assertEquals("失败的是停止手势，不能写成开始不支持", CapabilityRecord.untested, capabilities.foregroundStartStop)
    }

    @Test
    fun `声明没效果时，界面也把实际的锁屏状态交给向导：读数刚变、通知还没送到向导，这次声明不采纳`() = runUnconfined {
        fun declaredStop(reading: Boolean?): CalibrationUi.Finished {
            val lagging = LaggingLock(delivered = false)
            val rig = rig(CaptureState.ARMED, lockSource = lagging)
            rig.viewModel.setRealDeviceConfirmed(true)
            rig.viewModel.startCalibration()
            rig.viewModel.skipStep()
            // 第二步：屏幕按钮开始录音，录音中
            rig.gesture(PlatformEvent.SCREEN_BUTTON, BusinessCommand.START, ControlSource.SCREEN_CONTROL)
            rig.decide(CaptureEvent.START, CaptureState.ROUTING)
            rig.decide(CaptureEvent.ROUTE_VERIFIED, CaptureState.RECORDING)
            lagging.current = reading // 锁屏读数刚变，通知还没送到向导
            rig.viewModel.reportNoEffect() // 耳机停止手势没反应
            rig.viewModel.nextStep()
            repeat(2) { rig.viewModel.skipStep() }
            return rig.state.calibration as CalibrationUi.Finished
        }

        assertTrue("对照：读数没变，声明站得住，有可保存的结论", declaredStop(reading = false).canSave)
        assertFalse("读数刚变成读不出来：向导还以为亮着，这次声明不能算数", declaredStop(reading = null).canSave)
        assertFalse("读数刚变成锁着：向导还以为亮着，这次声明不能算数", declaredStop(reading = true).canSave)
    }

    @Test
    fun `中途锁过屏再解锁之后点“重做”：界面把此刻实际的环境交给向导重新建立基准，重试之后的声明站得住`() = runUnconfined {
        val rig = rig(CaptureState.ARMED)
        rig.viewModel.setRealDeviceConfirmed(true)
        rig.viewModel.startCalibration()
        rig.viewModel.skipStep()
        rig.gesture(PlatformEvent.SCREEN_BUTTON, BusinessCommand.START, ControlSource.SCREEN_CONTROL)
        rig.decide(CaptureEvent.START, CaptureState.ROUTING)
        rig.decide(CaptureEvent.ROUTE_VERIFIED, CaptureState.RECORDING)
        rig.lock.value = true // 录音中自动锁屏
        rig.lock.value = false // 解锁
        rig.viewModel.repeatStep() // 点“重做”，再用耳机试一次，没反应
        rig.viewModel.reportNoEffect()
        rig.viewModel.nextStep()
        repeat(2) { rig.viewModel.skipStep() }
        rig.viewModel.saveProfile()

        assertEquals(CompatStatus.UNSUPPORTED, rig.store.saves.single().capabilities.stopWhileBluetoothMicActive.status)
    }

    @Test
    fun `锁屏通知送到向导时，界面把此刻实际的采集状态一起交给向导：快照说录音已结束、决定还没送到，解锁不算“录音撑过了锁屏”`() = runUnconfined {
        fun survived(recordingEndsBeforeUnlock: Boolean): Boolean {
            val rig = rig(CaptureState.ARMED)
            rig.viewModel.setRealDeviceConfirmed(true)
            rig.viewModel.startCalibration()
            repeat(3) { rig.viewModel.skipStep() } // 第四步：锁屏
            rig.gesture(PlatformEvent.SCREEN_BUTTON, BusinessCommand.START, ControlSource.SCREEN_CONTROL)
            rig.decide(CaptureEvent.START, CaptureState.ROUTING)
            rig.decide(CaptureEvent.ROUTE_VERIFIED, CaptureState.RECORDING)
            rig.lock.value = true // 录音中锁屏
            if (recordingEndsBeforeUnlock) rig.controls.move(CaptureState.FINALIZING) // 路由丢失，录音结束了，快照已更新，决定还没送到向导
            rig.lock.value = false // 解锁
            if (recordingEndsBeforeUnlock) {
                rig.controls.decisions.tryEmit(CaptureDecision(CaptureEvent.ROUTE_LOST, Outcome.TRANSITION, CaptureState.RECORDING, CaptureState.FINALIZING, Cause.MACHINE))
            }
            rig.viewModel.nextStep()
            return (rig.state.calibration as CalibrationUi.Finished).canSave
        }

        assertTrue("对照：录音一直在，撑过了锁屏，有可保存的结论", survived(recordingEndsBeforeUnlock = false))
        assertFalse("录音在解锁之前已经结束：不能算撑过了锁屏", survived(recordingEndsBeforeUnlock = true))
    }

    @Test
    fun `校准进行中或已完成时再点开始校准：不理会，不会多出一路旁听，也不会回到第一步`() = runUnconfined {
        val rig = rig(CaptureState.ARMED)
        rig.viewModel.startCalibration()
        rig.viewModel.nextStep()

        rig.viewModel.startCalibration()

        assertEquals(CalibrationStep.GESTURE_WHILE_RECORDING, (rig.state.calibration as CalibrationUi.Running).step)
        assertEquals(1, rig.events.observerCount)

        repeat(3) { rig.viewModel.nextStep() }
        rig.viewModel.startCalibration()

        assertTrue("结果页要先关闭才能重新校准", rig.state.calibration is CalibrationUi.Finished)
        assertEquals(0, rig.events.observerCount)
    }

    @Test
    fun `同一步被连点两下“做完了”或“跳过”：第二下对着已经翻过去的那一步，不理会，不会悄悄多跳一步`() = runUnconfined {
        val rig = rig(CaptureState.ARMED)
        rig.viewModel.startCalibration()
        fun shown() = (rig.state.calibration as CalibrationUi.Running).step

        val first = shown()
        rig.viewModel.nextStep(first)
        rig.viewModel.nextStep(first) // 连点：屏幕上还是旧的第一步，这一下带着旧的步骤
        assertEquals(CalibrationStep.GESTURE_WHILE_RECORDING, shown())

        val second = shown()
        rig.viewModel.skipStep(second)
        rig.viewModel.skipStep(second)
        assertEquals(CalibrationStep.RESTART_AFTER_STOP, shown())

        rig.viewModel.nextStep(shown()) // 带着当前显示的步骤：正常前进
        assertEquals(CalibrationStep.SCREEN_LOCKED, shown())
    }

    @Test
    fun `已经保存的结果再点保存：什么都不做，档案版本不会白白加一`() = runUnconfined {
        val rig = rig(CaptureState.ARMED)
        rig.viewModel.setRealDeviceConfirmed(true)
        rig.viewModel.startCalibration()
        rig.stopByGestureStep()
        rig.viewModel.skipStep()
        rig.viewModel.skipStep()
        rig.viewModel.saveProfile()

        rig.viewModel.saveProfile()

        assertEquals(1, rig.store.saves.size)
        assertEquals(1, rig.store.saves.single().profileVersion)
    }

    @Test
    fun `界面销毁（视图模型被清理）时，校准的临时映射与旁听一并撤掉`() = runUnconfined {
        val saved = ControlProfile.generic().copy(profileId = "android-old", profileVersion = 4)
        val active = ActiveControlProfile(saved)
        val rig = rig(CaptureState.ARMED, active = active)
        rig.viewModel.startCalibration()
        rig.viewModel.nextStep()
        assertEquals("calibration-toggle-all", active.current.profileId)
        val store = ViewModelStore()
        ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = rig.viewModel as T
            },
        )[ControlViewModel::class.java]

        store.clear()

        assertEquals("临时映射不能留下：留着的话耳机的暂停键会变成开始录音", saved, active.current)
        assertEquals(0, rig.events.observerCount)
    }

    @Test
    fun `启动时读出已保存的档案并显示；没有档案就是通用映射`() = runUnconfined {
        assertEquals(ProfileFact.Generic, rig().state.profile)

        val stored = FakeStore(HeadsetProfile.fromJson(storedProfileJson))
        assertEquals(ProfileFact.Calibrated("android-old", 3), rig(store = stored).state.profile)
    }

    private val storedProfileJson = """
        {"schema_version":"1.1","profile_id":"android-old","profile_version":3,"manufacturer_hint":null,"model_hint":null,"firmware_constraint":null,
         "platform":"android","raw_gesture_visible":false,"command_mapping":{"play":"START","pause":"STOP","toggle":"TOGGLE"},"input_policy":"bluetooth_only",
         "capabilities":{"foreground_start_stop":{"status":"NOT_TESTED","evidence_level":null,"evidence_ids":[]},
         "stop_while_bluetooth_mic_active":{"status":"NOT_TESTED","evidence_level":null,"evidence_ids":[]},
         "continue_recording_when_locked":{"status":"NOT_TESTED","evidence_level":null,"evidence_ids":[]},
         "restart_after_stop_when_locked":{"status":"NOT_TESTED","evidence_level":null,"evidence_ids":[]},
         "start_after_process_termination":{"status":"NOT_TESTED","evidence_level":null,"evidence_ids":[]}}}
    """.trimIndent()

    private companion object {
        fun snapshot(state: CaptureState, version: Long = 0) = CaptureSnapshot(state, null, null, null, FrameCounters(), version)
    }
}
