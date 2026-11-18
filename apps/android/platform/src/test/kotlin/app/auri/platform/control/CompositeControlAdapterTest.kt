package app.auri.platform.control

import app.auri.capture.adapter.ControlAdapter
import app.auri.capture.adapter.Subscription
import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.ControlSource
import app.auri.capture.contract.NormalizedControlEvent
import app.auri.capture.contract.PlatformEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协调器只接一个控制适配器，所以屏幕按钮与耳机要合成一个。合成要保住两条：
 *  - 耳机控制开不起来时屏幕按钮仍然可用（手动兜底不能因耳机失败而丢）；
 *  - 两个来源的命令用同一套严格递增的序号递送给每个订阅者。
 */
class CompositeControlAdapterTest {
    private class StubAdapter(var enableResult: Boolean = true) : ControlAdapter {
        var enableCalls = 0
        var disableCalls = 0
        var enableThrows: RuntimeException? = null
        var disableThrows: RuntimeException? = null
        private val handlers = CopyOnWriteArrayList<(NormalizedControlEvent) -> Unit>()

        override fun enableUserControlMode(): Boolean {
            enableCalls += 1
            enableThrows?.let { throw it }
            return enableResult
        }

        override fun disableUserControlMode() {
            disableCalls += 1
            disableThrows?.let { throw it }
        }

        override fun onCommand(handler: (NormalizedControlEvent) -> Unit): Subscription {
            handlers += handler
            return Subscription { handlers -= handler }
        }

        val subscriberCount: Int get() = handlers.size

        fun emit(source: ControlSource, sequence: Long, command: BusinessCommand = BusinessCommand.TOGGLE): NormalizedControlEvent {
            val screen = source == ControlSource.SCREEN_CONTROL
            val event = NormalizedControlEvent(
                eventId = "%08x-0000-4000-8000-%012x".format(0xAB, sequence),
                source = source,
                platformEvent = if (screen) PlatformEvent.SCREEN_BUTTON else PlatformEvent.TOGGLE,
                command = command,
                monotonicNs = 1_000L + sequence,
                sequence = sequence,
                profileId = if (screen) null else "stub-profile",
                profileVersion = if (screen) null else 1,
                rawEventId = null,
            )
            handlers.forEach { it(event) }
            return event
        }
    }

    private val screen = StubAdapter()
    private val headset = StubAdapter()
    private val logs = mutableListOf<String>()
    private val composite = CompositeControlAdapter(screen, headset, logs::add)

    @Test
    fun `耳机控制开不起来，屏幕按钮仍然可用：开启成功`() {
        headset.enableResult = false
        assertTrue(composite.enableUserControlMode())

        headset.enableThrows = IllegalStateException("MediaSession 创建失败")
        assertTrue("框架异常也不能让屏幕兜底一起失败", composite.enableUserControlMode())
        assertTrue(logs.isNotEmpty())
    }

    @Test
    fun `两个来源都开不起来才是不可用`() {
        screen.enableResult = false
        headset.enableResult = false

        assertFalse(composite.enableUserControlMode())
    }

    @Test
    fun `屏幕那一半开不起来而耳机可用：开启成功`() {
        screen.enableResult = false

        assertTrue(composite.enableUserControlMode())
    }

    @Test
    fun `关闭时两个来源都被关闭，其中一个抛异常也不影响另一个，也不向外抛`() {
        headset.disableThrows = IllegalStateException("release 失败")

        composite.disableUserControlMode()

        assertEquals(1, headset.disableCalls)
        assertEquals("耳机那一半抛异常，屏幕那一半照样被关闭", 1, screen.disableCalls)
    }

    @Test
    fun `两个来源的命令用同一套严格递增的序号，每个订阅者收到的是同一个事件`() {
        val first = mutableListOf<NormalizedControlEvent>()
        val second = mutableListOf<NormalizedControlEvent>()
        composite.onCommand { first += it }
        composite.onCommand { second += it }

        screen.emit(ControlSource.SCREEN_CONTROL, sequence = 0)
        headset.emit(ControlSource.HEADSET_REMOTE, sequence = 0)
        screen.emit(ControlSource.SCREEN_CONTROL, sequence = 1)
        headset.emit(ControlSource.UNKNOWN_REMOTE, sequence = 1)

        assertEquals(listOf(0L, 1L, 2L, 3L), first.map { it.sequence })
        assertEquals(
            listOf(ControlSource.SCREEN_CONTROL, ControlSource.HEADSET_REMOTE, ControlSource.SCREEN_CONTROL, ControlSource.UNKNOWN_REMOTE),
            first.map { it.source },
        )
        assertEquals("所有订阅者看到同一个事件（含同一个序号）", first, second)
    }

    @Test
    fun `订阅者抛异常不影响其他订阅者；取消订阅后不再收到`() {
        val received = mutableListOf<NormalizedControlEvent>()
        composite.onCommand { error("订阅者的 bug") }
        val subscription = composite.onCommand { received += it }

        headset.emit(ControlSource.HEADSET_REMOTE, sequence = 0)
        subscription.cancel()
        headset.emit(ControlSource.HEADSET_REMOTE, sequence = 1)

        assertEquals(1, received.size)
    }

    @Test
    fun `旁听者先于所有命令订阅者收到事件，不管谁先登记（校准向导必须在协调器处理这个命令之前看到它）`() {
        val order = mutableListOf<String>()
        composite.onCommand { order += "命令订阅者#${it.sequence}" }
        composite.observe { order += "旁听者#${it.sequence}" }

        headset.emit(ControlSource.HEADSET_REMOTE, sequence = 0)

        assertEquals(listOf("旁听者#0", "命令订阅者#0"), order)
    }

    @Test
    fun `旁听者抛异常，命令照样递送；取消旁听后不再收到`() {
        val observed = mutableListOf<NormalizedControlEvent>()
        val delivered = mutableListOf<NormalizedControlEvent>()
        composite.observe { error("旁听者的 bug") }
        val subscription = composite.observe { observed += it }
        composite.onCommand { delivered += it }

        headset.emit(ControlSource.HEADSET_REMOTE, sequence = 0)
        subscription.cancel()
        headset.emit(ControlSource.HEADSET_REMOTE, sequence = 1)

        assertEquals("旁听者抛异常不能吞掉命令", 2, delivered.size)
        assertEquals(listOf(0L), observed.map { it.sequence })
        assertTrue(logs.any { it.startsWith("control_observer_failed") })
    }

    @Test
    fun `两个来源同时来事件：编号、旁听者、命令订阅者看到的是同一个先后顺序（校准向导按旁听顺序给决定配对，顺序错了会配错）`() {
        val observed = CopyOnWriteArrayList<Long>()
        val delivered = CopyOnWriteArrayList<Long>()
        val firstInObserver = CountDownLatch(1)
        val release = CountDownLatch(1)
        composite.observe { event ->
            observed += event.sequence
            if (event.sequence == 0L) {
                firstInObserver.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        composite.onCommand { delivered += it.sequence }

        val first = thread { screen.emit(ControlSource.SCREEN_CONTROL, sequence = 0) }
        val second = thread(start = false) { headset.emit(ControlSource.HEADSET_REMOTE, sequence = 0) }
        try {
            assertTrue("第一个事件已编号，卡在旁听者里", firstInObserver.await(5, TimeUnit.SECONDS))
            second.start()
            // 没有串行化时，第二个事件会越过第一个直接跑完（TERMINATED），先到达命令订阅者
            awaitBlockedOrDone(second)
            assertEquals("第二个事件必须被锁挡在第一个后面", Thread.State.BLOCKED, second.state)
        } finally {
            release.countDown()
        }
        first.join(5_000)
        second.join(5_000)

        assertEquals(listOf(0L, 1L), observed)
        assertEquals("命令订阅者的顺序必须和旁听者、和编号一致", listOf(0L, 1L), delivered)
    }

    @Test
    fun `编号也在锁里取：卡在“取得编号”这一步的事件，不会被另一个来源的事件超过——序号必须和递送的先后一致`() {
        val numbered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val counter = AtomicLong(0)
        val nextSequence = {
            val number = counter.getAndIncrement()
            if (number == 0L) {
                numbered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            number
        }
        val screenSource = StubAdapter()
        val headsetSource = StubAdapter()
        val own = CompositeControlAdapter(screenSource, headsetSource, nextSequence = nextSequence)
        val delivered = CopyOnWriteArrayList<Long>()
        own.onCommand { delivered += it.sequence }

        val first = thread { screenSource.emit(ControlSource.SCREEN_CONTROL, sequence = 0) }
        val second = thread(start = false) { headsetSource.emit(ControlSource.HEADSET_REMOTE, sequence = 0) }
        try {
            assertTrue("第一个事件已取得编号 0，卡在编号这一步", numbered.await(5, TimeUnit.SECONDS))
            second.start()
            // 编号若在锁外，第二个事件会取到 1、直接递送完（TERMINATED），先于编号 0 到达订阅者
            awaitBlockedOrDone(second)
            assertEquals("第二个事件必须被挡在第一个后面，连编号都不能先取", Thread.State.BLOCKED, second.state)
        } finally {
            release.countDown()
        }
        first.join(5_000)
        second.join(5_000)

        assertEquals("序号与递送的先后一致", listOf(0L, 1L), delivered)
    }

    @Test
    fun `合成只向每个来源订阅一次，不管有多少个订阅者`() {
        composite.onCommand { }
        composite.onCommand { }

        assertEquals(1, screen.subscriberCount)
        assertEquals(1, headset.subscriberCount)
    }
}
