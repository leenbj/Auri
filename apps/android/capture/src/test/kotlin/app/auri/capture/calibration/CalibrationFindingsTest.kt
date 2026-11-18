package app.auri.capture.calibration

import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.BusinessCommand.START
import app.auri.capture.contract.BusinessCommand.STOP
import app.auri.capture.contract.BusinessCommand.TOGGLE
import app.auri.capture.contract.CaptureState.ARMED
import app.auri.capture.contract.CaptureState.BLOCKED
import app.auri.capture.contract.CaptureState.FINALIZING
import app.auri.capture.contract.CaptureState.RECORDING
import app.auri.capture.contract.CaptureState.ROUTING
import app.auri.capture.contract.ControlSource
import app.auri.capture.contract.PlatformEvent
import app.auri.capture.control.ControlProfile
import app.auri.capture.coordinator.Cause
import app.auri.capture.machine.CaptureEvent
import app.auri.capture.machine.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校准向导的结论规则：档案里的每一项能力状态，只来自向导亲眼看到的观察，不来自“已连接”，也不来自“没有事件”。
 * 每个测试名里写着这条规则为什么重要：错一条，就会把没测过的耳机行为记成“支持”。
 */
class CalibrationFindingsTest {
    private val real = EvidenceSetting.REAL_DEVICE_AND_HEADSET

    private class Run(setting: EvidenceSetting, val runId: String = "run-1") {
        val wizard = CalibrationWizard(runId, setting).also { it.start() }
        val feed = Feed(wizard)
        fun profile(modelHint: String? = null): HeadsetProfile = wizard.buildProfile("android-headset", 1, modelHint)!!
    }

    // ---- 各场景的“剧本”：每个函数走完一步并点“下一步” ----

    /** 第一步：不录音时按两次手势（只观察）。 */
    private fun Run.idle(kind: PlatformEvent = PlatformEvent.TOGGLE) {
        feed.ignoredGesture(kind)
        feed.ignoredGesture(kind)
        wizard.next()
    }

    /** 第二步：屏幕按钮开始录音，耳机手势停止。 */
    private fun Run.stopByGesture(kind: PlatformEvent = PlatformEvent.TOGGLE, locked: Boolean? = false) {
        feed.gesture(PlatformEvent.SCREEN_BUTTON, START, ROUTING, source = ControlSource.SCREEN_CONTROL)
        feed.other(CaptureEvent.ROUTE_VERIFIED, RECORDING)
        feed.gesture(kind, TOGGLE, FINALIZING, locked)
        feed.other(CaptureEvent.FINALIZE_COMPLETE, ARMED)
        wizard.next()
    }

    /** 第二步：同样的流程，但停止用屏幕按钮（耳机手势没起作用）。 */
    private fun Run.stopByScreen() {
        feed.gesture(PlatformEvent.SCREEN_BUTTON, START, ROUTING, source = ControlSource.SCREEN_CONTROL)
        feed.other(CaptureEvent.ROUTE_VERIFIED, RECORDING)
        feed.gesture(PlatformEvent.SCREEN_BUTTON, STOP, FINALIZING, source = ControlSource.SCREEN_CONTROL)
        feed.other(CaptureEvent.FINALIZE_COMPLETE, ARMED)
        wizard.next()
    }

    /** 第三步：停止之后用耳机手势再开始；[reached] = 录音真的起来了。 */
    private fun Run.startAgain(kind: PlatformEvent = PlatformEvent.TOGGLE, locked: Boolean? = false, reached: Boolean = true) {
        feed.gesture(kind, TOGGLE, ROUTING, locked)
        if (reached) feed.other(CaptureEvent.ROUTE_VERIFIED, RECORDING) else feed.other(CaptureEvent.ROUTE_UNVERIFIED, BLOCKED)
        wizard.next()
    }

    /** 第四步：录音中锁屏，锁屏时手势停止，再手势开始，解锁。 */
    private fun Run.lockScenario() {
        wizard.onScreenLockChanged(true)
        feed.gesture(PlatformEvent.TOGGLE, TOGGLE, FINALIZING, locked = true)
        feed.other(CaptureEvent.FINALIZE_COMPLETE, ARMED, locked = true)
        feed.gesture(PlatformEvent.TOGGLE, TOGGLE, ROUTING, locked = true)
        feed.other(CaptureEvent.ROUTE_VERIFIED, RECORDING, locked = true)
        wizard.onScreenLockChanged(false)
        wizard.next()
    }

    /** 真机上完整做完一轮，每一步都成功。 */
    private fun fullRun(setting: EvidenceSetting): Run = Run(setting).apply {
        idle()
        stopByGesture()
        startAgain()
        lockScenario()
    }

    private fun HeadsetProfile.statuses() = capabilities.all.map { it.status }

    // ---- 没有观察 ----

    @Test
    fun `没有任何观察：每一步都点完成，五项能力也全是未测试，没有证据等级、没有证据标识`() {
        EvidenceSetting.entries.forEach { setting ->
            val run = Run(setting)
            repeat(4) { run.wizard.next() }
            val profile = run.profile()
            assertEquals("$setting", List(5) { CompatStatus.NOT_TESTED }, profile.statuses())
            assertEquals("$setting", List(5) { null }, profile.capabilities.all.map { it.evidenceLevel })
            assertEquals("$setting", List(5) { emptyList<String>() }, profile.capabilities.all.map { it.evidenceIds })
            assertEquals("没有观察就没有映射上的改动", ControlProfile.generic().mapping, profile.commandMapping)
        }
    }

    @Test
    fun `只收到事件、从没成功开始或停止：事件数量不是能力结论`() {
        val run = Run(real)
        run.idle()
        run.feed.ignoredGesture(PlatformEvent.TOGGLE)
        run.wizard.next() // 第二步：收到了事件，但决定是被忽略
        run.wizard.next()
        run.wizard.next()
        assertEquals(List(5) { CompatStatus.NOT_TESTED }, run.profile().statuses())
    }

    // ---- 真机上完整的一轮 ----

    @Test
    fun `真机 + 真实耳机完整做完：四项有结论，E3，带证据标识；进程终止后启动始终未测试`() {
        val profile = fullRun(real).profile()
        val c = profile.capabilities
        assertEquals(CompatStatus.SUPPORTED_FOREGROUND, c.foregroundStartStop.status)
        assertEquals(CompatStatus.SUPPORTED_FOREGROUND, c.stopWhileBluetoothMicActive.status)
        assertEquals(CompatStatus.SUPPORTED_RECORDING_IN_BACKGROUND, c.continueRecordingWhenLocked.status)
        assertEquals(CompatStatus.SUPPORTED_BACKGROUND_RESTART, c.restartAfterStopWhenLocked.status)
        assertEquals("向导的四个场景根本测不到进程被杀后能否启动", CapabilityRecord.untested, c.startAfterProcessTermination)
        listOf(c.foregroundStartStop, c.stopWhileBluetoothMicActive, c.continueRecordingWhenLocked, c.restartAfterStopWhenLocked).forEach {
            assertEquals(EvidenceLevel.E3, it.evidenceLevel)
            assertTrue(it.evidenceIds.isNotEmpty())
        }
        val all = c.all.flatMap { it.evidenceIds }
        assertTrue("证据标识带上这一轮校准的编号", all.all { it.startsWith("calibration:run-1:") })
        assertTrue("证据标识写明取证环境", all.all { it.endsWith(":real-device") })
        assertEquals("每项能力的证据标识互不重复", c.all.map { it.evidenceIds.toSet().size }, c.all.map { it.evidenceIds.size })
        assertEquals("停止的证据同时支撑两项能力，标识相同", c.stopWhileBluetoothMicActive.evidenceIds.single(), c.foregroundStartStop.evidenceIds.first())
    }

    @Test
    fun `同样的观察放在模拟器、未确认、单测环境：一律只是 E2，不能写成兼容结论`() {
        listOf(EvidenceSetting.EMULATOR, EvidenceSetting.UNCONFIRMED, EvidenceSetting.UNIT_TEST).forEach { setting ->
            val profile = fullRun(setting).profile()
            assertEquals("$setting", List(5) { CompatStatus.NOT_TESTED }, profile.statuses())
            val c = profile.capabilities
            listOf(c.foregroundStartStop, c.stopWhileBluetoothMicActive, c.continueRecordingWhenLocked, c.restartAfterStopWhenLocked).forEach {
                assertEquals("$setting：跑过，但只是 E2", EvidenceLevel.E2, it.evidenceLevel)
                assertTrue("证据标识写明环境", it.evidenceIds.all { id -> id.endsWith(":${setting.label}") })
            }
            assertEquals(CapabilityRecord.untested, c.startAfterProcessTermination)
            assertTrue("$setting：没有任何一项是 E3 或 E4", c.all.none { it.evidenceLevel == EvidenceLevel.E3 || it.evidenceLevel == EvidenceLevel.E4 })
        }
    }

    // ---- 前台开始 / 停止 ----

    @Test
    fun `只做了停止：停止能力有结论；前台开始停止还差开始，保持未测试`() {
        val run = Run(real)
        run.idle()
        run.stopByGesture()
        run.wizard.next()
        run.wizard.next()
        val c = run.profile().capabilities
        assertEquals(CompatStatus.SUPPORTED_FOREGROUND, c.stopWhileBluetoothMicActive.status)
        assertEquals(CapabilityRecord.untested, c.foregroundStartStop)
    }

    @Test
    fun `停止之后开始，但录音没有真的起来（路由没确认）：不算开始成功`() {
        val run = Run(real)
        run.idle()
        run.stopByGesture()
        run.startAgain(reached = false)
        run.wizard.next()
        assertEquals(CapabilityRecord.untested, run.profile().capabilities.foregroundStartStop)
    }

    @Test
    fun `第一次开始不算“停止之后再开始”：之前没有封存过一段录音`() {
        val run = Run(real)
        run.idle()
        run.wizard.next() // 第二步没做
        run.startAgain() // 第三步：从未录过音，直接用手势开始
        run.wizard.next()
        assertEquals(CapabilityRecord.untested, run.profile().capabilities.foregroundStartStop)
    }

    @Test
    fun `锁屏状态未知时，前台的观察不算数：未知不等于没锁屏`() {
        val run = Run(real)
        run.idle()
        run.stopByGesture(locked = null)
        run.startAgain(locked = null)
        run.wizard.next()
        assertEquals(List(5) { CompatStatus.NOT_TESTED }, run.profile().statuses())
    }

    @Test
    fun `屏幕上点开始和停止从不算耳机手势：整轮都用屏幕按钮，所有能力保持未测试`() {
        val run = Run(real)
        run.idle()
        run.stopByScreen()
        run.feed.gesture(PlatformEvent.SCREEN_BUTTON, START, ROUTING, source = ControlSource.SCREEN_CONTROL)
        run.feed.other(CaptureEvent.ROUTE_VERIFIED, RECORDING)
        run.wizard.next()
        run.wizard.onScreenLockChanged(true)
        run.feed.gesture(PlatformEvent.SCREEN_BUTTON, STOP, FINALIZING, locked = true, source = ControlSource.SCREEN_CONTROL)
        run.wizard.next()
        assertEquals(List(5) { CompatStatus.NOT_TESTED }, run.profile().statuses())
    }

    // ---- 用户声明“没起作用” ----

    /** 第二步：屏幕按钮开始，耳机手势没有任何反应，用户声明没效果，再用屏幕按钮停止。 */
    private fun Run.stopNoEffect() {
        feed.gesture(PlatformEvent.SCREEN_BUTTON, START, ROUTING, source = ControlSource.SCREEN_CONTROL)
        feed.other(CaptureEvent.ROUTE_VERIFIED, RECORDING)
        wizard.reportNoEffect()
        feed.gesture(PlatformEvent.SCREEN_BUTTON, STOP, FINALIZING, source = ControlSource.SCREEN_CONTROL)
        feed.other(CaptureEvent.FINALIZE_COMPLETE, ARMED)
        wizard.next()
    }

    @Test
    fun `做了手势没有停下来，用户声明没效果：停止是 UNSUPPORTED（E3），开始还没测，不能凭停止下结论`() {
        val run = Run(real)
        run.idle()
        run.stopNoEffect()
        run.wizard.next()
        run.wizard.next()
        val c = run.profile().capabilities
        assertEquals(CompatStatus.UNSUPPORTED, c.stopWhileBluetoothMicActive.status)
        assertEquals(EvidenceLevel.E3, c.stopWhileBluetoothMicActive.evidenceLevel)
        assertTrue(c.stopWhileBluetoothMicActive.evidenceIds.single().contains(":owner-reported-no-effect:"))
        assertEquals(CapabilityRecord.untested, c.foregroundStartStop)
    }

    @Test
    fun `前台开始停止要两项都有结论才下结论：一项支持一项不支持是 PARTIAL，两项都不支持是 UNSUPPORTED，一项没测仍是未测试`() {
        // 停止成功、开始声明没效果 -> PARTIAL
        val partial = Run(real)
        partial.idle()
        partial.stopByGesture()
        partial.wizard.reportNoEffect()
        partial.wizard.next() // 第三步：没有观察，用户声明没效果
        partial.wizard.next()
        assertEquals(CompatStatus.PARTIAL, partial.profile().capabilities.foregroundStartStop.status)
        assertEquals("停止本身是支持的", CompatStatus.SUPPORTED_FOREGROUND, partial.profile().capabilities.stopWhileBluetoothMicActive.status)

        // 两项都声明没效果 -> UNSUPPORTED（第二步在录音中声明停止没效果，第三步在待命时声明开始没效果：声明要对着它所指的那次手势）
        val unsupported = Run(real)
        unsupported.idle()
        unsupported.stopNoEffect()
        unsupported.wizard.reportNoEffect()
        unsupported.wizard.next()
        unsupported.wizard.next()
        assertEquals(CompatStatus.UNSUPPORTED, unsupported.profile().capabilities.foregroundStartStop.status)

        // 一项声明没效果、另一项根本没做 -> 未测试
        val half = Run(real)
        half.idle()
        half.stopNoEffect()
        half.wizard.skip()
        half.wizard.next()
        assertEquals(CapabilityRecord.untested, half.profile().capabilities.foregroundStartStop)
    }

    @Test
    fun `声明没效果，但这一步其实观察到了成功：以观察为准，不写成不支持`() {
        val run = Run(real)
        run.idle()
        run.wizard.reportNoEffect() // 第二步先误点了“没效果”
        run.feed.gesture(PlatformEvent.SCREEN_BUTTON, START, ROUTING, source = ControlSource.SCREEN_CONTROL)
        run.feed.other(CaptureEvent.ROUTE_VERIFIED, RECORDING)
        run.feed.gesture(PlatformEvent.TOGGLE, TOGGLE, FINALIZING)
        run.wizard.next()
        run.wizard.next()
        run.wizard.next()
        assertEquals(CompatStatus.SUPPORTED_FOREGROUND, run.profile().capabilities.stopWhileBluetoothMicActive.status)
    }

    @Test
    fun `跳过的步骤什么都不算：之前声明过没效果也作废`() {
        val run = Run(real)
        run.idle()
        run.wizard.reportNoEffect()
        run.wizard.skip()
        run.wizard.next()
        run.wizard.next()
        assertEquals(List(5) { CompatStatus.NOT_TESTED }, run.profile().statuses())
    }

    @Test
    fun `模拟器上的“没效果”声明也只是 E2，不写成 UNSUPPORTED`() {
        val run = Run(EvidenceSetting.EMULATOR)
        run.idle()
        run.stopNoEffect() // 录音中做了停止手势，没反应，声明没效果（环境对得上，只是取证环境不是真机）
        run.wizard.next()
        run.wizard.next()
        val c = run.profile().capabilities
        assertEquals(CompatStatus.NOT_TESTED, c.stopWhileBluetoothMicActive.status)
        assertEquals(EvidenceLevel.E2, c.stopWhileBluetoothMicActive.evidenceLevel)
    }

    @Test
    fun `重做之后只看最后一次尝试，证据标识带上尝试序号`() {
        // 第一次尝试里观察到了成功，随后重做：第一次的观察作废
        val redo = Run(real)
        redo.idle()
        redo.feed.gesture(PlatformEvent.SCREEN_BUTTON, START, ROUTING, source = ControlSource.SCREEN_CONTROL)
        redo.feed.other(CaptureEvent.ROUTE_VERIFIED, RECORDING)
        redo.feed.gesture(PlatformEvent.TOGGLE, TOGGLE, FINALIZING)
        redo.wizard.repeat()
        redo.wizard.next()
        redo.wizard.next()
        redo.wizard.next()
        assertEquals("重做清掉了第一次的观察", CapabilityRecord.untested, redo.profile().capabilities.stopWhileBluetoothMicActive)

        val again = Run(real)
        again.idle()
        again.wizard.repeat()
        again.stopByGesture()
        again.wizard.next()
        again.wizard.next()
        assertTrue(again.profile().capabilities.stopWhileBluetoothMicActive.evidenceIds.single().contains(":attempt-2:"))
    }

    // ---- 锁屏 ----

    private fun Run.toLockStep(recordingAtLock: Boolean = true) {
        idle()
        stopByGesture()
        startAgain(reached = recordingAtLock)
    }

    @Test
    fun `锁屏时录音还在，耳机手势把它停下：录音能在锁屏时继续`() {
        val run = Run(real)
        run.toLockStep()
        run.wizard.onScreenLockChanged(true)
        run.feed.gesture(PlatformEvent.TOGGLE, TOGGLE, FINALIZING, locked = true)
        run.wizard.next()
        assertEquals(CompatStatus.SUPPORTED_RECORDING_IN_BACKGROUND, run.profile().capabilities.continueRecordingWhenLocked.status)
    }

    @Test
    fun `锁屏时耳机手势没到，但解锁时录音仍在：录音能在锁屏时继续，重新开始仍未测试`() {
        val run = Run(real)
        run.toLockStep()
        run.wizard.onScreenLockChanged(true)
        run.wizard.onScreenLockChanged(false)
        run.wizard.next()
        val c = run.profile().capabilities
        assertEquals(CompatStatus.SUPPORTED_RECORDING_IN_BACKGROUND, c.continueRecordingWhenLocked.status)
        assertEquals(CapabilityRecord.untested, c.restartAfterStopWhenLocked)
    }

    @Test
    fun `锁屏期间录音被系统掐断：不算能继续，之后再开始的录音也不能顶替`() {
        val run = Run(real)
        run.toLockStep()
        run.wizard.onScreenLockChanged(true)
        run.feed.other(CaptureEvent.ROUTE_LOST, FINALIZING, locked = true)
        run.feed.other(CaptureEvent.FINALIZE_COMPLETE, ARMED, locked = true)
        run.feed.gesture(PlatformEvent.TOGGLE, TOGGLE, ROUTING, locked = true)
        run.feed.other(CaptureEvent.ROUTE_VERIFIED, RECORDING, locked = true)
        run.wizard.onScreenLockChanged(false) // 解锁时又是 RECORDING，但那是另一段录音
        run.wizard.next()
        val c = run.profile().capabilities
        assertEquals(CapabilityRecord.untested, c.continueRecordingWhenLocked)
        assertEquals("能在锁屏时用手势重新开始，这一点是观察到的", CompatStatus.SUPPORTED_BACKGROUND_RESTART, c.restartAfterStopWhenLocked.status)
    }

    @Test
    fun `锁屏开始时没在录音：锁屏中才开始的录音撑到解锁，也不算“录音能在锁屏时继续”`() {
        val run = Run(real)
        run.idle()
        run.stopByGesture()
        run.startAgain(reached = false) // 第三步结束时是 BLOCKED，没有录音
        run.wizard.onScreenLockChanged(true)
        run.feed.gesture(PlatformEvent.TOGGLE, TOGGLE, ROUTING, locked = true)
        run.feed.other(CaptureEvent.ROUTE_VERIFIED, RECORDING, locked = true)
        run.wizard.onScreenLockChanged(false) // 解锁时是 RECORDING，但这段录音是锁屏之后才开始的
        run.wizard.next()
        val c = run.profile().capabilities
        assertEquals(CapabilityRecord.untested, c.continueRecordingWhenLocked)
        assertEquals("锁屏中用手势开始是观察到的", CompatStatus.SUPPORTED_BACKGROUND_RESTART, c.restartAfterStopWhenLocked.status)
    }

    @Test
    fun `锁屏时耳机手势到了，但不知道屏幕是否锁着：不算数`() {
        val run = Run(real)
        run.toLockStep()
        run.wizard.onScreenLockChanged(true)
        run.feed.gesture(PlatformEvent.TOGGLE, TOGGLE, FINALIZING, locked = null)
        run.wizard.next()
        assertEquals(CapabilityRecord.untested, run.profile().capabilities.continueRecordingWhenLocked)
    }

    @Test
    fun `锁屏状态下手势开始：必须是封存之后、锁屏中、录音真的起来了`() {
        // 在锁屏中开始，但路由没确认
        val blocked = Run(real)
        blocked.toLockStep(recordingAtLock = false)
        blocked.wizard.onScreenLockChanged(true)
        blocked.feed.gesture(PlatformEvent.TOGGLE, TOGGLE, ROUTING, locked = true)
        blocked.feed.other(CaptureEvent.ROUTE_UNVERIFIED, BLOCKED, locked = true)
        blocked.wizard.next()
        assertEquals(CapabilityRecord.untested, blocked.profile().capabilities.restartAfterStopWhenLocked)

        // 开始发生在解锁状态：那是前台的开始，不是锁屏下的
        val unlocked = Run(real)
        unlocked.toLockStep(recordingAtLock = false)
        unlocked.feed.gesture(PlatformEvent.TOGGLE, TOGGLE, ROUTING, locked = false)
        unlocked.feed.other(CaptureEvent.ROUTE_VERIFIED, RECORDING, locked = false)
        unlocked.wizard.next()
        assertEquals(CapabilityRecord.untested, unlocked.profile().capabilities.restartAfterStopWhenLocked)
    }

    @Test
    fun `锁屏中手势开始，但这一轮从没封存过任何录音：那不是“停止之后再开始”`() {
        val run = Run(real)
        run.idle()
        run.wizard.skip()
        run.wizard.skip()
        run.wizard.onScreenLockChanged(true)
        run.feed.gesture(PlatformEvent.TOGGLE, TOGGLE, ROUTING, locked = true)
        run.feed.other(CaptureEvent.ROUTE_VERIFIED, RECORDING, locked = true)
        run.wizard.onScreenLockChanged(false)
        run.wizard.next()
        assertEquals(CapabilityRecord.untested, run.profile().capabilities.restartAfterStopWhenLocked)
    }

    @Test
    fun `锁屏步骤声明没效果：没有观察到成功的锁屏能力是 UNSUPPORTED，已观察到成功的仍是支持`() {
        val run = Run(real)
        run.toLockStep()
        run.wizard.onScreenLockChanged(true)
        run.feed.gesture(PlatformEvent.TOGGLE, TOGGLE, FINALIZING, locked = true) // 录音在锁屏时被手势停下
        run.feed.other(CaptureEvent.FINALIZE_COMPLETE, ARMED, locked = true)
        run.wizard.reportNoEffect() // 但之后再开始没有反应
        run.wizard.next()
        val c = run.profile().capabilities
        assertEquals(CompatStatus.SUPPORTED_RECORDING_IN_BACKGROUND, c.continueRecordingWhenLocked.status)
        assertEquals(CompatStatus.UNSUPPORTED, c.restartAfterStopWhenLocked.status)
    }

    @Test
    fun `锁屏步骤只声明没效果、没观察到锁屏中手势停掉过录音：声明说的是手势，既说明不了录音撑没撑过锁屏，也说明不了重新开始`() {
        // 屏幕锁着，手势没反应，没解锁就点完成：录音撑没撑过没有观察到，不能写成“不能继续”
        val stillLocked = Run(real)
        stillLocked.toLockStep()
        stillLocked.wizard.onScreenLockChanged(true)
        stillLocked.wizard.reportNoEffect()
        stillLocked.wizard.next()
        val noObservation = stillLocked.profile().capabilities
        assertEquals(CapabilityRecord.untested, noObservation.continueRecordingWhenLocked)
        assertEquals("锁屏中根本没有停下过录音：重新开始没有试过", CapabilityRecord.untested, noObservation.restartAfterStopWhenLocked)

        // 解锁时录音还在：撑过锁屏是观察到的；重新开始仍然没试过，不能因为手势没反应就写成不支持
        val survived = Run(real)
        survived.toLockStep()
        survived.wizard.onScreenLockChanged(true)
        survived.wizard.reportNoEffect()
        survived.wizard.onScreenLockChanged(false)
        survived.wizard.next()
        val observed = survived.profile().capabilities
        assertEquals(CompatStatus.SUPPORTED_RECORDING_IN_BACKGROUND, observed.continueRecordingWhenLocked.status)
        assertEquals(CapabilityRecord.untested, observed.restartAfterStopWhenLocked)
    }

    @Test
    fun `锁屏步骤里手势停止发生在没锁屏的时候：之后锁屏、没反应，也不是对“锁屏时重新开始”的否定`() {
        val run = Run(real)
        run.toLockStep()
        run.feed.gesture(PlatformEvent.TOGGLE, TOGGLE, FINALIZING, locked = false) // 还没锁屏就停了，不是这一步要的流程
        run.feed.other(CaptureEvent.FINALIZE_COMPLETE, ARMED)
        run.wizard.onScreenLockChanged(true)
        run.wizard.reportNoEffect()
        run.wizard.next()
        assertEquals(CapabilityRecord.untested, run.profile().capabilities.restartAfterStopWhenLocked)
    }

    @Test
    fun `锁屏步骤里先声明没效果、重试之后锁屏中停止成功了：声明说的是更早那次尝试，不是对“重新开始”的否定`() {
        val run = Run(real)
        run.toLockStep()
        run.wizard.onScreenLockChanged(true)
        run.wizard.reportNoEffect() // 第一次手势没反应
        run.feed.gesture(PlatformEvent.TOGGLE, TOGGLE, FINALIZING, locked = true) // 又试了一次，这次停下来了
        run.feed.other(CaptureEvent.FINALIZE_COMPLETE, ARMED, locked = true)
        run.wizard.next()
        val c = run.profile().capabilities
        assertEquals("锁屏时手势停下了录音，是观察到的", CompatStatus.SUPPORTED_RECORDING_IN_BACKGROUND, c.continueRecordingWhenLocked.status)
        assertEquals("重新开始没有试过", CapabilityRecord.untested, c.restartAfterStopWhenLocked)
    }

    // ---- 声明说的是哪一次手势：由声明那一刻的环境决定（录音中的手势是想停止，就绪时的是想开始；锁没锁屏） ----

    @Test
    fun `锁屏步骤里手势停止之后，解锁用屏幕按钮重新开始，再声明耳机停止没效果：那是一次停止手势，不是对“锁屏时重新开始”的否定`() {
        val run = Run(real)
        run.toLockStep()
        run.wizard.onScreenLockChanged(true)
        run.feed.gesture(PlatformEvent.TOGGLE, TOGGLE, FINALIZING, locked = true) // 锁屏中手势停下了录音
        run.feed.other(CaptureEvent.FINALIZE_COMPLETE, ARMED, locked = true)
        run.wizard.onScreenLockChanged(false)
        run.feed.gesture(PlatformEvent.SCREEN_BUTTON, START, ROUTING, source = ControlSource.SCREEN_CONTROL) // 解锁后用屏幕按钮重新开始
        run.feed.other(CaptureEvent.ROUTE_VERIFIED, RECORDING)
        run.wizard.onScreenLockChanged(true)
        run.wizard.reportNoEffect() // 录音中，这次做的是停止手势
        run.wizard.next()
        assertEquals(CapabilityRecord.untested, run.profile().capabilities.restartAfterStopWhenLocked)
    }

    @Test
    fun `锁屏步骤里手势停止之后先解锁了，再声明手势开始没效果：那是亮屏时做的手势，不是对“锁屏时重新开始”的否定`() {
        val run = Run(real)
        run.toLockStep()
        run.wizard.onScreenLockChanged(true)
        run.feed.gesture(PlatformEvent.TOGGLE, TOGGLE, FINALIZING, locked = true)
        run.feed.other(CaptureEvent.FINALIZE_COMPLETE, ARMED, locked = true)
        run.wizard.onScreenLockChanged(false) // 解锁了
        run.wizard.reportNoEffect() // 亮屏时手势再开始，没反应
        run.wizard.next()
        assertEquals(CapabilityRecord.untested, run.profile().capabilities.restartAfterStopWhenLocked)
    }

    @Test
    fun `第二步还没开始录音就声明“停止手势没效果”：没有录音就没有什么可停，不写成停止不支持`() {
        val run = Run(real)
        run.idle()
        run.wizard.reportNoEffect() // 就绪状态，不可能是一次停止手势
        run.wizard.next()
        run.wizard.next()
        run.wizard.next()
        assertEquals(CapabilityRecord.untested, run.profile().capabilities.stopWhileBluetoothMicActive)
    }

    @Test
    fun `第二步里屏幕已经锁了，声明停止手势没效果：前台停止要求亮屏，锁屏时的失败不算它`() {
        val run = Run(real)
        run.idle()
        run.feed.gesture(PlatformEvent.SCREEN_BUTTON, START, ROUTING, source = ControlSource.SCREEN_CONTROL)
        run.feed.other(CaptureEvent.ROUTE_VERIFIED, RECORDING)
        run.wizard.onScreenLockChanged(true)
        run.wizard.reportNoEffect()
        run.wizard.next()
        run.wizard.next()
        run.wizard.next()
        assertEquals(CapabilityRecord.untested, run.profile().capabilities.stopWhileBluetoothMicActive)
    }

    @Test
    fun `第三步里录音还在进行就声明“开始手势没效果”：那是一次停止手势，不是对“停止之后再开始”的否定`() {
        val run = Run(real)
        run.idle()
        run.stopByGesture()
        run.feed.gesture(PlatformEvent.SCREEN_BUTTON, START, ROUTING, source = ControlSource.SCREEN_CONTROL)
        run.feed.other(CaptureEvent.ROUTE_VERIFIED, RECORDING)
        run.wizard.reportNoEffect() // 录音中
        run.wizard.next()
        run.wizard.next()
        val c = run.profile().capabilities
        assertEquals("第二步手势停止是观察到的", CompatStatus.SUPPORTED_FOREGROUND, c.stopWhileBluetoothMicActive.status)
        assertEquals("开始没有试过，不能写成 PARTIAL", CapabilityRecord.untested, c.foregroundStartStop)
    }

    @Test
    fun `第三步里屏幕已经锁了，声明开始手势没效果：前台开始要求亮屏，锁屏时的失败不算它`() {
        val run = Run(real)
        run.idle()
        run.stopByGesture()
        run.wizard.onScreenLockChanged(true)
        run.wizard.reportNoEffect()
        run.wizard.next()
        run.wizard.next()
        assertEquals(CapabilityRecord.untested, run.profile().capabilities.foregroundStartStop)
    }

    // ---- 一次手势开始的结局属于它自己 ----

    @Test
    fun `手势开始之后准备没成功，之后用屏幕按钮重试才录上：录上的是屏幕按钮这一次，不能记成手势开始成功`() {
        val run = Run(real)
        run.idle()
        run.stopByGesture()
        // 第三步：手势让录音进入准备，路由没确认（受阻）；用户改点屏幕按钮重试，这次录上了
        run.feed.gesture(PlatformEvent.TOGGLE, TOGGLE, ROUTING)
        run.feed.other(CaptureEvent.ROUTE_UNVERIFIED, BLOCKED)
        run.feed.gesture(PlatformEvent.SCREEN_BUTTON, START, ROUTING, source = ControlSource.SCREEN_CONTROL)
        run.feed.other(CaptureEvent.ROUTE_VERIFIED, RECORDING)
        run.wizard.next()
        run.wizard.next()
        val c = run.profile().capabilities
        assertEquals("第二步手势停止是观察到的", CompatStatus.SUPPORTED_FOREGROUND, c.stopWhileBluetoothMicActive.status)
        assertEquals("手势开始这一次没成，不能拿屏幕按钮的那一次顶替", CapabilityRecord.untested, c.foregroundStartStop)
    }

    @Test
    fun `锁屏中手势开始之后准备被取消，随后的开始也不算：只有紧接着那次准备的结局是录音中才算`() {
        val run = Run(real)
        run.toLockStep(recordingAtLock = false)
        run.wizard.onScreenLockChanged(true)
        run.feed.gesture(PlatformEvent.TOGGLE, TOGGLE, ROUTING, locked = true)
        run.feed.gesture(PlatformEvent.TOGGLE, TOGGLE, ARMED, locked = true) // 又按了一次：取消准备
        run.feed.gesture(PlatformEvent.TOGGLE, TOGGLE, ROUTING, locked = true) // 又开始，但这一次被受阻结束
        run.feed.other(CaptureEvent.ROUTE_UNVERIFIED, BLOCKED, locked = true)
        run.feed.gesture(PlatformEvent.SCREEN_BUTTON, START, ROUTING, locked = true, source = ControlSource.SCREEN_CONTROL)
        run.feed.other(CaptureEvent.ROUTE_VERIFIED, RECORDING, locked = true)
        run.wizard.next()
        assertEquals(CapabilityRecord.untested, run.profile().capabilities.restartAfterStopWhenLocked)
    }

    // ---- 声明说的是哪一次手势：声明那一刻的环境要“对得上、站得住”（复审第 5 轮） ----

    @Test
    fun `第二步里中途锁了屏、锁屏时做的手势没反应、解锁后才声明：那次失败不是亮屏时做的，不写成前台停止不支持`() {
        // 对照：全程亮屏，声明站得住，是 UNSUPPORTED
        val steady = Run(real)
        steady.idle()
        steady.feed.gesture(PlatformEvent.SCREEN_BUTTON, START, ROUTING, source = ControlSource.SCREEN_CONTROL)
        steady.feed.other(CaptureEvent.ROUTE_VERIFIED, RECORDING)
        steady.wizard.reportNoEffect()
        steady.wizard.next()
        steady.wizard.skip()
        steady.wizard.skip()
        assertEquals(CompatStatus.UNSUPPORTED, steady.profile().capabilities.stopWhileBluetoothMicActive.status)

        // 同样的声明，但录音之后锁过屏又解了锁：声明那一刻是“录音中、亮屏”，那次手势却可能是锁屏时做的
        val locked = Run(real)
        locked.idle()
        locked.feed.gesture(PlatformEvent.SCREEN_BUTTON, START, ROUTING, source = ControlSource.SCREEN_CONTROL)
        locked.feed.other(CaptureEvent.ROUTE_VERIFIED, RECORDING)
        locked.wizard.onScreenLockChanged(true)
        locked.wizard.onScreenLockChanged(false)
        locked.wizard.reportNoEffect()
        locked.wizard.next()
        locked.wizard.skip()
        locked.wizard.skip()
        assertEquals(CapabilityRecord.untested, locked.profile().capabilities.stopWhileBluetoothMicActive)
    }

    @Test
    fun `锁屏变化之后又有了新的采集决定：锁屏读数重新对齐，之后的声明站得住`() {
        val run = Run(real)
        run.idle()
        run.feed.gesture(PlatformEvent.SCREEN_BUTTON, START, ROUTING, source = ControlSource.SCREEN_CONTROL)
        run.wizard.onScreenLockChanged(true)
        run.wizard.onScreenLockChanged(false)
        run.feed.other(CaptureEvent.ROUTE_VERIFIED, RECORDING, locked = false) // 决定自带的读数：亮屏
        run.wizard.reportNoEffect()
        run.wizard.next()
        run.wizard.skip()
        run.wizard.skip()
        assertEquals(CompatStatus.UNSUPPORTED, run.profile().capabilities.stopWhileBluetoothMicActive.status)
    }

    @Test
    fun `锁屏步骤里解锁时读不出锁屏状态：之前的“锁着”不再沿用，之后声明手势开始没效果不写成“锁屏时重新开始”不支持`() {
        // 变化通知读不出来
        val change = Run(real)
        change.toLockStep()
        change.wizard.onScreenLockChanged(true)
        change.feed.gesture(PlatformEvent.TOGGLE, TOGGLE, FINALIZING, locked = true) // 锁屏中手势停下了录音
        change.feed.other(CaptureEvent.FINALIZE_COMPLETE, ARMED, locked = true)
        change.wizard.onScreenLockChanged(null) // 解锁了，系统这次没读出来
        change.wizard.reportNoEffect() // 之后手势再开始，没反应——不知道是不是还在锁屏
        change.wizard.next()
        assertEquals(CapabilityRecord.untested, change.profile().capabilities.restartAfterStopWhenLocked)

        // 协调器的决定处理时读不出来
        val decision = Run(real)
        decision.toLockStep()
        decision.wizard.onScreenLockChanged(true)
        decision.feed.gesture(PlatformEvent.TOGGLE, TOGGLE, FINALIZING, locked = true)
        decision.feed.other(CaptureEvent.FINALIZE_COMPLETE, ARMED, locked = null)
        decision.wizard.reportNoEffect()
        decision.wizard.next()
        assertEquals(CapabilityRecord.untested, decision.profile().capabilities.restartAfterStopWhenLocked)
    }

    @Test
    fun `锁屏中手势开始，路由到解锁之后才确认：录音不是在锁屏状态下起来的，不算“锁屏时重新开始”成功`() {
        val run = Run(real)
        run.toLockStep()
        run.wizard.onScreenLockChanged(true)
        run.feed.gesture(PlatformEvent.TOGGLE, TOGGLE, FINALIZING, locked = true)
        run.feed.other(CaptureEvent.FINALIZE_COMPLETE, ARMED, locked = true)
        run.feed.gesture(PlatformEvent.TOGGLE, TOGGLE, ROUTING, locked = true) // 锁屏中手势让录音进入准备
        run.wizard.onScreenLockChanged(false) // 路由确认之前用户解了锁
        run.feed.other(CaptureEvent.ROUTE_VERIFIED, RECORDING, locked = false)
        run.wizard.next()
        assertEquals(CapabilityRecord.untested, run.profile().capabilities.restartAfterStopWhenLocked)
    }

    @Test
    fun `前台手势开始，路由到锁屏之后才确认：录音不是在亮屏状态下起来的，不算“停止之后再开始”成功`() {
        val run = Run(real)
        run.idle()
        run.stopByGesture()
        run.feed.gesture(PlatformEvent.TOGGLE, TOGGLE, ROUTING, locked = false)
        run.wizard.onScreenLockChanged(true)
        run.feed.other(CaptureEvent.ROUTE_VERIFIED, RECORDING, locked = true)
        run.wizard.next()
        run.wizard.skip()
        val c = run.profile().capabilities
        assertEquals("第二步手势停止是观察到的", CompatStatus.SUPPORTED_FOREGROUND, c.stopWhileBluetoothMicActive.status)
        assertEquals(CapabilityRecord.untested, c.foregroundStartStop)
    }

    @Test
    fun `向导所知的采集状态落后于实际：声明时界面给的实际状态对不上，就不算“开始手势没效果”`() {
        // 第三步：用户用屏幕按钮开始了录音，向导还没收到那个决定；这时耳机停止手势没反应、声明
        val stale = Run(real)
        stale.idle()
        stale.stopByGesture() // 向导所知：就绪
        stale.wizard.reportNoEffect(CurrentEnvironment(RECORDING, false))
        stale.wizard.next()
        stale.wizard.skip()
        val c = stale.profile().capabilities
        assertEquals("第二步手势停止是观察到的", CompatStatus.SUPPORTED_FOREGROUND, c.stopWhileBluetoothMicActive.status)
        assertEquals("失败的是停止手势，不能写成开始不支持", CapabilityRecord.untested, c.foregroundStartStop)

        // 对照：实际状态与向导所知一致，声明照常算数（停止支持 + 开始不支持 = 部分）
        val agreed = Run(real)
        agreed.idle()
        agreed.stopByGesture()
        agreed.wizard.reportNoEffect(CurrentEnvironment(ARMED, false))
        agreed.wizard.next()
        agreed.wizard.skip()
        assertEquals(CompatStatus.PARTIAL, agreed.profile().capabilities.foregroundStartStop.status)
    }

    @Test
    fun `停止手势的事件到了、协调器的决定还没送到，用户就声明没反应并翻到下一步：决定晚到，那次手势其实成功了，不写成不支持`() {
        val run = Run(real)
        run.idle()
        run.feed.gesture(PlatformEvent.SCREEN_BUTTON, START, ROUTING, source = ControlSource.SCREEN_CONTROL)
        run.feed.other(CaptureEvent.ROUTE_VERIFIED, RECORDING)
        run.wizard.onControlEvent(run.feed.event(PlatformEvent.TOGGLE, TOGGLE)) // 耳机的停止事件到了向导
        run.wizard.reportNoEffect() // 决定还没送到，用户就点了“没反应”
        run.wizard.next()
        // 决定晚到：属于第二步的那次手势，它其实停下了录音
        run.wizard.onDecision(run.feed.decision(CaptureEvent.TOGGLE, Outcome.TRANSITION, FINALIZING), false)
        run.wizard.skip()
        run.wizard.skip()
        assertEquals(CapabilityRecord.untested, run.profile().capabilities.stopWhileBluetoothMicActive)
    }

    @Test
    fun `重做这一步之后，上一次尝试的事件晚到的决定，不能记到新的尝试头上`() {
        val run = Run(real)
        run.idle()
        run.feed.gesture(PlatformEvent.SCREEN_BUTTON, START, ROUTING, source = ControlSource.SCREEN_CONTROL)
        run.feed.other(CaptureEvent.ROUTE_VERIFIED, RECORDING)
        run.wizard.onControlEvent(run.feed.event(PlatformEvent.TOGGLE, TOGGLE)) // 第一次尝试：事件到了，决定还没送到
        run.wizard.repeat() // 用户重做这一步
        run.wizard.onDecision(run.feed.decision(CaptureEvent.TOGGLE, Outcome.TRANSITION, FINALIZING), false) // 第一次尝试的决定晚到
        run.wizard.next()
        run.wizard.skip()
        run.wizard.skip()
        assertEquals("晚到的决定属于被丢弃的那次尝试，不是新尝试观察到的成功", CapabilityRecord.untested, run.profile().capabilities.stopWhileBluetoothMicActive)
    }

    @Test
    fun `第三步里中途锁了屏、锁屏时做的开始手势没反应、解锁后才声明：那次失败不是亮屏时做的，不写成前台开始不支持`() {
        val run = Run(real)
        run.idle()
        run.stopByGesture() // 向导所知：就绪
        run.wizard.onScreenLockChanged(true) // 锁屏时做了开始手势，没反应
        run.wizard.onScreenLockChanged(false) // 解锁回来
        run.wizard.reportNoEffect()
        run.wizard.next()
        run.wizard.skip()
        val c = run.profile().capabilities
        assertEquals("第二步手势停止是观察到的", CompatStatus.SUPPORTED_FOREGROUND, c.stopWhileBluetoothMicActive.status)
        assertEquals(CapabilityRecord.untested, c.foregroundStartStop)
    }

    @Test
    fun `一步里丢了一个决定，只影响那一步：待配对的事件到步骤边界就清掉，不会让后面各步的声明作废`() {
        val run = Run(real)
        run.idle()
        // 第二步：耳机手势停止成功；之后又有一个耳机事件没等到决定（决定丢了）
        run.feed.gesture(PlatformEvent.SCREEN_BUTTON, START, ROUTING, source = ControlSource.SCREEN_CONTROL)
        run.feed.other(CaptureEvent.ROUTE_VERIFIED, RECORDING)
        run.feed.gesture(PlatformEvent.TOGGLE, TOGGLE, FINALIZING)
        run.feed.other(CaptureEvent.FINALIZE_COMPLETE, ARMED)
        run.wizard.onControlEvent(run.feed.event(PlatformEvent.TOGGLE, TOGGLE))
        run.wizard.next()
        // 第三步：就绪，耳机手势开始没反应，声明
        run.wizard.reportNoEffect()
        run.wizard.next()
        run.wizard.skip()
        assertEquals(
            "第二步停止成功 + 第三步开始声明没效果；第二步丢的那个决定不能连累第三步",
            CompatStatus.PARTIAL,
            run.profile().capabilities.foregroundStartStop.status,
        )
    }

    // ---- 复审第 6 轮：声明与配对要经得起“事件、决定、锁屏通知”三路到达的先后 ----

    /** 第一步做完，第二步里用屏幕按钮开始了录音：向导所知是录音中、亮屏。 */
    private fun Run.recordingInStep2() {
        idle()
        feed.gesture(PlatformEvent.SCREEN_BUTTON, START, ROUTING, source = ControlSource.SCREEN_CONTROL)
        feed.other(CaptureEvent.ROUTE_VERIFIED, RECORDING)
    }

    @Test
    fun `重做之后旧的决定才送到：它属于重做之前的屏幕停止，不能配给重做之后耳机发的停止键，不写成“耳机手势停止了录音”`() {
        val run = Run(real)
        run.recordingInStep2()
        run.wizard.onControlEvent(run.feed.event(PlatformEvent.SCREEN_BUTTON, STOP, ControlSource.SCREEN_CONTROL)) // 用户点了屏幕“停止”，决定还没送到
        run.wizard.repeat() // 然后点了“重做”
        run.wizard.onControlEvent(run.feed.event(PlatformEvent.PAUSE, STOP)) // 重做之后耳机发来明确的停止键（录音其实已经在封存）
        run.wizard.onDecision(run.feed.decision(CaptureEvent.STOP, Outcome.TRANSITION, FINALIZING), false) // 先送到的是屏幕停止的决定
        run.wizard.onDecision(run.feed.decision(CaptureEvent.STOP, Outcome.IGNORED, FINALIZING), false) // 后送到耳机停止的决定：已在封存，被忽略
        run.feed.other(CaptureEvent.FINALIZE_COMPLETE, ARMED)
        run.wizard.next()
        run.wizard.skip()
        run.wizard.skip()
        assertEquals("停下录音的是屏幕按钮，不是耳机", CapabilityRecord.untested, run.profile().capabilities.stopWhileBluetoothMicActive)
    }

    @Test
    fun `事件已经到了、决定还没到时点“没反应”：这次声明不记（那次手势的结局还不知道）；决定随后送到、翻步，也不会补记成“开始不支持”`() {
        val run = Run(real)
        run.idle()
        run.stopByGesture() // 第二步停止成功；向导所知：就绪
        run.wizard.onControlEvent(run.feed.event(PlatformEvent.TOGGLE, TOGGLE)) // 第三步：耳机的开始事件到了向导
        assertFalse("决定还没到，声明说不清指的是哪次手势", run.wizard.reportNoEffect())
        run.wizard.onDecision(run.feed.decision(CaptureEvent.TOGGLE, Outcome.TRANSITION, ROUTING), false) // 决定送到：开始了
        run.wizard.next() // 路由确认已经是下一步的事
        run.wizard.skip()
        val c = run.profile().capabilities
        assertEquals("第二步手势停止是观察到的", CompatStatus.SUPPORTED_FOREGROUND, c.stopWhileBluetoothMicActive.status)
        assertEquals("那次开始手势其实起作用了，只是结局没在这一步里看到", CapabilityRecord.untested, c.foregroundStartStop)
    }

    @Test
    fun `锁屏时做的开始手势没反应、解锁之后，一个不改变状态的无关决定（被拒绝）不能让锁屏读数重新对齐，之后声明也不写成“前台开始不支持”`() {
        val run = Run(real)
        run.idle()
        run.stopByGesture() // 向导所知：就绪、亮屏
        run.wizard.onScreenLockChanged(true) // 锁屏时做了开始手势，没反应
        run.wizard.onScreenLockChanged(false) // 解锁回来
        run.wizard.onDecision(run.feed.decision(CaptureEvent.ROUTE_VERIFIED, Outcome.REJECTED, ARMED, Cause.STALE_SESSION), false) // 旧会话的路由回调被拒绝：就绪 → 就绪
        run.wizard.reportNoEffect()
        run.wizard.next()
        run.wizard.skip()
        assertEquals(CapabilityRecord.untested, run.profile().capabilities.foregroundStartStop)
    }

    @Test
    fun `第四步：锁屏后读数读不出来，耳机手势停了录音，再用屏幕另开一段、读数恢复亮屏：后一段不是撑过了锁屏的那一段`() {
        val run = Run(real)
        run.toLockStep() // 第三步结束时在录音中
        run.wizard.onScreenLockChanged(true)
        run.wizard.onScreenLockChanged(null) // 读不出来
        run.feed.gesture(PlatformEvent.TOGGLE, TOGGLE, FINALIZING, locked = null) // 耳机手势停了录音，这一刻读不出锁屏状态
        run.feed.other(CaptureEvent.FINALIZE_COMPLETE, ARMED, locked = null)
        run.feed.gesture(PlatformEvent.SCREEN_BUTTON, START, ROUTING, locked = null, source = ControlSource.SCREEN_CONTROL) // 用屏幕另开一段
        run.feed.other(CaptureEvent.ROUTE_VERIFIED, RECORDING, locked = null)
        run.wizard.onScreenLockChanged(false) // 读数恢复：亮屏，这一段录音在继续
        run.wizard.next()
        assertEquals(CapabilityRecord.untested, run.profile().capabilities.continueRecordingWhenLocked)
    }

    @Test
    fun `声明那一刻界面读到的锁屏状态与向导所知的对不上（读数刚变、通知还没送到）：不采纳；一致时照常算数`() {
        fun stopRecord(now: CurrentEnvironment): CapabilityRecord {
            val run = Run(real)
            run.recordingInStep2()
            run.wizard.reportNoEffect(now)
            run.wizard.next()
            run.wizard.skip()
            run.wizard.skip()
            return run.profile().capabilities.stopWhileBluetoothMicActive
        }
        assertEquals("对照：界面读到的与向导所知的一致", CompatStatus.UNSUPPORTED, stopRecord(CurrentEnvironment(RECORDING, false)).status)
        assertEquals("界面此刻读到“读不出来”，向导还不知道", CapabilityRecord.untested, stopRecord(CurrentEnvironment(RECORDING, null)))
        assertEquals("界面此刻读到“锁着”，向导以为亮着", CapabilityRecord.untested, stopRecord(CurrentEnvironment(RECORDING, true)))
    }

    @Test
    fun `锁屏通知还没送到，但一个没有状态变化的决定已经带来了新读数：锁屏状态变过，之后的声明不采纳`() {
        val run = Run(real)
        run.recordingInStep2() // 向导所知：录音中、亮屏
        run.wizard.onDecision(run.feed.decision(CaptureEvent.ROUTE_VERIFIED, Outcome.IGNORED, RECORDING, Cause.MACHINE), true) // 这个决定处理时屏幕是锁着的
        run.wizard.reportNoEffect()
        run.wizard.next()
        run.wizard.skip()
        run.wizard.skip()
        assertEquals(CapabilityRecord.untested, run.profile().capabilities.stopWhileBluetoothMicActive)
    }

    @Test
    fun `中途锁过屏再解锁之后点“重做”：重做是新一次尝试的起点，以界面此刻读到的环境重新建立基准，重试之后的声明站得住`() {
        val run = Run(real)
        run.recordingInStep2()
        run.wizard.onScreenLockChanged(true) // 录音中自动锁屏
        run.wizard.onScreenLockChanged(false) // 解锁
        run.wizard.repeat(CurrentEnvironment(RECORDING, false)) // 点“重做”，再用耳机试一次，没反应
        run.wizard.reportNoEffect(CurrentEnvironment(RECORDING, false))
        run.wizard.next()
        run.wizard.skip()
        run.wizard.skip()
        assertEquals(CompatStatus.UNSUPPORTED, run.profile().capabilities.stopWhileBluetoothMicActive.status)
    }

    @Test
    fun `点“重做”时界面读到的环境与向导所知的都不同（决定与锁屏通知还没送到向导）：采集状态、锁屏状态、锁屏是否变过，三样都以界面此刻读到的为准`() {
        val run = Run(real)
        run.idle() // 第二步开始；向导所知：就绪（ARMED），此刻还不知道录音已经开始了
        run.wizard.onScreenLockChanged(null) // 锁屏读数读不出来：向导所知的锁屏状态不可信
        run.wizard.repeat(CurrentEnvironment(RECORDING, false)) // 界面此刻读到：录音中、亮屏
        run.wizard.reportNoEffect(CurrentEnvironment(RECORDING, false)) // 再用耳机试一次，没反应
        run.wizard.next()
        run.wizard.skip()
        run.wizard.skip()
        assertEquals(CompatStatus.UNSUPPORTED, run.profile().capabilities.stopWhileBluetoothMicActive.status)
    }

    @Test
    fun `命令处理时适配器抛了异常（内部错误决定，没有触发器）：那个命令的决定永远不会来，队列要清空，之后的配对不会整体错开一格，保存的映射不会和实际相反`() {
        val run = Run(real)
        run.idle()
        run.wizard.skip() // 第二步不做，进入第三步
        run.wizard.onControlEvent(run.feed.event(PlatformEvent.PLAY, TOGGLE)) // 耳机的播放键到了协调器，处理时适配器抛了异常：它的决定永远不会来
        run.wizard.onDecision(run.feed.decision(null, Outcome.REJECTED, ARMED, Cause.INTERNAL_ERROR), false)
        run.wizard.repeat() // 用户点“重做”
        run.feed.gesture(PlatformEvent.PAUSE, TOGGLE, ROUTING) // 重做之后：暂停键开始了录音
        run.feed.other(CaptureEvent.ROUTE_VERIFIED, RECORDING)
        run.feed.gesture(PlatformEvent.PLAY, TOGGLE, FINALIZING) // 播放键把它停下来
        run.feed.other(CaptureEvent.FINALIZE_COMPLETE, ARMED)
        run.wizard.next()
        run.wizard.skip()
        val mapping = run.profile().commandMapping
        assertEquals("暂停键开始了录音", BusinessCommand.START, mapping.getValue(PlatformEvent.PAUSE))
        assertEquals("播放键停止了录音", BusinessCommand.STOP, mapping.getValue(PlatformEvent.PLAY))
    }

    @Test
    fun `锁屏通知送到时，界面读到的实际采集状态与向导所知的对不上：当作不知道，解锁不算“录音撑过了锁屏”；一致时照常算数`() {
        fun survival(block: Run.() -> Unit): CapabilityRecord {
            val run = Run(real)
            run.toLockStep() // 第四步开始时录音着
            run.block()
            run.wizard.next()
            return run.profile().capabilities.continueRecordingWhenLocked
        }
        assertEquals(
            "对照：界面读到的与向导所知的一致，录音一直在",
            CompatStatus.SUPPORTED_RECORDING_IN_BACKGROUND,
            survival {
                wizard.onScreenLockChanged(true, RECORDING)
                wizard.onScreenLockChanged(false, RECORDING)
            }.status,
        )
        assertEquals(
            "录音已因路由丢失而结束，决定还没送到向导；解锁通知先到，界面读到的已经是封存中",
            CapabilityRecord.untested,
            survival {
                wizard.onScreenLockChanged(true, RECORDING)
                wizard.onScreenLockChanged(false, FINALIZING)
                feed.other(CaptureEvent.ROUTE_LOST, FINALIZING)
            },
        )
        assertEquals(
            "向导已经知道录音结束了，界面读到的快照还没更新（落后）：两个读数对不上，不能当作录音着",
            CapabilityRecord.untested,
            survival {
                feed.other(CaptureEvent.ROUTE_LOST, FINALIZING)
                wizard.onScreenLockChanged(true, RECORDING)
                wizard.onScreenLockChanged(false, RECORDING)
            },
        )
    }

    // ---- 档案本身与命令映射 ----

    @Test
    fun `档案的固定字段：安卓、看不到原始手势、只用蓝牙输入，标识与型号原样带上，能转成合同 JSON 再读回`() {
        val profile = fullRun(real).profile(modelHint = "FreeClip 2")
        assertEquals(ProfilePlatform.ANDROID, profile.platform)
        assertEquals("Android 的媒体会话只给平台事件，看不到双击", false, profile.rawGestureVisible)
        assertEquals("bluetooth_only", profile.inputPolicy)
        assertEquals("android-headset", profile.profileId)
        assertEquals("FreeClip 2", profile.modelHint)
        assertNull("不猜厂商", profile.manufacturerHint)
        assertNull("不猜固件", profile.firmwareConstraint)
        assertEquals(profile, HeadsetProfile.fromJson(profile.toJson()))
    }

    @Test
    fun `没有完成的校准出不了档案`() {
        val run = Run(real)
        assertNull(run.wizard.buildProfile("android-headset", 1, null))
        run.idle()
        assertNull(run.wizard.buildProfile("android-headset", 1, null))
    }

    @Test
    fun `一轮校准的编号只能是短的字母数字串：它会进入证据标识`() {
        listOf("", "a".repeat(81), "含中文", "a b", "a/b", "a:b").forEach {
            try {
                CalibrationWizard(it, real)
                throw AssertionError("编号 '$it' 本应被拒绝")
            } catch (expected: IllegalArgumentException) {
            }
        }
        assertNotNull(CalibrationWizard("cal-2026-11-18.10_15", real))
    }

    @Test
    fun `命令映射只采用亲眼看到的：同一种事件既开始又停止才是切换，只开始或只停止就只映射那一个`() {
        // 单事件耳机：每次手势都是 play，既开始了也停止了（默认 play 只会开始）
        val single = Run(real)
        single.idle(PlatformEvent.PLAY)
        single.stopByGesture(PlatformEvent.PLAY)
        single.startAgain(PlatformEvent.PLAY)
        single.wizard.next()
        assertEquals(BusinessCommand.TOGGLE, single.profile().commandMapping.getValue(PlatformEvent.PLAY))
        assertEquals("没观察到的事件保持默认", BusinessCommand.STOP, single.profile().commandMapping.getValue(PlatformEvent.PAUSE))
        assertEquals(BusinessCommand.TOGGLE, single.profile().commandMapping.getValue(PlatformEvent.TOGGLE))

        // 只观察到 play 停止过录音：play 就是停止（默认是开始），没看到它开始过
        val stopOnly = Run(real)
        stopOnly.idle()
        stopOnly.stopByGesture(PlatformEvent.PLAY)
        stopOnly.wizard.next()
        stopOnly.wizard.next()
        assertEquals(BusinessCommand.STOP, stopOnly.profile().commandMapping.getValue(PlatformEvent.PLAY))

        // 只观察到 pause 开始过录音：pause 就是开始（默认是停止）
        val startOnly = Run(real)
        startOnly.idle()
        startOnly.wizard.next()
        startOnly.startAfterFinalizedRecording(PlatformEvent.PAUSE)
        startOnly.wizard.next()
        assertEquals(BusinessCommand.START, startOnly.profile().commandMapping.getValue(PlatformEvent.PAUSE))
    }

    /** 手工造出“已经封存过一段”之后，用 [kind] 手势开始。 */
    private fun Run.startAfterFinalizedRecording(kind: PlatformEvent) {
        feed.gesture(PlatformEvent.SCREEN_BUTTON, START, ROUTING, source = ControlSource.SCREEN_CONTROL)
        feed.other(CaptureEvent.ROUTE_VERIFIED, RECORDING)
        feed.gesture(PlatformEvent.SCREEN_BUTTON, STOP, FINALIZING, source = ControlSource.SCREEN_CONTROL)
        feed.other(CaptureEvent.FINALIZE_COMPLETE, ARMED)
        startAgain(kind)
    }

    @Test
    fun `下一首、上一首永远是忽略：即使观察里它们引起了停止`() {
        val run = Run(real)
        run.idle(PlatformEvent.NEXT)
        run.stopByGesture(PlatformEvent.NEXT)
        run.wizard.next()
        run.wizard.next()
        val mapping = run.profile().commandMapping
        assertEquals(BusinessCommand.IGNORE, mapping.getValue(PlatformEvent.NEXT))
        assertEquals(BusinessCommand.IGNORE, mapping.getValue(PlatformEvent.PREVIOUS))
    }
}
