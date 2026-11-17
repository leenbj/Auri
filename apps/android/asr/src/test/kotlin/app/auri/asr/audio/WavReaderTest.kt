package app.auri.asr.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** 宿主应用的“转写这个文件”入口读的是 WAV；读错格式不能悄悄当成别的格式往下走。 */
class WavReaderTest {

    private class Chunk(val id: String, val body: ByteArray)

    private fun le(size: Int, block: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).also(block).array()

    private fun fmtChunk(rate: Int, channels: Int, bits: Int, tag: Int = 1, extensibleSubFormat: Int? = null): Chunk {
        val blockAlign = channels * bits / 8
        val extensible = extensibleSubFormat != null
        val body = le(if (extensible) 40 else 16) {
            putShort(if (extensible) 0xFFFE.toShort() else tag.toShort())
            putShort(channels.toShort())
            putInt(rate)
            putInt(rate * blockAlign)
            putShort(blockAlign.toShort())
            putShort(bits.toShort())
            if (extensible) {
                putShort(22) // cbSize
                putShort(bits.toShort()) // validBitsPerSample
                putInt(0) // channelMask
                putShort(extensibleSubFormat!!.toShort()) // GUID 前两个字节是格式码
                put(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x10, 0x00, 0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71))
            }
        }
        return Chunk("fmt ", body)
    }

    private fun pcm16(samples: ShortArray): ByteArray =
        le(samples.size * 2) { samples.forEach { putShort(it) } }

    private fun riff(chunks: List<Chunk>, dataSizeOverride: Long? = null, magic: String = "RIFF", form: String = "WAVE"): ByteArray {
        val out = ByteArrayOutputStream()
        fun u32(v: Long) = out.write(le(4) { putInt(v.toInt()) })
        out.write(magic.toByteArray()); u32(0); out.write(form.toByteArray())
        for (c in chunks) {
            out.write(c.id.toByteArray())
            u32(if (c.id == "data" && dataSizeOverride != null) dataSizeOverride else c.body.size.toLong())
            out.write(c.body)
            if (c.body.size % 2 == 1) out.write(0) // RIFF 块按偶数字节对齐
        }
        return out.toByteArray()
    }

    private fun standard(samples: ShortArray, rate: Int = 16000, channels: Int = 1) =
        listOf(fmtChunk(rate, channels, 16), Chunk("data", pcm16(samples)))

    private fun assertRejected(bytes: ByteArray, why: String) {
        try {
            WavReader.parse(bytes)
        } catch (_: WavFormatException) {
            return
        }
        fail("应当拒绝：$why")
    }

    @Test
    fun `单声道 16k 读出原样的样本`() {
        val samples = shortArrayOf(0, 1, -1, 12345, -32768, 32767)

        val audio = WavReader.parse(riff(standard(samples)))

        assertEquals(16000, audio.sampleRateHz)
        assertEquals(1, audio.channels)
        assertArrayEquals(samples, audio.samples)
    }

    @Test
    fun `立体声 44_1k 保持交错顺序`() {
        val samples = shortArrayOf(1, -1, 2, -2, 3, -3)

        val audio = WavReader.parse(riff(standard(samples, rate = 44100, channels = 2)))

        assertEquals(44100, audio.sampleRateHz)
        assertEquals(2, audio.channels)
        assertEquals(3, audio.frames)
        assertArrayEquals(samples, audio.samples)
    }

    @Test
    fun `跳过不认识的块 并处理奇数长度块的补位字节`() {
        val samples = shortArrayOf(5, 6, 7)
        val chunks = listOf(
            Chunk("LIST", "abcde".toByteArray()), // 5 字节，后面有 1 字节补位
            fmtChunk(16000, 1, 16),
            Chunk("fact", ByteArray(4)),
            Chunk("data", pcm16(samples)),
            Chunk("id3 ", "tail".toByteArray()),
        )

        assertArrayEquals(samples, WavReader.parse(riff(chunks)).samples)
    }

    @Test
    fun `可扩展格式头里声明的是 PCM 时可以读`() {
        val samples = shortArrayOf(10, 20)
        val chunks = listOf(fmtChunk(48000, 1, 16, extensibleSubFormat = 1), Chunk("data", pcm16(samples)))

        val audio = WavReader.parse(riff(chunks))

        assertEquals(48000, audio.sampleRateHz)
        assertArrayEquals(samples, audio.samples)
    }

    @Test
    fun `可扩展格式头里声明的是浮点时拒绝`() {
        val chunks = listOf(fmtChunk(48000, 1, 16, extensibleSubFormat = 3), Chunk("data", pcm16(shortArrayOf(1))))

        assertRejected(riff(chunks), "子格式不是 PCM")
    }

    @Test
    fun `不是 16 位整数 PCM 的格式一律拒绝 不会当成别的格式硬读`() {
        assertRejected(riff(listOf(fmtChunk(16000, 1, 32, tag = 3), Chunk("data", ByteArray(8)))), "32 位浮点")
        assertRejected(riff(listOf(fmtChunk(16000, 1, 8), Chunk("data", ByteArray(8)))), "8 位")
        assertRejected(riff(listOf(fmtChunk(16000, 1, 24), Chunk("data", ByteArray(9)))), "24 位")
        assertRejected(riff(listOf(fmtChunk(16000, 1, 16, tag = 0x55), Chunk("data", ByteArray(8)))), "MP3 编码")
    }

    @Test
    fun `声道数与采样率超出合同范围时拒绝`() {
        assertRejected(riff(standard(shortArrayOf(1, 2, 3), channels = 3)), "3 声道")
        assertRejected(riff(standard(shortArrayOf(1, 2), channels = 0)), "0 声道")
        assertRejected(riff(standard(shortArrayOf(1, 2), rate = 7999)), "采样率过低")
        assertRejected(riff(standard(shortArrayOf(1, 2), rate = 192001)), "采样率过高")
    }

    @Test
    fun `结构残缺的文件拒绝`() {
        assertRejected(ByteArray(0), "空文件")
        assertRejected(ByteArray(11), "不足一个文件头")
        assertRejected(riff(standard(shortArrayOf(1)), magic = "RIFX"), "不是 RIFF")
        assertRejected(riff(standard(shortArrayOf(1)), form = "AVI "), "不是 WAVE")
        assertRejected(riff(listOf(Chunk("data", pcm16(shortArrayOf(1))))), "没有 fmt 块")
        assertRejected(riff(listOf(fmtChunk(16000, 1, 16))), "没有 data 块")
        assertRejected(riff(listOf(Chunk("data", pcm16(shortArrayOf(1))), fmtChunk(16000, 1, 16))), "data 在 fmt 之前")
    }

    @Test
    fun `data 块声明的长度超过文件实际长度时只取实际存在的完整帧`() {
        val samples = shortArrayOf(1, 2, 3, 4)
        for (declared in listOf(0xFFFFFFFFL, 1_000_000L)) { // 流式写入的占位值与被截断的文件
            val audio = WavReader.parse(riff(standard(samples), dataSizeOverride = declared))

            assertArrayEquals("声明长度 $declared", samples, audio.samples)
        }
    }

    @Test
    fun `末尾不足一帧的零头被丢弃`() {
        val body = pcm16(shortArrayOf(1, -1, 2, -2)) + byteArrayOf(9, 9, 9) // 立体声：多出 3 字节
        val bytes = riff(listOf(fmtChunk(16000, 2, 16), Chunk("data", body)))

        assertArrayEquals(shortArrayOf(1, -1, 2, -2), WavReader.parse(bytes).samples)
    }

    @Test
    fun `只有文件头没有样本时得到空音频`() {
        assertEquals(0, WavReader.parse(riff(standard(ShortArray(0)))).samples.size)
    }
}
