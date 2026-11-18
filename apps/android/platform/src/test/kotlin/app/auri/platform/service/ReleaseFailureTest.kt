package app.auri.platform.service

import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.CaptureState
import app.auri.capture.contract.EndReason
import app.auri.capture.coordinator.CaptureConfig
import app.auri.capture.testing.CaptureRig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReleaseFailureTest {
    private class Service : ForegroundServiceController {
        var running = false
        override fun start(): Boolean { running = true; return true }
        override fun stop() { running = false }
    }

    private fun TestScope.checkReleaseFailure(reason: EndReason, trigger: (CaptureRig) -> Unit) {
        val rig = CaptureRig(this, CaptureConfig(maxDurationMs = 1_000))
        val service = Service()
        backgroundScope.bindForegroundService(rig.coordinator.snapshot, service) { error("unexpected refusal") }
        rig.coordinator.enableControlMode()
        rig.settle()
        rig.control.press(BusinessCommand.START)
        rig.settle()
        rig.capture.verifiedRoute(epoch = 1)
        rig.settle()
        rig.capture.frames(samples = 1600, epoch = 1)
        rig.settle()
        rig.capture.releaseFailure = IllegalStateException("release failed")

        trigger(rig)
        rig.settle()

        assertEquals(CaptureState.ARMED, rig.state)
        assertEquals(reason, rig.output.recordings.single().result.endReason)
        assertEquals(1600L, rig.output.recordings.single().audio?.sampleCount)
        assertFalse(service.running)
    }

    @Test fun `stop seals audio even when release throws`() = runTest {
        checkReleaseFailure(EndReason.SCREEN_CONTROL) { it.control.press(BusinessCommand.STOP) }
    }

    @Test fun `route loss seals audio even when release throws`() = runTest {
        checkReleaseFailure(EndReason.ROUTE_LOST) { it.capture.mismatchRoute() }
    }

    @Test fun `duration limit seals audio even when release throws`() = runTest {
        checkReleaseFailure(EndReason.DURATION_LIMIT) { it.advance(1_000) }
    }
}
