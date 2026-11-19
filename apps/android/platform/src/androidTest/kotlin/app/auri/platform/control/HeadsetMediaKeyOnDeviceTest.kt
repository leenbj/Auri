package app.auri.platform.control

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.ControlSource
import app.auri.capture.contract.NormalizedControlEvent
import app.auri.capture.control.ControlProfile
import app.auri.capture.testing.MediaSessionDump
import app.auri.capture.testing.MediaSessionInfo
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
 * 真实的系统媒体按键通道（MediaSession）在 Android 框架上的行为，证据等级 E2（模拟器；`adb shell input keyevent` 注入，不是耳机）。
 *
 * 证明——读系统自己的会话列表（`dumpsys media_session`），不靠“有没有收到键”：
 *  - 控制模式关着：系统里没有我们的会话；开着：有、已激活、声明的是 PAUSED 而不是 PLAYING；关掉：会话没了；再开：又有。
 *
 * 只测量、不断言（那是真机问题）：开着时注入的键能不能送到适配器。API 35 模拟器上的实测是**送不到**：
 * 系统按“实际在放音频的应用”挑媒体键会话，没有音频在播时 dump 里是 “Media button session is null”，与声明的 PlaybackState 无关。
 * 所以“关着时收不到键”在这类设备上是空转的证据（任何静音会话本来就收不到键），真正的把关是上面的会话列表断言。
 * 测得的数字写在 logcat 的 AuriControlTest 标签下，进 README 的结果表；万一某台设备真的送达了，
 * 送达的键仍要按种类归一化（下面最后一个测试），那时它才有牙齿。
 *
 * 不证明：蓝牙耳机（FreeClip）的按键种类、真机是否把键交给只声明 PAUSED 的会话、锁屏时是否还能收到——真机 E3
 * （README 的产品负责人步骤，NOT_RUN）。注入的键没有外接设备信息，所以就算送达，来源也是 UNKNOWN_REMOTE。
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

    /** 经系统的 shell 执行一条命令；读到命令结束才返回。 */
    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { String(it.readBytes()) }

    /** 经系统的输入服务注入一个按键（按下 + 抬起）。 */
    private fun inject(keyCode: Int) {
        shell("input keyevent $keyCode")
    }

    private fun next(timeoutMs: Long): NormalizedControlEvent? = received.poll(timeoutMs, TimeUnit.MILLISECONDS)

    private fun systemSessions(): MediaSessionDump = MediaSessionDump.parse(shell("dumpsys media_session"))

    /**
     * 等到系统的会话列表满足条件，返回最后一次看到的、本包名下的我们的会话（没有则 null）。
     * 会话的创建、激活、释放都是跨进程调用，不保证返回时系统已经处理完，所以要轮询；条件始终不满足就把最后所见交给调用方去断言。
     */
    private fun awaitOurSession(timeoutMs: Long = SETTLE_MS, condition: (MediaSessionInfo?) -> Boolean): MediaSessionInfo? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var ours = systemSessions().find(context.packageName, MEDIA_SESSION_TAG)
        while (!condition(ours) && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MS)
            ours = systemSessions().find(context.packageName, MEDIA_SESSION_TAG)
        }
        return ours
    }

    @Test
    fun sessionExistsInTheSystemOnlyWhileControlModeIsOn() {
        assertNull("控制模式没开：系统的会话列表里没有我们的会话", awaitOurSession { it == null })

        assertTrue("系统给了媒体会话", adapter.enableUserControlMode())
        val on = awaitOurSession { it?.active == true }
        // 同一个解析器在开着时读到了我们的会话，下面“没有”的断言才不是空转（读不到任何东西 = 永远“没有”）
        assertNotNull("控制模式开着：系统的会话列表里有我们的会话", on)
        assertTrue("会话已激活", on!!.active)
        logSessionHeaderAsTheSystemPrints()

        adapter.disableUserControlMode()
        assertNull("控制模式关掉之后：会话已释放", awaitOurSession { it == null })

        assertTrue(adapter.enableUserControlMode())
        assertNotNull("再次开启：又有了", awaitOurSession { it?.active == true })

        adapter.disableUserControlMode()
        assertNull("再次关掉：又没了", awaitOurSession { it == null })
    }

    @Test
    fun sessionDeclaresPausedNeverPlayingBecauseNothingPlays() {
        assertTrue(adapter.enableUserControlMode())

        val ours = awaitOurSession { it?.state != null }

        assertNotNull("先要读得到我们的会话", ours)
        assertEquals("声明的是暂停：本应用什么都没在播，不假装在播放（ADR / PRD NFR-012）", "PAUSED", ours!!.state)
    }

    @Test
    fun mediaKeysReachNothingWhileControlModeIsOff() {
        inject(KEYCODE_MEDIA_PLAY_PAUSE)
        inject(KEYCODE_HEADSETHOOK)

        // 注意：在没有音频播放的设备上这条本来就恒成立（见类注释），真正的把关是会话列表那条
        assertNull("控制模式没开：适配器不该收到任何事件", next(SILENCE_MS))
    }

    @Test
    fun injectedMediaKeysAreMeasuredAndWhateverArrivesIsNormalizedByKind() {
        assertTrue(adapter.enableUserControlMode())
        assertNotNull(awaitOurSession { it?.active == true })
        val mediaButtonSession = systemSessions().mediaButtonSession

        val injected = listOf(
            KEYCODE_MEDIA_PLAY_PAUSE to BusinessCommand.TOGGLE,
            KEYCODE_MEDIA_PLAY to BusinessCommand.START,
            KEYCODE_MEDIA_PAUSE to BusinessCommand.STOP,
            KEYCODE_HEADSETHOOK to BusinessCommand.TOGGLE,
        )
        val arrived = mutableListOf<Pair<Int, NormalizedControlEvent>>()
        for ((code, _) in injected) {
            inject(code)
            next(DELIVERY_WAIT_MS)?.let { arrived += code to it }
            Thread.sleep(GESTURE_GAP_MS)
        }

        Log.i(
            TAG,
            "measured sdk=${Build.VERSION.SDK_INT} media_button_session=${mediaButtonSession ?: "null"} " +
                "injected=${injected.size} delivered=${arrived.size}",
        )
        // 送达的每一个键都要按种类归一化；一个都没送达时这里没有断言（类注释说明了为什么）
        val expectedByKey = injected.toMap()
        arrived.forEach { (code, event) ->
            assertEquals("按键 $code 的业务命令", expectedByKey.getValue(code), event.command)
            assertTrue("来源只能是耳机遥控或未知遥控，没有屏幕按钮", event.source == ControlSource.HEADSET_REMOTE || event.source == ControlSource.UNKNOWN_REMOTE)
            assertEquals("控制事件带着档案标识", ControlProfile.GENERIC_ID, event.profileId)
        }
        val sequences = arrived.map { it.second.sequence }
        assertEquals("序号严格递增", sequences.sorted(), sequences)
        assertEquals(sequences.size, sequences.toSet().size)
    }

    /** 把系统打印的会话表头原样记下来（空格换成 ·）：解析器的格式假设每次都用真实输出留证。 */
    private fun logSessionHeaderAsTheSystemPrints() {
        val header = shell("dumpsys media_session").lineSequence().firstOrNull { it.trim().startsWith("$MEDIA_SESSION_TAG ") }
        Log.i(TAG, "measured session_header_raw=[${header?.replace(' ', '·')}]")
    }

    private companion object {
        const val TAG = "AuriControlTest"
        const val KEYCODE_MEDIA_PLAY_PAUSE = 85
        const val KEYCODE_MEDIA_PLAY = 126
        const val KEYCODE_MEDIA_PAUSE = 127
        const val KEYCODE_HEADSETHOOK = 79

        /** 比去抖窗口（默认 400 ms）长：两次注入是两个不同的手势。 */
        const val GESTURE_GAP_MS = 700L

        /** 系统分发媒体键是毫秒级的；送得到的话 2 秒绰绰有余，送不到就不必干等。 */
        const val DELIVERY_WAIT_MS = 2_000L
        const val SETTLE_MS = 3_000L
        const val POLL_MS = 100L

        /** “什么都没收到”要等多久才算数。 */
        const val SILENCE_MS = 1_500L
    }
}
