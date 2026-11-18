package app.auri.sync.testing

import app.auri.capture.testing.FakeClock
import app.auri.sync.SyncLog
import app.auri.sync.discovery.GatewayLocator
import app.auri.sync.engine.JobKey
import app.auri.sync.engine.RetryPolicy
import app.auri.sync.engine.SyncDecision
import app.auri.sync.engine.SyncEngine
import app.auri.sync.engine.SyncJob
import app.auri.sync.gateway.RevisionSubmission
import app.auri.sync.pairing.PairedGateway
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.TestScope

/** 收集日志行，测试逐行核对机密没有进过日志。 */
class CollectingLog : SyncLog {
    val lines = CopyOnWriteArrayList<String>()

    override fun event(line: String) {
        lines += line
    }
}

/**
 * 同步引擎的整套假适配器：内存任务表与配对记录、虚拟时钟、内存“局域网”（网关 A 坐在 [SyncSamples.LAN_ADDRESS]）、
 * 假 mDNS、假的本地 Record 库（[submissions]）。引擎本身是真的。
 */
class SyncRig(val scope: TestScope, gateway: PairedGateway? = SyncSamples.pairedGateway(), gatewayOnLan: Boolean = true) {
    val clock = FakeClock(scope.testScheduler)
    val lan = FakeLan()
    val discovery = FakeDiscovery()
    val server = FakeGateway(now = clock::now)
    val jobs = InMemorySyncJobStore()
    val pairing = InMemoryPairingStore(gateway)
    val log = CollectingLog()
    val decisions = CopyOnWriteArrayList<SyncDecision>()
    val submissions = ConcurrentHashMap<JobKey, RevisionSubmission>()

    /** 非 null 时，读本地 Record 库会抛它（模拟存储故障）。 */
    @Volatile
    var sourceFailure: Throwable? = null

    /** 一个新的引擎对象，接在同一套“磁盘”（任务表、配对记录）上：模拟进程重启。 */
    fun newEngine() = SyncEngine(
        jobs = jobs,
        pairing = pairing,
        source = { key -> sourceFailure?.let { throw it }; submissions[key] },
        http = lan.factory,
        locator = GatewayLocator(lan.factory, discovery, log),
        clock = clock,
        retry = RetryPolicy(),
        log = log,
        onDecision = { decisions += it },
    )

    val engine = newEngine()

    init {
        if (gatewayOnLan) lan.host(SyncSamples.LAN_ADDRESS, TlsFixtures.gatewayA) { server.handle(it) }
    }

    /** 发布一条 Revision：本地库里有了它，同步任务入队（真实应用里这两步在同一个事务里，见 SqliteSyncJobStore）。 */
    suspend fun publish(recordId: String = SyncSamples.RECORD_ID, revision: Int = 1, text: String = "你好，这是一条测试口述"): JobKey {
        val key = JobKey(recordId, revision)
        submissions[key] = SyncSamples.submission(recordId, revision, text)
        jobs.enqueue(key, clock.now())
        return key
    }

    suspend fun job(key: JobKey): SyncJob = jobs.get(key) ?: error("没有任务 $key")

    fun decisionsFor(key: JobKey) = decisions.filter { it.key == key }
}
