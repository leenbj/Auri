package app.auri.probe.core

/**
 * Contract-aligned enums and data types (contracts/schemas/common.schema.json & adapter.schema.json v1.1).
 */
enum class BusinessCommand {
    START,
    STOP,
    TOGGLE,
    IGNORE,
}

enum class ControlSource(val wireValue: String) {
    HEADSET_REMOTE("headset_remote"),
    UNKNOWN_REMOTE("unknown_remote"),
    VOLUME_GESTURE("volume_gesture"),
    SCREEN_CONTROL("screen_control"),
}

enum class PlatformEvent(val wireValue: String) {
    PLAY("play"),
    PAUSE("pause"),
    TOGGLE("toggle"),
    NEXT("next"),
    PREVIOUS("previous"),
    VOLUME_CHANGE("volume_change"),
    SCREEN_BUTTON("screen_button"),
}

enum class ObservedInputRouteType(val wireValue: String) {
    BLUETOOTH_HFP("bluetooth_hfp"),
    BLUETOOTH_LE_AUDIO("bluetooth_le_audio"),
    BLUETOOTH_UNKNOWN_PROFILE("bluetooth_unknown_profile"),
    BUILT_IN_MIC("built_in_mic"),
    WIRED_HEADSET("wired_headset"),
    USB_AUDIO("usb_audio"),
    OTHER("other"),
    UNKNOWN("unknown");

    fun isVerifiedBluetoothInput(): Boolean =
        this == BLUETOOTH_HFP ||
            this == BLUETOOTH_LE_AUDIO ||
            this == BLUETOOTH_UNKNOWN_PROFILE
}

enum class VerificationState(val wireValue: String) {
    VERIFIED("verified"),
    MISMATCH("mismatch"),
    UNKNOWN("unknown"),
}

enum class CaptureState {
    DISARMED,
    ARMED,
    ROUTING,
    RECORDING,
    FINALIZING,
    BLOCKED,
}

enum class PermissionState(val wireValue: String) {
    GRANTED("granted"),
    DENIED("denied"),
    UNDETERMINED("undetermined"),
    RESTRICTED("restricted"),
    UNKNOWN("unknown"),
}

enum class AppLifecycleState(val wireValue: String) {
    FOREGROUND("foreground"),
    BACKGROUND("background"),
    LOCKED("locked"),
    UNKNOWN("unknown"),
}

enum class CanStart(val wireValue: String) {
    YES("yes"),
    NO("no"),
    UNKNOWN("unknown"),
}

enum class CompatStatus {
    NOT_TESTED,
    CALIBRATION_REQUIRED,
    SUPPORTED_FOREGROUND,
    SUPPORTED_RECORDING_IN_BACKGROUND,
    SUPPORTED_BACKGROUND_RESTART,
    PARTIAL,
    UNSUPPORTED,
}

enum class EvidenceLevel {
    E0,
    E1,
    E2,
    E3,
    E4,
}

enum class CommandMapping(val wireValue: String) {
    LOG_ONLY("logOnly"),
    SEMANTIC("semantic"),
    TOGGLE("toggle"),
}
