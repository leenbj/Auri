package app.auri.platform.storage

import app.auri.capture.adapter.OrphanFailure
import app.auri.capture.adapter.OrphanRecordingSource
import app.auri.capture.adapter.OrphanScan
import app.auri.capture.adapter.PcmFormat
import app.auri.capture.adapter.RecordingMeta
import app.auri.capture.adapter.RecordingStore
import app.auri.capture.adapter.RecordingWriter
import app.auri.capture.adapter.RecoveredRecording
import app.auri.capture.adapter.RecoveredRun
import app.auri.capture.adapter.SealedAudio
import app.auri.capture.contract.CaptureHandle
import app.auri.capture.contract.GateState
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

/**
 * 录音存储：每个 Record 一个 16 位 PCM 的 WAV 文件，放在应用私有目录里（调用方传 `filesDir/records`），文件名就是 record_id。
 * 只有路由确认之后协调器才会调用 [begin]，所以这个目录里不会出现任何未经确认的音频。
 *
 * **写入中**的是 `<record_id>.wav.part`（头部先占位）与 `<record_id>.journal`（见 [RecordingJournal]）。[RecordingWriter.seal]
 * 做完头部修补与落盘才把 `.part` 改名成 `<record_id>.wav`：进程在录音中途被杀，留下的只会是 `.part`，不会有看上去已经完成、
 * 实际却是半截的录音。`sha256` 是 PCM 采样字节（不含 WAV 头）的 SHA-256，边写边算，封存时不用再读一遍文件。
 *
 * **分段提交（Ticket 19）**：音频按“分段”落盘。写满一个提交间隔（默认 1 秒的语音）或遇到时间线上的空隙，就先 fsync 数据，
 * 再追加一行日志（这一段的位置、累计字节数、SHA-256）并 fsync。所以进程被杀之后，日志里有的分段数据一定在，没有写进日志的尾巴
 * （至多一个提交间隔加一次写入）不可信。[recoverOrphans] 在下次启动时逐段验证、把不可信的尾巴截掉、修好头部、改名，
 * 交给 Record 管线像别的录音一样登记。封存之后日志仍然保留，直到下次启动发现这个 Record 已经登记进库才删。
 *
 * 一次 [RecordingWriter.append] 要么整批写下，要么什么都不留：写不下时把写了一半的字节截掉再抛 [IOException]，
 * 协调器的计数与磁盘上的内容永远一致。封存只在最后一段的日志行落盘之后才完成（头部原地改写与改名本身不要新空间，
 * 但那一行日志要）：写满到连一行日志都写不下时 [RecordingWriter.seal] 抛 [IOException]，不留 `.wav`，已提交的分段下次启动找回。
 */
class WavRecordingStore internal constructor(
    private val directory: File,
    private val io: StorageIo,
    private val commitIntervalMs: Long,
) : RecordingStore, OrphanRecordingSource {
    constructor(directory: File) : this(directory, StorageIo.Real, COMMIT_INTERVAL_MS)

    /** 正在被本进程写的录音；恢复扫描绝不碰它们。 */
    private val writing = HashSet<String>()

    /** 本进程已经封存、交给协调器去交付的录音；日志要留到下次启动才清理，这个进程里的扫描不能把它们当孤儿。 */
    private val handedOver = HashSet<String>()

    override fun begin(recordId: String, handle: CaptureHandle, format: PcmFormat, meta: RecordingMeta): RecordingWriter {
        // record_id 直接当文件名：只接受合同里的 uuid，挡住 "../x" 之类的路径
        require(UUID_PATTERN.matches(recordId)) { "record_id 必须是小写 UUID" }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建录音目录")
        val part = File(directory, "$recordId.wav.part")
        val sealed = File(directory, "$recordId.wav")
        val journalFile = File(directory, "$recordId.journal")
        if (sealed.exists() || part.exists() || journalFile.exists()) throw IOException("这个 Record 的音频文件已经存在")
        synchronized(writing) { writing += recordId }
        var journalHandle: RandomAccessFile? = null
        var data: RandomAccessFile? = null
        try {
            // 日志先于音频：begin 这一行落盘之后才创建音频文件，所以任何音频都找得到它的来历
            journalHandle = io.open(journalFile)
            val journal = JournalWriter(journalHandle, journalFile.name, io)
            journal.append(RecordingJournal.beginLine(RecordingJournal.Begin(recordId, handle.captureSessionId, meta.startedAt, meta.routeEvidenceId, meta.evidence, format)))
            data = io.open(part)
            data.write(ByteArray(HEADER_BYTES.toInt())) // 头部先占位，封存时按实际长度修补
            syncDirectoryQuietly()
            return Writer(recordId, part, sealed, handle, format, data, journal, commitSamples(format))
        } catch (e: Exception) {
            // 开不了：不留任何残渣（begin 的日志也不留：这次录音不存在）
            closeQuietly(data)
            closeQuietly(journalHandle)
            part.delete()
            journalFile.delete()
            synchronized(writing) { writing -= recordId }
            throw e
        }
    }

    private fun commitSamples(format: PcmFormat) = commitIntervalMs * format.sampleRateHz * format.channels / 1000

    private fun syncDirectoryQuietly() {
        try {
            io.syncDirectory(directory)
        } catch (e: IOException) {
            // 目录项没能落盘：不影响这次写入，只是掉电之后可能找不到这条录音
        }
    }

    private inner class Writer(
        private val recordId: String,
        private val part: File,
        private val target: File,
        private val handle: CaptureHandle,
        private val format: PcmFormat,
        private val data: RandomAccessFile,
        private val journal: JournalWriter,
        private val commitSamples: Long,
    ) : RecordingWriter {
        private val whole = MessageDigest.getInstance("SHA-256")
        private var segment = MessageDigest.getInstance("SHA-256")
        private var dataBytes = 0L
        private var segmentStart = 0L
        private var segmentEnd = 0L
        private var segmentSamples = 0L

        /** 这一段已经算好、日志行还没写进去的哈希：提交失败重试时沿用，不依赖摘要对象可复制。 */
        private var pendingSha: String? = null
        private var finished = false

        @Synchronized
        override fun append(handle: CaptureHandle, startSample: Long, samples: ShortArray) {
            check(!finished) { "已经封存或放弃，不能再写" }
            check(handle.gateState == GateState.OPEN) { "闸门已关，不接受写入" }
            check(handle.captureSessionId == this.handle.captureSessionId && handle.generation == this.handle.generation && handle.verifiedRouteEpoch == this.handle.verifiedRouteEpoch) {
                "写入句柄不属于这次录音"
            }
            require(startSample >= maxOf(segmentEnd, 0L)) { "写入的位置不能倒退" }
            if (samples.isEmpty()) return
            // 在写新数据之前先收尾上一段：遇到空隙，或这一段已经够长；上一次提交失败过（哈希已算好）也先补完，
            // 免得新数据落进一个哈希已经定下的分段。提交失败就在这里抛出，新数据一个字节都没写，计数不会错
            if (segmentSamples > 0 && (pendingSha != null || startSample != segmentEnd || segmentSamples >= commitSamples)) commit()
            val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach { buffer.putShort(it) }
            val bytes = buffer.array()
            val position = HEADER_BYTES + dataBytes
            try {
                data.seek(position)
                data.write(bytes)
            } catch (e: IOException) {
                try {
                    data.setLength(position) // 写了一半的字节截掉：要么整批写下，要么什么都不留
                } catch (cleanup: IOException) {
                    // 截不掉也无妨：封存时会按实际长度再截一次
                }
                throw e
            }
            if (segmentSamples == 0L) {
                segmentStart = startSample
                segment.reset()
            }
            segment.update(bytes)
            whole.update(bytes)
            dataBytes += bytes.size
            segmentSamples += samples.size
            segmentEnd = startSample + samples.size
        }

        /** 提交当前分段：先 fsync 数据，再追加日志行并 fsync。日志里有的分段，数据一定已经在磁盘上。 */
        private fun commit() {
            if (segmentSamples == 0L) return
            io.sync(data, part.name)
            // digest() 会把摘要复位；日志行写失败时数据没有再增长，重试用同一个哈希
            val sha = pendingSha ?: segment.digest().hex().also { pendingSha = it }
            journal.append(RecordingJournal.segmentLine(RecordingJournal.Segment(segmentStart, segmentEnd, dataBytes, sha)))
            pendingSha = null
            segmentSamples = 0
        }

        /** 只给 [release] 用：收场时能提交就提交；写不进去也无妨，日志里已有的分段下次启动找回。 */
        private fun commitIfPossible() {
            try {
                commit()
            } catch (e: IOException) {
                // 日志写不进去（磁盘满）：没提交的尾巴留在 .part 里，下次启动恢复时按不可信截掉
            }
        }

        // 挂起函数不能用 @Synchronized；封存本身是阻塞的文件操作，没有挂起点，用 synchronized 块即可
        override suspend fun seal(): SealedAudio = synchronized(this) {
            check(!finished) { "已经封存或放弃" }
            // 最后一段的日志行必须先落盘：封存好的音频要能在进程被杀之后凭日志验证、找回。写不进去（磁盘满）就不封存，
            // 直接抛出，.part 与日志原样留着（协调器收场时释放写入器，日志里已有的分段下次启动找回）；
            // 绝不能留下一个没有任何分段记录的 .wav，登记之前被杀，之后每次恢复都得拒收它（audio_unverified）
            commit()
            try {
                data.setLength(HEADER_BYTES + dataBytes) // 回滚没能截掉的残渣
                data.seek(0)
                data.write(header(dataBytes, format))
                io.sync(data, part.name)
            } finally {
                closeQuietly(data)
            }
            io.move(part, target)
            syncDirectoryQuietly()
            journal.close()
            finished = true
            synchronized(writing) {
                writing -= recordId
                handedOver += recordId
            }
            SealedAudio(target.absolutePath, whole.digest().hex(), HEADER_BYTES + dataBytes, dataBytes / 2, format)
        }

        @Synchronized
        override fun abort() {
            if (finished) return // 已经封存好的音频不是“已写的部分”，不删
            finished = true
            closeQuietly(data)
            journal.close()
            part.delete()
            File(directory, "$recordId.journal").delete()
            synchronized(writing) { writing -= recordId }
        }

        @Synchronized
        override fun release() {
            if (finished) return
            finished = true
            commitIfPossible() // 体面的收场：还没提交的尾巴能提交就提交，一个采样都不丢
            closeQuietly(data)
            journal.close()
            synchronized(writing) { writing -= recordId } // 不进 handedOver：之后的扫描可以把它找回来
        }
    }

    // ---- 恢复 ----

    override suspend fun recoverOrphans(isRegistered: suspend (recordId: String) -> Boolean): OrphanScan {
        val names = directory.list() ?: return OrphanScan(emptyList(), emptyList())
        val candidates = names.mapNotNull { FILE_NAME.matchEntire(it) }
            .filter { it.groupValues[2] != "wav" } // 只有 .wav 的是早已交付的普通录音
            .map { it.groupValues[1] }
            .toSortedSet()
        val recovered = mutableListOf<RecoveredRecording>()
        val failures = mutableListOf<OrphanFailure>()
        for (recordId in candidates) {
            if (!File(directory, "$recordId.journal").isFile && !File(directory, "$recordId.wav.part").isFile) continue // 同名的是目录之类
            if (!claim(recordId)) continue
            try {
                when (val outcome = recoverOne(recordId, isRegistered)) {
                    is Outcome.Recovered -> recovered += outcome.recording
                    is Outcome.Failed -> failures += OrphanFailure(recordId, outcome.reason)
                    Outcome.Settled -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 一个录音出错只影响它自己；原因只记类别，不含路径、设备标识或内容
                failures += OrphanFailure(recordId, if (e is IOException) "io_error" else "unexpected_error")
            } finally {
                synchronized(writing) { writing -= recordId }
            }
        }
        return OrphanScan(recovered, failures)
    }

    /** 本进程正在写、或已经封存交付的不是孤儿；否则标记为“正在恢复”，免得并发开始的新录音被当成孤儿。 */
    private fun claim(recordId: String): Boolean = synchronized(writing) {
        if (recordId in writing || recordId in handedOver) false else writing.add(recordId)
    }

    private sealed interface Outcome {
        class Recovered(val recording: RecoveredRecording) : Outcome

        class Failed(val reason: String) : Outcome

        /** 无事可做：已经登记进库（只清了日志），或者根本没录下任何东西（只清了空壳）。 */
        data object Settled : Outcome
    }

    private suspend fun recoverOne(recordId: String, isRegistered: suspend (String) -> Boolean): Outcome {
        val journalFile = File(directory, "$recordId.journal")
        val part = File(directory, "$recordId.wav.part")
        val sealed = File(directory, "$recordId.wav")
        if (journalFile.isFile) {
            val registered = try {
                isRegistered(recordId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return Outcome.Failed("registry_unavailable")
            }
            if (registered) {
                journalFile.delete() // 已经交付：只清理恢复记录，音频不动
                return Outcome.Settled
            }
        }
        val parsed = if (journalFile.isFile) RecordingJournal.parse(journalFile.readBytes()) else return Outcome.Failed("journal_missing")
        val begin = parsed.begin
        if (begin == null) {
            // begin 这一行还没写完就被杀：音频文件在它落盘之后才创建，所以什么都没录到、也没有这个 Record，只清掉空壳。
            // 音频文件已经存在而日志读不懂，才是真的坏了：留着，记为失败
            if (part.exists() || sealed.exists()) return Outcome.Failed("journal_unreadable")
            journalFile.delete()
            return Outcome.Settled
        }
        if (begin.recordId != recordId) return Outcome.Failed("journal_unreadable")
        if (part.isFile && sealed.isFile) return Outcome.Failed("ambiguous_audio_files")
        val dataFile = if (part.isFile) part else if (sealed.isFile) sealed else null
        if (dataFile == null) {
            // 日志落盘了、音频文件还没来得及创建就被杀：没有任何音频，仍然是一个被中断的 Record
            return if (parsed.segments.isEmpty()) Outcome.Recovered(recording(begin, emptyList(), null, 0)) else Outcome.Failed("audio_missing")
        }
        val file = io.open(dataFile)
        try {
            val verified = verify(file, parsed.segments)
            val validLength = HEADER_BYTES + verified.validBytes
            val dropped = maxOf(0L, file.length() - validLength)
            if (verified.runs.isEmpty()) {
                if (dataFile == sealed && file.length() > HEADER_BYTES) return Outcome.Failed("audio_unverified") // 封存好的音频不因为日志不全就删
                closeQuietly(file)
                dataFile.delete() // 没有任何可信的字节：只剩一个空壳
                return Outcome.Recovered(recording(begin, emptyList(), null, dropped))
            }
            if (file.length() != validLength) file.setLength(validLength) // 截回最后一个有效边界
            file.seek(0)
            file.write(header(verified.validBytes, begin.format))
            io.sync(file, dataFile.name)
            closeQuietly(file)
            if (dataFile == part) {
                io.move(part, sealed)
                syncDirectoryQuietly()
            }
            val audio = SealedAudio(sealed.absolutePath, verified.wholeSha256, validLength, verified.validBytes / 2, begin.format)
            return Outcome.Recovered(recording(begin, verified.runs, audio, dropped))
        } finally {
            closeQuietly(file)
        }
    }

    private fun recording(begin: RecordingJournal.Begin, runs: List<RecoveredRun>, audio: SealedAudio?, dropped: Long) = RecoveredRecording(
        recordId = begin.recordId,
        captureSessionId = begin.sessionId,
        startedAt = begin.startedAt,
        evidence = begin.evidence,
        routeEvidenceId = begin.evidenceId,
        format = begin.format,
        runs = runs,
        audio = audio,
        droppedBytes = dropped,
    )

    private class Verified(val runs: List<RecoveredRun>, val validBytes: Long, val wholeSha256: String)

    /** 正在拼的一段语音：连续的分段合并成一段，哈希覆盖它所有的字节。 */
    private class RunBuilder(val start: Long, var end: Long, val digest: MessageDigest) {
        fun finish() = RecoveredRun(start, end, digest.digest().hex())
    }

    /** 逐段验证：位置连续、字节数对得上、文件里有、哈希相符。第一个不满足的分段和它之后的一律不信。 */
    private fun verify(file: RandomAccessFile, segments: List<RecordingJournal.Segment>): Verified {
        val whole = MessageDigest.getInstance("SHA-256")
        val runs = mutableListOf<RecoveredRun>()
        var run: RunBuilder? = null
        var offset = 0L
        var lastEnd = 0L
        val fileLength = file.length()
        for (segment in segments) {
            val samples = segment.end - segment.start
            val byteCount = samples * 2
            if (samples <= 0 || segment.start < lastEnd || segment.offset != offset + byteCount || byteCount > MAX_SEGMENT_BYTES) break // 日志自相矛盾
            if (HEADER_BYTES + segment.offset > fileLength) break // 数据被撕断
            val bytes = ByteArray(byteCount.toInt())
            file.seek(HEADER_BYTES + offset)
            file.readFully(bytes)
            if (MessageDigest.getInstance("SHA-256").digest(bytes).hex() != segment.sha256) break // 数据被改过
            whole.update(bytes)
            val current = run
            if (current != null && segment.start == current.end) {
                current.end = segment.end
                current.digest.update(bytes)
            } else {
                if (current != null) runs += current.finish()
                run = RunBuilder(segment.start, segment.end, MessageDigest.getInstance("SHA-256").apply { update(bytes) })
            }
            offset += byteCount
            lastEnd = segment.end
        }
        run?.let { runs += it.finish() }
        return Verified(runs, offset, whole.digest().hex())
    }

    private fun closeQuietly(file: RandomAccessFile?) {
        try {
            file?.close()
        } catch (e: IOException) {
            // 关不上也没有更多能做的
        }
    }

    companion object {
        private const val HEADER_BYTES = 44L

        /** 一个分段最多这么多字节：日志声称更大的当作损坏，免得为它分配巨大的缓冲。一个提交间隔的音频只有几十 KB。 */
        private const val MAX_SEGMENT_BYTES = 8L shl 20

        /** 一个提交间隔：1 秒的语音。被杀之后最多丢这么多（再加一次写入）；更短的间隔换来更多的 fsync。 */
        const val COMMIT_INTERVAL_MS = 1_000L

        private val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private val FILE_NAME = Regex("^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.(journal|wav\\.part|wav)$")

        /** 标准 44 字节的 PCM WAV 头（16 位小端）。 */
        internal fun header(dataBytes: Long, format: PcmFormat): ByteArray {
            val byteRate = format.sampleRateHz * format.channels * 2
            return ByteBuffer.allocate(HEADER_BYTES.toInt()).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt((36 + dataBytes).toInt())
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1) // PCM
                putShort(format.channels.toShort())
                putInt(format.sampleRateHz)
                putInt(byteRate)
                putShort((format.channels * 2).toShort())
                putShort(16)
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(dataBytes.toInt())
            }.array()
        }
    }
}
