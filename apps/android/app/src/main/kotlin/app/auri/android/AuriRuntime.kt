package app.auri.android

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.auri.android.home.CoordinatorControls
import app.auri.android.home.HomeViewModel
import app.auri.asr.LocalTranscriber
import app.auri.asr.engine.SherpaOnnxEngineFactory
import app.auri.asr.manifest.ModelManifest
import app.auri.capture.adapter.TranscriptionAdapter
import app.auri.capture.coordinator.CaptureConfig
import app.auri.capture.coordinator.CaptureCoordinator
import app.auri.capture.pipeline.RecordPipeline
import app.auri.platform.asr.LocalTranscriptionAdapter
import app.auri.platform.asr.asSpeechEngine
import app.auri.platform.audio.AndroidAudioSystem
import app.auri.platform.audio.AndroidHeadsetPresence
import app.auri.platform.audio.AudioSystem
import app.auri.platform.audio.BluetoothCaptureAdapter
import app.auri.platform.control.ScreenControlAdapter
import app.auri.platform.library.SqliteRecordLibrary
import app.auri.platform.service.AndroidForegroundServiceController
import app.auri.platform.service.bindForegroundService
import app.auri.platform.storage.WavRecordingStore
import app.auri.platform.system.AndroidCaptureEnvironment
import app.auri.platform.system.AppLifecycleTracker
import app.auri.platform.system.RandomIds
import app.auri.platform.system.SystemClock
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 一次录音最多比转录引擎一次能处理的长度少这么多：留出读音频批次的余量，保证录下的音频不会超出引擎上限。 */
private const val DURATION_SAFETY_MARGIN_MS = 500L

/**
 * 在分段与长录音（Ticket 18/20）之前，一次录音不能长过转录引擎一次能处理的时长，否则录完会转不了。
 * 所以自动停止的时长上限取引擎声明的上限（再留一点余量）；引擎没有上限就用协调器的默认值。
 */
fun captureConfigFor(maxAudioDurationMs: Long?): CaptureConfig {
    val default = CaptureConfig()
    if (maxAudioDurationMs == null) return default
    val limit = (maxAudioDurationMs - DURATION_SAFETY_MARGIN_MS).takeIf { it > 0 } ?: maxAudioDurationMs
    return default.copy(maxDurationMs = limit)
}

/**
 * 把各层接起来的地方：屏幕按钮 → 协调器 → 蓝牙采集适配器 / WAV 存储 → 管线（自动转录）→ SQLite 库。
 * 没有别的逻辑；每一层的规则在它自己的模块里，有各自的测试。需要替换的东西（比如测试里的音频系统）经构造参数传入。
 */
class AuriRuntime(
    application: Application,
    audio: AudioSystem = AndroidAudioSystem(application),
    databaseName: String? = "auri.db",
    recordingsDir: File = File(application.filesDir, "recordings"),
    modelsDir: File = File(application.filesDir, "models"),
    transcriptionOverride: TranscriptionAdapter? = null,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val lifecycle = AppLifecycleTracker(application)
    val headset = AndroidHeadsetPresence(application)
    val library = SqliteRecordLibrary(application, databaseName)
    val screenControl = ScreenControlAdapter(SystemClock, RandomIds)
    val captureAdapter = BluetoothCaptureAdapter(audio, AndroidCaptureEnvironment(application, lifecycle), SystemClock)

    private val manifestJson = application.assets.open(MODEL_MANIFEST_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
    private val manifest = ModelManifest.parse(manifestJson)
    private val transcriber = LocalTranscriber(manifest, File(modelsDir, "${manifest.modelId}/${manifest.modelVersion}"), SherpaOnnxEngineFactory())
    private val transcription = transcriptionOverride ?: LocalTranscriptionAdapter(transcriber.asSpeechEngine())

    val pipeline = RecordPipeline(library, transcription, SystemClock, scope)

    val coordinator = CaptureCoordinator(
        control = screenControl,
        capture = captureAdapter,
        store = WavRecordingStore(recordingsDir),
        output = pipeline,
        clock = SystemClock,
        ids = RandomIds,
        config = captureConfigFor(transcriber.capabilities.limits.maxAudioDurationMs),
        scope = scope,
        adapterDispatcher = Dispatchers.IO,
    )

    val controls = CoordinatorControls(coordinator, screenControl)

    init {
        // 前台服务跟着采集状态走；服务起不来就取消这次采集（没有前台服务，录音一进后台就会被系统静音）
        scope.bindForegroundService(coordinator.snapshot, AndroidForegroundServiceController(application)) { coordinator.userCancel() }
        pipeline.resume() // 上次没转完的、等模型的 Record 重新排队
    }

    val homeViewModelFactory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(controls, headset, library, pipeline.notices, CoroutineScope(SupervisorJob() + Dispatchers.Default)) as T
    }

    companion object {
        const val MODEL_MANIFEST_ASSET = "sensevoice-small-int8-2024-07-17.manifest.json"
    }
}
