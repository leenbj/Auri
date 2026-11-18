package app.auri.android.capture

import android.Manifest
import android.app.Application
import android.media.AudioManager
import android.os.Build
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
import app.auri.capture.contract.AsrCapabilities
import app.auri.capture.contract.CaptureState
import app.auri.capture.contract.ErrorCode
import app.auri.capture.coordinator.CaptureSnapshot
import app.auri.platform.audio.AndroidAudioSystem
import app.auri.platform.audio.AudioDeviceTypes
import app.auri.platform.audio.AudioSystem
import app.auri.platform.audio.BluetoothInput
import app.auri.platform.audio.HeadsetStatus
import app.auri.platform.audio.Recorder
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ticket 16 第 8 项里模拟器能证明的那部分：**拒绝路径**。设备上没有蓝牙耳机时，用真的应用接线（真的协调器、真的蓝牙采集适配器、
 * 真的 AndroidAudioSystem、真的 WAV 存储与 SQLite 库），在前台、权限都给了的情况下按“开始”：
 * 拒绝并给出原因，一个字节都没写，没有转录，没有 Record，系统里没有任何活动的录音，也从没有打开过任何录音器（所以更不可能是手机麦克风）。
 *
 * 证据等级：模拟器 = E2。它证明拒绝逻辑在真实 Android 框架上成立；不能证明真实耳机上的路由行为（那是真机 E3，见 README）。
 */
@RunWith(AndroidJUnit4::class)
class CaptureRefusalOnDeviceTest {
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

    private class CountingTranscription : TranscriptionAdapter {
        val requests = AtomicInteger()

        override suspend fun capabilities(): AsrCapabilities = error("本测试不应问到转录能力")

        override suspend fun transcribe(request: TranscriptionRequest): TranscriptionResult {
            requests.incrementAndGet()
            error("没有录音，不应有转录请求")
        }
    }

    private fun awaitSnapshot(runtime: AuriRuntime, timeoutMs: Long = 15_000, predicate: (CaptureSnapshot) -> Boolean): CaptureSnapshot {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            runtime.coordinator.snapshot.value.takeIf(predicate)?.let { return it }
            Thread.sleep(50)
        }
        error("等不到预期的采集状态，当前是 ${runtime.coordinator.snapshot.value.state}")
    }

    @Test
    fun noHeadsetRefusesAndNothingIsRecordedWrittenOrTranscribed() {
        val app = DeviceEnv.target.applicationContext as Application
        val audioManager = app.getSystemService(AudioManager::class.java)
        val inputTypes = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).map { it.type }
        assertTrue(
            "这个测试要在没有蓝牙耳机的环境里跑（模拟器）；现在的输入设备类型是 $inputTypes",
            inputTypes.none { it == AudioDeviceTypes.BLUETOOTH_SCO || it == AudioDeviceTypes.BLE_HEADSET },
        )

        val dbName = "refusal-${UUID.randomUUID()}.db"
        val dir = File(app.cacheDir, "refusal-${UUID.randomUUID()}")
        val audio = CountingAudio(AndroidAudioSystem(app))
        val transcription = CountingTranscription()
        val runtime = AuriRuntime(app, audio, dbName, dir, dir, transcription)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                runtime.controls.start() // 屏幕上的“开始”按钮走的就是这一个调用
                val refused = awaitSnapshot(runtime) { it.state == CaptureState.BLOCKED }

                assertEquals("应当是“没有蓝牙输入设备”，而不是权限或前台条件", ErrorCode.INPUT_DEVICE_MISSING, refused.blockedReason)
                assertEquals("没有观测到任何输入", null, refused.actualInput)
            }

            assertEquals("没有向系统请求过任何通信路由", 0, audio.routeRequests.get())
            assertEquals("从没有打开过录音器：不存在手机麦克风的请求", 0, audio.recordersOpened.get())
            assertTrue(
                "系统里没有任何活动的录音：${audioManager.activeRecordingConfigurations.size}",
                audioManager.activeRecordingConfigurations.isEmpty(),
            )
            val written = dir.walkTopDown().filter { it.isFile }.toList()
            assertEquals("一个文件都没写（连 .part 也没有）：$written", 0, written.size)
            assertEquals("没有转录请求", 0, transcription.requests.get())
            assertTrue("没有 Record", runBlocking { runtime.library.observe().first() }.isEmpty())
            assertEquals("耳机没连接这一项如实显示", HeadsetStatus.NONE, runtime.headset.status.value)
            assertEquals("受阻之后可以再按开始重试", CaptureState.BLOCKED, runtime.coordinator.snapshot.value.state)
        } finally {
            runtime.scope.cancel()
            runtime.library.close()
            app.deleteDatabase(dbName)
            dir.deleteRecursively()
        }
    }
}
