package app.auri.platform.jobs

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.auri.capture.contract.ErrorCode
import app.auri.capture.machine.TranscriptionState
import app.auri.capture.scheduler.JobKind
import app.auri.capture.scheduler.JobStore
import app.auri.capture.scheduler.ResourceReason
import app.auri.capture.scheduler.TranscriptionJob
import app.auri.capture.scheduler.WaitReason
import app.auri.platform.library.unwire
import app.auri.platform.library.wire
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 转录任务库的 SQLite 实现：进程重启之后，任务与它的状态、等待原因、已试次数、重试时间都还在（Ticket 20）。
 * 单独一个数据库文件，不和 Record 库同库：两边各自落盘，对账由调度器做（它重启后拿 Record 库校对任务）。
 * 一个任务一行，每次状态变化整行覆盖；枚举存合同里的取值或常量名；时间存毫秒（`Clock.now()` 给的就是毫秒精度）。
 * 行里没有正文、音频或设备标识。所有数据库操作在 [io] 上执行。
 */
class SqliteTranscriptionJobStore(
    context: Context,
    /** 数据库文件名；null 是内存数据库（只给测试用，关了就没了）。 */
    databaseName: String?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : JobStore {
    private val helper = object : SQLiteOpenHelper(context.applicationContext, databaseName, null, SCHEMA_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            SCHEMA.forEach(db::execSQL)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("没有从版本 $oldVersion 升级到 $newVersion 的迁移")
        }
    }

    /** 关闭数据库（测试里重开同一个文件时用）。 */
    fun close() = helper.close()

    override suspend fun put(job: TranscriptionJob) {
        withContext(io) {
            helper.writableDatabase.insertWithOnConflict("transcription_jobs", null, job.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    override suspend fun all(): List<TranscriptionJob> = withContext(io) {
        helper.readableDatabase.rawQuery("SELECT * FROM transcription_jobs ORDER BY seq", emptyArray())
            .use { cursor -> generateSequence { if (cursor.moveToNext()) cursor.toJob() else null }.toList() }
    }

    private companion object {
        const val SCHEMA_VERSION = 1

        val SCHEMA = listOf(
            """
            CREATE TABLE transcription_jobs (
                job_id TEXT NOT NULL PRIMARY KEY,
                record_id TEXT NOT NULL,
                kind TEXT NOT NULL,
                seq INTEGER NOT NULL,
                state TEXT NOT NULL,
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                attempts INTEGER NOT NULL,
                base_revisions INTEGER,
                wait_kind TEXT,
                wait_value TEXT,
                wait_checks INTEGER NOT NULL,
                needs_user INTEGER NOT NULL,
                retry_at_ms INTEGER,
                failure_code TEXT,
                result_revision INTEGER,
                model_id TEXT,
                model_version TEXT
            )
            """.trimIndent(),
            "CREATE INDEX transcription_jobs_seq ON transcription_jobs(seq)",
        )

        const val WAIT_MODEL = "MODEL"
        const val WAIT_RESOURCES = "RESOURCES"
    }

    private fun TranscriptionJob.toValues() = ContentValues().apply {
        put("job_id", jobId)
        put("record_id", recordId)
        put("kind", kind.name)
        put("seq", seq)
        put("state", state.name)
        put("created_at_ms", createdAt.toEpochMilli())
        put("updated_at_ms", updatedAt.toEpochMilli())
        put("attempts", attempts)
        val base = baseRevisions
        if (base == null) putNull("base_revisions") else put("base_revisions", base)
        when (val reason = wait) {
            null -> {
                putNull("wait_kind")
                putNull("wait_value")
            }
            is WaitReason.Model -> {
                put("wait_kind", WAIT_MODEL)
                put("wait_value", wire(reason.code))
            }
            is WaitReason.Resources -> {
                put("wait_kind", WAIT_RESOURCES)
                put("wait_value", reason.reason.name)
            }
        }
        put("wait_checks", waitChecks)
        put("needs_user", if (needsUser) 1 else 0)
        val retry = retryAt
        if (retry == null) putNull("retry_at_ms") else put("retry_at_ms", retry.toEpochMilli())
        val code = failure
        if (code == null) putNull("failure_code") else put("failure_code", wire(code))
        val revision = resultRevision
        if (revision == null) putNull("result_revision") else put("result_revision", revision)
        val model = modelId
        if (model == null) putNull("model_id") else put("model_id", model)
        val version = modelVersion
        if (version == null) putNull("model_version") else put("model_version", version)
    }

    private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column))

    private fun Cursor.stringOrNull(column: String): String? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }

    private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))

    private fun Cursor.longOrNull(column: String): Long? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }

    private fun Cursor.toJob(): TranscriptionJob {
        val wait = when (val kind = stringOrNull("wait_kind")) {
            null -> null
            WAIT_MODEL -> WaitReason.Model(unwire<ErrorCode>(string("wait_value")))
            WAIT_RESOURCES -> WaitReason.Resources(ResourceReason.valueOf(string("wait_value")))
            else -> error("未知的等待种类：$kind")
        }
        return TranscriptionJob(
            jobId = string("job_id"),
            recordId = string("record_id"),
            kind = JobKind.valueOf(string("kind")),
            seq = long("seq"),
            state = TranscriptionState.valueOf(string("state")),
            createdAt = Instant.ofEpochMilli(long("created_at_ms")),
            updatedAt = Instant.ofEpochMilli(long("updated_at_ms")),
            attempts = long("attempts").toInt(),
            baseRevisions = longOrNull("base_revisions")?.toInt(),
            wait = wait,
            waitChecks = long("wait_checks").toInt(),
            needsUser = long("needs_user") != 0L,
            retryAt = longOrNull("retry_at_ms")?.let { Instant.ofEpochMilli(it) },
            failure = stringOrNull("failure_code")?.let { unwire<ErrorCode>(it) },
            resultRevision = longOrNull("result_revision")?.toInt(),
            modelId = stringOrNull("model_id"),
            modelVersion = stringOrNull("model_version"),
        )
    }
}
