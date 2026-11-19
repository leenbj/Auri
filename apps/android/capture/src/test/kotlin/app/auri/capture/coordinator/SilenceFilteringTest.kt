package app.auri.capture.coordinator

import app.auri.capture.adapter.AudioFrames
import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.CaptureState
import app.auri.capture.contract.EndReason
import app.auri.capture.contract.TimeRange
import app.auri.capture.feedback.feedbackFor
import app.auri.capture.library.TranscriptionStatus
import app.auri.capture.pipeline.RecordPipeline
import app.auri.capture.testing.CaptureRig
import app.auri.capture.testing.FakeTranscriptionAdapter
import app.auri.capture.testing.InMemoryRecordLibrary
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceFilteringTest {
    @Test
    fun `VAD receives only frames from the verified route epoch`() = runTest {
        val seen = mutableListOf<Short>()
        val rig = CaptureRig(this, vad = VoiceActivityDetector { samples ->
            seen += samples.first()
            true
        })
        rig.coordinator.enableControlMode()
        rig.settle()
        rig.control.press(BusinessCommand.START)
        rig.settle()
        rig.capture.sink().frames(AudioFrames(0, ShortArray(1_600) { 2_000 }))
        rig.settle()
        assertTrue(seen.isEmpty())
        rig.capture.verifiedRoute(epoch = 1)
        rig.settle()
        rig.capture.sink().frames(AudioFrames(2, ShortArray(1_600) { 3_000 }))
        rig.settle()
        assertTrue(seen.isEmpty())
        rig.capture.sink().frames(AudioFrames(1, ShortArray(1_600) { 1_000 }))
        rig.settle()
        assertTrue(seen.isNotEmpty())
        assertTrue(seen.all { it == 1_000.toShort() })
    }

    @Test
    fun `a changed duration setting applies to the next session only`() = runTest {
        var limit = 120_000L
        val rig = CaptureRig(this, durationLimit = { limit })
        rig.coordinator.enableControlMode()
        rig.settle()
        rig.control.press(BusinessCommand.START)
        rig.settle()
        rig.capture.verifiedRoute(epoch = 1)
        rig.settle()
        limit = 180_000L
        rig.advance(120_000)
        assertEquals(CaptureState.ARMED, rig.state)
        assertEquals(120_000L, rig.coordinator.snapshot.value.durationLimitMs)

        rig.control.press(BusinessCommand.START)
        rig.settle()
        rig.capture.verifiedRoute(epoch = 1)
        rig.settle()
        rig.advance(120_000)
        assertEquals(CaptureState.RECORDING, rig.state)
        rig.advance(60_000)
        assertEquals(CaptureState.ARMED, rig.state)
        assertEquals(180_000L, rig.coordinator.snapshot.value.durationLimitMs)
    }

    @Test
    fun `one minute warning precedes automatic limit and no new recording starts`() = runTest {
        val rig = CaptureRig(this, CaptureConfig(maxDurationMs = 120_000))
        rig.coordinator.enableControlMode()
        rig.settle()
        rig.control.press(BusinessCommand.START)
        rig.settle()
        rig.capture.verifiedRoute(epoch = 1)
        rig.settle()

        rig.advance(59_999)
        assertTrue(rig.decisions.none { it.cause.name == "DURATION_WARNING" })
        rig.advance(1)
        val warning = rig.decisions.single { it.cause.name == "DURATION_WARNING" }
        assertEquals("DURATION_WARNING", feedbackFor(warning)?.name)
        assertTrue(rig.coordinator.snapshot.value.durationWarning)
        assertEquals(CaptureState.RECORDING, rig.state)
        rig.advance(59_999)
        assertEquals(CaptureState.RECORDING, rig.state)
        rig.advance(1)
        assertEquals(CaptureState.ARMED, rig.state)
        assertTrue(!rig.coordinator.snapshot.value.durationWarning)
        assertEquals(EndReason.DURATION_LIMIT, rig.output.recordings.single().result.endReason)
        rig.advance(120_000)
        assertEquals(1, rig.capture.requests.size)
    }

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
        assertEquals(result.verifiedRanges, result.chunks.map { TimeRange(it.startMs, it.endMs) })
        assertEquals(48_000, rig.store.written.size)
        assertTrue(rig.store.written.take(3_200).all { it == 0.toShort() })
        assertTrue(rig.store.written.drop(3_200).take(16_000).all { it == 1_000.toShort() })
        result.chunks.forEachIndexed { index, chunk ->
            val bytes = ByteBuffer.allocate(24_000 * 2).order(ByteOrder.LITTLE_ENDIAN)
            rig.store.written.subList(index * 24_000, (index + 1) * 24_000).forEach { bytes.putShort(it) }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
            assertEquals(digest, chunk.sha256)
        }
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
