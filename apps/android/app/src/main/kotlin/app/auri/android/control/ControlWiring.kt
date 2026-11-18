package app.auri.android.control

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.auri.capture.calibration.ProfileStore
import app.auri.capture.control.ActiveControlProfile
import app.auri.capture.control.ControlProfile
import app.auri.capture.coordinator.CaptureCoordinator
import app.auri.capture.feedback.FeedbackHub
import app.auri.platform.control.AndroidMediaButtonSource
import app.auri.platform.control.CompositeControlAdapter
import app.auri.platform.control.HeadsetControlAdapter
import app.auri.platform.control.ScreenControlAdapter
import app.auri.platform.feedback.AndroidFeedbackPlayer
import app.auri.platform.system.AndroidScreenLockMonitor
import app.auri.platform.system.RandomIds
import app.auri.platform.system.SystemClock
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

const val CONTROL_LOG_TAG = "AuriControl"

/** 校准档案在应用私有目录里的文件名（真机取档案：`adb shell run-as app.auri.android cat files/headset-profile.json`）。 */
const val PROFILE_FILE_NAME = "headset-profile.json"

/**
 * 判断是不是 Android 模拟器，只认模拟器独有的强特征：判成模拟器，校准的证据就只能是 E2。
 * 真机被错判成模拟器的代价更大（真实校准永远拿不到 E3），所以宁可漏判——漏判时还有“用户必须明确确认真机 + 真实耳机”这道手动确认。
 */
internal fun looksLikeEmulator(fingerprint: String, model: String, hardware: String, product: String): Boolean =
    fingerprint.startsWith("generic") ||
        model.contains("Emulator", ignoreCase = true) ||
        model.contains("Android SDK built for", ignoreCase = true) ||
        hardware == "goldfish" || hardware == "ranchu" ||
        product.startsWith("sdk_")

/**
 * 耳机控制的生产接线，没有别的逻辑（每一层的规则在自己的模块里，有各自的测试）：
 * 系统媒体键 → [AndroidMediaButtonSource] → [HeadsetControlAdapter]（只在控制模式开着时占着通道）→ 与屏幕按钮合并
 * 成协调器的控制适配器；协调器的决定 → [FeedbackHub] → 振动 + 通知（没有声音）；屏幕锁定变化与已保存的档案接到视图模型。
 * 这些东西的生命周期是进程，不跟界面走：应用在后台、界面不在时，耳机手势与反馈照样工作。
 */
class ControlWiring(
    private val application: Application,
    private val screen: ScreenControlAdapter,
    private val store: ProfileStore,
) {
    private val active = ActiveControlProfile(store.load()?.controlProfile() ?: ControlProfile.generic())
    private val headset = HeadsetControlAdapter(AndroidMediaButtonSource(application, SystemClock, ::log), RandomIds, { active.current }, log = ::log)
    private val screenLock = AndroidScreenLockMonitor(application, ::log)

    /** 交给协调器的控制适配器（屏幕按钮 + 耳机）。 */
    val control = CompositeControlAdapter(screen, headset, ::log)

    /** 协调器造好之后接上反馈与锁屏监听，返回耳机控制界面的视图模型工厂。 */
    fun connect(coordinator: CaptureCoordinator, scope: CoroutineScope): ViewModelProvider.Factory {
        screenLock.start()
        val feedback = FeedbackHub(coordinator.decisions, AndroidFeedbackPlayer(application, ::log), scope, ::log)
        val controls = CoordinatorControlModeControls(coordinator, screen)
        val emulator = looksLikeEmulator(Build.FINGERPRINT, Build.MODEL, Build.HARDWARE, Build.PRODUCT)
        return object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ControlViewModel(
                controls, headset.status, feedback.latest, screenLock.locked, control::observe, active, store, emulator,
                { "cal-${UUID.randomUUID()}" }, CoroutineScope(SupervisorJob() + Dispatchers.Default),
            ) as T
        }
    }

    // 日志只有状态与原因：没有设备名、标识或任何内容
    private fun log(line: String) {
        Log.i(CONTROL_LOG_TAG, line)
    }
}
