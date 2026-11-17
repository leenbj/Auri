package app.auri.probe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureEligibilityEvaluatorTest {

    @Test
    fun deniedMicPermissionBlocksStartEvenInForegroundWithFgsRunning() {
        val result = CaptureEligibilityEvaluator.evaluate(
            micPermission = PermissionState.DENIED,
            bluetoothPermission = PermissionState.GRANTED,
            lifecycle = AppLifecycleState.FOREGROUND,
            fgsState = ForegroundServiceState.RUNNING_MICROPHONE,
        )
        assertEquals(CanStart.NO, result.canStart)
        assertEquals("PERMISSION_MIC_DENIED", result.reasonCode)
    }

    @Test
    fun grantedMicPermissionInBackgroundWithoutRunningFgsDoesNotClaimCanStartYes() {
        val result = CaptureEligibilityEvaluator.evaluate(
            micPermission = PermissionState.GRANTED,
            bluetoothPermission = PermissionState.GRANTED,
            lifecycle = AppLifecycleState.BACKGROUND,
            fgsState = ForegroundServiceState.NOT_RUNNING,
        )
        assertEquals(CanStart.UNKNOWN, result.canStart)
        assertEquals("fgs_microphone_not_started_cannot_infer_from_check_self_permission", result.layerNote)
    }

    @Test
    fun fgsStartDeniedInBackgroundReportsBackgroundStartDenied() {
        val result = CaptureEligibilityEvaluator.evaluate(
            micPermission = PermissionState.GRANTED,
            bluetoothPermission = PermissionState.GRANTED,
            lifecycle = AppLifecycleState.LOCKED,
            fgsState = ForegroundServiceState.START_FAILED_SECURITY,
        )
        assertEquals(CanStart.NO, result.canStart)
        assertEquals("BACKGROUND_START_DENIED", result.reasonCode)
    }

    @Test
    fun grantedMicAndBluetoothInForegroundWithMicFgsRunningAllowsStartAttempt() {
        val result = CaptureEligibilityEvaluator.evaluate(
            micPermission = PermissionState.GRANTED,
            bluetoothPermission = PermissionState.GRANTED,
            lifecycle = AppLifecycleState.FOREGROUND,
            fgsState = ForegroundServiceState.RUNNING_MICROPHONE,
        )
        assertEquals(CanStart.YES, result.canStart)
        assertNull(result.reasonCode)
    }

    @Test
    fun deniedBluetoothConnectPermissionBlocksBluetoothDiscoveryAndCapture() {
        val result = CaptureEligibilityEvaluator.evaluate(
            micPermission = PermissionState.GRANTED,
            bluetoothPermission = PermissionState.DENIED,
            lifecycle = AppLifecycleState.FOREGROUND,
            fgsState = ForegroundServiceState.RUNNING_MICROPHONE,
        )
        assertEquals(CanStart.NO, result.canStart)
        assertEquals("INPUT_DEVICE_MISSING", result.reasonCode)
        assertEquals("bluetooth_connect_permission_not_granted", result.layerNote)
    }
}
