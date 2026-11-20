package app.auri.syncplatform

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import app.auri.capture.coordinator.Clock
import app.auri.capture.testing.Samples
import app.auri.platform.library.SqliteRecordLibrary
import app.auri.sync.discovery.GatewayLocator
import app.auri.sync.engine.JobKey
import app.auri.sync.engine.RecordLibrarySubmissionSource
import app.auri.sync.engine.SyncEngine
import app.auri.sync.gateway.SubmissionBuilder
import app.auri.sync.http.HttpExchange
import app.auri.sync.http.HttpExchangeFactory
import app.auri.sync.http.HttpReply
import app.auri.sync.http.HttpRequest
import app.auri.sync.machine.SyncState
import app.auri.sync.pairing.GatewayAddress
import app.auri.sync.testing.FakeDiscovery
import app.auri.sync.testing.FakeGateway
import app.auri.sync.testing.InMemoryDataFlowPolicy
import app.auri.sync.testing.InMemoryPairingStore
import app.auri.sync.testing.SyncSamples
import app.auri.sync.tls.SpkiPin
import app.auri.syncplatform.jobs.SqliteSyncJobStore
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Ticket 21 第 4 项：进程被**真正杀掉**（SIGKILL，在另一个进程里写库）之后，真实 SQLite 里的状态：
 *  - 事务中途被杀：Revision 与任务一个都没有（原子）；
 *  - 提交之后被杀：Revision 与任务都在（持久）；
 *  - 发送途中被杀：任务以“发送中 + 结果不确定”留下，重启后引擎先问网关，网关已经收下就不重发（不会有重复的 Record）。
 */
class ProcessKillSurvivalTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val application = context.applicationContext as Application
    private val name = "kill-${UUID.randomUUID()}.db"
    private val signal = File(context.filesDir, "$name.signal")
    private val opened = mutableListOf<SqliteRecordLibrary>()
    private var writerPid: Int? = null

    @After
    fun cleanUp() {
        writerPid?.let { if (File("/proc/$it").exists()) Process.killProcess(it) }
        context.stopService(Intent(context, WriterService::class.java))
        opened.forEach { it.close() }
        context.deleteDatabase(name)
        signal.delete()
    }

    private fun open(): SqliteRecordLibrary = SyncStorage.library(application, name).also { opened += it }

    /** 让写进程跑到指定模式的“卡住点”，返回它的进程号。 */
    private fun startWriter(mode: String, record: Int): Int {
        signal.delete()
        context.startService(
            Intent(context, WriterService::class.java)
                .putExtra(WriterService.EXTRA_MODE, mode)
                .putExtra(WriterService.EXTRA_DATABASE, name)
                .putExtra(WriterService.EXTRA_SIGNAL, signal.path)
                .putExtra(WriterService.EXTRA_RECORD, record),
        )
        val deadline = System.nanoTime() + 60_000_000_000L
        while (System.nanoTime() < deadline) {
            val text = if (signal.exists()) signal.readText() else ""
            if (text.startsWith("FAILED")) fail("写进程出错：$text")
            Regex("^$mode pid=(\\d+)$").matchEntire(text)?.let { return it.groupValues[1].toInt().also { pid -> writerPid = pid } }
            Thread.sleep(50)
        }
        fail("写进程在 60 秒内没有到达 $mode")
        error("unreachable")
    }

    private fun kill(pid: Int) {
        assertTrue("写进程应当是另一个进程", pid != Process.myPid())
        Process.killProcess(pid)
        val deadline = System.nanoTime() + 30_000_000_000L
        while (File("/proc/$pid").exists() && System.nanoTime() < deadline) Thread.sleep(50)
        assertFalse("写进程应当已经死了", File("/proc/$pid").exists())
    }

    @Test
    fun 事务中途被杀_Revision_与任务一个都没有留下() = runBlocking {
        val record = Samples.entry(11)
        kill(startWriter(WriterService.IN_TRANSACTION, 11))

        val library = open()
        val entry = library.get(record.recordId)

        assertNotNull("Record 本身是事务之前提交的", entry)
        assertTrue("被杀的事务里的 Revision 不能留下", checkNotNull(entry).revisions.isEmpty())
        assertTrue("被杀的事务里的任务不能留下", SqliteSyncJobStore(library).all().isEmpty())
        // 版本号没有被占用：重新发布得到第 1 版，并带着任务
        assertEquals(1, library.publishRevision(Samples.draft(record.recordId)).number)
        assertEquals(listOf(JobKey(record.recordId, 1)), SqliteSyncJobStore(library).all().map { it.key })
    }

    @Test
    fun 提交之后被杀_Revision_与任务都在() = runBlocking {
        val record = Samples.entry(12)
        kill(startWriter(WriterService.AFTER_COMMIT, 12))

        val library = open()

        val revisions = checkNotNull(library.get(record.recordId)).revisions
        assertEquals(listOf(WriterService.TEXT), revisions.map { it.text })
        val job = checkNotNull(SqliteSyncJobStore(library).get(JobKey(record.recordId, 1)))
        assertEquals(SyncState.PENDING, job.state)
        assertEquals(WriterService.NOW, job.createdAt)
    }

    @Test
    fun 发送途中被杀_任务以结果不确定留下_重启后引擎先问网关_网关已收下就不重发() = runBlocking {
        val record = Samples.entry(13)
        kill(startWriter(WriterService.SENDING, 13))
        val library = open()
        val jobs = SqliteSyncJobStore(library)
        val key = JobKey(record.recordId, 1)

        val left = checkNotNull(jobs.get(key))
        assertEquals(SyncState.SENDING, left.state)
        assertTrue("请求发出之前就已落盘的“结果不确定”必须还在", left.uncertain)

        // 网关其实已经收下了这一版（请求到了，响应丢了）
        val revision = checkNotNull(library.get(record.recordId)).revisions.single()
        val server = FakeGateway(now = { Instant.now() })
        server.stored[key] = revision.textSha256
        val http = HttpExchangeFactory {
            object : HttpExchange {
                override suspend fun execute(address: GatewayAddress, request: HttpRequest): HttpReply = server.handle(request)
            }
        }
        val clock = object : Clock {
            override fun monotonicNs(): Long = System.nanoTime()

            override fun now(): Instant = Instant.now()
        }
        val engine = SyncEngine(
            jobs = jobs,
            pairing = InMemoryPairingStore(SyncSamples.pairedGateway(pin = SpkiPin.parse("2e".repeat(32))!!)),
            source = RecordLibrarySubmissionSource(library, SubmissionBuilder("test")),
            http = http,
            locator = GatewayLocator(http, FakeDiscovery()),
            clock = clock,
            policy = InMemoryDataFlowPolicy(), // 装配时显式授权：这个测试测的是被杀后的接回
        )

        engine.runOnce() // 重启后的第一轮

        assertEquals(SyncState.STORED, checkNotNull(jobs.get(key)).state)
        assertEquals("先问了网关", 1, server.statusCount)
        assertEquals("网关已经有了，不能再发一次", 0, server.putCount)
        assertNull(jobs.get(key)?.blockedReason)
    }
}
