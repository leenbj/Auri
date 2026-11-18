package app.auri.platform.storage

import app.auri.capture.adapter.PcmFormat
import app.auri.capture.adapter.RecordingStore
import app.auri.capture.adapter.RecordingWriter
import app.auri.capture.adapter.SealedAudio
import app.auri.capture.contract.CaptureHandle
import app.auri.capture.contract.GateState
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * 录音存储：每个 Record 一个 16 位 PCM 的 WAV 文件，放在应用私有目录里（调用方传 `filesDir/records`），文件名就是 record_id。
 * 只有路由确认之后协调器才会调用 [begin]，所以这个目录里不会出现任何未经确认的音频。
 *
 * 写入中的文件叫 `<record_id>.wav.part`，[RecordingWriter.seal] 做完头部修补与落盘才改名成 `<record_id>.wav`：
 * 进程在录音中途被杀，留下的只会是 `.part`，不会有看上去已经完成、实际却是半截的录音（Ticket 19 负责恢复这些残段）。
 * `sha256` 是 PCM 采样字节（不含 WAV 头）的 SHA-256，边写边算，封存时不用再读一遍文件。
 */
class WavRecordingStore(private val directory: File) : RecordingStore {
    override fun begin(recordId: String, handle: CaptureHandle, format: PcmFormat): RecordingWriter {
        // record_id 直接当文件名：只接受合同里的 uuid，挡住 "../x" 之类的路径
        require(UUID_PATTERN.matches(recordId)) { "record_id 必须是小写 UUID" }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建录音目录")
        val part = File(directory, "$recordId.wav.part")
        val sealed = File(directory, "$recordId.wav")
        if (sealed.exists() || part.exists()) throw IOException("这个 Record 的音频文件已经存在")
        return Writer(part, sealed, handle, format)
    }

    private class Writer(
        private val part: File,
        private val target: File,
        private val handle: CaptureHandle,
        private val format: PcmFormat,
    ) : RecordingWriter {
        private val file = RandomAccessFile(part, "rw")
        private val digest = MessageDigest.getInstance("SHA-256")
        private var dataBytes = 0L
        private var finished = false

        init {
            try {
                file.write(ByteArray(HEADER_BYTES.toInt())) // 头部先占位，封存时按实际长度修补
            } catch (e: IOException) {
                file.close()
                part.delete()
                throw e
            }
        }

        @Synchronized
        override fun append(handle: CaptureHandle, samples: ShortArray) {
            check(!finished) { "已经封存或放弃，不能再写" }
            check(handle.gateState == GateState.OPEN) { "闸门已关，不接受写入" }
            check(handle.captureSessionId == this.handle.captureSessionId && handle.generation == this.handle.generation && handle.verifiedRouteEpoch == this.handle.verifiedRouteEpoch) {
                "写入句柄不属于这次录音"
            }
            val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach { buffer.putShort(it) }
            val bytes = buffer.array()
            file.write(bytes)
            digest.update(bytes)
            dataBytes += bytes.size
        }

        // 挂起函数不能用 @Synchronized；封存本身是阻塞的文件操作，没有挂起点，用 synchronized 块即可
        override suspend fun seal(): SealedAudio = synchronized(this) {
            check(!finished) { "已经封存或放弃" }
            try {
                file.seek(0)
                file.write(header(dataBytes, format))
                file.fd.sync()
            } finally {
                file.close()
            }
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            finished = true
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            SealedAudio(target.absolutePath, sha, HEADER_BYTES + dataBytes, dataBytes / 2, format)
        }

        @Synchronized
        override fun abort() {
            if (finished) return // 已经封存好的音频不是“已写的部分”，不删
            finished = true
            try {
                file.close()
            } catch (e: IOException) {
                // 关不上也要继续删
            }
            part.delete()
        }
    }

    companion object {
        private const val HEADER_BYTES = 44L
        private val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

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
