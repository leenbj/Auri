package app.auri.sync.pairing

import app.auri.capture.testing.RepoContracts
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 取景的每一帧都要恰好关闭一次（CameraX 不关就不给下一帧），读失败的帧不能连累后面的帧。
 */
class QrFrameReaderTest {
    private val payloadText: String =
        RepoContracts.json("fixtures/messages/valid/pairing.payload.lan-gateway.json").jsonObject.getValue("instance").toString()

    private class Frame(
        override val width: Int,
        override val height: Int,
        override val rowStride: Int,
        private val bytes: () -> ByteArray,
    ) : LuminanceFrame {
        var closed = 0

        override fun luminance(): ByteArray = bytes()

        override fun close() {
            closed += 1
        }
    }

    private fun qr(text: String): BitMatrix =
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 400, 400, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4))

    private fun frameOf(matrix: BitMatrix, stride: Int = matrix.width): Frame {
        val bytes = ByteArray(stride * matrix.height) { 0x55 }
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) bytes[y * stride + x] = if (matrix[x, y]) 0 else 0xFF.toByte()
        return Frame(matrix.width, matrix.height, stride) { bytes }
    }

    @Test
    fun `读到二维码就把文字交出去，并关闭这一帧`() {
        val texts = mutableListOf<String>()
        val frame = frameOf(qr(payloadText))

        QrFrameReader(texts::add).read(frame)

        assertEquals(listOf(payloadText), texts)
        assertEquals(1, frame.closed)
    }

    @Test
    fun `按这一帧自己的行距读`() {
        val texts = mutableListOf<String>()
        val frame = frameOf(qr(payloadText), stride = 448)

        QrFrameReader(texts::add).read(frame)

        assertEquals(listOf(payloadText), texts)
    }

    @Test
    fun `没有二维码的帧什么也不交出去，但照样关闭`() {
        val texts = mutableListOf<String>()
        val frame = Frame(200, 200, 200) { ByteArray(200 * 200) { 0xFF.toByte() } }

        QrFrameReader(texts::add).read(frame)

        assertTrue(texts.isEmpty())
        assertEquals(1, frame.closed)
    }

    @Test
    fun `取灰度出异常也不外抛，仍然关闭这一帧`() {
        val frame = Frame(10, 10, 10) { throw IllegalStateException("缓冲区已被回收") }

        QrFrameReader { }.read(frame)

        assertEquals(1, frame.closed)
    }

    @Test
    fun `回调出异常也不外抛（不能让分析线程死掉），仍然关闭这一帧`() {
        val frame = frameOf(qr(payloadText))

        QrFrameReader { throw IllegalStateException("界面已经没了") }.read(frame)

        assertEquals(1, frame.closed)
    }

    @Test
    fun `一帧失败不影响下一帧`() {
        val texts = mutableListOf<String>()
        val reader = QrFrameReader(texts::add)
        reader.read(Frame(10, 10, 10) { throw IllegalStateException("坏帧") })

        val good = frameOf(qr(payloadText))
        reader.read(good)

        assertEquals(listOf(payloadText), texts)
        assertEquals(1, good.closed)
    }
}
