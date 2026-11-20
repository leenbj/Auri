package app.auri.platform.audio

import app.auri.capture.adapter.CaptureAdapter
import app.auri.capture.adapter.CaptureSink
import app.auri.capture.adapter.InputRequest
import app.auri.capture.adapter.Subscription
import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.CaptureState
import app.auri.capture.contract.EndReason
import app.auri.capture.contract.ErrorCode
import app.auri.capture.contract.PreparedInput
import app.auri.capture.coordinator.CaptureCoordinator
import app.auri.capture.coordinator.CaptureDecision
import app.auri.capture.coordinator.Cause
import app.auri.capture.coordinator.Clock
import app.auri.capture.machine.CaptureEvent
import app.auri.capture.machine.Outcome
import app.auri.capture.testing.CollectingOutput
import app.auri.capture.testing.FakeControlAdapter
import app.auri.capture.testing.FakeRecordingStore
import app.auri.capture.testing.SequentialIds
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ticket 19 评审 H2：读线程发现权限被撤销、通知还没送到协调器的时候，这次采集已经结束、权限已经恢复、新的录音已经开始，
 * 迟到的通知不能把新的录音打断。
 *
 * 真实的适配器 + 真实的协调器；只有系统边缘（音频、环境、存储）是假的。竞态怎么造：[DelayedPermissionAdapter] 把适配器
 * 关于权限的**一切**说法（不管走哪条通道）攒着，由测试决定什么时候递送，等于“读线程已经发现、通知还停在半路”。
 * 不论适配器用哪条通道说话它都攒得住，所以同一个测试在旧实现（无标签的全局回调）下是红的，在带会话标签的实现下才绿。
 */
class PermissionRevocationRaceTest {
    private class FixedClock : Clock {
        override fun monotonicNs() = 123_000_000L
        override fun now(): Instant = Instant.parse("2026-11-18T08:00:00Z")
    }

    private class DelayedPermissionAdapter(private val real: CaptureAdapter) : CaptureAdapter by real {
        var delaying = false
        private val delayed = ArrayDeque<() -> Unit>()

        override suspend fun prepareBluetoothInput(request: InputRequest, sink: CaptureSink): PreparedInput =
            real.prepareBluetoothInput(request, DelayingSink(sink))

        override fun onPermissionRevoked(handler: (ErrorCode) -> Unit): Subscription =
            real.onPermissionRevoked { reason -> hold { handler(reason) } }

        /** 攒着的说法按原来的顺序递送，之后不再攒。 */
        fun deliverDelayed() {
            delaying = false
            while (delayed.isNotEmpty()) delayed.removeFirst()()
        }

        private fun hold(action: () -> Unit) {
            if (delaying) delayed += action else action()
        }

        private inner class DelayingSink(private val inner: CaptureSink) : CaptureSink by inner {
            override fun permissionRevoked(reason: ErrorCode) = hold { inner.permissionRevoked(reason) }
        }
    }

    private fun chunk(value: Int) = ShortArray(1600) { value.toShort() }

    @Test
    fun `a revocation noticed during the first recording and delivered during the second does not end the second`() {
        val clock = FixedClock()
        val audio = FakeAudioSystem()
        val environment = FakeEnvironment()
        val readers = mutableListOf<Runnable>()
        val adapter = DelayedPermissionAdapter(BluetoothCaptureAdapter(audio, environment, clock) { readers += it })
        val control = FakeControlAdapter(clock)
        val store = FakeRecordingStore()
        val output = CollectingOutput()
        val decisions = CopyOnWriteArrayList<CaptureDecision>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val coordinator = CaptureCoordinator(control, adapter, store, output, clock, SequentialIds(), scope = scope, adapterDispatcher = Dispatchers.Unconfined)
            scope.launch { coordinator.decisions.toCollection(decisions) }
            coordinator.enableControlMode()

            // 第一次录音：读线程在读第二批的时候发现麦克风权限被撤销；通知还没送到协调器，读线程随后读失败、自己退出
            control.press(BusinessCommand.START)
            val first = audio.recorders[0]
            first.script += ReadStep(chunk(2_001), HEADSET)
            first.script += ReadStep(chunk(2_002), HEADSET)
            var firstReads = 0
            first.onRead = { if (++firstReads == 2) environment.mic = false }
            adapter.delaying = true
            readers[0].run()
            assertEquals("协调器还不知道权限被撤销了", CaptureState.RECORDING, coordinator.snapshot.value.state)

            // 通知送到之前：用户停止了第一次录音，权限恢复，开始第二次录音
            control.press(BusinessCommand.STOP)
            assertEquals(CaptureState.ARMED, coordinator.snapshot.value.state)
            environment.mic = true
            control.press(BusinessCommand.START)
            assertEquals(CaptureState.RECORDING, coordinator.snapshot.value.state)

            // 第二次录音读到第二批的时候，第一次录音的通知才到；读到第三批的时候用户停止
            val second = audio.recorders[1]
            second.script += ReadStep(chunk(2_003), HEADSET)
            second.script += ReadStep(chunk(2_004), HEADSET)
            second.script += ReadStep(chunk(2_005), HEADSET)
            var secondReads = 0
            var stateAfterLateNotice: CaptureState? = null
            second.onRead = {
                when (++secondReads) {
                    2 -> {
                        adapter.deliverDelayed()
                        stateAfterLateNotice = coordinator.snapshot.value.state
                    }
                    3 -> control.press(BusinessCommand.STOP)
                }
            }
            readers[1].run()

            assertEquals("迟到的旧通知没有打断第二次录音", CaptureState.RECORDING, stateAfterLateNotice)
            assertEquals(
                "第二次录音一直在写：通知之前和之后的两批都落了盘（第三批到的时候用户已经停止，不再递送）",
                List(1600) { 2_003.toShort() } + List(1600) { 2_004.toShort() },
                store.written.takeLast(3200),
            )
            assertEquals(listOf(EndReason.SCREEN_CONTROL, EndReason.SCREEN_CONTROL), output.recordings.map { it.result.endReason })
            assertEquals(
                "旧通知在日志里是被拒绝的过期事件，不是一次状态变化",
                listOf(Cause.STALE_GENERATION),
                decisions.filter { it.trigger == CaptureEvent.PERMISSION_REVOKED && it.outcome == Outcome.REJECTED }.map { it.cause },
            )
            assertEquals(emptyList<CaptureDecision>(), decisions.filter { it.trigger == CaptureEvent.PERMISSION_REVOKED && it.outcome == Outcome.TRANSITION })
        } finally {
            scope.cancel()
        }
    }
}
