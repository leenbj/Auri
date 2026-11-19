package app.auri.platform.jobs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.auri.capture.scheduler.JobStore
import app.auri.capture.testing.JobStoreContract
import java.util.UUID
import org.junit.After

/**
 * SQLite 任务库与内存任务库过**同一份**行为合同（JobStoreContract，在 :capture 的 testFixtures 里）。
 * “重启”是关掉数据库再打开同一个文件。只能在 Android 上跑（框架自带的 SQLite）：模拟器上是 E2，真机上才是 E3。
 */
class SqliteTranscriptionJobStoreContractTest : JobStoreContract() {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "job-contract-${UUID.randomUUID()}.db"
    private val opened = mutableListOf<SqliteTranscriptionJobStore>()

    private fun open() = SqliteTranscriptionJobStore(context, name).also { opened += it }

    override fun create(): JobStore = open()

    override fun reopen(previous: JobStore): JobStore {
        (previous as SqliteTranscriptionJobStore).close()
        return open()
    }

    @After
    fun cleanUp() {
        opened.forEach { it.close() }
        context.deleteDatabase(name)
    }
}
