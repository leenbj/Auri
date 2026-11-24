package app.auri.android

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.auri.android.control.ControlWiring
import app.auri.android.control.PROFILE_FILE_NAME
import app.auri.android.destinations.DestinationDetailViewModel
import app.auri.android.destinations.DestinationsViewModel
import app.auri.android.destinations.LibraryItemTexts
import app.auri.android.destinations.SecretClipboardGuard
import app.auri.android.destinations.SendReviewViewModel
import app.auri.android.home.CoordinatorControls
import app.auri.android.home.HomeViewModel
import app.auri.android.modelui.ManagerModelControls
import app.auri.android.modelui.ModelsViewModel
import app.auri.android.modelui.SchedulerControls
import app.auri.android.revisions.RecordDetailViewModel
import app.auri.android.revisions.RecordsViewModel
import app.auri.android.sync.SyncViewModel
import app.auri.android.ui.AndroidClipboardPort
import app.auri.asr.engine.SherpaOnnxEngineFactory
import app.auri.capture.adapter.TranscriptionAdapter
import app.auri.capture.calibration.ProfileStore
import app.auri.capture.contract.CaptureState
import app.auri.capture.coordinator.CaptureConfig
import app.auri.capture.coordinator.CaptureCoordinator
import app.auri.capture.library.AudioRetentionCleaner
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
import app.auri.platform.library.relocateRecordedAudio
import app.auri.platform.service.AndroidForegroundServiceController
import app.auri.platform.service.bindForegroundService
import app.auri.platform.storage.AudioRetentionSetting
import app.auri.platform.storage.DurationLimitSetting
import app.auri.platform.storage.FileProfileStore
import app.auri.platform.storage.NoBackupStorage
import app.auri.platform.storage.WavRecordingStore
import app.auri.platform.system.AndroidCaptureEnvironment
import app.auri.platform.system.AndroidResourceMonitor
import app.auri.platform.system.AppLifecycleTracker
import app.auri.platform.system.RandomIds
import app.auri.platform.system.SystemClock
import app.auri.syncplatform.AndroidSyncLog
import app.auri.syncplatform.SyncRuntime
import app.auri.syncplatform.SyncStorage
import app.auri.syncplatform.auth.BiometricLocalAuthenticator
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
    recordingsDir: File = NoBackupStorage.place(application.noBackupFilesDir, application.filesDir, "recordings"),
    /** Ticket 14 的调试布局（files/models/<id>/<版本>）。产品转录不再读它：模型由 [modelManager] 下载并启用，放在 [modelStoreRoot] 下。 */
    @Suppress("UNUSED_PARAMETER") modelsDir: File = File(application.filesDir, "models"),
    transcriptionOverride: TranscriptionAdapter? = null,
    headsetProfileStore: ProfileStore = FileProfileStore(NoBackupStorage.place(application.noBackupFilesDir, application.filesDir, PROFILE_FILE_NAME)),
    /** 模型管理器的根目录；里面的 model-store/、model-downloads/、model-staging/ 是它自己的（布局见 ModelStore）。 */
    modelStoreRoot: File = application.filesDir,
    transport: HttpTransport = UrlConnectionTransport(),
) {
    val scope = scopeOf()

    private val lifecycle = AppLifecycleTracker(application)
    val headset = AndroidHeadsetPresence(application)
    val library = SyncStorage.library(application, databaseName) // Record 库与待同步任务表同库：发布 Revision 的事务里顺手登记任务（Ticket 21）

    init {
        // 升级前登记的录音位置还是老目录（filesDir/recordings）下的路径，而上面的 place 已把目录整个挪走：位置要跟着走（Ticket 19 评审 H1）。
        // 在调度器与管线出现之前同步做完（一条 UPDATE），它们读到的位置就不会是旧的；幂等，每次启动都做，中途被杀下次接着做。
        // 失败只记一行日志：这一步不该让应用起不来（登记的位置没改成，只影响升级前录下的那几条）。
        try {
            runBlocking(Dispatchers.IO) {
                relocateRecordedAudio(File(application.filesDir, recordingsDir.name), recordingsDir) { from, to -> library.relocateAudio(from, to) }
            }
        } catch (e: Exception) {
            Log.w(CAPTURE_LOG_TAG, "recordings relocation failed: ${e.javaClass.simpleName}")
        }
    }

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
    val audioRetentionSetting = AudioRetentionSetting(File(application.filesDir, "audio-retention-days"))
    val audioRetentionCleaner = AudioRetentionCleaner(library) { path -> File(path).let { !it.exists() || it.delete() } }

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

    /** 配对与同步（Ticket 21）。构造时不碰网络与相机；[SyncRuntime.start] 由 AuriApplication 调用。 */
    val sync: SyncRuntime = SyncRuntime.create(application, library, scope)

    /** 目的地管理（Ticket 22）：每次生成或轮换 Destination 凭证前现场做本机核验（生物识别或设备锁屏凭据）。 */
    val destinations = sync.destinationService(BiometricLocalAuthenticator(application, AndroidSyncLog))
    val recordRevisions = sync.recordRevisionService { path -> File(path).let { !it.exists() || it.delete() } }
    private val itemTexts = LibraryItemTexts(library)

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
            audioRetentionCleaner.purgeExpired(SystemClock.now(), audioRetentionSetting.days.value)
        }
    }

    val controlViewModelFactory = controlWiring.connect(coordinator, scope)

    val homeViewModelFactory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(controls, headset, library, pipeline.notices, scopeOf(), durationSetting) as T
    }

    val syncViewModelFactory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SyncViewModel(sync, scopeOf(), AndroidSyncLog) as T
    }

    val modelsViewModelFactory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ModelsViewModel(ManagerModelControls(modelManager), SchedulerControls(scheduler), scopeOf()) as T
    }

    /** 复制出去的凭证到点从剪贴板清掉（评审 B5）；比任何一页都长寿，定时器挂在主线程上。 */
    val secretClipboard = SecretClipboardGuard(
        AndroidClipboardPort(application),
        scopeOf(Dispatchers.Main.immediate),
        { android.os.SystemClock.elapsedRealtime() },
        AndroidSyncLog,
    )

    val destinationsViewModelFactory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DestinationsViewModel(destinations, scopeOf(), AndroidSyncLog) as T
    }

    val sendReviewViewModelFactory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SendReviewViewModel(sync.dispatch, itemTexts, SystemClock, sync::syncNow, scopeOf(), sync.dataFlow, AndroidSyncLog) as T
    }

    /** 每个目的地一个详情视图模型（以编号为键，见 MainActivity）；离开详情页时它自己抹掉凭证。 */
    fun destinationDetailViewModelFactory(destinationId: String) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DestinationDetailViewModel(destinationId, destinations, itemTexts, scopeOf(), AndroidSyncLog, sync.status.map { }) as T
    }

    val recordsViewModelFactory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            RecordsViewModel(library, sync.jobs, sync.dispatch, audioRetentionSetting, audioRetentionCleaner, SystemClock, scopeOf()) as T
    }

    fun recordDetailViewModelFactory(recordId: String) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            RecordDetailViewModel(recordId, library, recordRevisions, SystemClock, scopeOf()) as T
    }

    companion object {
        const val MODEL_MANIFEST_ASSET = "sensevoice-small-int8-2024-07-17.manifest.json"

        /**
         * 逃出作用域的异常只记一行日志，不再走到线程的默认处理器（评审 H4）：在 Android 上那就是整个进程退出，包括正在录音的前台服务。
         * 日志只有事件名与异常类名：没有异常信息（里面可能有路径与文字）、没有正文、没有标识。
         */
        private val escapedFailureHandler = CoroutineExceptionHandler { _, failure -> AndroidSyncLog.event("scope.escaped ${failure::class.simpleName}") }

        /** 这个运行时的每一个作用域都经这里造（GuardTest 钉住）：SupervisorJob（一个子协程失败不连累别的）加上面的处理器。 */
        private fun scopeOf(dispatcher: CoroutineDispatcher = Dispatchers.Default) = CoroutineScope(SupervisorJob() + dispatcher + escapedFailureHandler)
    }
}

/**
 * 转录任务库的文件名跟着 Record 库走：一个测试用自己的库名，就不会读到真应用留下的任务
 * （否则别人的任务会被这次测试的调度器“认领”）。Record 库是内存库（null）时，任务库也是内存库。
 */
internal fun jobsDatabaseNameFor(databaseName: String): String = databaseName.removeSuffix(".db") + "-jobs.db"
