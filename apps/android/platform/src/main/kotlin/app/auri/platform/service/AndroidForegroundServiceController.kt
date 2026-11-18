package app.auri.platform.service

import android.content.Context
import android.content.Intent
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [ForegroundServiceController] 的 Android 实现。
 *
 * “停”不直接 stopService，而是再发一条 startForegroundService（命令 [MicCaptureForegroundService.ACTION_STOP]）让服务自己停：
 * startForegroundService 之后、服务调用 startForeground 之前，系统不允许服务被拆掉，直接 stopService 抢在前面会让
 * 整个进程以 ForegroundServiceDidNotStartInTimeException 崩溃（logcat：“Bringing down service while still waiting for
 * start foreground”）。拒绝路径每次都撞这个时序：状态进入 ROUTING 就起服务，几十毫秒后系统说没有蓝牙输入、
 * 状态变成 BLOCKED 就要停（API 35 模拟器实测，见验收文档）。系统按顺序投递命令，所以停止命令总在 onCreate 里的
 * startForeground 之后才执行。
 */
class AndroidForegroundServiceController(private val context: Context) : ForegroundServiceController {
    // 只停自己起过的服务；绑定器在 DISARMED 初始状态也会调一次 stop，没起过就什么都不发（否则会为了停服务先起一个）
    private val started = AtomicBoolean(false)

    private fun intent(action: String) = Intent(context, MicCaptureForegroundService::class.java).setAction(action)

    override fun start(): Boolean = try {
        context.startForegroundService(intent(MicCaptureForegroundService.ACTION_START))
        started.set(true)
        true
    } catch (e: IllegalStateException) {
        false // ForegroundServiceStartNotAllowedException：应用不在允许启动前台服务的状态
    } catch (e: SecurityException) {
        false // 缺少麦克风前台服务的权限
    }

    override fun stop() {
        if (!started.getAndSet(false)) return
        try {
            context.startForegroundService(intent(MicCaptureForegroundService.ACTION_STOP))
        } catch (e: IllegalStateException) {
            // shortcut: 此刻（应用在后台）系统不收新的 startForegroundService 就直接停；服务通常早已在前台，只有刚起就进后台、
            // 还没 startForeground 的几毫秒里系统仍会终止进程；真机后台场景见验收文档 §4，出现了再改成由服务自己停
            context.stopService(intent(MicCaptureForegroundService.ACTION_STOP))
        }
    }
}
