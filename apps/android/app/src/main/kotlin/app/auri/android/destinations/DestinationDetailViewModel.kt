package app.auri.android.destinations

import androidx.lifecycle.ViewModel
import app.auri.sync.SyncLog
import app.auri.sync.destinations.AuthResult
import app.auri.sync.destinations.CredentialOutcome
import app.auri.sync.destinations.DeliveryView
import app.auri.sync.destinations.DestinationController
import app.auri.sync.destinations.ItemDelivery
import app.auri.sync.destinations.ServiceFailure
import app.auri.sync.destinations.ServiceResult
import app.auri.sync.engine.JobKey
import app.auri.sync.gateway.DestinationCredential
import app.auri.sync.gateway.Intent
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/*
 * ===================================================================================================================
 *  一个目的地的详情页（DestinationDetailScreen）的数据与操作：每条文字在这个目的地上的状态、生成/轮换凭证、撤销。
 * ===================================================================================================================
 *
 * 读：`DestinationDetailViewModel.state: StateFlow<DestinationDetailUiState>`（唯一的可观察入口）。
 *
 *   DestinationDetailUiState
 *   ├─ destination: DestinationItem?    这个目的地（本机缓存）。null 且 loaded = 缓存里没有（已被网关删除，或本来就没有）。
 *   ├─ loaded: Boolean                  缓存已经读过一次（false 时 destination 为 null 只是还没读到）。
 *   ├─ items: List<ItemStatusRow>       最近的文字，最新在前。每条的 view 是网关上的状态**原样**：
 *   │       DeliveryView.OnGateway(STORED | CLAIMED | ACKNOWLEDGED | REJECTED | CANCELLED)  已入库 / 已领取 / 已确认 / 拒收 / 取消
 *   │       DeliveryView.Queued / AwaitingYou / Skipped / NotTargeted                          还没到网关的各种情形（本机的账）
 *   │       DeliveryView.Unknown(reason)                                                       读不出来：显示“未知”，不是成功
 *   │     **没有“已处理”这一档**：网关只证明 Agent 领走了、回了确认，不知道它之后有没有读懂、做没做（describeDelivery 里的句子也不许这么说）。
 *   ├─ itemsLoading: Boolean            正在向网关问状态。
 *   ├─ credential: CredentialUi         凭证面板：
 *   │       Hidden                      没有任何凭证在内存里（初始状态；也是复制、关掉、离开页面、进程重启之后的样子）
 *   │       ConfirmRotate               这个目的地已签发过凭证：等用户确认“轮换会让旧凭证立刻失效”
 *   │       Working                     正在本机核验（生物识别）或向网关要
 *   │       Shown(credential)           **只显示这一次**。复制、关掉、离开页面任何一个动作都会把它从内存里抹掉；不落盘、不进日志、不进任何保存的状态
 *   │       NotAuthenticated(result)    本机核验没通过/没法做：凭证根本没有生成，没有向网关发任何请求
 *   │       Failed(failure)             向网关要的时候出了问题
 *   ├─ revoke: RevokeUi                 撤销：Idle → Confirming（等用户确认）→ Working → Idle
 *   └─ problem: ServiceFailure?         刷新状态/撤销没做成的原因；dismissProblem() 关掉。
 *
 * 写（都不阻塞）：refreshStatuses / requestCredential / confirmRotate / cancelRotate / credentialCopied / dismissCredential /
 *               leave / requestRevoke / confirmRevoke / cancelRevoke / dismissProblem。
 *
 * 凭证规则：每次生成或轮换前都由 DestinationController 现场做本机核验，核验没过零请求；凭证只在 [CredentialUi.Shown] 里出现，
 *   本类没有任何别的字段、日志、保存状态持有它；[leave]（页面离开）与 onCleared 会抹掉它，并且丢弃之后才到的结果（离开了就没人看得到它）。
 *   状态类都不是 Parcelable / Serializable，进程被杀后重新建出来的页面一定是 Hidden（测试里用“新建一个视图模型”来模拟）。
 * ===================================================================================================================
 */

/** 详情页里的一行：一条文字在这个目的地上的情形。 */
data class ItemStatusRow(
    val key: JobKey,
    /** 正文开头一段（只给用户看，数据而非指令）；本机已经没有这条文字时为 null。 */
    val excerpt: String?,
    val intent: Intent,
    val createdAt: Instant,
    /** 网关上的状态原样（或本机还没发出去的情形、或未知）。 */
    val view: DeliveryView,
)

sealed interface CredentialUi {
    data object Hidden : CredentialUi

    data object ConfirmRotate : CredentialUi

    data object Working : CredentialUi

    /** 唯一持有凭证的地方。toString 不含内容（[DestinationCredential] 自己脱敏）。 */
    data class Shown(val credential: DestinationCredential) : CredentialUi

    data class NotAuthenticated(val result: AuthResult) : CredentialUi

    data class Failed(val failure: ServiceFailure) : CredentialUi
}

enum class RevokeUi { IDLE, CONFIRMING, WORKING }

data class DestinationDetailUiState(
    val destination: DestinationItem? = null,
    val items: List<ItemStatusRow> = emptyList(),
    val itemsLoading: Boolean = false,
    val credential: CredentialUi = CredentialUi.Hidden,
    val revoke: RevokeUi = RevokeUi.IDLE,
    val problem: ServiceFailure? = null,
    /** 缓存已经读过一次：false 时 [destination] 为 null 只是“还没读到”，不是“找不到”。 */
    val loaded: Boolean = false,
)

class DestinationDetailViewModel(
    private val destinationId: String,
    private val controller: DestinationController,
    private val texts: ItemTexts,
    private val scope: CoroutineScope,
    private val log: SyncLog = SyncLog.None,
    /** 本机之外的变化（比如同步把一条文字送到了网关）：每发出一个值就重读一遍状态。 */
    refreshOn: Flow<Unit> = emptyFlow(),
) : ViewModel() {
    private val items = MutableStateFlow<List<ItemStatusRow>>(emptyList())
    private val itemsLoading = MutableStateFlow(false)
    private val credential = MutableStateFlow<CredentialUi>(CredentialUi.Hidden)
    private val revoke = MutableStateFlow(RevokeUi.IDLE)
    private val problem = MutableStateFlow<ServiceFailure?>(null)

    /** 页面离开一次加一：生成凭证的请求在途时页面离开了，之后才到的凭证不能再显示出来。 */
    @Volatile private var generation = 0

    /** 刷新状态的触发：合并成一次（正在刷新时再来的只记一次），两次之间留一点间隔——缓存每变一下都会触发，不能每次都去敲网关。 */
    private val triggers = Channel<Unit>(Channel.CONFLATED)

    private class Operations(val credential: CredentialUi, val revoke: RevokeUi, val problem: ServiceFailure?)

    val state: StateFlow<DestinationDetailUiState> = combine(
        controller.destinations.map { list -> list.firstOrNull { it.destinationId == destinationId }?.toItem() },
        items,
        itemsLoading,
        combine(credential, revoke, problem) { credential, revoke, problem -> Operations(credential, revoke, problem) },
    ) { destination, items, loading, operations ->
        DestinationDetailUiState(destination, items, loading, operations.credential, operations.revoke, operations.problem, loaded = true)
    }.stateIn(scope, SharingStarted.Eagerly, DestinationDetailUiState())

    init {
        scope.launch {
            for (trigger in triggers) {
                loadItems()
                delay(MIN_GAP_MS)
            }
        }
        scope.launch { controller.destinations.collect { triggers.trySend(Unit) } } // 缓存变了（新文字、新的决定）：重读一遍
        scope.launch { refreshOn.collect { triggers.trySend(Unit) } }
    }

    fun refreshStatuses() {
        triggers.trySend(Unit)
    }

    // ---- 凭证 ----

    /** 点“生成凭证”：从没签发过、或凭证已被撤销（暂停，没有旧凭证可换）就直接开始；签发过且还有效的是轮换，先让用户确认旧凭证会立刻失效。 */
    fun requestCredential() {
        val destination = state.value.destination ?: return
        if (!destination.active) return
        when (credential.value) {
            CredentialUi.Working, is CredentialUi.Shown, CredentialUi.ConfirmRotate -> return
            else -> Unit
        }
        if (destination.credentialIssued && !destination.suspended) credential.value = CredentialUi.ConfirmRotate else issue()
    }

    fun confirmRotate() {
        if (credential.value == CredentialUi.ConfirmRotate) issue()
    }

    fun cancelRotate() {
        if (credential.value == CredentialUi.ConfirmRotate) credential.value = CredentialUi.Hidden
    }

    /** 用户把凭证复制走了：抹掉。 */
    fun credentialCopied() = wipeCredential()

    /** 用户关掉凭证面板：抹掉。 */
    fun dismissCredential() = wipeCredential()

    /** 页面离开（回到列表、切到别的页、被销毁）：抹掉凭证，丢弃还在路上的。 */
    fun leave() {
        generation++
        wipeCredential()
    }

    /**
     * 应用退到后台（页面还在，但看不见了）：正在显示的凭证抹掉，回来时看到的和进程被杀后一样是空的。
     * 还在路上的不丢：核验框可能盖住了页面，而网关已经签发（轮换时旧凭证已经失效），丢了就找不回来。
     */
    fun backgrounded() {
        if (credential.value is CredentialUi.Shown) wipeCredential()
    }

    private fun wipeCredential() {
        credential.value = CredentialUi.Hidden
    }

    private fun issue() {
        credential.value = CredentialUi.Working
        val mine = generation
        scope.launch {
            val outcome = try {
                controller.issueCredential(destinationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log.event("destination.credential.crashed ${failure::class.simpleName}")
                if (mine == generation) credential.value = CredentialUi.Failed(ServiceFailure.Interrupted)
                return@launch
            }
            if (mine != generation) return@launch // 等待期间页面离开了：没人看得到它，不显示，不留
            credential.value = when (outcome) {
                is CredentialOutcome.Issued -> CredentialUi.Shown(outcome.credential.credential)
                is CredentialOutcome.NotAuthenticated -> CredentialUi.NotAuthenticated(outcome.result)
                is CredentialOutcome.Failed -> CredentialUi.Failed(outcome.failure)
            }
            triggers.trySend(Unit) // 签发过凭证了：缓存里的“已签发”标志变了，顺带刷新
        }
    }

    // ---- 撤销 ----

    fun requestRevoke() {
        if (state.value.destination?.active == true && revoke.value == RevokeUi.IDLE) revoke.value = RevokeUi.CONFIRMING
    }

    fun cancelRevoke() {
        if (revoke.value == RevokeUi.CONFIRMING) revoke.value = RevokeUi.IDLE
    }

    fun confirmRevoke() {
        if (!revoke.compareAndSet(RevokeUi.CONFIRMING, RevokeUi.WORKING)) return
        scope.launch {
            try {
                when (val result = controller.revoke(destinationId)) {
                    is ServiceResult.Ok -> wipeCredential() // 目的地都撤销了，凭证已经没用
                    is ServiceResult.Failed -> problem.value = result.failure
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log.event("destination.revoke.crashed ${failure::class.simpleName}")
                problem.value = ServiceFailure.Interrupted
            } finally {
                revoke.value = RevokeUi.IDLE
            }
            triggers.trySend(Unit)
        }
    }

    fun dismissProblem() {
        problem.value = null
    }

    override fun onCleared() {
        generation++
        credential.value = CredentialUi.Hidden
        scope.cancel()
    }

    private suspend fun loadItems() {
        itemsLoading.value = true
        try {
            items.value = controller.deliveries(destinationId, ITEM_LIMIT).map { it.toRow() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.event("destination.items.crashed ${failure::class.simpleName}")
        } finally {
            itemsLoading.value = false
        }
    }

    private suspend fun ItemDelivery.toRow() = ItemStatusRow(
        key = item.key,
        excerpt = texts.textOf(item.key)?.let(::excerptOf),
        intent = item.intent,
        createdAt = item.createdAt,
        view = view,
    )

    companion object {
        const val ITEM_LIMIT = 20
        private const val MIN_GAP_MS = 500L
        private const val EXCERPT_CHARS = 60

        internal fun excerptOf(text: String): String {
            val flat = text.replace(Regex("\\s+"), " ").trim()
            val cut = flat.codePointCount(0, flat.length)
            return if (cut <= EXCERPT_CHARS) flat else flat.substring(0, flat.offsetByCodePoints(0, EXCERPT_CHARS)) + "…"
        }
    }
}
