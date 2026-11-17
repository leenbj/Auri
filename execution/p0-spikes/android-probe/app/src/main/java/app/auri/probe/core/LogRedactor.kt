package app.auri.probe.core

/**
 * Redacts personal/identifying fields and Bluetooth MAC addresses from raw probe JSONL lines
 * before any log snippets leave the test machine (CLAUDE.md, PRD §20).
 */
object LogRedactor {
    private val macRegex = Regex("""(?i)\b([0-9a-f]{2}[:-]){5}[0-9a-f]{2}\b""")

    private val sensitiveKeys = setOf(
        "address",
        "address_tail",
        "mac",
        "device_name",
        "product_name",
        "display_name",
        "uid",
        "serial",
        "build_fingerprint",
        "transcript",
        "text",
        "audio_base64",
    )

    private val sensitiveFieldRegex: Regex = run {
        val keysPattern = sensitiveKeys.joinToString("|") { Regex.escape(it) }
        Regex(""""($keysPattern)"\s*:\s*"((?:\\.|[^"\\])*)"""")
    }

    fun redactLine(jsonLine: String): String {
        val fieldsRedacted = sensitiveFieldRegex.replace(jsonLine) { match ->
            val key = match.groupValues[1]
            "\"$key\":\"<REDACTED>\""
        }
        return macRegex.replace(fieldsRedacted, "<REDACTED_MAC>")
    }

    fun redactLines(lines: List<String>): List<String> = lines.map(::redactLine)
}
