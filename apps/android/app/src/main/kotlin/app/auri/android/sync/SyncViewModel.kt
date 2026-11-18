package app.auri.android.sync

import androidx.lifecycle.ViewModel
import app.auri.sync.SyncController
import app.auri.sync.pairing.PairingOutcome
import app.auri.sync.pairing.PayloadParse
import app.auri.sync.pairing.PairingPayload
import app.auri.sync.status.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/*
 * ===================================================================================================================
 *  同步页（SyncScreen）的数据与操作：另一个人会在这上面做真正的界面，界面只需要读 SyncUiState、调 SyncViewModel 的方法。
 * ===================================================================================================================
 *
 * 读：`SyncViewModel.state: StateFlow<SyncUiState>`（唯一的可观察入口）。
 *
 *   SyncUiState
 *   ├─ status: SyncStatus?        同步状态汇总；null = 还在读本机的配对记录（只在刚启动的一瞬间）。
 *   │   ├─ headline               六种互斥的情形，界面必须分开显示，不能合并成一句“同步失败”：
 *   │   │     NOT_PAIRED          还没配对：文字只留在本机，不会发出去。
 *   │   │     NEEDS_USER          需要用户处理（causes 里是原因：公钥不符 / 令牌被拒 / 配对记录丢失 / 某一版被网关永久拒绝……）。
 *   │   │     RETRYING_LATER      稍后自动重试（网关答了可重试的错误，或请求发出后没拿到结果）。不需要用户做什么。
 *   │   │     WAITING_FOR_GATEWAY 等网关：这会儿连不上（不在同一网络、网关没开），连得上时自动继续。不需要用户做什么。
 *   │   │     SENDING             排队或正在发送。
 *   │   │     UP_TO_DATE          配了对，并且没有没入库的东西。
 *   │   ├─ causes                 NEEDS_USER 的原因（可能不止一个，顺序固定）。
 *   │   ├─ counts                 各状态的任务数（排队 / 发送中 / 等网关 / 稍后重试 / 需处理 / 已入库）。
 *   │   └─ nextRetryAt            等待重试的任务里最早的重试时间（RETRYING_LATER 时可以显示）。
 *   ├─ pairing: PairingProgress   配对这个动作的进展：Idle / Pairing / Failed(problem)。
 *   └─ manualEntryAvailable       true 才显示“手动输入配对载荷”：只有调试版为 true（模拟器没有摄像头）；release 版为 false。
 *
 * 写（都不阻塞，可在任何线程调，包括摄像头的分析线程）：
 *   - onQrText(text)             取景读到一段二维码文字（同一帧可以反复来）。只有合规的配对载荷才会开始配对；配对进行中重复的帧被忽略。
 *   - onManualPayload(text)      调试版手动输入的配对载荷；release 版什么也不做。
 *   - dismissPairingProblem()    关掉配对失败的提示。
 *   - syncNow()                  现在同步一轮（回到前台、用户点“现在同步”）。
 *
 * 规则：令牌、配对码、公钥指纹不进界面状态、不进日志（这里没有任何日志）；转录文字是数据，这里根本不读文字（SEC-004）。
 * ===================================================================================================================
 */

data class SyncUiState(
    val status: SyncStatus?,
    val pairing: PairingProgress,
    val manualEntryAvailable: Boolean,
)

/** 配对这个动作进行到哪了（与“同步有没有完成”无关）。 */
sealed interface PairingProgress {
    data object Idle : PairingProgress

    /** 已经把载荷交给配对流程：正在找网关、核对公钥、兑换配对码。 */
    data object Pairing : PairingProgress

    /** 没配成。[problem] 说明为什么，每种的处置不同（见 [PairingProblem]）。 */
    data class Failed(val problem: PairingProblem) : PairingProgress
}

/** 配对失败的原因，界面按它显示不同的话（用户该做什么不一样）。 */
enum class PairingProblem {
    /** 读到的二维码不是合规的 Auri 配对载荷。 */
    INVALID_QR,

    /** 二维码已过期（按手机时钟判断）：在网关上重新生成。 */
    EXPIRED,

    /** 二维码里的地址和 mDNS 都找不到网关：手机与网关得在同一个局域网。 */
    NOT_FOUND,

    /** 对方的公钥与二维码里的指纹不符：不是二维码对应的那台网关，配对码没有发给它。 */
    KEY_MISMATCH,

    /** 网关说配对码无效、已过期或已被用过：在网关上重新生成。 */
    CODE_REJECTED,

    /** 网关回了别的错误。 */
    GATEWAY_ERROR,

    /** 请求发出后没拿到明确的回答：配对码可能已被消耗，不能自动重试，需要在网关上重新生成。 */
    UNCERTAIN,

    /** 对方回答的网关编号不是二维码里的那一个。 */
    WRONG_GATEWAY,

    /** 在网关上配好了，但令牌没能存在本机：需要在网关上撤销这台设备后重新配对。 */
    NOT_SAVED,

    /** 配对流程出了没有预料到的错误。 */
    UNKNOWN,
}

class SyncViewModel(
    private val controller: SyncController,
    private val scope: CoroutineScope,
    private val manualEntryAvailable: Boolean,
) : ViewModel() {
    private val progress = MutableStateFlow<PairingProgress>(PairingProgress.Idle)

    /** 刚刚配成功的那张二维码的文字：取景还有几帧在路上时，同一张码再来不能触发第二次配对（它的配对码已经用掉了）。只在内存里，不对外。 */
    @Volatile private var lastPairedText: String? = null

    // 读到配对记录之前 status 是 null；配对的进展不用等它（先有 null 再有真实状态，combine 才不会卡在等第一份状态上）
    private val statuses: Flow<SyncStatus?> = controller.status.map<SyncStatus, SyncStatus?> { it }.onStart { emit(null) }

    val state: StateFlow<SyncUiState> = combine(statuses, progress) { status, pairing ->
        SyncUiState(status, pairing, manualEntryAvailable)
    }.stateIn(scope, SharingStarted.Eagerly, SyncUiState(null, PairingProgress.Idle, manualEntryAvailable))

    fun onQrText(text: String) {
        val current = progress.value
        if (current is PairingProgress.Pairing) return // 取景一秒会来几十帧，配对进行中只认第一帧
        if (text.trim() == lastPairedText) return
        val payload = when (val parsed = PairingPayload.parse(text)) {
            is PayloadParse.Valid -> parsed.payload
            is PayloadParse.Invalid -> {
                progress.compareAndSet(current, PairingProgress.Failed(PairingProblem.INVALID_QR))
                return
            }
        }
        // 别的线程上的另一帧抢先开始了就算了：只有一次配对在进行
        if (!progress.compareAndSet(current, PairingProgress.Pairing)) return
        scope.launch {
            val result = pair(payload)
            if (result == PairingProgress.Idle) lastPairedText = text.trim()
            progress.value = result
        }
    }

    fun onManualPayload(text: String) {
        if (manualEntryAvailable) onQrText(text)
    }

    fun dismissPairingProblem() {
        val current = progress.value
        if (current is PairingProgress.Failed) progress.compareAndSet(current, PairingProgress.Idle)
    }

    fun syncNow() = controller.syncNow()

    override fun onCleared() {
        scope.cancel()
    }

    private suspend fun pair(payload: PairingPayload): PairingProgress = try {
        when (val outcome = controller.pair(payload)) {
            is PairingOutcome.Paired -> PairingProgress.Idle
            PairingOutcome.Expired -> PairingProgress.Failed(PairingProblem.EXPIRED)
            PairingOutcome.NotFound -> PairingProgress.Failed(PairingProblem.NOT_FOUND)
            PairingOutcome.KeyMismatch -> PairingProgress.Failed(PairingProblem.KEY_MISMATCH)
            PairingOutcome.CodeRejected -> PairingProgress.Failed(PairingProblem.CODE_REJECTED)
            is PairingOutcome.GatewayError -> PairingProgress.Failed(PairingProblem.GATEWAY_ERROR)
            PairingOutcome.Uncertain -> PairingProgress.Failed(PairingProblem.UNCERTAIN)
            PairingOutcome.WrongGateway -> PairingProgress.Failed(PairingProblem.WRONG_GATEWAY)
            PairingOutcome.NotSaved -> PairingProgress.Failed(PairingProblem.NOT_SAVED)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        PairingProgress.Failed(PairingProblem.UNKNOWN) // 配对流程本身不该抛；抛了也不能让界面一直停在“正在配对”
    }
}
