package app.auri.platform.control

import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.CaptureState
import app.auri.capture.contract.ControlSource
import app.auri.capture.contract.NormalizedControlEvent
import app.auri.capture.control.ControlConfig
import app.auri.capture.control.ControlProfile
import app.auri.capture.control.MediaKind
import app.auri.capture.coordinator.CaptureConfig
import app.auri.capture.coordinator.CaptureCoordinator
import app.auri.capture.coordinator.CaptureDecision
import app.auri.capture.coordinator.Cause
import app.auri.capture.machine.CaptureEvent
import app.auri.capture.machine.Outcome
import app.auri.capture.testing.CollectingOutput
import app.auri.capture.testing.FakeCaptureAdapter
import app.auri.capture.testing.FakeClock
import app.auri.capture.testing.FakeRecordingStore
import app.auri.capture.testing.SequentialIds
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 17 清单第 1～3 项：整条链都是真的——媒体按键（假的系统来源）→ 耳机适配器（归一化、去抖、时间戳）→
 * 合成适配器 → 真正的协调器与状态机。假的只有系统一侧（媒体来源、蓝牙采集、存储）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HeadsetControlChainTest {
    private class Rig(private val scope: TestScope, controlConfig: ControlConfig = ControlConfig()) {
        val clock = FakeClock(scope.testScheduler)
        val source = FakeMediaButtonSource(clock)
        val headset = HeadsetControlAdapter(source, SequentialIds(0x11), { ControlProfile.generic() }, controlConfig)
        val screen = ScreenControlAdapter(clock, SequentialIds(0x22))
        val control = CompositeControlAdapter(screen, headset)
        val capture = FakeCaptureAdapter()
        val store = FakeRecordingStore()
        val output = CollectingOutput()
        val coordinator = CaptureCoordinator(
            control = control,
            capture = capture,
            store = store,
            output = output,
            clock = clock,
            ids = SequentialIds(0x33),
            config = CaptureConfig(),
            scope = scope.backgroundScope,
            adapterDispatcher = StandardTestDispatcher(scope.testScheduler),
        )
        val decisions = CopyOnWriteArrayList<CaptureDecision>()

        /** 协调器之外再订阅一份：看到的就是归一化之后递送给协调器的那些事件。 */
        val events = CopyOnWriteArrayList<NormalizedControlEvent>()

        init {
            control.onCommand { events += it }
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { coordinator.decisions.toCollection(decisions) }
        }

        val state: CaptureState get() = coordinator.snapshot.value.state

        fun settle() = scope.testScheduler.runCurrent()

        fun advance(ms: Long) {
            scope.testScheduler.advanceTimeBy(ms)
            scope.testScheduler.runCurrent()
        }

        fun controlModeOn() {
            coordinator.enableControlMode()
            settle()
        }

        fun key(kind: MediaKind, issuedAtNs: Long? = null, external: Boolean? = true) {
            source.press(kind, issuedAtNs = issuedAtNs ?: clock.monotonicNs(), external = external)
            settle()
        }

        fun record() {
            key(MediaKind.PLAY_PAUSE)
            capture.verifiedRoute(epoch = 1)
            settle()
            assertEquals(CaptureState.RECORDING, state)
            capture.frames(samples = 1600, epoch = 1)
            settle()
        }
    }

    // ---- 清单第 1 项：控制模式关闭时，媒体按键不会开始录音 ----

    @Test
    fun `控制模式没开：媒体来源根本没有被激活，按键不触发任何东西`() = runTest {
        val rig = Rig(this)

        val delivered = rig.source.press(MediaKind.PLAY_PAUSE)
        rig.settle()

        assertFalse("没有激活，就没有人收听", delivered)
        assertEquals(0, rig.source.activateCalls)
        assertEquals(CaptureState.DISARMED, rig.state)
        assertTrue(rig.capture.requests.isEmpty())
        assertTrue(rig.events.isEmpty())
    }

    @Test
    fun `控制模式关闭之后：来源被释放，迟到的旧回调也不能开始录音`() = runTest {
        val rig = Rig(this)
        rig.controlModeOn()
        assertEquals(CaptureState.ARMED, rig.state)
        assertEquals(1, rig.source.activateCalls)

        rig.coordinator.disableControlMode()
        rig.settle()
        assertEquals(CaptureState.DISARMED, rig.state)
        assertEquals(1, rig.source.deactivateCalls)

        rig.source.pressViaStaleListener(0, MediaKind.PLAY_PAUSE)
        rig.settle()

        assertEquals(CaptureState.DISARMED, rig.state)
        assertTrue("没有发出任何输入请求", rig.capture.requests.isEmpty())
        assertTrue(rig.events.isEmpty())
    }

    @Test
    fun `对照：控制模式开着的时候，同一个按键会进入准备`() = runTest {
        val rig = Rig(this)
        rig.controlModeOn()

        rig.key(MediaKind.PLAY_PAUSE)

        assertEquals(CaptureState.ROUTING, rig.state)
        assertEquals(1, rig.capture.requests.size)
    }

    @Test
    fun `耳机控制开不起来时屏幕按钮仍然能开始：控制模式照样开启`() = runTest {
        val rig = Rig(this)
        rig.source.activateResult = false

        rig.controlModeOn()
        assertEquals("屏幕兜底还在，所以控制模式开启成功", CaptureState.ARMED, rig.state)

        rig.screen.pressStart()
        rig.settle()

        assertEquals(CaptureState.ROUTING, rig.state)
        assertEquals(ControlSource.SCREEN_CONTROL, rig.events.single().source)
    }

    // ---- 清单第 2 项：归一化事件带来源与序号；重复突发永远不会先开始再停止 ----

    @Test
    fun `进入协调器的事件带着来源、序号与档案；两个来源共用一套序号`() = runTest {
        val rig = Rig(this)
        rig.controlModeOn()

        rig.key(MediaKind.PLAY_PAUSE, external = null) // 系统不能证明来自外接设备
        rig.screen.pressStop() // 屏幕上取消准备
        rig.settle()
        rig.advance(1_000)
        rig.key(MediaKind.PLAY_PAUSE, external = null)

        assertEquals(listOf(0L, 1L, 2L), rig.events.map { it.sequence })
        assertEquals(
            listOf(ControlSource.UNKNOWN_REMOTE, ControlSource.SCREEN_CONTROL, ControlSource.UNKNOWN_REMOTE),
            rig.events.map { it.source },
        )
        assertTrue(rig.events.filter { it.source != ControlSource.SCREEN_CONTROL }.all { it.profileId == ControlProfile.GENERIC_ID })
    }

    @Test
    fun `一次手势发出一串事件：只开始一次，之后的录音不会被同一手势的余波停掉`() = runTest {
        val rig = Rig(this)
        rig.controlModeOn()

        rig.source.press(MediaKind.PLAY)
        rig.advance(20)
        rig.source.press(MediaKind.PAUSE)
        rig.advance(25)
        rig.source.press(MediaKind.PLAY_PAUSE)
        rig.advance(45)
        rig.source.press(MediaKind.STOP)
        rig.settle()

        assertEquals("只有第一个事件进了协调器", 1, rig.events.size)
        assertEquals(CaptureState.ROUTING, rig.state)
        assertEquals(1, rig.capture.requests.size)

        rig.capture.verifiedRoute(epoch = 1)
        rig.advance(3_000)
        assertEquals("同一手势的余波没有把它停掉", CaptureState.RECORDING, rig.state)
        val commandDecisions = rig.decisions.filter { it.trigger == CaptureEvent.START || it.trigger == CaptureEvent.STOP || it.trigger == CaptureEvent.TOGGLE }
        assertEquals("协调器只处理过一个控制命令", listOf(CaptureEvent.START), commandDecisions.map { it.trigger })
    }

    // ---- 清单第 3 项：准备中的停止不被吞掉；封存期间过时的切换不会开始下一段 ----

    @Test
    fun `准备中再按一次切换键：取消准备，回到待命，输入请求被释放`() = runTest {
        val rig = Rig(this)
        rig.controlModeOn()
        rig.key(MediaKind.PLAY_PAUSE)
        assertEquals(CaptureState.ROUTING, rig.state)
        rig.advance(1_000) // 路由还没确认，用户又做了一次手势

        rig.key(MediaKind.PLAY_PAUSE)

        assertEquals("这一次手势没有被去抖吞掉", CaptureState.ARMED, rig.state)
        assertEquals(1, rig.capture.releases.size)
        val cancel = rig.decisions.last { it.trigger == CaptureEvent.TOGGLE }
        assertEquals(CaptureState.ROUTING, cancel.stateBefore)
        assertEquals(CaptureState.ARMED, cancel.stateAfter)
    }

    @Test
    fun `准备中收到明确的暂停键：同样取消准备`() = runTest {
        val rig = Rig(this)
        rig.controlModeOn()
        rig.key(MediaKind.PLAY)
        rig.advance(1_000)

        rig.key(MediaKind.PAUSE)

        assertEquals(CaptureState.ARMED, rig.state)
        assertEquals(1, rig.capture.releases.size)
    }

    @Test
    fun `封存期间按下并在封存期间送到的切换键：被忽略，也不会排队到封存之后`() = runTest {
        val rig = Rig(this)
        rig.store.sealGate = CompletableDeferred()
        rig.controlModeOn()
        rig.record()
        rig.advance(2_000)
        rig.key(MediaKind.PLAY_PAUSE) // 停止
        assertEquals(CaptureState.FINALIZING, rig.state)

        rig.advance(1_000)
        rig.key(MediaKind.PLAY_PAUSE) // 封存还没完成，用户又按了一次
        assertEquals(CaptureState.FINALIZING, rig.state)
        assertEquals(Outcome.IGNORED, rig.decisions.last().outcome)

        rig.store.sealGate!!.complete(Unit)
        rig.settle()
        rig.advance(10_000)

        assertEquals("封存完成后待命，没有偷偷开始下一段", CaptureState.ARMED, rig.state)
        assertEquals(1, rig.capture.requests.size)
    }

    @Test
    fun `封存期间按下、封存完成之后才送到的切换键：带着按下的时刻，协调器当成过时命令丢掉`() = runTest {
        val rig = Rig(this)
        rig.store.sealGate = CompletableDeferred()
        rig.controlModeOn()
        rig.record()
        rig.advance(2_000)
        rig.key(MediaKind.PLAY_PAUSE) // 停止，进入封存
        assertEquals(CaptureState.FINALIZING, rig.state)

        rig.advance(600)
        val pressedWhileFinalizing = rig.clock.monotonicNs()
        rig.advance(300)
        rig.store.sealGate!!.complete(Unit)
        rig.settle()
        assertEquals(CaptureState.ARMED, rig.state)

        // 主线程卡住，那次按键直到封存完成之后才送到适配器：事件上带着它真正被按下的时刻
        rig.key(MediaKind.PLAY_PAUSE, issuedAtNs = pressedWhileFinalizing)

        assertEquals("过时的命令不能开始下一段", CaptureState.ARMED, rig.state)
        assertEquals(1, rig.capture.requests.size)
        val decision = rig.decisions.last()
        assertEquals(CaptureEvent.TOGGLE, decision.trigger)
        assertEquals(Outcome.IGNORED, decision.outcome)
        assertEquals(Cause.STALE_COMMAND, decision.cause)

        // 对照：封存完成之后新按下的切换键是有效的
        rig.advance(1_000)
        rig.key(MediaKind.PLAY_PAUSE)
        assertEquals(CaptureState.ROUTING, rig.state)
        assertEquals(2, rig.capture.requests.size)
    }

    @Test
    fun `start and toggle pressed while finalizing cannot restart after delivery over five seconds late`() = runTest {
        listOf(126, 85).forEach { keyCode ->
            val rig = Rig(this)
            rig.store.sealGate = CompletableDeferred()
            rig.controlModeOn()
            rig.record()
            rig.advance(1_000)
            rig.key(MediaKind.PLAY_PAUSE)
            assertEquals(CaptureState.FINALIZING, rig.state)
            rig.advance(100)
            val pressedAtMs = rig.clock.monotonicNs() / 1_000_000
            rig.advance(100)
            rig.store.sealGate!!.complete(Unit)
            rig.settle()
            rig.advance(6_000)

            assertTrue(rig.source.pressPlatformKey(keyCode, pressedAtMs))
            rig.settle()

            assertEquals("key $keyCode must not restart", CaptureState.ARMED, rig.state)
            assertEquals(1, rig.capture.requests.size)
        }
    }

    @Test
    fun `a stop delivered over five seconds late can still stop recording`() = runTest {
        val rig = Rig(this)
        rig.controlModeOn()
        rig.record()
        rig.advance(500)
        val pressedAtMs = rig.clock.monotonicNs() / 1_000_000
        rig.advance(6_000)

        assertTrue(rig.source.pressPlatformKey(86, pressedAtMs))
        rig.settle()

        assertEquals(CaptureState.ARMED, rig.state)
        assertEquals(1, rig.output.recordings.size)
    }

    @Test
    fun `start and toggle without trustworthy event times do not start recording`() = runTest {
        listOf(126, 85).forEach { keyCode ->
            val rig = Rig(this)
            rig.controlModeOn()
            assertTrue(rig.source.pressPlatformKey(keyCode, 0))
            rig.settle()
            assertEquals("key $keyCode with no event time", CaptureState.ARMED, rig.state)
            assertTrue(rig.source.pressPlatformKey(keyCode, rig.clock.monotonicNs() / 1_000_000 + 1_000))
            rig.settle()
            assertEquals("key $keyCode with future event time", CaptureState.ARMED, rig.state)
            assertTrue(rig.capture.requests.isEmpty())
        }
    }
}
