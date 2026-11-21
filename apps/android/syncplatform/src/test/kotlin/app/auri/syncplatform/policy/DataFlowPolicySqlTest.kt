package app.auri.syncplatform.policy

import app.auri.sync.pairing.GatewayIdentity
import app.auri.sync.policy.StoredDataFlow
import app.auri.syncplatform.dispatch.JdbcSqlDb
import app.auri.syncplatform.dispatch.SqlDb
import java.sql.DriverManager
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评审 B1：“文字能不能进网关”的两道开关的存储。真 SQLite 引擎。
 * 每个测试守一条“不确定就是不许”：从没设置过、被改坏、读不出来，结果都不能是放行。
 * 复评 R3：授权记着它是给哪个网关的；对别的网关不算数。
 */
class DataFlowPolicySqlTest {
    private val t0 = Instant.parse("2026-11-20T08:00:00.123Z")
    private val gatewayA = GatewayIdentity("fa1b98ec-1bfb-53b3-85a3-4d36b680fe6e", "a".repeat(64))
    private val gatewayB = GatewayIdentity("0f0f0f0f-1bfb-53b3-85a3-4d36b680fe6e", "b".repeat(64))

    /** 一行规规矩矩的 v2 授权（给 [gateway]、时间 [time]）。 */
    private fun grantRow(gateway: GatewayIdentity = gatewayA, time: String = "2026-11-20T08:00:00Z") = "granted v2 ${gateway.gatewayId} ${gateway.keySha256} $time"

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
        val stored = DataFlowPolicySql(newDb()).read()

        assertEquals(StoredDataFlow.Unset, stored)
        assertFalse("配对不等于同意：默认一个字节也不发", stored.viewFor(gatewayA).textMayEnterGateway)
    }

    @Test
    fun `授权会被记下（带网关、版本与时间），换一个新实例、也就是重启之后仍然有效`() {
        val db = newDb()
        DataFlowPolicySql(db).grantTo(gatewayA, t0)

        val reopened = DataFlowPolicySql(db).read()

        assertEquals(gatewayA, reopened.grantedTo)
        assertTrue(reopened.viewFor(gatewayA).textMayEnterGateway)
        assertEquals("授权记录含范围（哪台网关）、版本与时间（SEC-002）", "granted v2 ${gatewayA.gatewayId} ${gatewayA.keySha256} 2026-11-20T08:00:00.123Z", db.row("gateway_text_grant"))
    }

    @Test
    fun `复评 R3：授权只对给出它的那个网关算数，别的网关、同编号换了公钥的、同公钥换了编号的、没有网关的都不放行`() {
        val db = newDb()
        DataFlowPolicySql(db).grantTo(gatewayA, t0)

        val stored = DataFlowPolicySql(db).read()

        assertTrue(stored.viewFor(gatewayA).textMayEnterGateway)
        assertFalse("另一台网关：用户没同意", stored.viewFor(gatewayB).textMayEnterGateway)
        assertFalse("同一个编号、换了公钥：是另一台网关（密钥换了就是换了身份）", stored.viewFor(gatewayA.copy(keySha256 = gatewayB.keySha256)).textMayEnterGateway)
        assertFalse("同一把公钥、换了编号：也是另一台网关", stored.viewFor(gatewayA.copy(gatewayId = gatewayB.gatewayId)).textMayEnterGateway)
        assertFalse("没有配对着的网关就谈不上授权", stored.viewFor(null).textMayEnterGateway)
        assertTrue("别的网关没放行，授权本身还在、给的还是 A", stored.grantedTo == gatewayA)
    }

    @Test
    fun `给另一个网关授权：原来的那份被替换，同时只有一份授权`() {
        val db = newDb()
        val policy = DataFlowPolicySql(db)
        policy.grantTo(gatewayA, t0)

        policy.grantTo(gatewayB, t0.plusSeconds(5))

        val stored = policy.read()
        assertEquals(gatewayB, stored.grantedTo)
        assertFalse("A 的授权没有了", stored.viewFor(gatewayA).textMayEnterGateway)
        assertTrue(stored.viewFor(gatewayB).textMayEnterGateway)
    }

    @Test
    fun `换配对的清理：授权不是给新网关的就作废并留下记录；给它的原样保留；没有授权时什么也不写`() {
        val db = newDb()
        val policy = DataFlowPolicySql(db)

        policy.forgetGrantExcept(gatewayA, t0)
        assertNull("从没授权过：不凭空写一行", db.row("gateway_text_grant"))

        policy.grantTo(gatewayA, t0)
        policy.forgetGrantExcept(gatewayA, t0.plusSeconds(60))
        assertEquals("同一台网关重新配对：授权原样保留，连那一行都不重写", gatewayA, policy.read().grantedTo)
        assertEquals("granted v2 ${gatewayA.gatewayId} ${gatewayA.keySha256} 2026-11-20T08:00:00.123Z", db.row("gateway_text_grant"))

        policy.forgetGrantExcept(gatewayB, t0.plusSeconds(120))
        assertNull("配到了另一台网关：A 的授权作废", policy.read().grantedTo)
        assertEquals("作废也留下记录", "revoked v2 2026-11-20T08:02:00.123Z", db.row("gateway_text_grant"))

        policy.grantTo(gatewayB, t0.plusSeconds(180))
        policy.forgetGrantExcept(null, t0.plusSeconds(240))
        assertNull("没有配对着的网关：任何授权都作废", policy.read().grantedTo)
    }

    @Test
    fun `旧版本的授权（v1，那时授权是全局的、不知道给了谁）读起来是没授权，要用户对现在的网关重新同意`() {
        val db = newDb()
        db.rawPut("gateway_text_grant", "granted v1 2026-11-20T08:00:00.123Z")

        val stored = DataFlowPolicySql(db).read()

        assertNull(stored.grantedTo)
        assertFalse(stored.viewFor(gatewayA).textMayEnterGateway)
    }

    @Test
    fun `收回授权：立刻回到不放行（不论原来授权给了谁），收回也留下记录`() {
        val db = newDb()
        val policy = DataFlowPolicySql(db)
        policy.grantTo(gatewayA, t0)

        policy.revokeGrant(t0.plusSeconds(60))

        assertFalse(policy.read().viewFor(gatewayA).textMayEnterGateway)
        assertEquals("revoked v2 2026-11-20T08:01:00.123Z", db.row("gateway_text_grant"))
    }

    @Test
    fun `严格仅本地开着时压过授权，关掉后授权照旧生效`() {
        val policy = DataFlowPolicySql(newDb())
        policy.grantTo(gatewayA, t0)
        assertTrue(policy.read().viewFor(gatewayA).textMayEnterGateway)

        policy.setStrictLocal(true)
        assertFalse("统一门禁：授权了也不发", policy.read().viewFor(gatewayA).textMayEnterGateway)
        assertTrue(policy.read().viewFor(gatewayA).gatewayTextGranted)

        policy.setStrictLocal(false)
        assertTrue(policy.read().viewFor(gatewayA).textMayEnterGateway)
    }

    @Test
    fun `授权的行不是逐字合规就当没授权：别的版本、大小写、缺字段、多字段、编号或指纹写法不对、空、被改坏`() {
        val db = newDb()
        val policy = DataFlowPolicySql(db)
        val id = gatewayA.gatewayId
        val key = gatewayA.keySha256
        val broken = listOf(
            "granted v3 $id $key 2026-11-20T08:00:00Z", "GRANTED v2 $id $key 2026-11-20T08:00:00Z", "granted v2 $id $key", "granted v2 $id", "granted v2", "granted",
            "granted v2 $id $key 2026-11-20T08:00:00Z extra", "granted  v2 $id $key 2026-11-20T08:00:00Z",
            "granted v2 ${id.uppercase()} $key 2026-11-20T08:00:00Z", "granted v2 not-a-uuid $key 2026-11-20T08:00:00Z",
            "granted v2 $id ${key.uppercase()} 2026-11-20T08:00:00Z", "granted v2 $id ${key.take(63)} 2026-11-20T08:00:00Z", "granted v2 $id ${key}0 2026-11-20T08:00:00Z",
            "revoked v2 2026-11-20T08:00:00Z", "", "true", "1", "granted v1 2026-11-20T08:00:00Z",
        )
        for (row in broken) {
            db.rawPut("gateway_text_grant", row)

            assertNull("“$row” 不能算授权", policy.read().grantedTo)
        }
        db.rawPut("gateway_text_grant", grantRow())
        assertEquals("对照：规规矩矩的那一行是授权", gatewayA, policy.read().grantedTo)
    }

    @Test
    fun `复评 R8：授权那一行的时间读不懂就当没授权，不是时间随便写什么都行（含评审给的 granted v1 nonsense）`() {
        val db = newDb()
        val policy = DataFlowPolicySql(db)
        val id = gatewayA.gatewayId
        val key = gatewayA.keySha256
        val invalidTimes = listOf(
            "nonsense", "not-a-time", "2026-13-40T99:99:99Z", "2026-02-30T08:00:00Z", "2026-11-20", "2026-11-20T08:00:00", "08:00:00Z",
            "1763625600000", "1763625600", "2026-11-20T08:00:00+00:00", "2026-11-20T09:00:00+01:00", "2026-11-20t08:00:00z", "2026-11-20T08:00:00.000Z",
            "2026-11-20T08:00:00.1230Z", "2026-11-20T23:59:60Z", "2026-11-20T08:00:00Z\n", "???",
        )
        for (time in invalidTimes) {
            db.rawPut("gateway_text_grant", grantRow(time = time))

            assertNull("时间“$time”读不懂：整行不能算授权（不确定就是不许）", policy.read().grantedTo)
            assertFalse(policy.read().viewFor(gatewayA).textMayEnterGateway)
        }
        for (row in listOf("granted v1 nonsense", "granted v2 nonsense", "granted v2 $id nonsense", "granted v2 $id $key nonsense")) {
            db.rawPut("gateway_text_grant", row)

            assertNull("“$row” 不能算授权", policy.read().grantedTo)
        }
        db.rawPut("gateway_text_grant", grantRow(time = "2026-11-20T08:00:00Z"))
        assertEquals("对照：同一行换成规范的时间就是授权，坏的只可能是时间", gatewayA, policy.read().grantedTo)
    }

    @Test
    fun `复评 R8：严格不等于误伤：grantTo 自己写出的各种时刻（整秒、毫秒、纳秒、纪元、远古与远未来）读回来都还是授权`() {
        val instants = listOf(
            Instant.parse("2026-11-20T08:00:00Z"), Instant.parse("2026-11-20T08:00:00.123Z"), Instant.parse("2026-11-20T08:00:00.123456Z"),
            Instant.parse("2026-11-20T08:00:00.123456789Z"), Instant.EPOCH, Instant.parse("0001-01-01T00:00:00Z"), Instant.parse("9999-12-31T23:59:59.999999999Z"),
            Instant.parse("+10000-01-01T00:00:00Z"), Instant.ofEpochSecond(1_900_000_000L, 1L),
        )
        for (instant in instants) {
            val db = newDb()
            DataFlowPolicySql(db).grantTo(gatewayA, instant)

            assertEquals("$instant 写进去的那一行读回来必须还是授权（${db.row("gateway_text_grant")}）", gatewayA, DataFlowPolicySql(db).read().grantedTo)
        }
    }

    @Test
    fun `严格仅本地的行只有 off 才算没开：on 和读不懂的内容都算开（不确定就是不许）`() {
        val db = newDb()
        val policy = DataFlowPolicySql(db)
        db.rawPut("gateway_text_grant", grantRow())
        for (garbled in listOf("on", "ON", "banana", "", "false", "0", " off")) {
            db.rawPut("strict_local", garbled)

            assertTrue("“$garbled” 必须按开处理", policy.read().strictLocal)
            assertFalse(policy.read().viewFor(gatewayA).textMayEnterGateway)
        }
        db.rawPut("strict_local", "off")
        assertFalse(policy.read().strictLocal)
        assertTrue(policy.read().viewFor(gatewayA).textMayEnterGateway)
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
        val stored = policy.readOrClosed()

        assertEquals(StoredDataFlow.Closed, stored)
        assertFalse(stored.viewFor(gatewayA).textMayEnterGateway)
    }

    @Test
    fun `建表可以反复执行：重启时打开数据库不会清掉已有的设置`() {
        val db = newDb()
        DataFlowPolicySql(db).grantTo(gatewayA, t0)

        DataFlowPolicySql.SCHEMA.forEach { statement -> db.connection.createStatement().use { it.execute(statement) } }

        assertTrue(DataFlowPolicySql(db).read().viewFor(gatewayA).textMayEnterGateway)
    }
}
