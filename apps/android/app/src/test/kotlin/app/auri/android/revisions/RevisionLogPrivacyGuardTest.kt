package app.auri.android.revisions

import app.auri.android.netguard.SourceText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 23 隐私与文案守卫（FR-TXT-002 / FR-TXT-005 / SEC-003）：
 * 1. 界面必须明确告知 Agent 已读走的内容无法收回，不得声称“撤回所有 Agent 记忆”或把确认说成“已处理”；
 * 2. Ticket 23 新增生产代码里不得把正文、音频路径、凭证、令牌或目的地名称写进日志。
 */
class RevisionLogPrivacyGuardTest {
    private val androidDir = File("..")

    @Test
    fun `界面文案明确说明 Agent 已读走的内容无法收回，且不夸大能力`() {
        val notice = RevisionDescriptions.DELETE_RECORD_NOTICE
        assertTrue("必须提到 Agent 已读走的内容无法收回：$notice", notice.contains("Agent") && notice.contains("无法收回"))
        assertFalse("不得声称撤回所有 Agent 记忆", notice.contains("撤回所有"))
        assertFalse("不得出现“已处理”", notice.contains("已处理"))
        assertTrue("必须说明新版本不自动重发", RevisionDescriptions.NO_AUTO_RESEND_NOTICE.contains("不会自动重发"))
    }

    @Test
    fun `Ticket 23 新增生产源码的日志调用不插值正文、音频路径、凭证或目的地名`() {
        val files = listOf(
            "capture/src/main/kotlin/app/auri/capture/library/AudioRetentionCleaner.kt",
            "platform/src/main/kotlin/app/auri/platform/storage/AudioRetentionSetting.kt",
            "sync/src/main/kotlin/app/auri/sync/engine/PendingDeletions.kt",
            "sync/src/main/kotlin/app/auri/sync/revisions/RecordRevisionService.kt",
            "sync/src/main/kotlin/app/auri/sync/revisions/RecordFilter.kt",
            "sync/src/main/kotlin/app/auri/sync/revisions/RecordExporter.kt",
            "syncplatform/src/main/kotlin/app/auri/syncplatform/jobs/SqlitePendingDeletions.kt",
            "app/src/main/kotlin/app/auri/android/revisions/RevisionDescriptions.kt",
            "app/src/main/kotlin/app/auri/android/revisions/RecordsViewModel.kt",
            "app/src/main/kotlin/app/auri/android/revisions/RecordDetailViewModel.kt",
            "app/src/main/kotlin/app/auri/android/ui/RecordsPage.kt",
        ).map { File(androidDir, it) }

        val forbidden = listOf("text", "draft", "audio", "location", "path", "credential", "token", "displayName", "destinationName", "message")
        val violations = mutableListOf<String>()
        for (file in files) {
            assertTrue("文件应当存在：${file.path}", file.isFile)
            file.readLines().forEachIndexed { index, raw ->
                val trimmed = raw.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed
                if (trimmed.contains("Log.") || trimmed.contains("log.event(")) {
                    for (bad in forbidden) {
                        if (Regex("""\$\{?[^}"\s]*$bad""", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) {
                            violations += "${file.name}:${index + 1}: 日志疑似插值敏感字段 '$bad': $trimmed"
                        }
                    }
                }
            }
        }
        assertEquals("日志不得包含正文、音频路径、凭证或目的地名", emptyList<String>(), violations)
    }
}
