package app.auri.syncplatform

import android.app.Application
import app.auri.capture.coordinator.Clock
import app.auri.platform.library.SqliteRecordLibrary
import app.auri.platform.system.SystemClock
import app.auri.sync.SyncController
import app.auri.sync.SyncLog
import app.auri.sync.discovery.GatewayDiscovery
import app.auri.sync.discovery.GatewayLocator
import app.auri.sync.engine.JobKey
import app.auri.sync.engine.RecordLibrarySubmissionSource
import app.auri.sync.engine.SyncEngine
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
import app.auri.sync.status.SyncStatus
import app.auri.syncplatform.discovery.NsdGatewayDiscovery
import app.auri.syncplatform.jobs.SqliteSyncJobStore
import app.auri.syncplatform.keystore.KeystoreKeys
import app.auri.syncplatform.network.AndroidNetworkSignal
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 装配：Record 库和待同步任务表在同一个数据库文件里，发布 Revision 的事务里顺手登记任务。 */
object SyncStorage {
    /** 发布 Revision 时在**同一个事务**里入队一条待同步任务的 Record 库；任务的创建时间取 [clock]。 */
    fun library(application: Application, databaseName: String?, clock: Clock = SystemClock): SqliteRecordLibrary =
        SqliteRecordLibrary(
            application,
            databaseName,
            publicationStep = { db, revision -> SqliteSyncJobStore.enqueueIn(db, JobKey(revision.recordId, revision.number), clock.now()) },
            extraSchema = SqliteSyncJobStore.SCHEMA,
        )
}

/**
 * 配对与同步在 Android 上的全部装配：配对记录（Keystore 加密）、待同步任务表、固定公钥的 HTTPS、
 * mDNS 发现、网络回调、同步循环。界面只通过它读状态（[status]、[pairing]）和下指令（[pair]、[syncNow]）。
 *
 * 构造参数都可以替换：仪器测试里换成连宿主机上真网关的 HTTP 通道与固定的网络信号。
 * [start] 只调用一次，之后同步循环一直跑到 [scope] 被取消（进程被杀之后什么也不会发生，下次启动的第一轮把任务接回来）。
 */
class SyncRuntime(
    val library: SqliteRecordLibrary,
    pairingFile: File,
    box: SecretBox,
    private val scope: CoroutineScope,
    clock: Clock,
    device: DeviceInfo,
    discovery: GatewayDiscovery,
    network: NetworkSignal,
    http: HttpExchangeFactory = HttpExchangeFactory { pin -> PinnedHttpsClient(pin) },
    log: SyncLog = SyncLog.None,
) : SyncController {
    val jobs = SqliteSyncJobStore(library)

    /** 配对状态（可观察）。读它之前先 [start]：启动时才读第一次，之前没有任何状态。 */
    val pairing = ObservablePairingStore(FilePairingStore(pairingFile, box))

    private val pairingService = PairingService(pairing, http, discovery, clock, device, log)
    private val engine = SyncEngine(
        jobs = jobs,
        pairing = pairing,
        source = RecordLibrarySubmissionSource(library, SubmissionBuilder(device.appVersion)),
        http = http,
        locator = GatewayLocator(http, discovery, log),
        clock = clock,
        log = log,
    )
    private val loop = SyncLoop(engine, jobs, network, clock, log = log)

    /** 界面要的同步状态汇总：配对状态与任务表任何一边变了都重新汇总。 */
    override val status: Flow<SyncStatus> = combine(pairing.states, jobs.observe()) { pairingState, list -> SyncStatus.of(pairingState, list) }.distinctUntilChanged()

    fun start(): Job = scope.launch {
        pairing.refresh()
        // 上次进程在“刚配好对、还没把停下的任务放回队列”时被杀：配对记录是好的，任务却还停着。放回去。
        engine.resolveAfterRepair()
        loop.start(this)
    }

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

    companion object {
        private const val PAIRING_KEY_ALIAS = "auri.sync.pairing.v1"
        private const val PAIRING_FILE = "pairing.sealed"

        /** 真机/模拟器上的标准装配：Keystore 加密、NsdManager 发现、系统网络回调、固定公钥的 HTTPS。 */
        fun create(application: Application, library: SqliteRecordLibrary, scope: CoroutineScope, log: SyncLog = AndroidSyncLog): SyncRuntime {
            val version = application.packageManager.getPackageInfo(application.packageName, 0).versionName.orEmpty()
            return SyncRuntime(
                library = library,
                pairingFile = File(application.filesDir, PAIRING_FILE),
                box = AesGcmSecretBox({ KeystoreKeys.aesKey(PAIRING_KEY_ALIAS) }, purpose = "pairing"),
                scope = scope,
                clock = SystemClock,
                device = deviceInfo(version),
                discovery = NsdGatewayDiscovery(application, log = log),
                network = AndroidNetworkSignal(application),
                log = log,
            )
        }
    }
}
