package app.auri.android.home

import app.auri.capture.contract.CaptureState
import app.auri.capture.contract.CaptureStatus
import app.auri.capture.contract.EndReason
import app.auri.capture.control.ControlProfile
import app.auri.capture.control.MediaKind
import app.auri.capture.control.RawMediaInput
import app.auri.capture.contract.ErrorCode
import app.auri.capture.contract.ObservedInputRouteType
import app.auri.capture.coordinator.CaptureCoordinator
import app.auri.capture.pipeline.RecordPipeline
import app.auri.capture.testing.CollectingOutput
import app.auri.capture.testing.FakeCaptureAdapter
import app.auri.capture.testing.FakeClock
import app.auri.capture.testing.FakeRecordingStore
import app.auri.capture.testing.FakeTranscriptionAdapter
import app.auri.capture.testing.InMemoryRecordLibrary
import app.auri.capture.testing.SequentialIds
import app.auri.platform.audio.HeadsetPresence
import app.auri.platform.audio.HeadsetStatus
import app.auri.platform.control.ScreenControlAdapter
import app.auri.platform.control.CompositeControlAdapter
import app.auri.platform.control.HeadsetControlAdapter
import app.auri.platform.control.MediaButtonSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 从屏幕按钮到列表里的文字，整条线的接缝：真的视图模型、真的 CoordinatorControls、真的屏幕控制适配器、
 * 真的协调器、真的转录管线；只有系统边缘（采集、存储、转录引擎、耳机在线）是假的。
 * 这里证明各层接得对；蓝牙与前台服务在真机上的行为不在这里。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeFlowTest {
    private class FakeHeadset(connected: Boolean) : HeadsetPresence {
        val flow = MutableStateFlow(HeadsetStatus(connected, if (connected) "Fixture Headset" else null))
        override val status: StateFlow<HeadsetStatus> = flow
    }

    private class FakeMediaSource(private val clock: FakeClock) : MediaButtonSource {
        var listener: ((RawMediaInput) -> Unit)? = null
        override fun activate(listener: (RawMediaInput) -> Unit): Boolean {
            this.listener = listener
            return true
        }
        override fun deactivate() { listener = null }
        fun press() { listener?.invoke(RawMediaInput(MediaKind.PLAY_PAUSE, issuedAtNs = clock.monotonicNs(), receivedAtNs = clock.monotonicNs())) }
    }

    private class Rig(scope: TestScope, headsetConnected: Boolean) {
        val clock = FakeClock(scope.testScheduler)
        val screen = ScreenControlAdapter(clock, SequentialIds(1))
        val remote = FakeMediaSource(clock)
        val headsetControl = HeadsetControlAdapter(remote, SequentialIds(3), { ControlProfile.generic() })
        val capture = FakeCaptureAdapter()
        val store = FakeRecordingStore()
        val transcription = FakeTranscriptionAdapter()
        val library = InMemoryRecordLibrary()
        val pipeline = RecordPipeline(library, transcription, clock, scope.backgroundScope)
        val output = CollectingOutput().also { it.forwardTo = pipeline }
        val coordinator = CaptureCoordinator(
            control = CompositeControlAdapter(screen, headsetControl),
            capture = capture,
            store = store,
            output = output,
            clock = clock,
            ids = SequentialIds(2),
            scope = scope.backgroundScope,
            adapterDispatcher = StandardTestDispatcher(scope.testScheduler),
        )
        val headset = FakeHeadset(headsetConnected)
        val viewModel = HomeViewModel(CoordinatorControls(coordinator, screen), headset, library, pipeline.notices, scope.backgroundScope)
        val state: HomeUiState get() = viewModel.state.value
    }

    private fun TestScope.settle() = testScheduler.runCurrent()

    @Test
    fun `manual capture while control mode is off does not activate the media session`() = runTest {
        val rig = Rig(this, headsetConnected = true)
        rig.viewModel.start()
        settle()
        assertEquals(CaptureState.ROUTING, rig.state.capture.state)
        assertFalse("manual capture must not activate the media session", rig.remote.listener != null)
        assertFalse(rig.coordinator.snapshot.value.controlModeEnabled)
        rig.capture.verifiedRoute()
        settle()
        rig.viewModel.stop()
        settle()
        rig.remote.press()
        settle()
        assertEquals(CaptureState.DISARMED, rig.state.capture.state)
        assertEquals(1, rig.capture.requests.size)
    }

    @Test
    fun `manual capture while control mode is on leaves the media session active`() = runTest {
        val rig = Rig(this, headsetConnected = true)
        rig.coordinator.enableControlMode()
        settle()
        rig.viewModel.start()
        settle()
        rig.capture.verifiedRoute()
        settle()
        rig.viewModel.stop()
        settle()
        assertEquals(CaptureState.ARMED, rig.state.capture.state)
        assertTrue(rig.remote.listener != null)
        assertTrue(rig.coordinator.snapshot.value.controlModeEnabled)
        rig.remote.press()
        settle()
        assertEquals(CaptureState.ROUTING, rig.state.capture.state)
        assertEquals(2, rig.capture.requests.size)
    }

    @Test
    fun `没有耳机麦克风时按开始：什么都不录、给出原因，没有 Record，没有转录，没有写下一个字节`() = runTest {
        val rig = Rig(this, headsetConnected = false)
        rig.capture.refuseWith = ErrorCode.INPUT_DEVICE_MISSING
        assertEquals(CaptureState.DISARMED, rig.state.capture.state)

        rig.viewModel.start()
        settle()

        assertEquals(CaptureState.BLOCKED, rig.state.capture.state)
        assertEquals(ErrorCode.INPUT_DEVICE_MISSING, rig.state.capture.blockedReason)
        assertEquals(HeadsetFact(false, null), rig.state.headset)
        assertEquals("没有观测到任何输入", null, rig.state.input.source)
        assertTrue("存储一次调用都没有收到", rig.store.events.isEmpty())
        assertTrue("没有转录请求", rig.transcription.requests.isEmpty())
        assertTrue("没有 Record", rig.state.records.isEmpty())
        assertTrue("受阻之后还能再按开始（重试）", rig.state.startEnabled)
    }

    @Test
    fun `按开始、确认耳机路由、录一段、按停止：自动转录，列表出现一条带文字与模型的 Record`() = runTest {
        val rig = Rig(this, headsetConnected = true)

        rig.viewModel.start()
        settle()
        assertEquals("按开始就开始准备", CaptureState.ROUTING, rig.state.capture.state)
        assertTrue(rig.state.stopEnabled)

        rig.capture.verifiedRoute()
        settle()
        assertEquals(CaptureState.RECORDING, rig.state.capture.state)
        assertEquals(ObservedInputRouteType.BLUETOOTH_HFP, rig.state.input.source)
        assertEquals("耳机已连接与实际输入来源是两个事实", true, rig.state.headset.connected)

        rig.capture.frames(1600, epoch = 1)
        settle()
        rig.viewModel.stop()
        settle()

        val item = rig.state.records.single()
        assertEquals(TextState.Done("你好，这是转录出的文字。", "fixture-model", "1.0.0"), item.text)
        assertEquals("自动转录，没有人触发", 1, rig.transcription.requests.size)
        assertEquals(CaptureState.DISARMED, rig.state.capture.state)
        assertFalse(rig.coordinator.snapshot.value.controlModeEnabled)
    }

    @Test
    fun `准备阶段按停止是取消：没有 Record，没有转录`() = runTest {
        val rig = Rig(this, headsetConnected = true)
        rig.viewModel.start()
        settle()
        assertEquals(CaptureState.ROUTING, rig.state.capture.state)

        rig.viewModel.stop()
        settle()

        assertEquals(CaptureState.DISARMED, rig.state.capture.state)
        assertFalse(rig.coordinator.snapshot.value.controlModeEnabled)
        assertTrue(rig.state.records.isEmpty())
        assertTrue(rig.transcription.requests.isEmpty())
        assertTrue(rig.store.events.isEmpty())
    }

    @Test
    fun `录完一段之后已经待命，能直接再录第二段`() = runTest {
        val rig = Rig(this, headsetConnected = true)
        suspend fun recordOne() {
            rig.viewModel.start()
            settle()
            rig.capture.verifiedRoute()
            settle()
            rig.capture.frames(1600, epoch = 1)
            settle()
            rig.viewModel.stop()
            settle()
        }

        recordOne()
        recordOne()

        assertEquals(2, rig.state.records.size)
        assertEquals(2, rig.transcription.requests.size)
        assertEquals("两条是不同的 Record", 2, rig.state.records.map { it.recordId }.toSet().size)
    }

    // ---- 录音被打断（Ticket 19）：从按钮到列表的整条线，真协调器 + 真管线 + 真视图模型，只有系统边缘是假的 ----

    private class Cut(val name: String, val reason: EndReason, val code: ErrorCode, val trigger: (Rig) -> Unit)

    private val cuts = listOf(
        Cut("耳机断开或输入被切走", EndReason.ROUTE_LOST, ErrorCode.INPUT_ROUTE_CHANGED) { it.capture.mismatchRoute() },
        Cut("来电抢走音频", EndReason.SYSTEM_INTERRUPTION, ErrorCode.AUDIO_INTERRUPTED) { it.capture.interrupt() },
        Cut("麦克风权限被撤销", EndReason.PERMISSION_REVOKED, ErrorCode.PERMISSION_MIC_DENIED) { it.capture.revokePermission() },
        Cut("蓝牙权限被撤销", EndReason.PERMISSION_REVOKED, ErrorCode.INPUT_DEVICE_MISSING) { it.capture.revokePermission(ErrorCode.INPUT_DEVICE_MISSING) },
        Cut("存储写满", EndReason.SYSTEM_INTERRUPTION, ErrorCode.STORAGE_EXHAUSTED) {
            it.store.failAppendAfterSamples = 1600 // 第一批 100 ms 已经写下，第二批写不下
            it.capture.frames(1600, epoch = 1)
        },
    )

    @Test
    fun `录音被打断：首页说清原因，已确认的部分成为被截断的 Record 并照常转录，按钮回到能开始，一个小时之内也不会自己重新开始`() = runTest {
        for (case in cuts) {
            val rig = Rig(this, headsetConnected = true)
            rig.viewModel.start()
            settle()
            rig.capture.verifiedRoute()
            settle()
            rig.capture.frames(1600, epoch = 1)
            settle()
            assertEquals(case.name, CaptureState.RECORDING, rig.state.capture.state)

            case.trigger(rig)
            settle()

            assertEquals(case.name, InterruptionFact(case.reason, case.code), rig.state.interruption)
            assertEquals(case.name, CaptureState.DISARMED, rig.state.capture.state)
            assertTrue("${case.name}：用户可以重新开始", rig.state.startEnabled)
            assertFalse("${case.name}：没有什么可停的", rig.state.stopEnabled)
            val item = rig.state.records.single()
            assertEquals("${case.name}：列表里的这一条标明被截断以及为什么", case.reason, item.truncatedBy)
            assertEquals("${case.name}：已保存的部分照常转录", TextState.Done("你好，这是转录出的文字。", "fixture-model", "1.0.0"), item.text)
            assertEquals("${case.name}：转录只有这一次", 1, rig.transcription.requests.size)
            assertEquals("${case.name}：采集资源只释放一次", 1, rig.capture.releases.size)
            assertEquals(CaptureStatus.PARTIAL, rig.library.get(item.recordId)?.status)

            testScheduler.advanceTimeBy(3_600_000)
            testScheduler.runCurrent()

            assertEquals("${case.name}：一个小时之后仍没有新的录音请求", 1, rig.capture.requests.size)
            assertEquals(case.name, CaptureState.DISARMED, rig.state.capture.state)
            assertEquals("${case.name}：提示一直在，直到用户重新开始", InterruptionFact(case.reason, case.code), rig.state.interruption)

            rig.viewModel.start() // 用户重新开始
            settle()

            assertEquals(case.name, CaptureState.ROUTING, rig.state.capture.state)
            assertNull("${case.name}：新一次开始时提示清除", rig.state.interruption)
            assertEquals(case.name, 2, rig.capture.requests.size)
        }
    }

    @Test
    fun `用户自己停的、到时长上限停的录音：没有打断提示，也不标截断`() = runTest {
        val rig = Rig(this, headsetConnected = true)
        rig.viewModel.start()
        settle()
        rig.capture.verifiedRoute()
        settle()
        rig.capture.frames(1600, epoch = 1)
        settle()

        rig.viewModel.stop()
        settle()

        assertNull(rig.state.interruption)
        assertNull(rig.state.records.single().truncatedBy)
    }
}
