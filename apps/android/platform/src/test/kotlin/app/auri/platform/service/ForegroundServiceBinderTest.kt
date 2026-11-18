package app.auri.platform.service

import app.auri.capture.contract.CaptureState
import app.auri.capture.coordinator.CaptureSnapshot
import app.auri.capture.coordinator.FrameCounters
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 前台服务的生命周期规则：采集在进行（ROUTING / RECORDING / FINALIZING）才开，其余状态必须关，
 * 起不来要报告。服务是否真的是麦克风类型、系统是否允许启动，是 Android 实现与设备上的事（见验收文档）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundServiceBinderTest {
    private class FakeController(var allow: Boolean = true) : ForegroundServiceController {
        val calls = mutableListOf<String>()

        override fun start(): Boolean {
            calls += "start"
            return allow
        }

        override fun stop() {
            calls += "stop"
        }
    }

    private fun snapshot(state: CaptureState, version: Long) = CaptureSnapshot(
        state = state,
        blockedReason = null,
        session = null,
        actualInput = null,
        counters = FrameCounters(),
        version = version,
    )

    private val states = MutableStateFlow(snapshot(CaptureState.DISARMED, 0))
    private val controller = FakeController()
    private val refused = mutableListOf<Unit>()

    private fun TestScope.bound() {
        bindForegroundService(states, controller) { refused += Unit }
        runCurrent()
    }

    private fun TestScope.moveTo(state: CaptureState) {
        states.value = snapshot(state, states.value.version + 1)
        runCurrent()
    }

    @Test
    fun `六个采集状态里只有 ROUTING、RECORDING、FINALIZING 会让服务开着，其余的都不会`() = TestScope(StandardTestDispatcher()).run {
        val active = setOf(CaptureState.ROUTING, CaptureState.RECORDING, CaptureState.FINALIZING)
        bound()
        assertEquals("初始 DISARMED：服务保持关闭", listOf("stop"), controller.calls)

        for (state in CaptureState.entries) {
            moveTo(CaptureState.DISARMED)
            controller.calls.clear()

            moveTo(state)

            assertEquals(
                "$state",
                if (state in active) listOf("start") else emptyList<String>(),
                controller.calls,
            )
        }
    }

    @Test
    fun `活动状态结束就关服务，不活动状态之间切换不重复操作`() = TestScope(StandardTestDispatcher()).run {
        bound()
        moveTo(CaptureState.ARMED)
        moveTo(CaptureState.ROUTING)
        controller.calls.clear()

        moveTo(CaptureState.ARMED)
        moveTo(CaptureState.BLOCKED)
        moveTo(CaptureState.DISARMED)

        assertEquals(listOf("stop"), controller.calls)
    }

    @Test
    fun `活动状态之间切换不重复启动服务`() = TestScope(StandardTestDispatcher()).run {
        bound()
        controller.calls.clear()

        moveTo(CaptureState.ROUTING)
        moveTo(CaptureState.RECORDING)
        moveTo(CaptureState.FINALIZING)

        assertEquals(listOf("start"), controller.calls)
    }

    @Test
    fun `服务起不来要报告，且在状态变回不活动时仍然会关`() = TestScope(StandardTestDispatcher()).run {
        controller.allow = false
        bound()

        moveTo(CaptureState.ROUTING)
        assertEquals(1, refused.size)

        controller.calls.clear()
        moveTo(CaptureState.ARMED)
        assertEquals(listOf("stop"), controller.calls)
    }
}
