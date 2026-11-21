package app.auri.syncplatform.dispatch

import app.auri.capture.contract.RevisionSource
import app.auri.platform.library.SqliteRecordLibrary
import app.auri.sync.destinations.Choice
import app.auri.sync.destinations.DecideResult
import app.auri.sync.destinations.DispatchItem
import app.auri.sync.destinations.DispatchStore
import app.auri.sync.destinations.IntentResult
import app.auri.sync.engine.BlockedReason
import app.auri.sync.engine.DeliveryAddition
import app.auri.sync.engine.JobKey
import app.auri.sync.engine.NoSuchJobException
import app.auri.sync.engine.Refusal
import app.auri.sync.engine.SyncJob
import app.auri.sync.engine.SyncJobStore
import app.auri.sync.gateway.Destination
import app.auri.sync.gateway.Intent
import app.auri.sync.gateway.Publication
import app.auri.sync.machine.SyncState
import app.auri.sync.testing.DispatchFixture
import app.auri.syncplatform.jobs.SqliteSyncJobStore
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * [DispatchStore] 在 JVM 测试里的壳：每个方法就是在真 SQLite 引擎上跑一遍 [DispatchSql]（与 Android 上的 [SqliteDispatchStore] 一样，
 * 区别只在“谁来提供 SqlDb、谁来切线程”）。壳本身没有逻辑，所以通过 DispatchStoreContract 就说明了 SQL 与逻辑的语义。
 */
class JdbcDispatchStore(private val sql: JdbcSqlDb, private val signal: MutableStateFlow<Long>) : DispatchStore {
    private val logic get() = DispatchSql(sql)

    private fun <T> write(block: DispatchSql.() -> T): T = logic.block().also { signal.value += 1 }

    override suspend fun destinations(): List<Destination> = logic.destinations()

    override suspend fun applyDestinations(gatewayId: String, destinations: List<Destination>, authoritative: Boolean, now: Instant) =
        write { applyDestinations(gatewayId, destinations, authoritative, now) }

    override suspend fun forgetDestinations(now: Instant) = write { forgetDestinations(now) }

    override suspend fun useGateway(gatewayId: String) {
        if (logic.useGateway(gatewayId)) signal.value += 1
    }

    override suspend fun item(key: JobKey): DispatchItem? = logic.item(key)

    override suspend fun items(limit: Int): List<DispatchItem> = logic.items(limit)

    override suspend fun awaiting(): List<DispatchItem> = logic.awaiting()

    override suspend fun itemsFor(destinationId: String, limit: Int): List<DispatchItem> = logic.itemsFor(destinationId, limit)

    override suspend fun decide(key: JobKey, destinationId: String, choice: Choice, now: Instant): DecideResult =
        write { decide(key, destinationId, choice, now) }

    override suspend fun setIntent(key: JobKey, intent: Intent, now: Instant): IntentResult = write { setIntent(key, intent, now) }

    override suspend fun publicationFor(key: JobKey): Publication = write { publicationFor(key) }

    override suspend fun unfreezeUnsent(key: JobKey) {
        if (logic.unfreezeUnsent(key)) signal.value += 1
    }

    override suspend fun due(): List<DeliveryAddition> = logic.due()

    override suspend fun gatewayHas(key: JobKey, destinationIds: Collection<String>) = write { gatewayHas(key, destinationIds) }

    override suspend fun refused(key: JobKey, destinationIds: Collection<String>, reason: Refusal) = write { refused(key, destinationIds, reason) }

    override suspend fun defaultIntent(): Intent = logic.defaultIntent()

    override suspend fun setDefaultIntent(intent: Intent) = write { setDefaultIntent(intent) }

    override fun changes(): Flow<Long> = signal
}

/** 任务表的视图（入队、整行替换）：契约测试读任务表里有没有、什么样，并用 [update] 模拟引擎把任务推到 STORED。 */
class JdbcJobsView(private val sql: SqlDb) : SyncJobStore {
    override suspend fun enqueue(key: JobKey, now: Instant): Boolean = SqliteSyncJobStore.enqueueIn(sql, key, now)

    override suspend fun get(key: JobKey): SyncJob? =
        sql.query("SELECT * FROM sync_jobs WHERE record_id = ? AND revision = ?", key.recordId, key.revision) { it.toJob() }.firstOrNull()

    override suspend fun all(): List<SyncJob> = sql.query("SELECT * FROM sync_jobs ORDER BY created_at_s, created_at_nano, rowid") { it.toJob() }

    override suspend fun update(job: SyncJob) {
        val updated = sql.update(
            """
            UPDATE sync_jobs SET state = ?, attempts = ?, uncertain = ?, retry_at_s = ?, retry_at_nano = ?, blocked_reason = ?,
                updated_at_s = ?, updated_at_nano = ?
            WHERE record_id = ? AND revision = ?
            """.trimIndent(),
            job.state.name,
            job.attempts,
            if (job.uncertain) 1 else 0,
            job.retryAt?.epochSecond,
            job.retryAt?.nano,
            job.blockedReason?.name,
            job.updatedAt.epochSecond,
            job.updatedAt.nano,
            job.key.recordId,
            job.key.revision,
        )
        if (updated == 0) throw NoSuchJobException(job.key)
    }

    override fun observe(): Flow<List<SyncJob>> = error("测试里不用")

    // SELECT * 的列顺序就是建表语句的顺序：record_id, revision, state, attempts, uncertain, retry_at_s, retry_at_nano, blocked_reason,
    // created_at_s, created_at_nano, updated_at_s, updated_at_nano
    private fun SqlRow.toJob() = SyncJob(
        key = JobKey(string(0), long(1).toInt()),
        state = SyncState.valueOf(string(2)),
        attempts = long(3).toInt(),
        uncertain = long(4) == 1L,
        retryAt = longOrNull(5)?.let { Instant.ofEpochSecond(it, long(6)) },
        blockedReason = stringOrNull(7)?.let(BlockedReason::valueOf),
        createdAt = Instant.ofEpochSecond(long(8), long(9)),
        updatedAt = Instant.ofEpochSecond(long(10), long(11)),
    )
}

/**
 * 一个“磁盘”（一条内存 SQLite 连接）和它上面的发送账。[publish] 做的就是 SqliteRecordLibrary.publishRevision 做的事：
 * 同一个事务里写 Revision 行、再执行 publicationStep（[DispatchSql.publish]）。[reopen] 是“进程重启，重新打开数据库”：
 * 重新执行 [DispatchSql.SCHEMA]（建表加回填，与 onOpen 里一样在一个事务里）。
 */
class JdbcDispatchFixture(private val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:"), freshDisk: Boolean = true) : DispatchFixture {
    private val sql = JdbcSqlDb(connection)
    private val signal = MutableStateFlow(0L)

    init {
        if (freshDisk) SqliteRecordLibrary.SCHEMA.forEach { statement -> connection.createStatement().use { it.execute(statement) } }
        open()
    }

    /** 打开数据库：onOpen 里的 extraSchema，一个事务里按顺序执行。 */
    private fun open() = sql.transaction {
        DispatchSql.SCHEMA.forEach { statement -> connection.createStatement().use { it.execute(statement) } }
    }

    override val store: DispatchStore = JdbcDispatchStore(sql, signal)

    override val jobs: SyncJobStore = JdbcJobsView(sql)

    override suspend fun publish(key: JobKey, source: RevisionSource, now: Instant) {
        sql.transaction<Unit> {
            sql.update(
                """
                INSERT OR IGNORE INTO records (record_id, capture_session_id, started_at_s, started_at_nano, status, end_reason,
                    capture_duration_ms, speech_duration_ms, speech_segments, route_evidence_id, input_route_type, transcription)
                VALUES (?, 'session', 1, 0, 'complete', 'screen_control', 5000, 4000, '0-4000', 'route', 'bluetooth_hfp', 'DONE')
                """.trimIndent(),
                key.recordId,
            )
            sql.update(
                """
                INSERT INTO revisions (record_id, number, text, text_sha256, source, language, review_state, execution_location,
                    created_at_s, created_at_nano)
                VALUES (?, ?, ?, ?, ?, 'zh', 'not_reviewed', 'on_device', ?, ?)
                """.trimIndent(),
                key.recordId,
                key.revision,
                "正文",
                "00".repeat(32),
                if (source == RevisionSource.ASR) "asr" else "user_edit",
                now.epochSecond,
                now.nano,
            )
            DispatchSql(sql).publish(key, source, now)
        }
        signal.value += 1
    }

    override fun reopen(): DispatchFixture = JdbcDispatchFixture(connection, freshDisk = false)

    /** 测试里直接看一眼库（SQL 层面的断言用）。 */
    val db: JdbcSqlDb get() = sql
}
