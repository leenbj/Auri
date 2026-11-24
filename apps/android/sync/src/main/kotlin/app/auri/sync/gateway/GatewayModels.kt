package app.auri.sync.gateway

import app.auri.capture.contract.ErrorCode
import app.auri.sync.http.TransportFailure
import app.auri.sync.pairing.DeviceToken
import java.time.Instant

/** 一次网关调用的结果。成功、网关明确拒绝、响应读不懂、没拿到响应：四种情形各自的处置不同，所以不混成一个“失败”。 */
sealed interface GatewayResult<out T> {
    data class Success<T>(val value: T) : GatewayResult<T>

    /**
     * 网关回了错误响应（HTTP 非 2xx）。[code] 是合同错误码，没认出来时为 null（[rawCode] 留原文，错误码本身不含机密）。
     * [retryable] 优先取网关在错误体里的说法；没有错误体（例如代理返回的 502）时按状态码推断。
     */
    data class Rejected(val status: Int, val code: ErrorCode?, val rawCode: String?, val retryable: Boolean) : GatewayResult<Nothing>

    /**
     * 网关回了 2xx，但内容不符合合同（缺字段、类型不对、版本不是 1.2、回答的不是我们问的那个 Record）。
     * 对“提交”来说这等于结果不确定：请求确实送到了，网关可能已经入库。
     */
    data class Garbled(val status: Int, val reason: String) : GatewayResult<Nothing>

    /** 没有拿到响应（连不上、指纹不符、中途断开、超时）。 */
    data class TransportFailed(val failure: TransportFailure) : GatewayResult<Nothing>
}

/** POST /v1/pairing/redeem 的响应（pairing.schema.json#/$defs/redeem_response）。令牌只在这里出现一次。 */
class RedeemResponse(val gatewayId: String, val deviceId: String, val deviceToken: DeviceToken, val pairedAt: Instant) {
    override fun toString() = "RedeemResponse(gatewayId=$gatewayId, deviceId=$deviceId, token=redacted)"
}

/** 手机上报给网关、用于在网关命令行里辨认设备的信息（pairing.schema.json#/$defs/redeem_request.device）。 */
data class DeviceInfo(val appVersion: String, val osBuild: String, val displayName: String) {
    init {
        require(appVersion.length in 1..64) { "app_version 长度必须在 1..64" }
        require(osBuild.length in 1..100) { "os_build 长度必须在 1..100" }
        require(displayName.length in 1..100) { "display_name 长度必须在 1..100" }
    }
}

/** 要提交的一条 Revision：路径参数、预期哈希与已经序列化好的请求体（revision.schema.json#/$defs/submission）。 */
class RevisionSubmission(val recordId: String, val revision: Int, val textSha256: String, val body: ByteArray) {
    init {
        require(Wire.UUID.matches(recordId)) { "record_id 必须是小写 UUID" }
        require(revision >= 1) { "revision 至少为 1" }
        require(Wire.SHA256.matches(textSha256)) { "text_sha256 必须是 64 位小写十六进制" }
    }

    override fun toString() = "RevisionSubmission($recordId#$revision)"
}

/**
 * 网关回答里的一份 Delivery（revision.schema.json 里入库结果与追加结果的 deliveries 项）。[state] 是网关的原话，不在这里翻译。
 * 入库结果里还可能有因这次发布而被取代的旧版本的 Delivery（[revision] 是旧版本号），所以用之前先看 [revision]。
 */
data class DeliveryOnRevision(val deliveryId: String, val destinationId: String, val revision: Int, val state: String)

/** PUT 入库成功（revision.schema.json#/$defs/stored）。[duplicate] = 这是重复提交，返回的是首次入库的结果；[deliveries] 是首次入库时创建的那些。 */
data class StoredResponse(
    val operationId: String,
    val recordId: String,
    val revision: Int,
    val textSha256: String,
    val storedAt: Instant,
    val duplicate: Boolean,
    val deliveries: List<DeliveryOnRevision> = emptyList(),
)

/** 追加 Delivery 成功（revision.schema.json#/$defs/deliveries_added）：请求里每个 Destination 一项（新建的与本来就有的）。 */
data class DeliveriesAdded(val recordId: String, val revision: Int, val deliveries: List<DeliveryOnRevision>)

/** DELETE /v1/records/{id} 的响应（revision.schema.json#/$defs/record_deleted）。 */
data class RecordDeletedResponse(val recordId: String, val deletedAt: Instant, val cancelledDeliveryIds: List<String>)

/** 网关上记着的一条 Revision（gateway.schema.json#/$defs/revision_status_summary）。 */
data class RevisionOnGateway(val revision: Int, val state: String, val textSha256: String)

/**
 * GET /v1/records/{id}/status 里手机关心的部分：这条 Record 还在不在、网关记了哪些 Revision、各自的文字哈希，
 * 以及（Ticket 22）每个 Destination 上的投递状态 [destinations]（读不懂的条目被丢掉，字段缺失按没有投递算；同步引擎只看 [revisions]）。
 */
data class RecordStatus(
    val recordId: String,
    val originDeviceId: String,
    val deleted: Boolean,
    val revisions: List<RevisionOnGateway>,
    val destinations: List<DeliveryOnGateway> = emptyList(),
)

/** 网关通告的合同版本对手机意味着什么（GET /v1/capabilities 的 supported_schema_versions；评审 R7）。 */
enum class ContractVerdict {
    /** 通告里有手机要的版本：可以说话。 */
    COMPATIBLE,

    /** 通告的全是更旧的版本：要先更新网关，一个数据请求都不能发。 */
    TOO_OLD,

    /** 无法判断（只有更新的版本、新旧都有但没有手机要的、读不懂的版本号、空列表）：不放行，也不冤枉网关“太旧”。 */
    UNCONFIRMED,
}
