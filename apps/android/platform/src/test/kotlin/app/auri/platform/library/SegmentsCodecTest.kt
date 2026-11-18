package app.auri.platform.library

import app.auri.capture.contract.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 语音片段在数据库里是一个文本列（"起-止;起-止"）。读回来要和写进去的一模一样，否则 Ticket 21 提交的 speech_segments
 * 会悄悄变形；空列表（确认了路由却没有语音的 Record）必须能存、能读回空列表。
 */
class SegmentsCodecTest {
    @Test
    fun `多个片段往返不变，顺序保留`() {
        val segments = listOf(TimeRange(800, 5_200), TimeRange(12_100, 16_900), TimeRange(16_900, 18_500))

        assertEquals(segments, decodeSegments(encodeSegments(segments)))
    }

    @Test
    fun `没有片段：存成空串，读回空列表`() {
        assertEquals("", encodeSegments(emptyList()))
        assertEquals(emptyList<TimeRange>(), decodeSegments(""))
    }

    @Test
    fun `起点为 0 的片段与超过 32 位的毫秒数都原样往返`() {
        val segments = listOf(TimeRange(0, 1), TimeRange(0, 5_000_000_000L))

        assertEquals(segments, decodeSegments(encodeSegments(segments)))
    }
}
