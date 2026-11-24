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
 * 兼容的记忆**不是永久的**（再评 S6）：同一个进程里网关可能被换回旧版本，所以任何“网关可能变了”的迹象都要 [invalidate]：
 * 连接断过（连不上、传输中断、平台报告网络变了）、请求被当成“不合规”拒绝（旧网关对 1.2 的请求回的就是 422）。作废之后下一次 [check] 重新问。
 *
 * 作废时**已经在路上的询问**（第四轮 F5）：它的答复是作废之前的网关说的，对谁都不算数——不记住，也不交给提问的人（不论答复是兼容、太旧、被拒还是没问成）。
 * 第三轮 S6 只做到“不记住”，提问的人仍拿着变之前的“兼容”去发数据请求，可网关可能已被换成旧版本，整条文字就被永久拒绝。
 * 现在 [check] 重新问；世代一直在变，最多问 [MAX_QUESTIONS] 次就回“没问成”（什么数据请求也不发，下一轮再来）。
 */
class ContractGuard {
    private val lock = Any()
    private var verified: PairedGateway? = null

    /** 作废的世代：[invalidate] 加一。[askOnce] 在发问之前记下世代，回来时世代变了，这份答复就是作废之前的网关说的：不记、不交。 */
    private var generation = 0L

    /** 作废兼容的记忆：下一次 [check] 重新问网关。已经在路上的那一次提问，它的答复不算数（见类说明），[check] 会重新问。 */
    fun invalidate() {
        synchronized(lock) {
            verified = null
            generation += 1
        }
    }

    suspend fun check(gateway: PairedGateway, address: GatewayAddress, client: GatewayClient): ContractAnswer {
        repeat(MAX_QUESTIONS) {
            askOnce(gateway, address, client)?.let { return it }
        }
        return ContractAnswer.UNCONFIRMED
    }

    /** 问一次。问的期间世代变了就回 null：这份答复是作废之前的网关说的，不记也不交。 */
    private suspend fun askOnce(gateway: PairedGateway, address: GatewayAddress, client: GatewayClient): ContractAnswer? {
        val asked = synchronized(lock) {
            if (verified?.isSamePairing(gateway) == true) return ContractAnswer.COMPATIBLE
            generation
        }
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
        return synchronized(lock) {
            if (generation != asked) return null
            if (answer == ContractAnswer.COMPATIBLE) verified = gateway
            answer
        }
    }

    private fun GatewayResult.Rejected.rejectsDevice() = status == 401 || code == ErrorCode.GATEWAY_AUTH_REVOKED || code == ErrorCode.GATEWAY_AUTH_INVALID

    private companion object {
        /** 网络一直在变时，一次 [check] 最多问几次：问到这个数还在变就回“没问成”，不无休止地问。 */
        const val MAX_QUESTIONS = 3
    }
}
