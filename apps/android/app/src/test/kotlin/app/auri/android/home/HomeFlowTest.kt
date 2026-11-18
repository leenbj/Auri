package app.auri.android.home

import app.auri.capture.contract.CaptureState
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    private class Rig(scope: TestScope, headsetConnected: Boolean) {
        val clock = FakeClock(scope.testScheduler)
        val screen = ScreenControlAdapter(clock, SequentialIds(1))
        val capture = FakeCaptureAdapter()
        val store = FakeRecordingStore()
        val transcription = FakeTranscriptionAdapter()
        val library = InMemoryRecordLibrary()
        val pipeline = RecordPipeline(library, transcription, clock, scope.backgroundScope)
        val output = CollectingOutput().also { it.forwardTo = pipeline }
        val coordinator = CaptureCoordinator(
            control = screen,
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
        assertEquals("按开始就开启控制模式并开始准备", CaptureState.ROUTING, rig.state.capture.state)
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
        assertEquals(CaptureState.ARMED, rig.state.capture.state)
    }

    @Test
    fun `准备阶段按停止是取消：没有 Record，没有转录`() = runTest {
        val rig = Rig(this, headsetConnected = true)
        rig.viewModel.start()
        settle()
        assertEquals(CaptureState.ROUTING, rig.state.capture.state)

        rig.viewModel.stop()
        settle()

        assertEquals(CaptureState.ARMED, rig.state.capture.state)
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
}
