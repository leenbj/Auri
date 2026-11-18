package app.auri.capture.coordinator

import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.CaptureStatus
import app.auri.capture.contract.CaptureState
import app.auri.capture.contract.ContractJson
import app.auri.capture.contract.ContractRules
import app.auri.capture.contract.ControlSource
import app.auri.capture.contract.EndReason
import app.auri.capture.contract.ErrorCode
import app.auri.capture.contract.RecordingResult
import app.auri.capture.machine.CaptureEvent
import app.auri.capture.machine.Outcome
import app.auri.capture.testing.CaptureRig
import app.auri.capture.testing.StoreEvent
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 16 第 6 项的前半：协调器产出的 RecordingResult 是合同里的结果（status 与 end_reason 一致、时间线与哈希对得上），
 * 以及“路由确认了但没有音频 / 写不下 / 封存失败”这几种边角怎么收场。
 */
class CaptureResultTest {
    private fun TestScope.recording(rig: CaptureRig = CaptureRig(this), samples: Int = 3200, epoch: Long = 1): CaptureRig {
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

    private fun CaptureRig.stop(source: ControlSource = ControlSource.SCREEN_CONTROL, command: BusinessCommand = BusinessCommand.STOP) {
        control.press(command, source)
        settle()
    }

    @Test
    fun `正常停止：结果与合同一致，哈希是存储里那份音频的哈希，Record 与音频同一个标识`() = runTest {
        val rig = recording(samples = 3200, epoch = 4)
        rig.stop()

        val sealed = rig.output.recordings.single()
        val result = sealed.result
        val begin = rig.store.events.filterIsInstance<StoreEvent.Begin>().single()
        assertEquals("Record 与音频文件共用一个标识", begin.recordId, result.recordId)
        assertEquals(CaptureStatus.COMPLETE, result.status)
        assertEquals(EndReason.SCREEN_CONTROL, result.endReason)
        assertEquals("16 kHz：3200 个采样是 200 ms", 200L, result.captureDurationMs)
        assertEquals(200L, result.speechDurationMs)
        assertEquals(1, result.chunks.size)
        assertEquals(4L, result.chunks.single().routeEpoch)
        assertEquals(0L, result.verifiedRanges.single().startMs)
        assertEquals(200L, result.verifiedRanges.single().endMs)

        val bytes = ByteArray(3200 * 2)
        for (i in 0 until 3200) {
            val sample = (i % 100).toShort().toInt()
            bytes[2 * i] = (sample and 0xFF).toByte()
            bytes[2 * i + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        val expectedSha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("结果里的 sha256 必须是落盘音频的哈希", expectedSha, result.chunks.single().sha256)
        assertEquals(sealed.audio?.sha256, result.chunks.single().sha256)

        assertEquals(emptyList<String>(), ContractRules.violations(result))
        assertEquals("经合同 JSON 往返不变", result, ContractJson.decode<RecordingResult>(ContractJson.encode(result)))
    }

    @Test
    fun `每种结束方式对应合同里的 end_reason，截断类原因的 status 一律是 partial`() = runTest {
        data class Case(val name: String, val expectedReason: EndReason, val expectedStatus: CaptureStatus, val trigger: (CaptureRig) -> Unit)

        val cases = listOf(
            Case("屏幕按钮停止", EndReason.SCREEN_CONTROL, CaptureStatus.COMPLETE) { it.stop(ControlSource.SCREEN_CONTROL) },
            Case("音量手势停止", EndReason.VOLUME_GESTURE, CaptureStatus.COMPLETE) { it.stop(ControlSource.VOLUME_GESTURE) },
            Case("耳机 TOGGLE 停止", EndReason.HEADSET_COMMAND, CaptureStatus.COMPLETE) { it.stop(ControlSource.HEADSET_REMOTE, BusinessCommand.TOGGLE) },
            Case("到时长上限", EndReason.DURATION_LIMIT, CaptureStatus.COMPLETE) { it.advance(it.config.maxDurationMs) },
            Case("路由丢失", EndReason.ROUTE_LOST, CaptureStatus.PARTIAL) { it.capture.mismatchRoute(); it.settle() },
            Case("麦克风权限被撤销", EndReason.PERMISSION_REVOKED, CaptureStatus.PARTIAL) { it.capture.revokePermission(); it.settle() },
            Case("系统中断", EndReason.SYSTEM_INTERRUPTION, CaptureStatus.PARTIAL) { it.capture.interrupt(); it.settle() },
        )
        for (case in cases) {
            val rig = recording()
            case.trigger(rig)
            rig.settle()
            val result = rig.output.recordings.singleOrNull()?.result ?: error("${case.name}：没有交出录音结果")
            assertEquals(case.name, case.expectedReason, result.endReason)
            assertEquals(case.name, case.expectedStatus, result.status)
            assertEquals("${case.name}：已确认的 200 ms 都在", 200L, result.captureDurationMs)
            assertEquals(case.name, emptyList<String>(), ContractRules.violations(result))
            assertEquals(case.name, CaptureState.ARMED, rig.state)
        }
    }

    @Test
    fun `路由确认超时从按下开始算：超时前一毫秒仍在等，到点受阻`() = runTest {
        val rig = CaptureRig(this)
        rig.coordinator.enableControlMode()
        rig.settle()
        rig.control.press(BusinessCommand.START)
        rig.settle()

        rig.advance(rig.config.routeTimeoutMs - 1)
        assertEquals(CaptureState.ROUTING, rig.state)

        rig.advance(1)
        assertEquals(CaptureState.BLOCKED, rig.state)
    }

    @Test
    fun `时长上限从路由确认那一刻起算，不是从按下开始算`() = runTest {
        val rig = CaptureRig(this)
        rig.coordinator.enableControlMode()
        rig.settle()
        rig.control.press(BusinessCommand.START)
        rig.settle()
        rig.advance(3_000) // 3 秒后系统才确认路由
        rig.capture.verifiedRoute(epoch = 1)
        rig.settle()
        rig.capture.frames(samples = 1600, epoch = 1)

        rig.advance(rig.config.maxDurationMs - 1)
        assertEquals("确认后差一毫秒到上限，仍在录", CaptureState.RECORDING, rig.state)

        rig.advance(1)
        assertEquals(CaptureState.ARMED, rig.state)
        assertEquals(EndReason.DURATION_LIMIT, rig.output.recordings.single().result.endReason)
    }

    @Test
    fun `会话结束后定时器被取消：之后不会冒出过时的超时事件`() = runTest {
        val rig = recording()
        rig.stop()
        val before = rig.decisions.size

        rig.advance(rig.config.maxDurationMs * 2)

        assertEquals("没有新的决定：定时器都已取消", before, rig.decisions.size)
        assertEquals(1, rig.output.recordings.size)
    }

    @Test
    fun `路由确认了却一个采样也没收到：不留空文件，仍然交出一个没有音频的结果`() = runTest {
        val rig = recording(samples = 0)
        rig.stop()

        val sealed = rig.output.recordings.single()
        assertNull("没有音频文件", sealed.audio)
        assertEquals(0L, sealed.result.captureDurationMs)
        assertTrue(sealed.result.chunks.isEmpty())
        assertTrue(sealed.result.verifiedRanges.isEmpty())
        assertEquals("存储被要求清理（abort），没有 seal", listOf(StoreEvent.Abort(sealed.result.recordId)), rig.store.events.filter { it is StoreEvent.Abort || it is StoreEvent.Seal })
        assertEquals(emptyList<String>(), ContractRules.violations(sealed.result))
    }

    @Test
    fun `录音中写入失败：已经写下的部分被封存为 partial，之后的帧不再写`() = runTest {
        val rig = CaptureRig(this)
        rig.store.failAppendAfterSamples = 1600
        rig.coordinator.enableControlMode()
        rig.settle()
        rig.control.press(BusinessCommand.START)
        rig.settle()
        rig.capture.verifiedRoute(epoch = 1)
        rig.settle()
        rig.capture.frames(samples = 1600, epoch = 1)
        rig.capture.frames(samples = 1600, epoch = 1) // 这一批写不下
        rig.capture.frames(samples = 1600, epoch = 1) // 已经在封存，闸门关了
        rig.settle()

        assertEquals(CaptureState.ARMED, rig.state)
        val result = rig.output.recordings.single().result
        assertEquals(CaptureStatus.PARTIAL, result.status)
        assertEquals(EndReason.SYSTEM_INTERRUPTION, result.endReason)
        assertEquals("只有写成功的那 1600 个采样", 100L, result.captureDurationMs)
        assertEquals(1600, rig.store.written.size)
        assertEquals(Cause.STORAGE_FAILURE, rig.decisions.first { it.trigger == CaptureEvent.SYSTEM_INTERRUPTION }.cause)
        assertEquals(ErrorCode.STORAGE_EXHAUSTED, rig.decisions.first { it.trigger == CaptureEvent.SYSTEM_INTERRUPTION }.errorCode)
    }

    @Test
    fun `封存失败：通知录音丢失并回到待命，存储被要求清理，不交出任何结果`() = runTest {
        val rig = recording()
        rig.store.failSeal = IOException("封存失败（测试注入）")
        rig.stop()

        assertEquals(CaptureState.ARMED, rig.state)
        assertTrue("没有录音结果", rig.output.recordings.isEmpty())
        val (recordId, reason) = rig.output.lost.single()
        assertEquals(ErrorCode.STORAGE_EXHAUSTED, reason)
        assertEquals("丢失通知里的标识就是 begin 时的 Record 标识", rig.store.events.filterIsInstance<StoreEvent.Begin>().single().recordId, recordId)
        assertEquals("残留被要求清理", listOf(StoreEvent.Abort(recordId)), rig.store.events.filterIsInstance<StoreEvent.Abort>())
        assertEquals(Cause.STORAGE_FAILURE, rig.decisions.last().cause)
    }

    @Test
    fun `封存时适配器抛出没预料到的异常：当作失败收场，状态机不会卡在 FINALIZING`() = runTest {
        val rig = recording()
        rig.store.failSealUnexpectedly = IllegalStateException("适配器的 bug（测试注入）")
        rig.stop()

        assertEquals(CaptureState.ARMED, rig.state)
        assertEquals(1, rig.output.lost.size)
        assertTrue(rig.output.recordings.isEmpty())
    }

    @Test
    fun `适配器在处理某条输入时抛出异常：这条输入被放弃，后面的输入照常处理`() = runTest {
        val rig = CaptureRig(this)
        rig.control.throwOnEnable = IllegalStateException("适配器的 bug（测试注入）")
        rig.coordinator.enableControlMode()
        rig.settle()
        assertEquals(CaptureState.DISARMED, rig.state)
        assertEquals(Cause.INTERNAL_ERROR, rig.decisions.last().cause)
        assertEquals(Outcome.REJECTED, rig.decisions.last().outcome)

        rig.control.throwOnEnable = null
        rig.coordinator.enableControlMode()
        rig.settle()
        assertEquals("协调器没有因为那次异常停摆", CaptureState.ARMED, rig.state)
    }
}
