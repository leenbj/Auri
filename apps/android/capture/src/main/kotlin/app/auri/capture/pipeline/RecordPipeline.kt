package app.auri.capture.pipeline

import app.auri.capture.adapter.TranscriptionAdapter
import app.auri.capture.contract.ErrorCode
import app.auri.capture.coordinator.CaptureOutput
import app.auri.capture.coordinator.Clock
import app.auri.capture.coordinator.SealedRecording
import app.auri.capture.library.RecordEntry
import app.auri.capture.library.RecordLibrary
import app.auri.capture.library.TranscriptionStatus
import app.auri.capture.scheduler.TranscriptionScheduler
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
 * 录音封存之后的流水线：登记 Record → 交给转录调度器（[TranscriptionScheduler]）→ 第一条 Revision 由调度器发布。
 * 接在协调器后面当它的 [CaptureOutput]；协调器只管把录音封存好，之后没有任何人需要“触发转录”。
 *
 * 本类只做“登记”：转录的排队、一次只转一个（默认）、等模型、等资源、有界重试、取消、重转录、重启后恢复，全在调度器里
 * （规则见它的类注释；任务状态严格按 transcription.machine.json 走）。这里保留的规则：
 *  - 一个封存结果 = 一个 Record（record_id 与音频同一个标识）；同一个录音交付两次只登记一次。
 *  - 没有音频（确认了路由却没收到采样）记为没有语音，不提交转录。
 *  - 转出的文字是数据（SEC-004）、没有语音就没有 Revision、模型没装好 Record 与音频保留并等模型——这些由调度器保证。
 *  - 库的异常只影响那一个 Record：登记失败发 [PipelineNotice.RecordNotSaved]，其余照常。
 *  - 日志里不出现正文（本类不打日志）。
 */
class RecordPipeline(
    private val library: RecordLibrary,
    private val scheduler: TranscriptionScheduler,
    private val scope: CoroutineScope,
) : CaptureOutput {
    /** 默认配置的调度器：模型随时可用、资源永远够、任务只存在内存里（重启后靠 Record 库的状态重建）。 */
    constructor(library: RecordLibrary, transcription: TranscriptionAdapter, clock: Clock, scope: CoroutineScope) :
        this(library, TranscriptionScheduler(library, transcription, clock, scope), scope)

    private val intake = Channel<SealedRecording>(Channel.UNLIMITED)
    private val noticeFlow = MutableSharedFlow<PipelineNotice>(extraBufferCapacity = 64)

    val notices: SharedFlow<PipelineNotice> = noticeFlow.asSharedFlow()

    init {
        scope.launch { for (sealed in intake) register(sealed) }
    }

    /** 协调器在封存完成时调用；只入队，不阻塞。 */
    override fun recordingSealed(sealed: SealedRecording) {
        intake.trySend(sealed)
    }

    override fun recordingLost(recordId: String, reason: ErrorCode) {
        noticeFlow.tryEmit(PipelineNotice.RecordingLost(recordId, reason))
    }

    /**
     * 进程启动后、界面回到前台、模型装好后调用（可以随便多调）：调度器会认领上次被杀时正在转的任务，
     * 并把库里没转完却没有任务的 Record 补上任务。
     */
    fun resume() = scheduler.resume()

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
        if (added && audio != null) scheduler.submit(result.recordId)
    }
}
