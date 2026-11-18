package app.auri.platform.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import app.auri.capture.contract.LifecycleState
import app.auri.platform.audio.CaptureEnvironment

/** 权限与前台服务是两层：运行时权限（麦克风、蓝牙）与“麦克风类前台服务”的权限分开问。 */
class AndroidCaptureEnvironment(
    private val context: Context,
    private val tracker: AppLifecycleTracker,
) : CaptureEnvironment {
    override fun micPermissionGranted() = granted(Manifest.permission.RECORD_AUDIO)

    override fun bluetoothPermissionGranted() = granted(Manifest.permission.BLUETOOTH_CONNECT)

    // 这个权限从 API 34 才有（安装时授予）；更低版本没有这一层
    override fun microphoneServicePermissionGranted() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || granted(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)

    override fun lifecycle(): LifecycleState = tracker.lifecycle()

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
