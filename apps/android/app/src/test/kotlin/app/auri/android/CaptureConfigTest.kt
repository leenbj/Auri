package app.auri.android

import app.auri.capture.coordinator.CaptureConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 录音上限由用户设置决定；转录引擎的一次输入上限不再截短 Capture Session。
 */
class CaptureConfigTest {
    @Test
    fun `默认三十分钟不会被引擎的三十秒上限截短`() {
        assertEquals(1_800_000L, captureConfigFor(30).maxDurationMs)
    }

    @Test
    fun `最大可以设置一百二十分钟`() {
        assertEquals(7_200_000L, captureConfigFor(120).maxDurationMs)
    }

    @Test
    fun `超出范围的设置会被拒绝`() {
        assertThrows(IllegalArgumentException::class.java) { captureConfigFor(0) }
        assertThrows(IllegalArgumentException::class.java) { captureConfigFor(121) }
    }

    @Test
    fun `只改自动停止的时长，其余参数不动`() {
        val config = captureConfigFor(45)

        assertEquals(CaptureConfig().copy(maxDurationMs = 2_700_000), config)
    }
}
