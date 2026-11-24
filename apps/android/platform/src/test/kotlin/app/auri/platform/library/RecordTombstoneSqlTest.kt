package app.auri.platform.library

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * FR-TXT-002 / FR-TXT-005：
 * - Revision 不可变：任何 UPDATE 都必须被触发器拒绝；没有墓碑的 DELETE 也必须被触发器拒绝（不允许只删单个 Revision）。
 * - 删除整条 Record（写下墓碑后）允许在事务里删掉该 Record 的 revisions 与 records 行。
 * - 从 v2 迁移到 v3 保留已有数据并装上墓碑表与新的删除触发器。
 */
class RecordTombstoneSqlTest {
    private val db: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @Before
    fun setup() {
        db.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        SqliteRecordLibrary.SCHEMA.forEach(::exec)
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun exec(sql: String) {
        db.createStatement().use { it.execute(sql) }
    }

    private fun seed(recordId: String) {
        exec(
            """
            INSERT INTO records (record_id, capture_session_id, started_at_s, started_at_nano, status, end_reason,
                capture_duration_ms, speech_duration_ms, speech_segments, route_evidence_id, input_route_type, transcription)
            VALUES ('$recordId', 'sess', 100, 0, 'complete', 'screen_control', 5000, 4000, '0-4000', 'route', 'bluetooth_hfp', 'DONE')
            """.trimIndent(),
        )
        exec(
            """
            INSERT INTO revisions (record_id, number, text, text_sha256, source, language, review_state, execution_location,
                created_at_s, created_at_nano)
            VALUES ('$recordId', 1, '原文', '${"00".repeat(32)}', 'asr', 'zh', 'not_reviewed', 'on_device', 101, 0)
            """.trimIndent(),
        )
    }

    @Test
    fun `没有墓碑时无法删除单个 Revision，写下墓碑后可删除整条 Record`() {
        val id = "00000000-0000-4000-8000-000000000001"
        seed(id)

        try {
            exec("UPDATE revisions SET text = '篡改' WHERE record_id = '$id'")
            fail("UPDATE 必须被触发器拒绝")
        } catch (_: SQLException) {
            // 预期
        }

        try {
            exec("DELETE FROM revisions WHERE record_id = '$id'")
            fail("没有墓碑时 DELETE 必须被触发器拒绝")
        } catch (_: SQLException) {
            // 预期
        }

        exec("INSERT INTO record_tombstones (record_id, deleted_at_s, deleted_at_nano, gateway_synced) VALUES ('$id', 200, 0, 0)")
        exec("DELETE FROM revisions WHERE record_id = '$id'")
        exec("DELETE FROM records WHERE record_id = '$id'")

        db.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM records WHERE record_id = '$id'").use { it.next(); assertEquals(0, it.getInt(1)) }
            st.executeQuery("SELECT COUNT(*) FROM revisions WHERE record_id = '$id'").use { it.next(); assertEquals(0, it.getInt(1)) }
            st.executeQuery("SELECT gateway_synced FROM record_tombstones WHERE record_id = '$id'").use {
                assertTrue(it.next())
                assertEquals(0, it.getInt(1))
            }
        }
    }

    @Test
    fun `v2 到 v3 迁移语句可把旧库升级并支持墓碑删除`() {
        val v2 = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            v2.createStatement().use { st ->
                st.execute("PRAGMA foreign_keys = ON")
                // 模拟 v2 结构（无 record_tombstones，旧的 unconditional delete trigger）
                SqliteRecordLibrary.SCHEMA
                    .filterNot { it.contains("record_tombstones") }
                    .forEach { st.execute(it) }
                st.execute("CREATE TRIGGER revisions_immutable_delete BEFORE DELETE ON revisions BEGIN SELECT RAISE(ABORT, 'revision 不可删除'); END")
                st.execute(
                    """
                    INSERT INTO records (record_id, capture_session_id, started_at_s, started_at_nano, status, end_reason,
                        capture_duration_ms, speech_duration_ms, speech_segments, route_evidence_id, input_route_type, transcription)
                    VALUES ('rec-1', 'sess', 100, 0, 'complete', 'screen_control', 5000, 4000, '0-4000', 'route', 'bluetooth_hfp', 'DONE')
                    """.trimIndent(),
                )
                st.execute(
                    """
                    INSERT INTO revisions (record_id, number, text, text_sha256, source, language, review_state, execution_location,
                        created_at_s, created_at_nano)
                    VALUES ('rec-1', 1, '旧数据', '${"00".repeat(32)}', 'asr', 'zh', 'not_reviewed', 'on_device', 101, 0)
                    """.trimIndent(),
                )
                SqliteRecordLibrary.MIGRATION_2_TO_3.forEach { st.execute(it) }
                st.execute("INSERT INTO record_tombstones (record_id, deleted_at_s, deleted_at_nano, gateway_synced) VALUES ('rec-1', 300, 0, 0)")
                st.execute("DELETE FROM revisions WHERE record_id = 'rec-1'")
                st.execute("DELETE FROM records WHERE record_id = 'rec-1'")
            }
        } finally {
            v2.close()
        }
    }
}
