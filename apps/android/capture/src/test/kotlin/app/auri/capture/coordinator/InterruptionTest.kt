package app.auri.capture.coordinator

import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.CaptureState
import app.auri.capture.contract.CaptureStatus
import app.auri.capture.contract.ContractRules
import app.auri.capture.contract.DiscardReason
import app.auri.capture.contract.DiscardedRange
import app.auri.capture.contract.EndReason
import app.auri.capture.contract.ErrorCode
import app.auri.capture.machine.CaptureEvent
import app.auri.capture.testing.CaptureRig
import app.auri.capture.testing.StoreEvent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 19 第 1、2、5、6 项（协调器这一层）：录音中被打断时怎么收场，以及打断之后绝不自己续录。
 *
 * 每个测试都跑真正的协调器，只有系统边缘（适配器、存储）是假的；“绝不续录”不是断言某个布尔值，而是把所有可能把它唤醒的东西
 * （旧会话的路由确认与帧、重复的中断、权限、一个小时的时间）都递一遍，再数适配器收到的请求、存储收到的 begin。
 */
class InterruptionTest {
    private fun TestScope.recording(samples: Int = 1600, epoch: Long = 1): CaptureRig {
        val rig = CaptureRig(this)
        rig.coordinator.enableControlMode()
        rig.settle()
        rig.control.press(BusinessCommand.START)
        rig.settle()
        rig.capture.verifiedRoute(epoch = epoch)
        rig.settle()
        if (samples > 0) rig.capture.frames(samples = samples, epoch = epoch)
        rig.settle()
        assertEquals(CaptureState.RECORDING, rig.state)
        return rig
    }

    // ---- 第 1 项：路由变化 / 耳机断开 ----

    @Test
    fun `路由变化：只封存已确认的整窗音频，跨越变化的那一点尾巴丢弃，并记成 route_unverified`() = runTest {
        val rig = recording(samples = 1605) // 100 ms 加 5 个采样：这 5 个还在 VAD 的分析窗里，不知道属于变化之前还是之后
        rig.advance(250)

        rig.capture.mismatchRoute(epoch = 2) // 系统把输入换成了手机麦克风
        rig.capture.frames(samples = 1600, epoch = 2) // 变化之后的帧：不属于已确认的路由
        rig.capture.frames(samples = 1600, epoch = 1) // 旧 epoch 迟到的帧：闸门已经关了
        rig.settle()

        val sealed = rig.output.recordings.single()
        assertEquals(EndReason.ROUTE_LOST, sealed.result.endReason)
        assertEquals(CaptureStatus.PARTIAL, sealed.result.status)
        assertEquals("尾巴里那 5 个采样没有写", 1600L, sealed.audio?.sampleCount)
        assertEquals("存储收到的采样也只有已确认的整窗", 1600, rig.store.writtenSamples)
        assertEquals(250L, sealed.result.captureDurationMs)
        assertEquals(
            "从最后一个确认的整窗到变化的那一刻，整段记成 route_unverified，不能混进静音",
            listOf(DiscardedRange(100, 250, DiscardReason.ROUTE_UNVERIFIED)),
            sealed.result.discardedRanges,
        )
        assertEquals(emptyList<String>(), ContractRules.violations(sealed.result))
        assertEquals("采集只释放一次", listOf(rig.capture.requests.single().operationId), rig.capture.releases)
        assertEquals(CaptureState.ARMED, rig.state)
    }

    @Test
    fun `耳机断开（系统说输入没了）：同样立刻关闸，截断并保留已确认的部分，原因写在快照里`() = runTest {
        val rig = recording(samples = 1605)
        rig.advance(80)

        rig.capture.sink().inputUnavailable(ErrorCode.INPUT_DEVICE_MISSING)
        rig.capture.frames(samples = 1600, epoch = 1) // 断开之后才送到的帧
        rig.settle()

        val sealed = rig.output.recordings.single()
        assertEquals(EndReason.ROUTE_LOST, sealed.result.endReason)
        assertEquals(CaptureStatus.PARTIAL, sealed.result.status)
        assertEquals(1600L, sealed.audio?.sampleCount)
        assertEquals(DiscardReason.ROUTE_UNVERIFIED, sealed.result.discardedRanges.last().reason)
        assertEquals(ErrorCode.INPUT_DEVICE_MISSING, rig.coordinator.snapshot.value.interruption)
        assertEquals(EndReason.ROUTE_LOST, rig.coordinator.snapshot.value.interruptionReason)
        assertEquals(CaptureState.ARMED, rig.state)
    }

    @Test
    fun `路由其实已经变了、路由事件还没到：epoch 对不上的帧一个采样都不写，事件到了之后照常按路由丢失收场`() = runTest {
        val rig = recording(samples = 1600)
        val droppedBefore = rig.coordinator.snapshot.value.counters.droppedGate

        rig.capture.frames(samples = 1600, epoch = 2) // 适配器已经给新路由的帧盖了新 epoch，但路由观测事件还在路上
        rig.settle()
        assertEquals("这些帧自己不会让会话收场", CaptureState.RECORDING, rig.state)
        assertEquals("一个采样都不能写：不知道它们属于哪条路由", 1600, rig.store.writtenSamples)
        assertEquals("记成闸门丢弃", droppedBefore + 1, rig.coordinator.snapshot.value.counters.droppedGate)

        rig.capture.mismatchRoute(epoch = 2)
        rig.settle()

        val sealed = rig.output.recordings.single()
        assertEquals(EndReason.ROUTE_LOST, sealed.result.endReason)
        assertEquals("封存的只有变化之前已确认的那部分", 1600L, sealed.audio?.sampleCount)
        assertEquals(1600, rig.store.writtenSamples)
    }

    @Test
    fun `正常停止时分析窗里那点尾巴照常保留：只有路由丢失才丢，别的原因没有被动`() = runTest {
        val rig = recording(samples = 1605)

        rig.control.press(BusinessCommand.STOP)
        rig.settle()

        val sealed = rig.output.recordings.single()
        assertEquals(EndReason.SCREEN_CONTROL, sealed.result.endReason)
        assertEquals("1605 个采样都在", 1605L, sealed.audio?.sampleCount)
        assertTrue(sealed.result.discardedRanges.none { it.reason == DiscardReason.ROUTE_UNVERIFIED })
        assertNull(rig.coordinator.snapshot.value.interruption)
        assertNull("用户停的不是打断", rig.coordinator.snapshot.value.interruptionReason)
    }

    // ---- 第 2 项：任何中断之后都不会自己续录 ----

    private class Interruption(val name: String, val endReason: EndReason, val code: ErrorCode, val trigger: (CaptureRig) -> Unit)

    private val interruptions = listOf(
        Interruption("路由变成手机麦克风", EndReason.ROUTE_LOST, ErrorCode.INPUT_ROUTE_CHANGED) { it.capture.mismatchRoute(epoch = 2) },
        Interruption("耳机断开", EndReason.ROUTE_LOST, ErrorCode.INPUT_DEVICE_MISSING) { it.capture.sink().inputUnavailable(ErrorCode.INPUT_DEVICE_MISSING) },
        Interruption("来电或音频焦点被抢", EndReason.SYSTEM_INTERRUPTION, ErrorCode.AUDIO_INTERRUPTED) { it.capture.interrupt() },
        Interruption("麦克风权限被撤销", EndReason.PERMISSION_REVOKED, ErrorCode.PERMISSION_MIC_DENIED) { it.capture.revokePermission() },
        Interruption("蓝牙权限被撤销", EndReason.PERMISSION_REVOKED, ErrorCode.INPUT_DEVICE_MISSING) { it.capture.revokePermission(ErrorCode.INPUT_DEVICE_MISSING) },
        Interruption("存储写满", EndReason.SYSTEM_INTERRUPTION, ErrorCode.STORAGE_EXHAUSTED) {
            it.store.failAppendAfterSamples = 1600
            it.capture.frames(samples = 1600, epoch = 1)
        },
        Interruption("前台服务没了", EndReason.SYSTEM_INTERRUPTION, ErrorCode.BACKGROUND_START_DENIED) { it.coordinator.foregroundServiceFailed() },
    )

    @Test
    fun `每一种中断：关闸封存已确认的部分，回到待命；把所有可能唤醒它的东西都递一遍也不会续录，只有用户重新开始才有新的录音`() = runTest {
        for (case in interruptions) {
            val rig = recording()
            val first = rig.capture.requests.single()

            case.trigger(rig)
            rig.settle()

            assertEquals(case.name, CaptureState.ARMED, rig.state)
            val sealed = rig.output.recordings.single()
            assertEquals(case.name, case.endReason, sealed.result.endReason)
            assertEquals(case.name, CaptureStatus.PARTIAL, sealed.result.status)
            assertEquals("${case.name}：已确认的 100 ms 保留下来", 1600L, sealed.audio?.sampleCount)
            assertEquals("${case.name}：界面看得到原因", case.code, rig.coordinator.snapshot.value.interruption)
            assertEquals("${case.name}：以及结束原因（同一个错误码可以对应不同的原因）", case.endReason, rig.coordinator.snapshot.value.interruptionReason)
            assertEquals("${case.name}：采集资源只释放一次", listOf(first.operationId), rig.capture.releases)
            assertEquals("${case.name}：写入只开过一次", 1, rig.store.events.count { it is StoreEvent.Begin })

            // 旧会话的路由重新确认（耳机又连上了）、新的帧、重复的中断、一个小时的流逝：都唤醒不了任何东西
            rig.capture.verifiedRoute(epoch = 9, session = 0)
            rig.capture.frames(samples = 1600, epoch = 9, session = 0)
            rig.capture.interrupt(session = 0)
            rig.settle()
            rig.advance(3_600_000)

            assertEquals(case.name, CaptureState.ARMED, rig.state)
            assertEquals("${case.name}：没有再向系统请求输入", 1, rig.capture.requests.size)
            assertEquals("${case.name}：没有再开写入", 1, rig.store.events.count { it is StoreEvent.Begin })
            assertEquals("${case.name}：没有第二个录音", 1, rig.output.recordings.size)
            assertEquals(case.name, null, rig.coordinator.snapshot.value.session)

            // 用户重新按开始：新的会话、新的录音；上一次的中断提示随之清除
            rig.store.failAppendAfterSamples = null
            rig.control.press(BusinessCommand.START)
            rig.settle()
            assertEquals(case.name, CaptureState.ROUTING, rig.state)
            assertNull("${case.name}：新一次开始时清除", rig.coordinator.snapshot.value.interruption)
            assertNull("${case.name}：结束原因也清除", rig.coordinator.snapshot.value.interruptionReason)
            rig.capture.verifiedRoute(epoch = 1)
            rig.settle()
            rig.capture.frames(samples = 1600, epoch = 1)
            rig.control.press(BusinessCommand.STOP)
            rig.settle()

            assertEquals(case.name, 2, rig.capture.requests.size)
            assertNotEquals(case.name, first.captureSessionId, rig.capture.requests.last().captureSessionId)
            assertEquals(case.name, 2, rig.output.recordings.size)
            assertEquals(case.name, EndReason.SCREEN_CONTROL, rig.output.recordings.last().result.endReason)
            assertNotEquals(case.name, sealed.result.recordId, rig.output.recordings.last().result.recordId)
        }
    }

    @Test
    fun `同时来了好几个中断：只收场一次，只出一个录音，采集只释放一次`() = runTest {
        val rig = recording()

        rig.capture.mismatchRoute(epoch = 2)
        rig.capture.interrupt()
        rig.capture.revokePermission()
        rig.coordinator.foregroundServiceFailed()
        rig.settle()

        assertEquals(CaptureState.ARMED, rig.state)
        assertEquals(1, rig.output.recordings.size)
        assertEquals(EndReason.ROUTE_LOST, rig.output.recordings.single().result.endReason)
        assertEquals(1, rig.capture.releases.size)
        assertEquals(1, rig.decisions.count { it.trigger == CaptureEvent.ROUTE_LOST && it.stateAfter == CaptureState.FINALIZING })
    }

    @Test
    fun `准备阶段被打断：不开始，受阻并说明是被占用，之后迟到的路由确认不会再开闸`() = runTest {
        val rig = CaptureRig(this)
        rig.coordinator.enableControlMode()
        rig.settle()
        rig.control.press(BusinessCommand.START)
        rig.settle()
        assertEquals(CaptureState.ROUTING, rig.state)

        rig.capture.interrupt() // 刚按下开始就来了电话
        rig.settle()
        rig.capture.verifiedRoute(epoch = 1) // 这个会话已经作废，它迟到的确认不能开闸
        rig.capture.frames(samples = 1600, epoch = 1)
        rig.settle()

        assertEquals(CaptureState.BLOCKED, rig.state)
        assertEquals(ErrorCode.AUDIO_INTERRUPTED, rig.coordinator.snapshot.value.blockedReason)
        assertEquals("存储一次调用都没有", emptyList<StoreEvent>(), rig.store.events)
        assertTrue(rig.output.recordings.isEmpty())
        assertEquals("采集被释放", listOf(rig.capture.requests.single().operationId), rig.capture.releases)
    }

    // ---- 第 5、6 项：写不下、存储适配器自己出错 ----

    @Test
    fun `存储适配器抛出不是 IOException 的异常：同样安全收场，不会卡在录音中，已写的部分保留`() = runTest {
        val rig = recording()
        rig.store.failAppendUnexpectedly = IllegalStateException("存储适配器的 bug（测试注入）")

        rig.capture.frames(samples = 1600, epoch = 1)
        rig.capture.frames(samples = 1600, epoch = 1)
        rig.settle()

        assertEquals(CaptureState.ARMED, rig.state)
        val sealed = rig.output.recordings.single()
        assertEquals(EndReason.SYSTEM_INTERRUPTION, sealed.result.endReason)
        assertEquals(CaptureStatus.PARTIAL, sealed.result.status)
        assertEquals(1600L, sealed.audio?.sampleCount)
        assertEquals(Cause.INTERNAL_ERROR, rig.decisions.first { it.trigger == CaptureEvent.SYSTEM_INTERRUPTION }.cause)
        assertEquals(listOf(rig.capture.requests.single().operationId), rig.capture.releases)
    }

    @Test
    fun `用户停止时最后那点音频写不下：不当作正常结束，按系统中断记录，并说明是存储`() = runTest {
        val rig = recording(samples = 1605)
        rig.store.failAppendAfterSamples = 1600 // 分析窗里的 5 个采样在停止时才写，写不下

        rig.control.press(BusinessCommand.STOP)
        rig.settle()

        val sealed = rig.output.recordings.single()
        assertEquals(EndReason.SYSTEM_INTERRUPTION, sealed.result.endReason)
        assertEquals(CaptureStatus.PARTIAL, sealed.result.status)
        assertEquals(1600L, sealed.audio?.sampleCount)
        assertEquals(ErrorCode.STORAGE_EXHAUSTED, rig.coordinator.snapshot.value.interruption)
        assertEquals(EndReason.SYSTEM_INTERRUPTION, rig.coordinator.snapshot.value.interruptionReason)
    }
}
