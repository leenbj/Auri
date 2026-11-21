package app.auri.sync.pairing

import app.auri.capture.testing.RepoContracts
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 21 第 1 项：二维码里的配对载荷必须按配对合同（pairing.schema.json#/$defs/payload）严格校验。
 * 二维码来自“摄像头看到的任何东西”，所以多一个字段、少一个字段、版本不对、地址里夹带换行，都当作无效，不猜、不容忍。
 * 合同样本直接从仓库的 contracts/ 读取（不是拷贝），样本一变这里就跟着变。
 */
class PairingPayloadTest {
    private val validJson: JsonObject = RepoContracts.json("fixtures/messages/valid/pairing.payload.lan-gateway.json")
        .jsonObject.getValue("instance").jsonObject

    private fun parse(json: JsonObject) = PairingPayload.parse(json.toString())

    private fun problemOf(json: JsonObject): PayloadProblem =
        (parse(json) as? PayloadParse.Invalid)?.problem ?: error("应当被拒绝，但被接受了：$json")

    private fun JsonObject.set(key: String, value: kotlinx.serialization.json.JsonElement) = JsonObject(this + (key to value))

    private fun JsonObject.without(key: String) = JsonObject(this - key)

    @Test
    fun `合同里的有效样本被接受，字段与样本逐个相等`() {
        val payload = (parse(validJson) as PayloadParse.Valid).payload

        // 期望值是样本文件里写死的字面值，不是从解析结果推出来的
        assertEquals("fa1b98ec-1bfb-53b3-85a3-4d36b680fe6e", payload.gatewayId)
        assertEquals(listOf(GatewayAddress("study-pc.local", 8443), GatewayAddress("192.168.1.20", 8443)), payload.addresses)
        assertEquals("_auri-gateway._tcp", payload.mdnsServiceType)
        assertEquals("eedd1d30512d98a7084435f20abdccb697f22dba6ed119f3bd6ef60d1cfef8b1", payload.publicKeySha256.hex)
        assertEquals("FIXTURE-PAIRING-CODE-NOT-A-SECRET", payload.pairingCode)
        assertEquals(Instant.parse("2026-11-15T02:10:00Z"), payload.expiresAt)
    }

    @Test
    fun `仓库里所有配对载荷样本：有效的接受，schema 无效的拒绝`() {
        val dirs = listOf("valid" to true, "schema-invalid" to false)
        var checked = 0
        for ((dir, shouldAccept) in dirs) {
            RepoContracts.contractsDir.resolve("fixtures/messages/$dir").listFiles { f -> f.name.startsWith("pairing.payload.") }!!.forEach { file ->
                val fixture = RepoContracts.json("fixtures/messages/$dir/${file.name}").jsonObject
                if (fixture.getValue("schema").jsonPrimitive.content != "pairing.schema.json#/\$defs/payload") return@forEach
                val result = PairingPayload.parse(fixture.getValue("instance").toString())
                assertEquals("${file.name}", shouldAccept, result is PayloadParse.Valid)
                checked += 1
            }
        }
        assertTrue("至少应当检查到有效与无效各一个样本，实际检查了 $checked 个", checked >= 3)
    }

    @Test
    fun `前后的空白不影响，但不是 JSON 或不是对象就拒绝`() {
        assertTrue(PairingPayload.parse("  \n" + validJson + "\n ") is PayloadParse.Valid)
        // kotlinx 把裸单词当作原始字面量解析，所以 "hello" 归为“不是对象”；两种说法对用户都是“这不是配对二维码”，要紧的是拒绝
        assertTrue(PairingPayload.parse("hello") is PayloadParse.Invalid)
        assertEquals(PayloadProblem.NOT_JSON, (PairingPayload.parse("") as PayloadParse.Invalid).problem)
        assertEquals(PayloadProblem.NOT_JSON, (PairingPayload.parse(validJson.toString() + " trailing") as PayloadParse.Invalid).problem)
        assertEquals(PayloadProblem.NOT_AN_OBJECT, (PairingPayload.parse("[1,2]") as PayloadParse.Invalid).problem)
        assertEquals(PayloadProblem.NOT_AN_OBJECT, (PairingPayload.parse("\"x\"") as PayloadParse.Invalid).problem)
    }

    @Test
    fun `过大的输入直接拒绝，不去解析`() {
        val huge = validJson.set("pairing_code", JsonPrimitive("x".repeat(8_000)))
        assertEquals(PayloadProblem.TOO_LARGE, (PairingPayload.parse(huge.toString()) as PayloadParse.Invalid).problem)
    }

    @Test
    fun `少一个字段、多一个字段都拒绝（合同 additionalProperties 为 false）`() {
        for (required in listOf("schema_version", "gateway_id", "addresses", "mdns_service_type", "public_key_sha256", "pairing_code", "expires_at")) {
            assertEquals("缺 $required", PayloadProblem.MISSING_FIELD, problemOf(validJson.without(required)))
        }
        assertEquals(PayloadProblem.UNKNOWN_FIELD, problemOf(validJson.set("tenant_id", JsonPrimitive("someone-else"))))
    }

    @Test
    fun `只认合同版本 1_2：别的版本号、数字形式的版本都拒绝`() {
        for (version in listOf("1.3", "2.0", "", "1.2.0")) {
            assertEquals(version, PayloadProblem.WRONG_SCHEMA_VERSION, problemOf(validJson.set("schema_version", JsonPrimitive(version))))
        }
        assertEquals(PayloadProblem.WRONG_SCHEMA_VERSION, problemOf(validJson.set("schema_version", JsonPrimitive(1.2))))
        assertEquals(PayloadProblem.WRONG_SCHEMA_VERSION, problemOf(validJson.set("schema_version", JsonPrimitive(1.1))))
    }

    @Test
    fun `更旧的合同版本（1_0、1_1）是网关太旧而不是二维码坏了：配对之前就能说清要先更新网关（评审 R7）`() {
        for (version in listOf("1.0", "1.1")) {
            assertEquals(version, PayloadProblem.GATEWAY_TOO_OLD, problemOf(validJson.set("schema_version", JsonPrimitive(version))))
        }
    }

    @Test
    fun `网关标识必须是小写 UUID`() {
        for (bad in listOf("FA1B98EC-1BFB-53B3-85A3-4D36B680FE6E", "fa1b98ec1bfb53b385a34d36b680fe6e", "not-a-uuid", "")) {
            assertEquals(bad, PayloadProblem.BAD_GATEWAY_ID, problemOf(validJson.set("gateway_id", JsonPrimitive(bad))))
        }
    }

    @Test
    fun `公钥指纹必须是 64 位小写十六进制`() {
        val ok = "eedd1d30512d98a7084435f20abdccb697f22dba6ed119f3bd6ef60d1cfef8b1"
        for (bad in listOf(ok.uppercase(), ok.dropLast(1), ok + "0", ok.replace('e', 'g'), "")) {
            assertEquals(bad, PayloadProblem.BAD_FINGERPRINT, problemOf(validJson.set("public_key_sha256", JsonPrimitive(bad))))
        }
    }

    @Test
    fun `配对码长度 16 到 128，多出的和不足的都拒绝`() {
        assertTrue(parse(validJson.set("pairing_code", JsonPrimitive("a".repeat(16)))) is PayloadParse.Valid)
        assertTrue(parse(validJson.set("pairing_code", JsonPrimitive("a".repeat(128)))) is PayloadParse.Valid)
        assertEquals(PayloadProblem.BAD_PAIRING_CODE, problemOf(validJson.set("pairing_code", JsonPrimitive("a".repeat(15)))))
        assertEquals(PayloadProblem.BAD_PAIRING_CODE, problemOf(validJson.set("pairing_code", JsonPrimitive("a".repeat(129)))))
    }

    @Test
    fun `服务类型只认约定的 mDNS 服务名`() {
        assertEquals(PayloadProblem.BAD_SERVICE_TYPE, problemOf(validJson.set("mdns_service_type", JsonPrimitive("_http._tcp"))))
    }

    @Test
    fun `过期时间必须是带时区的时间戳`() {
        for (bad in listOf("2026-11-15 02:10:00", "2026-11-15T02:10:00", "tomorrow", "")) {
            assertEquals(bad, PayloadProblem.BAD_EXPIRY, problemOf(validJson.set("expires_at", JsonPrimitive(bad))))
        }
        val withOffset = (parse(validJson.set("expires_at", JsonPrimitive("2026-11-15T10:10:00+08:00"))) as PayloadParse.Valid).payload
        assertEquals(Instant.parse("2026-11-15T02:10:00Z"), withOffset.expiresAt)
        val withFraction = (parse(validJson.set("expires_at", JsonPrimitive("2026-11-15T02:10:00.250Z"))) as PayloadParse.Valid).payload
        assertEquals(Instant.parse("2026-11-15T02:10:00.250Z"), withFraction.expiresAt)
    }

    @Test
    fun `地址：1 到 8 个，端口 1 到 65535，主机名里不能夹带换行、空格、斜杠或 at 符号`() {
        fun addresses(vararg pairs: Pair<String, Int>) = buildJsonArray {
            pairs.forEach { (host, port) -> add(buildJsonObject { put("host", host); put("port", port) }) }
        }
        assertEquals(PayloadProblem.BAD_ADDRESSES, problemOf(validJson.set("addresses", addresses())))
        assertEquals(PayloadProblem.BAD_ADDRESSES, problemOf(validJson.set("addresses", addresses(*Array(9) { "10.0.0.${it + 1}" to 8443 }))))
        assertTrue(parse(validJson.set("addresses", addresses(*Array(8) { "10.0.0.${it + 1}" to 8443 }))) is PayloadParse.Valid)

        for (port in listOf(0, 65536, -1)) {
            assertEquals("端口 $port", PayloadProblem.BAD_PORT, problemOf(validJson.set("addresses", addresses("10.0.0.1" to port))))
        }
        assertTrue(parse(validJson.set("addresses", addresses("10.0.0.1" to 1, "10.0.0.2" to 65535))) is PayloadParse.Valid)

        // Host 头是我们自己拼的：主机名里有 CR/LF 就是请求走私的入口，必须在这里拦住
        for (host in listOf("evil\r\nX-Injected: 1", "a b", "a/b", "user@host", "host?x=1", "host#frag", "", "h".repeat(254), "http://x", "[::1]")) {
            assertEquals("主机 ${host.take(20).replace("\r", "\\r").replace("\n", "\\n")}", PayloadProblem.BAD_HOST, problemOf(validJson.set("addresses", addresses(host to 8443))))
        }
        for (host in listOf("study-pc.local", "192.168.1.20", "fd00::1", "fe80::1%wlan0", "my_host-1.lan")) {
            assertTrue(host, parse(validJson.set("addresses", addresses(host to 8443))) is PayloadParse.Valid)
        }
    }

    @Test
    fun `地址里多出的字段也拒绝`() {
        val extra = buildJsonArray { add(buildJsonObject { put("host", "10.0.0.1"); put("port", 8443); put("path", "/x") }) }
        assertEquals(PayloadProblem.UNKNOWN_FIELD, problemOf(validJson.set("addresses", extra)))
    }

    @Test
    fun `过期判断：到点即过期`() {
        val payload = (parse(validJson) as PayloadParse.Valid).payload
        assertFalse(payload.isExpired(Instant.parse("2026-11-15T02:09:59Z")))
        assertTrue(payload.isExpired(Instant.parse("2026-11-15T02:10:00Z")))
        assertTrue(payload.isExpired(Instant.parse("2026-11-15T02:10:01Z")))
    }

    @Test
    fun `日志里不会出现配对码：toString 打码`() {
        val payload = (parse(validJson) as PayloadParse.Valid).payload
        assertFalse(payload.toString().contains("FIXTURE-PAIRING-CODE"))
    }

    @Test
    fun `解析失败的原因里不带载荷内容`() {
        val result = PairingPayload.parse(validJson.set("pairing_code", JsonPrimitive("short")).toString()) as PayloadParse.Invalid
        assertFalse(result.toString().contains("short"))
        assertFalse(result.toString().contains("study-pc"))
    }
}
