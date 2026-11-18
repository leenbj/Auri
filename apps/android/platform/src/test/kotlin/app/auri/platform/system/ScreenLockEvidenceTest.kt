package app.auri.platform.system

import app.auri.capture.calibration.CalibrationWizard
import app.auri.capture.calibration.CompatStatus
import app.auri.capture.calibration.EvidenceSetting
import app.auri.capture.contract.CaptureState
import app.auri.capture.coordinator.CaptureDecision
import app.auri.capture.coordinator.Cause
import app.auri.capture.machine.CaptureEvent
import app.auri.capture.machine.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenLockEvidenceTest {
    @Test
    fun `screen off without keyguard is unknown and cannot prove locked recording`() {
        val screenOff = lockedFrom(interactive = false, keyguardLocked = false)
        assertNull(screenOff)
        val wizard = CalibrationWizard("screen-off", EvidenceSetting.REAL_DEVICE_AND_HEADSET)
        wizard.start()
        repeat(3) { wizard.skip() }
        wizard.onDecision(CaptureDecision(CaptureEvent.START, Outcome.TRANSITION, CaptureState.ARMED, CaptureState.ROUTING, Cause.MACHINE), false)
        wizard.onDecision(CaptureDecision(CaptureEvent.ROUTE_VERIFIED, Outcome.TRANSITION, CaptureState.ROUTING, CaptureState.RECORDING, Cause.MACHINE), false)
        wizard.onScreenLockChanged(screenOff, CaptureState.RECORDING)
        wizard.onScreenLockChanged(false, CaptureState.RECORDING)
        wizard.next()

        val profile = checkNotNull(wizard.buildProfile("fixture", 1, null))
        assertEquals(CompatStatus.NOT_TESTED, profile.capabilities.continueRecordingWhenLocked.status)
    }

    @Test
    fun `keyguard signal is locked and interactive unlocked state is unlocked`() {
        assertEquals(true, lockedFrom(interactive = false, keyguardLocked = true))
        assertEquals(true, lockedFrom(interactive = true, keyguardLocked = true))
        assertEquals(false, lockedFrom(interactive = true, keyguardLocked = false))
    }
}
