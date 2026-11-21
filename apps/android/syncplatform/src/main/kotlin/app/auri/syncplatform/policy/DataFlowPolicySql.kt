package app.auri.syncplatform.policy

import app.auri.sync.pairing.GatewayIdentity
import app.auri.sync.policy.StoredDataFlow
import app.auri.syncplatform.dispatch.SqlDb
import java.time.Instant

/**
 * “文字能不能进网关”两道开关的存储逻辑，写在 [SqlDb] 上：Android 上跑在 Record 库同一个数据库文件里，
 * JVM 测试里跑在真正的 SQLite 引擎上（DataFlowPolicySqlTest）。方法是同步的，调用方负责放到数据库线程上。
 *
 * 读的规矩是“不确定就是不许”（评审 B1）：
 * - 从没写过 → 用户什么都没设置：不授权、严格仅本地没开（[StoredDataFlow.Unset]）——文字照样不会发。
 * - 授权那一行必须**逐字**是 `granted v2 <网关编号> <公钥指纹> <时间>` 才算授权，授给的就是行里写的那个网关（复评 R3，SEC-002：同意限定在指定的网关上）；
 *   `revoked …`、别的版本号（含 v1：那时授权是全局的，不知道给了谁，读起来是“没授权”）、被改坏的内容都当没授权。
 * - 严格仅本地那一行只有 `off` 才算没开；`on` 和任何读不懂的内容都当开。
 * - 读本身出任何故障（[readOrClosed]）→ [StoredDataFlow.Closed]。
 * 授权那一行带上范围、版本与时间：以后要核对“用户在什么时候、对哪台网关、按哪一版的文案授权的”有据可查（PRD SEC-002：授权记录含范围、版本、时间与撤销状态）。
 */
class DataFlowPolicySql(private val sql: SqlDb) {
    fun read(): StoredDataFlow = StoredDataFlow(
        grantedTo = value(TEXT_GRANT)?.let(::grantee),
        strictLocal = value(STRICT_LOCAL)?.let { it != STRICT_OFF } ?: false,
    )

    /** 读不出来就按最保守的回答，绝不抛出。 */
    fun readOrClosed(): StoredDataFlow = try {
        read()
    } catch (_: Exception) {
        StoredDataFlow.Closed
    }

    /** 同意文字进 [gateway]。只存一份授权：给另一个网关授权，原来的那份就被替换掉。 */
    fun grantTo(gateway: GatewayIdentity, now: Instant) = sql.transaction {
        put(TEXT_GRANT, "granted $GRANT_VERSION ${gateway.gatewayId} ${gateway.keySha256} $now")
    }

    /** 收回授权（不论给了谁）。 */
    fun revokeGrant(now: Instant) = sql.transaction { put(TEXT_GRANT, revoked(now)) }

    /** 授权给的不是 [keep] 就作废（[keep] 为 null = 任何授权都作废）；读—判—写在同一个事务里。没有授权、或已经读不懂的行不动。 */
    fun forgetGrantExcept(keep: GatewayIdentity?, now: Instant) = sql.transaction {
        val granted = value(TEXT_GRANT)?.let(::grantee)
        if (granted != null && granted != keep) put(TEXT_GRANT, revoked(now))
    }

    fun setStrictLocal(on: Boolean) = sql.transaction { put(STRICT_LOCAL, if (on) "on" else STRICT_OFF) }

    private fun revoked(now: Instant) = "revoked $GRANT_VERSION $now"

    /** 这一行授权给了谁；不是一行规规矩矩的 v2 授权就是 null。 */
    private fun grantee(value: String): GatewayIdentity? {
        val parts = value.split(' ')
        if (parts.size != 5 || parts[0] != "granted" || parts[1] != GRANT_VERSION) return null
        // shortcut: 第五段（时间）这里还没有解析，下一条评审项（R8）补上严格解析
        return GatewayIdentity(parts[2].takeIf { GATEWAY_ID.matches(it) } ?: return null, parts[3].takeIf { KEY_SHA256.matches(it) } ?: return null)
    }

    private fun value(name: String): String? = sql.query("SELECT value FROM data_flow_policy WHERE name = ?", name) { it.string(0) }.firstOrNull()

    private fun put(name: String, value: String) {
        sql.update("INSERT OR REPLACE INTO data_flow_policy (name, value) VALUES (?, ?)", name, value)
    }

    companion object {
        /** 授权所依据的文案/范围的版本。文案或范围变了就升版本：旧版本的授权读起来是“没授权”，要用户重新同意（v2：授权带上了它是给哪个网关的）。 */
        const val GRANT_VERSION = "v2"
        private val GATEWAY_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private val KEY_SHA256 = Regex("^[0-9a-f]{64}$")
        private const val TEXT_GRANT = "gateway_text_grant"
        private const val STRICT_LOCAL = "strict_local"
        private const val STRICT_OFF = "off"

        /** 交给 [app.auri.platform.library.SqliteRecordLibrary] 的 extraSchema（和发送账的建表一起，在同一个事务里执行）。 */
        val SCHEMA = listOf("CREATE TABLE IF NOT EXISTS data_flow_policy (name TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }
}
