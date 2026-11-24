package app.auri.syncplatform.jobs

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.auri.platform.library.SqliteRecordLibrary
import app.auri.sync.engine.BlockedReason
import app.auri.sync.engine.JobKey
import app.auri.sync.engine.NoSuchJobException
import app.auri.sync.engine.SyncJob
import app.auri.sync.engine.SyncJobStore
import app.auri.sync.machine.SyncState
import app.auri.syncplatform.dispatch.AndroidSqlDb
import app.auri.syncplatform.dispatch.SqlDb
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * 待同步任务表（`sync_jobs`）的 SQLite 实现，和 Record 库在**同一个数据库文件**里，用库的同一条连接。
 *
 * 为什么同库：发布 Revision 与“登记待同步任务”必须是同一个事务。否则进程恰好死在两步之间，就会留下一条
 * 永远不会被同步的文字（已发布却没有任务），或者一条指向不存在 Revision 的任务。入队那一步由 [enqueueIn] 提供，
 * 装配时交给库的 publicationStep，在发布 Revision 的事务里执行；步骤抛异常则整个事务回滚，两边都不留。
 *
 * 任务一行一条，主键是 record_id + revision（与网关的幂等键一致），所以同一个 Revision 不会有两条任务。
 * 状态在每次发送之前就落盘（见 SyncEngine），进程在发送途中被杀重启后，这里读到的就是“结果不确定”的那条。
 * 这里不记日志、不碰正文：任务表里没有文字。
 */
class SqliteSyncJobStore(private val library: SqliteRecordLibrary) : SyncJobStore {
    private val ownChanges = MutableStateFlow(0L)

    override suspend fun enqueue(key: JobKey, now: Instant): Boolean {
        val inserted = library.withDatabase { db -> enqueueIn(db, key, now) }
        if (inserted) ownChanges.value += 1
        return inserted
    }

    override suspend fun get(key: JobKey): SyncJob? = library.withDatabase { db ->
        db.rawQuery("SELECT * FROM sync_jobs WHERE record_id = ? AND revision = ?", arrayOf(key.recordId, key.revision.toString()))
            .use { if (it.moveToFirst()) it.toJob() else null }
    }

    override suspend fun all(): List<SyncJob> = library.withDatabase { db ->
        db.rawQuery("SELECT * FROM sync_jobs ORDER BY created_at_s, created_at_nano, rowid", null)
            .use { cursor -> generateSequence { if (cursor.moveToNext()) cursor.toJob() else null }.toList() }
    }

    override suspend fun update(job: SyncJob) {
        val updated = library.withDatabase { db ->
            db.update("sync_jobs", job.toValues(), "record_id = ? AND revision = ?", arrayOf(job.key.recordId, job.key.revision.toString()))
        }
        if (updated == 0) throw NoSuchJobException(job.key)
        ownChanges.value += 1
    }

    /** 自己改了任务表、或者库里提交了新内容（发布 Revision 时在同一事务里入了队）都重读一遍。 */
    override fun observe(): Flow<List<SyncJob>> = combine(ownChanges, library.changeSignal) { _, _ -> }.map { all() }

    companion object {
        /** 任务表的建表语句。 */
        val CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS sync_jobs (
                record_id TEXT NOT NULL,
                revision INTEGER NOT NULL CHECK (revision >= 1),
                state TEXT NOT NULL,
                attempts INTEGER NOT NULL CHECK (attempts >= 0),
                uncertain INTEGER NOT NULL CHECK (uncertain IN (0, 1)),
                retry_at_s INTEGER,
                retry_at_nano INTEGER,
                blocked_reason TEXT,
                created_at_s INTEGER NOT NULL,
                created_at_nano INTEGER NOT NULL,
                updated_at_s INTEGER NOT NULL,
                updated_at_nano INTEGER NOT NULL,
                PRIMARY KEY (record_id, revision)
            )
        """.trimIndent()

        /** 回填语句（见 [SCHEMA]）。 */
        fun backfill(): String = """
            INSERT INTO sync_jobs (
                record_id, revision, state, attempts, uncertain, retry_at_s, retry_at_nano, blocked_reason,
                created_at_s, created_at_nano, updated_at_s, updated_at_nano
            )
            SELECT r.record_id, r.number, '${SyncState.PENDING.name}', 0, 0, NULL, NULL, NULL,
                   r.created_at_s, r.created_at_nano, r.created_at_s, r.created_at_nano
            FROM revisions r
            WHERE NOT EXISTS (SELECT 1 FROM sync_jobs j WHERE j.record_id = r.record_id AND j.revision = r.number)
              AND NOT EXISTS (SELECT 1 FROM record_tombstones t WHERE t.record_id = r.record_id)
            ORDER BY r.created_at_s, r.created_at_nano, r.record_id, r.number
        """.trimIndent()

        /**
         * 交给 [SqliteRecordLibrary] 的 extraSchema：每次打开数据库时在**一个事务里**按顺序幂等执行——先建表，再回填。
         *
         * 回填（评审 F5）：升级一台装过 Ticket 16 的手机时，库里已经有“已发布”的 Revision，却没有任务；只建空表的话这些文字永远不会同步。
         * 所以凡是库里有 Revision 而任务表里还没有它的，补一条等待发送的任务——与发布事务里入队的那条（[enqueueIn]）一个样子，
         * 时间取 Revision 自己的发布时间（所以先发布的先同步，并且和“当时就入了队”没有区别）。
         * 幂等：已有任务（不论进展到哪）一条都不动，重复打开不会重复；进程死在建表与回填之间，下一次打开补齐。
         *
         * 条件：本库没有墓碑（删除是后续 Ticket 的事）、也没有来源设备列——库里的 Record 全部由本机采集（ADR-0020，V1 没有导入路径，
         * 换机迁移与备份已被关掉），所以评审说的“未删除、来源是本机”现在恒成立。**加墓碑的那个 Ticket 必须在这条回填里排除已删除的 Record**，
         * 否则每次打开都会把它们的任务复活；SyncJobsBackfillTest 把两张表的列固定住了，改了结构那里会变红。
         */
        val SCHEMA = listOf(CREATE_TABLE, backfill())

        /**
         * 在调用方已经打开的事务里入队一条 PENDING 任务（同一个键已存在则什么都不做）。
         * 发布 Revision 的事务通过它登记任务；不自己开关事务，成败跟着外面那个事务走。
         */
        fun enqueueIn(db: SQLiteDatabase, key: JobKey, now: Instant): Boolean = enqueueIn(AndroidSqlDb(db), key, now)

        /** 同上，写在 [SqlDb] 上：发送账（[app.auri.syncplatform.dispatch.DispatchSql]）与 JVM 上的真 SQLite 测试共用这一条语句。 */
        fun enqueueIn(sql: SqlDb, key: JobKey, now: Instant): Boolean = sql.update(
            """
            INSERT OR IGNORE INTO sync_jobs (
                record_id, revision, state, attempts, uncertain, retry_at_s, retry_at_nano, blocked_reason,
                created_at_s, created_at_nano, updated_at_s, updated_at_nano
            ) VALUES (?, ?, ?, 0, 0, NULL, NULL, NULL, ?, ?, ?, ?)
            """.trimIndent(),
            key.recordId,
            key.revision,
            SyncState.PENDING.name,
            now.epochSecond,
            now.nano,
            now.epochSecond,
            now.nano,
        ) > 0
    }
}

private fun SyncJob.toValues() = ContentValues().apply {
    put("state", state.name)
    put("attempts", attempts)
    put("uncertain", if (uncertain) 1 else 0)
    val retry = retryAt
    if (retry == null) {
        putNull("retry_at_s")
        putNull("retry_at_nano")
    } else {
        put("retry_at_s", retry.epochSecond)
        put("retry_at_nano", retry.nano)
    }
    val reason = blockedReason
    if (reason == null) putNull("blocked_reason") else put("blocked_reason", reason.name)
    put("created_at_s", createdAt.epochSecond)
    put("created_at_nano", createdAt.nano)
    put("updated_at_s", updatedAt.epochSecond)
    put("updated_at_nano", updatedAt.nano)
}

private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column))

private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))

private fun Cursor.instant(seconds: String, nanos: String): Instant = Instant.ofEpochSecond(long(seconds), long(nanos))

private fun Cursor.toJob(): SyncJob {
    val retryIndex = getColumnIndexOrThrow("retry_at_s")
    val reasonIndex = getColumnIndexOrThrow("blocked_reason")
    return SyncJob(
        key = JobKey(string("record_id"), long("revision").toInt()),
        state = SyncState.valueOf(string("state")),
        attempts = long("attempts").toInt(),
        uncertain = long("uncertain") == 1L,
        retryAt = if (isNull(retryIndex)) null else instant("retry_at_s", "retry_at_nano"),
        blockedReason = if (isNull(reasonIndex)) null else BlockedReason.valueOf(getString(reasonIndex)),
        createdAt = instant("created_at_s", "created_at_nano"),
        updatedAt = instant("updated_at_s", "updated_at_nano"),
    )
}
