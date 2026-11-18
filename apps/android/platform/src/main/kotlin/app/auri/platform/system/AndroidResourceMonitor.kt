package app.auri.platform.system

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import app.auri.capture.scheduler.ResourcePolicy
import app.auri.capture.scheduler.ResourceMonitor
import app.auri.capture.scheduler.ResourceReadings
import app.auri.capture.scheduler.ResourceStatus
import app.auri.capture.scheduler.ResourceThresholds
import java.io.File
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/**
 * 手机此刻转得动吗：把系统的四个读数交给 [ResourcePolicy] 判断（Ticket 20）。
 *  - 温度：`PowerManager.getCurrentThermalStatus()`（API 29+；本应用 minSdk 31），变化走 `addThermalStatusListener`；
 *  - 省电模式：`PowerManager.isPowerSaveMode`，变化走系统广播 `ACTION_POWER_SAVE_MODE_CHANGED`；
 *  - 内存：`ActivityManager.MemoryInfo` 的 `availMem` 与 `lowMemory`；
 *  - 存储：应用私有目录所在分区的 `StatFs.availableBytes`。
 * 内存与存储没有变化回调，所以 [changes] 只管温度与省电模式，其余靠调度器按有界的间隔自己重查（见 [app.auri.capture.scheduler.SchedulerConfig.resourceRecheckMs]）。
 * 订阅 [changes] 时系统会立刻回报一次当前温度状态（`addThermalStatusListener` 注册即回调），所以订阅方先收到一个信号：只是多重查一次，无害；
 * 但要证明“变化会通知”的人必须丢掉它（见 AndroidResourceMonitorTest）。
 * 读数不含任何内容或标识。读不出来就抛异常：调度器把监视器故障当作“可用”处理（不让一个读不出来的传感器永远挡住转录，
 * 引擎自己的失败路径兜底；SchedulerWaitingTest 钉住这一条）。
 *
 * 阈值是没有真机标定的保守初值（[ResourceThresholds]）；这些读数在真机上的实际表现是 NOT_RUN，步骤见 execution/acceptance/android-models/README.md。
 */
class AndroidResourceMonitor(
    context: Context,
    private val thresholds: ResourceThresholds = ResourceThresholds(),
    private val storage: File = context.applicationContext.filesDir,
) : ResourceMonitor {
    private val app = context.applicationContext
    private val power = app.getSystemService(PowerManager::class.java)
    private val activity = app.getSystemService(ActivityManager::class.java)

    fun readings(): ResourceReadings {
        val memory = ActivityManager.MemoryInfo().also { activity.getMemoryInfo(it) }
        return ResourceReadings(
            thermalStatus = power.currentThermalStatus,
            batterySaver = power.isPowerSaveMode,
            availableMemoryBytes = memory.availMem,
            systemLowMemory = memory.lowMemory,
            freeStorageBytes = StatFs(storage.path).availableBytes,
        )
    }

    override fun check(): ResourceStatus = ResourcePolicy.evaluate(readings(), thresholds)

    override val changes: Flow<Unit> = callbackFlow {
        val thermal = PowerManager.OnThermalStatusChangedListener { trySend(Unit) }
        val saver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(Unit)
            }
        }
        power.addThermalStatusListener(Runnable::run, thermal)
        val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(saver, filter, Context.RECEIVER_NOT_EXPORTED) else app.registerReceiver(saver, filter)
        awaitClose {
            power.removeThermalStatusListener(thermal)
            app.unregisterReceiver(saver)
        }
    }.buffer(Channel.CONFLATED)
}
