package app.auri.platform.audio

import app.auri.capture.contract.BusinessCommand
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
}
