package app.auri.platform.library

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import app.auri.capture.contract.CaptureStatus
import app.auri.capture.contract.ErrorCode
import app.auri.capture.library.TranscriptionStatus
import app.auri.capture.testing.Samples
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 只有 SQLite 才有的事：重启后还在、Revision 在数据库层面就改不了、发布与“待同步”在同一个事务里、存的是合同取值。 */
class SqliteRecordLibraryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "sqlite-${UUID.randomUUID()}.db"
    private val opened = mutableListOf<SqliteRecordLibrary>()

    private fun open(step: (SQLiteDatabase, app.auri.capture.library.Revision) -> Unit = { _, _ -> }) =
        SqliteRecordLibrary(context, name, publicationStep = step).also { opened += it }

    @After
    fun cleanUp() {
        opened.forEach { it.close() }
        context.deleteDatabase(name)
    }

    private fun raw(block: (SQLiteDatabase) -> Unit) {
        val db = SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            block(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun 关掉重开同一个文件_Record_与它的_Revision_都还在() = runBlocking {
        val first = open()
        val entry = Samples.entry(1)
        first.addRecord(entry)
        val revision = first.publishRevision(Samples.draft(entry.recordId, text = "重启之前写下的"))
        first.close()

        val reopened = open()

        val stored = checkNotNull(reopened.get(entry.recordId))
        assertEquals(listOf(revision), stored.revisions)
        assertEquals(TranscriptionStatus.DONE, stored.transcription)
        assertEquals(entry.audio, stored.audio)
        assertEquals(listOf(stored), reopened.observe().first())
    }

    @Test
    fun 没有转出文字的_Record_重启之后还是待转录_音频位置不丢() = runBlocking {
        val first = open()
        val entry = Samples.entry(2)
        first.addRecord(entry)
        first.markTranscription(entry.recordId, TranscriptionStatus.WAITING_MODEL, ErrorCode.MODEL_NOT_INSTALLED)
        first.close()

        val incomplete = open().incomplete()

        assertEquals(listOf(entry.recordId), incomplete.map { it.recordId })
        assertEquals(ErrorCode.MODEL_NOT_INSTALLED, incomplete.single().failureCode)
        assertEquals(entry.audio, incomplete.single().audio)
    }

    @Test
    fun 数据库层面就改不了也删不了已发布的_Revision() = runBlocking {
        val library = open()
        val entry = Samples.entry(3)
        library.addRecord(entry)
        library.publishRevision(Samples.draft(entry.recordId, text = "不可改写"))

        raw { db ->
            for (sql in listOf("UPDATE revisions SET text = '被改了'", "DELETE FROM revisions")) {
                try {
                    db.execSQL(sql)
                    fail("应当被触发器拒绝：$sql")
                } catch (expected: SQLiteException) {
                    assertTrue(expected.message.orEmpty(), expected.message.orEmpty().contains("revision"))
                }
            }
        }
        assertEquals("不可改写", checkNotNull(library.get(entry.recordId)).latestText)
    }

    @Test
    fun 发布步骤与_Revision_在同一个事务里_步骤成功则一起留下_步骤失败则一起回滚() = runBlocking {
        var fail = false
        val library = open { db, revision ->
            // 就像 Ticket 21 要做的：在发布的同一个事务里写一条待同步任务（建表也在事务里，回滚时一并消失）
            db.execSQL("CREATE TABLE IF NOT EXISTS pending_sync (record_id TEXT NOT NULL, revision INTEGER NOT NULL)")
            db.execSQL("INSERT INTO pending_sync VALUES (?, ?)", arrayOf<Any>(revision.recordId, revision.number))
            if (fail) throw IllegalStateException("步骤失败（测试注入）")
        }
        val entry = Samples.entry(4)
        library.addRecord(entry)
        library.publishRevision(Samples.draft(entry.recordId, text = "成功的一条"))

        fail = true
        try {
            library.publishRevision(Samples.draft(entry.recordId, text = "会回滚的一条"))
            fail("步骤失败应当让发布失败")
        } catch (expected: IllegalStateException) {
            // 预期
        }

        val stored = checkNotNull(library.get(entry.recordId))
        assertEquals(listOf("成功的一条"), stored.revisions.map { it.text })
        raw { db ->
            val rows = db.rawQuery("SELECT record_id, revision FROM pending_sync", null).use { c ->
                generateSequence { if (c.moveToNext()) c.getString(0) to c.getInt(1) else null }.toList()
            }
            assertEquals("只有成功那一次留下了待同步任务", listOf(entry.recordId to 1), rows)
        }
        fail = false
        assertEquals("失败的那次没有占用 number", 2, library.publishRevision(Samples.draft(entry.recordId, text = "再来一次")).number)
    }

    @Test
    fun 步骤里新建的表在回滚后不存在() = runBlocking {
        val library = open { db, _ ->
            db.execSQL("CREATE TABLE rolled_back_marker (x INTEGER)")
            throw IllegalStateException("步骤失败（测试注入）")
        }
        val entry = Samples.entry(5)
        library.addRecord(entry)

        try {
            library.publishRevision(Samples.draft(entry.recordId))
            fail("应当失败")
        } catch (expected: IllegalStateException) {
            // 预期
        }

        raw { db ->
            val exists = db.rawQuery("SELECT 1 FROM sqlite_master WHERE name = 'rolled_back_marker'", null).use { it.moveToFirst() }
            assertFalse(exists)
        }
        assertTrue(checkNotNull(library.get(entry.recordId)).revisions.isEmpty())
    }

    @Test
    fun 数据库里存的是合同取值而不是_Kotlin_常量名() = runBlocking {
        val library = open()
        val entry = Samples.entry(6).copy(status = CaptureStatus.PARTIAL)
        library.addRecord(entry)
        library.publishRevision(Samples.draft(entry.recordId))

        raw { db ->
            db.rawQuery("SELECT status, end_reason, input_route_type FROM records", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(listOf("partial", "screen_control", "bluetooth_hfp"), (0..2).map(c::getString))
            }
            db.rawQuery("SELECT source, review_state, execution_location FROM revisions", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(listOf("asr", "not_reviewed", "on_device"), (0..2).map(c::getString))
            }
        }
        assertEquals(CaptureStatus.PARTIAL, checkNotNull(library.get(entry.recordId)).status)
    }

    @Test
    fun 文字原样进出_多字节_换行_引号_以及纳秒精度的时间() = runBlocking {
        val library = open()
        val entry = Samples.entry(7).copy(startedAt = Instant.parse("2026-11-18T08:00:00.123456789Z"))
        library.addRecord(entry)
        val text = "你好，\"引号\"和'单引号'\n第二行\t制表符 😀 Ünïcode ' OR 1=1; --"

        val revision = library.publishRevision(Samples.draft(entry.recordId, text = text, at = Instant.parse("2026-11-18T08:01:00.987654321Z")))

        val stored = checkNotNull(library.get(entry.recordId))
        assertEquals(text, stored.latestText)
        assertEquals(revision, stored.revisions.single())
        assertEquals(Instant.parse("2026-11-18T08:00:00.123456789Z"), stored.startedAt)
        assertEquals(Instant.parse("2026-11-18T08:01:00.987654321Z"), stored.revisions.single().createdAt)
    }

    @Test
    fun 没有音频的_Record_也能存取_确认了路由却没有采样() = runBlocking {
        val library = open()
        val entry = Samples.entry(8, audio = null)

        library.addRecord(entry)

        val stored = checkNotNull(library.get(entry.recordId))
        assertNull(stored.audio)
        assertEquals(entry, stored)
        assertTrue("没有音频就没有什么可转录的", library.incomplete().isEmpty())
    }
}
