package app.auri.platform.system

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.auri.capture.scheduler.ResourceReason
import app.auri.capture.scheduler.ResourceStatus
import app.auri.capture.scheduler.ResourceThresholds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ticket 20 第 4 项：资源监视器读的是系统的真实读数（温度、省电模式、内存、存储），而且变化会通知调度器。
 * 在模拟器上用 shell 命令改系统状态（`cmd thermalservice override-status`、`cmd power set-mode`、`dumpsys battery unplug`）：
 * 这证明“系统接口的接线是对的”（E2）；真机发烫、内存吃紧时的实际表现是 NOT_RUN（步骤见 execution/acceptance/android-models/README.md）。
 *
 * 两条规矩，都是这个文件的上一版栽过的（省电模式那个测试在模拟器上 12 次失败 4 次）：
 *  1. 改系统状态之前先**等系统认到前一步**：`dumpsys battery unplug` 要过几百毫秒系统才认到，而系统在“插着电”时会悄悄吞掉“开省电模式”
 *     （状态机：插电 + 手动开启 = 什么也不做）。所以先轮询到系统自己说“没插电”，再开。
 *  2. “变化信号”必须是**触发之后**来的那一个：订阅温度监听时系统会立刻回报一次当前状态（注册即回调），上一版把它当成了“变化”，
 *     什么都没变测试就往下走了——信号这一半其实什么也没证明，还把上一条的竞争藏了起来。
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

    /** 轮询到 [condition] 成立；超时就失败并说明在等什么，绝不在条件没成立时往下测。 */
    private fun awaitSystem(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val start = System.nanoTime()
        while (!condition()) {
            if ((System.nanoTime() - start) / 1_000_000 > timeoutMs) fail("等了 $timeoutMs 毫秒：$what")
            Thread.sleep(100)
        }
    }

    /**
     * 订阅 [monitor] 的变化信号，做一次 [trigger]，要求“触发之后”来一个新信号，没来就失败。
     * 订阅后先等系统的初始回报来了并丢掉（见类注释第 2 条；没有初始回报也不碍事）。
     */
    private fun awaitChangeSignalAfter(trigger: () -> Unit) = runBlocking {
        val signals = Channel<Unit>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) { monitor.changes.collect { signals.trySend(Unit) } }
        try {
            withTimeoutOrNull(3_000) { signals.receive() }
            delay(500) // 让订阅完整建好，再把紧跟在初始回报后面的丢掉
            while (signals.tryReceive().isSuccess) { /* 丢掉 */ }
            trigger()
            withTimeoutOrNull(10_000) { signals.receive() } ?: fail("触发之后 10 秒内没有收到变化信号")
        } finally {
            collector.cancel()
        }
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

    @Test
    fun aThermalStatusChangeIsAnnouncedAndMakesTheJobWait() {
        // 先放到“不热”，不管这台设备本来几级：下面的第一步一定是一次真实的变化
        shell("cmd thermalservice override-status 0")
        awaitSystem("热状态没有回到 0") { monitor.readings().thermalStatus == 0 }

        awaitChangeSignalAfter { shell("cmd thermalservice override-status 3") }
        assertEquals("信号来的时候读数已经是新的", ResourceStatus.Low(ResourceReason.THERMAL), monitor.check())

        awaitChangeSignalAfter { shell("cmd thermalservice override-status 0") }
        assertTrue("热状态恢复后不再因温度等待：${monitor.check()}", monitor.check() != ResourceStatus.Low(ResourceReason.THERMAL))
    }

    /** `dumpsys power` 里系统自己（电源服务与省电状态机各有一行）都说没插电。 */
    private fun unplugged(dump: String): Boolean {
        val lines = dump.lines().map { it.trim() }
        return "mIsPowered=false" in lines && "mIsPowered=true" !in lines
    }

    @Test
    fun batterySaverIsAnnouncedAndMakesTheJobWait() {
        shell("dumpsys battery unplug")
        awaitSystem("系统一直没认到电源已拔掉") { unplugged(shell("dumpsys power")) }

        awaitChangeSignalAfter { shell("cmd power set-mode 1") }
        assertTrue("信号来的时候系统报告省电模式已开", monitor.readings().batterySaver)
        assertEquals(ResourceStatus.Low(ResourceReason.BATTERY_SAVER), monitor.check())

        awaitChangeSignalAfter { shell("cmd power set-mode 0") }
        assertTrue("省电模式关掉后不再等：${monitor.check()}", monitor.check() != ResourceStatus.Low(ResourceReason.BATTERY_SAVER))
    }
}
