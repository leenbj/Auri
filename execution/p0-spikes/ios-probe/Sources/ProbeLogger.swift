import Foundation
import UIKit

/// 追加写入的 JSON Lines 探针日志。每行一个事件，带单调时间；不记录音频或口述正文。
final class ProbeLogger: @unchecked Sendable {
    static let shared = ProbeLogger()

    let launchID = UUID().uuidString
    let fileURL: URL
    var onLine: (@Sendable (String) -> Void)?

    private let lock = NSLock()
    private var seq = 0
    private var state = "unknown"
    private var handle: FileHandle?

    private init() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        fileURL = docs.appendingPathComponent("probe-log.jsonl")
        if !FileManager.default.fileExists(atPath: fileURL.path) {
            FileManager.default.createFile(atPath: fileURL.path, contents: nil)
        }
        handle = try? FileHandle(forWritingTo: fileURL)
        _ = try? handle?.seekToEnd()
    }

    var appState: String {
        lock.lock()
        defer { lock.unlock() }
        return state
    }

    func setAppState(_ value: String) {
        lock.lock()
        state = value
        lock.unlock()
    }

    /// 单调时钟，不受校时和休眠影响，用来对齐路由变化与音频帧。
    static func monoNanos() -> UInt64 {
        clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW)
    }

    func log(_ kind: String, _ data: [String: Any] = [:]) {
        lock.lock()
        seq += 1
        let line: [String: Any] = [
            "seq": seq,
            "launch_id": launchID,
            "mono_ns": ProbeLogger.monoNanos(),
            "wall": ISO8601DateFormatter().string(from: Date()),
            "kind": kind,
            "app_state": state,
            "data": data,
        ]
        let encoded = (try? JSONSerialization.data(withJSONObject: line, options: [.sortedKeys]))
            .flatMap { String(data: $0, encoding: .utf8) } ?? "{\"kind\":\"log_encode_failed\"}"
        if let bytes = (encoded + "\n").data(using: .utf8) {
            try? handle?.write(contentsOf: bytes)
        }
        lock.unlock()
        onLine?(encoded)
    }
}
