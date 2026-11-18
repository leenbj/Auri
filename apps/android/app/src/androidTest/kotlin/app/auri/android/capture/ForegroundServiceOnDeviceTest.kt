package app.auri.android.capture

import android.Manifest
import android.app.Application
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import app.auri.android.DeviceEnv
import app.auri.android.MainActivity
import app.auri.platform.service.AndroidForegroundServiceController
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 麦克风类前台服务在真实 Android 框架上的样子：应用在前台、有麦克风权限时能启动，系统记录它是前台服务且类型是麦克风；
 * 停止之后它不在了。证据等级：模拟器 = E2；后台启动被拒、锁屏、多种耳机下的行为要真机。
 */
@RunWith(AndroidJUnit4::class)
class ForegroundServiceOnDeviceTest {
    @get:Rule
    val grant: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private fun dump(): String = DeviceEnv.shell("dumpsys activity services ${DeviceEnv.target.packageName}")

    private fun awaitDump(timeoutMs: Long = 15_000, predicate: (String) -> Boolean): String {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var last = ""
        while (System.nanoTime() < deadline) {
            last = dump()
            if (predicate(last)) return last
            Thread.sleep(250)
        }
        return last
    }

    /** 当前获得焦点的窗口那一行（`dumpsys window` 的 mCurrentFocus），等到满足条件或超时，返回最后一次看到的那一行。 */
    private fun awaitFocus(timeoutMs: Long = 15_000, predicate: (String) -> Boolean): String {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var last = ""
        while (System.nanoTime() < deadline) {
            last = DeviceEnv.shell("dumpsys window").lineSequence().firstOrNull { "mCurrentFocus=" in it }.orEmpty()
            if (predicate(last)) return last
            Thread.sleep(250)
        }
        return last
    }

    @Test
    fun serviceRunsAsMicrophoneForegroundServiceAndStops() {
        val app = DeviceEnv.target.applicationContext as Application
        val controller = AndroidForegroundServiceController(app)
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue("应用在前台、有麦克风权限时应能启动麦克风前台服务", controller.start())

            val running = awaitDump { it.contains("MicCaptureForegroundService") && it.contains("isForeground=true") }
            assertTrue("系统应记录它是前台服务：\n$running", running.contains("MicCaptureForegroundService") && running.contains("isForeground=true"))
            // FOREGROUND_SERVICE_TYPE_MICROPHONE = 0x80
            assertTrue("类型应是麦克风（0x80）：\n$running", Regex("0x0*80\\b").containsMatchIn(running))

            controller.stop()
            val stopped = awaitDump { !it.contains("MicCaptureForegroundService") }
            assertFalse("停止之后服务应该不在了：\n$stopped", stopped.contains("MicCaptureForegroundService"))
        }
    }

    /**
     * 拒绝路径的真实时序：状态一进入 ROUTING 就起服务，几十毫秒后系统说“没有蓝牙输入”、状态变成 BLOCKED 就停服务。
     * 停得比服务调用 startForeground() 还早，系统会让整个进程以 ForegroundServiceDidNotStartInTimeException 崩溃
     * （logcat：“Bringing down service while still waiting for start foreground”）。上一个测试等服务进了前台才停，碰不到这个时序。
     * 这里连续起停十次、不给服务任何喘息：进程必须活着，最后服务必须不在，之后也不会自己冒出来（START_NOT_STICKY）。
     */
    @Test
    fun stoppingRightAfterStartingNeverKillsTheProcessAndLeavesNoServiceBehind() {
        val app = DeviceEnv.target.applicationContext as Application
        val controller = AndroidForegroundServiceController(app)
        ActivityScenario.launch(MainActivity::class.java).use {
            repeat(10) {
                assertTrue("应用在前台时应能启动麦克风前台服务", controller.start())
                controller.stop()
            }

            val stopped = awaitDump { !it.contains("MicCaptureForegroundService") }
            assertFalse("最后一次停止之后服务应该不在了：\n$stopped", stopped.contains("MicCaptureForegroundService"))
            // 系统若要因此让进程崩溃，会在服务被拆掉之后两百毫秒内动手（实测）：多等一会儿，崩溃的话进程会在这期间被杀
            Thread.sleep(2_000)
            val later = dump()
            assertFalse("之后服务不应该自己冒出来：\n$later", later.contains("MicCaptureForegroundService"))
        }
    }

    /**
     * 应用已在后台时停服务（时长上限到点、耳机在后台断开都会这样）：停止必须照样送达，服务要消失，进程要活着。
     * 后台里再发 startForegroundService 系统收不收没有文档保证，所以在 API 35 模拟器上实测；
     * 先确认应用真的不在前台（窗口焦点不是本应用），否则这个测试什么也没证明。
     */
    @Test
    fun stoppingWhileTheAppIsInTheBackgroundStillStopsTheService() {
        val app = DeviceEnv.target.applicationContext as Application
        val controller = AndroidForegroundServiceController(app)
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue("应用在前台时应能启动麦克风前台服务", controller.start())
            val running = awaitDump { it.contains("MicCaptureForegroundService") && it.contains("isForeground=true") }
            assertTrue("服务应已进入前台：\n$running", running.contains("isForeground=true"))

            DeviceEnv.shell("input keyevent KEYCODE_HOME")
            // 找不到焦点行时返回空串：不能把“没看到”当成“不在前台”
            val inBackground = { focus: String -> focus.isNotEmpty() && !focus.contains(DeviceEnv.target.packageName) }
            val focus = awaitFocus(predicate = inBackground)
            assertTrue("按了 Home 之后应能看到别的应用是焦点窗口：「$focus」", inBackground(focus))

            controller.stop()
            val stopped = awaitDump { !it.contains("MicCaptureForegroundService") }
            assertFalse("应用在后台时停止也应生效：\n$stopped", stopped.contains("MicCaptureForegroundService"))
            Thread.sleep(2_000) // 崩溃的话进程会在这期间被杀
        }
    }
}
