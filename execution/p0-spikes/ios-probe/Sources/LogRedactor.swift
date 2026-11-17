import Foundation

/// 探针日志脱敏器：确保日志不落盘正文、音频、凭证或完整蓝牙 MAC / UID。
enum LogRedactor {
    private static let sensitiveKeys: Set<String> = [
        "transcript",
        "text",
        "body",
        "audio",
        "audio_data",
        "pcm",
        "token",
        "secret",
        "password",
        "credential",
        "authorization",
        "mac",
        "mac_address",
        "uid",
    ]

    private static let macRegex: NSRegularExpression? = try? NSRegularExpression(
        pattern: "(?i)\\b([0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b"
    )

    static func redactUID(_ uid: String) -> String {
        guard uid.count > 6 else { return "redacted" }
        return String(uid.suffix(6))
    }

    static func maskMACAddresses(in text: String) -> String {
        guard let macRegex else { return text }
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        return macRegex.stringByReplacingMatches(in: text, options: [], range: range, withTemplate: "[REDACTED_MAC]")
    }

    static func redactPort(type: String, name: String, uid: String) -> [String: String] {
        [
            "type": type,
            "name": maskMACAddresses(in: name),
            "uid_tail": redactUID(uid),
        ]
    }

    static func sanitizeData(_ data: [String: Any]) -> [String: Any] {
        var result: [String: Any] = [:]
        result.reserveCapacity(data.count)
        for (key, value) in data {
            result[key] = sanitizeValue(value, key: key)
        }
        return result
    }

    private static func sanitizeValue(_ value: Any, key: String? = nil) -> Any {
        if let key, sensitiveKeys.contains(key.lowercased()) {
            return "[REDACTED]"
        }
        if let text = value as? String {
            return maskMACAddresses(in: text)
        }
        if let dict = value as? [String: Any] {
            return sanitizeData(dict)
        }
        if let array = value as? [Any] {
            return array.map { sanitizeValue($0) }
        }
        return value
    }
}
