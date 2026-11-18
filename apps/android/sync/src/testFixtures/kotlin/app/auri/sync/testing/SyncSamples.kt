package app.auri.sync.testing

import app.auri.sync.pairing.DeviceToken
import app.auri.sync.pairing.GatewayAddress
import app.auri.sync.pairing.NeedsUser
import app.auri.sync.pairing.PairedGateway
import app.auri.sync.tls.SpkiPin
import java.time.Instant

/** 取自合同样本的标识与一枚假令牌（不是任何真实密钥）。 */
object SyncSamples {
    const val GATEWAY_ID = "fa1b98ec-1bfb-53b3-85a3-4d36b680fe6e"
    const val DEVICE_ID = "ef7b1358-df31-520f-90f3-d98fc5173b46"
    const val TOKEN_TEXT = "fixture-device-token-not-a-real-secret-0001"
    const val PAIRING_CODE = "FIXTURE-PAIRING-CODE-NOT-A-SECRET"
    val token: DeviceToken = DeviceToken.parse(TOKEN_TEXT)!!
    val NAS_ADDRESS = GatewayAddress("study-pc.local", 8443)
    val LAN_ADDRESS = GatewayAddress("192.168.1.20", 8443)

    fun pairedGateway(
        pin: SpkiPin = TlsFixtures.gatewayA.pin,
        addresses: List<GatewayAddress> = listOf(NAS_ADDRESS, LAN_ADDRESS),
        lastGoodAddress: GatewayAddress? = null,
        needsUser: NeedsUser? = null,
    ) = PairedGateway(GATEWAY_ID, DEVICE_ID, pin, addresses, lastGoodAddress, token, Instant.parse("2026-11-15T02:05:00Z"), needsUser)
}
