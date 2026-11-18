package app.auri.platform.control

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.ControlSource
import app.auri.capture.contract.NormalizedControlEvent
import app.auri.capture.contract.PlatformEvent
import app.auri.capture.control.ControlProfile
import app.auri.platform.system.RandomIds
import app.auri.platform.system.SystemClock
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真实的系统媒体按键通道（MediaSession）上的行为，证据等级 E2（模拟器；`adb shell input keyevent` 注入，不是耳机）。
 *
 * 证明：控制模式关着时媒体键到不了适配器；开着时到得了，并且归一化成带来源和序号的控制事件；关掉之后会话真的释放了
 * （再按没有人收到）；再开又收得到。
 * 不证明：蓝牙耳机（FreeClip）的按键种类、PAUSED 会话在真机上能否收到耳机键、锁屏时是否还能收到、
 * 事件来源能否被系统确认为外接设备——这些是真机问题（README 的产品负责人步骤，NOT_RUN）。
 * 注入的键没有外接设备信息，所以来源是 UNKNOWN_REMOTE；测得的来源写在 logcat 的 AuriControlTest 标签下。
 */
@RunWith(AndroidJUnit4::class)
class HeadsetMediaKeyOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val received = LinkedBlockingQueue<NormalizedControlEvent>()
    private val adapter = HeadsetControlAdapter(
        source = AndroidMediaButtonSource(context, SystemClock) { Log.i(TAG, it) },
        ids = RandomIds,
        profile = { ControlProfile.generic() },
        log = { Log.i(TAG, it) },
    )

    @Before
    fun subscribe() {
        adapter.onCommand { received.add(it) }
    }

    @After
    fun release() {
        adapter.disableUserControlMode()
    }

    /** 经系统的输入服务注入一个按键（按下 + 抬起）；读到命令结束才返回。 */
    private fun inject(keyCode: Int) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input keyevent $keyCode")).use { it.readBytes() }
    }

    private fun next(timeoutMs: Long = DELIVERY_TIMEOUT_MS): NormalizedControlEvent? = received.poll(timeoutMs, TimeUnit.MILLISECONDS)

    @Test
    fun mediaKeysReachNothingWhileControlModeIsOff() {
        inject(KEYCODE_MEDIA_PLAY_PAUSE)
        inject(KEYCODE_HEADSETHOOK)

        assertNull("控制模式没开：适配器不该收到任何事件", next(SILENCE_MS))
    }

    @Test
    fun mediaKeyReachesTheAdapterAsANormalizedEventWhileControlModeIsOn() {
        assertTrue("系统给了媒体会话", adapter.enableUserControlMode())

        inject(KEYCODE_MEDIA_PLAY_PAUSE)

        val event = next()
        assertNotNull("控制模式开着：媒体键应当到达适配器", event)
        event!!
        assertEquals(PlatformEvent.TOGGLE, event.platformEvent)
        assertEquals(BusinessCommand.TOGGLE, event.command)
        assertTrue("来源只能是耳机遥控或未知遥控，没有屏幕按钮", event.source == ControlSource.HEADSET_REMOTE || event.source == ControlSource.UNKNOWN_REMOTE)
        assertEquals("控制事件带着档案标识", ControlProfile.GENERIC_ID, event.profileId)
        assertTrue("序号单调递增，从 0 起", event.sequence >= 0)
        Log.i(TAG, "measured source=${event.source} platform_event=${event.platformEvent} command=${event.command} sequence=${event.sequence}")
    }

    @Test
    fun playPauseAndHookKeysAreNormalizedByKindOnTheRealSession() {
        assertTrue(adapter.enableUserControlMode())

        val seen = listOf(KEYCODE_MEDIA_PLAY, KEYCODE_MEDIA_PAUSE, KEYCODE_HEADSETHOOK).map { code ->
            inject(code)
            checkNotNull(next()) { "按键 $code 没有到达适配器" }.also { Thread.sleep(GESTURE_GAP_MS) }
        }

        assertEquals(listOf(BusinessCommand.START, BusinessCommand.STOP, BusinessCommand.TOGGLE), seen.map { it.command })
        assertEquals("序号严格递增", seen.map { it.sequence }.sorted(), seen.map { it.sequence })
        assertEquals(3, seen.map { it.sequence }.toSet().size)
    }

    @Test
    fun sessionIsReleasedWhenControlModeTurnsOffAndAcquiredAgainWhenItTurnsOn() {
        assertTrue(adapter.enableUserControlMode())
        inject(KEYCODE_MEDIA_PLAY_PAUSE)
        assertNotNull("开着时收得到", next())
        Thread.sleep(GESTURE_GAP_MS)

        adapter.disableUserControlMode()
        inject(KEYCODE_MEDIA_PLAY_PAUSE)
        assertNull("关掉之后会话已释放：再按没有人收到", next(SILENCE_MS))

        assertTrue(adapter.enableUserControlMode())
        inject(KEYCODE_MEDIA_PLAY_PAUSE)
        assertNotNull("再次开启又收得到", next())
    }

    private companion object {
        const val TAG = "AuriControlTest"
        const val KEYCODE_MEDIA_PLAY_PAUSE = 85
        const val KEYCODE_MEDIA_PLAY = 126
        const val KEYCODE_MEDIA_PAUSE = 127
        const val KEYCODE_HEADSETHOOK = 79

        /** 比去抖窗口（默认 400 ms）长：两次注入是两个不同的手势。 */
        const val GESTURE_GAP_MS = 700L
        const val DELIVERY_TIMEOUT_MS = 5_000L

        /** “什么都没收到”要等多久才算数：系统分发媒体键是毫秒级的，这里留足余量。 */
        const val SILENCE_MS = 1_500L
    }
}
