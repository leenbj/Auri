package app.auri.capture.recovery

import app.auri.capture.adapter.OrphanRecordingSource
import app.auri.capture.adapter.RecoveredRecording
import app.auri.capture.contract.AudioChunk
import app.auri.capture.contract.CaptureStatus
import app.auri.capture.contract.ContractRules
import app.auri.capture.contract.DiscardReason
import app.auri.capture.contract.DiscardedRange
import app.auri.capture.contract.EndReason
import app.auri.capture.contract.RecordingResult
import app.auri.capture.contract.TimeRange
import app.auri.capture.coordinator.CaptureOutput
import app.auri.capture.coordinator.SealedRecording
import app.auri.capture.library.RecordLibrary
import java.util.UUID

// 崩溃恢复（Ticket 19 第 3、4 项）：进程被杀或封存没能完成之后，下次启动把磁盘上验证通过的部分整理成 Record，
// 和任何别的 Record 一样交给 Record 管线登记并转录。
//
// 恢复**只读磁盘、只登记 Record**：不开麦、不起前台服务、不碰采集状态机（它在 DISARMED），也不自动开始任何录音。

/**
 * 从磁盘上找回的一次录音重建成可以登记的 [SealedRecording]。
 *
 * 恢复出来的录音一律是 `partial` + `process_terminated`：不知道它本来是怎么结束的（用户停止？时长上限？），
 * 也不知道进程是在什么时候死的，所以只承认验证过的那部分：
 *  - `captureDurationMs` 是**下限**：最后一段验证过的语音的结束时刻。被杀之后到下次启动之间没有任何信息；
 *  - `speechDurationMs`、`verifiedRanges`、`chunks` 只来自验证通过的分段；它们之间的空隙记成 `silence`（当初没有保留）；
 *  - 最后一次提交之后写下的、没法验证的字节已经被存储截掉，不出现在任何地方。
 */
fun RecoveredRecording.toSealedRecording(): SealedRecording {
    val rate = format.sampleRateHz.toLong() * format.channels
    val ranges = mutableListOf<TimeRange>()
    for (run in runs) {
        val start = maxOf(run.startSample * 1000 / rate, ranges.lastOrNull()?.endMs ?: 0L) // 取整之后也不能和前一段重叠（SR-02）
        ranges += TimeRange(start, (run.endSample * 1000 + rate - 1) / rate)
    }
    val chunks = runs.mapIndexed { index, run ->
        AudioChunk(stableChunkId(recordId, index), index.toLong(), run.sha256, evidence.epoch, ranges[index].startMs, ranges[index].endMs)
    }
    var cursor = 0L
    val discarded = buildList {
        for (range in ranges) {
            if (cursor < range.startMs) add(DiscardedRange(cursor, range.startMs, DiscardReason.SILENCE))
            cursor = range.endMs
        }
    }
    val result = RecordingResult(
        recordId = recordId,
        captureSessionId = captureSessionId,
        status = CaptureStatus.PARTIAL,
        endReason = EndReason.PROCESS_TERMINATED,
        captureDurationMs = ranges.lastOrNull()?.endMs ?: 0L,
        speechDurationMs = ranges.sumOf { it.endMs - it.startMs },
        chunks = chunks,
        verifiedRanges = ranges,
        discardedRanges = discarded,
    )
    check(ContractRules.violations(result).isEmpty()) { "恢复出来的结果违反了合同语义规则" }
    return SealedRecording(result, evidence, routeEvidenceId, startedAt, audio)
}

/** 同一个录音每次恢复得到同一批 chunk 标识：恢复可以重复，结果不能因为重复而不同。 */
private fun stableChunkId(recordId: String, index: Int): String =
    UUID.nameUUIDFromBytes("auri/recovered-chunk/$recordId/$index".toByteArray(Charsets.UTF_8)).toString()

/** 一次恢复的结果，给界面一句话用：没有任何正文、路径或标识。 */
data class RecoveryReport(
    /** 重建并交给管线登记的录音数（含没有音频的）。 */
    val recovered: Int,
    /** 其中没有任何验证通过的音频、只登记了一个空 Record 的。 */
    val withoutAudio: Int,
    /** 恢复不了、原样留在磁盘上的录音数。 */
    val failed: Int,
)

/**
 * 启动时的恢复：问存储有哪些没交付过的录音，把能验证的部分交给 [output]（Record 管线）。
 *
 * 可重复运行、中途被杀后再运行结果不变：哪些已经登记由 [library] 说了算；管线对同一个录音的重复交付只登记一次。
 */
class OrphanRecovery(
    private val source: OrphanRecordingSource,
    private val library: RecordLibrary,
    private val output: CaptureOutput,
) {
    suspend fun run(): RecoveryReport {
        val scan = source.recoverOrphans { recordId -> library.get(recordId) != null }
        var withoutAudio = 0
        for (recovered in scan.recovered) {
            val sealed = recovered.toSealedRecording()
            if (sealed.audio == null) withoutAudio += 1
            output.recordingSealed(sealed)
        }
        return RecoveryReport(scan.recovered.size, withoutAudio, scan.failures.size)
    }
}
