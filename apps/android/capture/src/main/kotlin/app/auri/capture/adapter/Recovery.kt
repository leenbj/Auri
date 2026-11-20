package app.auri.capture.adapter

import app.auri.capture.contract.RouteChangeEvent
import java.time.Instant

// 崩溃恢复的数据（Ticket 19）。存储（:platform）负责读盘、逐段验哈希、把不可信的尾巴截掉；
// 这里只描述“找回了什么”，从它重建 Record 的规则在 app.auri.capture.recovery（纯 Kotlin，三端照同一套规则实现）。

/** 一段被验证过的、保留下来的语音：原始时间线上的 [startSample, endSample)（交错采样数），[sha256] 是这段 16 位小端 PCM 字节的哈希。 */
data class RecoveredRun(val startSample: Long, val endSample: Long, val sha256: String) {
    init {
        require(startSample >= 0 && endSample > startSample) { "一段保留的语音必须有长度" }
        require(sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' }) { "sha256 必须是小写十六进制的 SHA-256" }
    }
}

/**
 * 上次进程没来得及交付的一次录音，只含验证通过的部分。[audio] 是已经整理好的 WAV（尾部不可信的字节已截掉，头部已修好）；
 * 一个验证通过的分段也没有时为 null。[runs] 与 [audio] 的内容严格对应：按时间线顺序排好，字节数加起来等于 [audio] 的 PCM 长度。
 */
class RecoveredRecording(
    val recordId: String,
    val captureSessionId: String,
    val startedAt: Instant,
    val evidence: RouteChangeEvent,
    val routeEvidenceId: String,
    val format: PcmFormat,
    val runs: List<RecoveredRun>,
    val audio: SealedAudio?,
    /** 没能验证、因而被截掉的字节数；只用于诊断，不进任何合同字段。 */
    val droppedBytes: Long,
) {
    init {
        require(runs.zipWithNext().all { (earlier, later) -> earlier.endSample <= later.startSample }) { "分段必须按时间线升序且不重叠" }
        require((audio?.sampleCount ?: 0L) == runs.sumOf { it.endSample - it.startSample }) { "分段的采样数必须与整理好的音频严格对应" }
        require(droppedBytes >= 0) { "droppedBytes 不能为负" }
    }
}

/** 一个录音恢复不了：[recordId] 读不出来时为 null，[reason] 只写原因类别，不含路径、设备标识或内容。录音留在磁盘上，不删。 */
data class OrphanFailure(val recordId: String?, val reason: String)

class OrphanScan(
    val recovered: List<RecoveredRecording>,
    val failures: List<OrphanFailure>,
)
