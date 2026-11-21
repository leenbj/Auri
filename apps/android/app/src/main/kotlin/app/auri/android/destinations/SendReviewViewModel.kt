package app.auri.android.destinations

import androidx.lifecycle.ViewModel
import app.auri.capture.coordinator.Clock
import app.auri.sync.SyncLog
import app.auri.sync.destinations.Choice
import app.auri.sync.destinations.DecideResult
import app.auri.sync.destinations.DispatchItem
import app.auri.sync.destinations.DispatchStore
import app.auri.sync.destinations.GatewayDelivery
import app.auri.sync.destinations.TargetDecision
import app.auri.sync.engine.JobKey
import app.auri.sync.engine.Refusal
import app.auri.sync.gateway.Intent
import app.auri.sync.gateway.SendMode
import app.auri.sync.policy.DataFlowState
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
 *   ├─ items: List<ReviewItem>        最近 [SendReviewViewModel.ITEM_LIMIT] 条文字，加上更早的、还在等你决定的（等你决定的不能因为后来的文字多了就够不着，评审 M1）：
 *   │                                  等你决定的在前，其余按时间从新到旧。
 *   │   ├─ text                       正文（只给用户看，数据而非指令）；本机已经没有时为 null。
 *   │   ├─ intent                     标签：CONTEXT 备忘 / ACTION_REQUEST 请求执行。只是数据上的标记，不执行任何东西、不给 Agent 额外权限。
 *   │   ├─ intentLocked               标签已冻结：引擎开始为它构造提交（网关马上就要拿到这一版）的那一刻起，标签不可更改，canChangeIntent 为 false。
 *   │   │                              在那之前（没网、等网关、排队）都还能改，提交带的就是改后的（评审 M3）。
 *   │   ├─ targets: List<ReviewTarget> 每个目的地的处置：自动发送 / 等你确认 / 你已确认 / 你选择了不发送 / 不会发给它 / 没有送给它。
 *   │   │                              “没有送给它” = 网关没有（也不会）为它建 Delivery：它暂停期间错过的、或网关拒收的；之后目的地恢复也不补发，
 *   │   │                              所以没有什么可决定的——不摆“确认发送 / 不发送”，界面说清原因（复评 R10，ReviewTarget.delivery / refusal）。
 *   │   │                              文字照常入库、不等你；预览确认的目的地在“你确认”之前**网关上没有给它的 Delivery**，它的 Agent 领不到这条文字（评审 B2）。
 *   │   ├─ awaitingUser               还有等你决定的目标。
 *   │   └─ 所有这些文字都是转录出来的**未审核初稿**（UNREVIEWED_LABEL）：自动发送出去的请求里就是 review_state=not_reviewed。
 *   ├─ defaultIntent                  新录出来的文字用的标签（默认备忘）；在录音之前改。
 *   ├─ dataFlow                       文字能不能进网关（评审 B1：与配对、与各 Destination 的发送方式都是各自独立的授权，默认关；“严格仅本地”压过授权）。
 *   │                                  没放行时页面如实说“确认只是记下你的决定，现在不会发出”，不假装发出去了；没读到时按没放行算。
 *   ├─ unreadable                     读不到本机保存的发送记录（评审 H4）：items 是最近一次读到的，会自动重试；页面如实说读不到，不把空白当成“没有文字”。
 *   └─ confirmNeedsRetry              最近一次“确认发送”没有落账（再评 S8）：系统不让应用登记后台叫醒，没有它应用一被杀这条确认就没人送出，所以不记；
 *                                      页面如实说没记下、什么都没变、请再点一次；用户再点就作废这句话。
 *
 * 写（都不阻塞）：confirm(key, destinationId) / skip(key, destinationId) / setIntent(key, intent) / setDefaultIntent(intent)。
 *   决定是持久的、幂等的：同一个决定重复多少次（双击、进程被杀后重来）只放行一次，重复的什么也不改。
 *   决定落下之后调用 onDecided（让同步立刻跑一轮：确认的话，同步会给已入库的文字追加这个目的地的 Delivery）。
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
    /** 网关上有没有这个目标的 Delivery（账上记的）。被拒绝的目标之后目的地恢复了也不补发：界面要如实说，不能还摆出“确认发送”（复评 R10）。 */
    val delivery: GatewayDelivery = GatewayDelivery.NOT_YET,
    /** [delivery] 是 [GatewayDelivery.REFUSED] 时的原因（暂停期间错过的 / 网关拒收的）；否则为 null。 */
    val refusal: Refusal? = null,
) {
    /**
     * 等着用户做决定（并且这个决定真的会影响发送）：界面据此显示“确认发送 / 不发送”两个按钮。
     * 与账本的 [app.auri.sync.destinations.DispatchTarget.awaitingUser] 同一个判断：被拒绝建 Delivery 的目标没有什么可决定的，
     * 确认与不发送背后都是 NotEligible——摆出两个什么也不做的按钮是在骗用户（复评 R10）。
     */
    val awaitingUser: Boolean get() = decision == TargetDecision.AWAITING && eligible && delivery != GatewayDelivery.REFUSED
}

data class ReviewItem(
    val key: JobKey,
    val text: String?,
    val intent: Intent,
    val createdAt: Instant,
    val intentLocked: Boolean,
    val targets: List<ReviewTarget>,
) {
    val awaitingUser: Boolean get() = targets.any { it.awaitingUser }
    val canChangeIntent: Boolean get() = !intentLocked
}

data class SendReviewUiState(
    val items: List<ReviewItem> = emptyList(),
    val defaultIntent: Intent = Intent.CONTEXT,
    /** 还没读到之前按“没放行”算（fail-closed）。 */
    val dataFlow: DataFlowState = DataFlowState.Closed,
    /** 读不到本机保存的发送记录（评审 H4）：[items] 是最近一次读到的（可能是旧的，也可能是空的），会自动重试，读到之后恢复。 */
    val unreadable: Boolean = false,
    /**
     * 最近一次“确认发送”**没有落账**（再评 S8）：系统不让应用登记“稍后在后台继续发送”，没有它应用一被杀这条确认就没人送出，所以不记这个决定。
     * 页面如实说没记下、什么都没变、请再点一次；用户再点（无论结果）就收掉这句话，点成了自然不再显示。
     */
    val confirmNeedsRetry: Boolean = false,
)

class SendReviewViewModel(
    private val dispatch: DispatchStore,
    private val texts: ItemTexts,
    private val clock: Clock,
    private val onDecided: () -> Unit,
    private val scope: CoroutineScope,
    dataFlow: Flow<DataFlowState>,
    private val log: SyncLog = SyncLog.None,
) : ViewModel() {
    /** 最近一次“确认发送”没有落账（系统不让登记后台叫醒，再评 S8）：用户再点一次就作废，点成了自然不再说。 */
    private val confirmNeedsRetry = MutableStateFlow(false)

    val state: StateFlow<SendReviewUiState> = combine(dispatch.changes().onStart { emit(0L) }, dataFlow.onStart { emit(DataFlowState.Closed) }, confirmNeedsRetry) { _, flow, retry -> load(flow).copy(confirmNeedsRetry = retry) }
        // 读发送账失败不能逃出作用域（评审 H4）：记一行日志、如实标上“读不到”（留着最近读到的）、过一会儿重读
        .containReadFailures("send.review", log) { last -> emit((last ?: SendReviewUiState()).copy(unreadable = true)) }
        .stateIn(scope, SharingStarted.Eagerly, SendReviewUiState())

    fun confirm(key: JobKey, destinationId: String) = decide(key, destinationId, Choice.CONFIRM)

    fun skip(key: JobKey, destinationId: String) = decide(key, destinationId, Choice.SKIP)

    fun setIntent(key: JobKey, intent: Intent) {
        scope.launch { guarded("intent") { dispatch.setIntent(key, intent, clock.now()) } }
    }

    fun setDefaultIntent(intent: Intent) {
        scope.launch { guarded("default-intent") { dispatch.setDefaultIntent(intent) } }
    }

    override fun onCleared() {
        scope.cancel()
    }

    private fun decide(key: JobKey, destinationId: String, choice: Choice) {
        scope.launch {
            confirmNeedsRetry.value = false // 用户又点了一次：之前那句话作废，这次的结果再决定要不要说
            val result = guarded("decide") { dispatch.decide(key, destinationId, choice, clock.now()) }
            // Decided：刚记下一个新决定，让同步立刻跑一轮（确认的话要追加 Delivery）。
            // WakeUnavailable：确认没有落账（系统不让登记后台叫醒），如实告诉用户再点一次（再评 S8）。其余（早就决定过、没有这个目标）什么也不变
            when (result) {
                is DecideResult.Decided -> onDecided()
                is DecideResult.WakeUnavailable -> confirmNeedsRetry.value = true
                else -> Unit
            }
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
        // 最近一页，再补上更早的、还在等用户决定的：等你决定的不能因为后面又来了很多新文字就够不着（评审 M1）
        val recent = dispatch.items(ITEM_LIMIT)
        val shown = recent.mapTo(HashSet()) { it.key }
        val items = (recent + dispatch.awaiting().filter { it.key !in shown }).map { it.toReview() }
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
        intentLocked = intentLocked,
        targets = targets.map { ReviewTarget(it.destinationId, it.destinationName, it.sendMode, it.decision, it.eligible, it.delivery, it.refusal) },
    )

    companion object {
        const val ITEM_LIMIT = 30
    }
}
