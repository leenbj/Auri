package app.auri.capture.testing

import app.auri.capture.contract.EndReason
import app.auri.capture.contract.ErrorCode
import app.auri.capture.library.NoSuchRecordException
import app.auri.capture.library.RecordLibrary
import app.auri.capture.library.Revision
import app.auri.capture.library.TranscriptionStatus
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 任何 [RecordLibrary] 实现都必须满足的行为：JVM 里的内存库和模拟器/真机上的 SQLite 库跑同一份。
 * 用 runBlocking（不用虚拟时间）是为了能在 Android 仪器测试里原样复用。
 */
abstract class RecordLibraryContract {
    /** 造一个干净、空的库。[publicationStep] 在每次发布 Revision 的事务里被调用，抛异常表示事务失败。 */
    protected abstract fun newLibrary(publicationStep: (Revision) -> Unit = {}): RecordLibrary

    private fun <T> run(block: suspend () -> T): T = runBlocking { withTimeout(30_000) { block() } }

    @Test
    fun `同一个转录任务重放发布只返回原来的 Revision`() = run {
        var publications = 0
        val library = newLibrary { publications++ }
        val entry = Samples.entry(1)
        library.addRecord(entry)
        val draft = Samples.draft(entry.recordId, text = "原结果").copy(publicationKey = Samples.uuid(90))

        val first = library.publishRevision(draft)
        val replay = library.publishRevision(draft.copy(text = "重放得到的另一段文字"))
        val next = library.publishRevision(draft.copy(publicationKey = Samples.uuid(91)))

        assertEquals(first, replay)
        assertEquals(2, next.number)
        assertEquals(listOf(first, next), library.get(entry.recordId)!!.revisions)
        assertEquals(2, publications)
    }

    @Test
    fun `登记之后能取回：一个 Record，没有 Revision，状态是 PENDING，与音频同一个标识`() = run {
        val library = newLibrary()
        val entry = Samples.entry(1)

        assertTrue(library.addRecord(entry))

        val stored = checkNotNull(library.get(entry.recordId))
        assertEquals(entry, stored)
        assertEquals(TranscriptionStatus.PENDING, stored.transcription)
        assertTrue(stored.revisions.isEmpty())
        assertEquals("音频位置里带着同一个标识", entry.recordId, stored.audio?.location?.substringAfterLast('/')?.removeSuffix(".wav"))
    }

    @Test
    fun `同一个 recordId 登记两次：第二次什么都不改，也不报错`() = run {
        val library = newLibrary()
        val first = Samples.entry(1)
        library.addRecord(first)
        library.publishRevision(Samples.draft(first.recordId))

        val again = library.addRecord(first.copy(endReason = EndReason.ROUTE_LOST))

        assertFalse(again)
        val stored = checkNotNull(library.get(first.recordId))
        assertEquals("已有的 Record 与它的 Revision 都原样保留", EndReason.SCREEN_CONTROL, stored.endReason)
        assertEquals(1, stored.revisions.size)
    }

    @Test
    fun `发布 Revision：number 从 1 递增，旧的一字不改，Record 变成 DONE`() = run {
        val library = newLibrary()
        val entry = Samples.entry(1)
        library.addRecord(entry)

        val first = library.publishRevision(Samples.draft(entry.recordId, text = "第一版"))
        val second = library.publishRevision(Samples.draft(entry.recordId, text = "第二版"))

        assertEquals(1, first.number)
        assertEquals(2, second.number)
        val stored = checkNotNull(library.get(entry.recordId))
        assertEquals(listOf(first, second), stored.revisions)
        assertEquals("第一版", stored.revisions[0].text)
        assertEquals("第二版", stored.latestText)
        assertEquals(TranscriptionStatus.DONE, stored.transcription)
    }

    @Test
    fun `Revision 写明用了哪个模型，哈希是正文 UTF-8 原始字节的 SHA-256`() = run {
        val library = newLibrary()
        val entry = Samples.entry(1)
        library.addRecord(entry)
        val text = "Tab\t换行\n引号\"表情😀 \u0000尾部空格  "

        val revision = library.publishRevision(Samples.draft(entry.recordId, text = text))

        val stored = checkNotNull(library.get(entry.recordId)).revisions.single()
        assertEquals("正文原样存取，不做任何规范化", text, stored.text)
        val expected = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        assertEquals(expected, stored.textSha256)
        assertEquals(revision, stored)
        assertEquals("fixture-engine", stored.engine)
        assertEquals("fixture-model", stored.modelId)
        assertEquals("1.0.0", stored.modelVersion)
    }

    @Test
    fun `给不存在的 Record 发布：抛 NoSuchRecordException，库里没有任何东西`() = run {
        val library = newLibrary()

        try {
            library.publishRevision(Samples.draft(Samples.uuid(99)))
            fail("应当抛 NoSuchRecordException")
        } catch (expected: NoSuchRecordException) {
            assertEquals(Samples.uuid(99), expected.recordId)
        }
        assertTrue(library.observe().first().isEmpty())
    }

    @Test
    fun `发布步骤失败：整个事务回滚，Record 保持 PENDING 没有 Revision，number 没被占用`() = run {
        var fail = true
        val library = newLibrary { if (fail) throw IllegalStateException("待同步任务入队失败（测试注入）") }
        val entry = Samples.entry(1)
        library.addRecord(entry)

        try {
            library.publishRevision(Samples.draft(entry.recordId))
            fail("发布步骤失败时 publishRevision 必须抛出")
        } catch (expected: IllegalStateException) {
            // 预期
        }

        val untouched = checkNotNull(library.get(entry.recordId))
        assertEquals(entry, untouched)

        fail = false
        assertEquals("失败的那次没有占用 number 1", 1, library.publishRevision(Samples.draft(entry.recordId)).number)
    }

    @Test
    fun `没有语音：标记 NO_SPEECH，没有 Revision`() = run {
        val library = newLibrary()
        val entry = Samples.entry(1)
        library.addRecord(entry)

        library.markTranscription(entry.recordId, TranscriptionStatus.NO_SPEECH)

        val stored = checkNotNull(library.get(entry.recordId))
        assertEquals(TranscriptionStatus.NO_SPEECH, stored.transcription)
        assertTrue(stored.revisions.isEmpty())
        assertNull(stored.latestText)
    }

    @Test
    fun `失败与等模型都会记下原因，之后转出文字就变成 DONE 并清掉原因`() = run {
        val library = newLibrary()
        val entry = Samples.entry(1)
        library.addRecord(entry)

        library.markTranscription(entry.recordId, TranscriptionStatus.WAITING_MODEL, ErrorCode.MODEL_NOT_INSTALLED)
        assertEquals(ErrorCode.MODEL_NOT_INSTALLED, checkNotNull(library.get(entry.recordId)).failureCode)

        library.publishRevision(Samples.draft(entry.recordId))
        val done = checkNotNull(library.get(entry.recordId))
        assertEquals(TranscriptionStatus.DONE, done.transcription)
        assertNull(done.failureCode)
    }

    @Test
    fun `已经有 Revision 的 Record 不能再被标成别的转录状态`() = run {
        val library = newLibrary()
        val entry = Samples.entry(1)
        library.addRecord(entry)
        library.publishRevision(Samples.draft(entry.recordId))

        try {
            library.markTranscription(entry.recordId, TranscriptionStatus.NO_SPEECH)
            fail("应当拒绝")
        } catch (expected: IllegalStateException) {
            // 预期
        }
        assertEquals(TranscriptionStatus.DONE, checkNotNull(library.get(entry.recordId)).transcription)
    }

    @Test
    fun `列表最新的在前，变化之后发出新的列表`() = run {
        val library = newLibrary()
        val older = Samples.entry(1)
        val newer = Samples.entry(2)
        library.addRecord(older)
        library.addRecord(newer)
        assertEquals(listOf(newer.recordId, older.recordId), library.observe().first().map { it.recordId })

        library.publishRevision(Samples.draft(older.recordId))

        val latest = library.observe().first()
        assertEquals(listOf(newer.recordId, older.recordId), latest.map { it.recordId })
        assertEquals(1, latest.last().revisions.size)
    }

    @Test
    fun `incomplete 只含还要转录的：PENDING 与 WAITING_MODEL，且有音频`() = run {
        val library = newLibrary()
        val pending = Samples.entry(1)
        val waiting = Samples.entry(2)
        val done = Samples.entry(3)
        val noSpeech = Samples.entry(4)
        val failed = Samples.entry(5)
        val noAudio = Samples.entry(6, audio = null)
        listOf(pending, waiting, done, noSpeech, failed, noAudio).forEach { library.addRecord(it) }
        library.markTranscription(waiting.recordId, TranscriptionStatus.WAITING_MODEL, ErrorCode.MODEL_NOT_INSTALLED)
        library.publishRevision(Samples.draft(done.recordId))
        library.markTranscription(noSpeech.recordId, TranscriptionStatus.NO_SPEECH)
        library.markTranscription(failed.recordId, TranscriptionStatus.FAILED, ErrorCode.ASR_RESOURCE_LIMIT)

        assertEquals(setOf(pending.recordId, waiting.recordId), library.incomplete().map { it.recordId }.toSet())
    }

    @Test
    fun `删除 Record 留下墓碑：本地不可见，重复删除幂等，再次登记或发布修订都不会复活`() = run {
        val library = newLibrary()
        val entry = Samples.entry(1)
        library.addRecord(entry)
        library.publishRevision(Samples.draft(entry.recordId, text = "将被删除的正文"))

        val deletedAt = entry.startedAt.plusSeconds(60)
        assertTrue(library.deleteRecord(entry.recordId, deletedAt))
        assertFalse("重复删除幂等", library.deleteRecord(entry.recordId, deletedAt.plusSeconds(10)))
        assertNull(library.get(entry.recordId))
        assertTrue(library.observe().first().isEmpty())
        assertTrue(library.isDeleted(entry.recordId))
        assertFalse("已删除的 Record 不能被再次登记复活", library.addRecord(entry))
        assertNull(library.get(entry.recordId))

        try {
            library.publishRevision(Samples.draft(entry.recordId, text = "尝试复活"))
            fail("已删除的 Record 不得再发布 Revision")
        } catch (expected: app.auri.capture.library.RecordDeletedException) {
            assertEquals(entry.recordId, expected.recordId)
        }
    }

    @Test
    fun `单独删除音频只清空 audio，保留 Record 与全部 Revision，且不产生墓碑`() = run {
        val library = newLibrary()
        val entry = Samples.entry(1)
        library.addRecord(entry)
        val rev1 = library.publishRevision(Samples.draft(entry.recordId, text = "第一版文字"))

        assertTrue(library.deleteAudio(entry.recordId))
        assertFalse("重复删除音频返回 false", library.deleteAudio(entry.recordId))
        assertFalse("删除音频不是删除 Record，不产生墓碑", library.isDeleted(entry.recordId))

        val stored = checkNotNull(library.get(entry.recordId))
        assertNull(stored.audio)
        assertEquals(listOf(rev1), stored.revisions)
        assertEquals("第一版文字", stored.latestText)
    }
}
