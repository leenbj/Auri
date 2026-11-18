package app.auri.platform.system

import android.content.Context
import android.app.KeyguardManager
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 屏幕锁定监视器跟着系统的熄屏 / 亮屏走（证据等级 E2：模拟器，用 `input keyevent` 熄屏）。
 * 只证明监视器读到并报告系统的屏幕状态；锁屏时耳机手势还管不管用，是真机上校准向导的第四步。
 *
 * 熄屏键是个不太可靠的“刺激”：亮屏是异步的，PowerManager 会忽略事件时间早于“最近一次亮屏”的熄屏请求，
 * 紧接着亮屏发的熄屏键可能被吞掉（这个测试曾因此间歇性失败）。所以先等亮屏落定，熄屏键没生效就补发，
 * 并且先确认“系统真的熄屏了”，再要求监视器跟上——这样“刺激没生效”和“监视器没跟上”是两种不同的失败，不会混在一起。
 */
@RunWith(AndroidJUnit4::class)
class ScreenLockOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val power: PowerManager = context.getSystemService(PowerManager::class.java)
    private val keyguard: KeyguardManager = context.getSystemService(KeyguardManager::class.java)
    private val monitor = AndroidScreenLockMonitor(context)

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    private inline fun await(timeoutMs: Long, everyMs: Long = POLL_MS, onTick: (elapsedMs: Long) -> Unit = {}, condition: () -> Boolean): Boolean {
        val start = System.nanoTime()
        while (true) {
            if (condition()) return true
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            if (elapsedMs >= timeoutMs) return false
            onTick(elapsedMs)
            Thread.sleep(everyMs)
        }
    }

    private fun wakeUpAndSettle() {
        shell("input keyevent KEYCODE_WAKEUP")
        if (!await(TIMEOUT_MS) { power.isInteractive }) fail("系统没有亮屏（刺激没生效，不是监视器的问题）")
        Thread.sleep(WAKE_SETTLE_MS)
    }

    @After
    fun restore() {
        monitor.stop()
        shell("input keyevent KEYCODE_WAKEUP")
    }

    @Test
    fun monitorReportsKeyguardStateAfterTheScreenTurnsOff() {
        wakeUpAndSettle()
        monitor.start()

        var nextResend = RESEND_EVERY_MS
        shell("input keyevent KEYCODE_SLEEP")
        val systemTurnedOff = await(TIMEOUT_MS, onTick = { elapsedMs ->
            if (elapsedMs >= nextResend) {
                shell("input keyevent KEYCODE_SLEEP")
                nextResend += RESEND_EVERY_MS
            }
        }) { !power.isInteractive }
        if (!systemTurnedOff) fail("系统没有熄屏（熄屏键补发过也没生效：刺激的问题，不是监视器的问题）")

        val reported = await(TIMEOUT_MS) { monitor.locked.value == lockedFrom(power.isInteractive, keyguard.isKeyguardLocked) }
        assertEquals("屏幕熄灭后监视器应当报告实际 keyguard 状态", true, reported)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 100L
        const val WAKE_SETTLE_MS = 1_500L
        const val RESEND_EVERY_MS = 2_500L
    }
}
