package app.auri.platform.audio

import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.CaptureStatus
import app.auri.capture.contract.EndReason
import app.auri.capture.coordinator.CaptureCoordinator
import app.auri.capture.coordinator.Clock
import app.auri.capture.testing.CollectingOutput
import app.auri.capture.testing.FakeControlAdapter
import app.auri.capture.testing.FakeRecordingStore
import app.auri.capture.testing.SequentialIds
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteBoundaryTest {
    @Test
    fun `a buffer spanning an unknown to verified route never reaches storage`() {
        val clock = object : Clock {
            override fun monotonicNs() = 123_000_000L
            override fun now(): Instant = Instant.parse("2026-11-18T08:00:00Z")
        }
        val audio = FakeAudioSystem().apply { initialRouted = null }
        val readers = mutableListOf<Runnable>()
        val adapter = BluetoothCaptureAdapter(audio, FakeEnvironment(), clock) { readers += it }
        val control = FakeControlAdapter(clock)
        val store = FakeRecordingStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val coordinator = CaptureCoordinator(control, adapter, store, CollectingOutput(), clock, SequentialIds(), scope = scope, adapterDispatcher = Dispatchers.Unconfined)
            coordinator.enableControlMode()
            control.press(BusinessCommand.START)
            val recorder = audio.recorders.single()
            recorder.script += ReadStep(ShortArray(1600) { 1_000 }, HEADSET, notify = false)
            recorder.script += ReadStep(ShortArray(1600) { 2_000 }, HEADSET)

            readers.single().run()

            assertEquals(List(1600) { 2_000.toShort() }, store.written)
            assertEquals(1600L, adapter.droppedAmbiguousSamples)
        } finally {
            scope.cancel()
        }
    }

    /**
     * Ticket 19 评审 B1：耳机断开又回来，两条路由通知被占住的主线程攒着，到的时候读到的已经是恢复后的耳机。
     * 旧代码把它们当“没变”吞掉，录音照旧继续、之后的帧照样落盘；现在通知本身就使证据作废，录音按 route_lost 截断。
     */
    @Test
    fun `route notifications delayed past a headset away-and-back end the recording as route_lost and nothing later is written`() {
        val clock = object : Clock {
            override fun monotonicNs() = 123_000_000L
            override fun now(): Instant = Instant.parse("2026-11-18T08:00:00Z")
        }
        val audio = FakeAudioSystem()
        val readers = mutableListOf<Runnable>()
        val adapter = BluetoothCaptureAdapter(audio, FakeEnvironment(), clock) { readers += it }
        val control = FakeControlAdapter(clock)
        val store = FakeRecordingStore()
        val output = CollectingOutput()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val coordinator = CaptureCoordinator(control, adapter, store, output, clock, SequentialIds(), scope = scope, adapterDispatcher = Dispatchers.Unconfined)
            coordinator.enableControlMode()
            control.press(BusinessCommand.START)
            val recorder = audio.recorders.single()
            var reads = 0
            recorder.onRead = {
                reads += 1
                if (reads == 2) {
                    recorder.holdCallbacks = true
                    recorder.systemChangesRoute(PHONE_MIC)
                    recorder.systemChangesRoute(HEADSET)
                    recorder.deliverHeldCallbacks()
                }
            }
            repeat(3) { recorder.script += ReadStep(ShortArray(1600) { 2_000 }, HEADSET, notify = false) }

            readers.single().run()

            assertEquals("只有通知到达之前读到的那一批落了盘", List(1600) { 2_000.toShort() }, store.written)
            val result = output.recordings.single().result
            assertEquals(EndReason.ROUTE_LOST, result.endReason)
            assertEquals(CaptureStatus.PARTIAL, result.status)
        } finally {
            scope.cancel()
        }
    }
}
