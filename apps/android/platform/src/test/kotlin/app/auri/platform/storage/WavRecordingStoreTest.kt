package app.auri.platform.storage

import app.auri.asr.audio.WavReader
import app.auri.capture.adapter.PcmFormat
import app.auri.capture.contract.CaptureHandle
import app.auri.capture.contract.GateState
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavRecordingStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val format = PcmFormat(16_000)
    private val recordId = "0a0a0a0a-0000-4000-8000-000000000001"
    private val sessionId = "0b0b0b0b-0000-4000-8000-000000000001"
    private val open = CaptureHandle(sessionId, 1, 4, GateState.OPEN)

    private fun store() = WavRecordingStore(File(folder.root, "records"))

    private fun pcmBytes(vararg chunks: ShortArray): ByteArray {
        val all = chunks.flatMap { it.toList() }
        return ByteBuffer.allocate(all.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { all.forEach { putShort(it) } }.array()
    }

    @Test
    fun `one second of stereo audio retains all interleaved samples and its duration`() = runBlocking {
        val stereo = PcmFormat(16_000, channels = 2)
        val writer = store().begin(recordId, open, stereo)
        writer.append(open, ShortArray(32_000))

        val sealed = writer.seal()

        assertEquals(32_000L, sealed.sampleCount)
        assertEquals(1_000L, sealed.durationMs)
        assertEquals(2, WavReader.read(File(sealed.location)).channels)
    }

    @Test
    fun `写完封存：文件名就是 record_id，WAV 能被读回原样，哈希是 PCM 字节的 SHA-256`() = runBlocking {
        val first = ShortArray(800) { (it - 400).toShort() }
        val second = ShortArray(1600) { (it * 7).toShort() }
        val writer = store().begin(recordId, open, format)
        writer.append(open, first)
        writer.append(open, second)

        val sealed = writer.seal()

        val file = File(folder.root, "records/$recordId.wav")
        assertEquals(file.absolutePath, sealed.location)
        assertTrue(file.isFile)
        assertFalse("改名之后不会留下 .part", File(folder.root, "records/$recordId.wav.part").exists())
        assertEquals(44L + 2400 * 2, file.length())
        assertEquals(file.length(), sealed.sizeBytes)
        assertEquals(2400L, sealed.sampleCount)
        assertEquals(150L, sealed.durationMs)

        val read = WavReader.read(file)
        assertEquals(16_000, read.sampleRateHz)
        assertEquals(1, read.channels)
        assertArrayEquals(first + second, read.samples)

        val expected = MessageDigest.getInstance("SHA-256").digest(pcmBytes(first, second)).joinToString("") { "%02x".format(it) }
        assertEquals("哈希只覆盖 PCM 字节，不含头部", expected, sealed.sha256)
    }

    @Test
    fun `录音进行中磁盘上只有 part 文件，封存之前不存在看起来已完成的录音`() {
        val writer = store().begin(recordId, open, format)
        writer.append(open, ShortArray(100))

        assertTrue(File(folder.root, "records/$recordId.wav.part").isFile)
        assertFalse(File(folder.root, "records/$recordId.wav").exists())
    }

    @Test
    fun `放弃：删除已写的部分；重复放弃没有副作用；封存之后放弃不删已封存的音频`() = runBlocking {
        val aborted = store().begin(recordId, open, format)
        aborted.append(open, ShortArray(100))
        aborted.abort()
        aborted.abort()
        assertFalse(File(folder.root, "records/$recordId.wav.part").exists())
        assertFalse(File(folder.root, "records/$recordId.wav").exists())

        val otherId = "0a0a0a0a-0000-4000-8000-000000000002"
        val kept = store().begin(otherId, open, format)
        kept.append(open, ShortArray(100))
        kept.seal()
        kept.abort()
        assertTrue(File(folder.root, "records/$otherId.wav").isFile)
    }

    @Test
    fun `写入句柄必须是 begin 时那个：闸门已关或属于别的会话就拒绝，文件一个字节不增`() {
        val writer = store().begin(recordId, open, format)
        writer.append(open, ShortArray(100))
        val part = File(folder.root, "records/$recordId.wav.part")
        val before = part.length()

        val wrong = listOf(
            open.copy(gateState = GateState.CLOSED),
            open.copy(captureSessionId = "0c0c0c0c-0000-4000-8000-000000000009"),
            open.copy(generation = 2),
            open.copy(verifiedRouteEpoch = 5),
        )
        for (handle in wrong) {
            try {
                writer.append(handle, ShortArray(100))
                fail("应当拒绝：$handle")
            } catch (expected: IllegalStateException) {
                // 预期
            }
        }
        assertEquals(before, part.length())
    }

    @Test
    fun `record_id 直接当文件名，不是 uuid 的一律拒绝，不会写到目录之外`() {
        val store = store()
        for (bad in listOf("../escape", "a/b", "", "0A0A0A0A-0000-4000-8000-000000000001", recordId + "x")) {
            try {
                store.begin(bad, open, format)
                fail("应当拒绝：$bad")
            } catch (expected: IllegalArgumentException) {
                // 预期
            }
        }
        assertFalse(File(folder.root, "escape.wav.part").exists())
    }

    @Test
    fun `同一个 Record 的音频已经存在就拒绝再开一份，不覆盖`() = runBlocking {
        val first = store().begin(recordId, open, format)
        first.append(open, ShortArray(10) { 1 })
        first.seal()

        try {
            store().begin(recordId, open, format)
            fail("应当拒绝")
        } catch (expected: java.io.IOException) {
            // 预期
        }
        assertEquals("原来的音频没被动", 44L + 20, File(folder.root, "records/$recordId.wav").length())
    }

    @Test
    fun `采样按 16 位小端写入`() = runBlocking {
        val writer = store().begin(recordId, open, format)
        writer.append(open, shortArrayOf(0x0102, -2))
        writer.seal()

        val bytes = File(folder.root, "records/$recordId.wav").readBytes()
        assertArrayEquals(byteArrayOf(0x02, 0x01, 0xFE.toByte(), 0xFF.toByte()), bytes.copyOfRange(44, 48))
    }
}
