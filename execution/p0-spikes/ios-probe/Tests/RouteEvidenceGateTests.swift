import XCTest
@testable import AuriProbeIOS

final class RouteEvidenceGateTests: XCTestCase {
    func testAcceptsBluetoothHFPInput() {
        let verdict = RouteEvidenceGate.evaluate(inputs: [
            ObservedAudioPort(portTypeRaw: "BluetoothHFP", portName: "FreeClip 2", uid: "11:22:33:44:55:66-tacl")
        ])

        XCTAssertTrue(verdict.isBluetoothOnlyVerified)
        XCTAssertEqual(verdict.verificationState, .verified)
        XCTAssertEqual(verdict.primaryObservedRouteType, .bluetoothHFP)
    }

    func testAcceptsBluetoothLEInput() {
        let verdict = RouteEvidenceGate.evaluate(inputs: [
            ObservedAudioPort(portTypeRaw: "BluetoothLE", portName: "FreeClip 2 LE", uid: "abcdef123456")
        ])

        XCTAssertTrue(verdict.isBluetoothOnlyVerified)
        XCTAssertEqual(verdict.verificationState, .verified)
        XCTAssertEqual(verdict.primaryObservedRouteType, .bluetoothLEAudio)
    }

    func testRejectsEmptyInputs() {
        let verdict = RouteEvidenceGate.evaluate(inputs: [])

        XCTAssertFalse(verdict.isBluetoothOnlyVerified)
        XCTAssertEqual(verdict.verificationState, .mismatch)
        XCTAssertEqual(verdict.primaryObservedRouteType, .unknown)
        XCTAssertEqual(verdict.reason, "no_inputs")
    }

    func testRejectsBuiltInMicrophoneFallback() {
        let verdict = RouteEvidenceGate.evaluate(inputs: [
            ObservedAudioPort(portTypeRaw: "MicrophoneBuiltIn", portName: "iPhone Microphone", uid: "Built-In Microphone")
        ])

        XCTAssertFalse(verdict.isBluetoothOnlyVerified)
        XCTAssertEqual(verdict.verificationState, .mismatch)
        XCTAssertEqual(verdict.primaryObservedRouteType, .builtInMic)
    }

    func testRejectsMixedBluetoothAndBuiltInMicrophone() {
        let verdict = RouteEvidenceGate.evaluate(inputs: [
            ObservedAudioPort(portTypeRaw: "BluetoothHFP", portName: "FreeClip 2", uid: "11:22:33:44:55:66-tacl"),
            ObservedAudioPort(portTypeRaw: "MicrophoneBuiltIn", portName: "iPhone Microphone", uid: "Built-In Microphone")
        ])

        XCTAssertFalse(verdict.isBluetoothOnlyVerified)
        XCTAssertEqual(verdict.verificationState, .mismatch)
        XCTAssertEqual(verdict.primaryObservedRouteType, .builtInMic)
    }

    func testRejectsUnknownPortTypeAsUnverified() {
        let verdict = RouteEvidenceGate.evaluate(inputs: [
            ObservedAudioPort(portTypeRaw: "", portName: "Mystery", uid: "unknown")
        ])

        XCTAssertFalse(verdict.isBluetoothOnlyVerified)
        XCTAssertEqual(verdict.verificationState, .unknown)
        XCTAssertEqual(verdict.primaryObservedRouteType, .unknown)
    }
}
