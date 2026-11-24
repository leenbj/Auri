package app.auri.syncplatform.dispatch

import app.auri.capture.contract.RevisionSource
import app.auri.platform.library.SqliteRecordLibrary
import app.auri.sync.destinations.Choice
import app.auri.sync.destinations.DecideResult
import app.auri.sync.destinations.IntentResult
import app.auri.sync.engine.JobKey
import app.auri.sync.engine.Refusal
import app.auri.sync.gateway.Destination
import app.auri.sync.gateway.Intent
import app.auri.sync.gateway.SendMode
import app.auri.sync.pairing.GatewayIdentity
import app.auri.syncplatform.jobs.SqliteSyncJobStore
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 只有真 SQL 才会出问题的地方：升级一台装过上一版（只有任务表、没有发送账）的手机、发布与规划同进退、冻结意图与回滚、
 * 重复打开数据库、含引号与表情的名字原样存取。行为本身（预览确认、幂等、账）由 [SqliteDispatchStoreContractTest] 验证。
 */
class DispatchSqlTest {
    private val t0 = Instant.parse("2026-11-20T08:00:00.123456789Z")
    private val gateway = GatewayIdentity("fa1b98ec-1bfb-53b3-85a3-4d36b680fe6e", "11".repeat(32))
    private val auto = "65ea2953-505c-5c6f-9474-7cd5b8474104"
    private val preview = "9b1a23f8-5241-4e78-9d21-15c4c720c2c9"
    private val recordA = "a4a7c08d-2767-50d6-9238-6fa277033a49"
    private val recordB = "b5b8d19e-3878-5ae7-a349-7fb388144b5a"
    private val keyA = JobKey(recordA, 1)
    private val keyB = JobKey(recordB, 1)

    private fun destination(id: String, mode: SendMode, name: String = "目的地", intents: Set<Intent> = setOf(Intent.CONTEXT)) =
        Destination(id, name, mode, intents, t0, "ef7b1358-df31-520f-90f3-d98fc5173b46", credentialIssued = false, revokedAt = null)

    private fun JdbcSqlDb.count(table: String): Int = query("SELECT COUNT(*) FROM $table") { it.long(0).toInt() }.single()

    // ---- 凭证从不落盘（Ticket 22 第 2 项）----

    @Test
    fun the_tables_hold_only_public_facts_so_there_is_no_column_where_a_credential_could_be_stored() = runBlocking {
        val fixture = JdbcDispatchFixture()
        val columns = listOf("dispatch_meta", "dispatch_destinations", "dispatch_items", "dispatch_targets").associateWith { table ->
            fixture.db.query("PRAGMA table_info($table)") { it.string(1) }.toSet()
        }

        // 一列一列列出来：新增任何一列（比如存个 credential）都会让这个测试失败，逼人想清楚它会不会带走凭证
        assertEquals(
            mapOf(
                "dispatch_meta" to setOf("name", "value"),
                "dispatch_destinations" to setOf(
                    "destination_id", "display_name", "send_mode", "allowed_intents", "created_at_s", "created_at_nano",
                    "created_by_device_id", "credential_issued", "credential_active", "revoked_at_s", "revoked_at_nano",
                ),
                "dispatch_items" to setOf("record_id", "revision", "intent", "created_at_s", "created_at_nano", "intent_locked"),
                "dispatch_targets" to setOf("record_id", "revision", "destination_id", "send_mode", "decision", "delivery", "refusal"),
            ),
            columns,
        )
        // “是否签发过”“现在有没有有效凭证”都是 0/1 的标志（轮换前要先确认、暂停要显示），不是凭证
        val types = fixture.db.query("PRAGMA table_info(dispatch_destinations)") { it.string(1) to it.string(2) }.toMap()
        assertEquals("INTEGER", types.getValue("credential_issued"))
        assertEquals("INTEGER", types.getValue("credential_active"))
    }

    // ---- 打开数据库（建表加回填）----

    @Test
    fun opening_the_database_again_changes_nothing() = runBlocking {
        val fixture = JdbcDispatchFixture()
        fixture.store.applyDestinations(gateway, listOf(destination(auto, SendMode.AUTO_SEND), destination(preview, SendMode.PREVIEW_CONFIRM)), true, t0)
        fixture.publish(keyA, RevisionSource.ASR, t0)
        val before = listOf("dispatch_meta", "dispatch_destinations", "dispatch_items", "dispatch_targets", "sync_jobs").map { fixture.db.count(it) }

        val reopened = fixture.reopen() as JdbcDispatchFixture
        val twice = reopened.reopen() as JdbcDispatchFixture

        val after = listOf("dispatch_meta", "dispatch_destinations", "dispatch_items", "dispatch_targets", "sync_jobs").map { twice.db.count(it) }
        assertEquals("打开几次都一样：不多出行、不丢行", before, after)
        assertEquals("文字发布时就登记了任务（不等预览确认），重启不会多一条也不会少一条", 1, twice.db.count("sync_jobs"))
    }

    @Test
    fun upgrading_a_phone_that_only_had_the_job_table_keeps_its_jobs_and_backfills_the_rest() = runBlocking {
        // 上一版的手机：Record 库加任务表（SqliteSyncJobStore.SCHEMA），已有两条文字，一条正在发送，另一条（Ticket 21 之前发布的）没有任务
        val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        SqliteRecordLibrary.SCHEMA.forEach { connection.createStatement().use { s -> s.execute(it) } }
        SqliteSyncJobStore.SCHEMA.forEach { connection.createStatement().use { s -> s.execute(it) } }
        val db = JdbcSqlDb(connection)
        for ((record, seconds) in listOf(recordA to 100L, recordB to 200L)) {
            db.update(
                """
                INSERT INTO records (record_id, capture_session_id, started_at_s, started_at_nano, status, end_reason,
                    capture_duration_ms, speech_duration_ms, speech_segments, route_evidence_id, input_route_type, transcription)
                VALUES (?, 'session', 1, 0, 'complete', 'screen_control', 5000, 4000, '0-4000', 'route', 'bluetooth_hfp', 'DONE')
                """.trimIndent(),
                record,
            )
            db.update(
                "INSERT INTO revisions (record_id, number, text, text_sha256, source, language, review_state, execution_location, created_at_s, created_at_nano) VALUES (?, 1, 'x', ?, 'asr', 'zh', 'not_reviewed', 'on_device', ?, 0)",
                record,
                "00".repeat(32),
                seconds,
            )
        }
        SqliteSyncJobStore.enqueueIn(db, keyA, t0)
        db.update("UPDATE sync_jobs SET state = 'SENDING', attempts = 3 WHERE record_id = ?", recordA)
        assertEquals("前提：旧版的 A 有任务、B 没有", listOf(recordA), db.query("SELECT record_id FROM sync_jobs") { it.string(0) })

        // 升级后第一次打开：执行新的 SCHEMA
        db.transaction { DispatchSql.SCHEMA.forEach { connection.createStatement().use { s -> s.execute(it) } } }

        val jobs = db.query("SELECT record_id, state, attempts FROM sync_jobs ORDER BY record_id") { Triple(it.string(0), it.string(1), it.long(2)) }
        assertEquals(
            "已有的任务一条都不动（进展、次数照旧），没有任务的旧文字补上一条等待发送的",
            listOf(Triple(recordA, "SENDING", 3L), Triple(recordB, "PENDING", 0L)),
            jobs,
        )
        assertEquals("旧文字没有发送账：它们从来没被规划过，照旧只入库", 0, db.count("dispatch_items"))
    }

    @Test
    fun the_backfill_gives_a_job_to_every_revision_without_one_and_leaves_planned_items_with_their_own_job_alone() = runBlocking {
        val fixture = JdbcDispatchFixture()
        fixture.store.applyDestinations(gateway, listOf(destination(preview, SendMode.PREVIEW_CONFIRM)), true, t0)
        fixture.publish(keyA, RevisionSource.ASR, t0)
        // 一条没被规划过的旧文字：直接在库里放一行 Revision（模拟升级前发布的）
        fixture.db.update("INSERT OR IGNORE INTO records (record_id, capture_session_id, started_at_s, started_at_nano, status, end_reason, capture_duration_ms, speech_duration_ms, speech_segments, route_evidence_id, input_route_type, transcription) VALUES (?, 's', 1, 0, 'complete', 'screen_control', 1, 1, '0-1', 'r', 'bluetooth_hfp', 'DONE')", recordB)
        fixture.db.update("INSERT INTO revisions (record_id, number, text, text_sha256, source, language, review_state, execution_location, created_at_s, created_at_nano) VALUES (?, 1, 'x', ?, 'asr', 'zh', 'not_reviewed', 'on_device', 5, 0)", recordB, "00".repeat(32))
        val before = fixture.jobs.get(keyA)

        val reopened = fixture.reopen()

        assertEquals("A 在发布时就有任务（预览确认只拦 Delivery、不拦文字）：回填不动它", before, reopened.jobs.get(keyA))
        assertNotNull("没规划过的旧文字 B：照旧补任务", reopened.jobs.get(keyB))
        assertEquals(2, reopened.jobs.all().size)
    }

    // ---- 发布与规划同进退 ----

    @Test
    fun publishing_and_planning_commit_or_roll_back_together() = runBlocking {
        val fixture = JdbcDispatchFixture()
        fixture.store.applyDestinations(gateway, listOf(destination(auto, SendMode.AUTO_SEND), destination(preview, SendMode.PREVIEW_CONFIRM)), true, t0)

        // 发布 Revision 的事务里，规划之后别的步骤失败了：库的 publishRevision 会整个回滚
        try {
            fixture.db.transaction<Unit> {
                fixture.db.update("INSERT INTO records (record_id, capture_session_id, started_at_s, started_at_nano, status, end_reason, capture_duration_ms, speech_duration_ms, speech_segments, route_evidence_id, input_route_type, transcription) VALUES (?, 's', 1, 0, 'complete', 'screen_control', 1, 1, '0-1', 'r', 'bluetooth_hfp', 'DONE')", recordA)
                fixture.db.update("INSERT INTO revisions (record_id, number, text, text_sha256, source, language, review_state, execution_location, created_at_s, created_at_nano) VALUES (?, 1, 'x', ?, 'asr', 'zh', 'not_reviewed', 'on_device', 5, 0)", recordA, "00".repeat(32))
                DispatchSql(fixture.db).publish(keyA, RevisionSource.ASR, t0)
                error("发布后面的步骤失败")
            }
            fail("应当抛出")
        } catch (expected: IllegalStateException) {
            // 预期
        }

        for (table in listOf("revisions", "dispatch_items", "dispatch_targets", "sync_jobs")) {
            assertEquals("$table 里不该留下任何行：已发布、已规划、已拦住要么都在要么都不在", 0, fixture.db.count(table))
        }
    }

    @Test
    fun confirming_a_preview_target_records_only_the_decision_the_job_row_is_not_touched() = runBlocking {
        val fixture = JdbcDispatchFixture()
        fixture.store.applyDestinations(gateway, listOf(destination(preview, SendMode.PREVIEW_CONFIRM)), true, t0)
        fixture.publish(keyA, RevisionSource.ASR, t0)
        val jobBefore = fixture.jobs.get(keyA)
        assertEquals(listOf("NOT_YET"), fixture.db.query("SELECT delivery FROM dispatch_targets") { it.string(0) })

        val decided = fixture.store.decide(keyA, preview, Choice.CONFIRM, t0.plusSeconds(9))

        assertEquals(DecideResult.Decided, decided)
        assertEquals("确认只记决定，不创建、不改动同步任务（文字早在发布时就登记了）", jobBefore, fixture.jobs.get(keyA))
        assertEquals(listOf("CONFIRMED"), fixture.db.query("SELECT decision FROM dispatch_targets") { it.string(0) })
        assertEquals("网关上有没有它的 Delivery 是另一本账：确认之后仍然是“还没有”", listOf("NOT_YET"), fixture.db.query("SELECT delivery FROM dispatch_targets") { it.string(0) })
    }

    // ---- 意图冻结与回滚（评审 M3）----

    @Test
    fun building_the_submission_freezes_the_intent_in_the_same_transaction_and_a_rolled_back_build_freezes_nothing() = runBlocking {
        val fixture = JdbcDispatchFixture()
        fixture.store.applyDestinations(gateway, listOf(destination(auto, SendMode.AUTO_SEND, intents = setOf(Intent.CONTEXT, Intent.ACTION_REQUEST))), true, t0)
        fixture.publish(keyA, RevisionSource.ASR, t0)
        fun locked() = fixture.db.query("SELECT intent_locked FROM dispatch_items") { it.long(0) }.single()

        // 构造提交的事务在提交前失败：冻结要跟着回滚，否则用户的标签就在一次没发出去的请求里被锁死了
        try {
            fixture.db.transaction<Unit> {
                DispatchSql(fixture.db).publicationFor(keyA)
                error("构造提交之后、提交之前出了事")
            }
            fail("应当抛出")
        } catch (expected: IllegalStateException) {
            // 预期
        }
        assertEquals("回滚了：没冻结", 0L, locked())
        assertEquals(IntentResult.Changed, fixture.store.setIntent(keyA, Intent.ACTION_REQUEST, t0))

        fixture.store.publicationFor(keyA)

        assertEquals("提交的那一刻落盘冻结", 1L, locked())
        assertEquals(IntentResult.Locked, fixture.store.setIntent(keyA, Intent.CONTEXT, t0))
        assertEquals("冻结的是改后的值", "action_request", fixture.db.query("SELECT intent FROM dispatch_items") { it.string(0) }.single())
    }

    // ---- 网关上有没有 Delivery 的账 ----

    @Test
    fun the_gateway_ledger_columns_round_trip_and_a_refusal_keeps_its_reason_until_the_gateway_says_otherwise() = runBlocking {
        val fixture = JdbcDispatchFixture()
        fixture.store.applyDestinations(gateway, listOf(destination(auto, SendMode.AUTO_SEND)), true, t0)
        fixture.publish(keyA, RevisionSource.ASR, t0)
        fun ledger() = fixture.db.query("SELECT delivery, refusal FROM dispatch_targets") { it.string(0) to it.stringOrNull(1) }.single()

        fixture.store.refused(keyA, listOf(auto), Refusal.REJECTED)
        assertEquals("REFUSED" to "REJECTED", ledger())

        fixture.store.gatewayHas(keyA, listOf(auto))
        assertEquals("网关说有就是有，拒绝的原因一起清掉", "CREATED" to null, ledger())

        fixture.store.refused(keyA, listOf(auto), Refusal.NOT_CREATED)
        assertEquals("已经记了网关有：迟到的拒绝不改写", "CREATED" to null, ledger())
    }

    // ---- 任务行与回填行同一个样子 ----

    @Test
    fun a_job_enqueued_by_the_dispatch_sql_looks_exactly_like_one_the_backfill_would_have_made() = runBlocking {
        val fixture = JdbcDispatchFixture()
        val published = Instant.ofEpochSecond(1_700_000_000, 123_456_789)
        // B：发布时入队（本类走的是 SqliteSyncJobStore.enqueueIn(SqlDb)）；A：同样的 Revision，但先删掉任务再让回填补
        fixture.publish(keyA, RevisionSource.USER_EDIT, published)
        fixture.publish(JobKey(recordB, 2), RevisionSource.USER_EDIT, published)
        fixture.db.update("DELETE FROM sync_jobs WHERE record_id = ?", recordA)
        fixture.reopen() // 回填给 A 补一条

        fun row(key: JobKey) = fixture.db.query("SELECT state, attempts, uncertain, retry_at_s, retry_at_nano, blocked_reason, created_at_s, created_at_nano, updated_at_s, updated_at_nano FROM sync_jobs WHERE record_id = ? AND revision = ?", key.recordId, key.revision) { r ->
            listOf(r.string(0), r.long(1), r.long(2), r.longOrNull(3), r.longOrNull(4), r.stringOrNull(5), r.long(6), r.long(7), r.long(8), r.long(9))
        }.single()

        assertEquals(row(JobKey(recordB, 2)), row(keyA))
    }

    // ---- 存取 ----

    @Test
    fun names_with_quotes_unicode_and_sql_look_alike_text_round_trip_exactly() = runBlocking {
        val fixture = JdbcDispatchFixture()
        val names = listOf("它的名字 'with' \"quotes\"", "Robert'); DROP TABLE dispatch_targets;--", "🙂 小号 Agent", "a\nb")
        val ids = listOf(auto, preview, "d214122a-4c32-4469-a0cc-24df2a128725", "0f0f0f0f-1bfb-53b3-85a3-4d36b680fe6e")
        val destinations = names.zip(ids).mapIndexed { index, (name, id) ->
            destination(id, if (index % 2 == 0) SendMode.AUTO_SEND else SendMode.PREVIEW_CONFIRM, name, setOf(Intent.CONTEXT, Intent.ACTION_REQUEST))
                .copy(createdAt = t0.plusSeconds(index.toLong()))
        }

        fixture.store.applyDestinations(gateway, destinations, true, t0)

        assertEquals(destinations, fixture.store.destinations())
        assertEquals("表没有被名字里的语句弄坏", 4, fixture.db.count("dispatch_destinations"))
    }

    @Test
    fun a_revoked_destination_and_its_flags_round_trip_with_nanosecond_precision() = runBlocking {
        val fixture = JdbcDispatchFixture()
        val revoked = destination(auto, SendMode.AUTO_SEND).copy(credentialIssued = true, revokedAt = t0.plusNanos(7))

        fixture.store.applyDestinations(gateway, listOf(revoked), true, t0)

        val read = fixture.store.destinations().single()
        assertEquals(revoked, read)
        assertTrue(read.credentialIssued)
        assertFalse(read.active)
    }

    @Test
    fun a_suspended_destination_round_trips_and_is_still_not_revoked() = runBlocking {
        val fixture = JdbcDispatchFixture()
        val suspended = destination(auto, SendMode.AUTO_SEND).copy(credentialIssued = true, credentialActive = false)

        fixture.store.applyDestinations(gateway, listOf(suspended), true, t0)

        val read = fixture.reopen().store.destinations().single()
        assertEquals("重启之后也还知道它暂停了", suspended, read)
        assertTrue(read.suspended)
        assertTrue("暂停不是撤销", read.active)
        assertFalse(read.receivesNewText)
    }

    @Test
    fun the_default_intent_and_the_gateway_id_live_in_the_meta_table_and_nothing_else_is_stored_there() = runBlocking {
        val fixture = JdbcDispatchFixture()
        fixture.store.setDefaultIntent(Intent.ACTION_REQUEST)
        fixture.store.applyDestinations(gateway, emptyList(), true, t0)

        val meta = fixture.db.query("SELECT name, value FROM dispatch_meta ORDER BY name") { it.string(0) to it.string(1) }

        assertEquals("缓存记着的是完整身份（编号加公钥指纹），不只是编号（复评三 S4）", listOf("default_intent" to "action_request", "gateway_id" to gateway.ledgerKey), meta)
    }

    @Test
    fun the_dispatch_tables_hold_no_text_no_credentials_and_no_addresses() {
        val columns = JdbcDispatchFixture().db.run {
            listOf("dispatch_meta", "dispatch_destinations", "dispatch_items", "dispatch_targets").flatMap { table ->
                query("SELECT name FROM pragma_table_info('$table')") { it.string(0) }.map { "$table.$it" }
            }
        }

        val forbidden = listOf("text", "body", "token", "secret", "credential_value", "address", "host", "url", "cert", "audio", "transcript")
        for (column in columns) {
            assertTrue("$column 像是存了不该存的东西", forbidden.none { column.substringAfter('.').contains(it) })
        }
        assertTrue("credential_issued 只是个是否的标志", "dispatch_destinations.credential_issued" in columns)
    }
}
