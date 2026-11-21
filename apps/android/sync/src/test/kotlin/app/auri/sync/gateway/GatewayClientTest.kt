package app.auri.sync.gateway

import app.auri.capture.contract.ErrorCode
import app.auri.capture.testing.RepoContracts
import app.auri.sync.http.HttpRequest
import app.auri.sync.http.TransportFailure
import app.auri.sync.pairing.DeviceToken
import app.auri.sync.pairing.GatewayAddress
import app.auri.sync.testing.ScriptedExchange
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 网关客户端：请求按合同组装，响应按合同解析。网关的回答一律取自 contracts/fixtures 里合同自带的样本
 * （不是自己编的），所以只要合同样本变了而解析没跟上，这里就会红。
 */
class GatewayClientTest {
    private val address = GatewayAddress("192.168.1.20", 8443)
    private val token = DeviceToken.parse("fixture-device-token-not-a-real-secret-0001")!!
    private val recordId = "a4a7c08d-2767-50d6-9238-6fa277033a49"
    private val sha = "d0d776b5f83facd57fa2b9ecd4e39984745e516a4c4de0495cdb71acc28f56ec"
    private val submission = RevisionSubmission(recordId, 1, sha, """{"schema_version":"1.2","text":"你好"}""".toByteArray())

    private fun sample(name: String): String = RepoContracts.json("fixtures/messages/valid/$name").jsonObject.getValue("instance").toString()

    private fun errorBody(code: String, retryable: Boolean = false) =
        """{"schema_version":"1.2","code":"$code","message":"x","retryable":$retryable,"required_action":null,"operation_id":null}"""

    private fun client(script: suspend (HttpRequest) -> app.auri.sync.http.HttpReply): Pair<GatewayClient, ScriptedExchange> {
        val exchange = ScriptedExchange { _, request -> script(request) }
        return GatewayClient(exchange) to exchange
    }

    private fun reply(status: Int, body: String) = ScriptedExchange.json(status, body)

    private fun <T> GatewayResult<T>.success(): T = (this as? GatewayResult.Success<T>)?.value ?: error("期望 Success，实际 $this")

    private val device = DeviceInfo("0.1.0-test", "Android 15 / emulator", "测试手机")

    // ---- health ----

    @Test
    fun `health：200 且 status 是 ok 才算存活；不带认证头，走 GET 健康检查路径`() = runBlocking {
        val (client, exchange) = client { reply(200, sample("gateway.health_response.ok.json")) }

        assertEquals(GatewayResult.Success(Unit), client.health(address))

        val request = exchange.calls.single().second
        assertEquals("GET", request.method)
        assertEquals("/health", request.path)
        assertNull(request.headers.keys.firstOrNull { it.equals("authorization", true) })
        assertEquals(address, exchange.calls.single().first)
    }

    @Test
    fun `health：回的不是合同里的存活响应就不能当存活`() = runBlocking {
        for (body in listOf("""{"status":"degraded"}""", "<html>router login</html>", "{}", "")) {
            val (client, _) = client { reply(200, body) }
            assertTrue("body=$body", client.health(address) is GatewayResult.Garbled)
        }
    }

    // ---- redeem ----

    @Test
    fun `redeem：请求体与合同样本逐个字段对应，平台是 android，不带任何认证头`() = runBlocking {
        val (client, exchange) = client { reply(200, sample("pairing.redeem_response.paired.json")) }

        client.redeem(address, "FIXTURE-PAIRING-CODE-NOT-A-SECRET", device)

        val request = exchange.calls.single().second
        assertEquals("POST", request.method)
        assertEquals("/v1/pairing/redeem", request.path)
        assertEquals("application/json", request.headers["Content-Type"])
        assertNull(request.headers.keys.firstOrNull { it.equals("authorization", true) })
        val sent = Json.parseToJsonElement(request.body!!.toString(Charsets.UTF_8)).jsonObject
        val reference = Json.parseToJsonElement(sample("pairing.redeem_request.ios-device.json")).jsonObject
        assertEquals("字段集合必须与合同样本一致", reference.keys, sent.keys)
        assertEquals(reference.getValue("device").jsonObject.keys, sent.getValue("device").jsonObject.keys)
        assertEquals("1.2", sent.getValue("schema_version").jsonPrimitive.content)
        assertEquals("FIXTURE-PAIRING-CODE-NOT-A-SECRET", sent.getValue("pairing_code").jsonPrimitive.content)
        assertEquals("android", sent.getValue("device").jsonObject.getValue("platform").jsonPrimitive.content)
        assertEquals("测试手机", sent.getValue("device").jsonObject.getValue("display_name").jsonPrimitive.content)
    }

    @Test
    fun `redeem：合同样本响应解析出网关与设备标识、令牌与配对时间，令牌不会出现在 toString 里`() = runBlocking {
        val (client, _) = client { reply(200, sample("pairing.redeem_response.paired.json")) }

        val response = client.redeem(address, "FIXTURE-PAIRING-CODE-NOT-A-SECRET", device).success()

        assertEquals("fa1b98ec-1bfb-53b3-85a3-4d36b680fe6e", response.gatewayId)
        assertEquals("ef7b1358-df31-520f-90f3-d98fc5173b46", response.deviceId)
        assertEquals("Bearer fixture-device-token-not-a-real-secret-0001", response.deviceToken.bearer())
        assertEquals(Instant.parse("2026-11-15T02:05:00Z"), response.pairedAt)
        assertFalse(response.toString().contains("fixture-device-token"))
        assertFalse(response.deviceToken.toString().contains("fixture-device-token"))
    }

    @Test
    fun `redeem：令牌太短、含空白或版本不是 1_2，一律不接受（不存下不合格的令牌）`() = runBlocking {
        val good = Json.parseToJsonElement(sample("pairing.redeem_response.paired.json")).jsonObject
        fun with(key: String, value: String) = JsonObject(good + (key to JsonPrimitive(value))).toString()
        for (bad in listOf(
            with("device_token", "too-short"),
            with("device_token", "fixture-device token-with-a-space-000000001"),
            with("device_token", "fixture-device-token\r\nX-Injected: 1-0000000000"),
            with("schema_version", "1.1"),
            with("schema_version", "1.3"),
            with("device_id", "NOT-A-UUID"),
            with("paired_at", "yesterday"),
        )) {
            val (client, _) = client { reply(200, bad) }
            assertTrue("应当拒绝：$bad", client.redeem(address, "FIXTURE-PAIRING-CODE-NOT-A-SECRET", device) is GatewayResult.Garbled)
        }
    }

    @Test
    fun `redeem：配对码无效时网关的拒绝带着合同错误码`() = runBlocking {
        val (client, _) = client { reply(422, sample("error.pairing-code-invalid.json")) }

        val result = client.redeem(address, "FIXTURE-PAIRING-CODE-NOT-A-SECRET", device)

        assertEquals(GatewayResult.Rejected(422, ErrorCode.PAIRING_CODE_INVALID, "PAIRING_CODE_INVALID", retryable = false), result)
    }

    // ---- putRevision ----

    @Test
    fun `PUT：路径、令牌、内容类型与正文逐字节正确`() = runBlocking {
        val (client, exchange) = client { reply(200, sample("revision.stored.first-store.json")) }

        client.putRevision(address, token, submission)

        val request = exchange.calls.single().second
        assertEquals("PUT", request.method)
        assertEquals("/v1/records/$recordId/revisions/1", request.path)
        assertEquals("Bearer fixture-device-token-not-a-real-secret-0001", request.headers["Authorization"])
        assertEquals("application/json", request.headers["Content-Type"])
        assertArrayEquals(submission.body, request.body)
        assertFalse("请求的字符串形式不能带令牌", request.toString().contains("fixture-device-token"))
    }

    @Test
    fun `PUT：首次入库与重复提交的合同样本都解析为成功，duplicate 如实带回`() = runBlocking {
        val (first, _) = client { reply(200, sample("revision.stored.first-store.json")) }
        val (again, _) = client { reply(200, sample("revision.stored.duplicate-submit.json")) }

        val stored = first.putRevision(address, token, submission).success()
        assertEquals(recordId, stored.recordId)
        assertEquals(1, stored.revision)
        assertEquals(sha, stored.textSha256)
        assertEquals("f248f8a6-694e-52a8-8aaf-76e5a7625808", stored.operationId)
        assertEquals(Instant.parse("2026-11-15T02:31:02Z"), stored.storedAt)
        assertFalse(stored.duplicate)
        assertTrue(again.putRevision(address, token, submission).success().duplicate)
    }

    @Test
    fun `PUT：网关回答的不是我们提交的那一条，绝不当成已入库`() = runBlocking {
        val stored = Json.parseToJsonElement(sample("revision.stored.first-store.json")).jsonObject
        fun mutated(key: String, value: kotlinx.serialization.json.JsonElement) = JsonObject(stored + (key to value)).toString()
        for (wrong in listOf(
            mutated("record_id", JsonPrimitive("00000000-0000-4000-8000-000000000000")),
            mutated("revision", JsonPrimitive(2)),
            mutated("text_sha256", JsonPrimitive("0".repeat(64))),
        )) {
            val (client, _) = client { reply(200, wrong) }
            assertTrue("应当当作读不懂：$wrong", client.putRevision(address, token, submission) is GatewayResult.Garbled)
        }
    }

    @Test
    fun `PUT：2xx 但内容读不懂（不是 JSON、缺字段、版本不对）都是 Garbled，不是成功也不是失败`() = runBlocking {
        val stored = Json.parseToJsonElement(sample("revision.stored.first-store.json")).jsonObject
        for (body in listOf("", "<html>", "[]", JsonObject(stored - "duplicate").toString(), JsonObject(stored - "stored_at").toString(), JsonObject(stored + ("schema_version" to JsonPrimitive("2.0"))).toString())) {
            val (client, _) = client { reply(200, body) }
            val result = client.putRevision(address, token, submission)
            assertTrue("body=$body 得到 $result", result is GatewayResult.Garbled)
        }
    }

    @Test
    fun `PUT：网关的各类拒绝带着合同错误码与它自己说的 retryable`() = runBlocking {
        val cases = listOf(
            Triple(401, "GATEWAY_AUTH_INVALID", false),
            Triple(401, "GATEWAY_AUTH_REVOKED", false),
            Triple(403, "RECORD_NOT_ORIGIN_DEVICE", false),
            Triple(409, "RECORD_VERSION_CONFLICT", false),
            Triple(410, "RECORD_DELETED", false),
            Triple(422, "REQUEST_INVALID", false),
            Triple(500, "GATEWAY_UNREACHABLE", true),
        )
        for ((status, code, retryable) in cases) {
            val (client, _) = client { reply(status, errorBody(code, retryable)) }
            val result = client.putRevision(address, token, submission)
            assertEquals(GatewayResult.Rejected(status, ErrorCode.valueOf(code), code, retryable), result)
        }
    }

    @Test
    fun `PUT：没有合同错误体的响应（代理的 502、纯文本 404）按状态码推断能不能重试；没见过的错误码保留原文`() = runBlocking {
        for ((status, retryable) in listOf(502 to true, 503 to true, 504 to true, 429 to true, 408 to true, 404 to false, 400 to false, 302 to false)) {
            val (client, _) = client { ScriptedExchange.json(status, "<html>proxy</html>") }
            assertEquals("status=$status", GatewayResult.Rejected(status, null, null, retryable), client.putRevision(address, token, submission))
        }
        val (unknown, _) = client { reply(422, errorBody("SOMETHING_NEW_IN_1_2")) }
        assertEquals(GatewayResult.Rejected(422, null, "SOMETHING_NEW_IN_1_2", false), unknown.putRevision(address, token, submission))
    }

    @Test
    fun `PUT：网关在错误体里明说的 retryable 优先于按状态码的推断（两个方向都要）`() = runBlocking {
        val (explicitNo, _) = client { reply(503, errorBody("GATEWAY_UNREACHABLE", retryable = false)) }
        assertFalse((explicitNo.putRevision(address, token, submission) as GatewayResult.Rejected).retryable)
        val (explicitYes, _) = client { reply(409, errorBody("RECORD_VERSION_CONFLICT", retryable = true)) }
        assertTrue((explicitYes.putRevision(address, token, submission) as GatewayResult.Rejected).retryable)
    }

    // ---- recordStatus ----

    @Test
    fun `status：合同样本解析出这条 Record 在网关上的 Revision 与文字哈希；请求带令牌`() = runBlocking {
        val (client, exchange) = client { reply(200, sample("gateway.record_status_response.per-destination.json")) }

        val status = client.recordStatus(address, token, recordId).success()

        val request = exchange.calls.single().second
        assertEquals("GET", request.method)
        assertEquals("/v1/records/$recordId/status", request.path)
        assertEquals("Bearer fixture-device-token-not-a-real-secret-0001", request.headers["Authorization"])
        assertEquals(recordId, status.recordId)
        assertEquals("3f9a1b6e-2f44-4d30-9f48-4b75617df2b1", status.originDeviceId)
        assertFalse(status.deleted)
        assertEquals(listOf(RevisionOnGateway(1, "PUBLISHED", sha)), status.revisions)
    }

    @Test
    fun `status：记录已被删除、网关不认识这条 Record 都如实返回`() = runBlocking {
        val deleted = JsonObject(Json.parseToJsonElement(sample("gateway.record_status_response.per-destination.json")).jsonObject + mapOf("deleted" to kotlinx.serialization.json.JsonPrimitive(true), "deleted_at" to JsonPrimitive("2026-11-15T03:00:00Z")))
        val (gone, _) = client { reply(200, deleted.toString()) }
        assertTrue(gone.recordStatus(address, token, recordId).success().deleted)

        val (unknown, _) = client { reply(404, errorBody("REQUEST_INVALID")) }
        assertEquals(GatewayResult.Rejected(404, ErrorCode.REQUEST_INVALID, "REQUEST_INVALID", false), unknown.recordStatus(address, token, recordId))
    }

    @Test
    fun `status：回答的不是我们问的那条 Record，不能采信`() = runBlocking {
        val other = JsonObject(Json.parseToJsonElement(sample("gateway.record_status_response.per-destination.json")).jsonObject + ("record_id" to JsonPrimitive("00000000-0000-4000-8000-000000000000")))
        val (client, _) = client { reply(200, other.toString()) }
        assertTrue(client.recordStatus(address, token, recordId) is GatewayResult.Garbled)
    }

    @Test
    fun `路径里的 record_id 必须是小写 UUID：夹带路径穿越或空白的写法在发出之前就被拒绝`() = runBlocking {
        val (client, exchange) = client { reply(200, "{}") }
        for (bad in listOf("../admin", "A4A7C08D-2767-50D6-9238-6FA277033A49", "x y", "", "$recordId/../x")) {
            try {
                client.recordStatus(address, token, bad)
                fail("应当拒绝 $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertTrue("拒绝发生在发请求之前", exchange.calls.isEmpty())
        assertTrue(runCatching { RevisionSubmission("../x", 1, sha, ByteArray(0)) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { RevisionSubmission(recordId, 0, sha, ByteArray(0)) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { RevisionSubmission(recordId, 1, sha.uppercase(), ByteArray(0)) }.exceptionOrNull() is IllegalArgumentException)
    }

    // ---- transport ----

    @Test
    fun `传输失败原样带出，三种不能混；协程取消不能被吞掉`() = runBlocking {
        for (failure in listOf(TransportFailure.Unreachable(), TransportFailure.KeyMismatch(), TransportFailure.Interrupted())) {
            val (client, _) = client { throw failure }
            assertEquals(GatewayResult.TransportFailed(failure), client.putRevision(address, token, submission))
            assertEquals(GatewayResult.TransportFailed(failure), client.health(address))
            assertEquals(GatewayResult.TransportFailed(failure), client.recordStatus(address, token, recordId))
            assertEquals(GatewayResult.TransportFailed(failure), client.redeem(address, "FIXTURE-PAIRING-CODE-NOT-A-SECRET", device))
        }
        val (cancelled, _) = client { throw CancellationException("cancelled") }
        try {
            cancelled.putRevision(address, token, submission)
            fail("取消必须继续向上抛")
        } catch (_: CancellationException) {
        }
    }
}
