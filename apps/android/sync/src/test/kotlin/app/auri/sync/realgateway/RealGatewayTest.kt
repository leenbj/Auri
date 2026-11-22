package app.auri.sync.realgateway

import app.auri.capture.coordinator.Clock
import app.auri.capture.testing.InMemoryRecordLibrary
import app.auri.capture.testing.RepoContracts
import app.auri.capture.testing.Samples
import app.auri.sync.discovery.GatewayDiscovery
import app.auri.sync.discovery.GatewayLocator
import app.auri.sync.engine.BlockedReason
import app.auri.sync.engine.JobKey
import app.auri.sync.engine.RecordLibrarySubmissionSource
import app.auri.sync.engine.RetryPolicy
import app.auri.sync.engine.SubmissionSource
import app.auri.sync.engine.SyncEngine
import app.auri.sync.gateway.DeviceInfo
import app.auri.sync.gateway.GatewayClient
import app.auri.sync.gateway.GatewayResult
import app.auri.sync.gateway.RevisionSubmission
import app.auri.sync.gateway.StoredResponse
import app.auri.sync.gateway.SubmissionBuilder
import app.auri.sync.http.HttpExchange
import app.auri.sync.http.HttpExchangeFactory
import app.auri.sync.http.HttpReply
import app.auri.sync.http.HttpRequest
import app.auri.sync.http.PinnedHttpsClient
import app.auri.sync.http.TransportFailure
import app.auri.sync.machine.SyncState
import app.auri.sync.pairing.AesGcmSecretBox
import app.auri.sync.pairing.FilePairingStore
import app.auri.sync.pairing.GatewayAddress
import app.auri.sync.pairing.NeedsUser
import app.auri.sync.pairing.PairedGateway
import app.auri.sync.pairing.PairingOutcome
import app.auri.sync.pairing.PairingPayload
import app.auri.sync.pairing.PairingService
import app.auri.sync.pairing.PairingState
import app.auri.sync.pairing.PayloadParse
import app.auri.sync.status.SyncHeadline
import app.auri.sync.status.SyncStatus
import app.auri.sync.status.UserCause
import app.auri.sync.testing.CollectingLog
import app.auri.sync.testing.FixtureRecords
import app.auri.sync.testing.InMemoryDataFlowPolicy
import app.auri.sync.testing.InMemorySyncJobStore
import app.auri.sync.testing.SyncSamples
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Ticket 21 的“真实网关测试”：手机端的真实代码（固定公钥的 HTTPS 客户端、加密落盘的配对记录、同步引擎、SubmissionBuilder）
 * 对着真实的 Python 网关进程说话——services/gateway 的 `python -m auri_gateway serve`，自签 TLS，临时数据目录，随机端口。
 * 网关一侧没有任何 mock：配对码、令牌、入库、幂等、吊销、数据库都是网关自己的代码。
 * 证据等级 E2（电脑上跑，不是真机、不是真的局域网），不能替代真机验收。
 *
 * 唯一的“假”：任务表与 Record 库在内存里（真实的 SQLite 实现在 :syncplatform 的仪器测试里验证）；
 * 以及两处明说的故障注入——[Fault]——用来造“响应丢了”和“请求没送到”，其余的网络路径全是真的。
 * 想在电脑上、没有 Gradle 进程之外的依赖下跑：./gradlew :sync:realGatewayTest（找不到仓库的 .venv 就直接失败，不会跳过）。
 */
class RealGatewayTest {
    @get:Rule
    val folder = TemporaryFolder()
    private val toClose = ArrayDeque<AutoCloseable>()

    @After
    fun closeEverything() {
        while (toClose.isNotEmpty()) runCatching { toClose.removeLast().close() }
    }

    private fun <T : AutoCloseable> T.tracked(): T = also { toClose.addLast(it) }

    // ---- 实验台 ----

    private enum class Fault {
        NONE,

        /** 请求根本没送到网关（手机端看到的和响应丢了一样：不确定）。 */
        LOSE_REQUEST,

        /** 请求送到了、网关处理完了，响应在回来的路上丢了。网关真的收到并入库了，手机不知道。 */
        LOSE_RESPONSE,
    }

    private object RealClock : Clock {
        override fun monotonicNs() = System.nanoTime()

        override fun now(): Instant = Instant.now()
    }

    private object NoDiscovery : GatewayDiscovery {
        override suspend fun find(gatewayId: String): List<GatewayAddress> = emptyList()
    }

    /** mDNS 的替身（JVM 里没有 NsdManager）：只在被问到时给出事先设好的候选地址，并数被问了几次。 */
    private class ScriptedDiscovery : GatewayDiscovery {
        @Volatile
        var candidates: List<GatewayAddress> = emptyList()
        val asked = AtomicInteger()

        override suspend fun find(gatewayId: String): List<GatewayAddress> = candidates.also { asked.incrementAndGet() }
    }

    /** 一部“手机”。 */
    private inner class Phone(
        val dir: File,
        val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey(),
        discovery: GatewayDiscovery = NoDiscovery,
        /**
         * 装配时显式授权：这个文件测的是同步与配对本身。授权是给某个网关的（复评 R3），所以配对成功后随即同意新配对的那一台（见 [pairWith]）。
         * 授权存在用户的数据库里，重建本地库、重启都不丢：重建出来的“同一部手机”接着用同一份。
         */
        val policy: InMemoryDataFlowPolicy = InMemoryDataFlowPolicy(gateway = null),
    ) {
        /** 引擎与配对服务发出的每个请求（“方法 路径”），按顺序。 */
        val calls = CopyOnWriteArrayList<String>()

        /** 其中真正送到了网关的请求数。 */
        val forwarded = AtomicInteger()

        @Volatile
        var fault: (HttpRequest) -> Fault = { Fault.NONE }
        val log = CollectingLog()
        val pairingFile = File(dir, "pairing.bin").also { dir.mkdirs() }
        val store = FilePairingStore(pairingFile, AesGcmSecretBox({ key }, "pairing-record"))
        val jobs = InMemorySyncJobStore()
        val library = InMemoryRecordLibrary()

        /** 优先于本地库给出的请求体（用来造库里表达不出来的情形）。 */
        val overrides = ConcurrentHashMap<JobKey, RevisionSubmission>()
        val builder = SubmissionBuilder(appVersion = APP_VERSION)

        private val http = HttpExchangeFactory { pin -> FaultInjectingExchange(PinnedHttpsClient(pin), this) }
        private val librarySource = RecordLibrarySubmissionSource(library, builder)
        val engine = SyncEngine(
            jobs = jobs,
            pairing = store,
            source = SubmissionSource { jobKey -> overrides[jobKey] ?: librarySource.submissionFor(jobKey) },
            http = http,
            locator = GatewayLocator(http, discovery, log),
            clock = RealClock,
            policy = policy,
            retry = RetryPolicy(baseMs = 100, maxMs = 400),
            log = log,
        )
        val pairing = PairingService(
            store, http, discovery, RealClock, DeviceInfo(APP_VERSION, "jvm-real-gateway-test", "Ticket 21 real gateway test"),
            beforeSaving = { policy.forgetGrantExcept(it) }, log = log,
        )

        /** 发布一条 Revision：本地库里有了，同步任务入队。 */
        suspend fun publish(n: Int, text: String): JobKey {
            val entry = Samples.entry(n)
            library.addRecord(entry)
            val revision = library.publishRevision(Samples.draft(entry.recordId, text))
            return JobKey(entry.recordId, revision.number).also { jobs.enqueue(it, RealClock.now()) }
        }

        suspend fun pairWith(payloadText: String): PairedGateway {
            val parsed = PairingPayload.parse(payloadText)
            check(parsed is PayloadParse.Valid) { "网关给的配对载荷手机端不认：$parsed" }
            val outcome = pairing.pair(parsed.payload)
            check(outcome is PairingOutcome.Paired) { "配对结果不是成功：$outcome" }
            policy.gateway = outcome.gateway.identity
            policy.grantTo(outcome.gateway.identity) // 测试里的用户配对之后随即同意文字发给它
            return outcome.gateway
        }

        suspend fun status(): SyncStatus = SyncStatus.of(store.load(), jobs.all(), InMemoryDataFlowPolicy.GRANTED)

        /** 非健康检查的请求。 */
        fun requests(): List<String> = calls.filterNot { it.startsWith("GET /health") }
    }

    private class FaultInjectingExchange(private val real: HttpExchange, private val phone: Phone) : HttpExchange {
        override suspend fun execute(address: GatewayAddress, request: HttpRequest): HttpReply {
            phone.calls += "${request.method} ${request.path}"
            val fault = phone.fault(request)
            if (fault == Fault.LOSE_REQUEST) throw TransportFailure.Interrupted()
            phone.forwarded.incrementAndGet()
            val reply = real.execute(address, request) // 真实的 TLS、真实的指纹核对、真实的网关
            if (fault == Fault.LOSE_RESPONSE) throw TransportFailure.Interrupted()
            return reply
        }
    }

    /** 一台网关、一个转发器（手机记着的地址是转发器的）、一部手机。 */
    private inner class Lab {
        val gateway = RealGateway(folder.newFolder()).tracked().start()
        val proxy = TcpProxy(gateway.port).tracked()
        val discovery = ScriptedDiscovery()
        val phone = Phone(folder.newFolder(), discovery = discovery)

        fun payload(of: RealGateway = gateway): String = of.pairingPayloadJson(advertisedPort = proxy.port)

        suspend fun pair(): PairedGateway = phone.pairWith(payload())
    }

    private fun addressOf(paired: PairedGateway): GatewayAddress = paired.addresses.first()

    private fun sha(text: String) = SyncSamples.sha256Hex(text)

    // ---- 1. 二维码配对 ----

    @Test
    fun `二维码配对：网关打印的真二维码手机端读得出，配对成功，令牌只以密文落盘，网关登记了这台设备，配对码只能用一次`() = runBlocking {
        val lab = Lab()
        val terminal = lab.gateway.pairingTerminalOutput(advertisedPort = lab.proxy.port)

        val text = AsciiQr.decode(terminal)
        assertNotNull("网关命令行打印的二维码，手机端的解码器必须读得出来", text)
        val parsed = PairingPayload.parse(text!!)
        assertTrue("二维码里必须是合同允许的配对载荷：$parsed", parsed is PayloadParse.Valid)
        val payload = (parsed as PayloadParse.Valid).payload
        assertEquals(listOf(GatewayAddress("127.0.0.1", lab.proxy.port)), payload.addresses)
        assertEquals("载荷里的指纹必须是网关证书文件里那把公钥的指纹（测试自己算的，不是问网关要的）", lab.gateway.pin, payload.publicKeySha256)

        val outcome = lab.phone.pairing.pair(payload)
        assertTrue("配对结果：$outcome", outcome is PairingOutcome.Paired)
        val paired = (outcome as PairingOutcome.Paired).gateway
        assertEquals(lab.gateway.pin, paired.pin)
        assertEquals("配对后记下的是配对时成功连上的地址", GatewayAddress("127.0.0.1", lab.proxy.port), paired.lastGoodAddress ?: addressOf(paired))

        // 网关登记了这台设备
        val devices = lab.gateway.devices()
        assertEquals(1, devices.size)
        assertEquals(paired.deviceId, devices.single().getValue("device_id").jsonPrimitive.content)

        // 配对记录：读回来一模一样；磁盘上只有密文（令牌、配对码、指纹、地址都不以明文出现）
        assertEquals(PairingState.Paired(paired), lab.phone.store.load())
        val onDisk = String(lab.phone.pairingFile.readBytes(), Charsets.ISO_8859_1)
        val secrets = mapOf("设备令牌" to paired.token.reveal(), "配对码" to payload.pairingCode, "公钥指纹" to payload.publicKeySha256.hex, "网关地址" to "127.0.0.1")
        for ((what, secret) in secrets) assertFalse("$what 不能以明文出现在配对记录文件里", onDisk.contains(secret))
        for ((what, secret) in secrets) {
            assertTrue("$what 不能出现在手机端的日志里", lab.phone.log.lines.none { it.contains(secret) })
        }
        assertTrue("配对过程应当留下日志（否则上面的检查什么也没检查）", lab.phone.log.lines.isNotEmpty())

        // 配对码是一次性的：再用一次，网关拒绝，手机上已有的配对记录不变
        assertEquals(PairingOutcome.CodeRejected, lab.phone.pairing.pair(payload))
        assertEquals(PairingState.Paired(paired), lab.phone.store.load())
        assertEquals(1, lab.gateway.devices().size)
        Unit
    }

    // ---- 2. 入库 ----

    @Test
    fun `入库：引擎把一条 Revision 送到真实网关，正文与哈希逐字节一致，来源设备是这部手机，同步状态变成已同步`() = runBlocking {
        val lab = Lab()
        val paired = lab.pair()
        // 前后各带空白与换行：正文必须原样送到，网关里存的与手机上的逐字节一样（哈希才对得上）
        val text = "  帮我记录一下：明天检查小说的记忆模块。café 🙂 “引号”与\\反斜杠\n第二行\n\n"
        val key = lab.phone.publish(1, text)

        lab.phone.engine.runOnce()

        assertEquals(SyncState.STORED, lab.phone.jobs.get(key)!!.state)
        val rows = lab.gateway.query("SELECT text, text_sha256, origin_device_id FROM revisions WHERE record_id = ? AND revision = ?", key.recordId, key.revision.toString())
        assertEquals("网关里应当恰好有这一版", 1, rows.size)
        assertEquals("网关存的正文必须与手机上的逐字相同", text, rows.single()[0])
        assertEquals(sha(text), rows.single()[1])
        assertEquals("来源设备是这部手机", paired.deviceId, rows.single()[2])
        assertEquals(1, lab.gateway.count("SELECT COUNT(*) FROM records"))
        assertEquals(SyncHeadline.UP_TO_DATE, lab.phone.status().headline)
        assertEquals("只发了一次 PUT", 1, lab.phone.requests().count { it.startsWith("PUT ") })
        Unit
    }

    @Test
    fun `合同里所有有效的 submission 样本，手机端组出的请求真实网关都收下，包括被截断的和跳号的`() = runBlocking {
        val lab = Lab()
        val paired = lab.pair()
        val client = GatewayClient(PinnedHttpsClient(paired.pin))
        val files = RepoContracts.contractsDir.resolve("fixtures/messages/valid").listFiles { f -> f.name.startsWith("revision.submission.") }!!.sortedBy { it.name }
        assertTrue("样本目录里应当有 submission 样本", files.size >= 5)
        val builder = SubmissionBuilder(APP_VERSION)

        for (file in files) {
            val instance = RepoContracts.json("fixtures/messages/valid/${file.name}").jsonObject.getValue("instance").jsonObject
            val (entry, revision) = FixtureRecords.localDataFrom(instance)
            val submission = builder.build(entry, revision)
            assertNotNull("${file.name}：合同说有效，却组不出请求", submission)
            val result = client.putRevision(addressOf(paired), paired.token, submission!!)
            assertTrue("${file.name}：真实网关没有收下 → $result", result is GatewayResult.Success)
            val stored = (result as GatewayResult.Success<StoredResponse>).value
            assertFalse("${file.name}：第一次提交不应当是重复", stored.duplicate)
            val row = lab.gateway.query("SELECT text, text_sha256 FROM revisions WHERE record_id = ? AND revision = ?", submission.recordId, submission.revision.toString()).single()
            assertEquals("${file.name}：网关存的正文", revision.text, row[0])
            assertEquals("${file.name}：网关存的哈希", revision.textSha256, row[1])
        }
        assertEquals("每个样本一版", files.size, lab.gateway.count("SELECT COUNT(*) FROM revisions"))
        Unit
    }

    // ---- 3. 幂等与永久拒绝 ----

    @Test
    fun `重复提交同一版：网关回 duplicate 并给出首次入库的结果，库里仍是一条`() = runBlocking {
        val lab = Lab()
        val paired = lab.pair()
        val entry = Samples.entry(1)
        lab.phone.library.addRecord(entry)
        val revision = lab.phone.library.publishRevision(Samples.draft(entry.recordId, "重复提交也只留一条"))
        val submission = lab.phone.builder.build(lab.phone.library.get(entry.recordId)!!, revision)!!
        val client = GatewayClient(PinnedHttpsClient(paired.pin))

        val first = (client.putRevision(addressOf(paired), paired.token, submission) as GatewayResult.Success).value
        val second = (client.putRevision(addressOf(paired), paired.token, submission) as GatewayResult.Success).value

        assertFalse(first.duplicate)
        assertTrue("同一版第二次提交，网关必须说这是重复", second.duplicate)
        assertEquals("重复提交返回的是首次入库的结果", first.operationId, second.operationId)
        assertEquals(first.storedAt, second.storedAt)
        assertEquals(1, lab.gateway.count("SELECT COUNT(*) FROM revisions"))
        assertEquals(1, lab.gateway.count("SELECT COUNT(*) FROM records"))
        Unit
    }

    @Test
    fun `别的设备改这条 Record：网关永久拒绝，任务停在需要处理，之后再多轮也不再发请求`() = runBlocking {
        val lab = Lab()
        val paired = lab.pair()
        val builder = SubmissionBuilder(APP_VERSION)
        val samples = listOf("asr-first-revision", "user-edit-skipped-revision").map {
            FixtureRecords.localDataFrom(RepoContracts.json("fixtures/messages/valid/revision.submission.$it.json").jsonObject.getValue("instance").jsonObject)
        }
        val (firstEntry, firstRevision) = samples[0]
        val (editEntry, editRevision) = samples[1]
        assertEquals("这两个样本是同一条 Record 的两版", firstEntry.recordId, editEntry.recordId)
        val stored = GatewayClient(PinnedHttpsClient(paired.pin)).putRevision(addressOf(paired), paired.token, builder.build(firstEntry, firstRevision)!!)
        assertTrue("第一版由第一部手机入库：$stored", stored is GatewayResult.Success)

        // 第二部手机（另一台设备）想给同一条 Record 发第 3 版：ADR-0020，只有录下它的那台设备能写
        val other = Phone(folder.newFolder())
        other.pairWith(lab.payload())
        val key = JobKey(editEntry.recordId, editRevision.number)
        other.overrides[key] = builder.build(editEntry, editRevision)!!
        other.jobs.enqueue(key, RealClock.now())

        other.engine.runOnce()

        val job = other.jobs.get(key)!!
        assertEquals(SyncState.BLOCKED, job.state)
        assertEquals(BlockedReason.NOT_ORIGIN_DEVICE, job.blockedReason)
        assertEquals(1, job.attempts)
        val status = other.status()
        assertEquals(SyncHeadline.NEEDS_USER, status.headline)
        assertTrue(UserCause.NOT_ORIGIN_DEVICE in status.causes)
        val before = other.requests().size
        repeat(3) { other.engine.runOnce() }
        assertEquals("永久拒绝之后，再多轮同步也不会再发请求", before, other.requests().size)
        assertEquals(1, other.jobs.get(key)!!.attempts)
        assertEquals("网关里没有被拒绝的那一版", 0, lab.gateway.count("SELECT COUNT(*) FROM revisions WHERE record_id = ? AND revision = ?", key.recordId, key.revision.toString()))
        Unit
    }

    @Test
    fun `同一版号、正文不同：网关回版本冲突，任务永久停下，库里留着最初的那一版`() = runBlocking {
        val lab = Lab()
        lab.pair()
        val original = lab.phone.publish(1, "最初的正文")
        lab.phone.engine.runOnce()
        assertEquals(SyncState.STORED, lab.phone.jobs.get(original)!!.state)

        // 同一部手机（同一份配对记录），本地库被重建，同一条 Record 的第 1 版换了正文
        val rebuilt = Phone(lab.phone.dir, lab.phone.key, policy = lab.phone.policy)
        val conflicting = rebuilt.publish(1, "换了的正文，版本号却没变")
        assertEquals(original, conflicting)

        rebuilt.engine.runOnce()

        val job = rebuilt.jobs.get(conflicting)!!
        assertEquals(SyncState.BLOCKED, job.state)
        assertEquals(BlockedReason.VERSION_CONFLICT, job.blockedReason)
        val before = rebuilt.requests().size
        repeat(3) { rebuilt.engine.runOnce() }
        assertEquals("版本冲突是永久拒绝，不会一直重试", before, rebuilt.requests().size)
        assertEquals("最初的正文原样留在网关里", "最初的正文", lab.gateway.query("SELECT text FROM revisions WHERE record_id = ?", original.recordId).single().single())
        assertEquals(1, lab.gateway.count("SELECT COUNT(*) FROM revisions"))
        Unit
    }

    // ---- 4. 结果不确定 ----

    @Test
    fun `响应在路上丢了、网关其实已经入库：先查询得知已入库，不重发，库里只有一条`() = runBlocking {
        val lab = Lab()
        lab.pair()
        val key = lab.phone.publish(1, "响应丢了，但网关已经收下")
        lab.phone.fault = { request -> if (request.method == "PUT") Fault.LOSE_RESPONSE else Fault.NONE }

        lab.phone.engine.runOnce()

        val uncertain = lab.phone.jobs.get(key)!!
        assertEquals(SyncState.RETRY_SCHEDULED, uncertain.state)
        assertTrue("请求发出去了却没拿到结果：必须记成不确定", uncertain.uncertain)
        assertEquals("网关其实已经入库了", 1, lab.gateway.count("SELECT COUNT(*) FROM revisions WHERE record_id = ?", key.recordId))
        assertEquals(SyncHeadline.RETRYING_LATER, lab.phone.status().headline)

        lab.phone.fault = { Fault.NONE }
        delay(500) // 超过退避时间
        lab.phone.engine.runOnce()

        assertEquals(SyncState.STORED, lab.phone.jobs.get(key)!!.state)
        val id = key.recordId
        assertEquals(
            "不确定时必须先问网关（GET status），问到已入库就确认，不能再 PUT 一次",
            // 开头那一次是合同版本探测（评审 R7：每份配对问一次）；PUT 的传输失败（响应丢了）会作废“兼容”的记忆（再评 S6），所以状态查询之前再探测一次，顺序仍是先问版本、再问状态、不再 PUT
            listOf("GET /v1/capabilities", "PUT /v1/records/$id/revisions/1", "GET /v1/capabilities", "GET /v1/records/$id/status"),
            lab.phone.requests().filter { !it.startsWith("POST ") },
        )
        assertEquals("网关里仍然只有一条", 1, lab.gateway.count("SELECT COUNT(*) FROM revisions"))
        assertEquals(1, lab.gateway.count("SELECT COUNT(*) FROM records"))
        assertEquals("到过网关的连接数 = 手机真正发出的请求数：没有别的路径在悄悄发请求", lab.phone.forwarded.get(), lab.proxy.reachedGateway.get())
        Unit
    }

    @Test
    fun `请求没送到网关：先查询得知没有，才重新提交一次，库里恰好一条`() = runBlocking {
        val lab = Lab()
        lab.pair()
        val key = lab.phone.publish(1, "第一次没送到")
        lab.phone.fault = { request -> if (request.method == "PUT") Fault.LOSE_REQUEST else Fault.NONE }

        lab.phone.engine.runOnce()

        assertEquals(SyncState.RETRY_SCHEDULED, lab.phone.jobs.get(key)!!.state)
        assertEquals("请求根本没到，网关里什么也没有", 0, lab.gateway.count("SELECT COUNT(*) FROM revisions"))

        lab.phone.fault = { Fault.NONE }
        delay(500)
        lab.phone.engine.runOnce()

        assertEquals(SyncState.STORED, lab.phone.jobs.get(key)!!.state)
        val id = key.recordId
        assertEquals(
            "先问（网关说没有这条），确认没有之后才重新提交",
            // 开头一次是合同版本探测（评审 R7）；PUT 的传输失败（请求没送到）会作废“兼容”的记忆（再评 S6），所以状态查询之前再探测一次，确认没有之后才重新提交
            listOf("GET /v1/capabilities", "PUT /v1/records/$id/revisions/1", "GET /v1/capabilities", "GET /v1/records/$id/status", "PUT /v1/records/$id/revisions/1"),
            lab.phone.requests().filter { !it.startsWith("POST ") },
        )
        assertEquals("真正到过网关的 PUT 只有一次", 1, lab.gateway.count("SELECT COUNT(*) FROM revisions"))
        Unit
    }

    // ---- 5. 网关不在 / 换了一台 / 吊销 ----

    @Test
    fun `地址搬家：记着的地址连不上就用发现（mDNS 的替身）找到网关的新地址，核对同一把公钥后送达，并记住新地址`() = runBlocking {
        val lab = Lab()
        lab.pair() // 记下的地址是转发器 A
        val key = lab.phone.publish(1, "网关换了地址之后写的")
        lab.proxy.close() // 记着的地址从此没人听；网关其实还在，只是换了地址
        val moved = TcpProxy(lab.gateway.port).tracked()
        val newAddress = GatewayAddress("127.0.0.1", moved.port)
        lab.discovery.candidates = listOf(newAddress)

        lab.phone.engine.runOnce()

        assertEquals(SyncState.STORED, lab.phone.jobs.get(key)!!.state)
        assertEquals("网关里有这一条", 1, lab.gateway.count("SELECT COUNT(*) FROM revisions WHERE record_id = ?", key.recordId))
        assertEquals("记着的地址不通才去问发现，而且只问一次", 1, lab.discovery.asked.get())
        assertTrue("请求是经新地址送到网关的", moved.reachedGateway.get() > 0)
        assertEquals("连上的新地址被记住", newAddress, ((lab.phone.store.load()) as PairingState.Paired).gateway.lastGoodAddress)

        // 记住了新地址：下一条先连它，不再需要发现
        val next = lab.phone.publish(2, "之后又写的")
        lab.phone.engine.runOnce()

        assertEquals(SyncState.STORED, lab.phone.jobs.get(next)!!.state)
        assertEquals("先连最近连上的地址：不再问发现", 1, lab.discovery.asked.get())
        Unit
    }

    @Test
    fun `网关关机：任务等网关，一条不丢；网关重启（同一份数据）之后自动送达`() = runBlocking {
        val lab = Lab()
        lab.pair()
        lab.gateway.stop()
        val key = lab.phone.publish(1, "网关睡着的时候写的")
        val second = lab.phone.publish(2, "网关睡着的时候写的第二条") // 这一轮只会试第一条：连不上就不必再试别的，但第二条也要如实标成等网关

        lab.phone.engine.runOnce()

        assertEquals(SyncState.WAITING_GATEWAY, lab.phone.jobs.get(key)!!.state)
        assertEquals("没有被试过的排队任务也改成等网关，界面不会一边说在发送一边什么也没发", SyncState.WAITING_GATEWAY, lab.phone.jobs.get(second)!!.state)
        assertEquals(SyncHeadline.WAITING_FOR_GATEWAY, lab.phone.status().headline)
        assertTrue("网关不在不是用户的事", lab.phone.status().causes.isEmpty())

        // 同一个数据目录（同一把 TLS 密钥）再启动，端口变了；手机记着的地址（转发器）不变
        val restarted = RealGateway(File(lab.gateway.dataDir.path)).tracked().start()
        lab.proxy.target = restarted.port
        lab.phone.engine.runOnce()

        assertEquals(SyncState.STORED, lab.phone.jobs.get(key)!!.state)
        assertEquals(SyncState.STORED, lab.phone.jobs.get(second)!!.state)
        assertEquals("重启后的网关里有这一条", 1, restarted.count("SELECT COUNT(*) FROM revisions WHERE record_id = ?", key.recordId))
        assertEquals("重启后的网关里有第二条", 1, restarted.count("SELECT COUNT(*) FROM revisions WHERE record_id = ?", second.recordId))
        assertEquals(SyncHeadline.UP_TO_DATE, lab.phone.status().headline)
        Unit
    }

    @Test
    fun `换了一台网关（公钥不同）：拒绝连接，需要重新配对，期间一个连接都不再发；重新配对后送达新网关`() = runBlocking {
        val lab = Lab()
        lab.pair()
        val key = lab.phone.publish(1, "不能发给陌生的网关")
        val stranger = RealGateway(folder.newFolder()).tracked().start()
        assertTrue("两台网关的公钥必须不同，否则这个测试没有意义", stranger.pin != lab.gateway.pin)
        lab.proxy.target = stranger.port // 手机记着的地址现在连到了另一台网关

        lab.phone.engine.runOnce()

        val status = lab.phone.status()
        assertEquals(SyncHeadline.NEEDS_USER, status.headline)
        assertTrue("原因要是公钥不符（界面据此提示重新配对）：${status.causes}", UserCause.KEY_MISMATCH in status.causes)
        assertEquals(NeedsUser.KEY_MISMATCH, ((lab.phone.store.load()) as PairingState.Paired).gateway.needsUser)
        assertEquals("陌生网关里什么也没有：没有设备、没有文字", 0, stranger.count("SELECT COUNT(*) FROM revisions") + stranger.count("SELECT COUNT(*) FROM devices"))
        // 用户还没处理，手机上又录了一条：公钥不符是对整个连接成立的事实，新任务也不能带着令牌去敲这个地址
        // （只看已经停下的那条任务，任务本来就不会再发，这个检查等于没做）
        val later = lab.phone.publish(2, "公钥不符期间新录的")
        val accepted = lab.proxy.accepted.get()
        repeat(2) { lab.phone.engine.runOnce() }
        assertEquals("需要用户处理的期间，手机一个连接也不会再发", accepted, lab.proxy.accepted.get())
        assertEquals("新任务没有被送给陌生网关", 0, stranger.count("SELECT COUNT(*) FROM revisions WHERE record_id = ?", later.recordId))

        // 用户在新网关上生成配对码、重新配对：任务回到排队，送到新网关
        lab.phone.pairWith(lab.payload(of = stranger))
        lab.phone.engine.resolveAfterRepair()
        lab.phone.engine.runOnce()

        assertEquals(SyncState.STORED, lab.phone.jobs.get(key)!!.state)
        assertEquals(SyncState.STORED, lab.phone.jobs.get(later)!!.state)
        assertEquals(1, stranger.count("SELECT COUNT(*) FROM revisions WHERE record_id = ?", key.recordId))
        assertEquals(1, stranger.count("SELECT COUNT(*) FROM revisions WHERE record_id = ?", later.recordId))
        assertEquals(0, lab.gateway.count("SELECT COUNT(*) FROM revisions"))
        Unit
    }

    @Test
    fun `网关吊销了这部手机：令牌被拒，需要重新配对，不再重试；重新配对后送达`() = runBlocking {
        val lab = Lab()
        val paired = lab.pair()
        val key = lab.phone.publish(1, "吊销之后写的")
        lab.gateway.cli("revoke-device", "--data-dir", lab.gateway.dataDir.path, "--device-id", paired.deviceId).orFail("revoke-device")
        assertEquals("网关上这台设备已是吊销状态", 1, lab.gateway.count("SELECT COUNT(*) FROM devices WHERE device_id = ? AND revoked_at IS NOT NULL", paired.deviceId))

        lab.phone.engine.runOnce()

        val status = lab.phone.status()
        assertEquals(SyncHeadline.NEEDS_USER, status.headline)
        assertTrue("原因要是令牌被拒：${status.causes}", UserCause.AUTH_REJECTED in status.causes)
        assertEquals(0, lab.gateway.count("SELECT COUNT(*) FROM revisions"))
        // 令牌被拒是对整个连接成立的事实：用户还没处理时又录的一条，也不能再带着这个令牌去敲网关
        val later = lab.phone.publish(2, "令牌被拒期间新录的")
        val before = lab.phone.requests().size
        repeat(3) { lab.phone.engine.runOnce() }
        assertEquals("令牌被拒之后不会再带着它发请求", before, lab.phone.requests().size)
        assertEquals(0, lab.gateway.count("SELECT COUNT(*) FROM revisions WHERE record_id = ?", later.recordId))

        lab.phone.pairWith(lab.payload())
        lab.phone.engine.resolveAfterRepair()
        lab.phone.engine.runOnce()

        assertEquals(SyncState.STORED, lab.phone.jobs.get(key)!!.state)
        assertEquals(SyncState.STORED, lab.phone.jobs.get(later)!!.state)
        assertEquals(1, lab.gateway.count("SELECT COUNT(*) FROM revisions WHERE record_id = ?", key.recordId))
        assertEquals(1, lab.gateway.count("SELECT COUNT(*) FROM revisions WHERE record_id = ?", later.recordId))
        assertEquals("新配对登记了一台新设备", 2, lab.gateway.count("SELECT COUNT(*) FROM devices"))
        assertEquals("旧设备仍是吊销状态，可用的只有新配对的那台", 1, lab.gateway.count("SELECT COUNT(*) FROM devices WHERE revoked_at IS NULL"))
        Unit
    }

    private companion object {
        const val APP_VERSION = "0.1.0-realgateway"
    }
}
