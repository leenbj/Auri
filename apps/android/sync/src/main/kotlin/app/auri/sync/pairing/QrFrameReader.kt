package app.auri.sync.pairing

/** 取景的一帧灰度图（Y 平面）。平台层把 CameraX 的 ImageProxy 适配成它。 */
interface LuminanceFrame {
    val width: Int
    val height: Int

    /** 一行占多少字节（可能大于 [width]）。 */
    val rowStride: Int

    /** 灰度字节，每个像素一个。 */
    fun luminance(): ByteArray

    /** 用完必须关闭：取景管线要等这一帧关闭才会给下一帧，漏了一次预览就卡住。 */
    fun close()
}

/**
 * 逐帧读二维码：读到文字就交给 [onText]（由上层按配对载荷的严格规则校验，这里不解释、不记录）。
 * 一帧读失败（解码出异常、取灰度出异常、回调出异常）不影响下一帧，更不能让分析线程死掉；
 * 不管发生什么，**每一帧都恰好关闭一次**。
 */
class QrFrameReader(private val onText: (String) -> Unit) {
    fun read(frame: LuminanceFrame) {
        try {
            QrDecoder.decode(frame.luminance(), frame.width, frame.height, frame.rowStride)?.let(onText)
        } catch (_: Exception) {
            // 这一帧读不出来，等下一帧
        } finally {
            frame.close()
        }
    }
}
