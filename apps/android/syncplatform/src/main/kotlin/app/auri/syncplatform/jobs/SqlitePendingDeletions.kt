package app.auri.syncplatform.jobs

import app.auri.platform.library.SqliteRecordLibrary
import app.auri.sync.engine.PendingDeletions
import kotlinx.coroutines.flow.Flow

/**
 * [PendingDeletions] 的 SQLite 实现：读写 [SqliteRecordLibrary] 里的 `record_tombstones` 表。
 */
class SqlitePendingDeletions(private val library: SqliteRecordLibrary) : PendingDeletions {
    override suspend fun pending(): List<String> = library.pendingGatewayDeletions()

    override suspend fun markSynced(recordId: String) = library.markGatewayDeleted(recordId)

    override fun changes(): Flow<Long> = library.changeSignal
}
