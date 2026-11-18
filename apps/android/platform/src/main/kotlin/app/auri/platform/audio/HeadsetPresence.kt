package app.auri.platform.audio

import kotlinx.coroutines.flow.StateFlow

/**
 * “耳机已连接”这一个事实，只回答系统里有没有蓝牙音频设备。它**不是**输入来源的证据：
 * 已配对、已连接都不等于“当前采集真正来自耳机”，实际输入来源只来自采集适配器报告的系统观测（FR-REC-001）。
 * 界面把这两件事分开显示。
 */
data class HeadsetStatus(val connected: Boolean, val name: String?) {
    companion object {
        val NONE = HeadsetStatus(connected = false, name = null)
    }
}

interface HeadsetPresence {
    val status: StateFlow<HeadsetStatus>
}

private val BLUETOOTH_AUDIO_TYPES = setOf(
    AudioDeviceTypes.BLUETOOTH_SCO,
    AudioDeviceTypes.BLUETOOTH_A2DP,
    AudioDeviceTypes.BLE_HEADSET,
    AudioDeviceTypes.BLE_SPEAKER,
)

/** 系统当前列出的音频设备 → 耳机连接状态：只看有没有蓝牙音频设备，名字取第一个非空的。 */
fun headsetStatusOf(devices: List<InputDevice>): HeadsetStatus {
    val bluetooth = devices.filter { it.type in BLUETOOTH_AUDIO_TYPES }
    if (bluetooth.isEmpty()) return HeadsetStatus.NONE
    return HeadsetStatus(connected = true, name = bluetooth.firstNotNullOfOrNull { it.name?.takeIf(String::isNotBlank) })
}
