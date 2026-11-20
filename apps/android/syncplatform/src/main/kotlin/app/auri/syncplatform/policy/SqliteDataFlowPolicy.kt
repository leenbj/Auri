package app.auri.syncplatform.policy

import app.auri.capture.coordinator.Clock
import app.auri.platform.library.SqliteRecordLibrary
import app.auri.sync.policy.DataFlowPolicy
import app.auri.sync.policy.DataFlowState
import app.auri.syncplatform.dispatch.AndroidSqlDb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * [DataFlowPolicy] 的 SQLite 实现：两道开关和 Record 库在同一个数据库文件里（库的 extraSchema 里有 [DataFlowPolicySql.SCHEMA]）。
 * 全部逻辑在 [DataFlowPolicySql]（JVM 上用真 SQLite 引擎测过）；这里只负责放到数据库线程上，并且保证**读出任何故障都回答“最保守”**。
 */
class SqliteDataFlowPolicy(private val library: SqliteRecordLibrary, private val clock: Clock) : DataFlowPolicy {
    private val changes = MutableStateFlow(0L)

    override suspend fun current(): DataFlowState = try {
        library.withDatabase { db -> DataFlowPolicySql(AndroidSqlDb(db)).readOrClosed() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DataFlowState.Closed // 连数据库都打不开：同样不许
    }

    override fun states(): Flow<DataFlowState> = changes.map { current() }.distinctUntilChanged()

    override suspend fun setGatewayTextGranted(granted: Boolean) {
        library.withDatabase { db -> DataFlowPolicySql(AndroidSqlDb(db)).setGatewayTextGranted(granted, clock.now()) }
        changes.value += 1
    }

    override suspend fun setStrictLocal(on: Boolean) {
        library.withDatabase { db -> DataFlowPolicySql(AndroidSqlDb(db)).setStrictLocal(on) }
        changes.value += 1
    }
}
