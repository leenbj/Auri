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
}
