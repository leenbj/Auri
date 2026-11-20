package app.auri.sync.gateway

import java.time.Instant

/**
 * 发送方式（合同 common.schema.json#/$defs/send_mode）。创建 Destination 时必须显式选一个：这是用户对“转录出来的文字可以自动发给这个 Agent”
 * 的独立授权（ADR-0009），不存在隐含默认值——所以这个类型在整个代码里没有任何一处给它取默认。
 */
enum class SendMode(val wire: String) {
    AUTO_SEND("auto_send"),
    PREVIEW_CONFIRM("preview_confirm"),
    ;

    companion object {
        fun fromWire(value: String): SendMode? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * 一条文字的意图标签（合同 common.schema.json#/$defs/intent）：`context` 是备忘，`action_request` 是“请求执行”。
 * 它只是**数据**上的一个标签：网关据此决定哪些 Destination 收得到，Agent 据此知道用户的本意——它不授予 Agent 任何额外权限，
 * 转录文本也从来不是指令（SEC-004）。
 */
enum class Intent(val wire: String) {
    CONTEXT("context"),
    ACTION_REQUEST("action_request"),
    ;

    companion object {
        fun fromWire(value: String): Intent? = entries.firstOrNull { it.wire == value }
    }
}

/** 网关上的一个 Destination（destination.schema.json#/$defs/resource）。里面没有任何机密：凭证只在签发时出现一次。 */
data class Destination(
    val destinationId: String,
    val displayName: String,
    val sendMode: SendMode,
    val allowedIntents: Set<Intent>,
    val createdAt: Instant,
    val createdByDeviceId: String,
    val credentialIssued: Boolean,
    val revokedAt: Instant?,
) {
    val active: Boolean get() = revokedAt == null

    fun allows(intent: Intent): Boolean = intent in allowedIntents
}

/**
 * POST /v1/destinations 的请求（destination.schema.json#/$defs/create_request）。
 * **没有任何默认参数**：发送方式与允许的意图都必须由调用方（最终是用户）明确给出。
 */
class CreateDestinationRequest(val displayName: String, val sendMode: SendMode, val allowedIntents: Set<Intent>) {
    init {
        require(displayName.codePointCount(0, displayName.length) in 1..MAX_NAME) { "display_name 必须是 1..$MAX_NAME 个字符" }
        require(allowedIntents.isNotEmpty()) { "allowed_intents 至少要有一种意图" }
    }

    override fun toString() = "CreateDestinationRequest(sendMode=$sendMode, intents=${allowedIntents.map { it.wire }})"

    companion object {
        const val MAX_NAME = 100
    }
}

/**
 * 网关发给 Agent 的 Destination 凭证。只有一个出口 [reveal]（交给用户去复制）；toString 永远不含内容，
 * 日志、异常消息、调试输出里即使不小心带上它也不会泄露。**不要持久化它**：它只在签发的那一刻存在于内存。
 */
class DestinationCredential private constructor(private val value: String) {
    fun reveal(): String = value

    override fun toString() = "DestinationCredential(redacted)"

    override fun equals(other: Any?) = other is DestinationCredential && other.value == value

    override fun hashCode() = value.hashCode()

    companion object {
        // 合同：32..512 个字符。再收紧到“可见 ASCII、没有空白”，凭证才不可能夹带换行去改写别处的请求头
        private val FORMAT = Regex("^[\\x21-\\x7e]{32,512}$")

        fun parse(raw: String): DestinationCredential? = if (FORMAT.matches(raw)) DestinationCredential(raw) else null
    }
}

/** POST /v1/destinations/{id}/credential 的响应（destination.schema.json#/$defs/credential_response）。[replacedPrevious] = 这是轮换，旧凭证已失效。 */
data class IssuedCredential(val destinationId: String, val credential: DestinationCredential, val issuedAt: Instant, val replacedPrevious: Boolean)

/**
 * 状态查询里一个 Destination 上的一次投递（gateway.schema.json#/$defs/record_status_response.destinations）。
 * [state] 是网关的原话（`PENDING`/`LEASED`/`ACKNOWLEDGED`/…），不在这里翻译：没见过的值要如实带到界面显示成“未知”，不能悄悄当成功。
 */
data class DeliveryOnGateway(val destinationId: String, val destinationDisplayName: String, val deliveryId: String, val revision: Int, val state: String)
