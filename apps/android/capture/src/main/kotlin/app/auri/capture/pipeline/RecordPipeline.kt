package app.auri.capture.pipeline

import app.auri.capture.adapter.TranscriptionAdapter
import app.auri.capture.adapter.TranscriptionOutcome
import app.auri.capture.adapter.TranscriptionRequest
import app.auri.capture.contract.ErrorCode
import app.auri.capture.contract.RevisionSource
import app.auri.capture.contract.ReviewState
import app.auri.capture.coordinator.CaptureOutput
import app.auri.capture.coordinator.Clock
import app.auri.capture.coordinator.SealedRecording
import app.auri.capture.library.RecordEntry
import app.auri.capture.library.RecordLibrary
import app.auri.capture.library.RevisionDraft
import app.auri.capture.library.TranscriptionStatus
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/** 管线需要让用户知道、但没有 Record 可以承载的事。 */
sealed interface PipelineNotice {
    /** 录音封存失败，音频没能保存。 */
    data class RecordingLost(val recordId: String, val reason: ErrorCode) : PipelineNotice

    /** 音频已经保存，但 Record 没能写进库（之后可由 Ticket 19 的恢复流程找回）。 */
    data class RecordNotSaved(val recordId: String) : PipelineNotice
}

/**
 * 录音封存之后的流水线：登记 Record → 自动转录（一次只转一个，按先后顺序）→ 发布第一条 Revision。
 * 接在协调器后面当它的 [CaptureOutput]；协调器只管把录音封存好，之后没有任何人需要“触发转录”。
 *
 * 规则：
 *  - 一个封存结果 = 一个 Record（record_id 与音频同一个标识）；同一个录音交付两次只登记一次。
 *  - 转出的文字是数据：原样存成 Revision，不解释、不执行（SEC-004）。空白文字当作没有语音，不留空 Revision。
 *  - 没有语音 = 有 Record、没有 Revision；不编造文字。没有音频（确认了路由却没收到采样）同样记为没有语音，不调用转录。
 *  - 模型没装好：Record 与音频保留，状态为 WAITING_MODEL，[resume] 时重新转录。转录失败：状态为 FAILED，不自动重试。
 *  - 适配器或库的异常只影响那一个 Record，队列继续往下走。
 *  - 日志里不出现正文（本类不打日志）。
 */
class RecordPipeline(
    private val library: RecordLibrary,
    private val transcription: TranscriptionAdapter,
    private val clock: Clock,
    private val scope: CoroutineScope,
) : CaptureOutput {
    private val intake = Channel<SealedRecording>(Channel.UNLIMITED)
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val queued: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val noticeFlow = MutableSharedFlow<PipelineNotice>(extraBufferCapacity = 64)

    val notices: SharedFlow<PipelineNotice> = noticeFlow.asSharedFlow()

    init {
        scope.launch { for (sealed in intake) register(sealed) }
        scope.launch { for (recordId in queue) transcribeOne(recordId) }
    }

    /** 协调器在封存完成时调用；只入队，不阻塞。 */
    override fun recordingSealed(sealed: SealedRecording) {
        intake.trySend(sealed)
    }

    override fun recordingLost(recordId: String, reason: ErrorCode) {
        noticeFlow.tryEmit(PipelineNotice.RecordingLost(recordId, reason))
    }

    /** 把库里还没转完的 Record（上次没转完、或等模型）重新排进转录队列：进程重启后、模型装好后调用。 */
    fun resume() {
        scope.launch {
            val pending = try {
                library.incomplete()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }
            pending.forEach { enqueue(it.recordId) }
        }
    }

    private suspend fun register(sealed: SealedRecording) {
        val result = sealed.result
        val audio = sealed.audio
        val entry = RecordEntry(
            recordId = result.recordId,
            captureSessionId = result.captureSessionId,
            startedAt = sealed.startedAt,
            status = result.status,
            endReason = result.endReason,
            captureDurationMs = result.captureDurationMs,
            speechDurationMs = result.speechDurationMs,
            speechSegments = result.verifiedRanges,
            audio = audio,
            routeEvidenceId = sealed.routeEvidenceId,
            inputRouteType = sealed.evidence.observedInput.routeType,
            headsetDisplayName = sealed.evidence.observedInput.displayName,
            // 没有音频就没有什么可转的：直接记为没有语音
            transcription = if (audio == null) TranscriptionStatus.NO_SPEECH else TranscriptionStatus.PENDING,
        )
        val added = try {
            library.addRecord(entry)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            noticeFlow.tryEmit(PipelineNotice.RecordNotSaved(result.recordId))
            return
        }
        if (added && audio != null) enqueue(result.recordId)
    }

    private fun enqueue(recordId: String) {
        if (queued.add(recordId)) queue.trySend(recordId)
    }

    private suspend fun transcribeOne(recordId: String) {
        try {
            val entry = library.get(recordId) ?: return
            val audio = entry.audio ?: return
            if (entry.transcription != TranscriptionStatus.PENDING && entry.transcription != TranscriptionStatus.WAITING_MODEL) return

            val result = transcription.transcribe(TranscriptionRequest(recordId, audio))
            when (val outcome = result.outcome) {
                is TranscriptionOutcome.Text ->
                    if (outcome.text.isBlank()) {
                        library.markTranscription(recordId, TranscriptionStatus.NO_SPEECH)
                    } else {
                        library.publishRevision(
                            RevisionDraft(
                                recordId = recordId,
                                text = outcome.text,
                                source = RevisionSource.ASR,
                                language = outcome.language,
                                reviewState = ReviewState.NOT_REVIEWED,
                                executionLocation = result.capabilities.executionLocation,
                                engine = result.capabilities.engine,
                                modelId = result.capabilities.modelId,
                                modelVersion = result.capabilities.modelVersion,
                                confidence = outcome.confidence,
                                createdAt = clock.now(),
                            ),
                        )
                    }

                TranscriptionOutcome.NoSpeech -> library.markTranscription(recordId, TranscriptionStatus.NO_SPEECH)
                is TranscriptionOutcome.WaitingModel -> library.markTranscription(recordId, TranscriptionStatus.WAITING_MODEL, outcome.code)
                is TranscriptionOutcome.Failed -> library.markTranscription(recordId, TranscriptionStatus.FAILED, outcome.code)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 只影响这一个 Record：标为失败（音频仍在），队列继续
            try {
                library.markTranscription(recordId, TranscriptionStatus.FAILED, null)
            } catch (inner: CancellationException) {
                throw inner
            } catch (inner: Exception) {
                // 连状态都写不进去：Record 保持原样，下次 resume 还会再试
            }
        } finally {
            queued.remove(recordId)
        }
    }
}
