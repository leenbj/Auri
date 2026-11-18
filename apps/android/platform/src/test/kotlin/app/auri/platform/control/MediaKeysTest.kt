package app.auri.platform.control

import app.auri.capture.control.KeyPhase
import app.auri.capture.control.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Android 键值 → 平台无关的原始输入（不依赖 android.view.KeyEvent，所以能在 JVM 里测）。
 * 重点是“拿不到的信息不要编”：没有事件时刻就没有标识也没有手势时刻；两个时钟对不上就不信事件时刻。
 */
class MediaKeysTest {
    private val ms = 1_000_000L

    @Test
    fun `媒体键值对应的种类：播放、暂停、播放暂停、耳机挂机、停止、下一首、上一首，其余都是 OTHER`() {
        assertEquals(MediaKind.PLAY, mediaKindOf(126))
        assertEquals(MediaKind.PAUSE, mediaKindOf(127))
        assertEquals(MediaKind.PLAY_PAUSE, mediaKindOf(85))
        assertEquals(MediaKind.HEADSET_HOOK, mediaKindOf(79))
        assertEquals(MediaKind.STOP, mediaKindOf(86))
        assertEquals(MediaKind.NEXT, mediaKindOf(87))
        assertEquals(MediaKind.PREVIOUS, mediaKindOf(88))
        assertEquals("音量加键不是媒体控制键", MediaKind.OTHER, mediaKindOf(24))
        assertEquals("快进不在合同的平台事件里", MediaKind.OTHER, mediaKindOf(90))
        assertEquals(MediaKind.OTHER, mediaKindOf(0))
    }

    private fun raw(
        keyCode: Int = 85,
        action: Int = 0,
        repeatCount: Int = 0,
        eventTimeMs: Long = 4_900,
        external: Boolean? = true,
        uptimeNowMs: Long = 5_000,
        receivedAtNs: Long = 5_000 * ms,
    ) = rawInputOf(keyCode, action, repeatCount, eventTimeMs, external, uptimeNowMs, receivedAtNs)

    @Test
    fun `按下、抬起、长按重复、其他动作分别是 DOWN、UP、REPEAT、REPEAT`() {
        assertEquals(KeyPhase.DOWN, raw(action = 0, repeatCount = 0).phase)
        assertEquals(KeyPhase.REPEAT, raw(action = 0, repeatCount = 1).phase)
        assertEquals(KeyPhase.UP, raw(action = 1).phase)
        assertEquals("ACTION_MULTIPLE 不是一次新的按下", KeyPhase.REPEAT, raw(action = 2).phase)
    }

    @Test
    fun `有事件时刻：原始标识由键值、动作与时刻组成；手势时刻是事件时刻换成纳秒`() {
        val input = raw(keyCode = 85, action = 0, eventTimeMs = 4_900)

        assertEquals("key:85:a:0:t:4900", input.rawId)
        assertEquals(4_900 * ms, input.issuedAtNs)
        assertEquals(5_000 * ms, input.receivedAtNs)
        assertEquals(MediaKind.PLAY_PAUSE, input.kind)
    }

    @Test
    fun `同一个事件重复递送标识相同；不同时刻的两次按键标识不同`() {
        assertEquals(raw(eventTimeMs = 4_000).rawId, raw(eventTimeMs = 4_000).rawId)
        assertNotEquals(raw(eventTimeMs = 4_000).rawId, raw(eventTimeMs = 4_500).rawId)
        assertNotEquals("按下与抬起是不同的事件", raw(action = 0).rawId, raw(action = 1).rawId)
    }

    @Test
    fun `没有事件时刻（合成的按键）：没有原始标识也没有手势时刻，绝不拿 0 当标识`() {
        val input = raw(eventTimeMs = 0)

        assertNull(input.rawId)
        assertNull(input.issuedAtNs)
    }

    @Test
    fun `系统时钟与应用的单调时钟对不上：不信事件时刻，手势时刻留空，标识仍然可用`() {
        // 应用时钟读数 5000ms，系统的 uptime 却是 9000ms：两个时钟不是同一个，换算出来的时刻不能用
        val input = raw(uptimeNowMs = 9_000, receivedAtNs = 5_000 * ms)

        assertNull(input.issuedAtNs)
        assertEquals("key:85:a:0:t:4900", input.rawId)
    }

    @Test
    fun `两个时钟差几毫秒是正常的（读数的先后），仍然信事件时刻`() {
        val input = raw(uptimeNowMs = 5_003, receivedAtNs = 5_000 * ms)

        assertEquals(4_900 * ms, input.issuedAtNs)
    }

    @Test
    fun `外接设备的判断原样带过去：true、false、null 三种都不改写`() {
        assertEquals(true, raw(external = true).fromExternalDevice)
        assertEquals(false, raw(external = false).fromExternalDevice)
        assertNull(raw(external = null).fromExternalDevice)
    }
}
