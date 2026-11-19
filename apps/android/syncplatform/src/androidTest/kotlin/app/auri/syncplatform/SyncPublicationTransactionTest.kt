package app.auri.syncplatform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.auri.capture.coordinator.Clock
import app.auri.capture.library.NoSuchRecordException
import app.auri.capture.testing.Samples
import app.auri.platform.library.SqliteRecordLibrary
import app.auri.sync.engine.JobKey
import app.auri.sync.machine.SyncState
import app.auri.syncplatform.jobs.SqliteSyncJobStore
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Ticket 21 第 4 项：发布 Revision 与登记待同步任务是**同一个 SQLite 事务**。
 * 这里用真正的数据库证明两件事：两者一起成功、一起回滚；重新打开数据库文件之后任务还在。
 * 进程在事务中途被杀的情形见 ProcessKillSurvivalTest。
 */
class SyncPublicationTransactionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "sync-tx-${UUID.randomUUID()}.db"
    private val opened = mutableListOf<SqliteRecordLibrary>()
    private val published = Instant.parse("2026-11-18T09:30:00.250Z")
    private val clock = object : Clock {
        override fun monotonicNs(): Long = 0L

        override fun now(): Instant = published
    }

    @After
    fun cleanUp() {
        opened.forEach { it.close() }
        context.deleteDatabase(name)
    }

    private fun open(): SqliteRecordLibrary =
        SyncStorage.library(context.applicationContext as android.app.Application, name, clock).also { opened += it }

    @Test
    fun 发布_Revision_的同一个事务里登记了待同步任务() = runBlocking {
        val library = open()
        val entry = Samples.entry(1)
        library.addRecord(entry)

        val revision = library.publishRevision(Samples.draft(entry.recordId))

        val job = checkNotNull(SqliteSyncJobStore(library).get(JobKey(entry.recordId, revision.number)))
        assertEquals(SyncState.PENDING, job.state)
        assertEquals(published, job.createdAt)
        assertEquals(0, job.attempts)
    }

    @Test
    fun 关掉重开数据库文件_Revision_与它的任务都还在() = runBlocking {
        val first = open()
        val entry = Samples.entry(2)
        first.addRecord(entry)
        val revision = first.publishRevision(Samples.draft(entry.recordId, text = "重启之前写下的"))
        first.close()

        val reopened = open()

        assertEquals(listOf(revision), checkNotNull(reopened.get(entry.recordId)).revisions)
        assertEquals(listOf(JobKey(entry.recordId, revision.number)), SqliteSyncJobStore(reopened).all().map { it.key })
    }

    @Test
    fun 一条_Record_的每一版各有一条任务() = runBlocking {
        val library = open()
        val entry = Samples.entry(3)
        library.addRecord(entry)

        library.publishRevision(Samples.draft(entry.recordId, text = "第一版"))
        library.publishRevision(Samples.draft(entry.recordId, text = "第二版"))

        assertEquals(
            listOf(JobKey(entry.recordId, 1), JobKey(entry.recordId, 2)),
            SqliteSyncJobStore(library).all().map { it.key },
        )
    }

    @Test
    fun 登记任务失败时整个事务回滚_Revision_也不会留下() = runBlocking {
        val entry = Samples.entry(4)
        val failing = SqliteRecordLibrary(
            context,
            name,
            publicationStep = { db, revision ->
                SqliteSyncJobStore.enqueueIn(db, JobKey(revision.recordId, revision.number), published)
                throw IllegalStateException("登记之后、提交之前出了故障")
            },
            extraSchema = SqliteSyncJobStore.SCHEMA,
        ).also { opened += it }
        failing.addRecord(entry)

        try {
            failing.publishRevision(Samples.draft(entry.recordId))
            fail("步骤抛出的异常必须传出来")
        } catch (expected: IllegalStateException) {
            // 对的
        }

        assertTrue("事务回滚后没有 Revision", checkNotNull(failing.get(entry.recordId)).revisions.isEmpty())
        assertTrue("事务回滚后没有任务", SqliteSyncJobStore(failing).all().isEmpty())
        // 版本号也没有被占用：之后正常发布的第一版仍是 1
        failing.close()
        val healthy = open()
        assertEquals(1, healthy.publishRevision(Samples.draft(entry.recordId)).number)
        assertEquals(listOf(JobKey(entry.recordId, 1)), SqliteSyncJobStore(healthy).all().map { it.key })
    }

    @Test
    fun 没有这条_Record_时发布失败_也不会留下任务() = runBlocking {
        val library = open()

        try {
            library.publishRevision(Samples.draft(Samples.uuid(404)))
            fail("没有这条 Record 必须报错")
        } catch (expected: NoSuchRecordException) {
            // 对的
        }

        assertTrue(SqliteSyncJobStore(library).all().isEmpty())
    }

    @Test
    fun 任务表的订阅在发布提交之后就收到新任务() = runBlocking {
        val library = open()
        val jobs = SqliteSyncJobStore(library)
        val entry = Samples.entry(5)
        library.addRecord(entry)
        assertTrue(jobs.observe().first().isEmpty())

        val revision = library.publishRevision(Samples.draft(entry.recordId))

        // 同步循环靠订阅任务表发现新任务：没有人手动调用 enqueue，订阅也必须看到
        val seen = withTimeout(10_000) { jobs.observe().first { list -> list.any { it.key == JobKey(entry.recordId, revision.number) } } }
        assertNotNull(seen.single())
    }

    @Test
    fun 旧数据库没有任务表时打开即补建_升级前已发布的每一版都回填成待发送任务_版本号不变() = runBlocking {
        // 没有 extraSchema 的旧版本（Ticket 16–20）先建库并写入数据：两条 Record、三个 Revision，发布时间各不相同
        val plain = SqliteRecordLibrary(context, name).also { opened += it }
        val first = Samples.entry(6)
        val second = Samples.entry(7)
        plain.addRecord(first)
        plain.addRecord(second)
        val firstOne = plain.publishRevision(Samples.draft(first.recordId, text = "升级前的第一条", at = Samples.start.plusSeconds(100)))
        val secondOne = plain.publishRevision(Samples.draft(second.recordId, text = "升级前的第二条", at = Samples.start.plusSeconds(200)))
        val firstTwo = plain.publishRevision(Samples.draft(first.recordId, text = "升级前的第一条（修订）", at = Samples.start.plusSeconds(300)))
        val plainVersion = plain.withDatabase { db -> db.version }
        plain.close()

        val upgraded = open()
        val jobs = SqliteSyncJobStore(upgraded)

        // 评审 F5：升级前发布的文字一个都不能漏，否则它们永远不会同步
        val backfilled = jobs.all()
        assertEquals(
            "先发布的先同步：次序就是各自的发布次序",
            listOf(firstOne, secondOne, firstTwo).map { JobKey(it.recordId, it.number) },
            backfilled.map { it.key },
        )
        assertEquals(listOf(firstOne, secondOne, firstTwo).map { it.createdAt }, backfilled.map { it.createdAt })
        backfilled.forEach { assertEquals(SyncState.PENDING, it.state) }

        // 之后发布的有任务，并且回填出来的任务与发布事务里入队的是同一个样子
        val next = upgraded.publishRevision(Samples.draft(first.recordId, text = "升级之后的"))
        assertEquals(3, next.number)
        val live = checkNotNull(jobs.get(JobKey(first.recordId, 3)))
        for (job in backfilled) {
            assertEquals(live.state, job.state)
            assertEquals(live.attempts, job.attempts)
            assertEquals(live.uncertain, job.uncertain)
            assertEquals(live.retryAt, job.retryAt)
            assertEquals(live.blockedReason, job.blockedReason)
        }
        assertEquals(4, jobs.all().size)

        // 再打开一次：不重复，已有任务的进度不被重置
        val done = checkNotNull(jobs.get(JobKey(first.recordId, 1))).copy(state = SyncState.STORED, attempts = 1)
        jobs.update(done)
        upgraded.close()
        val reopenedLibrary = open()
        val reopened = SqliteSyncJobStore(reopenedLibrary)
        assertEquals(4, reopened.all().size)
        assertEquals(done, reopened.get(JobKey(first.recordId, 1)))

        // 没有为此抬高数据库版本号：不会和别的 Ticket 的迁移抢版本
        assertEquals(plainVersion, reopenedLibrary.withDatabase { db -> db.version })
    }
}
