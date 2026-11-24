package app.auri.sync.gateway

import app.auri.capture.testing.RepoContracts
import app.auri.sync.http.HttpRequest
import app.auri.sync.http.TransportFailure
import app.auri.sync.pairing.DeviceToken
import app.auri.sync.pairing.GatewayAddress
import app.auri.sync.testing.ScriptedExchange
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手机只和说合同 1.2 的网关说话（评审 R7）。网关通告的版本（GET /v1/capabilities）是唯一的依据：
 * 通告里有 1.2 才兼容；通告的全是更旧的才是“太旧”（界面说“请先更新网关”）；其余——更新的、读不懂的——不冤枉网关，也不放行。
 */
class ContractVerdictTest {
    private val address = GatewayAddress("192.168.1.20", 8443)
    private val token = DeviceToken.parse("fixture-device-token-not-a-real-secret-0001")!!

    /** 合同自带的样本，只把通告的版本换成测试要的。 */
    private fun capabilities(vararg versions: String): String {
        val sample = RepoContracts.json("fixtures/messages/valid/gateway.capabilities_response.v1.json").jsonObject.getValue("instance").jsonObject
        val advertised = JsonArray(versions.map { JsonPrimitive(it) })
        return JsonObject(sample + ("supported_schema_versions" to advertised)).toString()
    }

    private fun verdictFor(body: String, status: Int = 200): Pair<GatewayResult<ContractVerdict>, ScriptedExchange> {
        val exchange = ScriptedExchange { _, _ -> ScriptedExchange.json(status, body) }
        return runBlocking { GatewayClient(exchange).contractVerdict(address, token) } to exchange
    }

    private fun verdict(vararg versions: String): ContractVerdict =
        (verdictFor(capabilities(*versions)).first as? GatewayResult.Success)?.value ?: error("期望 Success")

    @Test
    fun `通告里有手机要的 1_2 就兼容，另外还通告旧版本也一样`() {
        assertEquals(ContractVerdict.COMPATIBLE, verdict("1.2"))
        assertEquals(ContractVerdict.COMPATIBLE, verdict("1.1", "1.2"))
    }

    @Test
    fun `通告的全是更旧的版本才是太旧：1_1、1_0，或两者都有`() {
        assertEquals(ContractVerdict.TOO_OLD, verdict("1.1"))
        assertEquals(ContractVerdict.TOO_OLD, verdict("1.0"))
        assertEquals(ContractVerdict.TOO_OLD, verdict("1.0", "1.1"))
    }

    @Test
    fun `无法判断的不放行也不冤枉：只通告更新的版本、新旧都有但没有 1_2、通告了读不懂的版本号`() {
        assertEquals(ContractVerdict.UNCONFIRMED, verdict("1.3"))
        assertEquals(ContractVerdict.UNCONFIRMED, verdict("1.1", "1.3"))
        assertEquals(ContractVerdict.UNCONFIRMED, verdict("banana"))
    }

    @Test
    fun `太旧的网关回答里自己的 schema_version 是旧的，也照样读出它通告的版本（不能因为版本字段读不懂就说不清是网关太旧）`() {
        val oldGateway = capabilities("1.1").replace("\"schema_version\":\"1.2\"", "\"schema_version\":\"1.1\"")
        assertTrue("样本里要真有旧的版本字段", oldGateway.contains("\"schema_version\":\"1.1\""))

        assertEquals(ContractVerdict.TOO_OLD, (verdictFor(oldGateway).first as GatewayResult.Success).value)
    }

    @Test
    fun `读不懂的回答不是判决：通告字段缺失或类型不对是 Garbled，错误是 Rejected，断线是 TransportFailed`() {
        assertTrue(verdictFor("""{"schema_version":"1.2"}""").first is GatewayResult.Garbled)
        assertTrue(verdictFor("""{"supported_schema_versions":"1.2"}""").first is GatewayResult.Garbled)
        assertTrue(verdictFor("not json at all").first is GatewayResult.Garbled)
        val rejected = verdictFor("""{"schema_version":"1.2","code":"GATEWAY_AUTH_INVALID","message":"x","retryable":false,"required_action":null,"operation_id":null}""", status = 401).first
        assertEquals(401, (rejected as GatewayResult.Rejected).status)
        val cut = ScriptedExchange { _, _ -> throw TransportFailure.Unreachable() }
        assertTrue(runBlocking { GatewayClient(cut).contractVerdict(address, token) } is GatewayResult.TransportFailed)
    }

    @Test
    fun `问版本只读不改：GET、带设备令牌、不带请求体，路径是 capabilities`() {
        val (_, exchange) = verdictFor(capabilities("1.2"))

        val request: HttpRequest = exchange.calls.single().second
        assertEquals("GET", request.method)
        assertEquals("/v1/capabilities", request.path)
        assertEquals("Bearer fixture-device-token-not-a-real-secret-0001", request.headers["Authorization"])
        assertEquals(null, request.body)
    }
}
