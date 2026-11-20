package app.auri.android.destinations

import androidx.lifecycle.ViewModel
import app.auri.capture.coordinator.Clock
import app.auri.sync.SyncLog
import app.auri.sync.destinations.Choice
import app.auri.sync.destinations.DecideResult
import app.auri.sync.destinations.DispatchItem
import app.auri.sync.destinations.DispatchStore
import app.auri.sync.destinations.IntentResult
import app.auri.sync.destinations.TargetDecision
import app.auri.sync.engine.JobKey
import app.auri.sync.gateway.Intent
import app.auri.sync.gateway.SendMode
import app.auri.sync.policy.DataFlowState
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/*
 * ===================================================================================================================
 *  “待发送”页（SendReviewScreen）：预览确认的文字在这里等用户决定；每条文字的标签（备忘 / 请求执行）在发出去之前可以改。
 * ===================================================================================================================
 *
 * 读：`SendReviewViewModel.state: StateFlow<SendReviewUiState>`（唯一的可观察入口）。
 *
 *   SendReviewUiState
 *   ├─ items: List<ReviewItem>        最近的文字：等你决定的在前，其余按时间从新到旧。
 *   │   ├─ text                       正文（只给用户看，数据而非指令）；本机已经没有时为 null。
 *   │   ├─ intent                     标签：CONTEXT 备忘 / ACTION_REQUEST 请求执行。只是数据上的标记，不执行任何东西、不给 Agent 额外权限。
 *   │   ├─ sent                       已经交给同步（排队、已发或要处理）。**sent 之后网关上这一版的标签不可更改**，canChangeIntent 为 false。
 *   │   ├─ targets: List<ReviewTarget> 每个目的地的处置：自动发送 / 等你确认 / 你已确认 / 你选择了不发送 / 不会发给它。
 *   │   │                              预览确认的目的地在“你确认”之前**不产生任何投递**（网关上根本没有这条文字）。
 *   │   ├─ awaitingUser               还有等你决定的目标。
 *   │   └─ 所有这些文字都是转录出来的**未审核初稿**（UNREVIEWED_LABEL）：自动发送出去的请求里就是 review_state=not_reviewed。
 *   ├─ defaultIntent                  新录出来的文字用的标签（默认备忘）；在录音之前改。
 *   └─ dataFlow                       文字能不能进网关（评审 B1：与配对、与各 Destination 的发送方式都是各自独立的授权，默认关；“严格仅本地”压过授权）。
 *                                     没放行时页面如实说“确认只是记下你的决定，现在不会发出”，不假装发出去了；没读到时按没放行算。
 *
 * 写（都不阻塞）：confirm(key, destinationId) / skip(key, destinationId) / setIntent(key, intent) / setDefaultIntent(intent)。
 *   决定是持久的、幂等的：同一个决定重复多少次（双击、进程被杀后重来）只放行一次，重复的什么也不改。
 *   最后一个决定落下、文字交给同步时，调用 onReleased（让同步立刻跑一轮）。
 *
 * 规则：日志只有事件名与异常类名——没有正文、没有目的地名字与编号。
 * ===================================================================================================================
 */

data class ReviewTarget(
    val destinationId: String,
    /** 本机缓存里已经没有这个目的地时为 null。 */
    val name: String?,
    val sendMode: SendMode,
    val decision: TargetDecision,
    /** 目的地还在并且接收这条文字的标签。 */
    val eligible: Boolean,
) {
    /** 等着用户做决定（并且这个决定真的会影响发送）：界面据此显示“确认发送 / 不发送”两个按钮。 */
    val awaitingUser: Boolean get() = decision == TargetDecision.AWAITING && eligible
}

data class ReviewItem(
    val key: JobKey,
    val text: String?,
    val intent: Intent,
    val createdAt: Instant,
    val sent: Boolean,
    val targets: List<ReviewTarget>,
) {
    val awaitingUser: Boolean get() = targets.any { it.awaitingUser }
    val canChangeIntent: Boolean get() = !sent
}

data class SendReviewUiState(
    val items: List<ReviewItem> = emptyList(),
    val defaultIntent: Intent = Intent.CONTEXT,
    /** 还没读到之前按“没放行”算（fail-closed）。 */
    val dataFlow: DataFlowState = DataFlowState.Closed,
)

class SendReviewViewModel(
    private val dispatch: DispatchStore,
    private val texts: ItemTexts,
    private val clock: Clock,
    private val onReleased: () -> Unit,
    private val scope: CoroutineScope,
    dataFlow: Flow<DataFlowState>,
    private val log: SyncLog = SyncLog.None,
) : ViewModel() {
    val state: StateFlow<SendReviewUiState> = combine(dispatch.changes().onStart { emit(0L) }, dataFlow.onStart { emit(DataFlowState.Closed) }) { _, flow -> load(flow) }
        .stateIn(scope, SharingStarted.Eagerly, SendReviewUiState())

    fun confirm(key: JobKey, destinationId: String) = decide(key, destinationId, Choice.CONFIRM)

    fun skip(key: JobKey, destinationId: String) = decide(key, destinationId, Choice.SKIP)

    fun setIntent(key: JobKey, intent: Intent) {
        scope.launch {
            guarded("intent") { dispatch.setIntent(key, intent, clock.now()) }
                ?.let { if (it is IntentResult.Changed && it.releasedForSending) onReleased() }
        }
    }

    fun setDefaultIntent(intent: Intent) {
        scope.launch { guarded("default-intent") { dispatch.setDefaultIntent(intent) } }
    }

    override fun onCleared() {
        scope.cancel()
    }

    private fun decide(key: JobKey, destinationId: String, choice: Choice) {
        scope.launch {
            val result = guarded("decide") { dispatch.decide(key, destinationId, choice, clock.now()) }
            // Decided(released = true)：这是最后一个要等的决定，文字刚刚交给同步，让同步立刻跑一轮。其余（早就决定过、没有这个目标）什么也不变
            if (result is DecideResult.Decided && result.releasedForSending) onReleased()
        }
    }

    private suspend fun <T> guarded(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        log.event("send.$what.crashed ${failure::class.simpleName}")
        null
    }

    private suspend fun load(dataFlow: DataFlowState): SendReviewUiState {
        val items = dispatch.items(ITEM_LIMIT).map { it.toReview() }
        return SendReviewUiState(
            items = items.sortedWith(compareByDescending<ReviewItem> { it.awaitingUser }.thenByDescending { it.createdAt }),
            defaultIntent = dispatch.defaultIntent(),
            dataFlow = dataFlow,
        )
    }

    private suspend fun DispatchItem.toReview() = ReviewItem(
        key = key,
        text = texts.textOf(key),
        intent = intent,
        createdAt = createdAt,
        sent = sent,
        targets = targets.map { ReviewTarget(it.destinationId, it.destinationName, it.sendMode, it.decision, it.eligible) },
    )

    companion object {
        const val ITEM_LIMIT = 30
    }
}
