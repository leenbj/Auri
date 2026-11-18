package app.auri.platform.system

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.auri.capture.scheduler.ResourceReason
import app.auri.capture.scheduler.ResourceStatus
import app.auri.capture.scheduler.ResourceThresholds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ticket 20 第 4 项：资源监视器读的是系统的真实读数（温度、省电模式、内存、存储），而且变化会通知调度器。
 * 在模拟器上用 shell 命令改系统状态（`cmd thermalservice override-status`、`cmd power set-mode`、`dumpsys battery unplug`）：
 * 这证明“系统接口的接线是对的”（E2）；真机发烫、内存吃紧时的实际表现是 NOT_RUN（步骤见 execution/acceptance/android-models/README.md）。
 */
@RunWith(AndroidJUnit4::class)
class AndroidResourceMonitorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val monitor = AndroidResourceMonitor(context)

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    @After
    fun restoreSystem() {
        shell("cmd thermalservice reset")
        shell("cmd power set-mode 0")
        shell("dumpsys battery reset")
    }

    @Test
    fun readingsComeFromTheSystem() {
        val readings = monitor.readings()

        assertTrue("热状态是 0..6：${readings.thermalStatus}", readings.thermalStatus in 0..6)
        assertTrue("可用内存 > 0：${readings.availableMemoryBytes}", readings.availableMemoryBytes > 0)
        assertTrue("可用存储 > 0：${readings.freeStorageBytes}", readings.freeStorageBytes > 0)
        println("AuriResourceReadings thermal=${readings.thermalStatus} saver=${readings.batterySaver} availMemMiB=${readings.availableMemoryBytes shr 20} lowMemory=${readings.systemLowMemory} freeStorageMiB=${readings.freeStorageBytes shr 20}")
    }

    @Test
    fun everyReadingReachesThePolicy() {
        // 阈值取极端值：任何一个读数没有接到策略上（写死了常数），对应的断言就不成立
        assertEquals(
            ResourceStatus.Low(ResourceReason.THERMAL),
            AndroidResourceMonitor(context, ResourceThresholds(thermalBlockAt = 0)).check(),
        )
        assertEquals(
            ResourceStatus.Low(ResourceReason.LOW_MEMORY),
            AndroidResourceMonitor(context, ResourceThresholds(minAvailableMemoryBytes = Long.MAX_VALUE)).check(),
        )
        assertEquals(
            ResourceStatus.Low(ResourceReason.LOW_STORAGE),
            AndroidResourceMonitor(context, ResourceThresholds(minFreeStorageBytes = Long.MAX_VALUE)).check(),
        )
    }

    /** 一直做 [trigger] 直到 [monitor] 的变化信号来了；信号一直没来就失败。 */
    private fun awaitChangeSignal(trigger: () -> Unit) = runBlocking {
        withTimeout(20_000) {
            val job = launch {
                repeat(20) {
                    trigger()
                    delay(800)
                }
            }
            try {
                monitor.changes.first()
            } finally {
                job.cancel()
            }
        }
    }

    @Test
    fun aThermalStatusChangeIsAnnouncedAndMakesTheJobWait() {
        var status = 2
        awaitChangeSignal {
            status = if (status == 2) 3 else 2 // 来回切，确保真的有“变化”
            shell("cmd thermalservice override-status $status")
        }
        shell("cmd thermalservice override-status 3")
        runBlocking { delay(500) }

        assertEquals(ResourceStatus.Low(ResourceReason.THERMAL), monitor.check())
        shell("cmd thermalservice override-status 0")
        runBlocking { delay(500) }
        assertTrue("热状态恢复后不再因温度等待：${monitor.check()}", monitor.check() != ResourceStatus.Low(ResourceReason.THERMAL))
    }

    @Test
    fun batterySaverIsAnnouncedAndMakesTheJobWait() {
        shell("dumpsys battery unplug") // 插着电时系统会自己关掉省电模式
        var on = false
        awaitChangeSignal {
            on = !on
            shell("cmd power set-mode ${if (on) 1 else 0}")
        }
        shell("cmd power set-mode 1")
        runBlocking { delay(1_000) }

        assertTrue("系统报告省电模式已开", monitor.readings().batterySaver)
        assertEquals(ResourceStatus.Low(ResourceReason.BATTERY_SAVER), monitor.check())
        shell("cmd power set-mode 0")
        runBlocking { delay(1_000) }
        assertTrue("省电模式关掉后不再等：${monitor.check()}", monitor.check() != ResourceStatus.Low(ResourceReason.BATTERY_SAVER))
    }
}
