package app.auri.sync.pairing

import app.auri.sync.tls.SpkiPin
import java.time.Instant

/**
 * 连接层面“要用户处理”的事实。它不属于某一条同步任务：公钥不符、令牌被拒，对所有任务都一样成立，
 * 在用户重新配对（或确认处理）之前，不能再向网关发任何带令牌的请求。
 *
 * [GATEWAY_TOO_OLD]（评审 R7）是其中唯一不用重新配对的：网关说的合同版本比手机要的旧，处置是更新网关。
 * 在那之前一个数据请求都不发；更新之后下一轮同步问出来、自己清掉这个标记（见 ContractGuard 与 SyncEngine.runOnce）。
 */
enum class NeedsUser { KEY_MISMATCH, AUTH_REJECTED, GATEWAY_TOO_OLD }

/**
 * 用户同意把文字发给的那台网关：网关编号加公钥指纹（复评 R3，SEC-002 把同意限定在“指定的网关”上），再加上这一段配对的世代。
 * 编号与公钥两样都得对：同一个编号换了公钥、同一把公钥换了编号，都是另一台网关，原来的同意对它不算数。
 *
 * [pairingEpoch]（复评三 S5）：这一段配对的世代。换成别的网关、取消配对、配对记录丢了之后重新配对，都会开始新的一段，世代换新；
 * 同一台网关重新配对（中间没换过）沿用原来的。同意记着它是在哪一段配对里给的：就算换配对时的主动清理没跑成，
 * A→B→A 之后 A 的旧同意也对不上新一段的世代，不会复活——失败时关着（不放行），不靠清理一定成功。
 * 世代随配对记录一起、一次原子写入落盘（见 FilePairingStore）。升级前的记录没有世代，读成 [LEGACY_EPOCH]。
 */
data class GatewayIdentity(val gatewayId: String, val keySha256: String, val pairingEpoch: String = LEGACY_EPOCH) {
    /**
     * 发送账认“Destination 缓存与排着的目标属于哪台网关”的写法（复评三 S4）：编号加公钥指纹，缺一不可。
     * 同一个编号换了公钥是另一台网关（它那里没有 A 的 Destination），缓存与排着的目标都不能沿用。
     * 不含世代：同一台网关的 Destination 不因为重新配对而变（换了网关再换回来，换网关那一步已经作废过缓存）。
     */
    val ledgerKey: String get() = "$gatewayId $keySha256"

    companion object {
        /** 升级前写下的配对记录（与那时的授权）没有世代：统一读成这个值。 */
        const val LEGACY_EPOCH = "legacy"

        private val EPOCH = Regex("^[A-Za-z0-9._-]{1,64}$")

        /** 世代要能原样写进授权那一行（不含空白）：落盘的记录里读到不合规的值，整条记录或整行授权作废。 */
        fun isValidEpoch(text: String): Boolean = EPOCH.matches(text)
    }
}

/** 配对的结果：手机认识的那台网关。整个记录（含令牌、公钥指纹、地址）只以密文落盘，见 FilePairingStore。 */
data class PairedGateway(
    val gatewayId: String,
    val deviceId: String,
    val pin: SpkiPin,
    /** 配对时二维码里的地址（按二维码给的顺序）。 */
    val addresses: List<GatewayAddress>,
    /** 最近一次实际连上的地址；下次先连它。 */
    val lastGoodAddress: GatewayAddress?,
    val token: DeviceToken,
    val pairedAt: Instant,
    val needsUser: NeedsUser? = null,
    /** 这一段配对的世代（复评三 S5，见 [GatewayIdentity.pairingEpoch]）：只由 PairingService 在配对时定，其余地方只带着它走。 */
    val pairingEpoch: String = GatewayIdentity.LEGACY_EPOCH,
) {
    /** 文字授权按它认人（见 [GatewayIdentity]）。 */
    val identity: GatewayIdentity get() = GatewayIdentity(gatewayId, pin.hex, pairingEpoch)

    /** 是不是同一份配对：同一台设备、同一次配对时刻、同一个固定公钥。用户重新配对、改配别的网关之后就不是了。 */
    fun isSamePairing(other: PairedGateway) = deviceId == other.deviceId && pairedAt == other.pairedAt && pin == other.pin

    // 数据类默认的 toString 会打出所有字段：这里只留不含机密、不含网络位置的几项
    override fun toString() = "PairedGateway(gatewayId=$gatewayId, deviceId=$deviceId, needsUser=$needsUser)"
}

/** 本机的配对状态。[Lost] = 有配对记录但读不出来（被改动、密钥丢失）：等同没配对，但要告诉用户“需要重新配对”。 */
sealed interface PairingState {
    data object NotPaired : PairingState

    data object Lost : PairingState

    data class Paired(val gateway: PairedGateway) : PairingState
}

interface PairingStore {
    suspend fun load(): PairingState

    /** 原子地替换配对记录：要么是旧的，要么是新的，不会出现写了一半。 */
    suspend fun save(gateway: PairedGateway)

    /**
     * 在同一把锁里读出当前记录、套用 [transform]、写回。[transform] 返回 null 表示不改（不重写文件）。
     * 返回是否写入了。没配对或记录已丢失时什么也不做：不能凭空造出一条。
     * 引擎只能经它改记录（记最近连上的地址、打上“要用户处理”），这样晚到的旧观察不会覆盖用户刚刚重新配对写入的新记录。
     */
    suspend fun update(transform: (PairedGateway) -> PairedGateway?): Boolean

    /**
     * 在配对着的仍是 [gateway] 这一份配对（[isSamePairing]：同一设备、同一配对时刻、同一把公钥）的前提下执行 [block]，返回它的结果；
     * 已经不是这一份（换了、取消了、没配对、记录丢了）就不执行，返回 null。
     * 判断与 [block] 在**同一把锁**里：[save]、[update]、[clear] 都等着，所以“刚判断完、还没做，用户重新配对了”这个缝不存在
     * （复评 R5：旧配对的残余——一轮同步在网络上等的时候用户换了网关——不能改新配对的账）。
     * [block] 里不许再调用这个存储（同一把锁，会死锁）；它应当很短（一次本地写）。
     */
    suspend fun <T : Any> whileCurrent(gateway: PairedGateway, block: suspend () -> T): T?

    suspend fun clear()
}

/**
 * 给这份配对打上“网关太旧”（评审 R7）：只在它还是当前的配对、而且没有别的连接问题时才写（公钥不符、令牌被拒是更重的问题，不能被覆盖）。
 * 返回这份配对现在还是不是当前的配对（用户途中重新配对了就是 false，调用方要把结论作废）。已经带着这个标记时不重写。
 */
suspend fun PairingStore.markGatewayTooOld(gateway: PairedGateway): Boolean {
    update { current -> if (current.isSamePairing(gateway) && current.needsUser == null) current.copy(needsUser = NeedsUser.GATEWAY_TOO_OLD) else null }
    return (load() as? PairingState.Paired)?.gateway?.isSamePairing(gateway) == true
}

/** 网关已经更新：清掉“网关太旧”的标记。只清这一个标记、只对这一份配对，不需要重新配对。 */
suspend fun PairingStore.clearGatewayTooOld(gateway: PairedGateway) {
    update { current -> if (current.isSamePairing(gateway) && current.needsUser == NeedsUser.GATEWAY_TOO_OLD) current.copy(needsUser = null) else null }
}
