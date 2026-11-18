package app.auri.capture.coordinator

import app.auri.capture.adapter.SealedAudio
import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.CaptureState
import app.auri.capture.contract.CaptureStatus
import app.auri.capture.contract.ContractRules
import app.auri.capture.contract.EndReason
import app.auri.capture.contract.GateState
import app.auri.capture.testing.CaptureRig
import app.auri.capture.testing.StoreEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 16 第 1 项：协调器按采集状态机走完一次正常录音，且只通过适配器接口碰系统。
 * 一整条路：开启控制模式 → 屏幕按钮开始 → 路由确认前的帧只进内存 → 路由确认后才写入 → 停止 → 封存 → 产出录音结果。
 */
class CaptureHappyPathTest {
    @Test
    fun `正常录一段：确认前的音频只进内存，确认后才写入，封存后产出一个合同合法的录音结果`() = runTest {
        val rig = CaptureRig(this)

        rig.coordinator.enableControlMode()
        rig.settle()
        assertEquals(CaptureState.ARMED, rig.state)

        rig.control.press(BusinessCommand.START)
        rig.settle()
        assertEquals(CaptureState.ROUTING, rig.state)
        assertEquals("开始时请求的是蓝牙输入，且恰好一次", 1, rig.capture.requests.size)

        // 路由确认之前：1600 个采样（100 ms）只能进 Quarantine，存储完全没有被碰过
        rig.capture.frames(samples = 1600, epoch = 0)
        rig.settle()
        assertEquals(emptyList<StoreEvent>(), rig.store.events)
        assertEquals(1600L, rig.coordinator.snapshot.value.counters.quarantinedSamples)

        rig.capture.verifiedRoute(epoch = 1)
        rig.settle()
        assertEquals(CaptureState.RECORDING, rig.state)
        assertEquals("确认前的那 1600 个采样被丢弃，不是补写", 0L, rig.coordinator.snapshot.value.counters.quarantinedSamples)
        assertEquals(1600L, rig.coordinator.snapshot.value.counters.discardedQuarantineSamples)

        rig.capture.frames(samples = 3200, epoch = 1)
        rig.settle()
        rig.control.press(BusinessCommand.STOP)
        rig.settle()

        assertEquals(CaptureState.ARMED, rig.state)
        val sealed = rig.output.recordings.single()
        val result = sealed.result
        assertEquals(CaptureStatus.COMPLETE, result.status)
        assertEquals(EndReason.SCREEN_CONTROL, result.endReason)
        assertEquals("只有确认之后的 3200 个采样（200 ms）被保留", 200L, result.captureDurationMs)
        assertEquals(200L, result.speechDurationMs)
        assertEquals(listOf(0L to 200L), result.verifiedRanges.map { it.startMs to it.endMs })
        assertEquals(emptyList<Any>(), result.discardedRanges)
        assertEquals(emptyList<String>(), ContractRules.violations(result))

        val audio: SealedAudio = sealed.audio ?: error("保留了音频就必须有封存后的音频")
        val chunk = result.chunks.single()
        assertEquals(audio.sha256, chunk.sha256)
        assertEquals("块上写的是路由确认时的 epoch", 1L, chunk.routeEpoch)
        assertEquals(200L, chunk.endMs)

        // 存储的调用顺序：确认之后才 begin，只写确认之后的采样，最后封存
        val events = rig.store.events
        assertTrue(events[0] is StoreEvent.Begin)
        assertEquals(GateState.OPEN, (events[0] as StoreEvent.Begin).handle.gateState)
        assertEquals(1L, (events[0] as StoreEvent.Begin).handle.verifiedRouteEpoch)
        assertEquals(listOf(3200), events.filterIsInstance<StoreEvent.Append>().map { it.samples })
        assertTrue(events.last() is StoreEvent.Seal)
        assertEquals("Record 与音频共用同一个标识", result.recordId, (events[0] as StoreEvent.Begin).recordId)
        assertEquals("停止后立刻释放麦克风", listOf(rig.capture.requests.single().operationId), rig.capture.releases)
    }
}
