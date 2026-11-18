package app.auri.platform.system

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 屏幕锁定监视器跟着系统的熄屏 / 亮屏走（证据等级 E2：模拟器，用 `input keyevent` 熄屏）。
 * 只证明监视器读到并报告系统的屏幕状态；锁屏时耳机手势还管不管用，是真机上校准向导的第四步。
 */
@RunWith(AndroidJUnit4::class)
class ScreenLockOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val monitor = AndroidScreenLockMonitor(context)

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    private fun awaitLocked(expected: Boolean) {
        repeat(100) {
            if (monitor.locked.value == expected) return
            Thread.sleep(100)
        }
        assertEquals("监视器应当报告 locked=$expected", expected, monitor.locked.value)
    }

    @After
    fun restore() {
        monitor.stop()
        shell("input keyevent KEYCODE_WAKEUP")
    }

    @Test
    fun monitorReportsLockedAfterTheScreenTurnsOff() {
        shell("input keyevent KEYCODE_WAKEUP")
        monitor.start()

        shell("input keyevent KEYCODE_SLEEP")

        awaitLocked(true)
    }
}
