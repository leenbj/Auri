package app.auri.platform.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * 麦克风类前台服务：采集在进行时让进程保持“正在使用麦克风”的状态，否则应用一进后台系统就会把录音静音。
 * 它只是一个保活外壳，不碰音频；开关由 [bindForegroundService] 跟着采集状态走。
 *
 * - startForeground 放在 onCreate：startForegroundService 之后系统要求尽快调用它，放在 onStartCommand 里时，
 *   命令一到就被停掉的服务会因没调用而崩溃。
 * - 停止是一条命令（[ACTION_STOP]），不是 stopService：服务连 onCreate 都没执行时 stopService 会让进程崩溃，
 *   命令则按顺序排在 onCreate 之后。见 [AndroidForegroundServiceController]。
 * - START_NOT_STICKY：进程被杀后不会被系统自动拉起，更不会自己重新开麦（第 12 章：进程恢复后回到 DISARMED）。
 * - 通知只写“正在录音”，不含任何转录内容。
 * - 对应的权限（FOREGROUND_SERVICE_MICROPHONE）与运行时麦克风权限是两层，开始前的资格检查两层都查；
 *   这两层都过了 startForeground 才不会抛，所以下面 catch 里的 stopSelf 只是兜底（此时系统仍可能因没进前台而终止进程）。
 */
class MicCaptureForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "录音状态", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Auri 正在录音")
            .setContentText("使用蓝牙耳机麦克风")
            .setOngoing(true)
            .build()
        try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: IllegalStateException) {
            stopSelf() // 系统不允许此刻启动前台服务
        } catch (e: SecurityException) {
            stopSelf() // 缺少麦克风前台服务的权限
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 带 startId：只在这是最新一条命令时才停，比它新的启动命令还在路上时 stopSelf(startId) 不生效，
        // 新一轮采集的服务不会被上一轮的停止命令连带关掉
        if (intent?.action == ACTION_STOP) stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "app.auri.platform.action.START_CAPTURE_SERVICE"
        const val ACTION_STOP = "app.auri.platform.action.STOP_CAPTURE_SERVICE"
        private const val CHANNEL_ID = "auri-capture"
        private const val NOTIFICATION_ID = 1
    }
}
