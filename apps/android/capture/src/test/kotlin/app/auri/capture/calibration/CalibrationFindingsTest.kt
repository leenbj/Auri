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
import app.auri.capture.machine.CaptureEvent
import org.junit.Assert.assertEquals
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

        // 两项都声明没效果 -> UNSUPPORTED
        val unsupported = Run(real)
        unsupported.idle()
        unsupported.wizard.reportNoEffect()
        unsupported.wizard.next()
        unsupported.wizard.reportNoEffect()
        unsupported.wizard.next()
        unsupported.wizard.next()
        assertEquals(CompatStatus.UNSUPPORTED, unsupported.profile().capabilities.foregroundStartStop.status)

        // 一项声明没效果、另一项根本没做 -> 未测试
        val half = Run(real)
        half.idle()
        half.wizard.reportNoEffect()
        half.wizard.next()
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
        run.wizard.reportNoEffect()
        run.wizard.next()
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
        run.wizard.reportNoEffect() // 但之后再开始没有反应
        run.wizard.next()
        val c = run.profile().capabilities
        assertEquals(CompatStatus.SUPPORTED_RECORDING_IN_BACKGROUND, c.continueRecordingWhenLocked.status)
        assertEquals(CompatStatus.UNSUPPORTED, c.restartAfterStopWhenLocked.status)
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
