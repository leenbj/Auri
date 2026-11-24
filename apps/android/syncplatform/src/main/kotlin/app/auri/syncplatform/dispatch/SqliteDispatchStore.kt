package app.auri.syncplatform.dispatch

import android.database.sqlite.SQLiteDatabase
import app.auri.capture.library.Revision
import app.auri.platform.library.SqliteRecordLibrary
import app.auri.sync.destinations.Choice
import app.auri.sync.destinations.DecideResult
import app.auri.sync.destinations.DispatchItem
import app.auri.sync.destinations.DispatchStore
import app.auri.sync.destinations.IntentResult
import app.auri.sync.destinations.ResendResult
import app.auri.sync.engine.DeliveryAddition
import app.auri.sync.engine.JobKey
import app.auri.sync.engine.Refusal
import app.auri.sync.gateway.Destination
import app.auri.sync.gateway.Intent
import app.auri.sync.gateway.Publication
import app.auri.sync.pairing.GatewayIdentity
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update

/**
 * [DispatchStore] 的 SQLite 实现：发送账和 Record 库、待同步任务表在**同一个数据库文件**里，用库的同一条连接（[SqliteRecordLibrary.withDatabase]）。
 * 全部逻辑在 [DispatchSql]（JVM 上用真 SQLite 引擎跑同一份 DispatchStoreContract）；这里只负责把它放到数据库线程上，并在变化后发信号。
 *
 * 装配（SyncRuntime）：库的 `extraSchema` = [DispatchSql.SCHEMA]（建表、再回填）；
 * 库的 `publicationStep` = [publishIn]，在**发布 Revision 的那个事务里**创建同步任务并规划目标——
 * 进程死在任何一步，要么整个事务回滚，要么“已发布”“已有任务”“已规划”一起落盘，不会有文字没有任务。
 */
class SqliteDispatchStore(private val library: SqliteRecordLibrary) : DispatchStore {
    private val ownChanges = MutableStateFlow(0L)

    private suspend fun <T> read(block: DispatchSql.() -> T): T = library.withDatabase { db -> DispatchSql(AndroidSqlDb(db)).block() }

    private suspend fun <T> write(block: DispatchSql.() -> T): T {
        val result = read(block)
        ownChanges.update { it + 1 }
        return result
    }

    override suspend fun destinations(): List<Destination> = read { destinations() }

    override suspend fun applyDestinations(gateway: GatewayIdentity, destinations: List<Destination>, authoritative: Boolean, now: Instant) =
        write { applyDestinations(gateway, destinations, authoritative, now) }

    override suspend fun forgetDestinations(now: Instant) = write { forgetDestinations(now) }

    /** 引擎每次发请求前都会调用：没换网关时什么都没改，不发变化信号。 */
    override suspend fun useGateway(gateway: GatewayIdentity) {
        if (read { useGateway(gateway) }) ownChanges.update { it + 1 }
    }

    override suspend fun item(key: JobKey): DispatchItem? = read { item(key) }

    override suspend fun items(limit: Int): List<DispatchItem> = read { items(limit) }

    override suspend fun awaiting(): List<DispatchItem> = read { awaiting() }

    override suspend fun itemsFor(destinationId: String, limit: Int): List<DispatchItem> = read { itemsFor(destinationId, limit) }

    override suspend fun decide(key: JobKey, destinationId: String, choice: Choice, now: Instant): DecideResult =
        write { decide(key, destinationId, choice, now) }

    override suspend fun requestResend(key: JobKey, destinationId: String, intent: Intent, now: Instant): ResendResult =
        write { requestResend(key, destinationId, intent, now) }

    override suspend fun setIntent(key: JobKey, intent: Intent, now: Instant): IntentResult = write { setIntent(key, intent, now) }

    /** 构造提交就是冻结意图（界面上的标签随之锁住），所以要发变化信号。 */
    override suspend fun publicationFor(key: JobKey): Publication = write { publicationFor(key) }

    /** 请求没有离开手机、放开冻结：界面上的标签随之又能改，所以要发变化信号（没有冻着的什么都没改，不发）。 */
    override suspend fun unfreezeUnsent(key: JobKey) {
        if (read { unfreezeUnsent(key) }) ownChanges.value += 1
    }

    override suspend fun due(): List<DeliveryAddition> = read { due() }

    override suspend fun gatewayHas(key: JobKey, destinationIds: Collection<String>) = write { gatewayHas(key, destinationIds) }

    override suspend fun refused(key: JobKey, destinationIds: Collection<String>, reason: Refusal) = write { refused(key, destinationIds, reason) }

    override suspend fun defaultIntent(): Intent = read { defaultIntent() }

    override suspend fun setDefaultIntent(intent: Intent) = write { setDefaultIntent(intent) }

    /** 自己改了缓存/决定/意图，或库里提交了新内容（发布 Revision 的事务里规划了目标、创建了任务）都发信号。 */
    override fun changes(): Flow<Long> = combine(ownChanges, library.changeSignal) { own, library -> own + library }

    companion object {
        /**
         * 交给 [SqliteRecordLibrary] 的 publicationStep：在发布 Revision 的事务里调用，不自己开关事务（并入外面那个）。
         * 一定创建同步任务（文字不等任何决定）；ASR 初稿另外规划目标。
         */
        fun publishIn(db: SQLiteDatabase, revision: Revision, now: Instant) =
            DispatchSql(AndroidSqlDb(db)).publish(JobKey(revision.recordId, revision.number), revision.source, now)

        /** 交给 [SqliteRecordLibrary] 的 deletionStep：在删除 Record 的事务里一并清掉待同步任务与发送账。 */
        fun deleteRecordIn(db: SQLiteDatabase, recordId: String) =
            DispatchSql(AndroidSqlDb(db)).deleteRecord(recordId)
    }
}
