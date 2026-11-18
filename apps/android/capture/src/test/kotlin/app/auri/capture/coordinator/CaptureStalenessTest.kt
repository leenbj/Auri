package app.auri.capture.coordinator

import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.CaptureState
import app.auri.capture.contract.CaptureStatus
import app.auri.capture.contract.ControlSource
import app.auri.capture.contract.EndReason
import app.auri.capture.contract.ErrorCode
import app.auri.capture.contract.ObservedInput
import app.auri.capture.contract.ObservedInputRouteType
import app.auri.capture.contract.RouteChangeEvent
import app.auri.capture.contract.VerificationState
import app.auri.capture.machine.CaptureEvent
import app.auri.capture.machine.Outcome
import app.auri.capture.testing.CaptureRig
import app.auri.capture.testing.StoreEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 16 第 5 项：迟到的回调、旧 generation 的写入、route_epoch 对不上的输入，都改变不了当前会话；
 * 封存期间过时的 TOGGLE 不会在封存之后开始下一段。每一条一个测试。
 * “旧回调”的造法：保留旧会话的 sink（取消之后它依然能被调用），让它对已经是别人的世界说话。
 */
class CaptureStalenessTest {
    private fun TestScope.armed(rig: CaptureRig = CaptureRig(this)): CaptureRig {
        rig.coordinator.enableControlMode()
        rig.settle()
        return rig
    }

    private fun CaptureRig.press(command: BusinessCommand, source: ControlSource = ControlSource.SCREEN_CONTROL, atNs: Long = clock.monotonicNs()) {
        control.press(command, source, atNs)
        settle()
    }

    private fun CaptureRig.startAndRecord(epoch: Long = 1) {
        press(BusinessCommand.START)
        capture.verifiedRoute(epoch = epoch)
        settle()
        assertEquals(CaptureState.RECORDING, state)
    }

    // ---- 取消之后迟到的回调 ----

    @Test
    fun `取消之后旧会话迟到的路由确认、帧、中断和不可用通知，都改变不了任何东西`() = runTest {
        val rig = armed()
        rig.press(BusinessCommand.START)
        rig.press(BusinessCommand.STOP) // 取消准备
        assertEquals(CaptureState.ARMED, rig.state)
        val before = rig.decisions.size

        rig.capture.verifiedRoute(epoch = 7, session = 0)
        rig.capture.frames(samples = 1600, epoch = 7, session = 0)
        rig.capture.interrupt(session = 0)
        rig.capture.sink(0).inputUnavailable(ErrorCode.INPUT_DEVICE_MISSING)
        rig.settle()

        assertEquals("状态没动", CaptureState.ARMED, rig.state)
        assertEquals("存储一次调用都没有", emptyList<StoreEvent>(), rig.store.events)
        assertTrue(rig.output.recordings.isEmpty())
        val late = rig.decisions.drop(before)
        assertEquals(listOf(CaptureEvent.ROUTE_VERIFIED, CaptureEvent.SYSTEM_INTERRUPTION, CaptureEvent.ROUTE_UNVERIFIED), late.map { it.trigger })
        assertTrue("三条全部被拒绝，原因是已经没有当前会话：$late", late.all { it.outcome == Outcome.REJECTED && it.cause == Cause.STALE_SESSION })
        assertEquals("迟到的帧只被计数丢弃", 1L, rig.coordinator.snapshot.value.counters.droppedStale)
    }

    @Test
    fun `取消时还没返回的准备，晚到之后会被关掉，不会留下一个开着的采集`() = runTest {
        val rig = armed()
        val release = CompletableDeferred<Unit>()
        // 一个不响应取消的适配器：取消之后它的准备才返回
        rig.capture.prepareHook = { withContext(NonCancellable) { release.await() } }
        rig.press(BusinessCommand.START)
        rig.press(BusinessCommand.STOP)
        val operation = rig.capture.requests.single().operationId
        assertEquals("取消时已经释放过一次", listOf(operation), rig.capture.releases)

        release.complete(Unit)
        rig.settle()

        assertEquals("晚到的准备结果所属的会话已取消：再关一次", listOf(operation, operation), rig.capture.releases)
        assertEquals(CaptureState.ARMED, rig.state)
        assertEquals(emptyList<StoreEvent>(), rig.store.events)
    }

    // ---- 旧 generation 的写入 ----

    @Test
    fun `旧 generation 的帧、路由与中断，改变不了正在录的新会话`() = runTest {
        val rig = armed()
        rig.press(BusinessCommand.START) // 会话 0
        rig.press(BusinessCommand.STOP)
        rig.startAndRecord(epoch = 1) // 会话 1，已确认 epoch 1，正在录
        rig.capture.frames(samples = 1600, epoch = 1, session = 1)
        rig.settle()
        val handleBefore = rig.coordinator.snapshot.value.session
        val before = rig.decisions.size

        // 旧会话（generation 更小）用恰好相同的 epoch 来写：不该进存储
        rig.capture.frames(samples = 9999, epoch = 1, session = 0)
        rig.capture.verifiedRoute(epoch = 1, session = 0)
        rig.capture.mismatchRoute(epoch = 2, session = 0)
        rig.capture.interrupt(session = 0)
        rig.settle()

        assertEquals("新会话照常在录", CaptureState.RECORDING, rig.state)
        assertEquals("存储里只有新会话自己的 1600 个采样", 1600, rig.store.writtenSamples)
        assertEquals(handleBefore, rig.coordinator.snapshot.value.session)
        val late = rig.decisions.drop(before)
        assertEquals(3, late.size)
        assertTrue("旧回调被拒绝，原因是 generation 更旧：$late", late.all { it.outcome == Outcome.REJECTED && it.cause == Cause.STALE_GENERATION })
        assertEquals(1L, rig.coordinator.snapshot.value.counters.droppedStale)

        // 之后新会话还能正常写、正常结束
        rig.capture.frames(samples = 1600, epoch = 1, session = 1)
        rig.press(BusinessCommand.STOP)
        assertEquals(3200L, rig.output.recordings.single().audio?.sampleCount)
    }

    @Test
    fun `封存中的会话收到旧回调：不影响封存，不会重新开闸`() = runTest {
        val rig = armed()
        rig.store.sealGate = CompletableDeferred()
        rig.startAndRecord(epoch = 1)
        rig.capture.frames(samples = 1600, epoch = 1)
        rig.press(BusinessCommand.STOP)
        assertEquals(CaptureState.FINALIZING, rig.state)

        rig.capture.frames(samples = 1600, epoch = 1) // 闸门已关
        rig.capture.verifiedRoute(epoch = 2) // 封存期间的“迟到确认”
        rig.settle()
        assertEquals(CaptureState.FINALIZING, rig.state)
        assertEquals("闸门关了之后的帧一个也没写", 1600, rig.store.writtenSamples)

        rig.store.sealGate!!.complete(Unit)
        rig.settle()
        assertEquals(CaptureState.ARMED, rig.state)
        assertEquals(1600L, rig.output.recordings.single().audio?.sampleCount)
    }

    // ---- route_epoch 对不上 ----

    @Test
    fun `录音中 epoch 不符的帧被丢弃，不够新的 epoch 被拒绝，已确认的句柄不变`() = runTest {
        val rig = armed()
        rig.startAndRecord(epoch = 3)
        rig.capture.frames(samples = 1600, epoch = 3)
        rig.settle()
        val before = rig.decisions.size

        rig.capture.frames(samples = 1600, epoch = 2) // 比已确认的旧
        rig.capture.frames(samples = 1600, epoch = 4) // 比已确认的新（路由其实变了，但事件还没到）
        rig.capture.route(VerificationState.VERIFIED, ObservedInputRouteType.BLUETOOTH_HFP, epoch = 3) // 重复的旧观测
        rig.capture.route(VerificationState.MISMATCH, ObservedInputRouteType.BUILT_IN_MIC, epoch = 1) // 更旧的观测
        rig.settle()

        assertEquals("epoch 不符的输入改变不了会话", CaptureState.RECORDING, rig.state)
        assertEquals(3L, rig.coordinator.snapshot.value.session?.verifiedRouteEpoch)
        assertEquals("只有 epoch 对得上的 1600 个采样被写入", 1600, rig.store.writtenSamples)
        assertEquals(2L, rig.coordinator.snapshot.value.counters.droppedGate)
        val rejected = rig.decisions.drop(before)
        assertEquals(2, rejected.size)
        assertTrue("$rejected", rejected.all { it.outcome == Outcome.REJECTED && it.cause == Cause.STALE_EPOCH })
    }

    @Test
    fun `准备阶段 epoch 更旧的确认被拒绝，仍可用更新的 epoch 确认`() = runTest {
        val rig = armed()
        rig.press(BusinessCommand.START)
        rig.capture.route(VerificationState.UNKNOWN, ObservedInputRouteType.UNKNOWN, epoch = 3)
        rig.capture.route(VerificationState.VERIFIED, ObservedInputRouteType.BLUETOOTH_HFP, epoch = 2) // 比刚见过的旧
        rig.settle()

        assertEquals("更旧的确认不能开闸", CaptureState.ROUTING, rig.state)
        assertEquals(emptyList<StoreEvent>(), rig.store.events)
        assertEquals(Cause.STALE_EPOCH, rig.decisions.last().cause)

        rig.capture.route(VerificationState.VERIFIED, ObservedInputRouteType.BLUETOOTH_HFP, epoch = 4)
        rig.settle()
        assertEquals(CaptureState.RECORDING, rig.state)
        assertEquals("句柄记的是确认时的 epoch", 4L, rig.coordinator.snapshot.value.session?.verifiedRouteEpoch)
    }

    @Test
    fun `路由观测里写的会话标识不是当前会话：被拒绝，不能开闸`() = runTest {
        val rig = armed()
        rig.press(BusinessCommand.START)
        val foreign = RouteChangeEvent(
            captureSessionId = "ffffffff-0000-4000-8000-000000000001",
            epoch = 1,
            observedInput = ObservedInput(ObservedInputRouteType.BLUETOOTH_HFP, null),
            observedAtMonotonicNs = 0,
            verificationState = VerificationState.VERIFIED,
        )

        rig.capture.sink().routeChanged(foreign)
        rig.settle()

        assertEquals(CaptureState.ROUTING, rig.state)
        assertEquals(Cause.STALE_SESSION, rig.decisions.last().cause)
        assertEquals(emptyList<StoreEvent>(), rig.store.events)
    }

    // ---- 封存期间过时的 TOGGLE ----

    @Test
    fun `封存期间收到的 TOGGLE 被忽略，封存完成后不会自动开始下一段`() = runTest {
        val rig = armed()
        rig.store.sealGate = CompletableDeferred()
        rig.startAndRecord(epoch = 1)
        rig.capture.frames(samples = 1600, epoch = 1)
        rig.press(BusinessCommand.TOGGLE, ControlSource.HEADSET_REMOTE) // 停止
        assertEquals(CaptureState.FINALIZING, rig.state)

        rig.press(BusinessCommand.TOGGLE, ControlSource.HEADSET_REMOTE) // 过时的 TOGGLE
        assertEquals(CaptureState.FINALIZING, rig.state)
        assertEquals(Outcome.IGNORED, rig.decisions.last().outcome)

        rig.store.sealGate!!.complete(Unit)
        rig.settle()
        rig.advance(10_000)

        assertEquals("封存完成后回到待命，没有偷偷开始下一段", CaptureState.ARMED, rig.state)
        assertEquals(1, rig.capture.requests.size)
        assertEquals(EndReason.HEADSET_COMMAND, rig.output.recordings.single().result.endReason)
    }

    @Test
    fun `封存期间发出、封存完成之后才送到的 TOGGLE 同样被丢弃，不开始下一段`() = runTest {
        val rig = armed()
        rig.store.sealGate = CompletableDeferred()
        rig.startAndRecord(epoch = 1)
        rig.capture.frames(samples = 1600, epoch = 1)
        rig.press(BusinessCommand.STOP)
        rig.advance(30)
        val issuedWhileFinalizing = rig.clock.monotonicNs()
        rig.advance(30)
        rig.store.sealGate!!.complete(Unit)
        rig.settle()
        assertEquals(CaptureState.ARMED, rig.state)

        // 适配器把封存期间按下的 TOGGLE 拖到封存之后才递送
        rig.press(BusinessCommand.TOGGLE, ControlSource.HEADSET_REMOTE, atNs = issuedWhileFinalizing)

        assertEquals("过时的命令不能开始下一段", CaptureState.ARMED, rig.state)
        assertEquals(1, rig.capture.requests.size)
        val decision = rig.decisions.last()
        assertEquals(CaptureEvent.TOGGLE, decision.trigger)
        assertEquals(Outcome.IGNORED, decision.outcome)
        assertEquals(Cause.STALE_COMMAND, decision.cause)

        // 封存完成之后新按下的 TOGGLE 是有效的
        rig.advance(1)
        rig.press(BusinessCommand.TOGGLE, ControlSource.HEADSET_REMOTE)
        assertEquals(CaptureState.ROUTING, rig.state)
        assertEquals(2, rig.capture.requests.size)
    }

    // ---- 会话被重置之后晚到的封存结果 ----

    @Test
    fun `进程恢复把采集重置之后，晚到的封存结果照样交出去，不改变状态`() = runTest {
        val rig = armed()
        rig.store.sealGate = CompletableDeferred()
        rig.startAndRecord(epoch = 1)
        rig.capture.frames(samples = 1600, epoch = 1)
        rig.settle()

        rig.coordinator.processRestored()
        rig.settle()
        assertEquals(CaptureState.DISARMED, rig.state)
        assertTrue("封存还没做完，结果还没有", rig.output.recordings.isEmpty())

        rig.store.sealGate!!.complete(Unit)
        rig.settle()

        assertEquals("采集仍在 DISARMED，不因为晚到的封存结果而改变", CaptureState.DISARMED, rig.state)
        val sealed = rig.output.recordings.single()
        assertEquals("已经落盘的音频不能悄悄丢掉", 1600L, sealed.audio?.sampleCount)
        assertEquals(EndReason.PROCESS_TERMINATED, sealed.result.endReason)
        assertEquals(CaptureStatus.PARTIAL, sealed.result.status)
        assertEquals(Outcome.REJECTED, rig.decisions.last().outcome)
        assertEquals(Cause.STALE_SESSION, rig.decisions.last().cause)
    }
}
