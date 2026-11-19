package app.auri.sync.testing

import app.auri.sync.gateway.RevisionSubmission
import app.auri.sync.pairing.DeviceToken
import app.auri.sync.pairing.GatewayAddress
import app.auri.sync.pairing.NeedsUser
import app.auri.sync.pairing.PairedGateway
import app.auri.sync.tls.SpkiPin
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** 取自合同样本的标识与一枚假令牌（不是任何真实密钥）。 */
object SyncSamples {
    const val GATEWAY_ID = "fa1b98ec-1bfb-53b3-85a3-4d36b680fe6e"
    const val DEVICE_ID = "ef7b1358-df31-520f-90f3-d98fc5173b46"
    const val TOKEN_TEXT = "fixture-device-token-not-a-real-secret-0001"
    const val PAIRING_CODE = "FIXTURE-PAIRING-CODE-NOT-A-SECRET"
    const val RECORD_ID = "a4a7c08d-2767-50d6-9238-6fa277033a49"
    const val OTHER_RECORD_ID = "b5b8d19e-3878-5ae7-a349-7fb388144b5a"
    val token: DeviceToken = DeviceToken.parse(TOKEN_TEXT)!!
    val NAS_ADDRESS = GatewayAddress("study-pc.local", 8443)
    val LAN_ADDRESS = GatewayAddress("192.168.1.20", 8443)

    fun pairedGateway(
        pin: SpkiPin = TlsFixtures.gatewayA.pin,
        addresses: List<GatewayAddress> = listOf(NAS_ADDRESS, LAN_ADDRESS),
        lastGoodAddress: GatewayAddress? = null,
        needsUser: NeedsUser? = null,
        token: DeviceToken = this.token,
        pairedAt: Instant = Instant.parse("2026-11-15T02:05:00Z"),
    ) = PairedGateway(GATEWAY_ID, DEVICE_ID, pin, addresses, lastGoodAddress, token, pairedAt, needsUser)

    fun sha256Hex(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /**
     * 引擎测试用的请求：只有 FakeGateway 与引擎要看的字段（record_id、revision、content.text_sha256）。
     * 它不符合 revision.schema.json；符合合同的请求体由 SubmissionBuilder 生成，并有自己的校验测试。
     */
    fun submission(recordId: String = RECORD_ID, revision: Int = 1, text: String = "你好，这是一条测试口述"): RevisionSubmission {
        val sha = sha256Hex(text)
        val body = buildJsonObject {
            put("schema_version", "1.1")
            put("record_id", recordId)
            put("revision", revision)
            putJsonObject("content") {
                put("text", text)
                put("text_sha256", sha)
            }
        }
        return RevisionSubmission(recordId, revision, sha, body.toString().toByteArray(Charsets.UTF_8))
    }
}
