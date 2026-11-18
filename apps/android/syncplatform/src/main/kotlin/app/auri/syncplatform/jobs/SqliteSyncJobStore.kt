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
        /** 交给 [SqliteRecordLibrary] 的 extraSchema：每次打开数据库时幂等执行。 */
        val SCHEMA = listOf(
            """
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
            """.trimIndent(),
        )

        /**
         * 在调用方已经打开的事务里入队一条 PENDING 任务（同一个键已存在则什么都不做）。
         * 发布 Revision 的事务通过它登记任务；不自己开关事务，成败跟着外面那个事务走。
         */
        fun enqueueIn(db: SQLiteDatabase, key: JobKey, now: Instant): Boolean {
            val values = ContentValues().apply {
                put("record_id", key.recordId)
                put("revision", key.revision)
                put("state", SyncState.PENDING.name)
                put("attempts", 0)
                put("uncertain", 0)
                putNull("retry_at_s")
                putNull("retry_at_nano")
                putNull("blocked_reason")
                put("created_at_s", now.epochSecond)
                put("created_at_nano", now.nano)
                put("updated_at_s", now.epochSecond)
                put("updated_at_nano", now.nano)
            }
            return db.insertWithOnConflict("sync_jobs", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        }
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
