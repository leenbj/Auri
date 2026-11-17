package app.auri.probe

import android.content.Context
import android.os.SystemClock
import app.auri.probe.core.JsonLineCodec
import app.auri.probe.core.LogRedactor
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.util.UUID

/**
 * Append-only JSON Lines probe logger (mirrors ios-probe/Sources/ProbeLogger.swift).
 * Uses SystemClock.elapsedRealtimeNanos() for monotonic ordering across sleep/wake.
 * Never logs audio samples or dictation text.
 */
object ProbeLogger {
    private val lock = Any()
    private var initialized = false
    private var launchId: String = UUID.randomUUID().toString()
    private var logFile: File? = null
    private var redactedFile: File? = null
    private var seq: Long = 0L
    private var currentAppState: String = "unknown"

    @Volatile
    var onLine: ((String) -> Unit)? = null

    fun init(context: Context) {
        synchronized(lock) {
            if (initialized) return
            val dir = context.applicationContext.filesDir
            logFile = File(dir, "probe-log.jsonl")
            redactedFile = File(dir, "probe-log-redacted.jsonl")
            initialized = true
        }
    }

    fun setAppState(state: String) {
        synchronized(lock) {
            currentAppState = state
        }
    }

    fun appState(): String = synchronized(lock) { currentAppState }

    fun monoNanos(): Long = SystemClock.elapsedRealtimeNanos()

    fun log(kind: String, data: Map<String, Any?> = emptyMap()) {
        val line = synchronized(lock) {
            seq += 1L
            val payload = linkedMapOf<String, Any?>(
                "seq" to seq,
                "launch_id" to launchId,
                "mono_ns" to monoNanos(),
                "wall" to Instant.now().toString(),
                "kind" to kind,
                "app_state" to currentAppState,
                "data" to data,
            )
            val encoded = JsonLineCodec.encodeObject(payload)
            logFile?.let { file ->
                runCatching {
                    FileWriter(file, true).use { writer ->
                        writer.write(encoded)
                        writer.write("\n")
                    }
                }
            }
            encoded
        }
        onLine?.invoke(LogRedactor.redactLine(line))
    }

    fun writeRedactedCopy(): String? {
        val (src, dst) = synchronized(lock) { logFile to redactedFile }
        if (src == null || dst == null || !src.exists()) return null
        val redactedLines = synchronized(lock) {
            src.readLines().map(LogRedactor::redactLine)
        }
        dst.writeText(redactedLines.joinToString("\n", postfix = if (redactedLines.isNotEmpty()) "\n" else ""))
        log("redacted_log_written", mapOf("file_name" to dst.name, "line_count" to redactedLines.size))
        return dst.absolutePath
    }
}
