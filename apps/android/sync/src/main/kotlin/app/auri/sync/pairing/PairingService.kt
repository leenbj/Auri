package app.auri.sync.pairing

import app.auri.capture.contract.ErrorCode
import app.auri.capture.coordinator.Clock
import app.auri.sync.SyncLog
import app.auri.sync.discovery.GatewayDiscovery
import app.auri.sync.discovery.Step
import app.auri.sync.discovery.Walk
import app.auri.sync.discovery.walkAddresses
import app.auri.sync.gateway.DeviceInfo
import app.auri.sync.gateway.GatewayClient
import app.auri.sync.gateway.GatewayResult
import app.auri.sync.gateway.RedeemResponse
import app.auri.sync.http.HttpExchangeFactory
import app.auri.sync.http.TransportFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** 配对的结果。除 [Paired] 外都没有改动本机的配对记录。 */
sealed interface PairingOutcome {
    data class Paired(val gateway: PairedGateway) : PairingOutcome

    /** 二维码已过期（按手机时钟判断，没有联网）。 */
    data object Expired : PairingOutcome

    /** 二维码里的地址与 mDNS 都找不到网关。 */
    data object NotFound : PairingOutcome

    /** 对方的公钥与二维码里的指纹不符：不是二维码对应的那台网关。配对码没有发给它。 */
    data object KeyMismatch : PairingOutcome

    /** 网关说配对码无效、已过期或已被使用。 */
    data object CodeRejected : PairingOutcome

    /** 网关回了别的错误。 */
    data class GatewayError(val status: Int, val code: ErrorCode?) : PairingOutcome

    /** 请求可能已经送到，但没拿到可读的回答：配对码可能已被消耗，不能自动重试，请用户在网关上重新生成配对码。 */
    data object Uncertain : PairingOutcome

    /** 网关回答的 gateway_id 不是二维码里的那一个。 */
    data object WrongGateway : PairingOutcome

    /** 配对成功但令牌没能存下来（磁盘或密钥问题）：这台设备在网关上已有记录，用户需要在网关上撤销它并重新配对。 */
    data object NotSaved : PairingOutcome
}

/**
 * 扫码配对。二维码里的指纹是这次连接唯一的信任根：配对码只会发给出示该公钥的服务器。
 *
 * 一次兑换的结局只有三类，处置不同：
 * - 没连上、或对方公钥不符：请求没有发出，配对码还在，换下一个地址（最后用 mDNS）继续试；
 * - 网关明确拒绝：停下，如实报告；
 * - 请求可能已经送到却没拿到可读的回答（断线、超时、2xx 读不懂）：配对码可能已被消耗，**停下，绝不自动重发**。
 */
class PairingService(
    private val store: PairingStore,
    private val http: HttpExchangeFactory,
    private val discovery: GatewayDiscovery,
    private val clock: Clock,
    private val device: DeviceInfo,
    private val log: SyncLog = SyncLog.None,
) {
    /** 一个地址上兑换的终局：要么拿到了令牌，要么有个必须停下的结论。 */
    private sealed interface Attempt {
        data class Redeemed(val address: GatewayAddress, val response: RedeemResponse) : Attempt

        data class Ended(val outcome: PairingOutcome) : Attempt
    }

    suspend fun pair(payload: PairingPayload): PairingOutcome {
        if (payload.isExpired(clock.now())) return finish(PairingOutcome.Expired)
        log.event("pairing.start addresses=${payload.addresses.size}")
        val client = GatewayClient(http.forPin(payload.publicKeySha256))

        val walk = walkAddresses(payload.addresses, discovery, payload.gatewayId, log) { address ->
            when (val result = client.redeem(address, payload.pairingCode, device)) {
                is GatewayResult.Success -> Step.Done(Attempt.Redeemed(address, result.value))
                is GatewayResult.TransportFailed -> when (result.failure) {
                    is TransportFailure.Unreachable -> Step.Unreachable
                    is TransportFailure.KeyMismatch -> Step.Mismatch
                    is TransportFailure.Interrupted -> Step.Done(Attempt.Ended(PairingOutcome.Uncertain))
                }
                is GatewayResult.Garbled -> Step.Done(Attempt.Ended(PairingOutcome.Uncertain))
                is GatewayResult.Rejected -> Step.Done(
                    Attempt.Ended(if (result.code == ErrorCode.PAIRING_CODE_INVALID) PairingOutcome.CodeRejected else PairingOutcome.GatewayError(result.status, result.code)),
                )
            }
        }
        return finish(
            when (walk) {
                Walk.Mismatched -> PairingOutcome.KeyMismatch
                Walk.NothingReachable -> PairingOutcome.NotFound
                is Walk.Found -> when (val attempt = walk.value) {
                    is Attempt.Ended -> attempt.outcome
                    is Attempt.Redeemed -> remember(payload, attempt.address, attempt.response)
                }
            },
        )
    }

    private suspend fun remember(payload: PairingPayload, address: GatewayAddress, response: RedeemResponse): PairingOutcome {
        if (response.gatewayId != payload.gatewayId) return PairingOutcome.WrongGateway
        val gateway = PairedGateway(
            gatewayId = response.gatewayId,
            deviceId = response.deviceId,
            pin = payload.publicKeySha256,
            addresses = (payload.addresses + address).distinct(),
            lastGoodAddress = address,
            token = response.deviceToken,
            pairedAt = response.pairedAt,
        )
        return try {
            // 令牌只会出现这一次：网关已经签发，调用方此刻被取消也必须把它存下来
            withContext(NonCancellable) { store.save(gateway) }
            PairingOutcome.Paired(gateway)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PairingOutcome.NotSaved
        }
    }

    private fun finish(outcome: PairingOutcome): PairingOutcome {
        log.event("pairing.result=${outcome::class.simpleName}")
        return outcome
    }
}
