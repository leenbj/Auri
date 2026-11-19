package app.auri.sync.gateway

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** common.schema.json 里这几个格式的权威写法（uuid / sha256_hex / timestamp / schema_version）。 */
internal object Wire {
    const val SCHEMA_VERSION = "1.1"
    val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    val SHA256 = Regex("^[0-9a-f]{64}$")
    val TIMESTAMP = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?(Z|[+-][0-9]{2}:[0-9]{2})$")
}

/** 网关的响应读不懂。[field] 只放字段名，绝不放响应里的内容（响应里可能有令牌）。 */
internal class Unreadable(val field: String) : RuntimeException(field)

/**
 * 按合同严格读取网关响应里的一个 JSON 对象：字段必须在、类型必须对（字符串就是字符串，数字就是数字，不做宽松转换）。
 * 多出来的字段忽略（响应方向对新增字段宽容），缺的或错的一律 [Unreadable]。
 */
internal class WireObject private constructor(private val root: JsonObject) {
    fun string(key: String): String = primitive(key).takeIf { it.isString }?.content ?: throw Unreadable(key)

    fun uuid(key: String): String = string(key).takeIf { Wire.UUID.matches(it) } ?: throw Unreadable(key)

    fun sha256(key: String): String = string(key).takeIf { Wire.SHA256.matches(it) } ?: throw Unreadable(key)

    fun timestamp(key: String): Instant {
        val text = string(key).takeIf { Wire.TIMESTAMP.matches(it) } ?: throw Unreadable(key)
        return try {
            OffsetDateTime.parse(text).toInstant()
        } catch (_: DateTimeParseException) {
            throw Unreadable(key)
        }
    }

    fun int(key: String): Int = primitive(key).takeIf { !it.isString }?.intOrNull ?: throw Unreadable(key)

    fun boolean(key: String): Boolean = primitive(key).takeIf { !it.isString }?.booleanOrNull ?: throw Unreadable(key)

    fun array(key: String): JsonArray = root[key] as? JsonArray ?: throw Unreadable(key)

    fun requireVersion() {
        if (string("schema_version") != Wire.SCHEMA_VERSION) throw Unreadable("schema_version")
    }

    private fun primitive(key: String): JsonPrimitive = root[key] as? JsonPrimitive ?: throw Unreadable(key)

    companion object {
        fun parse(body: ByteArray): WireObject {
            val element = try {
                Json.parseToJsonElement(body.toString(Charsets.UTF_8))
            } catch (_: IllegalArgumentException) { // SerializationException 是它的子类
                throw Unreadable("body")
            }
            return WireObject(element as? JsonObject ?: throw Unreadable("body"))
        }
    }
}
