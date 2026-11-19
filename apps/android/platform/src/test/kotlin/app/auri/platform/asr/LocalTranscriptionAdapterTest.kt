package app.auri.platform.asr

import app.auri.asr.audio.PcmAudio
import app.auri.asr.contract.AsrError
import app.auri.asr.contract.ReviewState
import app.auri.asr.contract.RevisionContent
import app.auri.asr.contract.RevisionSource
import app.auri.capture.adapter.PcmFormat
import app.auri.capture.adapter.SealedAudio
import app.auri.capture.adapter.TranscriptionOutcome
import app.auri.capture.adapter.TranscriptionRequest
import app.auri.capture.contract.CaptureHandle
import app.auri.capture.contract.ErrorCode
import app.auri.capture.contract.GateState
import app.auri.platform.storage.WavRecordingStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 适配器做三件事：把封存好的 WAV 读出来交给引擎、把 :asr 的四种结果翻译成 :capture 的四种结果、
 * 在引擎根本处理不了时不浪费一次读盘。引擎用假的；真引擎在设备上的行为是 Ticket 14 的验收。
 */
class LocalTranscriptionAdapterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val sha = "a".repeat(64)
    private val jobId = "3c3c3c3c-0000-4000-8000-000000000001"

    private fun asrCapabilities(maxMs: Long? = 30_000) = app.auri.asr.contract.AsrCapabilities(
        executionLocation = app.auri.asr.contract.ExecutionLocation.ON_DEVICE,
        engine = "sherpa-onnx",
        modelId = "model-x",
        modelVersion = "ver-1",
        languages = listOf("zh", "en"),
        acceptsRecordedFile = true,
        formats = listOf(app.auri.asr.contract.AsrFormat(app.auri.asr.contract.AudioEncoding.PCM_S16LE, 16_000, 1)),
        streaming = false,
        timestampGranularity = app.auri.asr.contract.TimestampGranularity.NONE,
        limits = app.auri.asr.contract.AsrLimits(maxMs, 1),
        cancelSupport = app.auri.asr.contract.CancelSupport.NONE,
    )

    private fun asrError(code: app.auri.asr.contract.ErrorCode, retryable: Boolean = false) =
        AsrError("1.1", code, "测试", retryable = retryable, requiredAction = null, operationId = null)

    private fun content(text: String) = RevisionContent(
        text = text,
        textSha256 = sha,
        source = RevisionSource.ASR,
        language = "zh",
        reviewState = ReviewState.NOT_REVIEWED,
        executionLocation = app.auri.asr.contract.ExecutionLocation.ON_DEVICE,
        engine = "sherpa-onnx",
        modelId = "model-x",
        modelVersion = "ver-1",
        confidence = 0.9,
        segments = emptyList(),
    )

    private inner class FakeEngine(
        maxMs: Long? = 30_000,
        var reply: () -> app.auri.asr.TranscriptionOutcome = { app.auri.asr.TranscriptionOutcome.Succeeded(content("你好")) },
    ) : LocalSpeechEngine {
        override val capabilities = asrCapabilities(maxMs)
        val received = mutableListOf<PcmAudio>()

        override fun transcribe(audio: PcmAudio): app.auri.asr.TranscriptionResult {
            received += audio
            val job = app.auri.asr.contract.AsrTaskHandle(jobId, "local-sherpa-onnx", null, 1)
            val metrics = app.auri.asr.TranscriptionMetrics(audio.durationMs, 0, 0, 0, 0, null)
            return app.auri.asr.TranscriptionResult(job, reply(), emptyList(), metrics)
        }
    }

    private val recordId = "0a0a0a0a-0000-4000-8000-000000000001"
    private val handle = CaptureHandle("0b0b0b0b-0000-4000-8000-000000000001", 1, 1, GateState.OPEN)

    /** 经真的 [WavRecordingStore] 封存一段音频，和产品里录音落盘的路径一致。 */
    private fun sealed(samples: ShortArray): SealedAudio = runBlocking {
        val writer = WavRecordingStore(File(folder.root, "records")).begin(recordId, handle, PcmFormat(16_000))
        writer.append(handle, samples)
        writer.seal()
    }

    private fun request(audio: SealedAudio) = TranscriptionRequest(recordId, audio)

    @Test
    fun `读的是封存的那个文件：引擎收到的就是录下的采样，采样率与声道一致`() = runBlocking {
        val samples = ShortArray(1600) { (it * 3 - 700).toShort() }
        val engine = FakeEngine()

        LocalTranscriptionAdapter(engine).transcribe(request(sealed(samples)))

        val received = engine.received.single()
        assertEquals(16_000, received.sampleRateHz)
        assertEquals(1, received.channels)
        assertArrayEquals(samples, received.samples)
    }

    @Test
    fun `有文字：原样带出文字、语言、置信度，与引擎声明的模型和任务句柄一起返回`() = runBlocking {
        val result = LocalTranscriptionAdapter(FakeEngine()).transcribe(request(sealed(ShortArray(1600))))

        val text = result.outcome as TranscriptionOutcome.Text
        assertEquals("你好", text.text)
        assertEquals("zh", text.language)
        assertEquals(0.9, text.confidence!!, 0.0)
        assertEquals("model-x", result.capabilities.modelId)
        assertEquals("ver-1", result.capabilities.modelVersion)
        assertEquals(jobId, result.job.jobId)
        assertNull("本地转录没有远端任务标识", result.job.providerJobId)
    }

    @Test
    fun `没有语音：是 NoSpeech，不是空文字`() = runBlocking {
        val engine = FakeEngine(reply = { app.auri.asr.TranscriptionOutcome.NoSpeech(asrError(app.auri.asr.contract.ErrorCode.ASR_NO_SPEECH)) })

        val result = LocalTranscriptionAdapter(engine).transcribe(request(sealed(ShortArray(1600))))

        assertTrue(result.outcome is TranscriptionOutcome.NoSpeech)
    }

    @Test
    fun `模型没装或校验没过：等待模型并带着对应错误码，不会变成失败也不会换别的引擎`() = runBlocking {
        for ((asrCode, code) in listOf(
            app.auri.asr.contract.ErrorCode.MODEL_NOT_INSTALLED to ErrorCode.MODEL_NOT_INSTALLED,
            app.auri.asr.contract.ErrorCode.MODEL_INTEGRITY_FAILED to ErrorCode.MODEL_INTEGRITY_FAILED,
        )) {
            val engine = FakeEngine(reply = { app.auri.asr.TranscriptionOutcome.WaitingModel(asrError(asrCode)) })

            val outcome = LocalTranscriptionAdapter(engine).transcribe(request(sealed(ShortArray(1600)))).outcome

            assertEquals(code, (outcome as TranscriptionOutcome.WaitingModel).code)
            File(folder.root, "records").deleteRecursively()
        }
    }

    @Test
    fun `失败：带得出的错误码就带上，没有对应错误码的（引擎内部异常）就是 null`() = runBlocking {
        val withCode = FakeEngine(reply = {
            app.auri.asr.TranscriptionOutcome.Failed(asrError(app.auri.asr.contract.ErrorCode.ASR_RESOURCE_LIMIT), "IllegalStateException")
        })
        val without = FakeEngine(reply = { app.auri.asr.TranscriptionOutcome.Failed(null, "IllegalStateException") })
        val audio = sealed(ShortArray(1600))

        val a = LocalTranscriptionAdapter(withCode).transcribe(request(audio)).outcome as TranscriptionOutcome.Failed
        val b = LocalTranscriptionAdapter(without).transcribe(request(audio)).outcome as TranscriptionOutcome.Failed

        assertEquals(ErrorCode.ASR_RESOURCE_LIMIT, a.code)
        assertNull(b.code)
    }

    @Test
    fun `失败可不可以重试照引擎说的带出去，调度器据此决定要不要有界重试；没有错误信息的引擎内部异常不重试`() = runBlocking {
        val audio = sealed(ShortArray(1600))
        fun failure(error: AsrError?) = LocalTranscriptionAdapter(
            FakeEngine(reply = { app.auri.asr.TranscriptionOutcome.Failed(error, "IllegalStateException") }),
        ).let { adapter -> runBlocking { adapter.transcribe(request(audio)).outcome as TranscriptionOutcome.Failed } }

        assertTrue(failure(asrError(app.auri.asr.contract.ErrorCode.ASR_RESOURCE_LIMIT, retryable = true)).retryable)
        assertFalse(failure(asrError(app.auri.asr.contract.ErrorCode.ASR_RESOURCE_LIMIT, retryable = false)).retryable)
        assertFalse("引擎内部异常：没有声明过可以重试", failure(null).retryable)
    }

    @Test
    fun `超过引擎单次上限时分段转录并按顺序合并`() = runBlocking {
        var part = 0
        val engine = FakeEngine(maxMs = 1_000, reply = {
            part += 1
            app.auri.asr.TranscriptionOutcome.Succeeded(content("第${part}段"))
        })
        val samples = ShortArray(40_000) { (it % 1_000).toShort() }

        val outcome = LocalTranscriptionAdapter(engine).transcribe(request(sealed(samples))).outcome as TranscriptionOutcome.Text

        assertEquals(listOf(1_000L, 1_000L, 500L), engine.received.map { it.durationMs })
        assertArrayEquals(samples, engine.received.flatMap { it.samples.toList() }.toShortArray())
        assertEquals("第1段 第2段 第3段", outcome.text)
    }

    @Test
    fun `三十一秒录音可由三十秒上限的引擎完整转录`() = runBlocking {
        val engine = FakeEngine(maxMs = 30_000)
        val samples = ShortArray(16_000 * 31) { (it % 1_000).toShort() }

        val result = LocalTranscriptionAdapter(engine).transcribe(request(sealed(samples)))

        assertTrue(result.outcome is TranscriptionOutcome.Text)
        assertEquals(listOf(30_000L, 1_000L), engine.received.map { it.durationMs })
        assertArrayEquals(samples, engine.received.flatMap { it.samples.toList() }.toShortArray())
    }

    @Test
    fun `后一个分段失败时不发布前面分段的部分文字`() = runBlocking {
        var part = 0
        val engine = FakeEngine(maxMs = 1_000, reply = {
            if (part++ == 0) app.auri.asr.TranscriptionOutcome.Succeeded(content("第一段"))
            else app.auri.asr.TranscriptionOutcome.Failed(asrError(app.auri.asr.contract.ErrorCode.ASR_RESOURCE_LIMIT, retryable = true), null)
        })

        val result = LocalTranscriptionAdapter(engine).transcribe(request(sealed(ShortArray(40_000))))

        val failure = result.outcome as TranscriptionOutcome.Failed
        assertEquals(ErrorCode.ASR_RESOURCE_LIMIT, failure.code)
        assertTrue(failure.retryable)
        assertEquals(2, engine.received.size)
    }

    @Test
    fun `长 WAV 被截断时在调用引擎前失败`() = runBlocking {
        val engine = FakeEngine(maxMs = 1_000)
        val audio = sealed(ShortArray(32_000))
        java.io.RandomAccessFile(audio.location, "rw").use { it.setLength(it.length() - 100) }

        val result = LocalTranscriptionAdapter(engine).transcribe(request(audio))

        assertNull((result.outcome as TranscriptionOutcome.Failed).code)
        assertTrue(engine.received.isEmpty())
    }

    @Test
    fun `超过引擎上限但文件不存在时仍然安全失败`() = runBlocking {
        val engine = FakeEngine(maxMs = 30_000)
        val tooLong = SealedAudio("/does/not/exist.wav", sha, 1_000_000, 16_000L * 31, PcmFormat(16_000))

        val outcome = LocalTranscriptionAdapter(engine).transcribe(request(tooLong)).outcome

        assertNull((outcome as TranscriptionOutcome.Failed).code)
        assertTrue(engine.received.isEmpty())
    }

    @Test
    fun `没有时长上限的引擎不做这个预检`() = runBlocking {
        val engine = FakeEngine(maxMs = null)

        val result = LocalTranscriptionAdapter(engine).transcribe(request(sealed(ShortArray(16_000 * 31))))

        assertTrue(result.outcome is TranscriptionOutcome.Text)
    }

    @Test
    fun `音频读不了（文件丢了或不是 WAV）：失败且没有错误码，引擎不会被叫到`() = runBlocking {
        val engine = FakeEngine()
        val audio = sealed(ShortArray(1600))
        val missing = audio.copy(location = File(folder.root, "nope.wav").absolutePath)
        val garbage = File(folder.root, "garbage.wav").apply { writeText("这不是 WAV") }

        val a = LocalTranscriptionAdapter(engine).transcribe(request(missing)).outcome
        val b = LocalTranscriptionAdapter(engine).transcribe(request(audio.copy(location = garbage.absolutePath))).outcome

        assertNull((a as TranscriptionOutcome.Failed).code)
        assertNull((b as TranscriptionOutcome.Failed).code)
        assertTrue(engine.received.isEmpty())
    }

    @Test
    fun `声明的能力来自引擎：位置只可能是本机，没有云端取值可选`() = runBlocking {
        val capabilities = LocalTranscriptionAdapter(FakeEngine()).capabilities()

        assertEquals(app.auri.capture.contract.ExecutionLocation.ON_DEVICE, capabilities.executionLocation)
        assertEquals("model-x", capabilities.modelId)
        assertEquals(30_000L, capabilities.limits.maxAudioDurationMs)
        assertTrue("能转录已录好的文件，不靠重新打开麦克风", capabilities.acceptsRecordedFile)
    }

    @Test
    fun `asr 模块的每一个合同枚举值都映射得过去：它以后新增取值时这里先红，而不是运行时才炸`() {
        app.auri.asr.contract.ErrorCode.entries.forEach { it.toCapture() }
        app.auri.asr.contract.ExecutionLocation.entries.forEach { app.auri.capture.contract.ExecutionLocation.valueOf(it.name) }
        app.auri.asr.contract.AudioEncoding.entries.forEach { app.auri.capture.contract.AudioEncoding.valueOf(it.name) }
        app.auri.asr.contract.TimestampGranularity.entries.forEach { app.auri.capture.contract.TimestampGranularity.valueOf(it.name) }
        app.auri.asr.contract.CancelSupport.entries.forEach { app.auri.capture.contract.CancelSupport.valueOf(it.name) }
    }
}
