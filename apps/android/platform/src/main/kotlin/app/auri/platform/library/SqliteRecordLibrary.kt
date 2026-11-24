package app.auri.platform.library

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.auri.capture.adapter.PcmFormat
import app.auri.capture.adapter.SealedAudio
import app.auri.capture.contract.ContractJson
import app.auri.capture.contract.ErrorCode
import app.auri.capture.contract.TimeRange
import app.auri.capture.library.NoSuchRecordException
import app.auri.capture.library.RecordDeletedException
import app.auri.capture.library.RecordEntry
import app.auri.capture.library.RecordLibrary
import app.auri.capture.library.Revision
import app.auri.capture.library.RevisionDraft
import app.auri.capture.library.TranscriptionStatus
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.serializer

/**
 * Record 库的 SQLite 实现（Android 框架自带的 SQLite，不引入 Room：Room 需要 KSP，没有精确固定它就不加）。
 *
 * - 一个 Record 一行（`records`，主键就是与音频文件共用的 record_id），Revision 一条一行（`revisions`，主键 record_id + number）。
 * - Revision 不可变：触发器拒绝对 `revisions` 的任何 UPDATE 与 DELETE，所以已发布的文字不可能被改写。
 *   删除（墓碑，FR-TXT-005）是后续 Ticket 的事，那时要用迁移换掉 DELETE 触发器，不能绕过。
 * - 发布 Revision 是一个数据库事务：分配 number（事务里取最大值加一）、写 Revision、把 Record 标为 DONE，
 *   再执行 [publicationStep]。Ticket 21 的“待同步任务”就在这个步骤里写进同一个事务，“已发布”与“待同步”不会分家；
 *   步骤抛异常则整个事务回滚，什么都不留，number 也不会被占用。
 * - 枚举存合同里的取值（wire value），不存 Kotlin 常量名，改常量名不会让已有数据读不出来。
 * - 时间存为“秒 + 纳秒”两列，原样取回；排序用它们。
 * - 所有数据库操作在 [io] 上执行，不在调用方线程。
 * - 同一个数据库文件里可以带别的表（Ticket 21 的 sync_jobs）：[extraSchema] 在每次打开数据库时、在一个事务里按顺序幂等地执行
 *   （CREATE TABLE IF NOT EXISTS，以及给旧数据补行的回填），不改 [SCHEMA_VERSION]，所以不会和这里的迁移抢版本号；
 *   它们用 [withDatabase] 拿同一条连接，[changeSignal] 知道什么时候该重读。
 *
 * 文字（转录正文）在数据库里是数据，这里不记日志、不解释。
 */
class SqliteRecordLibrary(
    context: Context,
    /** 数据库文件名；null 是内存数据库（只给测试用，关了就没了）。 */
    databaseName: String?,
    private val publicationStep: (SQLiteDatabase, Revision) -> Unit = { _, _ -> },
    private val deletionStep: (SQLiteDatabase, String, Instant) -> Unit = { _, _, _ -> },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val extraSchema: List<String> = emptyList(),
) : RecordLibrary {
    private val helper = object : SQLiteOpenHelper(context.applicationContext, databaseName, null, SCHEMA_VERSION) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            SCHEMA.forEach(db::execSQL)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            var version = oldVersion
            if (version == 1 && newVersion >= 2) {
                db.execSQL("ALTER TABLE revisions ADD COLUMN publication_key TEXT")
                db.execSQL("CREATE UNIQUE INDEX revisions_publication_key ON revisions(publication_key)")
                version = 2
            }
            if (version == 2 && newVersion >= 3) {
                MIGRATION_2_TO_3.forEach(db::execSQL)
                version = 3
            }
            if (version != newVersion) {
                error("没有从版本 $oldVersion 升级到 $newVersion 的迁移")
            }
        }

        override fun onOpen(db: SQLiteDatabase) {
            if (db.isReadOnly || extraSchema.isEmpty()) return
            // 一个事务：别的表的建表与回填（评审 F5）要么一起生效、要么都不留；进程死在中间，下次打开从头再来
            db.beginTransaction()
            try {
                extraSchema.forEach(db::execSQL)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    private val changes = MutableStateFlow(0L)

    /** 每次提交（新增 Record、发布 Revision、改转录状态）之后加一：同库里别的表靠它知道该重读。 */
    val changeSignal: Flow<Long> get() = changes

    /** 在 [io] 上用这个库的连接做一件事（同库里别的表用）。事务由调用方自己开关。 */
    suspend fun <T> withDatabase(block: (SQLiteDatabase) -> T): T = withContext(io) { block(helper.writableDatabase) }

    /** 关闭数据库（测试里重开同一个文件时用）。 */
    fun close() = helper.close()

    /**
     * 把登记的音频位置里以 [fromPrefix] 开头的，改成以 [toPrefix] 开头（目录前缀，写法见 [audioDirPrefix]）：录音目录整个挪了家（评审 H1）。
     * 一条 UPDATE 语句，要么全改要么都不改；改过的行不再以 [fromPrefix] 开头，所以重复执行不会再改（幂等）。返回改了几行。
     */
    suspend fun relocateAudio(fromPrefix: String, toPrefix: String): Int = withContext(io) {
        val db = helper.writableDatabase
        val changed = db.compileStatement(RELOCATE_AUDIO_SQL).use { statement ->
            statement.bindString(1, toPrefix)
            statement.bindString(2, fromPrefix)
            statement.bindString(3, fromPrefix)
            statement.bindString(4, fromPrefix)
            statement.executeUpdateDelete()
        }
        if (changed > 0) changes.value += 1
        changed
    }

    override suspend fun addRecord(entry: RecordEntry): Boolean = withContext(io) {
        val db = helper.writableDatabase
        db.beginTransaction()
        val inserted = try {
            val isNew = !db.hasTombstone(entry.recordId) && !db.exists(entry.recordId)
            if (isNew) db.insertOrThrow("records", null, entry.toValues())
            db.setTransactionSuccessful()
            isNew
        } finally {
            db.endTransaction()
        }
        if (inserted) changes.value += 1
        inserted
    }

    override suspend fun publishRevision(draft: RevisionDraft): Revision = withContext(io) {
        val db = helper.writableDatabase
        db.beginTransaction()
        val revision = try {
            if (db.hasTombstone(draft.recordId)) throw RecordDeletedException(draft.recordId)
            if (!db.exists(draft.recordId)) throw NoSuchRecordException(draft.recordId)
            val existing = draft.publicationKey?.let { key ->
                db.rawQuery("SELECT * FROM revisions WHERE publication_key = ?", arrayOf(key)).use {
                    if (it.moveToFirst()) it.toRevision() else null
                }
            }
            if (existing != null) {
                require(existing.recordId == draft.recordId)
                db.setTransactionSuccessful()
                return@withContext existing
            }
            val next = db.rawQuery("SELECT COALESCE(MAX(number), 0) + 1 FROM revisions WHERE record_id = ?", arrayOf(draft.recordId))
                .use { it.moveToFirst(); it.getInt(0) }
            val published = draft.toRevision(next)
            db.insertOrThrow("revisions", null, published.toValues())
            db.update(
                "records",
                ContentValues().apply {
                    put("transcription", TranscriptionStatus.DONE.name)
                    putNull("failure_code")
                },
                "record_id = ?",
                arrayOf(draft.recordId),
            )
            publicationStep(db, published)
            db.setTransactionSuccessful()
            published
        } finally {
            db.endTransaction() // 没走到 setTransactionSuccessful 就是回滚
        }
        changes.value += 1
        revision
    }

    override suspend fun markTranscription(recordId: String, status: TranscriptionStatus, failureCode: ErrorCode?) {
        require(status != TranscriptionStatus.DONE) { "DONE 只能通过发布 Revision 得到" }
        withContext(io) {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                if (db.hasTombstone(recordId)) throw RecordDeletedException(recordId)
                val current = db.rawQuery("SELECT transcription FROM records WHERE record_id = ?", arrayOf(recordId))
                    .use { if (it.moveToFirst()) it.getString(0) else throw NoSuchRecordException(recordId) }
                check(current != TranscriptionStatus.DONE.name) { "已经有 Revision 的 Record 不能再改转录状态" }
                db.update(
                    "records",
                    ContentValues().apply {
                        put("transcription", status.name)
                        if (failureCode == null) putNull("failure_code") else put("failure_code", wire(failureCode))
                    },
                    "record_id = ?",
                    arrayOf(recordId),
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            changes.value += 1
        }
    }

    override suspend fun deleteRecord(recordId: String, deletedAt: Instant): Boolean = withContext(io) {
        val db = helper.writableDatabase
        db.beginTransaction()
        val newlyDeleted = try {
            if (db.hasTombstone(recordId)) {
                db.setTransactionSuccessful()
                false
            } else {
                db.insertOrThrow(
                    "record_tombstones",
                    null,
                    ContentValues().apply {
                        put("record_id", recordId)
                        put("deleted_at_s", deletedAt.epochSecond)
                        put("deleted_at_nano", deletedAt.nano)
                        put("gateway_synced", 0)
                    },
                )
                db.delete("revisions", "record_id = ?", arrayOf(recordId))
                db.delete("records", "record_id = ?", arrayOf(recordId))
                deletionStep(db, recordId, deletedAt)
                db.setTransactionSuccessful()
                true
            }
        } finally {
            db.endTransaction()
        }
        if (newlyDeleted) changes.value += 1
        newlyDeleted
    }

    override suspend fun isDeleted(recordId: String): Boolean = withContext(io) {
        helper.readableDatabase.hasTombstone(recordId)
    }

    override suspend fun deleteAudio(recordId: String): Boolean = withContext(io) {
        val db = helper.writableDatabase
        val changed = db.update(
            "records",
            ContentValues().apply {
                putNull("audio_location")
                putNull("audio_sha256")
                putNull("audio_size_bytes")
                putNull("audio_sample_count")
                putNull("audio_sample_rate_hz")
                putNull("audio_channels")
            },
            "record_id = ? AND audio_location IS NOT NULL",
            arrayOf(recordId),
        )
        if (changed > 0) changes.value += 1
        changed > 0
    }

    /** 尚未同步到网关的删除墓碑（record_id，按删除时间升序）。 */
    suspend fun pendingGatewayDeletions(): List<String> = withContext(io) {
        helper.readableDatabase.rawQuery(
            "SELECT record_id FROM record_tombstones WHERE gateway_synced = 0 ORDER BY deleted_at_s, deleted_at_nano, record_id",
            emptyArray(),
        ).use { cursor -> generateSequence { if (cursor.moveToNext()) cursor.getString(0) else null }.toList() }
    }

    /** 网关已确认删除该 Record（或已无需再发）：把墓碑标记为已同步。 */
    suspend fun markGatewayDeleted(recordId: String) = withContext(io) {
        val changed = helper.writableDatabase.update(
            "record_tombstones",
            ContentValues().apply { put("gateway_synced", 1) },
            "record_id = ? AND gateway_synced = 0",
            arrayOf(recordId),
        )
        if (changed > 0) changes.value += 1
    }

    override suspend fun get(recordId: String): RecordEntry? = withContext(io) {
        load("WHERE record_id = ?", arrayOf(recordId)).firstOrNull()
    }

    override fun observe(): Flow<List<RecordEntry>> = changes.map { withContext(io) { load("", emptyArray()) } }

    override suspend fun incomplete(): List<RecordEntry> = withContext(io) {
        load(
            "WHERE audio_location IS NOT NULL AND transcription IN (?, ?)",
            arrayOf(TranscriptionStatus.PENDING.name, TranscriptionStatus.WAITING_MODEL.name),
        )
    }

    /** 最新的在前；每个 Record 带上它按 number 递增的全部 Revision。 */
    private fun load(where: String, args: Array<String>): List<RecordEntry> {
        val db = helper.readableDatabase
        val records = db.rawQuery(
            "SELECT * FROM records $where ORDER BY started_at_s DESC, started_at_nano DESC, rowid DESC",
            args,
        ).use { cursor -> generateSequence { if (cursor.moveToNext()) cursor.toEntry() else null }.toList() }
        if (records.isEmpty()) return records
        val revisions = db.rawQuery("SELECT * FROM revisions ORDER BY record_id, number", emptyArray())
            .use { cursor -> generateSequence { if (cursor.moveToNext()) cursor.toRevision() else null }.toList() }
            .groupBy { it.recordId }
        return records.map { it.copy(revisions = revisions[it.recordId].orEmpty()) }
    }

    private fun SQLiteDatabase.exists(recordId: String) =
        rawQuery("SELECT 1 FROM records WHERE record_id = ?", arrayOf(recordId)).use { it.moveToFirst() }

    private fun SQLiteDatabase.hasTombstone(recordId: String) =
        rawQuery("SELECT 1 FROM record_tombstones WHERE record_id = ?", arrayOf(recordId)).use { it.moveToFirst() }

    companion object {
        private const val SCHEMA_VERSION = 3

        val MIGRATION_2_TO_3 = listOf(
            """
            CREATE TABLE IF NOT EXISTS record_tombstones (
                record_id TEXT NOT NULL PRIMARY KEY,
                deleted_at_s INTEGER NOT NULL,
                deleted_at_nano INTEGER NOT NULL,
                gateway_synced INTEGER NOT NULL CHECK (gateway_synced IN (0, 1)) DEFAULT 0
            )
            """.trimIndent(),
            "DROP TRIGGER IF EXISTS revisions_immutable_delete",
            """
            CREATE TRIGGER revisions_immutable_delete BEFORE DELETE ON revisions
            WHEN NOT EXISTS (SELECT 1 FROM record_tombstones t WHERE t.record_id = OLD.record_id)
            BEGIN SELECT RAISE(ABORT, 'revision 不可删除'); END
            """.trimIndent(),
        )

        /**
         * 改音频位置的目录前缀（参数依次：新前缀、旧前缀、旧前缀、旧前缀）。用 substr 比较而不是 LIKE：路径里的 % 与 _ 在 LIKE 里是通配符。
         * 公开只为让 JVM 测试在真实的 SQLite 引擎上验证这条语句；运行时只有 [relocateAudio] 用它。
         */
        const val RELOCATE_AUDIO_SQL =
            "UPDATE records SET audio_location = ? || substr(audio_location, length(?) + 1) " +
                "WHERE audio_location IS NOT NULL AND substr(audio_location, 1, length(?)) = ?"

        /** 建库语句（当前结构）。公开只为让别的模块的迁移测试在 JVM 上用真实结构造出一个库；运行时只有 [onCreate] 用它。 */
        val SCHEMA = listOf(
            """
            CREATE TABLE records (
                record_id TEXT NOT NULL PRIMARY KEY,
                capture_session_id TEXT NOT NULL,
                started_at_s INTEGER NOT NULL,
                started_at_nano INTEGER NOT NULL,
                status TEXT NOT NULL,
                end_reason TEXT NOT NULL,
                capture_duration_ms INTEGER NOT NULL,
                speech_duration_ms INTEGER NOT NULL,
                speech_segments TEXT NOT NULL,
                audio_location TEXT,
                audio_sha256 TEXT,
                audio_size_bytes INTEGER,
                audio_sample_count INTEGER,
                audio_sample_rate_hz INTEGER,
                audio_channels INTEGER,
                route_evidence_id TEXT NOT NULL,
                input_route_type TEXT NOT NULL,
                headset_display_name TEXT,
                transcription TEXT NOT NULL,
                failure_code TEXT
            )
            """.trimIndent(),
            """
            CREATE TABLE revisions (
                record_id TEXT NOT NULL REFERENCES records(record_id),
                number INTEGER NOT NULL CHECK (number >= 1),
                text TEXT NOT NULL,
                text_sha256 TEXT NOT NULL,
                source TEXT NOT NULL,
                language TEXT NOT NULL,
                review_state TEXT NOT NULL,
                execution_location TEXT NOT NULL,
                engine TEXT,
                model_id TEXT,
                model_version TEXT,
                confidence REAL,
                created_at_s INTEGER NOT NULL,
                created_at_nano INTEGER NOT NULL,
                publication_key TEXT,
                PRIMARY KEY (record_id, number)
            )
            """.trimIndent(),
            "CREATE UNIQUE INDEX revisions_publication_key ON revisions(publication_key)",
            """
            CREATE TABLE record_tombstones (
                record_id TEXT NOT NULL PRIMARY KEY,
                deleted_at_s INTEGER NOT NULL,
                deleted_at_nano INTEGER NOT NULL,
                gateway_synced INTEGER NOT NULL CHECK (gateway_synced IN (0, 1)) DEFAULT 0
            )
            """.trimIndent(),
            "CREATE TRIGGER revisions_immutable_update BEFORE UPDATE ON revisions BEGIN SELECT RAISE(ABORT, 'revision 不可改写'); END",
            """
            CREATE TRIGGER revisions_immutable_delete BEFORE DELETE ON revisions
            WHEN NOT EXISTS (SELECT 1 FROM record_tombstones t WHERE t.record_id = OLD.record_id)
            BEGIN SELECT RAISE(ABORT, 'revision 不可删除'); END
            """.trimIndent(),
        )
    }
}

internal inline fun <reified E : Enum<E>> wire(value: E): String = ContractJson.json.encodeToString(serializer<E>(), value).trim('"')

internal inline fun <reified E : Enum<E>> unwire(text: String): E = ContractJson.json.decodeFromString(serializer<E>(), "\"$text\"")

/** 语音段存成“起-止;起-止”的文本：段数很少，不值得再开一张表。 */
internal fun encodeSegments(segments: List<TimeRange>): String = segments.joinToString(";") { "${it.startMs}-${it.endMs}" }

internal fun decodeSegments(text: String): List<TimeRange> =
    if (text.isEmpty()) emptyList() else text.split(';').map { part -> part.split('-').let { TimeRange(it[0].toLong(), it[1].toLong()) } }

private fun RecordEntry.toValues() = ContentValues().apply {
    put("record_id", recordId)
    put("capture_session_id", captureSessionId)
    put("started_at_s", startedAt.epochSecond)
    put("started_at_nano", startedAt.nano)
    put("status", wire(status))
    put("end_reason", wire(endReason))
    put("capture_duration_ms", captureDurationMs)
    put("speech_duration_ms", speechDurationMs)
    put("speech_segments", encodeSegments(speechSegments))
    val sealed = audio
    if (sealed == null) {
        putNull("audio_location")
        putNull("audio_sha256")
        putNull("audio_size_bytes")
        putNull("audio_sample_count")
        putNull("audio_sample_rate_hz")
        putNull("audio_channels")
    } else {
        put("audio_location", sealed.location)
        put("audio_sha256", sealed.sha256)
        put("audio_size_bytes", sealed.sizeBytes)
        put("audio_sample_count", sealed.sampleCount)
        put("audio_sample_rate_hz", sealed.format.sampleRateHz)
        put("audio_channels", sealed.format.channels)
    }
    put("route_evidence_id", routeEvidenceId)
    put("input_route_type", wire(inputRouteType))
    if (headsetDisplayName == null) putNull("headset_display_name") else put("headset_display_name", headsetDisplayName)
    put("transcription", transcription.name)
    val failure = failureCode
    if (failure == null) putNull("failure_code") else put("failure_code", wire(failure))
}

private fun Revision.toValues() = ContentValues().apply {
    put("record_id", recordId)
    put("number", number)
    put("text", text)
    put("text_sha256", textSha256)
    put("source", wire(source))
    put("language", language)
    put("review_state", wire(reviewState))
    put("execution_location", wire(executionLocation))
    if (engine == null) putNull("engine") else put("engine", engine)
    if (modelId == null) putNull("model_id") else put("model_id", modelId)
    if (modelVersion == null) putNull("model_version") else put("model_version", modelVersion)
    if (confidence == null) putNull("confidence") else put("confidence", confidence)
    put("created_at_s", createdAt.epochSecond)
    put("created_at_nano", createdAt.nano)
    if (publicationKey == null) putNull("publication_key") else put("publication_key", publicationKey)
}

private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column))

private fun Cursor.stringOrNull(column: String): String? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }

private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))

private fun Cursor.toEntry(): RecordEntry {
    val audio = stringOrNull("audio_location")?.let {
        SealedAudio(
            location = it,
            sha256 = string("audio_sha256"),
            sizeBytes = long("audio_size_bytes"),
            sampleCount = long("audio_sample_count"),
            format = PcmFormat(long("audio_sample_rate_hz").toInt(), long("audio_channels").toInt()),
        )
    }
    return RecordEntry(
        recordId = string("record_id"),
        captureSessionId = string("capture_session_id"),
        startedAt = Instant.ofEpochSecond(long("started_at_s"), long("started_at_nano")),
        status = unwire(string("status")),
        endReason = unwire(string("end_reason")),
        captureDurationMs = long("capture_duration_ms"),
        speechDurationMs = long("speech_duration_ms"),
        speechSegments = decodeSegments(string("speech_segments")),
        audio = audio,
        routeEvidenceId = string("route_evidence_id"),
        inputRouteType = unwire(string("input_route_type")),
        headsetDisplayName = stringOrNull("headset_display_name"),
        transcription = TranscriptionStatus.valueOf(string("transcription")),
        failureCode = stringOrNull("failure_code")?.let { unwire<ErrorCode>(it) },
    )
}

private fun Cursor.toRevision(): Revision {
    val confidenceIndex = getColumnIndexOrThrow("confidence")
    return Revision(
        recordId = string("record_id"),
        number = long("number").toInt(),
        text = string("text"),
        textSha256 = string("text_sha256"),
        source = unwire(string("source")),
        language = string("language"),
        reviewState = unwire(string("review_state")),
        executionLocation = unwire(string("execution_location")),
        engine = stringOrNull("engine"),
        modelId = stringOrNull("model_id"),
        modelVersion = stringOrNull("model_version"),
        confidence = if (isNull(confidenceIndex)) null else getDouble(confidenceIndex),
        createdAt = Instant.ofEpochSecond(long("created_at_s"), long("created_at_nano")),
        publicationKey = stringOrNull("publication_key"),
    )
}
