package app.auri.sync.gateway

import app.auri.capture.contract.ErrorCode
import app.auri.capture.testing.RepoContracts
import app.auri.sync.http.HttpReply
import app.auri.sync.http.HttpRequest
import app.auri.sync.http.TransportFailure
import app.auri.sync.pairing.DeviceToken
import app.auri.sync.pairing.GatewayAddress
import app.auri.sync.testing.ScriptedExchange
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 22：网关客户端的 Destination 调用（创建、列表、撤销、签发凭证）与状态查询里的“每个 Destination 的投递状态”。
 * 请求的期望值取自 contracts/fixtures 里合同自带的样本（原始文件，不是拷贝）：样本里写的 `send_mode` 是什么，请求里就必须是什么；
 * 响应也用合同样本喂进来。所以合同变了而这里没跟上，会红。
 * 没有第二套 HTTP 栈：全部走 [GatewayClient]（固定公钥的 HTTPS 客户端的同一个 HttpExchange）。
 */
class DestinationClientTest {
    private val address = GatewayAddress("192.168.1.20", 8443)
    private val token = DeviceToken.parse("fixture-device-token-not-a-real-secret-0001")!!
    private val destinationId = "65ea2953-505c-5c6f-9474-7cd5b8474104"

    private fun sample(name: String): JsonObject = RepoContracts.json("fixtures/messages/valid/$name").jsonObject.getValue("instance").jsonObject

    private fun client(script: suspend (HttpRequest) -> HttpReply): Pair<GatewayClient, ScriptedExchange> {
        val exchange = ScriptedExchange { _, request -> script(request) }
        return GatewayClient(exchange) to exchange
    }

    private fun reply(status: Int, body: JsonObject) = ScriptedExchange.json(status, body.toString())

    private fun errorBody(code: String, retryable: Boolean = false) =
        """{"schema_version":"1.1","code":"$code","message":"x","retryable":$retryable,"required_action":null,"operation_id":null}"""

    private fun <T> GatewayResult<T>.success(): T = (this as? GatewayResult.Success<T>)?.value ?: error("期望 Success，实际 $this")

    private fun bodyOf(request: HttpRequest): JsonObject = Json.parseToJsonElement(request.body!!.toString(Charsets.UTF_8)).jsonObject

    private fun JsonObject.with(vararg changes: Pair<String, kotlinx.serialization.json.JsonElement>) = JsonObject(this + changes)

    // ---- 创建：发送方式必须显式给出，请求照合同样本的写法 ----

    @Test
    fun `创建自动发送的 Destination：请求体与合同样本逐字段相同，send_mode 就是 auto_send`() = runBlocking {
        val (client, exchange) = client { reply(200, sample("destination.resource.active.json")) }

        client.createDestination(address, token, CreateDestinationRequest("写作助手", SendMode.AUTO_SEND, setOf(Intent.CONTEXT)))

        val request = exchange.calls.single().second
        assertEquals("POST", request.method)
        assertEquals("/v1/destinations", request.path)
        assertEquals(token.bearer(), request.headers["Authorization"])
        assertEquals("application/json", request.headers["Content-Type"])
        assertEquals(sample("destination.create_request.auto-send.json"), bodyOf(request))
        assertEquals("auto_send", bodyOf(request).getValue("send_mode").let { (it as JsonPrimitive).content })
    }

    @Test
    fun `创建预览确认的 Destination：请求体与合同样本逐字段相同，send_mode 就是 preview_confirm`() = runBlocking {
        val response = sample("destination.resource.active.json").with(
            "send_mode" to JsonPrimitive("preview_confirm"),
            "allowed_intents" to JsonArray(listOf(JsonPrimitive("context"), JsonPrimitive("action_request"))),
        )
        val (client, exchange) = client { reply(200, response) }

        client.createDestination(address, token, CreateDestinationRequest("写作助手", SendMode.PREVIEW_CONFIRM, setOf(Intent.CONTEXT, Intent.ACTION_REQUEST)))

        assertEquals(sample("destination.create_request.preview-confirm.json"), bodyOf(exchange.calls.single().second))
    }

    @Test
    fun `发送方式没有默认值：创建请求的构造函数不允许省略它（类型层面就挡住“悄悄选一个”）`() {
        // Kotlin 的默认参数会让编译器多生成一个带 DefaultConstructorMarker 的构造函数；一个都不能有
        val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
        val withDefaults = CreateDestinationRequest::class.java.declaredConstructors.filter { constructor -> constructor.parameterTypes.any { it == marker } }

        assertTrue("CreateDestinationRequest 的参数不许有默认值（尤其是 sendMode）：$withDefaults", withDefaults.isEmpty())
    }

    @Test
    fun `创建请求的名字与意图要合规：名字 1 到 100 个字符，至少允许一种意图`() {
        for (name in listOf("", "x".repeat(101))) {
            val failure = runCatching { CreateDestinationRequest(name, SendMode.AUTO_SEND, setOf(Intent.CONTEXT)) }.exceptionOrNull()
            assertTrue("名字 '${name.take(5)}…'(${name.length}) 应当被拒绝", failure is IllegalArgumentException)
        }
        assertTrue(runCatching { CreateDestinationRequest("ok", SendMode.AUTO_SEND, emptySet()) }.exceptionOrNull() is IllegalArgumentException)
        // 100 个“字符”按码点算：100 个 emoji（每个 2 个 UTF-16 单元）是合规的
        CreateDestinationRequest("😀".repeat(100), SendMode.PREVIEW_CONFIRM, setOf(Intent.CONTEXT))
    }

    @Test
    fun `创建的响应按合同解析；网关回的是另一个发送方式、另一个名字或另一组意图，不能当成我们要的那个`() = runBlocking {
        val request = CreateDestinationRequest("写作助手", SendMode.AUTO_SEND, setOf(Intent.CONTEXT))
        val good = sample("destination.resource.active.json")

        val created = client { reply(200, good) }.first.createDestination(address, token, request).success()
        assertEquals(destinationId, created.destinationId)
        assertEquals("写作助手", created.displayName)
        assertEquals(SendMode.AUTO_SEND, created.sendMode)
        assertEquals(setOf(Intent.CONTEXT), created.allowedIntents)
        assertEquals(Instant.parse("2026-11-15T02:06:00Z"), created.createdAt)
        assertEquals("ef7b1358-df31-520f-90f3-d98fc5173b46", created.createdByDeviceId)
        assertTrue(created.credentialIssued)
        assertNull(created.revokedAt)
        assertTrue(created.active)

        for (wrong in listOf(
            good.with("send_mode" to JsonPrimitive("preview_confirm")),
            good.with("display_name" to JsonPrimitive("别的名字")),
            good.with("allowed_intents" to JsonArray(listOf(JsonPrimitive("context"), JsonPrimitive("action_request")))),
        )) {
            assertTrue("答非所问：$wrong", client { reply(200, wrong) }.first.createDestination(address, token, request) is GatewayResult.Garbled)
        }
    }

    @Test
    fun `创建被网关拒绝：保持原样报出状态码与合同错误码，不重试`() = runBlocking {
        val (client, exchange) = client { ScriptedExchange.json(422, errorBody("REQUEST_INVALID")) }

        val result = client.createDestination(address, token, CreateDestinationRequest("写作助手", SendMode.AUTO_SEND, setOf(Intent.CONTEXT)))

        val rejected = result as GatewayResult.Rejected
        assertEquals(422, rejected.status)
        assertEquals(ErrorCode.REQUEST_INVALID, rejected.code)
        assertEquals(1, exchange.calls.size)
    }

    @Test
    fun `创建时响应在路上丢了：报成传输失败（结果不确定），客户端不自己再发一次`() = runBlocking {
        val (client, exchange) = client { throw TransportFailure.Interrupted() }

        val result = client.createDestination(address, token, CreateDestinationRequest("写作助手", SendMode.AUTO_SEND, setOf(Intent.CONTEXT)))

        assertTrue(result is GatewayResult.TransportFailed && result.failure is TransportFailure.Interrupted)
        assertEquals(1, exchange.calls.size)
    }

    // ---- 列表与撤销 ----

    @Test
    fun `列表：GET 并带令牌，包含已撤销的，按合同解析 revoked_at`() = runBlocking {
        val body = JsonObject(
            mapOf(
                "schema_version" to JsonPrimitive("1.1"),
                "destinations" to JsonArray(listOf(sample("destination.resource.active.json"), sample("destination.resource.revoked.json").with("destination_id" to JsonPrimitive("9b1a23f8-5241-4e78-9d21-15c4c720c2c9")))),
            ),
        )
        val (client, exchange) = client { reply(200, body) }

        val list = client.listDestinations(address, token).success()

        val request = exchange.calls.single().second
        assertEquals("GET", request.method)
        assertEquals("/v1/destinations", request.path)
        assertEquals(token.bearer(), request.headers["Authorization"])
        assertEquals(listOf(true, false), list.map { it.active })
        assertEquals(Instant.parse("2026-11-20T00:00:00Z"), list[1].revokedAt)
    }

    @Test
    fun `列表里有一项读不懂，整个列表都不采信：缓存里不能混进半条记录`() = runBlocking {
        val broken = sample("destination.resource.active.json").with("send_mode" to JsonPrimitive("send_whenever"))
        val body = JsonObject(mapOf("schema_version" to JsonPrimitive("1.1"), "destinations" to JsonArray(listOf(sample("destination.resource.active.json"), broken))))

        assertTrue(client { reply(200, body) }.first.listDestinations(address, token) is GatewayResult.Garbled)
    }

    @Test
    fun `撤销：DELETE 这个 Destination，响应必须真的是已撤销的那一个`() = runBlocking {
        val (client, exchange) = client { reply(200, sample("destination.resource.revoked.json")) }

        val revoked = client.revokeDestination(address, token, destinationId).success()

        val request = exchange.calls.single().second
        assertEquals("DELETE", request.method)
        assertEquals("/v1/destinations/$destinationId", request.path)
        assertEquals(token.bearer(), request.headers["Authorization"])
        assertFalse(revoked.active)
        assertEquals(Instant.parse("2026-11-20T00:00:00Z"), revoked.revokedAt)

        // 网关说“好了”却回了一个没撤销的 Destination，或者别的 Destination：不能当撤销成功
        assertTrue(client { reply(200, sample("destination.resource.active.json")) }.first.revokeDestination(address, token, destinationId) is GatewayResult.Garbled)
        val other = sample("destination.resource.revoked.json").with("destination_id" to JsonPrimitive("9b1a23f8-5241-4e78-9d21-15c4c720c2c9"))
        assertTrue(client { reply(200, other) }.first.revokeDestination(address, token, destinationId) is GatewayResult.Garbled)
    }

    // ---- 凭证 ----

    @Test
    fun `签发凭证：POST 空对象，响应按合同解析，凭证对象的 toString 不含凭证`() = runBlocking {
        val (client, exchange) = client { reply(200, sample("destination.credential_response.first-issue.json")) }

        val issued = client.issueCredential(address, token, destinationId).success()

        val request = exchange.calls.single().second
        assertEquals("POST", request.method)
        assertEquals("/v1/destinations/$destinationId/credential", request.path)
        assertEquals(token.bearer(), request.headers["Authorization"])
        assertEquals(JsonObject(emptyMap()), bodyOf(request))
        assertEquals(destinationId, issued.destinationId)
        assertEquals("fixture-credential-not-a-real-secret-0001", issued.credential.reveal())
        assertEquals(Instant.parse("2026-11-15T02:06:30Z"), issued.issuedAt)
        assertFalse(issued.replacedPrevious)
        for (text in listOf(issued.toString(), issued.credential.toString(), "$issued")) {
            assertFalse("凭证不能出现在 toString 里：$text", text.contains("fixture-credential"))
        }
    }

    @Test
    fun `轮换：replaced_previous 为 true 时如实带出来，界面据此告诉用户旧凭证已失效`() = runBlocking {
        val body = sample("destination.credential_response.first-issue.json").with("replaced_previous" to JsonPrimitive(true))

        assertTrue(client { reply(200, body) }.first.issueCredential(address, token, destinationId).success().replacedPrevious)
    }

    @Test
    fun `凭证响应不合规就丢掉：太短、别的 Destination、缺字段，一个字节都不留`() = runBlocking {
        val good = sample("destination.credential_response.first-issue.json")
        val broken = listOf(
            good.with("credential" to JsonPrimitive("too-short")),
            good.with("credential" to JsonPrimitive("has space inside the credential value, 32+ chars")),
            good.with("destination_id" to JsonPrimitive("9b1a23f8-5241-4e78-9d21-15c4c720c2c9")),
            JsonObject(good - "issued_at"),
        )
        for (body in broken) {
            val result = client { reply(200, body) }.first.issueCredential(address, token, destinationId)
            assertTrue("$body", result is GatewayResult.Garbled)
            assertFalse("读不懂的响应，诊断信息里也不能带凭证：$result", result.toString().contains("fixture-credential"))
        }
    }

    @Test
    fun `签发凭证被拒：已撤销的 Destination 是 409，令牌被吊销是 401，原样报出`() = runBlocking {
        val revoked = client { ScriptedExchange.json(409, errorBody("REQUEST_INVALID")) }.first.issueCredential(address, token, destinationId)
        assertEquals(409, (revoked as GatewayResult.Rejected).status)

        val auth = client { ScriptedExchange.json(401, errorBody("GATEWAY_AUTH_REVOKED")) }.first.issueCredential(address, token, destinationId)
        assertEquals(ErrorCode.GATEWAY_AUTH_REVOKED, (auth as GatewayResult.Rejected).code)
    }

    // ---- 状态查询里每个 Destination 的投递状态 ----

    @Test
    fun `状态查询：每个 Destination 的投递状态照网关的说法读出来（已入库、已领取、已确认）`() = runBlocking {
        val (client, _) = client { reply(200, sample("gateway.record_status_response.per-destination.json")) }

        val status = client.recordStatus(address, token, "a4a7c08d-2767-50d6-9238-6fa277033a49").success()

        assertEquals(
            listOf("PENDING", "LEASED", "ACKNOWLEDGED"),
            status.destinations.map { it.state },
        )
        assertEquals(listOf("Hermes", "Claude Code", "Notes Agent"), status.destinations.map { it.destinationDisplayName })
        assertEquals(listOf(1, 1, 1), status.destinations.map { it.revision })
        assertEquals("65ea2953-505c-5c6f-9474-7cd5b8474104", status.destinations.first().destinationId)
        assertEquals("7b8524b6-8c52-571f-98c3-7f62b3764bea", status.destinations.first().deliveryId)
    }

    @Test
    fun `状态查询：投递条目读不懂或状态没见过，不让整个查询失败（同步引擎靠它确认入库），而是原样带出让界面显示未知`() = runBlocking {
        val base = sample("gateway.record_status_response.per-destination.json")
        val entries = (base.getValue("destinations") as JsonArray).map { it.jsonObject }
        val weird = base.with(
            "destinations" to JsonArray(
                listOf(
                    entries[0].with("delivery_state" to JsonPrimitive("TELEPORTED")),
                    JsonObject(entries[1] - "delivery_id"), // 缺字段：没法归属，丢掉这一条
                    entries[2],
                ),
            ),
        )

        val status = client { reply(200, weird) }.first.recordStatus(address, token, "a4a7c08d-2767-50d6-9238-6fa277033a49").success()

        assertEquals(listOf("TELEPORTED", "ACKNOWLEDGED"), status.destinations.map { it.state })
        assertEquals("同步引擎要的那几项不受影响", 1, status.revisions.size)
    }

    @Test
    fun `状态查询：没有 destinations 字段时按没有投递处理，同步引擎靠 revisions 判断入库，不受影响`() = runBlocking {
        val base = sample("gateway.record_status_response.per-destination.json")

        val status = client { reply(200, JsonObject(base - "destinations")) }.first.recordStatus(address, token, "a4a7c08d-2767-50d6-9238-6fa277033a49").success()

        assertTrue(status.destinations.isEmpty())
        assertEquals(1, status.revisions.size)
    }
}
