package app.auri.android.destinations

import androidx.lifecycle.ViewModel
import app.auri.sync.SyncLog
import app.auri.sync.destinations.DestinationController
import app.auri.sync.destinations.ServiceFailure
import app.auri.sync.destinations.ServiceResult
import app.auri.sync.gateway.CreateDestinationRequest
import app.auri.sync.gateway.Destination
import app.auri.sync.gateway.Intent
import app.auri.sync.gateway.SendMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * ===================================================================================================================
 *  发送目的地列表与创建（DestinationsScreen）的数据与操作。界面只读 DestinationsUiState、调 DestinationsViewModel 的方法。
 * ===================================================================================================================
 *
 * 读：`DestinationsViewModel.state: StateFlow<DestinationsUiState>`（唯一的可观察入口）。
 *
 *   DestinationsUiState
 *   ├─ destinations: List<DestinationItem>   本机缓存的目的地（读它不联网）：有效的在前，其次按创建时间从新到旧；含已撤销的。
 *   ├─ refreshing: Boolean                   正在向网关要完整列表。
 *   ├─ form: CreateForm                      创建表单。
 *   │   ├─ name                              用户起的名字（1..100 个字符）。
 *   │   ├─ sendMode: SendMode?               **没有默认值**：一开始是 null，用户必须明确选“自动发送”或“预览确认”。
 *   │   ├─ allowActionRequests               是否也接收“请求执行”标签的文字（默认否：只收备忘）。
 *   │   └─ canSubmit                         名字合格并且已选发送方式——创建按钮只在这时可点。
 *   ├─ creating: Boolean                     创建请求在途：期间再点没有用（创建没有幂等键，重复请求会多出同名目的地）。
 *   ├─ problem: ServiceFailure?              最近一次操作没做成的原因（界面用 describeFailure 说话）；dismissProblem() 关掉。
 *   │                                         “结果不确定”（Unreadable / Interrupted）时表单不清空，提示用户先刷新确认。
 *   └─ unreadable: Boolean                   读不到本机缓存的目的地（评审 H4）：destinations 是最近一次读到的，会自动重试；页面如实说读不到。
 *
 * 写（都不阻塞）：setName / chooseSendMode / setAllowActionRequests / submit / refresh / dismissProblem。
 *
 * 规则：创建请求里的 send_mode 就是用户选的那个；没选就根本不会发请求（submit 什么也不做）。
 *       日志只有事件名与异常类名，没有目的地名字、编号或地址。
 * ===================================================================================================================
 */

/** 列表里的一行（网关上的 Destination 的公开信息；凭证从不在这里）。 */
data class DestinationItem(
    val destinationId: String,
    val name: String,
    val sendMode: SendMode,
    val allowedIntents: Set<Intent>,
    val active: Boolean,
    /** 这个目的地已经签发过凭证（再生成就是轮换）。 */
    val credentialIssued: Boolean,
    /** 凭证被撤销、没有新的：网关暂停了它（评审 B3）——没被撤销，但不再接收新文字；重新生成凭证才恢复。 */
    val suspended: Boolean = false,
)

internal fun Destination.toItem() = DestinationItem(destinationId, displayName, sendMode, allowedIntents, active, credentialIssued, suspended)

data class CreateForm(
    val name: String = "",
    /** 一开始就是 null：**不存在默认的发送方式**。 */
    val sendMode: SendMode? = null,
    val allowActionRequests: Boolean = false,
) {
    val nameValid: Boolean
        get() = name.trim().let { it.isNotEmpty() && it.codePointCount(0, it.length) <= CreateDestinationRequest.MAX_NAME }

    val canSubmit: Boolean get() = nameValid && sendMode != null

    /** 没选发送方式（或名字不合格）就没有请求。 */
    internal fun request(): CreateDestinationRequest? {
        val mode = sendMode ?: return null
        if (!nameValid) return null
        return CreateDestinationRequest(name.trim(), mode, if (allowActionRequests) setOf(Intent.CONTEXT, Intent.ACTION_REQUEST) else setOf(Intent.CONTEXT))
    }
}

data class DestinationsUiState(
    val destinations: List<DestinationItem> = emptyList(),
    val refreshing: Boolean = false,
    val form: CreateForm = CreateForm(),
    val creating: Boolean = false,
    val problem: ServiceFailure? = null,
    /** 读不到本机缓存的目的地（评审 H4）：[destinations] 是最近一次读到的（可能是旧的，也可能是空的），会自动重试，读到之后恢复。 */
    val unreadable: Boolean = false,
)

class DestinationsViewModel(
    private val controller: DestinationController,
    private val scope: CoroutineScope,
    private val log: SyncLog = SyncLog.None,
) : ViewModel() {
    private val form = MutableStateFlow(CreateForm())
    private val refreshing = MutableStateFlow(false)
    private val creating = MutableStateFlow(false)
    private val problem = MutableStateFlow<ServiceFailure?>(null)

    /** 读本机缓存的结果：读不到时留着最近读到的，并标上 [unreadable]（评审 H4）。 */
    private data class Cache(val destinations: List<DestinationItem> = emptyList(), val unreadable: Boolean = false)

    val state: StateFlow<DestinationsUiState> = combine(
        controller.destinations
            .map { list -> Cache(list.sortedWith(compareByDescending<Destination> { it.active }.thenByDescending { it.createdAt }).map { it.toItem() }) }
            .containReadFailures("destinations", log) { last -> emit((last ?: Cache()).copy(unreadable = true)) },
        form,
        refreshing,
        creating,
        problem,
    ) { cache, form, refreshing, creating, problem ->
        DestinationsUiState(cache.destinations, refreshing, form, creating, problem, cache.unreadable)
    }.stateIn(scope, SharingStarted.Eagerly, DestinationsUiState())

    init {
        refresh()
    }

    fun setName(name: String) = form.update { it.copy(name = name) }

    /** 用户明确选了一种发送方式。没有“取消选择”：选了之后只能换成另一种。 */
    fun chooseSendMode(mode: SendMode) = form.update { it.copy(sendMode = mode) }

    fun setAllowActionRequests(allow: Boolean) = form.update { it.copy(allowActionRequests = allow) }

    fun submit() {
        val request = form.value.request() ?: return // 没选发送方式：什么请求都不发
        if (!creating.compareAndSet(false, true)) return // 双击：只发一次
        problem.value = null
        scope.launch {
            try {
                when (val result = controller.create(request)) {
                    is ServiceResult.Ok -> form.value = CreateForm()
                    is ServiceResult.Failed -> problem.value = result.failure // 不确定的结果也不清空表单：用户先刷新确认，别直接重试
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log.event("destination.create.crashed ${failure::class.simpleName}")
                problem.value = ServiceFailure.Interrupted
            } finally {
                creating.value = false
            }
        }
    }

    fun refresh() {
        if (!refreshing.compareAndSet(false, true)) return
        scope.launch {
            try {
                (controller.refresh() as? ServiceResult.Failed)?.let { problem.value = it.failure }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log.event("destination.refresh.crashed ${failure::class.simpleName}")
            } finally {
                refreshing.value = false
            }
        }
    }

    fun dismissProblem() {
        problem.value = null
    }

    override fun onCleared() {
        scope.cancel()
    }
}
