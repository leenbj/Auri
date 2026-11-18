package app.auri.android

import app.auri.capture.coordinator.CaptureSnapshot

const val CAPTURE_LOG_TAG = "AuriCapture"

/**
 * 真机复测要知道“应用当时相信什么”，所以采集状态每变化一次写一行日志（`adb logcat -d -s AuriCapture:I`）。
 * 只写状态、受阻原因、系统观测到的输入类型；不写耳机名、设备地址、会话/Record 标识、文字、音频
 * （日志默认不含正文、音频、凭证，也不带能认出设备的东西）。
 */
fun captureLogLine(snapshot: CaptureSnapshot): String =
    "state=${snapshot.state} reason=${snapshot.blockedReason ?: "-"} input=${snapshot.actualInput?.routeType ?: "-"}"
