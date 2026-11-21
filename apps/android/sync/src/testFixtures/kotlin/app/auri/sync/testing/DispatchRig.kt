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
import app.auri.sync.gateway.ContractGuard
import app.auri.sync.gateway.CreateDestinationRequest
import app.auri.sync.gateway.Destination
import app.auri.sync.gateway.Intent
import app.auri.sync.gateway.SendMode
import app.auri.sync.gateway.SubmissionBuilder
import app.auri.sync.pairing.GatewayAddress
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
    /** 引擎、服务与来源看到的发送账可以包一层（复评 R5 的测试在写账的瞬间停住，看这时重新配对能不能插进来）；rig.dispatch 仍是没包的那份。 */
    private val wrapLedger: (DispatchStore) -> DispatchStore = { it },
) {
    val clock = FakeClock(scope.testScheduler)
    val lan = FakeLan()
    val discovery = FakeDiscovery()
    val server = FakeGateway(now = clock::now)
    val library = InMemoryRecordLibrary()
    val pairing = InMemoryPairingStore(gateway)
    val policy = InMemoryDataFlowPolicy(policyState, gateway?.identity) // 授权是给这个网关的
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
        val contract = ContractGuard() // 和线上一样：引擎与管理页共用一个，重启（assemble）才换新
        val ledger = wrapLedger(dispatch)
        engine = SyncEngine(
            jobs = jobs,
            pairing = pairing,
            source = RecordLibrarySubmissionSource(library, SubmissionBuilder("0.1.0-test"), ledger),
            http = lan.factory,
            locator = locator,
            clock = clock,
            policy = policy,
            additions = ledger,
            retry = RetryPolicy(),
            log = log,
            contract = contract,
        )
        service = DestinationService(pairing, lan.factory, locator, ledger, jobs, authenticator, clock, log, contract)
    }

    /** 进程被杀后重启：引擎、服务、发送账实例全换新，磁盘（任务表、发送账、配对记录、Record 库）还是原来的。 */
    fun restart() {
        fixture = fixture.reopen()
        assemble()
    }

    /**
     * 对着另一个网关 B 重新配对（不同的网关编号、公钥、地址；B 上一个 Destination 也没有），返回 B。
     * [grant] = 用户随即对 B 另行同意文字进网关（复评 R3：授权是给某个网关的，换了配对要重新同意）；
     * false = 授权清理还没跑到（或失败了）：旧网关的授权原样留着，用来造“旧配对的一轮醒来时看起来还被允许”的缝。
     */
    suspend fun repairWithAnotherGateway(grant: Boolean = true): FakeGateway {
        val other = FakeGateway(now = clock::now)
        lan.host(ADDRESS_B, TlsFixtures.gatewayB) { other.handle(it) }
        val gatewayB = SyncSamples.pairedGateway(pin = TlsFixtures.gatewayB.pin, addresses = listOf(ADDRESS_B)).copy(gatewayId = GATEWAY_B_ID)
        pairing.save(gatewayB)
        if (grant) policy.grantTo(gatewayB.identity)
        return other
    }

    /** 在网关上创建一个 Destination（经真实的服务，所以本机缓存也跟着更新）。 */
    suspend fun createDestination(name: String, mode: SendMode, intents: Set<Intent> = setOf(Intent.CONTEXT)): Destination {
        val result = service.create(CreateDestinationRequest(name, mode, intents))
        return (result as? ServiceResult.Ok)?.value ?: error("创建 Destination 失败：$result")
    }

    /** 一条新录音转写出了文字：Record 与它的 ASR 初稿 Revision 发布（未审核），同步任务登记、发送账规划目标（Android：库的 publicationStep）。 */
    suspend fun transcribed(text: String = "明天检查小说记忆模块"): JobKey {
        val entry = Samples.entry(++recordCounter).copy(startedAt = clock.now())
        library.addRecord(entry)
        val revision = library.publishRevision(Samples.draft(entry.recordId, text, at = clock.now()))
        val key = JobKey(entry.recordId, revision.number)
        fixture.publish(key, RevisionSource.ASR, clock.now())
        return key
    }

    companion object {
        /** [repairWithAnotherGateway] 里网关 B 的地址与编号。 */
        val ADDRESS_B = GatewayAddress("192.168.1.30", 8443)
        const val GATEWAY_B_ID = "0f0f0f0f-1bfb-53b3-85a3-4d36b680fe6e"
    }
}
