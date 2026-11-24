package app.auri.sync.revisions

import app.auri.capture.contract.CaptureStatus
import app.auri.capture.contract.EndReason
import app.auri.capture.contract.RevisionSource
import app.auri.capture.contract.ReviewState
import app.auri.capture.testing.Samples
import app.auri.sync.engine.BlockedReason
import app.auri.sync.engine.JobKey
import app.auri.sync.engine.SyncJob
import app.auri.sync.machine.SyncState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-TXT-004：按时间、关键词、发送状态筛选；导出为 TXT 和 Markdown；文件名不得泄露正文敏感信息。 */
class RecordFilterAndExportTest {
    private val t1 = Instant.parse("2026-11-20T08:00:00Z")
    private val t2 = Instant.parse("2026-11-22T09:30:00Z")
    private val t3 = Instant.parse("2026-11-24T18:00:00Z")

    private fun entryWithText(seed: Int, startedAt: Instant, vararg texts: String) = Samples.entry(seed, startedAt = startedAt).let { base ->
        val revisions = texts.mapIndexed { idx, text ->
            Samples.draft(base.recordId, text = text, at = startedAt.plusSeconds(idx + 1L))
                .copy(
                    source = if (idx == 0) RevisionSource.ASR else RevisionSource.USER_EDIT,
                    reviewState = if (idx == 0) ReviewState.NOT_REVIEWED else ReviewState.REVIEWED,
                )
                .toRevision(idx + 1)
        }
        base.copy(revisions = revisions)
    }

    private fun job(recordId: String, rev: Int, state: SyncState, reason: BlockedReason? = null) =
        SyncJob(JobKey(recordId, rev), state, attempts = 1, uncertain = false, retryAt = null, blockedReason = reason, createdAt = t1, updatedAt = t1)

    @Test
    fun `按时间范围、关键词和发送状态组合筛选`() {
        val r1 = entryWithText(1, t1, "买牛奶和咖啡")
        val r2 = entryWithText(2, t2, "初稿买茶叶", "修订为买绿茶和咖啡")
        val r3 = entryWithText(3, t3, "周会纪要：完成发布")
        val records = listOf(r3, r2, r1)
        val jobs = listOf(
            job(r1.recordId, 1, SyncState.STORED),
            job(r2.recordId, 2, SyncState.WAITING_GATEWAY),
            job(r3.recordId, 1, SyncState.BLOCKED, BlockedReason.REQUEST_REJECTED),
        )

        // 时间筛选
        val byTime = RecordFilter.apply(records, jobs, emptySet(), RecordQuery(from = t2, to = t3))
        assertEquals(listOf(r3.recordId, r2.recordId), byTime.map { it.recordId })

        // 关键词筛选（不区分大小写，覆盖历史修订）
        val byKeyword = RecordFilter.apply(records, jobs, emptySet(), RecordQuery(keyword = "咖啡"))
        assertEquals(listOf(r2.recordId, r1.recordId), byKeyword.map { it.recordId })
        val byOldRevisionKeyword = RecordFilter.apply(records, jobs, emptySet(), RecordQuery(keyword = "茶叶"))
        assertEquals(listOf(r2.recordId), byOldRevisionKeyword.map { it.recordId })

        // 发送状态筛选
        assertEquals(listOf(r1.recordId), RecordFilter.apply(records, jobs, emptySet(), RecordQuery(sendStatus = SendStatusFilter.STORED)).map { it.recordId })
        assertEquals(listOf(r2.recordId), RecordFilter.apply(records, jobs, emptySet(), RecordQuery(sendStatus = SendStatusFilter.WAITING_GATEWAY)).map { it.recordId })
        assertEquals(listOf(r3.recordId), RecordFilter.apply(records, jobs, emptySet(), RecordQuery(sendStatus = SendStatusFilter.NEEDS_USER)).map { it.recordId })
        assertEquals(
            listOf(r1.recordId),
            RecordFilter.apply(records, jobs, setOf(JobKey(r1.recordId, 1)), RecordQuery(sendStatus = SendStatusFilter.AWAITING_CONFIRM)).map { it.recordId },
        )
    }

    @Test
    fun `导出为 TXT 与 Markdown 包含正文、修订历史与截断标注，且文件名绝不泄露正文`() {
        val secretText = "绝密口述正文XYZ98765"
        val partial = entryWithText(1, t3, "初稿文字", secretText).copy(
            status = CaptureStatus.PARTIAL,
            endReason = EndReason.ROUTE_LOST,
            headsetDisplayName = "我的秘密耳机名称",
        )

        val txt = RecordExporter.format(listOf(partial), ExportFormat.TXT)
        assertTrue(txt.contains(secretText))
        assertTrue(txt.contains("初稿文字"))
        assertTrue(txt.contains("partial") || txt.contains("被截断"))

        val md = RecordExporter.format(listOf(partial), ExportFormat.MARKDOWN)
        assertTrue(md.startsWith("# "))
        assertTrue(md.contains(secretText))
        assertTrue(md.contains("初稿文字"))

        val txtName = RecordExporter.safeFileName(ExportFormat.TXT, t3)
        val mdName = RecordExporter.safeFileName(ExportFormat.MARKDOWN, t3, singleRecordId = partial.recordId)
        assertTrue(txtName.endsWith(".txt"))
        assertTrue(mdName.endsWith(".md"))
        assertFalse("文件名不得泄露正文", txtName.contains(secretText) || mdName.contains(secretText))
        assertFalse("文件名不得泄露耳机名", txtName.contains("秘密耳机") || mdName.contains("秘密耳机"))
        assertTrue("文件名仅含安全字符", Regex("^[a-z0-9._-]+$").matches(txtName))
        assertTrue("文件名仅含安全字符", Regex("^[a-z0-9._-]+$").matches(mdName))
    }
}
