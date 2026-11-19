package app.auri.capture.feedback

import app.auri.capture.coordinator.CaptureDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 反馈泵：协调器的每个决定问一次 [feedbackFor]，有对应的反馈就交给 [player]，并留作 [latest] 给界面显示屏幕文字。
 *
 *  - 屏幕文字先于播放更新：振动或通知出了任何问题，用户仍然能在屏幕上看到发生了什么；
 *  - 播放器抛异常只记一行（步骤 + 异常类型，不写消息：消息里可能有设备名），不影响后面的反馈，更不影响录音；
 *  - 订阅在构造函数返回之前就登记好了：决定流没有回放，订阅晚了，期间的决定就永远丢了；
 *  - [latest] 一直保留到下一个反馈把它换掉；界面如果只想显示一会儿，自己决定什么时候不显示。
 */
class FeedbackHub(
    decisions: SharedFlow<CaptureDecision>,
    private val player: FeedbackPlayer,
    scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {
    private val latestFlow = MutableStateFlow<Feedback?>(null)
    val latest: StateFlow<Feedback?> = latestFlow

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { decisions.collect(::onDecision) }
    }

    private fun onDecision(decision: CaptureDecision) {
        val feedback = Feedbacks.of(feedbackFor(decision) ?: return)
        latestFlow.value = feedback
        try {
            player.play(feedback)
        } catch (e: Exception) {
            log("feedback_play_failed reason=${e.javaClass.simpleName}")
        }
    }
}
