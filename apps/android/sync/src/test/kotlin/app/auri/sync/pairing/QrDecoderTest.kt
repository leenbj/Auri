package app.auri.sync.pairing

import app.auri.capture.testing.RepoContracts
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.Writer
import com.google.zxing.common.BitMatrix
import com.google.zxing.oned.Code128Writer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 21 第 1 项：摄像头看到的画面里的二维码要读成配对载荷的文字。解码器用固定版本的 ZXing core（Apache-2.0）。
 * 测试里的图像由 ZXing 的写码器生成，再经我们的解码入口读回来：证明“画面（灰度，行距可能大于宽度）→ 文字”这一段是通的；
 * 摄像头本身（CameraX 取景与对焦）只有真机能验证，见验收文档。
 */
class QrDecoderTest {
    private val payloadText: String =
        RepoContracts.json("fixtures/messages/valid/pairing.payload.lan-gateway.json").jsonObject.getValue("instance").toString()

    /** 深色模块 = 0，浅色 = 255；[stride] 比宽度大的部分填一个干扰值，证明解码按行距而不是按宽度读。 */
    private fun luminanceOf(matrix: BitMatrix, stride: Int = matrix.width, inverted: Boolean = false, padding: Byte = 0x55): ByteArray {
        val bytes = ByteArray(stride * matrix.height) { padding }
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            val dark = matrix[x, y] != inverted
            bytes[y * stride + x] = if (dark) 0 else 0xFF.toByte()
        }
        return bytes
    }

    private fun qr(text: String, size: Int = 400, level: ErrorCorrectionLevel = ErrorCorrectionLevel.M): BitMatrix =
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.ERROR_CORRECTION to level, EncodeHintType.MARGIN to 4))

    @Test
    fun `配对载荷的二维码读回来，一个字节都不差`() {
        val matrix = qr(payloadText)
        assertEquals(payloadText, QrDecoder.decode(luminanceOf(matrix), matrix.width, matrix.height))
    }

    @Test
    fun `摄像头的行距大于宽度时按行距读（填充字节不是图像的一部分）`() {
        val matrix = qr(payloadText)
        val stride = matrix.width + 48
        assertEquals(payloadText, QrDecoder.decode(luminanceOf(matrix, stride), matrix.width, matrix.height, stride))
    }

    @Test
    fun `深底浅码（反色）也能读`() {
        val matrix = qr(payloadText)
        assertEquals(payloadText, QrDecoder.decode(luminanceOf(matrix, inverted = true), matrix.width, matrix.height))
    }

    /** 顺时针转 90 度 [times] 次。 */
    private fun rotated(matrix: BitMatrix, times: Int): BitMatrix {
        var current = matrix
        repeat(times) {
            val next = BitMatrix(current.height, current.width)
            for (y in 0 until current.height) for (x in 0 until current.width) if (current[x, y]) next.set(current.height - 1 - y, x)
            current = next
        }
        return current
    }

    @Test
    fun `四个方向都能读：手机竖着拿时相机给的帧通常转了 90 度`() {
        // 实测 ZXing 3.5.4 对这个 379 字符的载荷，顺时针转 90 度的那一帧会抛 FormatException，其余三个方向没事；
        // 所以解码器自己把画面转一圈再试，不能指望库的内部旋转处理。
        val matrix = qr(payloadText)
        for (quarterTurns in 0..3) {
            val image = rotated(matrix, quarterTurns)
            assertEquals("转 ${quarterTurns * 90} 度", payloadText, QrDecoder.decode(luminanceOf(image), image.width, image.height))
            assertEquals("转 ${quarterTurns * 90} 度且反色", payloadText, QrDecoder.decode(luminanceOf(image, inverted = true), image.width, image.height))
        }
    }

    @Test
    fun `转过的画面同样按行距读`() {
        val image = rotated(qr(payloadText), 1)
        val stride = image.width + 16
        assertEquals(payloadText, QrDecoder.decode(luminanceOf(image, stride), image.width, image.height, stride))
    }

    @Test
    fun `画面太小（每个模块不到一个像素）读不出来就是 null，不是异常`() {
        val matrix = qr(payloadText)
        val full = luminanceOf(matrix)
        val factor = 8
        val width = matrix.width / factor
        val small = ByteArray(width * width) { index -> full[(index / width * factor) * matrix.width + (index % width) * factor] }
        assertNull(QrDecoder.decode(small, width, width))
    }

    @Test
    fun `纯白、纯黑、噪声都不是二维码`() {
        assertNull(QrDecoder.decode(ByteArray(300 * 300) { 0xFF.toByte() }, 300, 300))
        assertNull(QrDecoder.decode(ByteArray(300 * 300), 300, 300))
        val noise = java.util.Random(7).let { random -> ByteArray(300 * 300) { random.nextInt(256).toByte() } }
        assertNull(QrDecoder.decode(noise, 300, 300))
    }

    @Test
    fun `只认二维码：别的条码（Code 128）即使完整清晰也不读`() {
        val writer: Writer = Code128Writer()
        val matrix = writer.encode("AURI-PAIRING", BarcodeFormat.CODE_128, 400, 120)
        assertNull(QrDecoder.decode(luminanceOf(matrix), matrix.width, matrix.height))
    }

    @Test
    fun `尺寸与数据对不上时返回 null，不抛越界异常`() {
        assertNull(QrDecoder.decode(ByteArray(10), 100, 100))
        assertNull(QrDecoder.decode(ByteArray(100), 0, 10))
        assertNull(QrDecoder.decode(ByteArray(100), 10, 0))
        assertNull(QrDecoder.decode(ByteArray(100), 10, 10, rowStride = 5))
        assertNull(QrDecoder.decode(ByteArray(0), 1, 1))
    }

    @Test
    fun `读出来的文字再交给载荷校验：二维码里不是配对载荷就被拒绝，而不是当成功`() {
        val matrix = qr("https://example.com/not-a-pairing-payload")
        val text = QrDecoder.decode(luminanceOf(matrix), matrix.width, matrix.height)!!
        assertTrue(PairingPayload.parse(text) is PayloadParse.Invalid)
        val real = QrDecoder.decode(luminanceOf(qr(payloadText)), 400, 400)!!
        assertTrue(PairingPayload.parse(real) is PayloadParse.Valid)
    }
}
