package app.auri.sync.realgateway

import app.auri.capture.contract.ErrorCode
import app.auri.capture.contract.RevisionSource
import app.auri.capture.coordinator.Clock
import app.auri.capture.testing.InMemoryRecordLibrary
import app.auri.capture.testing.Samples
import app.auri.sync.destinations.AuthResult
import app.auri.sync.destinations.CredentialOutcome
import app.auri.sync.destinations.DestinationService
import app.auri.sync.destinations.ServiceResult
import app.auri.sync.discovery.GatewayDiscovery
import app.auri.sync.discovery.GatewayLocator
import app.auri.sync.engine.JobKey
import app.auri.sync.engine.PendingDeletions
import app.auri.sync.engine.RecordLibrarySubmissionSource
import app.auri.sync.engine.RetryPolicy
import app.auri.sync.engine.SyncEngine
import app.auri.sync.gateway.CreateDestinationRequest
import app.auri.sync.gateway.DeviceInfo
import app.auri.sync.gateway.GatewayClient
import app.auri.sync.gateway.GatewayResult
import app.auri.sync.gateway.Intent
import app.auri.sync.gateway.SendMode
import app.auri.sync.gateway.SubmissionBuilder
import app.auri.sync.http.HttpExchange
import app.auri.sync.http.HttpExchangeFactory
import app.auri.sync.http.HttpReply
import app.auri.sync.http.HttpRequest
import app.auri.sync.http.PinnedHttpsClient
import app.auri.sync.pairing.AesGcmSecretBox
import app.auri.sync.pairing.FilePairingStore
import app.auri.sync.pairing.GatewayAddress
import app.auri.sync.pairing.PairingOutcome
import app.auri.sync.pairing.PairingPayload
import app.auri.sync.pairing.PairingService
import app.auri.sync.pairing.PairingState
import app.auri.sync.pairing.PayloadParse
import app.auri.sync.testing.CollectingLog
import app.auri.sync.testing.InMemoryDataFlowPolicy
import app.auri.sync.testing.InMemoryDispatchFixture
import app.auri.sync.testing.ScriptedAuthenticator
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.KeyGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Ticket 23 真实网关测试（E2）：对着真实 Python 网关进程与官方 MCP 客户端验证：
 * - 删除 Record 后本地与网关都不可见，未领取的 Delivery 被取消，离线补传（旧 PUT）返回 410 RECORD_DELETED 且绝不复活；
 * - 离线删除（从未上传过）在恢复联网后向网关种下墓碑，后续补传同样无法复活。
 */
class RealGatewayRevisionsAndDeleteTest {
    @get:Rule
    val folder = TemporaryFolder()
    private val toClose = ArrayDeque<AutoCloseable>()

    @After
    fun closeEverything() {
        while (toClose.isNotEmpty()) runCatching { toClose.removeLast().close() }
    }

    private fun <T : AutoCloseable> T.tracked(): T = also { toClose.addLast(it) }

    private object RealClock : Clock {
        override fun monotonicNs() = System.nanoTime()
        override fun now(): Instant = Instant.now()
    }

    private object NoDiscovery : GatewayDiscovery {
        override suspend fun find(gatewayId: String): List<GatewayAddress> = emptyList()
    }

    private class MemoryPendingDeletions : PendingDeletions {
        private val items = linkedSetOf<String>()
        private val signal = MutableStateFlow(0L)

        fun add(recordId: String) {
            if (items.add(recordId)) signal.value += 1
        }

        override suspend fun pending(): List<String> = items.toList()

        override suspend fun markSynced(recordId: String) {
            if (items.remove(recordId)) signal.value += 1
        }

        override fun changes(): Flow<Long> = signal
    }

    private class RecordingExchange(private val real: HttpExchange, private val calls: MutableList<String>) : HttpExchange {
        override suspend fun execute(address: GatewayAddress, request: HttpRequest): HttpReply {
            calls += "${request.method} ${request.path}"
            return real.execute(address, request)
        }
    }

    private inner class Phone {
        val calls = CopyOnWriteArrayList<String>()
        val log = CollectingLog()
        val authenticator = ScriptedAuthenticator()
        val policy = InMemoryDataFlowPolicy(gateway = null)
        val fixture = InMemoryDispatchFixture()
        val pendingDeletions = MemoryPendingDeletions()
        val library = InMemoryRecordLibrary().apply {
            onDelete = { recordId, _ -> pendingDeletions.add(recordId) }
        }
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        private val dir = folder.newFolder()
        val store = FilePairingStore(File(dir, "pairing.bin"), AesGcmSecretBox({ key }, "pairing-record"))
        val http = HttpExchangeFactory { pin -> RecordingExchange(PinnedHttpsClient(pin), calls) }
        private val locator = GatewayLocator(http, NoDiscovery, log)
        private val pairing = PairingService(
            store, http, NoDiscovery, RealClock, DeviceInfo("1.0.0-t23", "jvm-real-gateway-test", "Ticket 23 real gateway test"),
            beforeSaving = { policy.forgetGrantExcept(it) }, log = log,
        )
        val engine = SyncEngine(
            jobs = fixture.jobs,
            pairing = store,
            source = RecordLibrarySubmissionSource(library, SubmissionBuilder("1.0.0-t23"), fixture.store),
            http = http,
            locator = locator,
            clock = RealClock,
            policy = policy,
            additions = fixture.store,
            retry = RetryPolicy(baseMs = 100, maxMs = 400),
            log = log,
            deletions = pendingDeletions,
        )
        val service = DestinationService(store, http, locator, fixture.store, fixture.jobs, authenticator, policy, RealClock, log)
        private var counter = 0

        suspend fun pairWith(payloadText: String) {
            val parsed = PairingPayload.parse(payloadText)
            check(parsed is PayloadParse.Valid) { "网关配对载荷无效：$parsed" }
            val outcome = pairing.pair(parsed.payload)
            check(outcome is PairingOutcome.Paired) { "配对失败：$outcome" }
            policy.gateway = outcome.gateway.identity
            policy.grantTo(outcome.gateway.identity)
        }

        suspend fun transcribed(text: String): JobKey {
            val entry = Samples.entry(++counter)
            library.addRecord(entry)
            val revision = library.publishRevision(Samples.draft(entry.recordId, text, at = RealClock.now()))
            val jobKey = JobKey(entry.recordId, revision.number)
            fixture.publish(jobKey, RevisionSource.ASR, RealClock.now())
            return jobKey
        }
    }

    @Test
    fun `删除 Record 后本地与真实网关都不可见，离线补传返回 RECORD_DELETED 且绝不复活`() = runBlocking {
        val gateway = RealGateway(folder.newFolder("gw-delete")).tracked().start()
        val phone = Phone()
        phone.pairWith(gateway.pairingPayloadJson())

        // 创建一个自动发送 Destination 并签发凭证，验证删除后 Agent 领不到也看不到
        val created = phone.service.create(CreateDestinationRequest("测试代理", SendMode.AUTO_SEND, setOf(Intent.CONTEXT)))
        val destination = (created as ServiceResult.Ok).value
        phone.authenticator.next = AuthResult.Authenticated
        val credential = (phone.service.issueCredential(destination.destinationId) as CredentialOutcome.Issued).credential.credential.reveal()

        val key = phone.transcribed("这条文字随后会被整条删除")
        val entryBeforeDelete = checkNotNull(phone.library.get(key.recordId))
        val revBeforeDelete = entryBeforeDelete.revisions.single()
        val staleSubmission = checkNotNull(SubmissionBuilder("1.0.0-t23").build(entryBeforeDelete, revBeforeDelete))

        phone.engine.runOnce()

        // 本地删除 Record 并同步删除墓碑到真实网关
        assertTrue(phone.library.deleteRecord(key.recordId, RealClock.now()))
        assertNull("删除后本地不可见", phone.library.get(key.recordId))
        assertTrue("本地留下墓碑", phone.library.isDeleted(key.recordId))

        phone.engine.runOnce()
        assertTrue("删除墓碑已同步到网关", phone.pendingDeletions.pending().isEmpty())
        assertTrue("向网关发出了 DELETE", "DELETE /v1/records/${key.recordId}" in phone.calls)

        // 网关状态查询确认 deleted=true
        val paired = (phone.store.load() as PairingState.Paired).gateway
        val client = GatewayClient(PinnedHttpsClient(paired.pin))
        val status = (client.recordStatus(paired.lastGoodAddress!!, paired.token, key.recordId) as GatewayResult.Success).value
        assertTrue("网关上该 Record 必须是已删除状态", status.deleted)

        // Agent 经真实 MCP 客户端领取：未领取的 Delivery 已被取消，领不到任何内容
        val agent = RealAgent(gateway, credential)
        val claimed = agent.claim().payload["deliveries"]!!.jsonArray
        assertTrue("删除后 Agent 不应再领到该 Record 的投递：$claimed", claimed.isEmpty())

        // 模拟离线旧补传：直接对网关再次 PUT 已删除 Record 的 Revision，必须被 410 RECORD_DELETED 拒绝，且绝不复活
        val resurrectAttempt = client.putRevision(paired.lastGoodAddress!!, paired.token, staleSubmission)
        assertTrue("离线补传必须被网关拒绝：$resurrectAttempt", resurrectAttempt is GatewayResult.Rejected)
        val rejected = resurrectAttempt as GatewayResult.Rejected
        assertEquals(410, rejected.status)
        assertEquals(ErrorCode.RECORD_DELETED, rejected.code)
        assertFalse(rejected.retryable)

        val statusAfter = (client.recordStatus(paired.lastGoodAddress!!, paired.token, key.recordId) as GatewayResult.Success).value
        assertTrue("离线补传失败后 Record 仍保持已删除，没有复活", statusAfter.deleted)
    }

    @Test
    fun `从未上传过的 Record 在离线删除后同步：网关种下墓碑，后续离线补传无法复活`() = runBlocking {
        val gateway = RealGateway(folder.newFolder("gw-offline-delete")).tracked().start()
        val phone = Phone()
        phone.pairWith(gateway.pairingPayloadJson())

        val entry = Samples.entry(42)
        phone.library.addRecord(entry)
        val rev = phone.library.publishRevision(Samples.draft(entry.recordId, "尚未上传就被删除的文字", at = RealClock.now()))
        val staleSubmission = checkNotNull(SubmissionBuilder("1.0.0-t23").build(entry, rev))

        // 在从未 PUT 到网关之前就删除了 Record
        assertTrue(phone.library.deleteRecord(entry.recordId, RealClock.now()))
        phone.engine.runOnce()

        assertTrue("同步发出了 DELETE", "DELETE /v1/records/${entry.recordId}" in phone.calls)
        assertFalse("从未发出过 PUT", phone.calls.any { it.startsWith("PUT /v1/records/${entry.recordId}") })
        assertTrue(phone.pendingDeletions.pending().isEmpty())

        // 后续任何离线补传（PUT）都必须被 410 RECORD_DELETED 拒绝
        val paired = (phone.store.load() as PairingState.Paired).gateway
        val client = GatewayClient(PinnedHttpsClient(paired.pin))
        val attempt = client.putRevision(paired.lastGoodAddress!!, paired.token, staleSubmission)
        assertTrue(attempt is GatewayResult.Rejected)
        assertEquals(410, (attempt as GatewayResult.Rejected).status)
        assertEquals(ErrorCode.RECORD_DELETED, attempt.code)
    }
}
