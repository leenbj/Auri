package app.auri.android

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.auri.android.control.ControlWiring
import app.auri.android.control.PROFILE_FILE_NAME
import app.auri.android.home.CoordinatorControls
import app.auri.android.home.HomeViewModel
import app.auri.android.modelui.ManagerModelControls
import app.auri.android.modelui.ModelsViewModel
import app.auri.android.modelui.SchedulerControls
import app.auri.asr.engine.SherpaOnnxEngineFactory
import app.auri.capture.adapter.TranscriptionAdapter
import app.auri.capture.calibration.ProfileStore
import app.auri.capture.contract.CaptureState
import app.auri.capture.coordinator.CaptureConfig
import app.auri.capture.coordinator.CaptureCoordinator
import app.auri.capture.pipeline.RecordPipeline
import app.auri.capture.recovery.OrphanRecovery
import app.auri.capture.recovery.StartupRecovery
import app.auri.capture.scheduler.RecordingAwareResourceMonitor
import app.auri.capture.scheduler.TranscriptionScheduler
import app.auri.modelmgmt.HttpTransport
import app.auri.modelmgmt.ModelCatalog
import app.auri.modelmgmt.ModelManager
import app.auri.modelmgmt.UrlConnectionTransport
import app.auri.platform.asr.LocalTranscriptionStack
import app.auri.platform.asr.declaredCapabilities
import app.auri.platform.asr.loadLocalEngine
import app.auri.platform.audio.AndroidAudioSystem
import app.auri.platform.audio.AndroidHeadsetPresence
import app.auri.platform.audio.AudioSystem
import app.auri.platform.audio.BluetoothCaptureAdapter
import app.auri.platform.control.ScreenControlAdapter
import app.auri.platform.jobs.SqliteTranscriptionJobStore
import app.auri.platform.library.SqliteRecordLibrary
import app.auri.platform.service.AndroidForegroundServiceController
import app.auri.platform.service.bindForegroundService
import app.auri.platform.storage.FileProfileStore
import app.auri.platform.storage.DurationLimitSetting
import app.auri.platform.storage.WavRecordingStore
import app.auri.platform.system.AndroidCaptureEnvironment
import app.auri.platform.system.AndroidResourceMonitor
import app.auri.platform.system.AppLifecycleTracker
import app.auri.platform.system.RandomIds
import app.auri.platform.system.SystemClock
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** 录音上限独立于转录引擎的单次输入上限。 */
fun captureConfigFor(durationMinutes: Int): CaptureConfig {
    require(durationMinutes in 1..120)
    return CaptureConfig(maxDurationMs = durationMinutes * 60_000L)
}

/**
 * 把各层接起来的地方：屏幕按钮与耳机媒体键（控制模式开着时）→ 协调器 → 蓝牙采集适配器 / WAV 存储 → 管线（自动转录）→ SQLite 库。
 * 没有别的逻辑；每一层的规则在它自己的模块里，有各自的测试。需要替换的东西（比如测试里的音频系统）经构造参数传入。
 */
class AuriRuntime(
    application: Application,
    audio: AudioSystem = AndroidAudioSystem(application),
    databaseName: String? = "auri.db",
    recordingsDir: File = File(application.filesDir, "recordings"),
    /** Ticket 14 的调试布局（files/models/<id>/<版本>）。产品转录不再读它：模型由 [modelManager] 下载并启用，放在 [modelStoreRoot] 下。 */
    @Suppress("UNUSED_PARAMETER") modelsDir: File = File(application.filesDir, "models"),
    transcriptionOverride: TranscriptionAdapter? = null,
    headsetProfileStore: ProfileStore = FileProfileStore(File(application.filesDir, PROFILE_FILE_NAME)),
    /** 模型管理器的根目录；里面的 model-store/、model-downloads/、model-staging/ 是它自己的（布局见 ModelStore）。 */
    modelStoreRoot: File = application.filesDir,
    transport: HttpTransport = UrlConnectionTransport(),
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val lifecycle = AppLifecycleTracker(application)
    val headset = AndroidHeadsetPresence(application)
    val library = SqliteRecordLibrary(application, databaseName)
    val screenControl = ScreenControlAdapter(SystemClock, RandomIds)
    private val controlWiring = ControlWiring(application, screenControl, headsetProfileStore)
    val captureAdapter = BluetoothCaptureAdapter(audio, AndroidCaptureEnvironment(application, lifecycle), SystemClock)

    private val manifestJson = application.assets.open(MODEL_MANIFEST_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
    private val catalog = ModelCatalog.fromJson(listOf(manifestJson))
    private val modelCapabilities = catalog.default.manifest.declaredCapabilities()

    /**
     * 模型的下载、安装与启用（Ticket 20）。启动时 [ModelManager.restore] 一次（很快：只读几个小文件，不算哈希、不联网），
     * 之后只有用户点了“下载”才会联网。
     */
    val modelManager = ModelManager(catalog, modelStoreRoot, transport, scope).also { it.restore() }

    /** 采集状态的镜像（见 init）：协调器要在管线之后才建得出来，而转录要据它在录音时让路。 */
    private val captureState = MutableStateFlow(CaptureState.DISARMED)

    /**
     * 转录调度器。线上是 [LocalTranscriptionStack]（跟着模型管理器走、资源不够就等、任务存 SQLite、录音时让路、最低优先级线程），
     * 进程那么长，所以不关它。测试传了假适配器时是内存里的简单调度器，行为与 Ticket 16 一致。
     */
    val scheduler: TranscriptionScheduler = if (transcriptionOverride != null) {
        TranscriptionScheduler(library, transcriptionOverride, SystemClock, scope)
    } else {
        LocalTranscriptionStack(
            library = library,
            active = modelManager.active,
            fallbackCapabilities = modelCapabilities,
            onIntegrityFailure = modelManager::reportIntegrityFailure,
            loadEngine = { loadLocalEngine(it, SherpaOnnxEngineFactory()) },
            clock = SystemClock,
            scope = scope,
            store = SqliteTranscriptionJobStore(application, databaseName?.let(::jobsDatabaseNameFor)),
            resources = RecordingAwareResourceMonitor(AndroidResourceMonitor(application), captureState),
        ).scheduler
    }

    val pipeline = RecordPipeline(library, scheduler, scope)
    val durationSetting = DurationLimitSetting(File(application.filesDir, "capture-duration-limit"))

    private val recordingStore = WavRecordingStore(recordingsDir)

    val coordinator = CaptureCoordinator(
        control = controlWiring.control,
        capture = captureAdapter,
        store = recordingStore,
        output = pipeline,
        clock = SystemClock,
        ids = RandomIds,
        config = captureConfigFor(durationSetting.minutes.value),
        scope = scope,
        adapterDispatcher = Dispatchers.IO,
        durationLimit = { durationSetting.minutes.value * 60_000L },
    )

    val controls = CoordinatorControls(coordinator, screenControl)

    init {
        // 前台服务跟着采集状态走；服务起不来就取消这次采集（没有前台服务，录音一进后台就会被系统静音）
        scope.bindForegroundService(coordinator.snapshot, AndroidForegroundServiceController(application)) { coordinator.foregroundServiceFailed() }
        scope.launch { coordinator.snapshot.collect { captureState.value = it.state } }
        pipeline.resume() // 上次没转完的、等模型的 Record 重新排队
        scope.launch { coordinator.snapshot.map(::captureLogLine).distinctUntilChanged().collect { Log.i(CAPTURE_LOG_TAG, it) } }
        // 上次进程没来得及交付的录音（被杀、封存没完成）：逐段验证后登记成被截断的 Record，像别的 Record 一样转录（Ticket 19）。
        // 恢复只读磁盘、只写库：不开麦、不起前台服务、不碰采集状态；协调器此刻必然是 DISARMED，之后也只有用户的操作能让它开始录音
        scope.launch(Dispatchers.IO) {
            StartupRecovery(OrphanRecovery(recordingStore, library, pipeline)) { Log.i(CAPTURE_LOG_TAG, it) }.run()
        }
    }

    val controlViewModelFactory = controlWiring.connect(coordinator, scope)

    val homeViewModelFactory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(controls, headset, library, pipeline.notices, CoroutineScope(SupervisorJob() + Dispatchers.Default), durationSetting) as T
    }

    val modelsViewModelFactory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ModelsViewModel(ManagerModelControls(modelManager), SchedulerControls(scheduler), CoroutineScope(SupervisorJob() + Dispatchers.Default)) as T
    }

    companion object {
        const val MODEL_MANIFEST_ASSET = "sensevoice-small-int8-2024-07-17.manifest.json"
    }
}

/**
 * 转录任务库的文件名跟着 Record 库走：一个测试用自己的库名，就不会读到真应用留下的任务
 * （否则别人的任务会被这次测试的调度器“认领”）。Record 库是内存库（null）时，任务库也是内存库。
 */
internal fun jobsDatabaseNameFor(databaseName: String): String = databaseName.removeSuffix(".db") + "-jobs.db"
