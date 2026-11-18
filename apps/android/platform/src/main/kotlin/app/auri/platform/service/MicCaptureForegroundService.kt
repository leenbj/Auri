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
 * - startForeground 放在 onCreate：startForegroundService 之后系统要求尽快调用它，
 *   服务若在 onStartCommand 之前就被停掉（路由很快失败），放在 onStartCommand 里会因没调用而崩溃。
 * - START_NOT_STICKY：进程被杀后不会被系统自动拉起，更不会自己重新开麦（第 12 章：进程恢复后回到 DISARMED）。
 * - 通知只写“正在录音”，不含任何转录内容。
 * - 对应的权限（FOREGROUND_SERVICE_MICROPHONE）与运行时麦克风权限是两层，开始前的资格检查两层都查。
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CHANNEL_ID = "auri-capture"
        const val NOTIFICATION_ID = 1
    }
}
