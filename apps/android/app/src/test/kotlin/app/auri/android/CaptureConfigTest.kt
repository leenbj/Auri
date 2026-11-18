package app.auri.android

import app.auri.capture.coordinator.CaptureConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 在分段（Ticket 18/20）之前，录音不能长过转录引擎一次能处理的时长，否则录完转不了：
 * 自动停止的上限跟着引擎声明的上限走，并留出读音频批次的余量。
 */
class CaptureConfigTest {
    @Test
    fun `自动停止的上限是引擎上限减去余量，录下的音频不会超过引擎能处理的长度`() {
        val config = captureConfigFor(30_000)

        assertEquals(29_500L, config.maxDurationMs)
        assertTrue("上限必须小于引擎上限", config.maxDurationMs < 30_000)
    }

    @Test
    fun `引擎没有时长上限就用协调器的默认值`() {
        assertEquals(CaptureConfig().maxDurationMs, captureConfigFor(null).maxDurationMs)
    }

    @Test
    fun `引擎上限小到不足以留余量时，不把上限减成零或负数`() {
        assertEquals(400L, captureConfigFor(400).maxDurationMs)
    }

    @Test
    fun `只改自动停止的时长，其余参数不动`() {
        val config = captureConfigFor(30_000)

        assertEquals(CaptureConfig().copy(maxDurationMs = 29_500), config)
    }
}
