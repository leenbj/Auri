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
import kotlinx.serialization.json.putJsonObject

/**
 * 网关的四个调用：存活检查、配对、提交 Revision、查询 Record 状态（PRD 17.2）。不重试、不改道：
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
            )
            if (status.recordId != recordId) throw Unreadable("answer is about a different record")
            status
        }
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
