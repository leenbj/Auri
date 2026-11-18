package app.auri.android.control

import android.Manifest
import android.app.Application
import android.media.AudioManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import app.auri.android.AuriRuntime
import app.auri.android.DeviceEnv
import app.auri.android.MainActivity
import app.auri.capture.adapter.PcmFormat
import app.auri.capture.adapter.TranscriptionAdapter
import app.auri.capture.adapter.TranscriptionRequest
import app.auri.capture.adapter.TranscriptionResult
import app.auri.capture.calibration.HeadsetProfile
import app.auri.capture.calibration.ProfileStore
import app.auri.capture.contract.AsrCapabilities
import app.auri.capture.contract.CaptureState
import app.auri.capture.contract.ErrorCode
import app.auri.capture.control.HeadsetControlStatus
import app.auri.capture.coordinator.CaptureDecision
import app.auri.capture.coordinator.CaptureSnapshot
import app.auri.capture.feedback.FeedbackKind
import app.auri.capture.testing.MediaSessionDump
import app.auri.capture.testing.MediaSessionInfo
import app.auri.platform.audio.AndroidAudioSystem
import app.auri.platform.audio.AudioDeviceTypes
import app.auri.platform.audio.AudioSystem
import app.auri.platform.audio.BluetoothInput
import app.auri.platform.audio.Recorder
import app.auri.platform.control.MEDIA_SESSION_TAG
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ticket 17 的应用级接线，用真的应用接线（真协调器、真媒体会话、真反馈泵、真视图模型，音频系统只是给真的包了个计数器）：
 *
 *  - 控制模式关着：系统的会话列表里没有我们的会话；系统媒体键到不了协调器——决定流里什么都没有（协调器对每个收到的输入
 *    至少给一条决定，连“忽略”都有，所以“一条都没有”说明输入根本没送到，而不是送到了被忽略）；
 *  - 控制模式开着：界面如实显示通道已占着，系统的会话列表里有我们的会话（已激活、PAUSED）。注入的媒体键能不能到协调器只测量、
 *    不断言——API 35 模拟器上实测送不到（系统按“实际在放音频的应用”挑媒体键会话，见 platform 的 HeadsetMediaKeyOnDeviceTest）。
 *    所以“开始”改用屏幕上的开始按钮走同一条链路：开始录音被受理；模拟器上没有蓝牙耳机，按严格蓝牙规则被拒绝
 *    （INPUT_DEVICE_MISSING），一次路由请求都没发、一个录音器都没开（更不可能是手机麦克风），反馈泵给出“没能开始”的反馈，
 *    屏幕上的开始按钮仍然可按；
 *  - 关掉控制模式：通道释放，系统的会话列表里没了。
 *
 * 证据等级：模拟器 = E2（`adb shell input keyevent` 注入，不是耳机）。证明接线在真实 Android 框架上成立；
 * 不证明 FreeClip 真的会发这些键、系统会不会把键交给我们、锁屏或后台时还收不收得到——那是真机 E3，
 * 见 execution/acceptance/android-control/README.md。
 */
@RunWith(AndroidJUnit4::class)
class ControlWiringOnDeviceTest {
    @get:Rule
    val grant: GrantPermissionRule = GrantPermissionRule.grant(
        *buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray(),
    )

    /** 转交给真的 AndroidAudioSystem，只数“有没有人要路由、有没有人打开录音器”。 */
    private class CountingAudio(private val real: AudioSystem) : AudioSystem by real {
        val routeRequests = AtomicInteger()
        val recordersOpened = AtomicInteger()

        override fun requestCommunicationRoute(input: BluetoothInput): Boolean {
            routeRequests.incrementAndGet()
            return real.requestCommunicationRoute(input)
        }

        override fun openRecorder(input: BluetoothInput, format: PcmFormat): Recorder {
            recordersOpened.incrementAndGet()
            return real.openRecorder(input, format)
        }
    }

    private class NoTranscription : TranscriptionAdapter {
        val requests = AtomicInteger()

        override suspend fun capabilities(): AsrCapabilities = error("本测试不应问到转录能力")

        override suspend fun transcribe(request: TranscriptionRequest): TranscriptionResult {
            requests.incrementAndGet()
            error("没有录音，不应有转录请求")
        }
    }

    /** 不读也不写手机上真正的档案文件：真机上跑本测试时，车主已保存的校准结果不会改变测试里的映射，也不会被改动。 */
    private class MemoryProfileStore : ProfileStore {
        override fun load(): HeadsetProfile? = null

        override fun save(profile: HeadsetProfile) = Unit
    }

    private class Rig(val app: Application, val dir: File, val dbName: String) {
        val audio = CountingAudio(AndroidAudioSystem(app))
        val transcription = NoTranscription()
        val runtime = AuriRuntime(app, audio, dbName, dir, dir, transcription, MemoryProfileStore())
        val decisions = CopyOnWriteArrayList<CaptureDecision>()
        val viewModels = ViewModelStore()
        val control: ControlViewModel

        init {
            // 先订阅再注入：决定流没有回放。UNDISPATCHED 保证 launch 返回时订阅已经登记
            runtime.scope.launch(start = CoroutineStart.UNDISPATCHED) { runtime.coordinator.decisions.collect { decisions += it } }
            control = ViewModelProvider(viewModels, runtime.controlViewModelFactory)[ControlViewModel::class.java]
        }

        fun awaitSnapshot(timeoutMs: Long = 15_000, predicate: (CaptureSnapshot) -> Boolean): CaptureSnapshot {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (System.nanoTime() < deadline) {
                runtime.coordinator.snapshot.value.takeIf(predicate)?.let { return it }
                Thread.sleep(POLL_MS)
            }
            error("等不到预期的采集状态，当前是 ${runtime.coordinator.snapshot.value.state}")
        }

        /** 决定是异步收进列表的：按内容等，不按时间点切。 */
        fun awaitDecision(what: String, from: Int = 0, timeoutMs: Long = 15_000, predicate: (CaptureDecision) -> Boolean): CaptureDecision {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (System.nanoTime() < deadline) {
                decisions.drop(from).firstOrNull(predicate)?.let { return it }
                Thread.sleep(POLL_MS)
            }
            error("等不到协调器的决定：$what；已收到 ${decisions.toList()}")
        }

        /**
         * 等到系统的会话列表满足条件，返回最后一次看到的、本应用名下的我们的会话（没有则 null）。
         * 会话的创建、激活、释放都是跨进程调用，不保证返回时系统已经处理完，所以要轮询；条件始终不满足就把最后所见交给调用方去断言。
         */
        fun awaitSystemSession(timeoutMs: Long = 3_000, condition: (MediaSessionInfo?) -> Boolean): MediaSessionInfo? {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            fun ours() = MediaSessionDump.parse(shell("dumpsys media_session")).find(app.packageName, MEDIA_SESSION_TAG)
            var seen = ours()
            while (!condition(seen) && System.nanoTime() < deadline) {
                Thread.sleep(POLL_MS)
                seen = ours()
            }
            return seen
        }

        fun awaitUi(timeoutMs: Long = 15_000, what: String, predicate: (ControlUiState) -> Boolean): ControlUiState {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (System.nanoTime() < deadline) {
                control.state.value.takeIf(predicate)?.let { return it }
                Thread.sleep(POLL_MS)
            }
            error("等不到界面状态：$what；当前是 ${control.state.value}")
        }

        fun close() {
            // 无论成败都把控制模式关掉：留着的话媒体会话会一直占着系统的媒体键
            if (runtime.coordinator.snapshot.value.state != CaptureState.DISARMED) {
                runtime.coordinator.disableControlMode()
                runCatching { awaitSnapshot(5_000) { it.state == CaptureState.DISARMED } }
            }
            viewModels.clear()
            runtime.scope.cancel()
            runtime.library.close()
            app.deleteDatabase(dbName)
            dir.deleteRecursively()
        }
    }

    private fun withRig(block: (Rig) -> Unit) {
        val app = DeviceEnv.target.applicationContext as Application
        val rig = Rig(app, File(app.cacheDir, "control-${UUID.randomUUID()}"), "control-${UUID.randomUUID()}.db")
        try {
            block(rig)
        } finally {
            rig.close()
        }
    }

    /** 经系统的输入服务注入一个按键（按下 + 抬起）；读到命令结束才返回。 */
    private fun inject(keyCode: Int) {
        shell("input keyevent $keyCode")
    }

    private fun assertNoBluetoothInputAvailable(app: Application) {
        val inputTypes = app.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS).map { it.type }
        assertTrue(
            "这个测试要在没有蓝牙耳机的环境里跑（模拟器）；现在的输入设备类型是 $inputTypes",
            inputTypes.none { it == AudioDeviceTypes.BLUETOOTH_SCO || it == AudioDeviceTypes.BLE_HEADSET },
        )
    }

    @Test
    fun mediaKeysNeverReachTheCoordinatorWhileControlModeIsOff() = withRig { rig ->
        assertNoBluetoothInputAvailable(rig.app)

        ActivityScenario.launch(MainActivity::class.java).use {
            inject(KEYCODE_MEDIA_PLAY_PAUSE)
            inject(KEYCODE_HEADSETHOOK)
            Thread.sleep(SILENCE_MS)
        }

        assertEquals("控制模式没开：协调器没有收到任何输入", emptyList<CaptureDecision>(), rig.decisions.toList())
        assertNull("控制模式没开：系统的会话列表里没有我们的会话", rig.awaitSystemSession { it == null })
        assertEquals(CaptureState.DISARMED, rig.runtime.coordinator.snapshot.value.state)
        assertEquals("界面如实显示：控制模式关，通道没占着", ControlModeUi(on = false, headset = HeadsetControlStatus.OFF, turningOff = false), rig.control.state.value.controlMode)
        assertEquals("没有向系统请求过任何通信路由", 0, rig.audio.routeRequests.get())
        assertEquals("从没有打开过录音器", 0, rig.audio.recordersOpened.get())
    }

    @Test
    fun controlModeOnClaimsTheMediaSessionAndOnScreenStartIsRefusedWithoutHeadsetThenTheSessionIsReleased() = withRig { rig ->
        assertNoBluetoothInputAvailable(rig.app)
        val audioManager = rig.app.getSystemService(AudioManager::class.java)

        ActivityScenario.launch(MainActivity::class.java).use {
            rig.control.setControlMode(true)
            rig.awaitUi(what = "控制模式开着且向系统要到了媒体键通道") { it.controlMode.on && it.controlMode.headset == HeadsetControlStatus.ACTIVE }
            rig.awaitDecision("开启控制模式（未开启 → 待命）") { it.stateBefore == CaptureState.DISARMED && it.stateAfter == CaptureState.ARMED }
            val ours = rig.awaitSystemSession { it?.active == true }
            assertNotNull("控制模式开着：系统的会话列表里有我们的会话", ours)
            assertEquals("声明的是暂停，不假装在播放", "PAUSED", ours!!.state)

            // 只测量、不断言：注入的媒体键能不能到协调器（API 35 模拟器实测送不到，原因见类注释）
            val beforeKey = rig.decisions.size
            inject(KEYCODE_MEDIA_PLAY_PAUSE)
            Thread.sleep(DELIVERY_WAIT_MS)
            Log.i(TAG, "measured app media_key_decisions=${rig.decisions.size - beforeKey}")

            // 屏幕上的开始按钮走同一条链路，它不依赖耳机键是否送达
            val beforeStart = rig.decisions.size
            rig.control.start()
            rig.awaitDecision("屏幕按钮被受理成开始录音（→ 准备中）", from = beforeStart) { it.stateAfter == CaptureState.ROUTING }
            rig.awaitDecision("严格蓝牙规则拒绝（准备中 → 受阻）", from = beforeStart) { it.stateBefore == CaptureState.ROUTING && it.stateAfter == CaptureState.BLOCKED }
            val refused = rig.awaitSnapshot { it.state == CaptureState.BLOCKED }

            assertEquals("应当是“没有蓝牙输入设备”，而不是权限或前台条件", ErrorCode.INPUT_DEVICE_MISSING, refused.blockedReason)
            val feedback = rig.awaitUi(what = "“没能开始”的反馈") { it.feedback?.kind == FeedbackKind.START_FAILED }
            assertTrue("屏幕上有文字", feedback.feedback!!.screenText.isNotBlank())
            assertTrue("屏幕上的开始按钮仍然可按（受阻之后可以再试）", feedback.manual.startEnabled)
            assertEquals("没有向系统请求过任何通信路由", 0, rig.audio.routeRequests.get())
            assertEquals("从没有打开过录音器：不存在手机麦克风的请求", 0, rig.audio.recordersOpened.get())
            assertTrue("系统里没有任何活动的录音", audioManager.activeRecordingConfigurations.isEmpty())
            assertEquals("没有转录请求", 0, rig.transcription.requests.get())
            assertTrue("没有 Record", runBlocking { rig.runtime.library.observe().first() }.isEmpty())

            rig.control.setControlMode(false)
            rig.awaitSnapshot { it.state == CaptureState.DISARMED }
            rig.awaitUi(what = "控制模式关，通道释放") { !it.controlMode.on && it.controlMode.headset == HeadsetControlStatus.OFF }
            rig.awaitDecision("关闭控制模式（受阻 → 未开启）") { it.stateAfter == CaptureState.DISARMED }
            assertNull("控制模式关掉之后：系统的会话列表里没有我们的会话了", rig.awaitSystemSession { it == null })
            val seen = rig.decisions.toList()

            inject(KEYCODE_MEDIA_PLAY_PAUSE)
            Thread.sleep(SILENCE_MS)

            assertEquals("关掉之后协调器没有再收到输入", seen, rig.decisions.toList())
            assertEquals(CaptureState.DISARMED, rig.runtime.coordinator.snapshot.value.state)
        }
    }

    private companion object {
        /** 经系统的 shell 执行一条命令；读到命令结束才返回。 */
        fun shell(command: String): String =
            ParcelFileDescriptor.AutoCloseInputStream(DeviceEnv.instrumentation.uiAutomation.executeShellCommand(command)).use { String(it.readBytes()) }

        const val TAG = "AuriControlTest"
        const val DELIVERY_WAIT_MS = 2_000L
        const val KEYCODE_MEDIA_PLAY_PAUSE = 85
        const val KEYCODE_HEADSETHOOK = 79
        const val POLL_MS = 50L

        /** “什么都没收到”要等多久才算数：系统分发媒体键是毫秒级的，这里留足余量。 */
        const val SILENCE_MS = 1_500L
    }
}
