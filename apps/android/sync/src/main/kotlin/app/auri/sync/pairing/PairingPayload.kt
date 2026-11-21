package app.auri.sync.pairing

import app.auri.sync.gateway.Wire
import app.auri.sync.tls.SpkiPin
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** 局域网里的一个网关地址（pairing.schema.json#/$defs/address）。 */
data class GatewayAddress(val host: String, val port: Int) {
    override fun toString() = "$host:$port"

    companion object {
        // 主机名或 IP 字面量（含 IPv6 与区域标识 %wlan0）。不含空白、斜杠、@、?、#、方括号：
        // 它会被写进 Host 头，CR/LF 与分隔符进来就是请求走私的入口。
        private val HOST = Regex("^[A-Za-z0-9._:%-]{1,253}$")

        fun isValidHost(host: String) = HOST.matches(host)

        fun isValidPort(port: Int) = port in 1..65535
    }
}

/** 载荷哪里不合格。只有类别，不带载荷里的任何内容（配对码是秘密，日志与界面都不该看到它）。 */
enum class PayloadProblem {
    TOO_LARGE,
    NOT_JSON,
    NOT_AN_OBJECT,
    MISSING_FIELD,
    UNKNOWN_FIELD,
    WRONG_SCHEMA_VERSION,

    /** 二维码是合同 1.2 之前的网关出的（评审 R7）：不是二维码坏了，是网关要先更新；这时一个请求都不该发。 */
    GATEWAY_TOO_OLD,
    BAD_GATEWAY_ID,
    BAD_ADDRESSES,
    BAD_HOST,
    BAD_PORT,
    BAD_SERVICE_TYPE,
    BAD_FINGERPRINT,
    BAD_PAIRING_CODE,
    BAD_EXPIRY,
}

sealed interface PayloadParse {
    data class Valid(val payload: PairingPayload) : PayloadParse

    data class Invalid(val problem: PayloadProblem) : PayloadParse
}

/**
 * 网关命令行生成、二维码里携带的配对载荷（pairing.schema.json#/$defs/payload，合同 1.2）。
 *
 * 载荷来自“摄像头看到的任何东西”，所以按合同逐项严格校验：字段不多不少（additionalProperties 为 false）、版本只认 1.2、
 * 地址里不能夹带换行（我们自己拼 Host 头）。校验不过就拒绝，不猜、不容忍。
 * 过期是另一回事（取决于现在的时间）：用 [isExpired] 判断。
 */
class PairingPayload(
    val gatewayId: String,
    val addresses: List<GatewayAddress>,
    val mdnsServiceType: String,
    val publicKeySha256: SpkiPin,
    /** 一次性配对码：秘密。不进日志、不进 toString。 */
    val pairingCode: String,
    val expiresAt: Instant,
) {
    /** 到点即过期（与网关一致：expires_at 不晚于现在就作废）。用手机时钟判断，只是为了早点给用户一个明确的提示；最终以网关为准。 */
    fun isExpired(now: Instant): Boolean = !now.isBefore(expiresAt)

    override fun toString() = "PairingPayload(gatewayId=$gatewayId, addresses=${addresses.size}, expiresAt=$expiresAt, pairingCode=***)"

    companion object {
        const val SERVICE_TYPE = "_auri-gateway._tcp"
        const val SCHEMA_VERSION = "1.2"

        // 一份合法载荷（8 个地址）不到 1 KiB；留足余量但不允许把任意大的东西喂给解析器
        private const val MAX_INPUT_CHARS = 4096
        private const val MAX_ADDRESSES = 8

        private val TOP_LEVEL_KEYS =
            setOf("schema_version", "gateway_id", "addresses", "mdns_service_type", "public_key_sha256", "pairing_code", "expires_at")
        private val ADDRESS_KEYS = setOf("host", "port")
        private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private val TIMESTAMP = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?(Z|[+-][0-9]{2}:[0-9]{2})$")

        fun parse(text: String): PayloadParse {
            val trimmed = text.trim()
            if (trimmed.length > MAX_INPUT_CHARS) return invalid(PayloadProblem.TOO_LARGE)
            val element = try {
                Json.parseToJsonElement(trimmed)
            } catch (_: SerializationException) {
                return invalid(PayloadProblem.NOT_JSON)
            } catch (_: IllegalArgumentException) {
                return invalid(PayloadProblem.NOT_JSON)
            }
            val root = element as? JsonObject ?: return invalid(PayloadProblem.NOT_AN_OBJECT)
            if (!root.keys.all { it in TOP_LEVEL_KEYS }) return invalid(PayloadProblem.UNKNOWN_FIELD)
            if (!root.keys.containsAll(TOP_LEVEL_KEYS)) return invalid(PayloadProblem.MISSING_FIELD)

            val version = root.string("schema_version")
            if (version != SCHEMA_VERSION) return invalid(if (version in Wire.OLDER_VERSIONS) PayloadProblem.GATEWAY_TOO_OLD else PayloadProblem.WRONG_SCHEMA_VERSION)
            val gatewayId = root.string("gateway_id")?.takeIf { UUID.matches(it) } ?: return invalid(PayloadProblem.BAD_GATEWAY_ID)

            val rawAddresses = root.getValue("addresses") as? JsonArray
            if (rawAddresses == null || rawAddresses.size !in 1..MAX_ADDRESSES) return invalid(PayloadProblem.BAD_ADDRESSES)
            val addresses = rawAddresses.map { item ->
                when (val parsed = parseAddress(item)) {
                    is AddressParse.Ok -> parsed.address
                    is AddressParse.Bad -> return invalid(parsed.problem)
                }
            }

            if (root.string("mdns_service_type") != SERVICE_TYPE) return invalid(PayloadProblem.BAD_SERVICE_TYPE)
            val pin = root.string("public_key_sha256")?.let { SpkiPin.parse(it) } ?: return invalid(PayloadProblem.BAD_FINGERPRINT)
            val code = root.string("pairing_code")?.takeIf { it.length in 16..128 } ?: return invalid(PayloadProblem.BAD_PAIRING_CODE)
            val expiresAt = root.string("expires_at")?.takeIf { TIMESTAMP.matches(it) }?.let(::parseInstant)
                ?: return invalid(PayloadProblem.BAD_EXPIRY)

            return PayloadParse.Valid(PairingPayload(gatewayId, addresses, SERVICE_TYPE, pin, code, expiresAt))
        }

        private sealed interface AddressParse {
            data class Ok(val address: GatewayAddress) : AddressParse

            data class Bad(val problem: PayloadProblem) : AddressParse
        }

        private fun parseAddress(item: JsonElement): AddressParse {
            val obj = item as? JsonObject ?: return AddressParse.Bad(PayloadProblem.BAD_ADDRESSES)
            if (!obj.keys.all { it in ADDRESS_KEYS }) return AddressParse.Bad(PayloadProblem.UNKNOWN_FIELD)
            val host = obj.string("host")?.takeIf { GatewayAddress.isValidHost(it) } ?: return AddressParse.Bad(PayloadProblem.BAD_HOST)
            val portElement = obj["port"] as? JsonPrimitive
            val port = portElement?.takeIf { !it.isString }?.intOrNull?.takeIf { GatewayAddress.isValidPort(it) }
                ?: return AddressParse.Bad(PayloadProblem.BAD_PORT)
            return AddressParse.Ok(GatewayAddress(host, port))
        }

        private fun parseInstant(text: String): Instant? = try {
            OffsetDateTime.parse(text).toInstant()
        } catch (_: java.time.DateTimeException) {
            null
        }

        /** 只认 JSON 字符串；数字、布尔、null 都不当字符串用。 */
        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun invalid(problem: PayloadProblem) = PayloadParse.Invalid(problem)
    }
}
