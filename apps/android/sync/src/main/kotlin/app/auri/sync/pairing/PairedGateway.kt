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
 * 用户同意把文字发给的那台网关：网关编号加公钥指纹（复评 R3，SEC-002 把同意限定在“指定的网关”上）。
 * 两样都得对：同一个编号换了公钥、同一把公钥换了编号，都是另一台网关，原来的同意对它不算数。
 */
data class GatewayIdentity(val gatewayId: String, val keySha256: String)

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
) {
    /** 文字授权按它认人（见 [GatewayIdentity]）。 */
    val identity: GatewayIdentity get() = GatewayIdentity(gatewayId, pin.hex)

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
