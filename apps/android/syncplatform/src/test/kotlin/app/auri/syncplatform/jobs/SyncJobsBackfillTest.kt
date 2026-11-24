package app.auri.syncplatform.jobs

import app.auri.platform.library.SqliteRecordLibrary
import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 评审 F5：升级一台装过 Ticket 16 的手机时，库里已经有“已发布”的 Revision，但待同步任务表是这一版才有的。
 * 如果打开时只建一张空表，这些文字就永远不会被同步（用户配好对、网关在线，却什么都没发出去，界面还显示一切正常）。
 *
 * 这里用真正的 SQLite 引擎（sqlite-jdbc，仅测试类路径）在 JVM 上验证“打开数据库时执行的那一串语句”
 * （[SqliteSyncJobStore.SCHEMA]，即 SqliteRecordLibrary.onOpen 里的 extraSchema）：
 * 库的结构用 [SqliteRecordLibrary.SCHEMA]（真实建库语句），所以库的结构一变，这里立刻看得到。
 * 限制：Android 自带的 SQLite 版本比 sqlite-jdbc 里的旧，这里证明的是 SQL 本身；onOpen 里包事务的那段 Kotlin 与整条同步链路
 * 在设备上由 SyncPublicationTransactionTest、UpgradeThenSyncTest 验证（本机没有设备，NOT_RUN，见验收 README）。
 */
class SyncJobsBackfillTest {
    private val db: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @Before
    fun libraryAsShippedBeforeSync() {
        SqliteRecordLibrary.SCHEMA.forEach(::exec)
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    private fun exec(sql: String) {
        db.createStatement().use { it.execute(sql) }
    }

    /** 打开数据库：执行 SqliteRecordLibrary.onOpen 里的 extraSchema。 */
    private fun open() = SqliteSyncJobStore.SCHEMA.forEach(::exec)

    private fun publish(recordId: String, number: Int, seconds: Long, nanos: Int = 0) {
        db.prepareStatement(
            """
            INSERT OR IGNORE INTO records (record_id, capture_session_id, started_at_s, started_at_nano, status, end_reason,
                capture_duration_ms, speech_duration_ms, speech_segments, route_evidence_id, input_route_type, transcription)
            VALUES (?, 'session', 1, 0, 'complete', 'screen_control', 5000, 4000, '0-4000', 'route', 'bluetooth_hfp', 'DONE')
            """.trimIndent(),
        ).use { it.setString(1, recordId); it.executeUpdate() }
        db.prepareStatement(
            """
            INSERT INTO revisions (record_id, number, text, text_sha256, source, language, review_state, execution_location,
                created_at_s, created_at_nano)
            VALUES (?, ?, ?, ?, 'asr', 'zh', 'not_reviewed', 'on_device', ?, ?)
            """.trimIndent(),
        ).use {
            it.setString(1, recordId)
            it.setInt(2, number)
            it.setString(3, "正文 $recordId/$number")
            it.setString(4, "00".repeat(32))
            it.setLong(5, seconds)
            it.setInt(6, nanos)
            it.executeUpdate()
        }
    }

    private data class Row(
        val recordId: String,
        val revision: Int,
        val state: String,
        val attempts: Int,
        val uncertain: Int,
        val retryAtSeconds: Long?,
        val blockedReason: String?,
        val createdAtSeconds: Long,
        val createdAtNanos: Int,
        val updatedAtSeconds: Long,
        val updatedAtNanos: Int,
    )

    /** 与 SqliteSyncJobStore.all() 同样的次序读回任务表。 */
    private fun jobs(): List<Row> = db.createStatement().use { statement ->
        statement.executeQuery("SELECT * FROM sync_jobs ORDER BY created_at_s, created_at_nano, rowid").use { rs ->
            generateSequence { if (rs.next()) rs else null }.map {
                val retry = rs.getLong("retry_at_s").takeUnless { rs.wasNull() }
                Row(
                    recordId = rs.getString("record_id"),
                    revision = rs.getInt("revision"),
                    state = rs.getString("state"),
                    attempts = rs.getInt("attempts"),
                    uncertain = rs.getInt("uncertain"),
                    retryAtSeconds = retry,
                    blockedReason = rs.getString("blocked_reason"),
                    createdAtSeconds = rs.getLong("created_at_s"),
                    createdAtNanos = rs.getInt("created_at_nano"),
                    updatedAtSeconds = rs.getLong("updated_at_s"),
                    updatedAtNanos = rs.getInt("updated_at_nano"),
                )
            }.toList()
        }
    }

    /** 与发布事务里入队的那条任务（SqliteSyncJobStore.enqueueIn）同一个样子：等待发送、没试过、没有不确定。 */
    private fun waiting(recordId: String, revision: Int, seconds: Long, nanos: Int = 0) =
        Row(recordId, revision, "PENDING", 0, 0, null, null, seconds, nanos, seconds, nanos)

    @Test
    fun `升级时旧库里已经发布的每个 Revision 都得到一条等待发送的任务，时间就是它的发布时间`() {
        publish("record-a", 1, 100, 250_000_000)
        publish("record-b", 1, 101)
        publish("record-a", 2, 102, 5)

        open()

        assertEquals(
            "升级前发布的文字一个都不能漏：没有任务就永远不会同步",
            listOf(
                waiting("record-a", 1, 100, 250_000_000),
                waiting("record-b", 1, 101),
                waiting("record-a", 2, 102, 5),
            ),
            jobs(),
        )
    }

    @Test
    fun `发布时间完全一样时次序也是确定的：先按 Record 再按版本号，同一条 Record 的版本不会倒过来发`() {
        // 故意按“乱”的次序写进 revisions：次序必须来自回填语句本身，而不是碰巧的扫描次序
        publish("record-b", 1, 100)
        publish("record-a", 2, 100)
        publish("record-a", 1, 100)

        open()

        assertEquals(
            listOf("record-a/1", "record-a/2", "record-b/1"),
            jobs().map { "${it.recordId}/${it.revision}" },
        )
    }

    @Test
    fun `再打开一次不会重复，也不会把已有任务的进度重置`() {
        publish("record-a", 1, 100)
        publish("record-b", 1, 101)
        open()
        // 同步做过一些事：a 已经入库，b 被网关拒收后挂起
        exec("UPDATE sync_jobs SET state = 'STORED', attempts = 1, updated_at_s = 500 WHERE record_id = 'record-a'")
        exec(
            "UPDATE sync_jobs SET state = 'BLOCKED', blocked_reason = 'NOT_ORIGIN_DEVICE', attempts = 3, uncertain = 1, " +
                "retry_at_s = 900, retry_at_nano = 0, updated_at_s = 600 WHERE record_id = 'record-b'",
        )
        val before = jobs()

        open()
        open()

        assertEquals("已入库的不能退回等待发送，被拒收的不能被重新放出来", before, jobs())
        assertEquals(2, jobs().size)
    }

    @Test
    fun `库里有 Revision 却没有任务时下一次打开补上，已有的不动`() {
        publish("record-a", 1, 100)
        open()
        exec("UPDATE sync_jobs SET state = 'STORED', attempts = 1 WHERE record_id = 'record-a'")
        publish("record-a", 2, 200) // 例如上一次回填被打断、或旧版本写的

        open()

        val byKey = jobs().associateBy { it.recordId to it.revision }
        assertEquals("STORED", byKey.getValue("record-a" to 1).state)
        assertEquals(waiting("record-a", 2, 200), byKey.getValue("record-a" to 2))
        assertEquals(2, byKey.size)
    }

    @Test
    fun `进程死在建表之后、回填之前：下一次打开把回填补齐`() {
        publish("record-a", 1, 100)
        publish("record-b", 1, 101)
        val create = SqliteSyncJobStore.SCHEMA.first()
        assertTrue("第一条语句应当是建表", create.contains("CREATE TABLE IF NOT EXISTS sync_jobs"))
        exec(create) // 死在这里：表有了，回填没做
        assertTrue(jobs().isEmpty())

        open()

        assertEquals(listOf("record-a/1", "record-b/1"), jobs().map { "${it.recordId}/${it.revision}" })
    }

    @Test
    fun `全新的库没有 Revision，任务表就是空的`() {
        open()

        assertTrue(jobs().isEmpty())
    }

    private fun columns(table: String): Set<String> = db.createStatement().use { statement ->
        statement.executeQuery("PRAGMA table_info($table)").use { rs ->
            generateSequence { if (rs.next()) rs.getString("name") else null }.toSet()
        }
    }

    @Test
    fun `已有删除墓碑的 Record 不会被回填复活待同步任务`() {
        publish("record-alive", 1, 100)
        publish("record-dead", 1, 101)
        exec("INSERT INTO record_tombstones (record_id, deleted_at_s, deleted_at_nano, gateway_synced) VALUES ('record-dead', 200, 0, 0)")

        open()

        assertEquals(listOf("record-alive/1"), jobs().map { "${it.recordId}/${it.revision}" })
    }

    @Test
    fun `本机的库里还没有“已删除”与“来源设备”这两个概念：回填因此不排除任何 Revision，哪天加了列这里要变红`() {
        // 评审 F5 要求回填“不含已删除、来源是本机”的 Revision。现在：
        //  - 本库没有墓碑（删除是后续 Ticket 的事），也没有来源设备列：库里的 Record 全部由本机采集（ADR-0020，V1 没有导入路径；
        //    换机迁移与备份已经被关掉，见评审 F2），所以这两个条件现在恒成立，回填不需要再写它们；
        //  - 一旦 records / revisions 多出这类列，回填就必须据此排除，否则每次打开都会把已删除文字的任务“复活”。
        //    下面把两张表的列固定下来：改了结构就会在这里变红，逼着改动的人回头给 SqliteSyncJobStore 的回填加条件。
        assertEquals(
            setOf(
                "record_id", "capture_session_id", "started_at_s", "started_at_nano", "status", "end_reason", "capture_duration_ms",
                "speech_duration_ms", "speech_segments", "audio_location", "audio_sha256", "audio_size_bytes", "audio_sample_count",
                "audio_sample_rate_hz", "audio_channels", "route_evidence_id", "input_route_type", "headset_display_name",
                "transcription", "failure_code",
            ),
            columns("records"),
        )
        assertEquals(
            setOf(
                "record_id", "number", "text", "text_sha256", "source", "language", "review_state", "execution_location", "engine",
                "model_id", "model_version", "confidence", "created_at_s", "created_at_nano", "publication_key",
            ),
            columns("revisions"),
        )
    }
}
