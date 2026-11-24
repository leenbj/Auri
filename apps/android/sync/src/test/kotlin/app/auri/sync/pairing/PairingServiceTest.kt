package app.auri.sync.pairing

import app.auri.capture.contract.ErrorCode
import app.auri.capture.coordinator.Clock
import app.auri.capture.testing.RepoContracts
import app.auri.sync.SyncLog
import app.auri.sync.gateway.DeviceInfo
import app.auri.sync.http.HttpReply
import app.auri.sync.http.HttpRequest
import app.auri.sync.http.TransportFailure
import app.auri.sync.policy.DataFlowPolicy
import app.auri.sync.policy.DataFlowState
import app.auri.sync.policy.current
import app.auri.sync.policy.setGrantFor
import app.auri.sync.testing.FakeDiscovery
import app.auri.sync.testing.FakeLan
import app.auri.sync.testing.InMemoryDataFlowPolicy
import app.auri.sync.testing.ScriptedExchange
import app.auri.sync.testing.SyncSamples
import app.auri.sync.testing.TlsFixtures
import java.io.File
import java.io.IOException
import java.time.Instant
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 扫码配对：二维码里的指纹就是这次连接的唯一信任根。配对码是一次性的秘密，只发给指纹核对通过的网关，
 * 而且一旦“可能已经送到”就绝不自动重发。
 */
class PairingServiceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val now = Instant.parse("2026-11-15T02:05:00Z")
    private val clock = object : Clock {
        override fun monotonicNs() = 0L
        override fun now() = now
    }
    private val device = DeviceInfo("0.1.0-test", "Android 15 / test", "测试手机")
    private val nas = SyncSamples.NAS_ADDRESS
    private val lan = SyncSamples.LAN_ADDRESS
    private val moved = GatewayAddress("192.168.1.77", 8443)
    private val lanSim = FakeLan()
    private val discovery = FakeDiscovery()
    private val logLines = mutableListOf<String>()
    private val box = AesGcmSecretBox({ SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }, "auri.pairing.v1")
    private val file by lazy { File(tmp.root, "pairing.bin") }
    private val store by lazy { FilePairingStore(file, box) }

    private fun service(store: PairingStore = this.store) = PairingService(store, lanSim.factory, discovery, clock, device) { logLines += it }

    private fun payload(expiresAt: String = "2026-11-15T02:10:00Z", pin: String = TlsFixtures.gatewayA.pin.hex, gatewayId: String = SyncSamples.GATEWAY_ID): PairingPayload {
        val text = RepoContracts.json("fixtures/messages/valid/pairing.payload.lan-gateway.json").jsonObject.getValue("instance").toString()
            .replace("2026-11-15T02:10:00Z", expiresAt)
            .replace("eedd1d30512d98a7084435f20abdccb697f22dba6ed119f3bd6ef60d1cfef8b1", pin)
            .replace(SyncSamples.GATEWAY_ID, gatewayId)
        return (PairingPayload.parse(text) as PayloadParse.Valid).payload
    }

    private fun redeemOk(gatewayId: String = SyncSamples.GATEWAY_ID) =
        ScriptedExchange.json(200, RepoContracts.json("fixtures/messages/valid/pairing.redeem_response.paired.json").jsonObject.getValue("instance").toString().replace(SyncSamples.GATEWAY_ID, gatewayId))

    private fun gatewayAt(address: GatewayAddress, identity: TlsFixtures.Identity = TlsFixtures.gatewayA, handler: suspend (HttpRequest) -> HttpReply = { redeemOk() }) = lanSim.host(address, identity, handler)

    private fun pair(payload: PairingPayload = payload(), service: PairingService = service()) = runBlocking { service.pair(payload) }

    private fun errorBody(code: String) = """{"schema_version":"1.2","code":"$code","message":"x","retryable":false,"required_action":null,"operation_id":null}"""

    @Test
    fun `扫码配对成功：配对码只发给核对过公钥的网关，令牌存进安全存储，日志里没有任何机密`() {
        gatewayAt(nas)

        val outcome = pair()

        val paired = (outcome as PairingOutcome.Paired).gateway
        val (address, request) = lanSim.delivered.single()
        assertEquals(nas, address)
        assertEquals("POST", request.method)
        assertEquals("/v1/pairing/redeem", request.path)
        assertTrue(Json.parseToJsonElement(request.body!!.toString(Charsets.UTF_8)).jsonObject.getValue("pairing_code").jsonPrimitive.content == SyncSamples.PAIRING_CODE)
        assertEquals("这次连接钉的是二维码里的指纹", listOf(TlsFixtures.gatewayA.pin), lanSim.pinsUsed.toList())

        val stored = (runBlocking { store.load() } as PairingState.Paired).gateway
        assertEquals(paired, stored)
        assertEquals(SyncSamples.GATEWAY_ID, stored.gatewayId)
        assertEquals(SyncSamples.DEVICE_ID, stored.deviceId)
        assertEquals("Bearer ${SyncSamples.TOKEN_TEXT}", stored.token.bearer())
        assertEquals(TlsFixtures.gatewayA.pin, stored.pin)
        assertEquals(listOf(nas, lan), stored.addresses)
        assertEquals(nas, stored.lastGoodAddress)
        assertNull(stored.needsUser)
        assertTrue("磁盘上只有密文", !String(file.readBytes(), Charsets.ISO_8859_1).contains(SyncSamples.TOKEN_TEXT))

        assertTrue("应当有日志可查", logLines.isNotEmpty())
        for (secret in listOf(SyncSamples.TOKEN_TEXT, SyncSamples.PAIRING_CODE, TlsFixtures.gatewayA.pin.hex, "192.168", "study-pc", SyncSamples.DEVICE_ID)) {
            logLines.forEach { assertFalse("日志里不能出现 $secret：$it", it.contains(secret)) }
        }
    }

    @Test
    fun `二维码过期：不联网、不改动配对记录`() {
        gatewayAt(nas)

        assertEquals(PairingOutcome.Expired, pair(payload(expiresAt = "2026-11-15T02:05:00Z")))

        assertTrue(lanSim.attempts.isEmpty())
        assertEquals(PairingState.NotPaired, runBlocking { store.load() })
    }

    @Test
    fun `第一个地址不通就试下一个，记下实际连上的那个`() {
        gatewayAt(lan)

        val gateway = (pair() as PairingOutcome.Paired).gateway

        assertEquals(listOf(nas, lan), lanSim.attempts.toList())
        assertEquals(lan, gateway.lastGoodAddress)
    }

    @Test
    fun `二维码里的地址都不通才用 mDNS（带着二维码里的网关标识），发现的地址也记进配对记录`() {
        gatewayAt(moved)
        discovery.result = { listOf(moved) }

        val gateway = (pair() as PairingOutcome.Paired).gateway

        assertEquals(listOf(SyncSamples.GATEWAY_ID), discovery.asked.toList())
        assertEquals(listOf(nas, lan, moved), lanSim.attempts.toList())
        assertEquals(moved, gateway.lastGoodAddress)
        assertEquals(listOf(nas, lan, moved), gateway.addresses)
    }

    @Test
    fun `第一个地址上是冒充者（公钥不符）：配对码不发给它，继续试下一个，真网关配对成功`() {
        gatewayAt(nas, TlsFixtures.gatewayB)
        gatewayAt(lan)

        val gateway = (pair() as PairingOutcome.Paired).gateway

        assertEquals(lan, gateway.lastGoodAddress)
        assertEquals("配对码只送到了真网关", listOf(lan), lanSim.delivered.map { it.first })
    }

    @Test
    fun `只有冒充者：公钥不符，配对码一个字节都没有发出去，配对记录不变`() {
        gatewayAt(nas, TlsFixtures.gatewayB)
        gatewayAt(lan, TlsFixtures.gatewayB)
        gatewayAt(moved, TlsFixtures.gatewayB)
        discovery.result = { listOf(moved) }

        assertEquals(PairingOutcome.KeyMismatch, pair())

        assertTrue(lanSim.delivered.isEmpty())
        assertEquals(PairingState.NotPaired, runBlocking { store.load() })
    }

    @Test
    fun `什么地址都找不到：没找到，不是公钥不符`() {
        assertEquals(PairingOutcome.NotFound, pair())
        assertEquals(PairingState.NotPaired, runBlocking { store.load() })
    }

    @Test
    fun `请求可能已经送到却没拿到回答：配对码可能已被消耗，不再试别的地址，也不用 mDNS`() {
        gatewayAt(nas) { throw TransportFailure.Interrupted() }
        gatewayAt(lan)
        discovery.result = { listOf(moved) }

        assertEquals(PairingOutcome.Uncertain, pair())

        assertEquals(listOf(nas), lanSim.attempts.toList())
        assertTrue(discovery.asked.isEmpty())
        assertEquals(PairingState.NotPaired, runBlocking { store.load() })
    }

    @Test
    fun `网关回了读不懂的 2xx：同样按结果不确定处理，不重发`() {
        gatewayAt(nas) { ScriptedExchange.json(200, """{"gateway_id":"x"}""") }
        gatewayAt(lan)

        assertEquals(PairingOutcome.Uncertain, pair())
        assertEquals(listOf(nas), lanSim.attempts.toList())
    }

    @Test
    fun `网关明确拒绝：配对码无效是 CodeRejected，别的错误带着状态与错误码；都不再试别的地址`() {
        gatewayAt(nas) { ScriptedExchange.json(422, errorBody("PAIRING_CODE_INVALID")) }
        gatewayAt(lan)
        assertEquals(PairingOutcome.CodeRejected, pair())
        assertEquals(listOf(nas), lanSim.attempts.toList())

        lanSim.attempts.clear()
        gatewayAt(nas) { ScriptedExchange.json(500, errorBody("GATEWAY_UNREACHABLE")) }
        assertEquals(PairingOutcome.GatewayError(500, ErrorCode.GATEWAY_UNREACHABLE), pair())
        assertEquals(listOf(nas), lanSim.attempts.toList())
        assertEquals(PairingState.NotPaired, runBlocking { store.load() })
    }

    @Test
    fun `网关回答的 gateway_id 不是二维码里的：不存`() {
        gatewayAt(nas) { ScriptedExchange.json(200, RepoContracts.json("fixtures/messages/valid/pairing.redeem_response.paired.json").jsonObject.getValue("instance").toString().replace(SyncSamples.GATEWAY_ID, "00000000-0000-4000-8000-000000000009")) }

        assertEquals(PairingOutcome.WrongGateway, pair())
        assertEquals(PairingState.NotPaired, runBlocking { store.load() })
    }

    @Test
    fun `令牌存不下来要如实报告，不能假装配对成功`() {
        gatewayAt(nas)
        val failing = object : PairingStore {
            override suspend fun load() = PairingState.NotPaired
            override suspend fun save(gateway: PairedGateway) = throw IOException("disk full")
            override suspend fun update(transform: (PairedGateway) -> PairedGateway?) = false
            override suspend fun <T : Any> whileCurrent(gateway: PairedGateway, block: suspend () -> T): T? = null
            override suspend fun clear() = Unit
        }

        assertEquals(PairingOutcome.NotSaved, pair(service = service(failing)))
    }

    @Test
    fun `重新配对取代旧记录，并清掉“需要用户处理”的标记`() {
        runBlocking { store.save(SyncSamples.pairedGateway(pin = TlsFixtures.gatewayB.pin, needsUser = NeedsUser.KEY_MISMATCH)) }
        gatewayAt(nas)

        pair()

        val stored = (runBlocking { store.load() } as PairingState.Paired).gateway
        assertEquals(TlsFixtures.gatewayA.pin, stored.pin)
        assertNull(stored.needsUser)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `配对成功之后即使调用方被取消，令牌也要存下来（网关已经签发，丢了就只能重新配对）`() = runTest {
        gatewayAt(nas)
        val saved = mutableListOf<PairedGateway>()
        val slowStore = object : PairingStore {
            override suspend fun load() = PairingState.NotPaired
            override suspend fun save(gateway: PairedGateway) {
                delay(100)
                saved += gateway
            }
            override suspend fun update(transform: (PairedGateway) -> PairedGateway?) = false
            override suspend fun <T : Any> whileCurrent(gateway: PairedGateway, block: suspend () -> T): T? = null
            override suspend fun clear() = Unit
        }
        val job = async(start = CoroutineStart.UNDISPATCHED) { service(slowStore).pair(payload()) }
        runCurrent()
        advanceTimeBy(50)
        job.cancel()
        advanceTimeBy(200)
        runCurrent()

        assertEquals("取消发生在保存的中途，保存仍应完成", 1, saved.size)
    }

    @Test
    fun `日志收集器不是摆设：配对流程确实往里写了事件`() {
        gatewayAt(nas)
        val lines = mutableListOf<String>()
        runBlocking { PairingService(store, lanSim.factory, discovery, clock, device, log = SyncLog { lines += it }).pair(payload()) }
        assertTrue(lines.any { it.startsWith("pairing.") })
    }

    // ---- 复评 R3：文字授权是给某个网关的，换配对时在换记录之前作废不是给新网关的授权 ----

    /** 记下“记录什么时候被换掉”，好和授权清理的先后比一比。 */
    private class OrderedStore(private val inner: PairingStore, private val events: MutableList<String>) : PairingStore by inner {
        override suspend fun save(gateway: PairedGateway) {
            events += "save"
            inner.save(gateway)
        }
    }

    @Test
    fun `配对成功：先让授权只留给新网关，再换配对记录（反过来的话两步之间旧授权还挂在新网关上）`() {
        gatewayAt(nas)
        val events = mutableListOf<String>()
        val handedToCleanup = mutableListOf<GatewayIdentity>()
        val service = PairingService(
            OrderedStore(store, events), lanSim.factory, discovery, clock, device,
            beforeSaving = { handedToCleanup += it; events += "keep-only ${it.gatewayId}" },
            newEpoch = { "epoch-1" },
        )

        val outcome = pair(service = service)

        val paired = (outcome as PairingOutcome.Paired).gateway
        assertEquals(listOf("keep-only ${SyncSamples.GATEWAY_ID}", "save"), events)
        val expected = GatewayIdentity(SyncSamples.GATEWAY_ID, TlsFixtures.gatewayA.pin.hex, "epoch-1")
        assertEquals("新网关的身份（编号 + 公钥指纹 + 这一段配对的世代）", expected, paired.identity)
        assertEquals("交给清理的与存下来的是同一个身份：清理留下的就是新这一段的同意", listOf(expected), handedToCleanup)
        assertEquals("存下来的记录带着这一段的世代", "epoch-1", (runBlocking { store.load() } as PairingState.Paired).gateway.pairingEpoch)
    }

    @Test
    fun `授权清理失败不拦配对：令牌只出现这一次，必须存下来；失败记一行不含机密的日志`() {
        gatewayAt(nas)
        val service = PairingService(store, lanSim.factory, discovery, clock, device, beforeSaving = { throw IllegalStateException("disk I/O error ${SyncSamples.TOKEN_TEXT}") }, log = SyncLog { logLines += it })

        val outcome = pair(service = service)

        assertTrue(outcome is PairingOutcome.Paired)
        assertTrue("记录照常换成了新的", runBlocking { store.load() } is PairingState.Paired)
        assertTrue("清理失败要留痕：${logLines}", logLines.contains("pairing.consent-clear-failed IllegalStateException"))
        logLines.forEach { assertFalse("日志里不能出现异常消息里的内容：$it", it.contains(SyncSamples.TOKEN_TEXT) || it.contains("disk I/O")) }
    }

    @Test
    fun `没配成（配对码被拒、二维码过期、找不到网关）：授权一点也不动`() {
        var cleared = 0
        val service = PairingService(store, lanSim.factory, discovery, clock, device, beforeSaving = { cleared += 1 })

        assertEquals(PairingOutcome.Expired, pair(payload(expiresAt = "2026-11-15T02:05:00Z"), service))
        assertEquals(PairingOutcome.NotFound, pair(service = service))
        gatewayAt(nas) { ScriptedExchange.json(422, errorBody("PAIRING_CODE_INVALID")) }
        assertEquals(PairingOutcome.CodeRejected, pair(service = service))

        assertEquals("配对记录没换，授权也不能动", 0, cleared)
    }

    // ---- 复评三 S5：授权清理没跑成时，旧同意也不能在“配回原来的网关”时复活（失败要关着：不放行）----

    /** 授权设置里“主动清理”总是失败（磁盘写不进去）：其余行为同内存版。 */
    private class CleanupFails(private val inner: InMemoryDataFlowPolicy) : DataFlowPolicy by inner {
        override suspend fun forgetGrantExcept(gateway: GatewayIdentity?): Unit = throw IOException("disk full")
    }

    private val otherGatewayId = "0f0f0f0f-1bfb-53b3-85a3-4d36b680fe6e"

    private fun pairA(service: PairingService): PairedGateway {
        gatewayAt(nas)
        return (pair(service = service) as PairingOutcome.Paired).gateway
    }

    private fun pairB(service: PairingService): PairedGateway {
        gatewayAt(nas, TlsFixtures.gatewayB) { redeemOk(otherGatewayId) }
        return (pair(payload(pin = TlsFixtures.gatewayB.pin.hex, gatewayId = otherGatewayId), service) as PairingOutcome.Paired).gateway
    }

    private fun granted(policy: DataFlowPolicy, gateway: PairedGateway) = runBlocking { policy.current(gateway.identity) }.gatewayTextGranted

    @Test
    fun `对 A 同意过，配到 B、再配回 A：两次授权清理都失败，A 原来的同意也不复活，要用户重新同意`() {
        val policy = InMemoryDataFlowPolicy(DataFlowState.Unset, gateway = null)
        val service = PairingService(store, lanSim.factory, discovery, clock, device, beforeSaving = { CleanupFails(policy).forgetGrantExcept(it) })
        val first = pairA(service)
        runBlocking { policy.setGrantFor(PairingState.Paired(first), granted = true) }
        assertTrue("前提：对 A 同意过", granted(policy, first))

        pairB(service)
        val back = pairA(service)

        assertEquals("前提：清理确实没跑成，A 的旧授权记录还在", first.identity, runBlocking { policy.stored().grantedTo })
        assertFalse("再配回 A 是新的一段配对：旧同意不算数", granted(policy, back))
    }

    @Test
    fun `同一台网关重新配对，中间没换过网关：同意沿用，不用重新同意`() {
        val policy = InMemoryDataFlowPolicy(DataFlowState.Unset, gateway = null)
        val service = PairingService(store, lanSim.factory, discovery, clock, device, beforeSaving = { policy.forgetGrantExcept(it) })
        val first = pairA(service)
        runBlocking { policy.setGrantFor(PairingState.Paired(first), granted = true) }

        val again = pairA(service)

        assertTrue("同一台网关重新配对（令牌换了而已）：同意还在", granted(policy, again))
    }

    @Test
    fun `取消配对后重新配对同一台网关：是新的一段配对，旧同意不复活（清理失败也一样）`() {
        val policy = InMemoryDataFlowPolicy(DataFlowState.Unset, gateway = null)
        val service = PairingService(store, lanSim.factory, discovery, clock, device, beforeSaving = { CleanupFails(policy).forgetGrantExcept(it) })
        val first = pairA(service)
        runBlocking { policy.setGrantFor(PairingState.Paired(first), granted = true) }
        runBlocking { store.clear() }

        val again = pairA(service)

        assertFalse("取消配对就是收回对这台网关的信任：重新配上之后要重新同意", granted(policy, again))
    }
}
