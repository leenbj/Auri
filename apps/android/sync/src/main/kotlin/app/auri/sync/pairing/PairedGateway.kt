package app.auri.sync.pairing

import app.auri.sync.tls.SpkiPin
import java.time.Instant

/**
 * 连接层面“要用户处理”的事实。它不属于某一条同步任务：公钥不符、令牌被拒，对所有任务都一样成立，
 * 在用户重新配对（或确认处理）之前，不能再向网关发任何带令牌的请求。
 */
enum class NeedsUser { KEY_MISMATCH, AUTH_REJECTED }

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

    suspend fun clear()
}
