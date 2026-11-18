package app.auri.android

import app.auri.capture.contract.CaptureState
import app.auri.capture.contract.ErrorCode
import app.auri.capture.contract.GateState
import app.auri.capture.contract.ObservedInput
import app.auri.capture.contract.ObservedInputRouteType
import app.auri.capture.coordinator.CaptureSnapshot
import app.auri.capture.coordinator.FrameCounters
import app.auri.capture.coordinator.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 真机复测时这行日志是“应用当时相信什么”的唯一记录，所以它要说清三件事（状态、为什么没录、系统观测到的输入类型），
 * 而且不能带出任何能认出设备或这次录音的东西。
 */
class CaptureLogTest {
    private fun snapshot(state: CaptureState, reason: ErrorCode? = null, input: ObservedInput? = null, sessionId: String? = null) = CaptureSnapshot(
        state = state,
        blockedReason = reason,
        session = sessionId?.let { SessionInfo(it, generation = 7, verifiedRouteEpoch = 3, gate = GateState.OPEN) },
        actualInput = input,
        counters = FrameCounters(),
        version = 1,
    )

    @Test
    fun `没录成：写出状态与原因，没有观测到输入`() {
        val line = captureLogLine(snapshot(CaptureState.BLOCKED, ErrorCode.INPUT_DEVICE_MISSING))

        assertEquals("state=BLOCKED reason=INPUT_DEVICE_MISSING input=-", line)
    }

    @Test
    fun `录音中：写出系统观测到的输入类型`() {
        val line = captureLogLine(snapshot(CaptureState.RECORDING, input = ObservedInput(ObservedInputRouteType.BLUETOOTH_HFP, "耳机")))

        assertEquals("state=RECORDING reason=- input=BLUETOOTH_HFP", line)
    }

    @Test
    fun `观测到的是手机麦克风时如实写出来，不替它说成耳机`() {
        val line = captureLogLine(snapshot(CaptureState.RECORDING, input = ObservedInput(ObservedInputRouteType.BUILT_IN_MIC, null)))

        assertEquals("state=RECORDING reason=- input=BUILT_IN_MIC", line)
    }

    @Test
    fun `不带耳机名与会话标识`() {
        val line = captureLogLine(
            snapshot(CaptureState.RECORDING, input = ObservedInput(ObservedInputRouteType.BLUETOOTH_HFP, "SECRET-HEADSET-NAME"), sessionId = "SECRET-SESSION-ID"),
        )

        assertFalse(line, "SECRET-HEADSET-NAME" in line || "SECRET-SESSION-ID" in line)
    }
}
