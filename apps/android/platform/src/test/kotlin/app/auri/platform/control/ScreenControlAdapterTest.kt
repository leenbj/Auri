package app.auri.platform.control

import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.ControlSource
import app.auri.capture.contract.NormalizedControlEvent
import app.auri.capture.contract.PlatformEvent
import app.auri.capture.coordinator.Clock
import app.auri.capture.testing.SequentialIds
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenControlAdapterTest {
    private class TickingClock : Clock {
        var ns = 5_000L
        override fun monotonicNs() = ns.also { ns += 1_000 }
        override fun now(): Instant = Instant.parse("2026-11-18T08:00:00Z")
    }

    private val adapter = ScreenControlAdapter(TickingClock(), SequentialIds())

    @Test
    fun `屏幕按钮递送的是合同里的归一化命令：来源是屏幕，没有 Headset Profile`() {
        val received = mutableListOf<NormalizedControlEvent>()
        adapter.onCommand { received += it }

        adapter.pressStart()
        adapter.pressStop()

        assertEquals(listOf(BusinessCommand.START, BusinessCommand.STOP), received.map { it.command })
        assertTrue(received.all { it.source == ControlSource.SCREEN_CONTROL && it.platformEvent == PlatformEvent.SCREEN_BUTTON })
        assertTrue("屏幕操作不经过耳机档案", received.all { it.profileId == null && it.profileVersion == null })
        assertEquals("单调时钟读数与序号都递增", listOf(5_000L, 6_000L), received.map { it.monotonicNs })
        assertEquals(listOf(0L, 1L), received.map { it.sequence })
        assertEquals("每个事件一个新的标识", 2, received.map { it.eventId }.toSet().size)
        assertNull(received.first().rawEventId)
    }

    @Test
    fun `取消订阅之后不再收到命令；屏幕控制始终可用`() {
        val received = mutableListOf<NormalizedControlEvent>()
        val subscription = adapter.onCommand { received += it }
        assertTrue(adapter.enableUserControlMode())

        subscription.cancel()
        adapter.pressStart()

        assertTrue(received.isEmpty())
    }
}
