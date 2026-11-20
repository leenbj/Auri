package app.auri.syncplatform

import android.app.Application
import app.auri.capture.coordinator.Clock
import app.auri.platform.library.SqliteRecordLibrary
import app.auri.platform.storage.NoBackupStorage
import app.auri.platform.system.SystemClock
import app.auri.sync.SyncController
import app.auri.sync.SyncLog
import app.auri.sync.destinations.DestinationService
import app.auri.sync.destinations.LocalAuthenticator
import app.auri.sync.discovery.GatewayDiscovery
import app.auri.sync.discovery.GatewayLocator
import app.auri.sync.engine.RecordLibrarySubmissionSource
import app.auri.sync.engine.SyncEngine
import app.auri.sync.engine.dueUpdates
import app.auri.sync.gateway.DeviceInfo
import app.auri.sync.gateway.SubmissionBuilder
import app.auri.sync.http.HttpExchangeFactory
import app.auri.sync.http.PinnedHttpsClient
import app.auri.sync.loop.NetworkSignal
import app.auri.sync.loop.SyncLoop
import app.auri.sync.pairing.AesGcmSecretBox
import app.auri.sync.pairing.FilePairingStore
import app.auri.sync.pairing.ObservablePairingStore
import app.auri.sync.pairing.PairingOutcome
import app.auri.sync.pairing.PairingPayload
import app.auri.sync.pairing.PairingService
import app.auri.sync.pairing.SecretBox
import app.auri.sync.policy.DataFlowPolicy
import app.auri.sync.resume.ResumePlanner
import app.auri.sync.resume.ResumeRunner
import app.auri.sync.resume.ResumeScheduler
import app.auri.sync.resume.needsResume
import app.auri.sync.status.SyncStatus
import app.auri.syncplatform.discovery.NsdGatewayDiscovery
import app.auri.syncplatform.dispatch.DispatchSql
import app.auri.syncplatform.dispatch.SqliteDispatchStore
import app.auri.syncplatform.jobs.SqliteSyncJobStore
import app.auri.syncplatform.keystore.KeystoreKeys
import app.auri.syncplatform.network.AndroidNetworkSignal
import app.auri.syncplatform.policy.DataFlowPolicySql
import app.auri.syncplatform.policy.SqliteDataFlowPolicy
import app.auri.syncplatform.resume.JobSchedulerResume
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 装配：Record 库、待同步任务表、发送账（Destination 缓存、预览确认的决定、意图标签、网关上有没有 Delivery 的账）在同一个数据库文件里，
 * 发布 Revision 的事务里一定登记任务、顺手规划目标（Ticket 22）。
 */
object SyncStorage {
    /** 发布 Revision 时在**同一个事务**里登记待同步任务、规划发送的 Record 库；时间取 [clock]。 */
    fun library(application: Application, databaseName: String?, clock: Clock = SystemClock): SqliteRecordLibrary =
        SqliteRecordLibrary(
            application,
            databaseName,
            publicationStep = { db, revision -> SqliteDispatchStore.publishIn(db, revision, clock.now()) },
            extraSchema = DispatchSql.SCHEMA + DataFlowPolicySql.SCHEMA,
        )
}

/**
 * 配对与同步在 Android 上的全部装配：配对记录（Keystore 加密）、待同步任务表、固定公钥的 HTTPS、
 * mDNS 发现、网络回调、同步循环。界面只通过它读状态（[status]、[pairing]）和下指令（[pair]、[syncNow]）。
 *
 * 构造参数都可以替换：仪器测试里换成连宿主机上真网关的 HTTP 通道与固定的网络信号。
 * [start] 只调用一次，之后同步循环一直跑到 [scope] 被取消。进程被系统杀掉之后：有没发完的任务期间，系统里留着一个“有网络就叫醒”的
 * 任务（[resume]、ResumePlanner），被叫醒后跑一轮有时限的同步（[resumePass]）；就算没有叫醒，下次启动的第一轮也会把任务接回来。
 */
class SyncRuntime(
    val library: SqliteRecordLibrary,
    pairingFile: File,
    box: SecretBox,
    private val scope: CoroutineScope,
    private val clock: Clock,
    device: DeviceInfo,
    discovery: GatewayDiscovery,
    network: NetworkSignal,
    private val http: HttpExchangeFactory = HttpExchangeFactory { pin -> PinnedHttpsClient(pin) },
    private val log: SyncLog = SyncLog.None,
    resume: ResumeScheduler = ResumeScheduler.None,
) : SyncController {
    val jobs = SqliteSyncJobStore(library)

    /** 发送账（Ticket 22）：Destination 缓存、预览确认的决定、意图标签、网关上有没有 Delivery 的账。引擎每次发送前向它要 publication，每轮最后向它要还欠的 Delivery。 */
    val dispatch = SqliteDispatchStore(library)

    /**
     * “文字能不能进网关”的两道开关（评审 B1）：默认关、读不出来也当关；引擎在每个可能发请求的地方先问它，
     * 叫醒计划与同步状态也据此判断。界面通过 [setGatewayTextGranted]、[setStrictLocal] 改。
     */
    val policy: DataFlowPolicy = SqliteDataFlowPolicy(library, clock)

    /** 配对状态（可观察）。读它之前先 [start]：启动时才读第一次，之前没有任何状态。 */
    val pairing = ObservablePairingStore(FilePairingStore(pairingFile, box))

    private val pairingService = PairingService(pairing, http, discovery, clock, device, log)
    private val locator = GatewayLocator(http, discovery, log)
    private val engine = SyncEngine(
        jobs = jobs,
        pairing = pairing,
        source = RecordLibrarySubmissionSource(library, SubmissionBuilder(device.appVersion), dispatch),
        http = http,
        locator = locator,
        clock = clock,
        policy = policy,
        additions = dispatch,
        log = log,
    )
    private val loop = SyncLoop(engine, jobs, network, clock, log = log, additions = dispatch)

    /** [start] 的启动工作（读配对记录、接回中断的任务）做完了没有：被系统叫醒的那一轮要等它。 */
    private val started = CompletableDeferred<Unit>()
    private val planner = ResumePlanner(
        pairing.states,
        jobs.observe(),
        policy.states(),
        resume,
        clock,
        log = log,
        additionsOwed = { dispatch.due().isNotEmpty() },
        additionsChanged = dispatch.changes(),
    )
    private val resumeRunner = ResumeRunner(
        ready = { started.await() },
        runPass = engine::runOnce,
        stillNeeded = { needsResume(pairing.load(), jobs.all(), policy.current(), dispatch.due().isNotEmpty()) },
        log = log,
    )

    /** 界面要的同步状态汇总：配对状态、任务表、数据流开关与发送账里等用户确认的文字，任何一边变了都重新汇总（评审 M2）。 */
    override val status: Flow<SyncStatus> = SyncStatus.watch(pairing.states, jobs.observe(), policy.states(), dispatch)

    fun start(): Job = scope.launch {
        // 同步出故障不能带倒整个进程（录音在同一个进程里，这个作用域又没有异常处理器，逃出来的异常就是应用崩溃）：
        // 记下来，循环照常起来，它的第一轮会再试
        try {
            pairing.refresh()
            // 上次进程在“刚配好对、还没把停下的任务放回队列”时被杀：配对记录是好的，任务却还停着。放回去。
            engine.resolveAfterRepair()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.event("start.failed ${failure::class.simpleName}")
        }
        started.complete(Unit)
        planner.start(this)
        loop.start(this)
    }

    /**
     * 系统因为有网络把应用叫醒后调用（见 SyncResumeService）：等启动做完，跑一轮有时限的同步，
     * 返回“还有活、请系统稍后再叫醒”。被取消（系统叫停）时取消照常向上传。
     */
    suspend fun resumePass(): Boolean = resumeRunner.run()

    /** 用二维码里的载荷配对。成功之后，因公钥不符或令牌被拒而停下的任务回到队列，并立刻同步一轮。 */
    override suspend fun pair(payload: PairingPayload): PairingOutcome {
        val outcome = pairingService.pair(payload)
        if (outcome is PairingOutcome.Paired) {
            // 令牌已经存下：即使调用方此刻被取消，也要把任务放回队列，否则它们会一直停着
            withContext(NonCancellable) { engine.resolveAfterRepair() }
            loop.nudge()
        }
        return outcome
    }

    /** 回到前台、用户点了“现在同步”：立刻跑一轮，并让探测的退避从头开始。 */
    override fun syncNow() = loop.nudge()

    /** 用户明确打开或收回“文字可以进网关”。打开之后马上跑一轮：之前被留在本机的文字这时才发出去。 */
    override suspend fun setGatewayTextGranted(granted: Boolean) {
        policy.setGatewayTextGranted(granted)
        loop.nudge()
    }

    /** 用户打开或关闭“严格仅本地”。关掉之后马上跑一轮（授权仍在的话，被留住的文字这时才发）。 */
    override suspend fun setStrictLocal(on: Boolean) {
        policy.setStrictLocal(on)
        loop.nudge()
    }

    /**
     * Destination 管理（Ticket 22）：和同步引擎共用配对记录、地址探测与固定公钥的 HTTPS，没有第二套网络栈。
     * [authenticator] 是界面层给的本机核验（生物识别）：生成或轮换凭证前每次都要过它。
     */
    fun destinationService(authenticator: LocalAuthenticator): DestinationService =
        DestinationService(pairing, http, locator, dispatch, jobs, authenticator, clock, log)

    companion object {
        private const val PAIRING_KEY_ALIAS = "auri.sync.pairing.v1"
        private const val PAIRING_FILE = "pairing.sealed"

        /** 真机/模拟器上的标准装配：Keystore 加密、NsdManager 发现、系统网络回调、固定公钥的 HTTPS。 */
        fun create(application: Application, library: SqliteRecordLibrary, scope: CoroutineScope, log: SyncLog = AndroidSyncLog): SyncRuntime {
            val version = application.packageManager.getPackageInfo(application.packageName, 0).versionName.orEmpty()
            return SyncRuntime(
                library = library,
                pairingFile = NoBackupStorage.place(application.noBackupFilesDir, application.filesDir, PAIRING_FILE),
                box = AesGcmSecretBox({ KeystoreKeys.aesKey(PAIRING_KEY_ALIAS) }, purpose = "pairing"),
                scope = scope,
                clock = SystemClock,
                device = deviceInfo(version),
                discovery = NsdGatewayDiscovery(application, log = log),
                network = AndroidNetworkSignal(application),
                log = log,
                resume = JobSchedulerResume(application),
            )
        }
    }
}
