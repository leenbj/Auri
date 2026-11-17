import XCTest
@testable import AuriProbeIOS

final class LogRedactorTests: XCTestCase {
    func testRedactPortTruncatesLongUIDAndMasksMACInName() {
        let port = LogRedactor.redactPort(
            type: "BluetoothHFP",
            name: "Headset 11:22:33:44:55:66",
            uid: "11:22:33:44:55:66-tacl"
        )

        XCTAssertEqual(port["type"], "BluetoothHFP")
        XCTAssertEqual(port["name"], "Headset [REDACTED_MAC]")
        XCTAssertEqual(port["uid_tail"], "6-tacl")
    }

    func testRedactPortHidesShortUIDCompletely() {
        let port = LogRedactor.redactPort(type: "BluetoothLE", name: "FreeClip 2", uid: "abc123")

        XCTAssertEqual(port["uid_tail"], "redacted")
    }

    func testSanitizeDataRedactsSensitiveKeysAndNestedMACs() {
        let input: [String: Any] = [
            "session_id": "abc",
            "transcript": "secret dictation",
            "token": "bearer-xyz",
            "nested": [
                "text": "hello",
                "device_label": "AA-BB-CC-DD-EE-FF",
            ],
            "items": ["keep", "00:11:22:33:44:55"],
        ]

        let sanitized = LogRedactor.sanitizeData(input)
        XCTAssertEqual(sanitized["session_id"] as? String, "abc")
        XCTAssertEqual(sanitized["transcript"] as? String, "[REDACTED]")
        XCTAssertEqual(sanitized["token"] as? String, "[REDACTED]")

        let nested = sanitized["nested"] as? [String: Any]
        XCTAssertEqual(nested?["text"] as? String, "[REDACTED]")
        XCTAssertEqual(nested?["device_label"] as? String, "[REDACTED_MAC]")

        let items = sanitized["items"] as? [String]
        XCTAssertEqual(items, ["keep", "[REDACTED_MAC]"])
    }
}
