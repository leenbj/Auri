package app.auri.syncplatform

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.auri.capture.contract.RevisionSource
import app.auri.capture.coordinator.Clock
import app.auri.capture.testing.Samples
import app.auri.platform.library.SqliteRecordLibrary
import app.auri.sync.destinations.DispatchStore
import app.auri.sync.engine.JobKey
import app.auri.sync.engine.SyncJobStore
import app.auri.sync.testing.DispatchFixture
import app.auri.sync.testing.DispatchStoreContract
import app.auri.syncplatform.dispatch.SqliteDispatchStore
import app.auri.syncplatform.jobs.SqliteSyncJobStore
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.junit.After

/**
 * Ticket 22：发送账在设备上真正的 SQLite（Android 自带的版本，库的数据库线程与连接）上，要通过与内存实现、与 JVM 上 sqlite-jdbc 完全相同的行为合同
 * （DispatchStoreContract）：预览确认之前不放行、决定幂等、重启（重新打开数据库文件）之后仍然拦着、用户修订只入库……
 * 装配用的就是产品的 [SyncStorage.library]：发布 Revision 的事务里规划目标（publicationStep）。
 * 需要设备或模拟器；本机没跑过（NOT_RUN，原因：为省内存不起模拟器），命令见 execution/acceptance/android-destinations/README.md。
 */
class SqliteDispatchStoreOnDeviceTest : DispatchStoreContract() {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val createdNames = mutableListOf<String>()
    private val opened = mutableListOf<SqliteRecordLibrary>()

    @After
    fun cleanUp() {
        opened.forEach { it.close() }
        createdNames.forEach { context.deleteDatabase(it) }
    }

    override fun newFixture(): DispatchFixture {
        val dbName = "dispatch-contract-${UUID.randomUUID()}.db".also { createdNames += it }
        return OnDeviceFixture(dbName)
    }

    /** 同一个数据库文件上的一个“进程”：[reopen] 在同一个文件上再开一个全新的实例（打开时的建表与回填再跑一遍）。 */
    private inner class OnDeviceFixture(private val dbName: String) : DispatchFixture {
        private val now = AtomicReference(Instant.EPOCH)
        private val clock = object : Clock {
            override fun monotonicNs(): Long = 0L

            override fun now(): Instant = now.get()
        }
        private val library = SyncStorage.library(context.applicationContext as Application, dbName, clock).also { opened += it }

        override val store: DispatchStore = SqliteDispatchStore(library)

        override val jobs: SyncJobStore = SqliteSyncJobStore(library)

        override suspend fun publish(key: JobKey, source: RevisionSource, now: Instant) {
            this.now.set(now)
            library.addRecord(Samples.entry(1).copy(recordId = key.recordId)) // 已经有就什么也不做
            // 版号由库在事务里按顺序分配：要发布第 n 版，前面不够的补成不规划、只入库的用户修订
            val have = library.get(key.recordId)?.revisions?.size ?: 0
            repeat((key.revision - 1 - have).coerceAtLeast(0)) { library.publishRevision(Samples.draft(key.recordId, at = now).copy(source = RevisionSource.USER_EDIT)) }
            val published = library.publishRevision(Samples.draft(key.recordId, at = now).copy(source = source))
            check(published.number == key.revision) { "库分配的版号 ${published.number} 与合同里要的 ${key.revision} 不一致" }
        }

        override fun reopen(): DispatchFixture = OnDeviceFixture(dbName)
    }
}
