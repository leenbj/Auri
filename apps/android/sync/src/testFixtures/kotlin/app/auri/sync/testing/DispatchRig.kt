package app.auri.sync.testing

import app.auri.capture.contract.RevisionSource
import app.auri.capture.testing.FakeClock
import app.auri.capture.testing.InMemoryRecordLibrary
import app.auri.capture.testing.Samples
import app.auri.sync.destinations.AuthPurpose
import app.auri.sync.destinations.AuthResult
import app.auri.sync.destinations.DestinationService
import app.auri.sync.destinations.DispatchStore
import app.auri.sync.destinations.LocalAuthenticator
import app.auri.sync.destinations.ServiceResult
import app.auri.sync.discovery.GatewayLocator
import app.auri.sync.engine.JobKey
import app.auri.sync.engine.RecordLibrarySubmissionSource
import app.auri.sync.engine.RetryPolicy
import app.auri.sync.engine.SyncEngine
import app.auri.sync.gateway.CreateDestinationRequest
import app.auri.sync.gateway.Destination
import app.auri.sync.gateway.Intent
import app.auri.sync.gateway.SendMode
import app.auri.sync.gateway.SubmissionBuilder
import app.auri.sync.pairing.PairedGateway
import app.auri.sync.policy.DataFlowState
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.TestScope

/** 按剧本回答的本机身份核验：记下每次被问的目的，默认通过。 */
class ScriptedAuthenticator(@Volatile var next: AuthResult = AuthResult.Authenticated) : LocalAuthenticator {
    val purposes = CopyOnWriteArrayList<AuthPurpose>()

    @Volatile
    var gate: (suspend () -> Unit)? = null

    override suspend fun authenticate(purpose: AuthPurpose): AuthResult {
        purposes += purpose
        gate?.invoke()
        return next
    }
}

/**
 * Ticket 22 的整套假适配器：同步引擎与 Destination 服务是真的，网关（含 Destination 与投递模型）、局域网、本地 Record 库、发送账、本机身份核验都是假的。
 * 发送账与同步任务表共用一份“磁盘”，[restart] 换掉所有进程内对象、磁盘不动：模拟进程被杀后重启。
 */
class DispatchRig(
    val scope: TestScope,
    gateway: PairedGateway? = SyncSamples.pairedGateway(),
    gatewayOnLan: Boolean = true,
    /** 文字能不能进网关：装配时显式授权（见 SyncRig）。 */
    policyState: DataFlowState = InMemoryDataFlowPolicy.GRANTED,
) {
    val clock = FakeClock(scope.testScheduler)
    val lan = FakeLan()
    val discovery = FakeDiscovery()
    val server = FakeGateway(now = clock::now)
    val library = InMemoryRecordLibrary()
    val pairing = InMemoryPairingStore(gateway)
    val policy = InMemoryDataFlowPolicy(policyState)
    val log = CollectingLog()
    val authenticator = ScriptedAuthenticator()

    var fixture: DispatchFixture = InMemoryDispatchFixture()
        private set

    val dispatch: DispatchStore get() = fixture.store
    val jobs get() = fixture.jobs

    lateinit var engine: SyncEngine
        private set
    lateinit var service: DestinationService
        private set

    private var recordCounter = 0

    init {
        if (gatewayOnLan) lan.host(SyncSamples.LAN_ADDRESS, TlsFixtures.gatewayA) { server.handle(it) }
        assemble()
    }

    private fun assemble() {
        val locator = GatewayLocator(lan.factory, discovery, log)
        engine = SyncEngine(
            jobs = jobs,
            pairing = pairing,
            source = RecordLibrarySubmissionSource(library, SubmissionBuilder("0.1.0-test"), dispatch),
            http = lan.factory,
            locator = locator,
            clock = clock,
            policy = policy,
            retry = RetryPolicy(),
            log = log,
        )
        service = DestinationService(pairing, lan.factory, locator, dispatch, jobs, authenticator, clock, log)
    }

    /** 进程被杀后重启：引擎、服务、发送账实例全换新，磁盘（任务表、发送账、配对记录、Record 库）还是原来的。 */
    fun restart() {
        fixture = fixture.reopen()
        assemble()
    }

    /** 在网关上创建一个 Destination（经真实的服务，所以本机缓存也跟着更新）。 */
    suspend fun createDestination(name: String, mode: SendMode, intents: Set<Intent> = setOf(Intent.CONTEXT)): Destination {
        val result = service.create(CreateDestinationRequest(name, mode, intents))
        return (result as? ServiceResult.Ok)?.value ?: error("创建 Destination 失败：$result")
    }

    /** 一条新录音转写出了文字：Record 与它的 ASR 初稿 Revision 发布（未审核），发送账规划目标并必要时放行（Android：库的 publicationStep）。 */
    suspend fun transcribed(text: String = "明天检查小说记忆模块"): JobKey {
        val entry = Samples.entry(++recordCounter).copy(startedAt = clock.now())
        library.addRecord(entry)
        val revision = library.publishRevision(Samples.draft(entry.recordId, text, at = clock.now()))
        val key = JobKey(entry.recordId, revision.number)
        fixture.publish(key, RevisionSource.ASR, clock.now())
        return key
    }
}
