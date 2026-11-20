package app.auri.platform.library

import app.auri.capture.library.RecordEntry
import java.sql.Connection
import java.sql.DriverManager

/**
 * 真 SQLite 引擎（sqlite-jdbc，仅测试类路径）里的 `records` 表：建表语句就是 [SqliteRecordLibrary.SCHEMA]，
 * 改音频位置用的就是 [SqliteRecordLibrary.RELOCATE_AUDIO_SQL]。库的结构或那条语句一变，用它的测试立刻看得到。
 * 限制：Android 自带的 SQLite 版本比 sqlite-jdbc 里的旧，这里证明的是 SQL 本身；`relocateAudio` 里绑定参数与执行的那几行
 * 在设备上由 `SqliteRecordLibraryTest` 验证（本机没有设备，NOT_RUN，见验收 README）。
 */
class RecordRows : AutoCloseable {
    private val db: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    init {
        SqliteRecordLibrary.SCHEMA.forEach { sql -> db.createStatement().use { it.execute(sql) } }
    }

    /** 登记一个 Record 的一行（只填必填列与音频列，别的列取固定值）。 */
    fun insert(entry: RecordEntry) {
        val audio = entry.audio
        db.prepareStatement(
            """
            INSERT INTO records (record_id, capture_session_id, started_at_s, started_at_nano, status, end_reason,
                capture_duration_ms, speech_duration_ms, speech_segments, audio_location, audio_sha256, audio_size_bytes,
                audio_sample_count, audio_sample_rate_hz, audio_channels, route_evidence_id, input_route_type, transcription)
            VALUES (?, 'session', 1, 0, 'complete', 'screen_control', 5000, 4000, '0-4000', ?, ?, ?, ?, ?, ?, 'route', 'bluetooth_hfp', ?)
            """.trimIndent(),
        ).use {
            it.setString(1, entry.recordId)
            it.setString(2, audio?.location)
            it.setString(3, audio?.sha256)
            if (audio == null) it.setNull(4, java.sql.Types.INTEGER) else it.setLong(4, audio.sizeBytes)
            if (audio == null) it.setNull(5, java.sql.Types.INTEGER) else it.setLong(5, audio.sampleCount)
            if (audio == null) it.setNull(6, java.sql.Types.INTEGER) else it.setInt(6, audio.format.sampleRateHz)
            if (audio == null) it.setNull(7, java.sql.Types.INTEGER) else it.setInt(7, audio.format.channels)
            it.setString(8, entry.transcription.name)
            it.executeUpdate()
        }
    }

    /** 只有标识与音频位置的一行（SQL 层测试用；[location] 为 null 是没有音频的 Record）。 */
    fun insertLocation(recordId: String, location: String?) {
        db.prepareStatement(
            """
            INSERT INTO records (record_id, capture_session_id, started_at_s, started_at_nano, status, end_reason,
                capture_duration_ms, speech_duration_ms, speech_segments, audio_location, route_evidence_id, input_route_type, transcription)
            VALUES (?, 'session', 1, 0, 'complete', 'screen_control', 5000, 4000, '0-4000', ?, 'route', 'bluetooth_hfp', 'PENDING')
            """.trimIndent(),
        ).use {
            it.setString(1, recordId)
            it.setString(2, location)
            it.executeUpdate()
        }
    }

    fun locationOf(recordId: String): String? =
        db.prepareStatement("SELECT audio_location FROM records WHERE record_id = ?").use {
            it.setString(1, recordId)
            it.executeQuery().use { rs -> check(rs.next()) { "没有这一行：$recordId" }; rs.getString(1) }
        }

    /** 与 [SqliteRecordLibrary.relocateAudio] 一样的语句、一样的参数顺序。返回改了几行。 */
    fun relocate(fromPrefix: String, toPrefix: String): Int =
        db.prepareStatement(SqliteRecordLibrary.RELOCATE_AUDIO_SQL).use {
            it.setString(1, toPrefix)
            it.setString(2, fromPrefix)
            it.setString(3, fromPrefix)
            it.setString(4, fromPrefix)
            it.executeUpdate()
        }

    override fun close() = db.close()
}
