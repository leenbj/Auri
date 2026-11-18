package app.auri.platform.system

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 熄屏或锁屏界面在显示，都算“锁着”：校准“锁屏后手势还管不管用”只关心屏幕不可交互这一件事。 */
fun lockedFrom(interactive: Boolean, keyguardLocked: Boolean): Boolean = !interactive || keyguardLocked

/**
 * 屏幕是不是锁着：true = 熄屏或锁屏界面显示中，false = 亮着且已解锁，null = 读不出来（框架抛了异常）。
 * 只读系统状态，不持有任何通路。监听熄屏 / 亮屏 / 解锁三个系统广播（动态注册，清单里没有广播接收器）。
 * 校准向导靠它给“锁屏时”的观察打标签；它不是兼容结论的证据，证据只来自录音状态与手势。
 */
class AndroidScreenLockMonitor(
    private val context: Context,
    private val log: (String) -> Unit = {},
) {
    private val state = MutableStateFlow(read())
    private var registered = false

    val locked: StateFlow<Boolean?> = state

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            state.value = read()
        }
    }

    @Synchronized
    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            registered = true
        } catch (e: Exception) {
            log("screen_lock_monitor_start_failed reason=${e.javaClass.simpleName}")
        }
        state.value = read()
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        registered = false
        try {
            context.unregisterReceiver(receiver)
        } catch (e: Exception) {
            log("screen_lock_monitor_stop_failed reason=${e.javaClass.simpleName}")
        }
    }

    private fun read(): Boolean? = try {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        lockedFrom(power.isInteractive, keyguard.isKeyguardLocked)
    } catch (e: Exception) {
        log("screen_lock_read_failed reason=${e.javaClass.simpleName}")
        null
    }
}
