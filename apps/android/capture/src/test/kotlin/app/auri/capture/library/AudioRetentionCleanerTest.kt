package app.auri.capture.library

import app.auri.capture.contract.ErrorCode
import app.auri.capture.contract.RevisionSource
import app.auri.capture.testing.InMemoryRecordLibrary
import app.auri.capture.testing.Samples
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-TXT-005：可单独删除音频而保留文字；可设置音频保留期，默认转录成功后 30 天。
 */
class AudioRetentionCleanerTest {
    @Test
    fun `默认保留期是转录成功后 30 天`() {
        assertEquals(30, AudioRetentionCleaner.DEFAULT_RETENTION_DAYS)
    }

    @Test
    fun `转录成功满 30 天的音频被自动清理并保留文字，未满 30 天与未转录成功的保留音频`() = runBlocking {
        val library = InMemoryRecordLibrary()
        val deletedPaths = mutableListOf<String>()
        val base = Instant.parse("2026-10-01T00:00:00Z")

        val expired = Samples.entry(1, startedAt = base)
        val fresh = Samples.entry(2, startedAt = base.plus(Duration.ofDays(5)))
        val pending = Samples.entry(3, startedAt = base)
        val failed = Samples.entry(4, startedAt = base)
        val noSpeech = Samples.entry(5, startedAt = base)
        listOf(expired, fresh, pending, failed, noSpeech).forEach { library.addRecord(it) }

        library.publishRevision(Samples.draft(expired.recordId, text = "旧文字").copy(createdAt = base.plusSeconds(30)))
        library.publishRevision(Samples.draft(fresh.recordId, text = "新文字").copy(createdAt = base.plus(Duration.ofDays(5))))
        library.markTranscription(failed.recordId, TranscriptionStatus.FAILED, ErrorCode.ASR_RESOURCE_LIMIT)
        library.markTranscription(noSpeech.recordId, TranscriptionStatus.NO_SPEECH)

        val now = base.plus(Duration.ofDays(30)).plusSeconds(30)
        val cleaner = AudioRetentionCleaner(library, deleteFile = { path -> deletedPaths += path; true })
        val report = cleaner.purgeExpired(now = now, retentionDays = AudioRetentionCleaner.DEFAULT_RETENTION_DAYS)

        assertEquals(1, report.purgedCount)
        assertEquals(listOf(expired.audio!!.location), deletedPaths)
        assertNull("到期 Record 的音频已清除", library.get(expired.recordId)!!.audio)
        assertEquals("文字完整保留", "旧文字", library.get(expired.recordId)!!.latestText)
        assertFalse("清理音频不产生墓碑", library.isDeleted(expired.recordId))

        assertNotNull("未到期的保留音频", library.get(fresh.recordId)!!.audio)
        assertNotNull("PENDING 的不按转录成功清理", library.get(pending.recordId)!!.audio)
        assertNotNull("FAILED 的不按转录成功清理", library.get(failed.recordId)!!.audio)
        assertNotNull("NO_SPEECH 的不按转录成功清理", library.get(noSpeech.recordId)!!.audio)
    }

    @Test
    fun `缩短保留期后按新天数清理，且以首次 ASR 转录成功时间起算而非用户修订时间`() = runBlocking {
        val library = InMemoryRecordLibrary()
        val deletedPaths = mutableListOf<String>()
        val base = Instant.parse("2026-10-01T00:00:00Z")
        val entry = Samples.entry(1, startedAt = base)
        library.addRecord(entry)
        library.publishRevision(Samples.draft(entry.recordId, text = "初稿").copy(source = RevisionSource.ASR, createdAt = base.plusSeconds(10)))
        library.publishRevision(Samples.draft(entry.recordId, text = "用户刚改过").copy(source = RevisionSource.USER_EDIT, createdAt = base.plus(Duration.ofDays(6))))

        val now = base.plus(Duration.ofDays(7)).plusSeconds(10)
        val cleaner = AudioRetentionCleaner(library, deleteFile = { path -> deletedPaths += path; true })
        val report = cleaner.purgeExpired(now = now, retentionDays = 7)

        assertEquals(1, report.purgedCount)
        assertEquals(listOf(entry.audio!!.location), deletedPaths)
        assertNull(library.get(entry.recordId)!!.audio)
        assertEquals(2, library.get(entry.recordId)!!.revisions.size)
    }
}
