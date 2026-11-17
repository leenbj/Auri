package app.auri.probe.core

enum class ForegroundServiceState(val wireValue: String) {
    NOT_RUNNING("not_running"),
    RUNNING_MICROPHONE("running_microphone"),
    RUNNING_WRONG_TYPE("running_wrong_type"),
    START_FAILED_NOT_ALLOWED("start_failed_not_allowed"),
    START_FAILED_SECURITY("start_failed_security"),
    START_FAILED_OTHER("start_failed_other"),
}

data class CaptureEligibilityResult(
    val permission: PermissionState,
    val bluetoothPermission: PermissionState,
    val lifecycle: AppLifecycleState,
    val fgsState: ForegroundServiceState,
    val canStart: CanStart,
    val reasonCode: String?,
    val layerNote: String,
)

/**
 * Evaluates Android's two independent capture layers (PRD §4.4):
 * Layer 1: Runtime dangerous permissions (RECORD_AUDIO, BLUETOOTH_CONNECT).
 * Layer 2: Lifecycle visibility + ForegroundService(FOREGROUND_SERVICE_TYPE_MICROPHONE).
 * Never infers background mic eligibility from checkSelfPermission(RECORD_AUDIO) alone.
 */
object CaptureEligibilityEvaluator {

    fun evaluate(
        micPermission: PermissionState,
        bluetoothPermission: PermissionState,
        lifecycle: AppLifecycleState,
        fgsState: ForegroundServiceState,
    ): CaptureEligibilityResult {
        if (micPermission != PermissionState.GRANTED) {
            return CaptureEligibilityResult(
                permission = micPermission,
                bluetoothPermission = bluetoothPermission,
                lifecycle = lifecycle,
                fgsState = fgsState,
                canStart = CanStart.NO,
                reasonCode = "PERMISSION_MIC_DENIED",
                layerNote = "layer1_record_audio_not_granted",
            )
        }

        if (bluetoothPermission != PermissionState.GRANTED) {
            return CaptureEligibilityResult(
                permission = micPermission,
                bluetoothPermission = bluetoothPermission,
                lifecycle = lifecycle,
                fgsState = fgsState,
                canStart = CanStart.NO,
                reasonCode = "INPUT_DEVICE_MISSING",
                layerNote = "bluetooth_connect_permission_not_granted",
            )
        }

        return when (fgsState) {
            ForegroundServiceState.RUNNING_MICROPHONE -> CaptureEligibilityResult(
                permission = micPermission,
                bluetoothPermission = bluetoothPermission,
                lifecycle = lifecycle,
                fgsState = fgsState,
                canStart = CanStart.YES,
                reasonCode = null,
                layerNote = "layer1_and_layer2_microphone_fgs_active",
            )
            ForegroundServiceState.START_FAILED_NOT_ALLOWED,
            ForegroundServiceState.START_FAILED_SECURITY,
            ForegroundServiceState.START_FAILED_OTHER,
            ForegroundServiceState.RUNNING_WRONG_TYPE -> CaptureEligibilityResult(
                permission = micPermission,
                bluetoothPermission = bluetoothPermission,
                lifecycle = lifecycle,
                fgsState = fgsState,
                canStart = CanStart.NO,
                reasonCode = "BACKGROUND_START_DENIED",
                layerNote = "layer2_fgs_microphone_blocked_${fgsState.wireValue}",
            )
            ForegroundServiceState.NOT_RUNNING -> when (lifecycle) {
                AppLifecycleState.FOREGROUND -> CaptureEligibilityResult(
                    permission = micPermission,
                    bluetoothPermission = bluetoothPermission,
                    lifecycle = lifecycle,
                    fgsState = fgsState,
                    canStart = CanStart.UNKNOWN,
                    reasonCode = null,
                    layerNote = "fgs_microphone_not_started_yet_in_foreground",
                )
                AppLifecycleState.BACKGROUND,
                AppLifecycleState.LOCKED,
                AppLifecycleState.UNKNOWN -> CaptureEligibilityResult(
                    permission = micPermission,
                    bluetoothPermission = bluetoothPermission,
                    lifecycle = lifecycle,
                    fgsState = fgsState,
                    canStart = CanStart.UNKNOWN,
                    reasonCode = null,
                    layerNote = "fgs_microphone_not_started_cannot_infer_from_check_self_permission",
                )
            }
        }
    }
}
