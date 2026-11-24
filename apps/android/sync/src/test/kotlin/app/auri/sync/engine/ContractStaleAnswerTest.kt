@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.sync.engine

import app.auri.sync.gateway.ContractAnswer
import app.auri.sync.gateway.ContractGuard
import app.auri.sync.gateway.GatewayClient
import app.auri.sync.http.HttpReply
import app.auri.sync.http.TransportFailure
import app.auri.sync.machine.SyncState
import app.auri.sync.pairing.NeedsUser
import app.auri.sync.testing.FakeGateway
import app.auri.sync.testing.ScriptedExchange
import app.auri.sync.testing.SyncRig
import app.auri.sync.testing.SyncSamples
import app.auri.sync.testing.TlsFixtures
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 第四轮 F5：网络变了（[ContractGuard.invalidate]）的时候，已经在路上的版本询问，它的答复属于**变之前的网关**，不属于现在的。
 *
 * 第三轮 S6 做到了“这份答复不被记住”，却仍把它原样交给提问的人：提问的人拿着变之前的“兼容”去发数据请求，
 * 可网关可能已经被换成没升级的旧版本（或者根本是另一台）——旧网关把带着 1.2 的请求当成不合规，整条文字被永久拒绝。
 * 现在：询问期间世代变了，那份答复对谁都不算数（连提问的人也不给），重新问；一直在变就回“没问成”，什么数据请求也不发。
 * 世代没变，一次询问就够，照常记住。
 *
 * 交错是确定的，不靠睡眠：脚本化的交换让第一次询问停在网关回答之前，放行时网关才被降级。
 */
class ContractStaleAnswerTest {
    private val oldGateway = listOf("1.1")
    private val currentGateway = listOf("1.2")

    /** 第一次询问停在网关回答之前（[held] 记它已经发出）；放行后：用此刻的版本生成答复，之后网关改说 [afterwards]。 */
    private class FirstQuestionHeld(val server: FakeGateway, val afterwards: List<String>) {
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val exchange = ScriptedExchange { _, request ->
            val first = !held.isCompleted
            if (first) {
                held.complete(Unit)
                release.await()
            }
            server.handle(request).also { if (first) server.supportedVersions = afterwards }
        }
    }

    @Test
    fun `在路上的询问遇到作废：变之前的兼容答复不交给提问的人，重新问，拿到的是现在网关的答复`() = runTest {
        val server = FakeGateway(now = { Instant.EPOCH })
        val script = FirstQuestionHeld(server, afterwards = oldGateway)
        val guard = ContractGuard()
        val client = GatewayClient(script.exchange)
        val inFlight = async { guard.check(SyncSamples.pairedGateway(), SyncSamples.LAN_ADDRESS, client) }
        runCurrent()
        assertEquals("前提：第一次询问已经发出，网关此刻说 1.2", currentGateway, server.supportedVersions)

        guard.invalidate()
        script.release.complete(Unit)

        assertEquals("变之前的“兼容”不能交出去：网关已经被降级，提问的人要拿到重新问出来的“太旧”", ContractAnswer.TOO_OLD, inFlight.await())
        assertEquals("重新问了一次", 2, server.capabilityCalls.get())
    }

    @Test
    fun `任何答复都一样：变之前的太旧也不交给提问的人，重新问出来网关已经升级了`() = runTest {
        val server = FakeGateway(now = { Instant.EPOCH }).also { it.supportedVersions = oldGateway }
        val script = FirstQuestionHeld(server, afterwards = currentGateway)
        val guard = ContractGuard()
        val client = GatewayClient(script.exchange)
        val inFlight = async { guard.check(SyncSamples.pairedGateway(), SyncSamples.LAN_ADDRESS, client) }
        runCurrent()

        guard.invalidate()
        script.release.complete(Unit)

        assertEquals("变之前的“太旧”也不算数：网关已经升级，重新问出来是兼容", ContractAnswer.COMPATIBLE, inFlight.await())
        assertEquals(2, server.capabilityCalls.get())
    }

    @Test
    fun `拒绝类与没问成的答复同样不算数：令牌被拒、公钥对不上、断线、读不懂，作废之后重新问，拿到现在网关的答复`() = runTest {
        val rejection = """{"schema_version":"1.2","code":"GATEWAY_AUTH_INVALID","message":"x","retryable":false,"required_action":null,"operation_id":null}"""
        val staleFirstAnswers = mapOf<String, suspend () -> HttpReply>(
            "令牌被拒" to { ScriptedExchange.json(401, rejection) },
            "公钥对不上" to { throw TransportFailure.KeyMismatch() },
            "连不上" to { throw TransportFailure.Unreachable() },
            "读不懂" to { ScriptedExchange.json(200, "not json at all") },
        )
        for ((kind, firstAnswer) in staleFirstAnswers) {
            val server = FakeGateway(now = { Instant.EPOCH })
            val guard = ContractGuard()
            var questions = 0
            val client = GatewayClient(
                ScriptedExchange { _, request ->
                    questions += 1
                    if (questions == 1) {
                        guard.invalidate() // 这次询问在路上时网络变了，下面这份“拒绝”或“没问成”是变之前的网络说的
                        firstAnswer()
                    } else {
                        server.handle(request)
                    }
                },
            )

            val answer = guard.check(SyncSamples.pairedGateway(), SyncSamples.LAN_ADDRESS, client)

            assertEquals("$kind：变之前的答复不交给提问的人（要用户重新配对之类的结论只能出自现在的网关）", ContractAnswer.COMPATIBLE, answer)
            assertEquals("$kind：重新问了一次", 2, questions)
        }
    }

    @Test
    fun `作废在每一次询问期间都发生：问到上限就回没问成，不拿任何一份旧答复冒充，也什么都不记`() = runTest {
        val server = FakeGateway(now = { Instant.EPOCH })
        val guard = ContractGuard()
        val client = GatewayClient(
            ScriptedExchange { _, request ->
                guard.invalidate() // 网络在这次询问期间又变了
                server.handle(request)
            },
        )

        val answer = guard.check(SyncSamples.pairedGateway(), SyncSamples.LAN_ADDRESS, client)

        assertEquals("世代一直在变：什么也不发，等下一轮", ContractAnswer.UNCONFIRMED, answer)
        assertEquals("问了有限次，不会无休止地问下去", 3, server.capabilityCalls.get())
    }

    @Test
    fun `世代没变：一次询问就够，答复照常交出并记住，第二次不再问`() = runTest {
        val server = FakeGateway(now = { Instant.EPOCH })
        val guard = ContractGuard()
        val client = GatewayClient(ScriptedExchange { _, request -> server.handle(request) })
        val gateway = SyncSamples.pairedGateway()

        assertEquals(ContractAnswer.COMPATIBLE, guard.check(gateway, SyncSamples.LAN_ADDRESS, client))
        assertEquals(ContractAnswer.COMPATIBLE, guard.check(gateway, SyncSamples.LAN_ADDRESS, client))

        assertEquals(1, server.capabilityCalls.get())
    }

    @Test
    fun `引擎里网络变了的当口版本询问还在路上：变之前的兼容不放行数据请求，重新问出网关已降级，一个 PUT 都不发`() = runTest {
        val rig = SyncRig(this)
        val key = rig.publish(SyncSamples.RECORD_ID)
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        rig.lan.host(SyncSamples.LAN_ADDRESS, TlsFixtures.gatewayA) { request ->
            val firstQuestion = request.path == "/v1/capabilities" && !held.isCompleted
            if (firstQuestion) {
                held.complete(Unit)
                release.await()
            }
            rig.server.handle(request).also { if (firstQuestion) rig.server.supportedVersions = oldGateway }
        }
        val run = async { rig.engine.runOnce() }
        runCurrent()
        assertEquals("前提：版本询问在路上、还没有数据请求", 0, rig.server.putCount)

        rig.engine.networkChanged() // 换了网络：兼容的记忆作废，在路上的询问属于变之前的网关
        release.complete(Unit)
        run.await()

        assertEquals("变之前的兼容不能放行数据请求：没有一个 PUT 发给降级后的网关", 0, rig.server.putCount)
        assertEquals(SyncState.WAITING_GATEWAY, rig.job(key).state)
        assertNull("不是永久拒绝", rig.job(key).blockedReason)
        assertEquals("重新问出网关太旧：要用户更新网关", NeedsUser.GATEWAY_TOO_OLD, rig.pairing.gateway!!.needsUser)
    }
}
