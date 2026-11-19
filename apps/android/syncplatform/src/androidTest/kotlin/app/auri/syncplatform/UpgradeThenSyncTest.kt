package app.auri.syncplatform

import android.app.Application
import android.content.Context
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
import app.auri.sync.testing.InMemoryPairingStore
import app.auri.sync.testing.SyncSamples
import app.auri.sync.tls.SpkiPin
import app.auri.syncplatform.jobs.SqliteSyncJobStore
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 评审 F5 的端到端对照：一台装过没有同步的旧版本（Ticket 16–20）的手机升级之后，
 * 升级前已经发布的每一版文字，第一轮同步就要送到网关——而不是只得到一张永远空着的任务表。
 *
 * 用真的 SQLite 库文件与真的同步引擎，网关是系统边界上的替身（同 ProcessKillSurvivalTest）。
 * 本机没有设备：这个测试只编译，没有运行（NOT_RUN，见 execution/acceptance/android-sync/README.md）。
 * 迁移 SQL 本身在 JVM 上由 SyncJobsBackfillTest 用真的 SQLite 引擎验证过。
 */
class UpgradeThenSyncTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val application = context.applicationContext as Application
    private val name = "upgrade-sync-${UUID.randomUUID()}.db"
    private val opened = mutableListOf<SqliteRecordLibrary>()

    @After
    fun cleanUp() {
        opened.forEach { it.close() }
        context.deleteDatabase(name)
    }

    @Test
    fun 升级之后第一轮同步就把升级前已发布的每一版送到网关() = runBlocking {
        // 旧版本：只有 Record 库，没有任务表；发布过三个 Revision
        val plain = SqliteRecordLibrary(context, name).also { opened += it }
        val first = Samples.entry(31)
        val second = Samples.entry(32)
        plain.addRecord(first)
        plain.addRecord(second)
        val revisions = listOf(
            plain.publishRevision(Samples.draft(first.recordId, text = "升级前的第一条", at = Samples.start.plusSeconds(100))),
            plain.publishRevision(Samples.draft(second.recordId, text = "升级前的第二条", at = Samples.start.plusSeconds(200))),
            plain.publishRevision(Samples.draft(first.recordId, text = "升级前的第一条（修订）", at = Samples.start.plusSeconds(300))),
        )
        plain.close()

        // 升级：新版本打开同一个库文件（带任务表与回填），配好对，跑一轮同步
        val library = SyncStorage.library(application, name).also { opened += it }
        val jobs = SqliteSyncJobStore(library)
        val server = FakeGateway(now = { Instant.now() })
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
            pairing = InMemoryPairingStore(SyncSamples.pairedGateway(pin = SpkiPin.parse("3d".repeat(32))!!)),
            source = RecordLibrarySubmissionSource(library, SubmissionBuilder("test")),
            http = http,
            locator = GatewayLocator(http, FakeDiscovery()),
            clock = clock,
        )

        engine.runOnce()

        val keys = revisions.map { JobKey(it.recordId, it.number) }
        keys.forEach { assertEquals("$it 应当已入库", SyncState.STORED, checkNotNull(jobs.get(it)).state) }
        assertEquals("三个版本各发一次", 3, server.putCount)
        assertEquals(
            "网关上的正文哈希就是升级前库里的正文哈希",
            revisions.associate { JobKey(it.recordId, it.number) to it.textSha256 },
            server.stored.toMap(),
        )
        assertEquals("先发布的先同步", keys, server.stored.keys.toList())
    }
}
