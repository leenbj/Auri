package app.auri.sync.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * 尚未同步到网关的本地删除墓碑（FR-TXT-005 / FR-SYNC-002）：
 * 本地删除整条 Record 后，同步引擎向网关发送 `DELETE /v1/records/{record_id}`；
 * 网关种下墓碑后标记已同步，离线补传不会让已删除的 Record 复活。
 */
interface PendingDeletions {
    /** 尚未同步到网关的 record_id 列表（按删除时间升序）。 */
    suspend fun pending(): List<String>

    /** 网关已确认删除（或已终结不再重试）：标记已同步。 */
    suspend fun markSynced(recordId: String)

    /** 墓碑集合变化时发出递增信号。 */
    fun changes(): Flow<Long>

    companion object {
        val None: PendingDeletions = object : PendingDeletions {
            override suspend fun pending(): List<String> = emptyList()

            override suspend fun markSynced(recordId: String) = Unit

            override fun changes(): Flow<Long> = emptyFlow()
        }
    }
}

fun PendingDeletions.pendingUpdates(): Flow<List<String>> = changes().onStart { emit(0L) }.map { pending() }.distinctUntilChanged()
