package app.auri.asr

import app.auri.asr.audio.PcmAudio
import app.auri.asr.audio.Resampler
import app.auri.asr.contract.AsrCapabilities
import app.auri.asr.contract.AsrError
import app.auri.asr.contract.AsrFormat
import app.auri.asr.contract.AsrLimits
import app.auri.asr.contract.AsrTaskHandle
import app.auri.asr.contract.AudioEncoding
import app.auri.asr.contract.CancelSupport
import app.auri.asr.contract.ContractJson
import app.auri.asr.contract.ErrorCode
import app.auri.asr.contract.ExecutionLocation
import app.auri.asr.contract.ReviewState
import app.auri.asr.contract.RevisionContent
import app.auri.asr.contract.RevisionSource
import app.auri.asr.contract.TimestampGranularity
import app.auri.asr.engine.AsrEngine
import app.auri.asr.engine.AsrEngineFactory
import app.auri.asr.manifest.ModelManifest
import app.auri.asr.manifest.ModelRefusal
import app.auri.asr.manifest.ModelRefusedException
import app.auri.asr.manifest.ModelVerifier
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** 名字与合同 transcription.machine.json 的状态、事件逐字一致；只列出本地转录会用到的那些。 */
enum class TranscriptionState { QUEUED, WAITING_MODEL, RUNNING, SUCCEEDED, NO_SPEECH, FAILED }

enum class TranscriptionEvent { MODEL_MISSING, RUN_STARTED, RESULT_TEXT, RESULT_NO_SPEECH, PERMANENT_ERROR }

data class TraceStep(val event: TranscriptionEvent, val stateAfter: TranscriptionState)

sealed interface TranscriptionOutcome {
    data class Succeeded(val content: RevisionContent) : TranscriptionOutcome

    /** 有效音频里没有语音：保存记录、不制造文本（ASR_NO_SPEECH）。 */
    data class NoSpeech(val error: AsrError) : TranscriptionOutcome

    /**
     * 永久失败。[error] 为 null 表示合同的错误码清单里没有对应的码（例如引擎内部异常），不硬套一个；
     * [causeType] 只记异常类型的简单名，不记消息（消息里可能带口述内容）。
     */
    data class Failed(val error: AsrError?, val causeType: String?) : TranscriptionOutcome

    /** 没有可用的本地模型，等待安装或重新下载；绝不改走云端。 */
    data class WaitingModel(val error: AsrError) : TranscriptionOutcome
}

/**
 * 时间都是毫秒。[rtf] = [inferenceMs] / [audioDurationMs]（NFR-006），不含校验、加载与重采样；
 * 没有跑推理或音频时长为 0 时是 null。冷启动单独报告：[verifyMs] 与 [engineLoadMs] 只在首次调用里非零。
 */
data class TranscriptionMetrics(
    val audioDurationMs: Long,
    val verifyMs: Long,
    val engineLoadMs: Long,
    val resampleMs: Long,
    val inferenceMs: Long,
    val rtf: Double?,
)

data class TranscriptionResult(
    val job: AsrTaskHandle,
    val outcome: TranscriptionOutcome,
    /** 从合同初始状态 QUEUED 出发依次发生的事件与到达的状态。 */
    val steps: List<TraceStep>,
    val metrics: TranscriptionMetrics,
) {
    val finalState: TranscriptionState get() = steps.lastOrNull()?.stateAfter ?: TranscriptionState.QUEUED
}

/**
 * 一台设备上的本地转录：按清单校验模型 → 重采样 → 引擎 → 映射成合同类型。一次只处理一个任务。
 * 模型在第一次转录时才校验和加载（冷启动由此产生），之后复用；校验不过就不创建引擎。
 * 没有任何网络路径：这里和 :asr 模块都不依赖网络，也没有云端回退（ADR-0019）。
 */
class LocalTranscriber(
    private val manifest: ModelManifest,
    private val modelDir: File,
    private val engineFactory: AsrEngineFactory,
    private val nanoTime: () -> Long = System::nanoTime,
    private val newJobId: () -> String = { UUID.randomUUID().toString() },
    private val verifier: ModelVerifier = ModelVerifier(),
) : AutoCloseable {
    private var engine: AsrEngine? = null
    private var closed = false

    val capabilities: AsrCapabilities = AsrCapabilities(
        executionLocation = ExecutionLocation.ON_DEVICE,
        engine = manifest.engine.name,
        modelId = manifest.modelId,
        modelVersion = manifest.modelVersion,
        languages = manifest.languages,
        acceptsRecordedFile = true,
        formats = AsrCapabilities.DECLARED_INPUT_RATES_HZ.flatMap { rate ->
            listOf(1, 2).map { channels -> AsrFormat(AudioEncoding.PCM_S16LE, rate, channels) }
        },
        streaming = false,
        timestampGranularity = TimestampGranularity.NONE,
        limits = AsrLimits(manifest.limits.maxAudioDurationMs, maxConcurrentJobs = 1),
        cancelSupport = CancelSupport.NONE,
    )

    @Synchronized
    fun transcribe(audio: PcmAudio, configRevision: Int = 1): TranscriptionResult {
        check(!closed) { "转录器已关闭" }
        val job = AsrTaskHandle(newJobId(), "local-${manifest.engine.name}", null, configRevision)
        val steps = mutableListOf<TraceStep>()
        fun step(event: TranscriptionEvent, state: TranscriptionState) {
            steps += TraceStep(event, state)
        }
        val audioMs = audio.durationMs
        var verifyMs = 0L
        var loadMs = 0L
        var resampleMs = 0L
        var inferenceMs = 0L
        var inferenceRan = false
        fun result(outcome: TranscriptionOutcome) = TranscriptionResult(
            job, outcome, steps.toList(),
            TranscriptionMetrics(
                audioMs, verifyMs, loadMs, resampleMs, inferenceMs,
                rtf = if (inferenceRan && audioMs > 0) inferenceMs.toDouble() / audioMs else null,
            ),
        )
        fun failed(error: AsrError?, cause: Throwable? = null): TranscriptionResult {
            step(TranscriptionEvent.PERMANENT_ERROR, TranscriptionState.FAILED)
            return result(TranscriptionOutcome.Failed(error, cause?.javaClass?.simpleName))
        }

        if (engine == null) {
            val verifyStart = nanoTime()
            val verified = try {
                verifier.verify(manifest, modelDir)
            } catch (e: ModelRefusedException) {
                verifyMs = elapsedMs(verifyStart)
                step(TranscriptionEvent.MODEL_MISSING, TranscriptionState.WAITING_MODEL)
                return result(TranscriptionOutcome.WaitingModel(modelError(e.refusal, job)))
            }
            verifyMs = elapsedMs(verifyStart)

            val loadStart = nanoTime()
            try {
                engine = engineFactory.create(verified)
            } catch (e: Exception) {
                loadMs = elapsedMs(loadStart)
                step(TranscriptionEvent.RUN_STARTED, TranscriptionState.RUNNING)
                return failed(null, e)
            } catch (e: LinkageError) {
                loadMs = elapsedMs(loadStart)
                step(TranscriptionEvent.RUN_STARTED, TranscriptionState.RUNNING)
                return failed(null, e)
            }
            loadMs = elapsedMs(loadStart)
        }

        step(TranscriptionEvent.RUN_STARTED, TranscriptionState.RUNNING)
        if (audio.frames == 0) {
            step(TranscriptionEvent.RESULT_NO_SPEECH, TranscriptionState.NO_SPEECH)
            return result(TranscriptionOutcome.NoSpeech(noSpeechError(job)))
        }
        val limit = manifest.limits.maxAudioDurationMs
        if (limit != null && audioMs > limit) return failed(durationLimitError(job))

        val output = try {
            val resampleStart = nanoTime()
            val samples = Resampler.toMonoFloat(audio, manifest.sampleRateHz)
            resampleMs = elapsedMs(resampleStart)

            val inferenceStart = nanoTime()
            val raw = engine!!.recognize(samples)
            inferenceMs = elapsedMs(inferenceStart)
            inferenceRan = true
            raw
        } catch (e: Exception) {
            return failed(null, e)
        }

        if (output.text.isBlank()) {
            step(TranscriptionEvent.RESULT_NO_SPEECH, TranscriptionState.NO_SPEECH)
            return result(TranscriptionOutcome.NoSpeech(noSpeechError(job)))
        }
        step(TranscriptionEvent.RESULT_TEXT, TranscriptionState.SUCCEEDED)
        return result(TranscriptionOutcome.Succeeded(revisionContent(output.text, output.language)))
    }

    @Synchronized
    override fun close() {
        closed = true
        engine?.close()
        engine = null
    }

    /** 正文原样放进修订，不规范化、不裁剪；哈希按原始 UTF-8 字节计算（合同语义规则 SR-01）。 */
    private fun revisionContent(text: String, engineLanguage: String?) = RevisionContent(
        text = text,
        textSha256 = sha256Hex(text.toByteArray(Charsets.UTF_8)),
        source = RevisionSource.ASR,
        language = languageTag(engineLanguage),
        reviewState = ReviewState.NOT_REVIEWED,
        executionLocation = ExecutionLocation.ON_DEVICE,
        engine = manifest.engine.name,
        modelId = manifest.modelId,
        modelVersion = manifest.modelVersion,
        confidence = null, // SenseVoice 不给置信度；不编一个
        segments = emptyList(), // 能力声明里 timestamp_granularity 是 none；不编分段
    )

    private fun elapsedMs(startNs: Long) = (nanoTime() - startNs) / 1_000_000

    private fun modelError(refusal: ModelRefusal, job: AsrTaskHandle): AsrError =
        if (refusal == ModelRefusal.NOT_INSTALLED) {
            asrError(ErrorCode.MODEL_NOT_INSTALLED, "没有可用的本地转录模型，任务等待模型安装", true, "安装本地转录模型", job)
        } else {
            asrError(ErrorCode.MODEL_INTEGRITY_FAILED, "本地转录模型校验未通过，已拒绝加载", false, "重新下载模型（需要你的允许）", job)
        }

    private fun noSpeechError(job: AsrTaskHandle) =
        asrError(ErrorCode.ASR_NO_SPEECH, "音频中没有检测到语音", false, null, job)

    // 合同里没有“输入超过引擎单次时长上限”的专用码；ASR_RESOURCE_LIMIT 的本意是暂时性的资源不足（见 README 的合同缺口）。
    private fun durationLimitError(job: AsrTaskHandle) =
        asrError(ErrorCode.ASR_RESOURCE_LIMIT, "音频超过本地转录模型单次可处理的时长上限", false, "把音频切成更短的片段后重试", job)

    private fun asrError(code: ErrorCode, message: String, retryable: Boolean, action: String?, job: AsrTaskHandle) =
        AsrError(ContractJson.SCHEMA_VERSION, code, message, retryable, action, job.jobId)

    companion object {
        // 合同 revision.schema.json 的 content.language 格式
        private val contractLanguageTag = Regex("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$")

        /** SenseVoice 报告 `<|zh|>` 这样的包装；去掉包装后符合合同语言标签才采用，否则是 und（未确定）。 */
        internal fun languageTag(engineReported: String?): String {
            val tag = engineReported.orEmpty().removePrefix("<|").removeSuffix("|>")
            return if (contractLanguageTag.matches(tag)) tag else "und"
        }

        private fun sha256Hex(bytes: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
