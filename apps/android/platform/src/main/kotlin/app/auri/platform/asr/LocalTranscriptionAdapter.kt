package app.auri.platform.asr

import app.auri.asr.LocalTranscriber
import app.auri.asr.audio.PcmAudio
import app.auri.asr.audio.WavReader
import app.auri.capture.adapter.TranscriptionAdapter
import app.auri.capture.adapter.TranscriptionOutcome
import app.auri.capture.adapter.TranscriptionRequest
import app.auri.capture.adapter.TranscriptionResult
import app.auri.capture.contract.AsrCapabilities
import app.auri.capture.contract.AsrFormat
import app.auri.capture.contract.AsrLimits
import app.auri.capture.contract.AsrTaskHandle
import app.auri.capture.contract.AudioEncoding
import app.auri.capture.contract.CancelSupport
import app.auri.capture.contract.ErrorCode
import app.auri.capture.contract.ExecutionLocation
import app.auri.capture.contract.TimestampGranularity
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** :asr 的 [LocalTranscriber] 对外露出的那一点：音频进、:asr 的结果出。缩成接口是为了在 JVM 里用假引擎测这层映射。 */
interface LocalSpeechEngine {
    val capabilities: app.auri.asr.contract.AsrCapabilities

    fun transcribe(audio: PcmAudio): app.auri.asr.TranscriptionResult
}

fun LocalTranscriber.asSpeechEngine(): LocalSpeechEngine = object : LocalSpeechEngine {
    override val capabilities get() = this@asSpeechEngine.capabilities

    override fun transcribe(audio: PcmAudio) = this@asSpeechEngine.transcribe(audio)
}

/**
 * 转录适配器：把封存好的 WAV 交给 :asr 的本地转录，把 :asr 的结果翻译成 :capture 的类型。
 * :asr 的公开接口不动（Ticket 14 的验收依赖它），翻译在这一层做；:asr 与 :capture 各自带一份合同类型是为了让
 * :capture 保持纯 Kotlin、:asr 保持稳定。没有云端回退：引擎不可用就是不可用（等模型 / 失败），不会换别的路。
 * 转录文本是数据，原样往上传，这里不解释、不记录。
 */
class LocalTranscriptionAdapter(
    private val engine: LocalSpeechEngine,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TranscriptionAdapter {
    override suspend fun capabilities(): AsrCapabilities = engine.capabilities.toCapture()

    override suspend fun transcribe(request: TranscriptionRequest): TranscriptionResult = withContext(dispatcher) {
        val capabilities = engine.capabilities.toCapture()
        val windowMs = minOf(capabilities.limits.maxAudioDurationMs ?: 30_000L, 30_000L)
        try {
            if (request.audio.durationMs > windowMs) {
                transcribeWindows(request, capabilities, windowMs)
            } else {
                val audio = WavReader.read(File(request.audio.location))
                val result = engine.transcribe(audio)
                TranscriptionResult(result.job.toCapture(), capabilities, result.outcome.toCapture())
            }
        } catch (e: Exception) {
            // 读不了这份音频（文件丢了、被截断、格式不对）：没有对应的合同错误码，不硬套一个
            if (e !is IOException && e !is app.auri.asr.audio.WavFormatException) throw e
            TranscriptionResult(
                AsrTaskHandle(UUID.randomUUID().toString(), "local-${capabilities.engine}", null, 1),
                capabilities,
                TranscriptionOutcome.Failed(null),
            )
        }
    }

    /** Keeps each engine call and its temporary PCM buffer within the engine's limit. */
    private suspend fun transcribeWindows(request: TranscriptionRequest, capabilities: AsrCapabilities, windowMs: Long): TranscriptionResult {
        val format = request.audio.format
        val frameBytes = format.channels * 2
        val windowBytes = (windowMs * format.sampleRateHz / 1000 * frameBytes).coerceAtLeast(frameBytes.toLong()).toInt()
        var job: AsrTaskHandle? = null
        var language: String? = null
        var confidence: Double? = null
        var count = 0
        var textBytes = 0
        val text = StringBuilder()

        RandomAccessFile(File(request.audio.location), "r").use { file ->
            val prefix = ByteArray(12)
            file.readFully(prefix)
            if (String(prefix, 0, 4, Charsets.US_ASCII) != "RIFF" || String(prefix, 8, 4, Charsets.US_ASCII) != "WAVE") {
                throw app.auri.asr.audio.WavFormatException("不是 RIFF/WAVE 文件")
            }
            var offset = 12L
            var dataLength = -1L
            while (offset + 8 <= file.length()) {
                file.seek(offset)
                val tag = ByteArray(4).also(file::readFully).toString(Charsets.US_ASCII)
                val length = (0 until 4).fold(0L) { value, shift -> value or (file.readUnsignedByte().toLong() shl (shift * 8)) }
                if (tag == "data") {
                    dataLength = length
                    offset += 8
                    break
                }
                offset += 8 + length + (length and 1)
                if (offset > 1_048_576 || offset > file.length()) throw app.auri.asr.audio.WavFormatException("WAV 头不完整或过大")
            }
            if (dataLength < 0 || dataLength % frameBytes != 0L || dataLength != request.audio.sampleCount * 2 || offset + dataLength > file.length()) {
                throw app.auri.asr.audio.WavFormatException("WAV 数据长度不符")
            }
            val header = ByteArray(offset.toInt())
            file.seek(0)
            file.readFully(header)
            var remaining = dataLength
            while (remaining > 0) {
                currentCoroutineContext().ensureActive()
                val size = minOf(remaining, windowBytes.toLong()).toInt()
                val bytes = ByteArray(header.size + size)
                header.copyInto(bytes)
                file.readFully(bytes, header.size, size)
                val audio = WavReader.parse(bytes)
                if (audio.sampleRateHz != format.sampleRateHz || audio.channels != format.channels) {
                    throw app.auri.asr.audio.WavFormatException("WAV 格式与录音元数据不符")
                }
                val result = engine.transcribe(audio)
                if (job == null) job = result.job.toCapture()
                when (val outcome = result.outcome.toCapture()) {
                    is TranscriptionOutcome.Text -> {
                        textBytes += outcome.text.toByteArray(Charsets.UTF_8).size + if (text.isEmpty()) 0 else 1
                        if (textBytes > 1_048_576) return TranscriptionResult(result.job.toCapture(), capabilities, TranscriptionOutcome.Failed(null))
                        if (text.isNotEmpty()) text.append(' ')
                        text.append(outcome.text)
                        if (language == null) language = outcome.language
                        confidence = if (count++ == 0) outcome.confidence else null
                    }
                    TranscriptionOutcome.NoSpeech -> Unit
                    else -> return TranscriptionResult(result.job.toCapture(), capabilities, outcome)
                }
                remaining -= size
            }
        }
        val handle = job ?: AsrTaskHandle(UUID.randomUUID().toString(), "local-${capabilities.engine}", null, 1)
        val outcome = if (text.isEmpty()) TranscriptionOutcome.NoSpeech else TranscriptionOutcome.Text(text.toString(), language ?: "und", confidence)
        return TranscriptionResult(handle, capabilities, outcome)
    }
}

internal fun app.auri.asr.contract.AsrCapabilities.toCapture() = AsrCapabilities(
    executionLocation = ExecutionLocation.valueOf(executionLocation.name),
    engine = engine,
    modelId = modelId,
    modelVersion = modelVersion,
    languages = languages,
    acceptsRecordedFile = acceptsRecordedFile,
    formats = formats.map { AsrFormat(AudioEncoding.valueOf(it.encoding.name), it.sampleRateHz, it.channels) },
    streaming = streaming,
    timestampGranularity = TimestampGranularity.valueOf(timestampGranularity.name),
    limits = AsrLimits(limits.maxAudioDurationMs, limits.maxConcurrentJobs),
    cancelSupport = CancelSupport.valueOf(cancelSupport.name),
)

internal fun app.auri.asr.contract.AsrTaskHandle.toCapture() = AsrTaskHandle(jobId, providerId, providerJobId, configRevision)

internal fun app.auri.asr.contract.ErrorCode.toCapture(): ErrorCode = ErrorCode.valueOf(name)

internal fun app.auri.asr.TranscriptionOutcome.toCapture(): TranscriptionOutcome = when (this) {
    is app.auri.asr.TranscriptionOutcome.Succeeded -> TranscriptionOutcome.Text(content.text, content.language, content.confidence)
    is app.auri.asr.TranscriptionOutcome.NoSpeech -> TranscriptionOutcome.NoSpeech
    is app.auri.asr.TranscriptionOutcome.WaitingModel -> TranscriptionOutcome.WaitingModel(error.code.toCapture())
    // retryable 照引擎说的传出去；没有错误信息（引擎内部异常）就是没声明过可以重试
    is app.auri.asr.TranscriptionOutcome.Failed -> TranscriptionOutcome.Failed(error?.code?.toCapture(), error?.retryable == true)
}
