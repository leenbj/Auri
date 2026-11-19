package app.auri.capture.feedback

import app.auri.capture.contract.CaptureState
import app.auri.capture.coordinator.CaptureDecision
import app.auri.capture.coordinator.Cause

/** 用户需要分清的三件事：录音开始了、录音停止了、录音没能开始。 */
enum class FeedbackKind { STARTED, STOPPED, START_FAILED, DURATION_WARNING }

/**
 * 一种反馈的全部内容：屏幕上的文字、通知里的文字、振动节奏。
 *
 * 没有声音这一项，是刻意的：耳机麦克风正在（或即将）录音，任何提示音都会进入蓝牙通路，污染录音，
 * 也违反“不向蓝牙通路播放任何声音”的约束。文字只说发生了什么，没有正文、设备名或标识。
 */
data class Feedback(
    val kind: FeedbackKind,
    val screenText: String,
    val notificationText: String,
    /** 振动波形，毫秒：关、开、关、开……，从“关”开始（Android VibrationEffect.createWaveform 的格式）。 */
    val vibrationPattern: List<Long>,
) {
    /** 每个脉冲（“开”）的长度。 */
    val pulses: List<Long> get() = vibrationPattern.filterIndexed { index, _ -> index % 2 == 1 }
}

/** 把反馈交给用户的出口。生产实现在 :platform（振动 + 静音通知），测试里用假的。实现不得抛异常、不得发出任何声音。 */
fun interface FeedbackPlayer {
    fun play(feedback: Feedback)
}

object Feedbacks {
    // 不看屏幕就能分清：开始 = 一下短振；停止 = 两下短振；没能开始 = 一下长振
    private val started = Feedback(FeedbackKind.STARTED, "录音已开始", "录音中", listOf(0, 120))
    private val stopped = Feedback(FeedbackKind.STOPPED, "录音已停止", "录音已停止", listOf(0, 80, 120, 80))
    private val startFailed = Feedback(FeedbackKind.START_FAILED, "录音没能开始", "录音没能开始，请在屏幕上重试", listOf(0, 700))
    private val durationWarning = Feedback(FeedbackKind.DURATION_WARNING, "录音将在一分钟后停止", "录音将在一分钟后停止", listOf(0, 300, 100, 300))

    fun of(kind: FeedbackKind): Feedback = when (kind) {
        FeedbackKind.STARTED -> started
        FeedbackKind.STOPPED -> stopped
        FeedbackKind.START_FAILED -> startFailed
        FeedbackKind.DURATION_WARNING -> durationWarning
    }
}

/**
 * 协调器的一个决定对应哪种反馈；没有对应的返回 null。
 *
 * “已开始”要等路由确认、录音真的起来（准备中 → 录音中）才给：只是进入准备，输入还没确认是蓝牙耳机，不能先告诉用户已开始。
 * 被忽略、被拒绝的命令没有状态变化，不给反馈，免得用户把一个过时的切换命令当成又开始了一段。
 * 用户自己在准备阶段取消（准备中 → 就绪）不算失败。
 */
fun feedbackFor(decision: CaptureDecision): FeedbackKind? = when {
    decision.cause == Cause.DURATION_WARNING -> FeedbackKind.DURATION_WARNING
    decision.stateBefore == CaptureState.ROUTING && decision.stateAfter == CaptureState.RECORDING -> FeedbackKind.STARTED
    decision.stateBefore == CaptureState.RECORDING && decision.stateAfter == CaptureState.FINALIZING -> FeedbackKind.STOPPED
    decision.stateBefore == CaptureState.ROUTING && decision.stateAfter == CaptureState.BLOCKED -> FeedbackKind.START_FAILED
    else -> null
}
