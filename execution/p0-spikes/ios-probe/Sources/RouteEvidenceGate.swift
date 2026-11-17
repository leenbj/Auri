import Foundation

/// 对应合同 common.schema.json#/$defs/observed_input_route_type。
enum ObservedInputRouteType: String, Equatable, Sendable {
    case bluetoothHFP = "bluetooth_hfp"
    case bluetoothLEAudio = "bluetooth_le_audio"
    case bluetoothUnknownProfile = "bluetooth_unknown_profile"
    case builtInMic = "built_in_mic"
    case wiredHeadset = "wired_headset"
    case usbAudio = "usb_audio"
    case other
    case unknown

    var isBluetooth: Bool {
        switch self {
        case .bluetoothHFP, .bluetoothLEAudio, .bluetoothUnknownProfile:
            return true
        case .builtInMic, .wiredHeadset, .usbAudio, .other, .unknown:
            return false
        }
    }
}

enum RouteVerificationState: String, Equatable, Sendable {
    case verified
    case mismatch
    case unknown
}

struct ObservedAudioPort: Equatable, Sendable {
    let portTypeRaw: String
    let portName: String
    let uid: String
}

struct RouteEvidenceVerdict: Equatable, Sendable {
    let verificationState: RouteVerificationState
    let primaryObservedRouteType: ObservedInputRouteType
    let isBluetoothOnlyVerified: Bool
    let reason: String
}

/// 严格蓝牙输入路由闸门（PRD FR-REC-001/002）：
/// 仅当观测到的全部输入端口均为蓝牙输入时放行；任何手机内置麦、有线麦、未知或空输入一律拒绝。
enum RouteEvidenceGate {
    static func classify(portTypeRaw: String) -> ObservedInputRouteType {
        switch portTypeRaw {
        case "BluetoothHFP":
            return .bluetoothHFP
        case "BluetoothLE":
            return .bluetoothLEAudio
        case "MicrophoneBuiltIn":
            return .builtInMic
        case "HeadsetMic":
            return .wiredHeadset
        case "USBAudio":
            return .usbAudio
        case "":
            return .unknown
        default:
            return .other
        }
    }

    static func evaluate(inputs: [ObservedAudioPort]) -> RouteEvidenceVerdict {
        guard !inputs.isEmpty else {
            return RouteEvidenceVerdict(
                verificationState: .mismatch,
                primaryObservedRouteType: .unknown,
                isBluetoothOnlyVerified: false,
                reason: "no_inputs"
            )
        }

        let classified = inputs.map { classify(portTypeRaw: $0.portTypeRaw) }

        if let firstNonBluetooth = classified.first(where: { !$0.isBluetooth }) {
            let state: RouteVerificationState = firstNonBluetooth == .unknown ? .unknown : .mismatch
            let reason = firstNonBluetooth == .unknown ? "unknown_input_route" : "non_bluetooth_input_detected"
            return RouteEvidenceVerdict(
                verificationState: state,
                primaryObservedRouteType: firstNonBluetooth,
                isBluetoothOnlyVerified: false,
                reason: reason
            )
        }

        return RouteEvidenceVerdict(
            verificationState: .verified,
            primaryObservedRouteType: classified[0],
            isBluetoothOnlyVerified: true,
            reason: "bluetooth_input_verified"
        )
    }
}
