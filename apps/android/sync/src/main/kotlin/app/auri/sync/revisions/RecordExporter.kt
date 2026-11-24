package app.auri.sync.revisions

import app.auri.capture.contract.CaptureStatus
import app.auri.capture.contract.RevisionSource
import app.auri.capture.contract.ReviewState
import app.auri.capture.library.RecordEntry
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** 支持的导出格式（FR-TXT-004）。 */
enum class ExportFormat(val extension: String, val mimeType: String) {
    TXT("txt", "text/plain"),
    MARKDOWN("md", "text/markdown"),
}

/**
 * 导出 Record 为 TXT 或 Markdown（FR-TXT-004）。
 * 文件名仅由固定前缀、UTC 时间戳与可选的短 record_id 组成，绝不包含正文或耳机名等敏感信息。
 */
object RecordExporter {
    private val FILE_TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    private val SAFE_ID = Regex("^[0-9a-f]{8}")

    fun safeFileName(format: ExportFormat, now: Instant, singleRecordId: String? = null): String {
        val ts = FILE_TS.format(now)
        val shortId = singleRecordId?.lowercase()?.take(8)?.takeIf { SAFE_ID.matches(it) }
        val base = if (shortId != null) "auri-record-$shortId-$ts" else "auri-export-$ts"
        return "$base.${format.extension}"
    }

    fun format(records: List<RecordEntry>, format: ExportFormat): String = when (format) {
        ExportFormat.TXT -> formatTxt(records)
        ExportFormat.MARKDOWN -> formatMarkdown(records)
    }

    private fun formatTxt(records: List<RecordEntry>): String = buildString {
        appendLine("Auri 导出记录（共 ${records.size} 条）")
        appendLine("=".repeat(40))
        for ((index, entry) in records.withIndex()) {
            if (index > 0) appendLine("-".repeat(40))
            appendLine("时间：${entry.startedAt}")
            appendLine("时长：${entry.captureDurationMs / 1000.0} 秒")
            if (entry.status == CaptureStatus.PARTIAL) {
                appendLine("状态：被截断（${entry.endReason.name.lowercase()}）")
            }
            appendLine("本地音频：${if (entry.audio != null) "保留" else "已删除或无音频"}")
            val latest = entry.revisions.lastOrNull()
            if (latest == null) {
                appendLine("正文：（无文字）")
            } else {
                appendLine("当前版本：第 ${latest.number} 版（${describeSource(latest.source)} / ${describeReview(latest.reviewState)}）")
                appendLine(latest.text)
                if (entry.revisions.size > 1) {
                    appendLine()
                    appendLine("历史修订：")
                    for (rev in entry.revisions.dropLast(1)) {
                        appendLine("  [第 ${rev.number} 版 ${describeSource(rev.source)}] ${rev.text}")
                    }
                }
            }
        }
    }

    private fun formatMarkdown(records: List<RecordEntry>): String = buildString {
        appendLine("# Auri 导出记录（共 ${records.size} 条）")
        appendLine()
        for (entry in records) {
            appendLine("## ${entry.startedAt}")
            appendLine()
            appendLine("- 时长：${entry.captureDurationMs / 1000.0} 秒")
            if (entry.status == CaptureStatus.PARTIAL) {
                appendLine("- 状态：被截断（`${entry.endReason.name.lowercase()}`）")
            }
            appendLine("- 本地音频：${if (entry.audio != null) "保留" else "已删除或无音频"}")
            val latest = entry.revisions.lastOrNull()
            if (latest == null) {
                appendLine()
                appendLine("（无文字）")
            } else {
                appendLine("- 当前版本：第 ${latest.number} 版（${describeSource(latest.source)} / ${describeReview(latest.reviewState)}）")
                appendLine()
                appendLine(latest.text)
                if (entry.revisions.size > 1) {
                    appendLine()
                    appendLine("### 历史修订")
                    appendLine()
                    for (rev in entry.revisions.dropLast(1)) {
                        appendLine("- **第 ${rev.number} 版**（${describeSource(rev.source)}）：${rev.text}")
                    }
                }
            }
            appendLine()
        }
    }

    private fun describeSource(source: RevisionSource): String = when (source) {
        RevisionSource.ASR -> "语音转录"
        RevisionSource.USER_EDIT -> "用户修订"
    }

    private fun describeReview(state: ReviewState): String = when (state) {
        ReviewState.NOT_REVIEWED -> "未审核"
        ReviewState.REVIEWED -> "已审核"
    }
}
