package app.auri.asr.audio

import java.io.File

class WavFormatException(message: String) : Exception(message)

/**
 * 只读 16 位整数 PCM 的 WAV（含 WAVE_FORMAT_EXTENSIBLE 里声明为 PCM 的）。
 * 其它格式一律拒绝，不会把浮点、8 位或压缩数据当成 16 位整数硬读。
 * RIFF 头里的总长度不可信，不使用；data 块声明的长度超过文件实际长度（流式写入的占位值或被截断的文件）时，
 * 只取实际存在的完整帧。
 */
object WavReader {
    private const val PCM_TAG = 1
    private const val EXTENSIBLE_TAG = 0xFFFE
    private val PCM_GUID_TAIL = intArrayOf(0x00, 0x00, 0x00, 0x00, 0x10, 0x00, 0x80, 0x00, 0x00, 0xAA, 0x00, 0x38, 0x9B, 0x71)

    private class Format(val channels: Int, val sampleRateHz: Int)

    /** 64 MiB：约合 16 kHz 单声道 35 分钟。调用方知道更紧的上限就传更紧的。 */
    const val DEFAULT_MAX_BYTES = 64L shl 20

    /**
     * 先看大小再读内容：超大的（或恶意的稀疏）文件不能被整个读进内存。
     * shortcut: 检查与读取之间文件若被追加不会再被拦住，只面向本机验收用的文件，产品录音不走这条路。
     */
    fun read(file: File, maxBytes: Long = DEFAULT_MAX_BYTES): PcmAudio {
        if (!file.isFile) throw WavFormatException("不是常规文件")
        val size = file.length()
        if (size > maxBytes) throw WavFormatException("文件 $size 字节，超过读取上限 $maxBytes 字节")
        return parse(file.readBytes())
    }

    fun parse(bytes: ByteArray): PcmAudio {
        if (bytes.size < 12) throw WavFormatException("文件不足 12 字节，不是 WAV")
        if (tag(bytes, 0) != "RIFF" || tag(bytes, 8) != "WAVE") throw WavFormatException("不是 RIFF/WAVE 文件")

        var format: Format? = null
        var pos = 12L
        while (pos + 8 <= bytes.size) {
            val id = tag(bytes, pos.toInt())
            val size = u32(bytes, pos.toInt() + 4)
            val body = (pos + 8).toInt()
            when (id) {
                "fmt " -> format = parseFormat(bytes, body, size)
                "data" -> {
                    val f = format ?: throw WavFormatException("data 块出现在 fmt 块之前")
                    return decode(bytes, body, minOf(size, (bytes.size - body).toLong()).toInt(), f)
                }
            }
            pos = body + size + (size and 1L) // 块按偶数字节对齐，奇数长度后面有 1 字节补位
        }
        throw WavFormatException(if (format == null) "没有 fmt 块" else "没有 data 块")
    }

    private fun parseFormat(bytes: ByteArray, body: Int, size: Long): Format {
        if (size < 16 || body + size > bytes.size) throw WavFormatException("fmt 块残缺")
        val tag = u16(bytes, body)
        val channels = u16(bytes, body + 2)
        val rate = u32(bytes, body + 4)
        val bits = u16(bytes, body + 14)

        val isPcm = when (tag) {
            PCM_TAG -> true
            EXTENSIBLE_TAG -> size >= 40 && u16(bytes, body + 24) == PCM_TAG &&
                PCM_GUID_TAIL.indices.all { (bytes[body + 26 + it].toInt() and 0xFF) == PCM_GUID_TAIL[it] }
            else -> false
        }
        if (!isPcm) throw WavFormatException("不是整数 PCM（格式码 0x${tag.toString(16)}）")
        if (bits != 16) throw WavFormatException("只支持 16 位样本，文件是 $bits 位")
        if (channels !in 1..2) throw WavFormatException("只支持 1 或 2 个声道，文件是 $channels 个")
        if (rate !in PcmAudio.SAMPLE_RATE_RANGE.first.toLong()..PcmAudio.SAMPLE_RATE_RANGE.last.toLong()) {
            throw WavFormatException("采样率超出 8000–192000 Hz：$rate")
        }
        return Format(channels, rate.toInt())
    }

    private fun decode(bytes: ByteArray, start: Int, length: Int, format: Format): PcmAudio {
        val frames = length / (2 * format.channels) // 末尾不足一帧的零头丢弃
        val samples = ShortArray(frames * format.channels) { i ->
            val at = start + 2 * i
            ((bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() shl 8)).toShort()
        }
        return PcmAudio(samples, format.sampleRateHz, format.channels)
    }

    private fun tag(bytes: ByteArray, at: Int) = String(bytes, at, 4, Charsets.ISO_8859_1)

    private fun u16(bytes: ByteArray, at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, at: Int) = u16(bytes, at).toLong() or (u16(bytes, at + 2).toLong() shl 16)
}
