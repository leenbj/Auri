package app.auri.sync.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * 把摄像头的一帧灰度图读成二维码里的文字（ZXing core，固定版本，Apache-2.0）。只认 QR，别的条码一律不读；
 * 读出的文字只是“摄像头看到的某段字符串”，能不能信由 [PairingPayload.parse] 的严格校验说了算。
 * 深底浅码也试；画面转 0、90、180、270 度各试一遍：ZXing 3.5.4 对完整的配对载荷（约 380 字符）在顺时针转 90 度时会读不出来
 * （实测抛 FormatException，另外三个方向正常），而手机竖着拿时相机帧通常正是转了 90 度的。
 * 任何读不出来的情形都返回 null，不抛异常（取景回调里抛异常会让相机管线停掉）。
 */
object QrDecoder {
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    /** [luminance] 每个像素一个字节，一行占 [rowStride] 字节（CameraX 的 Y 平面行距可能大于宽度）。 */
    fun decode(luminance: ByteArray, width: Int, height: Int, rowStride: Int = width): String? {
        if (width <= 0 || height <= 0 || rowStride < width) return null
        if (luminance.size.toLong() < rowStride.toLong() * (height - 1) + width) return null
        // 先逐行拷成紧凑的灰度图（行距可能大于宽度），再每转 90 度读一遍。
        var pixels = ByteArray(width * height)
        for (y in 0 until height) System.arraycopy(luminance, y * rowStride, pixels, y * width, width)
        var w = width
        var h = height
        repeat(4) {
            val source = PlanarYUVLuminanceSource(pixels, w, h, 0, 0, w, h, false)
            (read(source) ?: read(source.invert()))?.let { return it }
            pixels = rotatedClockwise(pixels, w, h)
            val turnedWidth = h
            h = w
            w = turnedWidth
        }
        return null
    }

    private fun rotatedClockwise(pixels: ByteArray, width: Int, height: Int): ByteArray {
        val rotated = ByteArray(pixels.size)
        for (y in 0 until height) for (x in 0 until width) rotated[x * height + (height - 1 - y)] = pixels[y * width + x]
        return rotated
    }

    private fun read(source: LuminanceSource): String? = try {
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    } catch (_: ReaderException) {
        null
    }
}
