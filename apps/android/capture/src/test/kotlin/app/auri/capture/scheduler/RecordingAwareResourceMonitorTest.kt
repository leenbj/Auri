package app.auri.capture.scheduler

import app.auri.capture.adapter.TranscriptionOutcome
import app.auri.capture.contract.CaptureState
import app.auri.capture.machine.TranscriptionState
import app.auri.capture.testing.FakeResourceMonitor
import app.auri.capture.testing.SchedulerRig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 录音优先（Ticket 20 “转录不能饿死录音”）：采集正在确认路由或录音时，调度器不开始新的转录，录音一结束再开始。
 * 已经在跑的转录停不下来（真引擎不能中途取消），所以这里挡的只是“再开始一个”；录音路径本身不受影响靠专用低优先级线程
 * 与“转录时不丢帧”的测试保证，这一层是在那之上再少一点 CPU 争用。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingAwareResourceMonitorTest {
    private val base = FakeResourceMonitor()
    private val capture = MutableStateFlow(CaptureState.ARMED)
    private val monitor = RecordingAwareResourceMonitor(base, capture)

    @Test
    fun `没在录：读数交给下层，下层说什么就是什么`() {
        for (state in listOf(CaptureState.DISARMED, CaptureState.ARMED, CaptureState.BLOCKED, CaptureState.FINALIZING)) {
            capture.value = state
            base.status = ResourceStatus.Ok
            assertEquals("$state", ResourceStatus.Ok, monitor.check())
            base.status = ResourceStatus.Low(ResourceReason.THERMAL)
            assertEquals("$state", ResourceStatus.Low(ResourceReason.THERMAL), monitor.check())
        }
    }

    @Test
    fun `确认路由与录音中都算在录：新的转录不开始，原因是录音中，而且录音优先于下层的原因`() {
        for (state in listOf(CaptureState.ROUTING, CaptureState.RECORDING)) {
            capture.value = state
            base.status = ResourceStatus.Ok
            assertEquals("$state", ResourceStatus.Low(ResourceReason.RECORDING_ACTIVE), monitor.check())
            base.status = ResourceStatus.Low(ResourceReason.THERMAL)
            assertEquals("录音优先：$state", ResourceStatus.Low(ResourceReason.RECORDING_ACTIVE), monitor.check())
        }
    }

    @Test
    fun `封存中不算在录：刚录完的那一段是在封存时提交的，不能让它被挡一下再放行`() = runTest {
        // 麦克风这时已经关了，CPU 争用伤不到录音；而且若把封存算进去，每段录音自己的第一次转录都会白白经过一次“等待”并多写几次盘
        val rig = SchedulerRig(this, monitor = RecordingAwareResourceMonitor(FakeResourceMonitor(), capture))
        val id = rig.addRecord(1)
        capture.value = CaptureState.FINALIZING

        rig.scheduler.submit(id)
        rig.settle()

        assertEquals(TranscriptionState.SUCCEEDED, rig.jobOf(id).state)
        assertTrue("没有经过等待资源", rig.decisionsOf(rig.jobOf(id).jobId).none { it.stateAfter == TranscriptionState.WAITING_RESOURCES })
    }

    @Test
    fun `开始录与结束录都发信号让调度器重问，别的状态变化不发，下层的信号照样转发`() = runTest {
        val signals = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { monitor.changes.collect { signals += it } }

        capture.value = CaptureState.ROUTING
        assertEquals("开始确认路由", 1, signals.size)
        capture.value = CaptureState.RECORDING
        assertEquals("ROUTING → RECORDING 都是在录，答案没变，不发", 1, signals.size)
        capture.value = CaptureState.FINALIZING
        assertEquals("录音结束", 2, signals.size)
        capture.value = CaptureState.ARMED
        assertEquals("FINALIZING → ARMED 答案没变，不发", 2, signals.size)
        capture.value = CaptureState.BLOCKED
        assertEquals(2, signals.size)

        base.low(ResourceReason.THERMAL)
        assertEquals("下层的信号照样转发", 3, signals.size)
    }

    @Test
    fun `接进调度器：录音期间提交的任务等着并说明原因，录音一结束自己开始转`() = runTest {
        val rig = SchedulerRig(this, monitor = RecordingAwareResourceMonitor(FakeResourceMonitor(), capture))
        val id = rig.addRecord(1)
        capture.value = CaptureState.RECORDING
        rig.settle()

        rig.scheduler.submit(id)
        rig.advance(60_000)

        assertEquals(TranscriptionState.WAITING_RESOURCES, rig.jobOf(id).state)
        assertEquals(WaitReason.Resources(ResourceReason.RECORDING_ACTIVE), rig.jobOf(id).wait)
        assertTrue("录音期间引擎一次也没被叫到", rig.adapter.requests.isEmpty())

        capture.value = CaptureState.FINALIZING // 录音结束
        rig.settle()

        assertEquals(TranscriptionState.SUCCEEDED, rig.jobOf(id).state)
        assertEquals(1, rig.library.get(id)!!.revisions.size)
    }

    @Test
    fun `接进调度器：转录已经在跑时开始录音，这一次转完，后面排队的等录音结束`() = runTest {
        val rig = SchedulerRig(this, monitor = RecordingAwareResourceMonitor(FakeResourceMonitor(), capture))
        val first = rig.addRecord(1)
        val second = rig.addRecord(2)
        val gate = rig.hold(first)
        rig.scheduler.submit(first)
        rig.scheduler.submit(second)
        rig.settle()
        assertEquals(TranscriptionState.RUNNING, rig.jobOf(first).state)

        capture.value = CaptureState.RECORDING
        rig.settle()

        assertEquals("停不下来的调用不去打断它", TranscriptionState.RUNNING, rig.jobOf(first).state)
        assertEquals(TranscriptionState.WAITING_RESOURCES, rig.jobOf(second).state)
        gate.complete(TranscriptionOutcome.Text("第一段", "zh", null))
        rig.settle()
        assertEquals(TranscriptionState.SUCCEEDED, rig.jobOf(first).state)
        assertEquals("录音还在继续，第二个任务仍然等着", TranscriptionState.WAITING_RESOURCES, rig.jobOf(second).state)

        capture.value = CaptureState.FINALIZING
        rig.settle()
        assertEquals(TranscriptionState.SUCCEEDED, rig.jobOf(second).state)
    }
}
