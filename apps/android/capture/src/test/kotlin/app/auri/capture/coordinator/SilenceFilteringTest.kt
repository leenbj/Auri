package app.auri.capture.coordinator

import app.auri.capture.adapter.AudioFrames
import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.CaptureState
import app.auri.capture.contract.TimeRange
import app.auri.capture.library.TranscriptionStatus
import app.auri.capture.pipeline.RecordPipeline
import app.auri.capture.testing.CaptureRig
import app.auri.capture.testing.FakeTranscriptionAdapter
import app.auri.capture.testing.InMemoryRecordLibrary
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceFilteringTest {
    @Test
    fun `a verified 50 millisecond speech frame is stored promptly`() = runTest {
        val rig = CaptureRig(this)
        rig.coordinator.enableControlMode()
        rig.settle()
        rig.control.press(BusinessCommand.START)
        rig.settle()
        rig.capture.verifiedRoute(epoch = 1)
        rig.settle()
        rig.capture.sink().frames(AudioFrames(1, ShortArray(800) { 1_000 }))
        rig.settle()

        assertEquals(800, rig.store.written.size)
    }

    @Test
    fun `a long pause never stops the recording`() = runTest {
        val rig = CaptureRig(this)
        rig.coordinator.enableControlMode()
        rig.settle()
        rig.control.press(BusinessCommand.START)
        rig.settle()
        rig.capture.verifiedRoute(epoch = 1)
        rig.settle()
        rig.capture.sink().frames(AudioFrames(1, ShortArray(16_000) { 1_000 }))
        repeat(90) {
            rig.capture.sink().frames(AudioFrames(1, ShortArray(16_000)))
            rig.settle()
            rig.advance(1_000)
        }
        assertEquals(CaptureState.RECORDING, rig.state)
        assertTrue(rig.output.recordings.isEmpty())
        rig.capture.sink().frames(AudioFrames(1, ShortArray(16_000) { 1_000 }))
        rig.settle()
        rig.control.press(BusinessCommand.STOP)
        rig.settle()
        assertEquals(1, rig.output.recordings.size)
        assertEquals(2, rig.output.recordings.single().result.verifiedRanges.size)
    }

    @Test
    fun `quarantined voice is discarded and verified silence creates no revision or job`() = runTest {
        val rig = CaptureRig(this)
        val library = InMemoryRecordLibrary()
        val transcription = FakeTranscriptionAdapter()
        rig.output.forwardTo = RecordPipeline(library, transcription, rig.clock, backgroundScope)
        rig.coordinator.enableControlMode()
        rig.settle()
        rig.control.press(BusinessCommand.START)
        rig.settle()
        rig.capture.sink().frames(AudioFrames(0, ShortArray(16_000) { 2_000 }))
        rig.settle()
        assertTrue(rig.store.events.isEmpty())
        rig.capture.verifiedRoute(epoch = 1)
        rig.settle()
        rig.capture.sink().frames(AudioFrames(1, ShortArray(16_000)))
        rig.settle()
        rig.control.press(BusinessCommand.STOP)
        rig.settle()

        val sealed = rig.output.recordings.single()
        val record = checkNotNull(library.get(sealed.result.recordId))
        assertNull(sealed.audio)
        assertEquals(TranscriptionStatus.NO_SPEECH, record.transcription)
        assertTrue(record.revisions.isEmpty())
        assertTrue(transcription.requests.isEmpty())
    }

    @Test
    fun `kept speech and padding retain their positions in the original timeline`() = runTest {
        val rig = CaptureRig(this)
        rig.coordinator.enableControlMode()
        rig.settle()
        rig.control.press(BusinessCommand.START)
        rig.settle()
        rig.capture.verifiedRoute(epoch = 1)
        rig.settle()
        val sink = rig.capture.sink()
        sink.frames(AudioFrames(1, ShortArray(16_000)))
        sink.frames(AudioFrames(1, ShortArray(16_000) { 1_000 }))
        sink.frames(AudioFrames(1, ShortArray(32_000)))
        sink.frames(AudioFrames(1, ShortArray(16_000) { 1_000 }))
        sink.frames(AudioFrames(1, ShortArray(16_000)))
        rig.settle()
        rig.control.press(BusinessCommand.STOP)
        rig.settle()

        val result = rig.output.recordings.single().result
        assertEquals(6_000L, result.captureDurationMs)
        assertEquals(3_000L, result.speechDurationMs)
        assertEquals(listOf(TimeRange(800, 2_300), TimeRange(3_800, 5_300)), result.verifiedRanges)
        assertEquals(48_000, rig.store.written.size)
        assertTrue(rig.store.written.take(3_200).all { it == 0.toShort() })
        assertTrue(rig.store.written.drop(3_200).take(16_000).all { it == 1_000.toShort() })
    }

    @Test
    fun `verified silence produces a record without audio`() = runTest {
        val rig = CaptureRig(this)
        rig.coordinator.enableControlMode()
        rig.settle()
        rig.control.press(BusinessCommand.START)
        rig.settle()
        rig.capture.verifiedRoute(epoch = 1)
        rig.settle()
        rig.capture.sink().frames(AudioFrames(1, ShortArray(16_000)))
        rig.settle()
        rig.control.press(BusinessCommand.STOP)
        rig.settle()

        val result = rig.output.recordings.single()
        assertNull(result.audio)
        assertTrue(result.result.verifiedRanges.isEmpty())
        assertTrue(rig.store.written.isEmpty())
    }
}
