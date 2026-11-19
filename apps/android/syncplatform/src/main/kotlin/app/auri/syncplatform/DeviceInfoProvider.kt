package app.auri.syncplatform

import android.os.Build
import app.auri.sync.gateway.DeviceInfo

/**
 * 配对时告诉网关的设备信息（只用来在网关命令行里认出是哪台手机）。
 * 只取系统版本和厂商型号这类不识别个人的信息：没有序列号、IMEI、Android ID、构建指纹，也没有用户给设备起的名字。
 */
fun deviceInfo(appVersion: String): DeviceInfo =
    deviceInfoOf(appVersion, Build.VERSION.RELEASE, Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.MODEL)

internal fun deviceInfoOf(appVersion: String, release: String?, sdkInt: Int, manufacturer: String?, model: String?): DeviceInfo {
    val name = listOfNotNull(manufacturer?.trim(), model?.trim()).filter { it.isNotEmpty() }.joinToString(" ").take(100)
    return DeviceInfo(
        appVersion = appVersion.take(64).ifEmpty { "unknown" },
        osBuild = "Android ${release?.trim().orEmpty().ifEmpty { "?" }} (API $sdkInt)".take(100),
        displayName = name.ifEmpty { "Android 设备" },
    )
}
