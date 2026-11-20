package app.auri.syncplatform.policy

import app.auri.sync.policy.DataFlowState
import app.auri.syncplatform.dispatch.JdbcSqlDb
import app.auri.syncplatform.dispatch.SqlDb
import java.sql.DriverManager
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评审 B1：“文字能不能进网关”的两道开关的存储。真 SQLite 引擎。
 * 每个测试守一条“不确定就是不许”：从没设置过、被改坏、读不出来，结果都不能是放行。
 */
class DataFlowPolicySqlTest {
    private val t0 = Instant.parse("2026-11-20T08:00:00.123Z")

    private fun newDb(): JdbcSqlDb {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        DataFlowPolicySql.SCHEMA.forEach { statement -> connection.createStatement().use { it.execute(statement) } }
        return JdbcSqlDb(connection)
    }

    private fun JdbcSqlDb.rawPut(name: String, value: String) {
        update("INSERT OR REPLACE INTO data_flow_policy (name, value) VALUES (?, ?)", name, value)
    }

    private fun JdbcSqlDb.row(name: String): String? = query("SELECT value FROM data_flow_policy WHERE name = ?", name) { it.string(0) }.firstOrNull()

    @Test
    fun `从没设置过：没有授权，严格仅本地也没开，文字照样不放行`() {
        val state = DataFlowPolicySql(newDb()).read()

        assertEquals(DataFlowState.Unset, state)
        assertFalse("配对不等于同意：默认一个字节也不发", state.textMayEnterGateway)
    }

    @Test
    fun `授权会被记下（带版本与时间），换一个新实例、也就是重启之后仍然有效`() {
        val db = newDb()
        DataFlowPolicySql(db).setGatewayTextGranted(true, t0)

        val reopened = DataFlowPolicySql(db).read()

        assertTrue(reopened.gatewayTextGranted)
        assertTrue(reopened.textMayEnterGateway)
        assertEquals("授权记录含版本与时间（SEC-002）", "granted v1 2026-11-20T08:00:00.123Z", db.row("gateway_text_grant"))
    }

    @Test
    fun `收回授权：立刻回到不放行，收回也留下记录`() {
        val db = newDb()
        val policy = DataFlowPolicySql(db)
        policy.setGatewayTextGranted(true, t0)

        policy.setGatewayTextGranted(false, t0.plusSeconds(60))

        assertFalse(policy.read().textMayEnterGateway)
        assertEquals("revoked v1 2026-11-20T08:01:00.123Z", db.row("gateway_text_grant"))
    }

    @Test
    fun `严格仅本地开着时压过授权，关掉后授权照旧生效`() {
        val policy = DataFlowPolicySql(newDb())
        policy.setGatewayTextGranted(true, t0)
        assertTrue(policy.read().textMayEnterGateway)

        policy.setStrictLocal(true)
        assertFalse("统一门禁：授权了也不发", policy.read().textMayEnterGateway)
        assertTrue(policy.read().gatewayTextGranted)

        policy.setStrictLocal(false)
        assertTrue(policy.read().textMayEnterGateway)
    }

    @Test
    fun `授权的行不是逐字合规就当没授权：别的版本、大小写、缺字段、空、被改坏`() {
        val db = newDb()
        val policy = DataFlowPolicySql(db)
        for (broken in listOf("granted v2 2026-11-20T08:00:00Z", "GRANTED v1 2026-11-20T08:00:00Z", "granted v1", "granted", "", "true", "1", "granted  v1 x", "revoked v1 2026-11-20T08:00:00Z")) {
            db.rawPut("gateway_text_grant", broken)

            assertFalse("“$broken” 不能算授权", policy.read().gatewayTextGranted)
        }
    }

    @Test
    fun `严格仅本地的行只有 off 才算没开：on 和读不懂的内容都算开（不确定就是不许）`() {
        val db = newDb()
        val policy = DataFlowPolicySql(db)
        db.rawPut("gateway_text_grant", "granted v1 2026-11-20T08:00:00Z")
        for (garbled in listOf("on", "ON", "banana", "", "false", "0", " off")) {
            db.rawPut("strict_local", garbled)

            assertTrue("“$garbled” 必须按开处理", policy.read().strictLocal)
            assertFalse(policy.read().textMayEnterGateway)
        }
        db.rawPut("strict_local", "off")
        assertFalse(policy.read().strictLocal)
        assertTrue(policy.read().textMayEnterGateway)
    }

    @Test
    fun `读不出来（表不存在、连接坏了）：回答最保守的 Closed，绝不抛出，也绝不放行`() {
        val broken = object : SqlDb {
            override fun update(sql: String, vararg args: Any?): Int = throw IllegalStateException("disk I/O error")

            override fun <T> query(sql: String, vararg args: Any?, map: (app.auri.syncplatform.dispatch.SqlRow) -> T): List<T> = throw IllegalStateException("disk I/O error")

            override fun <T> transaction(block: () -> T): T = block()
        }
        val policy = DataFlowPolicySql(broken)

        assertThrows("read 本身会抛：保护在 readOrClosed 里，不是把故障吞了", IllegalStateException::class.java) { policy.read() }
        val state = policy.readOrClosed()

        assertEquals(DataFlowState.Closed, state)
        assertFalse(state.textMayEnterGateway)
    }

    @Test
    fun `建表可以反复执行：重启时打开数据库不会清掉已有的设置`() {
        val db = newDb()
        DataFlowPolicySql(db).setGatewayTextGranted(true, t0)

        DataFlowPolicySql.SCHEMA.forEach { statement -> db.connection.createStatement().use { it.execute(statement) } }

        assertTrue(DataFlowPolicySql(db).read().textMayEnterGateway)
    }
}
