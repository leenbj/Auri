package app.auri.platform.service

import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.CaptureState
import app.auri.capture.contract.EndReason
import app.auri.capture.contract.ErrorCode
import app.auri.capture.adapter.Subscription
import app.auri.capture.testing.CaptureRig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundServiceFailureTest {
    private class RefusingService : ForegroundServiceController {
        override fun start() = false
        override fun stop() = Unit
    }

    private class AsyncService : ForegroundServiceController {
        private var failure: (() -> Unit)? = null
        override fun onFailure(handler: () -> Unit): Subscription {
            failure = handler
            return Subscription { failure = null }
        }
        fun promotionFailed() { failure?.invoke() }
        override fun start() = true
        override fun stop() = Unit
    }

    @Test
    fun `service refusal while routing blocks capture with its reason`() = runTest {
        val rig = CaptureRig(this)
        rig.coordinator.enableControlMode()
        rig.settle()
        rig.control.press(BusinessCommand.START)
        rig.settle()
        backgroundScope.bindForegroundService(rig.coordinator.snapshot, RefusingService()) { rig.coordinator.foregroundServiceFailed() }
        rig.settle()

        assertEquals(CaptureState.BLOCKED, rig.state)
        assertEquals(ErrorCode.BACKGROUND_START_DENIED, rig.coordinator.snapshot.value.blockedReason)
    }

    @Test
    fun `service refusal after route verification seals the recording`() = runTest {
        val rig = CaptureRig(this)
        rig.coordinator.enableControlMode()
        rig.settle()
        rig.control.press(BusinessCommand.START)
        rig.settle()
        rig.capture.verifiedRoute()
        rig.settle()
        rig.capture.frames(1600, epoch = 1)
        rig.settle()
        backgroundScope.bindForegroundService(rig.coordinator.snapshot, RefusingService()) { rig.coordinator.foregroundServiceFailed() }
        rig.settle()

        assertEquals(CaptureState.ARMED, rig.state)
        assertEquals(EndReason.SYSTEM_INTERRUPTION, rig.output.recordings.single().result.endReason)
        assertEquals(1600L, rig.output.recordings.single().audio?.sampleCount)
    }

    @Test
    fun `asynchronous foreground promotion failure ends an active recording`() = runTest {
        val rig = CaptureRig(this)
        val service = AsyncService()
        backgroundScope.bindForegroundService(rig.coordinator.snapshot, service) { rig.coordinator.foregroundServiceFailed() }
        rig.coordinator.enableControlMode()
        rig.settle()
        rig.control.press(BusinessCommand.START)
        rig.settle()
        rig.capture.verifiedRoute()
        rig.settle()

        service.promotionFailed()
        rig.settle()

        assertEquals(CaptureState.ARMED, rig.state)
        assertEquals(EndReason.SYSTEM_INTERRUPTION, rig.output.recordings.single().result.endReason)
    }
}
