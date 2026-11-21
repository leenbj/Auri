package app.auri.syncplatform.policy

import app.auri.capture.coordinator.Clock
import app.auri.platform.library.SqliteRecordLibrary
import app.auri.sync.pairing.GatewayIdentity
import app.auri.sync.policy.DataFlowPolicy
import app.auri.sync.policy.PolicyGeneration
import app.auri.sync.policy.StoredDataFlow
import app.auri.sync.policy.recoveringStoredStates
import app.auri.syncplatform.dispatch.AndroidSqlDb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * [DataFlowPolicy] 的 SQLite 实现：两道开关和 Record 库在同一个数据库文件里（库的 extraSchema 里有 [DataFlowPolicySql.SCHEMA]）。
 * 全部逻辑在 [DataFlowPolicySql]（JVM 上用真 SQLite 引擎测过）；这里只负责放到数据库线程上，并且保证**读出任何故障都回答“最保守”**。
 */
class SqliteDataFlowPolicy(private val library: SqliteRecordLibrary, private val clock: Clock) : DataFlowPolicy {
    private val changes = MutableStateFlow(0L)

    override suspend fun stored(): StoredDataFlow = try {
        library.withDatabase { db -> DataFlowPolicySql(AndroidSqlDb(db)).readOrClosed() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        StoredDataFlow.Closed // 连数据库都打不开：同样不许
    }

    /**
     * 读出来了就是读到的值；读不出来（数据库打不开、读时出故障）= null：[storedStates] 要靠它分清“真的是最保守”与“暂时读不到、要重试”（复评 R9）。
     * 取消照常往外传，不当成读失败。
     */
    private suspend fun readOrNull(): StoredDataFlow? = try {
        library.withDatabase { db -> DataFlowPolicySql(AndroidSqlDb(db)).read() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    /** 读不出来先发 Closed、按退避重试、读到了发出真实值（接口文档里的“恢复后发出真实值”）：见 [recoveringStoredStates]。 */
    override fun storedStates(): Flow<StoredDataFlow> = changes.recoveringStoredStates(::readOrNull).distinctUntilChanged()

    override suspend fun grantTo(gateway: GatewayIdentity) = write { grantTo(gateway, clock.now()) }

    override suspend fun revokeGrant() = write { revokeGrant(clock.now()) }

    override suspend fun forgetGrantExcept(gateway: GatewayIdentity?) = write { forgetGrantExcept(gateway, clock.now()) }

    override suspend fun setStrictLocal(on: Boolean) = write { setStrictLocal(on) }

    /**
     * 世代（再评 S3）：开始写之前加一；写完（成败都算）在**数据库线程上、紧接着写**再加一——之后任何读（问放行也是读，走同一条连接）都排在它后面，
     * 所以“读到收回之后的设置”与“世代变过”不会错开；不放在回到调用方之后，那里隔着一次调度。
     */
    private val generations = PolicyGeneration()

    override val generation: Long get() = generations.value

    private suspend fun write(change: DataFlowPolicySql.() -> Unit) {
        generations.changing()
        library.withDatabase { db ->
            try {
                DataFlowPolicySql(AndroidSqlDb(db)).change()
            } finally {
                generations.changed()
            }
        }
        changes.value += 1
    }
}
