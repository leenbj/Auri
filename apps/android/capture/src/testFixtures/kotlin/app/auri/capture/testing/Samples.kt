package app.auri.capture.testing

import app.auri.capture.adapter.PcmFormat
import app.auri.capture.adapter.SealedAudio
import app.auri.capture.contract.AsrCapabilities
import app.auri.capture.contract.AsrFormat
import app.auri.capture.contract.AsrLimits
import app.auri.capture.contract.AsrTaskHandle
import app.auri.capture.contract.AudioChunk
import app.auri.capture.contract.AudioEncoding
import app.auri.capture.contract.CancelSupport
import app.auri.capture.contract.CaptureStatus
import app.auri.capture.contract.EndReason
import app.auri.capture.contract.ExecutionLocation
import app.auri.capture.contract.ObservedInput
import app.auri.capture.contract.ObservedInputRouteType
import app.auri.capture.contract.RecordingResult
import app.auri.capture.contract.RevisionSource
import app.auri.capture.contract.ReviewState
import app.auri.capture.contract.RouteChangeEvent
import app.auri.capture.contract.TimeRange
import app.auri.capture.contract.TimestampGranularity
import app.auri.capture.contract.VerificationState
import app.auri.capture.coordinator.SealedRecording
import app.auri.capture.library.RecordEntry
import app.auri.capture.library.RevisionDraft
import java.time.Instant

/** 测试用的样本数据：确定的标识和时间，不含任何真实录音或个人文字。 */
object Samples {
    val format = PcmFormat(16_000)
    val start: Instant = Instant.parse("2026-11-18T08:00:00Z")

    fun uuid(n: Int) = "%08x-0000-4000-8000-%012x".format(0xABC, n)

    fun audio(recordId: String, samples: Long = 16_000) =
        SealedAudio("fake://$recordId.wav", "%064x".format(samples), 44 + samples * 2, samples, format)

    fun entry(
        n: Int,
        startedAt: Instant = start.plusSeconds(n.toLong()),
        audio: SealedAudio? = audio(uuid(n)),
    ) = RecordEntry(
        recordId = uuid(n),
        captureSessionId = uuid(1000 + n),
        startedAt = startedAt,
        status = CaptureStatus.COMPLETE,
        endReason = EndReason.SCREEN_CONTROL,
        captureDurationMs = audio?.durationMs ?: 0,
        speechDurationMs = audio?.durationMs ?: 0,
        speechSegments = if (audio == null) emptyList() else listOf(TimeRange(0, audio.durationMs)),
        audio = audio,
        routeEvidenceId = uuid(2000 + n),
        inputRouteType = ObservedInputRouteType.BLUETOOTH_HFP,
        headsetDisplayName = "Fixture Headset",
    )

    /** 一个手工拼的封存结果（不经过协调器），用来测 Record 管线。 */
    fun sealed(n: Int, audio: SealedAudio? = audio(uuid(n))): SealedRecording {
        val duration = audio?.durationMs ?: 0
        val result = RecordingResult(
            recordId = uuid(n),
            captureSessionId = uuid(1000 + n),
            status = CaptureStatus.COMPLETE,
            endReason = EndReason.SCREEN_CONTROL,
            captureDurationMs = duration,
            speechDurationMs = duration,
            chunks = if (audio == null) emptyList() else listOf(AudioChunk(uuid(4000 + n), 0, audio.sha256, 1, 0, duration)),
            verifiedRanges = if (audio == null) emptyList() else listOf(TimeRange(0, duration)),
            discardedRanges = emptyList(),
        )
        val evidence = RouteChangeEvent(uuid(1000 + n), 1, ObservedInput(ObservedInputRouteType.BLUETOOTH_HFP, "Fixture Headset"), 0, VerificationState.VERIFIED)
        return SealedRecording(result, evidence, uuid(2000 + n), start.plusSeconds(n.toLong()), audio)
    }

    fun draft(recordId: String, text: String = "你好，这是一段测试文字。", at: Instant = start.plusSeconds(60)) = RevisionDraft(
        recordId = recordId,
        text = text,
        source = RevisionSource.ASR,
        language = "zh",
        reviewState = ReviewState.NOT_REVIEWED,
        executionLocation = ExecutionLocation.ON_DEVICE,
        engine = "fixture-engine",
        modelId = "fixture-model",
        modelVersion = "1.0.0",
        confidence = null,
        createdAt = at,
    )

    val capabilities = AsrCapabilities(
        executionLocation = ExecutionLocation.ON_DEVICE,
        engine = "fixture-engine",
        modelId = "fixture-model",
        modelVersion = "1.0.0",
        languages = listOf("zh", "en"),
        acceptsRecordedFile = true,
        formats = listOf(AsrFormat(AudioEncoding.PCM_S16LE, 16_000, 1)),
        streaming = false,
        timestampGranularity = TimestampGranularity.NONE,
        limits = AsrLimits(maxAudioDurationMs = 30_000, maxConcurrentJobs = 1),
        cancelSupport = CancelSupport.NONE,
    )

    fun job(n: Int) = AsrTaskHandle(jobId = uuid(3000 + n), providerId = "fixture-provider", providerJobId = null, configRevision = 1)
}
