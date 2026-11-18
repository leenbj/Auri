package app.auri.platform.control

import app.auri.capture.control.RawMediaInput

/**
 * 系统的媒体按键通道：[HeadsetControlAdapter] 只通过它拿到按键，测试里换成假的。
 * 真实现是 [AndroidMediaButtonSource]（MediaSession）。两个方法都可能抛异常，调用方负责包住。
 */
interface MediaButtonSource {
    /** 向系统要通道并开始把按键交给 [listener]。要到了返回 true；系统不给返回 false。可在任意线程调用。 */
    fun activate(listener: (RawMediaInput) -> Unit): Boolean

    /** 释放通道：幂等，释放之后不得再回调。 */
    fun deactivate()
}
