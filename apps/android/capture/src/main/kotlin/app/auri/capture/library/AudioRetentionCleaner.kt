package app.auri.capture.library

import app.auri.capture.contract.RevisionSource
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.first

/** 一次音频保留期清理的结果：只有计数，不含正文、路径或标识。 */
data class AudioRetentionReport(val purgedCount: Int, val failedCount: Int)

/**
 * 音频保留期自动清理（FR-TXT-005）：
 * - 默认转录成功后 30 天（[DEFAULT_RETENTION_DAYS]）。
 * - 只清理已成功转录（[TranscriptionStatus.DONE]）且仍有音频的 Record；未转录或转录失败的保留音频。
 * - 起算时刻为首次 ASR 转录成功时间（若无 ASR 版本则取首版时间），用户后续改字不会推迟旧音频清理。
 * - 只删除音频文件并清空库里的 audio 字段，完整保留 Record 与全部 Revision，不产生删除墓碑。
 */
class AudioRetentionCleaner(
    private val library: RecordLibrary,
    private val deleteFile: (location: String) -> Boolean,
) {
    suspend fun purgeExpired(now: Instant, retentionDays: Int = DEFAULT_RETENTION_DAYS): AudioRetentionReport {
        require(retentionDays in MIN_RETENTION_DAYS..MAX_RETENTION_DAYS) {
            "音频保留期必须在 $MIN_RETENTION_DAYS..$MAX_RETENTION_DAYS 天之间"
        }
        val records = library.observe().first()
        var purged = 0
        var failed = 0
        val retention = Duration.ofDays(retentionDays.toLong())
        for (entry in records) {
            val audio = entry.audio ?: continue
            if (entry.transcription != TranscriptionStatus.DONE) continue
            val transcribedAt = entry.revisions.firstOrNull { it.source == RevisionSource.ASR }?.createdAt
                ?: entry.revisions.firstOrNull()?.createdAt
                ?: continue
            if (now.isBefore(transcribedAt.plus(retention))) continue
            val fileRemoved = try {
                deleteFile(audio.location)
            } catch (_: Exception) {
                false
            }
            if (fileRemoved) {
                val cleared = try {
                    library.deleteAudio(entry.recordId)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                if (cleared) purged += 1 else failed += 1
            } else {
                failed += 1
            }
        }
        return AudioRetentionReport(purgedCount = purged, failedCount = failed)
    }

    companion object {
        const val DEFAULT_RETENTION_DAYS = 30
        const val MIN_RETENTION_DAYS = 1
        const val MAX_RETENTION_DAYS = 365
    }
}
