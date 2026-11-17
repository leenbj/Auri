package app.auri.probe.core

/**
 * Minimal, dependency-free JSON encoder/decoder for JSON Lines probe logs.
 * Keeps unit tests runnable on plain JVM without Android's stubbed org.json.
 */
object JsonLineCodec {

    fun encodeObject(fields: Map<String, Any?>): String {
        val sb = StringBuilder()
        appendValue(sb, fields)
        return sb.toString()
    }

    private fun appendValue(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (value) "true" else "false")
            is Number -> sb.append(value.toString())
            is String -> appendString(sb, value)
            is Enum<*> -> appendString(sb, value.name)
            is Map<*, *> -> {
                sb.append('{')
                val entries = value.entries
                    .filter { it.key != null }
                    .sortedBy { it.key.toString() }
                entries.forEachIndexed { index, entry ->
                    if (index > 0) sb.append(',')
                    appendString(sb, entry.key.toString())
                    sb.append(':')
                    appendValue(sb, entry.value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in value) {
                    if (!first) sb.append(',')
                    first = false
                    appendValue(sb, item)
                }
                sb.append(']')
            }
            is Array<*> -> {
                sb.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) sb.append(',')
                    appendValue(sb, item)
                }
                sb.append(']')
            }
            else -> appendString(sb, value.toString())
        }
    }

    private fun appendString(sb: StringBuilder, text: String) {
        sb.append('"')
        for (ch in text) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append("\\u")
                        sb.append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
    }
}
