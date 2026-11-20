package app.auri.syncplatform.policy

import app.auri.sync.policy.DataFlowState
import app.auri.syncplatform.dispatch.SqlDb
import java.time.Instant

/**
 * “文字能不能进网关”两道开关的存储逻辑，写在 [SqlDb] 上：Android 上跑在 Record 库同一个数据库文件里，
 * JVM 测试里跑在真正的 SQLite 引擎上（DataFlowPolicySqlTest）。方法是同步的，调用方负责放到数据库线程上。
 *
 * 读的规矩是“不确定就是不许”（评审 B1）：
 * - 从没写过 → 用户什么都没设置：不授权、严格仅本地没开（[DataFlowState.Unset]）——文字照样不会发。
 * - 授权那一行必须**逐字**是 `granted v1 <时间>` 才算授权；`revoked …`、别的版本号、被改坏的内容都当没授权。
 * - 严格仅本地那一行只有 `off` 才算没开；`on` 和任何读不懂的内容都当开。
 * - 读本身出任何故障（[readOrClosed]）→ [DataFlowState.Closed]。
 * 授权那一行带上版本与时间：以后要核对“用户在什么时候、按哪一版的文案授权的”有据可查（PRD SEC-002：授权记录含范围、版本、时间与撤销状态）。
 */
class DataFlowPolicySql(private val sql: SqlDb) {
    fun read(): DataFlowState = DataFlowState(
        gatewayTextGranted = value(TEXT_GRANT)?.let(::isGrant) ?: false,
        strictLocal = value(STRICT_LOCAL)?.let { it != STRICT_OFF } ?: false,
    )

    /** 读不出来就按最保守的回答，绝不抛出。 */
    fun readOrClosed(): DataFlowState = try {
        read()
    } catch (_: Exception) {
        DataFlowState.Closed
    }

    fun setGatewayTextGranted(granted: Boolean, now: Instant) = sql.transaction {
        put(TEXT_GRANT, "${if (granted) "granted" else "revoked"} $GRANT_VERSION $now")
    }

    fun setStrictLocal(on: Boolean) = sql.transaction { put(STRICT_LOCAL, if (on) "on" else STRICT_OFF) }

    private fun isGrant(value: String): Boolean = value.split(' ').let { it.size == 3 && it[0] == "granted" && it[1] == GRANT_VERSION }

    private fun value(name: String): String? = sql.query("SELECT value FROM data_flow_policy WHERE name = ?", name) { it.string(0) }.firstOrNull()

    private fun put(name: String, value: String) {
        sql.update("INSERT OR REPLACE INTO data_flow_policy (name, value) VALUES (?, ?)", name, value)
    }

    companion object {
        /** 授权所依据的文案/范围的版本。文案变了就升版本：旧版本的授权读起来是“没授权”，要用户重新同意。 */
        const val GRANT_VERSION = "v1"
        private const val TEXT_GRANT = "gateway_text_grant"
        private const val STRICT_LOCAL = "strict_local"
        private const val STRICT_OFF = "off"

        /** 交给 [app.auri.platform.library.SqliteRecordLibrary] 的 extraSchema（和发送账的建表一起，在同一个事务里执行）。 */
        val SCHEMA = listOf("CREATE TABLE IF NOT EXISTS data_flow_policy (name TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }
}
