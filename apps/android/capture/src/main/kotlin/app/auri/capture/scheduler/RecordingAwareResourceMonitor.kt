package app.auri.capture.scheduler

import app.auri.capture.contract.CaptureState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * 录音优先（Ticket 20）：采集正在确认路由或录音时，调度器不开始新的转录（原因 [ResourceReason.RECORDING_ACTIVE]），录音一结束再开始；
 * 其余读数（温度、省电、内存、存储）交给 [base]，录音的原因排在它们前面。
 *
 * 已经在跑的转录停不下来（真引擎 `cancel_support = none`），照常转完：这里挡的只是“再开始一个”。
 * 封存中（[CaptureState.FINALIZING]）不算在录：麦克风这时已经关了，而刚录完的那一段正是在封存时提交转录的，
 * 把它算进去，每一段录音自己的第一次转录都会白白经过一次“等待”。
 */
class RecordingAwareResourceMonitor(
    private val base: ResourceMonitor,
    private val captureState: StateFlow<CaptureState>,
) : ResourceMonitor {
    override fun check(): ResourceStatus =
        if (captureState.value in RECORDING) ResourceStatus.Low(ResourceReason.RECORDING_ACTIVE) else base.check()

    /** 开始录与结束录各发一个信号（调度器据此重问 [check]）；ROUTING 到 RECORDING 这类答案没变的状态变化不发。 */
    override val changes: Flow<Unit> = merge(
        base.changes,
        captureState.map { it in RECORDING }.distinctUntilChanged().drop(1).map { },
    )

    private companion object {
        val RECORDING = setOf(CaptureState.ROUTING, CaptureState.RECORDING)
    }
}
