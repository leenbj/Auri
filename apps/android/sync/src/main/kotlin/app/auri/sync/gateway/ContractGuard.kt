package app.auri.sync.gateway

import app.auri.capture.contract.ErrorCode
import app.auri.sync.http.TransportFailure
import app.auri.sync.pairing.GatewayAddress
import app.auri.sync.pairing.PairedGateway

/** [ContractGuard.check] 的结论：调用方各按自己的上下文处置（同步引擎停任务、管理页回报、追加 Delivery 退避）。 */
enum class ContractAnswer {
    /** 网关会说手机要的合同版本：可以发数据请求。 */
    COMPATIBLE,

    /** 网关说的合同版本比手机要的旧：要用户更新网关。一个数据请求都不能发。 */
    TOO_OLD,

    /** 没问成：连不上、网关出错、回答读不懂，或通告了无法判断的版本。什么都不发，也不能因此说网关“太旧”。 */
    UNCONFIRMED,

    /** 令牌被拒：和别处一样是连接问题，要重新配对，不是网关太旧。 */
    AUTH_REJECTED,

    /** 对方的公钥与配对时记下的不符。 */
    KEY_MISMATCH,
}

/**
 * 手机只和说合同 1.2 的网关说话（评审 R7）：联系网关传任何东西（入库、查状态、追加 Delivery、Destination 管理）之前，
 * 先对这份配对问一次网关会哪些版本。以前遇到没升级的网关，手机照发，网关回 422，每条文字都被当成“请求不合规”永久拒绝；现在是一个数据请求都不发。
 *
 * 只把“兼容”记在内存里：同一份配对、同一个进程里只问一次；进程重启或重新配对之后重新问（网关可能已经被换成旧的）。
 * 太旧、没问成都不记——前者要在用户更新网关之后自己恢复，后者下次再问。同步引擎与管理页共用同一个实例（见 SyncRuntime）。
 *
 * shortcut: 兼容的结论在进程活着期间一直有效；同一进程里网关被降级、而手机没有重启的话要到下次重启才发现。有真实的降级场景再加存活期限。
 */
class ContractGuard {
    private val lock = Any()
    private var verified: PairedGateway? = null

    suspend fun check(gateway: PairedGateway, address: GatewayAddress, client: GatewayClient): ContractAnswer {
        if (synchronized(lock) { verified?.isSamePairing(gateway) == true }) return ContractAnswer.COMPATIBLE
        val answer = when (val result = client.contractVerdict(address, gateway.token)) {
            is GatewayResult.Success -> when (result.value) {
                ContractVerdict.COMPATIBLE -> ContractAnswer.COMPATIBLE
                ContractVerdict.TOO_OLD -> ContractAnswer.TOO_OLD
                ContractVerdict.UNCONFIRMED -> ContractAnswer.UNCONFIRMED
            }

            is GatewayResult.Rejected -> if (result.rejectsDevice()) ContractAnswer.AUTH_REJECTED else ContractAnswer.UNCONFIRMED
            is GatewayResult.Garbled -> ContractAnswer.UNCONFIRMED
            is GatewayResult.TransportFailed -> if (result.failure is TransportFailure.KeyMismatch) ContractAnswer.KEY_MISMATCH else ContractAnswer.UNCONFIRMED
        }
        if (answer == ContractAnswer.COMPATIBLE) synchronized(lock) { verified = gateway }
        return answer
    }

    private fun GatewayResult.Rejected.rejectsDevice() = status == 401 || code == ErrorCode.GATEWAY_AUTH_REVOKED || code == ErrorCode.GATEWAY_AUTH_INVALID
}
