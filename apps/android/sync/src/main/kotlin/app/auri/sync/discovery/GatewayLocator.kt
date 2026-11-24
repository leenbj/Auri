package app.auri.sync.discovery

import app.auri.sync.SyncLog
import app.auri.sync.gateway.GatewayClient
import app.auri.sync.gateway.GatewayResult
import app.auri.sync.http.HttpExchangeFactory
import app.auri.sync.http.TransportFailure
import app.auri.sync.pairing.GatewayAddress
import app.auri.sync.pairing.PairedGateway
import app.auri.sync.policy.RequestGate

/** 找网关的结果。 */
sealed interface Located {
    data class Reachable(val address: GatewayAddress) : Located

    /** 记下的地址和 mDNS 都找不到网关（离开了局域网、网关没开）。 */
    data object Unreachable : Located

    /** 没有任何地址通过，而且有地址出示了不符的公钥：网关换了密钥，或者有别的设备冒充。需要用户重新配对。 */
    data object KeyMismatch : Located
}

/**
 * 找到“现在连得上的网关地址”：先试最近连上的地址，再试配对时记下的地址，都不通才用 mDNS。
 * 每个地址都用一次不带令牌的健康检查探测（经过固定公钥的通道，所以冒充者在这一步就被拒绝）。
 */
class GatewayLocator(
    private val http: HttpExchangeFactory,
    private val discovery: GatewayDiscovery,
    private val log: SyncLog = SyncLog.None,
) {
    suspend fun locate(gateway: PairedGateway): Located = locate(gateway, RequestGate.Open) ?: error("不设防的门不会拦下任何请求")

    /**
     * 同 [locate]，但每个会发出请求的步骤（探测一个地址、问一次 mDNS）各自经 [gate] 交出去（第三轮 S12）：
     * 门中途不放行（授权被收回）就停在那一步，返回 null，之后一个请求也不发。
     */
    suspend fun locate(gateway: PairedGateway, gate: RequestGate): Located? {
        val client = GatewayClient(http.forPin(gateway.pin))
        val stored = listOfNotNull(gateway.lastGoodAddress) + gateway.addresses
        val walk = walkAddresses(stored, discovery, gateway.gatewayId, log, gate) { address ->
            when (val probe = client.health(address)) {
                is GatewayResult.Success -> Step.Done(address)
                is GatewayResult.TransportFailed -> if (probe.failure is TransportFailure.KeyMismatch) Step.Mismatch else Step.Unreachable
                is GatewayResult.Rejected, is GatewayResult.Garbled -> Step.Unreachable // 对方说话了但不是我们的网关服务
            }
        }
        val located = when (walk) {
            is Walk.Found -> Located.Reachable(walk.value)
            Walk.Mismatched -> Located.KeyMismatch
            Walk.NothingReachable -> Located.Unreachable
            Walk.Withheld -> null
        }
        log.event("locate.result=${located?.let { it::class.simpleName } ?: "Withheld"}")
        return located
    }
}
