package app.auri.syncplatform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.auri.platform.library.SqliteRecordLibrary
import app.auri.sync.engine.SyncJobStore
import app.auri.sync.testing.SyncJobStoreContract
import app.auri.syncplatform.jobs.SqliteSyncJobStore
import org.junit.After

/** 同步任务表在真正的 SQLite 上，要通过与内存实现完全相同的行为合同（SyncJobStoreContract）。 */
class SqliteSyncJobStoreTest : SyncJobStoreContract() {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val opened = mutableListOf<SqliteRecordLibrary>()

    @After
    fun closeDatabases() {
        opened.forEach { it.close() }
    }

    override fun newStore(): SyncJobStore {
        val library = SqliteRecordLibrary(context, null, extraSchema = SqliteSyncJobStore.SCHEMA).also { opened += it }
        return SqliteSyncJobStore(library)
    }
}
