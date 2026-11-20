package app.auri.sync.gateway

import app.auri.capture.contract.ErrorCode
import app.auri.sync.http.HttpExchange
import app.auri.sync.http.HttpReply
import app.auri.sync.http.HttpRequest
import app.auri.sync.http.TransportFailure
import app.auri.sync.pairing.DeviceToken
import app.auri.sync.pairing.GatewayAddress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 网关的调用：存活检查、配对、提交 Revision、查询 Record 状态（PRD 17.2），以及 Ticket 22 的 Destination 创建、列表、撤销、签发凭证。不重试、不改道：
 * 重试与“结果不确定”的处置属于同步引擎，这里只把响应忠实地翻译成 [GatewayResult]。
 *
 * 响应一律按合同严格读取；而且回答必须是对我们问的那件事的回答：提交返回的 record_id / revision / text_sha256
 * 任何一个与我们提交的不同，都不能当成“已入库”。
 */
class GatewayClient(private val http: HttpExchange) {
    suspend fun health(address: GatewayAddress): GatewayResult<Unit> =
        call(address, HttpRequest("GET", "/health")) { if (it.string("status") != "ok") throw Unreadable("status") }

    suspend fun redeem(address: GatewayAddress, pairingCode: String, device: DeviceInfo): GatewayResult<RedeemResponse> {
        require(pairingCode.isNotBlank()) { "配对码不能为空" }
        val body = buildJsonObject {
            put("schema_version", Wire.SCHEMA_VERSION)
            put("pairing_code", pairingCode)
            putJsonObject("device") {
                put("platform", "android")
                put("app_version", device.appVersion)
                put("os_build", device.osBuild)
                put("display_name", device.displayName)
            }
        }
        return call(address, HttpRequest("POST", "/v1/pairing/redeem", JSON_BODY, body.toString().toByteArray(Charsets.UTF_8))) { reply ->
            reply.requireVersion()
            RedeemResponse(
                gatewayId = reply.uuid("gateway_id"),
                deviceId = reply.uuid("device_id"),
                deviceToken = DeviceToken.parse(reply.string("device_token")) ?: throw Unreadable("device_token"),
                pairedAt = reply.timestamp("paired_at"),
            )
        }
    }

    suspend fun putRevision(address: GatewayAddress, token: DeviceToken, submission: RevisionSubmission): GatewayResult<StoredResponse> {
        val path = "/v1/records/${submission.recordId}/revisions/${submission.revision}"
        return call(address, HttpRequest("PUT", path, JSON_BODY + authorization(token), submission.body)) { reply ->
            reply.requireVersion()
            val stored = StoredResponse(
                operationId = reply.uuid("operation_id"),
                recordId = reply.uuid("record_id"),
                revision = reply.int("revision"),
                textSha256 = reply.sha256("text_sha256"),
                storedAt = reply.timestamp("stored_at"),
                duplicate = reply.boolean("duplicate"),
            )
            reply.array("superseded_revisions")
            reply.array("deliveries")
            if (stored.recordId != submission.recordId || stored.revision != submission.revision || stored.textSha256 != submission.textSha256) {
                throw Unreadable("answer is about a different revision")
            }
            stored
        }
    }

    suspend fun recordStatus(address: GatewayAddress, token: DeviceToken, recordId: String): GatewayResult<RecordStatus> {
        require(Wire.UUID.matches(recordId)) { "record_id 必须是小写 UUID" }
        return call(address, HttpRequest("GET", "/v1/records/$recordId/status", authorization(token))) { reply ->
            reply.requireVersion()
            val status = RecordStatus(
                recordId = reply.uuid("record_id"),
                originDeviceId = reply.uuid("origin_device_id"),
                deleted = reply.boolean("deleted"),
                revisions = reply.array("revisions").map { element ->
                    val item = WireObject.parse(element.toString().toByteArray(Charsets.UTF_8))
                    RevisionOnGateway(item.int("revision"), item.string("state"), item.sha256("text_sha256"))
                },
                // 投递状态是 Ticket 22 界面要的；读不懂的条目丢掉、没有这个字段按没有投递算，不让它连累同步引擎靠 revisions 做的判断
                destinations = reply.itemsOrNull("destinations").orEmpty().mapNotNull(::readDelivery),
            )
            if (status.recordId != recordId) throw Unreadable("answer is about a different record")
            status
        }
    }

    // ---- Destination（Ticket 22）：同一个 HttpExchange，同一套严格读取，没有第二套 HTTP 栈 ----

    /** GET /v1/destinations：这个租户的全部 Destination（含已撤销的）。任何一项读不懂就整个不采信。 */
    suspend fun listDestinations(address: GatewayAddress, token: DeviceToken): GatewayResult<List<Destination>> =
        call(address, HttpRequest("GET", "/v1/destinations", authorization(token))) { reply ->
            reply.requireVersion()
            reply.items("destinations").map(::readDestination)
        }

    /** POST /v1/destinations。请求里的发送方式、名字、意图原样来自 [request]；网关回的必须就是我们要创建的那个。 */
    suspend fun createDestination(address: GatewayAddress, token: DeviceToken, request: CreateDestinationRequest): GatewayResult<Destination> {
        val body = buildJsonObject {
            put("schema_version", Wire.SCHEMA_VERSION)
            put("display_name", request.displayName)
            put("send_mode", request.sendMode.wire)
            putJsonArray("allowed_intents") {
                // 按枚举的固定次序写，同样的请求每次都是同样的字节
                Intent.entries.filter { it in request.allowedIntents }.forEach { add(JsonPrimitive(it.wire)) }
            }
        }
        return call(address, HttpRequest("POST", "/v1/destinations", JSON_BODY + authorization(token), body.toString().toByteArray(Charsets.UTF_8))) { reply ->
            val created = readDestination(reply)
            if (created.displayName != request.displayName || created.sendMode != request.sendMode || created.allowedIntents != request.allowedIntents) {
                throw Unreadable("answer is about a different destination")
            }
            created
        }
    }

    /** DELETE /v1/destinations/{id}：撤销 Destination 及其凭证。响应必须是这个 Destination 且确实已撤销。 */
    suspend fun revokeDestination(address: GatewayAddress, token: DeviceToken, destinationId: String): GatewayResult<Destination> {
        require(Wire.UUID.matches(destinationId)) { "destination_id 必须是小写 UUID" }
        return call(address, HttpRequest("DELETE", "/v1/destinations/$destinationId", authorization(token))) { reply ->
            val revoked = readDestination(reply)
            if (revoked.destinationId != destinationId || revoked.active) throw Unreadable("answer is not the revoked destination")
            revoked
        }
    }

    /**
     * POST /v1/destinations/{id}/credential：签发或轮换凭证。凭证只在这个响应里出现一次。
     * 调用方必须先让用户过本机验证（见 DestinationService）；这里不替它判断。读不懂的响应只报字段名，不带响应内容。
     */
    suspend fun issueCredential(address: GatewayAddress, token: DeviceToken, destinationId: String): GatewayResult<IssuedCredential> {
        require(Wire.UUID.matches(destinationId)) { "destination_id 必须是小写 UUID" }
        val request = HttpRequest("POST", "/v1/destinations/$destinationId/credential", JSON_BODY + authorization(token), "{}".toByteArray(Charsets.UTF_8))
        return call(address, request) { reply ->
            reply.requireVersion()
            val issued = IssuedCredential(
                destinationId = reply.uuid("destination_id"),
                credential = DestinationCredential.parse(reply.string("credential")) ?: throw Unreadable("credential"),
                issuedAt = reply.timestamp("issued_at"),
                replacedPrevious = reply.boolean("replaced_previous"),
            )
            if (issued.destinationId != destinationId) throw Unreadable("answer is about a different destination")
            issued
        }
    }

    private fun readDestination(reply: WireObject): Destination {
        reply.requireVersion()
        val intents = reply.strings("allowed_intents").map { Intent.fromWire(it) ?: throw Unreadable("allowed_intents") }.toSet()
        if (intents.isEmpty()) throw Unreadable("allowed_intents")
        val name = reply.string("display_name")
        if (name.codePointCount(0, name.length) !in 1..CreateDestinationRequest.MAX_NAME) throw Unreadable("display_name")
        return Destination(
            destinationId = reply.uuid("destination_id"),
            displayName = name,
            sendMode = SendMode.fromWire(reply.string("send_mode")) ?: throw Unreadable("send_mode"),
            allowedIntents = intents,
            createdAt = reply.timestamp("created_at"),
            createdByDeviceId = reply.uuid("created_by_device_id"),
            credentialIssued = reply.boolean("credential_issued"),
            revokedAt = reply.timestampOrNull("revoked_at"),
        )
    }

    /** 读不懂（缺字段、类型不对）的投递条目没法归属给任何 Destination，丢掉；状态字符串本身不判断，原样带出。 */
    private fun readDelivery(item: WireObject): DeliveryOnGateway? = try {
        DeliveryOnGateway(
            destinationId = item.uuid("destination_id"),
            destinationDisplayName = item.string("destination_display_name"),
            deliveryId = item.uuid("delivery_id"),
            revision = item.int("revision"),
            state = item.string("delivery_state"),
        )
    } catch (_: Unreadable) {
        null
    }

    private suspend fun <T> call(address: GatewayAddress, request: HttpRequest, read: (WireObject) -> T): GatewayResult<T> {
        val reply = try {
            http.execute(address, request)
        } catch (failure: TransportFailure) {
            return GatewayResult.TransportFailed(failure)
        }
        if (reply.status !in 200..299) return rejected(reply)
        return try {
            GatewayResult.Success(read(WireObject.parse(reply.body)))
        } catch (unreadable: Unreadable) {
            GatewayResult.Garbled(reply.status, "unreadable field: ${unreadable.field}")
        }
    }

    /** 错误体按合同读；读不懂（代理的 502、纯文本 404）就按状态码推断。网关说的 retryable 优先。 */
    private fun rejected(reply: HttpReply): GatewayResult.Rejected {
        val body = try {
            Json.parseToJsonElement(reply.body.toString(Charsets.UTF_8)) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        }
        val rawCode = (body?.get("code") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { CODE_FORMAT.matches(it) }
        val retryable = (body?.get("retryable") as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        return GatewayResult.Rejected(
            status = reply.status,
            code = rawCode?.let { name -> ErrorCode.entries.firstOrNull { it.name == name } },
            rawCode = rawCode,
            retryable = retryable ?: (reply.status >= 500 || reply.status == 429 || reply.status == 408),
        )
    }

    private fun authorization(token: DeviceToken) = mapOf("Authorization" to token.bearer())

    private companion object {
        val JSON_BODY = mapOf("Content-Type" to "application/json")
        val CODE_FORMAT = Regex("^[A-Z][A-Z0-9_]{0,63}$")
    }
}
