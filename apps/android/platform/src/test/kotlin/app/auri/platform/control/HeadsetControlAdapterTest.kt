package app.auri.platform.control

import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.ControlSource
import app.auri.capture.contract.NormalizedControlEvent
import app.auri.capture.contract.PlatformEvent
import app.auri.capture.control.ControlConfig
import app.auri.capture.control.ControlProfile
import app.auri.capture.control.HeadsetControlStatus
import app.auri.capture.control.KeyPhase
import app.auri.capture.control.MediaKind
import app.auri.capture.coordinator.Clock
import app.auri.capture.testing.SequentialIds
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 耳机控制适配器的边界规则：
 *  - 只在控制模式开启期间才接收媒体按键；关闭就释放来源，关闭之后（或上一次激活留下的）迟到回调什么都不触发；
 *  - 框架调用随时可能抛异常：包住、照样清理、照样到达终态，不向协调器抛；
 *  - 激活失败时如实说“耳机控制不可用”，而不是装作开着。
 */
class HeadsetControlAdapterTest {
    private class MutableClock : Clock {
        var ns = 1_000_000_000L
        override fun monotonicNs() = ns
        override fun now(): Instant = Instant.parse("2026-11-18T08:00:00Z")
        fun advanceMs(ms: Long) {
            ns += ms * 1_000_000L
        }
    }

    private val clock = MutableClock()
    private val source = FakeMediaButtonSource(clock)
    private var profile = ControlProfile.generic()
    private val logs = mutableListOf<String>()
    private val adapter = HeadsetControlAdapter(source, SequentialIds(), { profile }, ControlConfig(), logs::add)
    private val received = mutableListOf<NormalizedControlEvent>()

    private fun subscribe() = adapter.onCommand { received += it }

    @Test
    fun `控制模式没开：来源没有被激活，没有人收听媒体按键`() {
        subscribe()

        assertEquals(0, source.activateCalls)
        assertFalse("没有激活就没有人收听", source.press(MediaKind.PLAY_PAUSE))
        assertTrue(received.isEmpty())
        assertEquals(HeadsetControlStatus.OFF, adapter.status.value)
    }

    @Test
    fun `开启后按键变成带来源、序号与档案的命令；关闭后来源被释放，之后不再递送`() {
        subscribe()

        assertTrue(adapter.enableUserControlMode())
        assertEquals(HeadsetControlStatus.ACTIVE, adapter.status.value)
        source.press(MediaKind.PLAY_PAUSE, rawId = "key:85:t:1")
        clock.advanceMs(1_000)
        source.press(MediaKind.PAUSE, rawId = "key:127:t:2")

        assertEquals(listOf(BusinessCommand.TOGGLE, BusinessCommand.STOP), received.map { it.command })
        assertEquals(listOf(0L, 1L), received.map { it.sequence })
        assertTrue(received.all { it.source == ControlSource.HEADSET_REMOTE })
        assertTrue(received.all { it.profileId == ControlProfile.GENERIC_ID && it.profileVersion == 1 })
        assertEquals(PlatformEvent.TOGGLE, received.first().platformEvent)

        adapter.disableUserControlMode()

        assertEquals(1, source.deactivateCalls)
        assertEquals(HeadsetControlStatus.OFF, adapter.status.value)
        assertFalse(source.press(MediaKind.PLAY_PAUSE))
        assertEquals(2, received.size)
    }

    @Test
    fun `关闭之后才送到的旧回调什么都不触发；重新开启后，上一次激活的旧回调依然无效`() {
        subscribe()
        adapter.enableUserControlMode()
        adapter.disableUserControlMode()

        source.pressViaStaleListener(0, MediaKind.PLAY_PAUSE)
        assertTrue("关闭之后的迟到回调", received.isEmpty())

        adapter.enableUserControlMode()
        source.pressViaStaleListener(0, MediaKind.PLAY_PAUSE)
        assertTrue("新一次激活不接受上一次激活留下的回调", received.isEmpty())

        clock.advanceMs(1_000)
        source.press(MediaKind.PLAY_PAUSE)
        assertEquals(1, received.size)
    }

    @Test
    fun `激活失败：返回 false、状态是不可用，来源被清理；框架抛异常也一样，不向外抛`() {
        source.activateResult = false
        assertFalse(adapter.enableUserControlMode())
        assertEquals(HeadsetControlStatus.UNAVAILABLE, adapter.status.value)
        assertEquals("半成品也要释放", 1, source.deactivateCalls)

        source.activateResult = true
        source.activateThrows = IllegalStateException("MediaSession 创建失败")
        assertFalse(adapter.enableUserControlMode())
        assertEquals(HeadsetControlStatus.UNAVAILABLE, adapter.status.value)
        assertEquals(2, source.deactivateCalls)
        assertTrue("原因要留在日志里", logs.isNotEmpty())
    }

    @Test
    fun `激活失败之后还可以再试：下一次开启会重新向系统要会话`() {
        source.activateResult = false
        adapter.enableUserControlMode()
        source.activateResult = true

        assertTrue(adapter.enableUserControlMode())
        assertEquals(HeadsetControlStatus.ACTIVE, adapter.status.value)
        assertEquals(2, source.activateCalls)
    }

    @Test
    fun `释放时框架抛异常：照样关闭、不再递送、状态回到关闭，也不向外抛`() {
        subscribe()
        adapter.enableUserControlMode()
        source.deactivateThrows = IllegalStateException("release 失败")

        adapter.disableUserControlMode()

        assertEquals(HeadsetControlStatus.OFF, adapter.status.value)
        source.pressViaStaleListener(0, MediaKind.PLAY_PAUSE)
        assertTrue("释放失败也不能继续递送", received.isEmpty())
    }

    @Test
    fun `重复开启与重复关闭是幂等的：不会激活两次，也不会释放两次`() {
        assertTrue(adapter.enableUserControlMode())
        assertTrue(adapter.enableUserControlMode())
        assertEquals(1, source.activateCalls)

        adapter.disableUserControlMode()
        adapter.disableUserControlMode()
        assertEquals(1, source.deactivateCalls)
    }

    @Test
    fun `关闭再开启：去抖窗口清空，刚关闭前的按键不会吞掉重新开启后的第一次手势`() {
        subscribe()
        adapter.enableUserControlMode()
        source.press(MediaKind.PLAY_PAUSE)
        adapter.disableUserControlMode()
        adapter.enableUserControlMode()

        clock.advanceMs(10)
        source.press(MediaKind.PLAY_PAUSE)

        assertEquals(2, received.size)
        assertEquals("序号不因重新开启而重来", listOf(0L, 1L), received.map { it.sequence })
    }

    @Test
    fun `被去抖合并的按键不递送；抬起、长按重复、不认识的键也不递送`() {
        subscribe()
        adapter.enableUserControlMode()

        source.press(MediaKind.PLAY)
        clock.advanceMs(30)
        source.press(MediaKind.PAUSE)
        source.press(MediaKind.PLAY_PAUSE, phase = KeyPhase.UP)
        source.press(MediaKind.PLAY_PAUSE, phase = KeyPhase.REPEAT)
        source.press(MediaKind.OTHER)

        assertEquals(listOf(BusinessCommand.START), received.map { it.command })
    }

    @Test
    fun `档案是实时读取的：换成观察档案之后事件命令变成 IGNORE，换回去又恢复`() {
        subscribe()
        adapter.enableUserControlMode()

        profile = ControlProfile.generic().observeOnly()
        source.press(MediaKind.PLAY)
        clock.advanceMs(1_000)
        profile = ControlProfile.generic()
        source.press(MediaKind.PLAY)

        assertEquals(listOf(BusinessCommand.IGNORE, BusinessCommand.START), received.map { it.command })
    }

    @Test
    fun `一个订阅者抛异常不影响其他订阅者，也不影响之后的按键`() {
        adapter.onCommand { error("订阅者的 bug") }
        subscribe()
        adapter.enableUserControlMode()

        source.press(MediaKind.PLAY_PAUSE)
        clock.advanceMs(1_000)
        source.press(MediaKind.PLAY_PAUSE)

        assertEquals(2, received.size)
    }

    @Test
    fun `取消订阅之后不再收到命令`() {
        val subscription = subscribe()
        adapter.enableUserControlMode()

        subscription.cancel()
        source.press(MediaKind.PLAY_PAUSE)

        assertTrue(received.isEmpty())
    }
}
