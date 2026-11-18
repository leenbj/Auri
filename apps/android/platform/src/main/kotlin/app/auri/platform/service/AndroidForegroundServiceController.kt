package app.auri.platform.service

import android.content.Context
import android.content.Intent

class AndroidForegroundServiceController(private val context: Context) : ForegroundServiceController {
    private fun intent() = Intent(context, MicCaptureForegroundService::class.java)

    override fun start(): Boolean = try {
        context.startForegroundService(intent())
        true
    } catch (e: IllegalStateException) {
        false // ForegroundServiceStartNotAllowedException：应用不在允许启动前台服务的状态
    } catch (e: SecurityException) {
        false // 缺少麦克风前台服务的权限
    }

    override fun stop() {
        context.stopService(intent())
    }
}
