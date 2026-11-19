@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.sync.engine

import app.auri.capture.machine.Outcome
import app.auri.sync.discovery.GatewayLocator
import app.auri.sync.http.TransportFailure
import app.auri.sync.machine.SyncEvent
import app.auri.sync.machine.SyncState
import app.auri.sync.pairing.DeviceToken
import app.auri.sync.pairing.GatewayAddress
import app.auri.sync.pairing.NeedsUser
import app.auri.sync.pairing.PairingState
import app.auri.sync.testing.FakeGateway.Fault
import app.auri.sync.testing.SyncRig
import app.auri.sync.testing.SyncSamples
import app.auri.sync.testing.TlsFixtures
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 同步引擎的因果测试：每个测试讲一件“为什么这么做”的事（Ticket 21 第 2、3、6、7 项）。
 * 引擎是真的，网关、局域网、本地库、时钟都是假的（见 SyncRig）；合同场景回放在 SyncScenarioReplayTest。
 */
class SyncEngineTest {
    private val retryFailure = Fault.Reject(503, "GATEWAY_UNREACHABLE", retryable = true)
    private val thirdRecord = "c6c9e2af-4989-5bf8-b45a-8fc499255c6b"
    private val fourthRecord = "d7dafbb0-5a9a-5c09-856b-90d5aa366d7c"
    private val rePaired = Instant.parse("2026-11-18T09:00:00Z")

    private fun runRig(gatewayOnLan: Boolean = true, body: suspend TestScope.(SyncRig) -> Unit) =
        runTest { body(SyncRig(this, gatewayOnLan = gatewayOnLan)) }

    private suspend fun SyncRig.passes(count: Int, advanceMs: Long = 0) {
        repeat(count) {
            engine.runOnce()
            if (advanceMs > 0) scope.advanceTimeBy(advanceMs)
        }
    }

    private suspend fun SyncRig.advanceToRetry(key: JobKey) {
        scope.advanceTimeBy(Duration.between(clock.now(), job(key).retryAt!!).toMillis())
    }

    // ---- 正常入库 ----

    @Test
    fun `待发送的任务被发出去并入库：请求发到约定路径、带设备令牌、正文原样，指纹用配对时记下的那一个`() = runRig { rig ->
        val key = rig.publish(text = "明天检查小说记忆模块")

        rig.engine.runOnce()

        assertEquals(SyncState.STORED, rig.job(key).state)
        val (address, request) = rig.lan.delivered.single { it.second.method == "PUT" }
        assertEquals(SyncSamples.LAN_ADDRESS, address)
        assertEquals("/v1/records/${key.recordId}/revisions/1", request.path)
        assertEquals("Bearer ${SyncSamples.TOKEN_TEXT}", request.headers["Authorization"])
        assertTrue(request.body!!.contentEquals(rig.submissions.getValue(key).body))
        assertTrue("只能用配对时记下的指纹", rig.lan.pinsUsed.all { it == TlsFixtures.gatewayA.pin })
        assertEquals(listOf(SyncEvent.SEND_STARTED, SyncEvent.STORED_CONFIRMED), rig.decisionsFor(key).map { it.event })
    }

    @Test
    fun `重复提交的回答（duplicate 为 true）也是入库：网关上已有同样的内容，也算送达`() = runRig { rig ->
        val key = rig.publish()
        rig.server.stored[key] = rig.submissions.getValue(key).textSha256

        rig.engine.runOnce()

        assertEquals(SyncState.STORED, rig.job(key).state)
        assertEquals(1, rig.server.putCount)
        assertEquals(1, rig.server.stored.size)
    }

    @Test
    fun `多条任务按创建先后依次发送，已入库的不会再发`() = runRig { rig ->
        val first = rig.publish(SyncSamples.RECORD_ID, 1)
        rig.scope.advanceTimeBy(1_000)
        val second = rig.publish(SyncSamples.OTHER_RECORD_ID, 1)

        rig.engine.runOnce()
        rig.engine.runOnce()

        assertEquals(listOf("PUT ${first.recordId}#1", "PUT ${second.recordId}#1"), rig.server.calls.toList())
    }

    // ---- 第 7 项：结果不确定时先问网关 ----

    @Test
    fun `请求入了库但响应丢了：下一次先问网关，看到已入库就确认，绝不盲目重发`() = runRig { rig ->
        val key = rig.publish()
        rig.server.faults += Fault.StoreThenLoseResponse

        rig.engine.runOnce()
        val afterLoss = rig.job(key)
        assertEquals(SyncState.RETRY_SCHEDULED, afterLoss.state)
        assertTrue("请求可能已送到：必须记成结果不确定", afterLoss.uncertain)

        rig.advanceToRetry(key)
        rig.engine.runOnce()

        assertEquals(SyncState.STORED, rig.job(key).state)
        assertFalse(rig.job(key).uncertain)
        assertEquals("先问后发：第二次只查询，不再提交", listOf("PUT ${key.recordId}#1", "GET status ${key.recordId}"), rig.server.calls.toList())
        assertEquals("网关上只有一份", 1, rig.server.stored.size)
    }

    @Test
    fun `请求在路上丢了、网关什么也没收到：问过之后确认没有，才重新提交`() = runRig { rig ->
        val key = rig.publish()
        rig.server.faults += Fault.LoseRequest

        rig.engine.runOnce()
        rig.advanceToRetry(key)
        rig.engine.runOnce()

        assertEquals(SyncState.STORED, rig.job(key).state)
        assertEquals(listOf("PUT ${key.recordId}#1", "GET status ${key.recordId}", "PUT ${key.recordId}#1"), rig.server.calls.toList())
    }

    @Test
    fun `问网关结果时网关自己出错或没回答：不重发，保持不确定，稍后再问`() = runRig { rig ->
        val key = rig.publish()
        rig.server.faults += Fault.StoreThenLoseResponse
        rig.engine.runOnce()

        for (broken in listOf(retryFailure, Fault.LoseRequest)) {
            rig.advanceToRetry(key)
            rig.server.faults += broken
            rig.engine.runOnce()
            assertEquals(SyncState.RETRY_SCHEDULED, rig.job(key).state)
            assertTrue(rig.job(key).uncertain)
        }
        assertEquals("问不到结果就不能重发", 1, rig.server.putCount)
        assertEquals(2, rig.server.statusCount)
    }

    @Test
    fun `没有发生过不确定的失败时，重新连上之后直接发送，不多问一次`() = runRig(gatewayOnLan = false) { rig ->
        val key = rig.publish()
        rig.engine.runOnce()
        assertEquals(SyncState.WAITING_GATEWAY, rig.job(key).state)
        assertFalse("请求根本没发出去，不能记成不确定", rig.job(key).uncertain)

        rig.lan.host(SyncSamples.LAN_ADDRESS, TlsFixtures.gatewayA) { rig.server.handle(it) }
        rig.engine.runOnce()

        assertEquals(SyncState.STORED, rig.job(key).state)
        assertEquals(listOf("PUT ${key.recordId}#1"), rig.server.calls.toList())
    }

    @Test
    fun `网关里查不到这条 Record（404）等于没入库，再提交`() = runRig { rig ->
        val key = rig.publish()
        rig.jobs.update(rig.job(key).copy(uncertain = true))

        rig.engine.runOnce()

        assertEquals(listOf("GET status ${key.recordId}", "PUT ${key.recordId}#1"), rig.server.calls.toList())
        assertEquals(SyncState.STORED, rig.job(key).state)
    }

    @Test
    fun `网关上同一版本号是另一份正文：不能确认入库，也不再提交，直接停下并报版本冲突`() = runRig { rig ->
        val key = rig.publish()
        rig.jobs.update(rig.job(key).copy(uncertain = true))
        rig.server.stored[key] = SyncSamples.sha256Hex("不一样的正文")

        rig.engine.runOnce()

        assertEquals(SyncState.BLOCKED, rig.job(key).state)
        assertEquals(BlockedReason.VERSION_CONFLICT, rig.job(key).blockedReason)
        // 查询已经说明白了（同一版本号、另一份正文）：再 PUT 一次只是白白把本机正文再发给网关一遍，得到的还是冲突
        assertEquals("不能再提交", 0, rig.server.putCount)
        assertEquals(listOf("GET status ${key.recordId}"), rig.server.calls.toList())
    }

    @Test
    fun `网关上有这条 Record 的别的版本、唯独没有这一版：等于没入库，再提交（对照：冲突与相同都不提交）`() = runRig { rig ->
        val key = rig.publish(revision = 2)
        rig.jobs.update(rig.job(key).copy(uncertain = true))
        rig.server.stored[JobKey(key.recordId, 1)] = SyncSamples.sha256Hex("第一版的正文")

        rig.engine.runOnce()

        assertEquals(SyncState.STORED, rig.job(key).state)
        assertEquals(listOf("GET status ${key.recordId}", "PUT ${key.recordId}#2"), rig.server.calls.toList())
    }

    @Test
    fun `网关说这条 Record 已被删除：哪怕那一版的哈希相同也不能确认入库，也不再提交，停在需要用户处理`() = runRig { rig ->
        val key = rig.publish()
        rig.jobs.update(rig.job(key).copy(uncertain = true))
        rig.server.stored[key] = rig.submissions.getValue(key).textSha256
        rig.server.deleted = true

        rig.engine.runOnce()

        assertEquals("已删除的 Record 上的文字不算“已送达”", SyncState.BLOCKED, rig.job(key).state)
        assertEquals(BlockedReason.RECORD_DELETED, rig.job(key).blockedReason)
        assertEquals(0, rig.server.putCount)
    }

    @Test
    fun `网关说这条 Record 已被删除、而且没有这一版：同样停下，不去提交一个复活墓碑的版本`() = runRig { rig ->
        val key = rig.publish(revision = 2)
        rig.jobs.update(rig.job(key).copy(uncertain = true))
        rig.server.stored[JobKey(key.recordId, 1)] = SyncSamples.sha256Hex("第一版的正文")
        rig.server.deleted = true

        rig.engine.runOnce()

        assertEquals(SyncState.BLOCKED, rig.job(key).state)
        assertEquals(BlockedReason.RECORD_DELETED, rig.job(key).blockedReason)
        assertEquals(0, rig.server.putCount)
    }

    @Test
    fun `网关说这条 Record 是别的设备录的：哪怕那一版的哈希相同也不能确认入库，也不再提交，停在需要用户处理`() = runRig { rig ->
        val key = rig.publish()
        rig.jobs.update(rig.job(key).copy(uncertain = true))
        rig.server.stored[key] = rig.submissions.getValue(key).textSha256
        rig.server.originDeviceId = "11111111-2222-4333-8444-555555555555"

        rig.engine.runOnce()

        assertEquals("只有录下它的那台设备能为它写 Revision（ADR-0020），别的设备那里看到相同文字也不是本机的送达", SyncState.BLOCKED, rig.job(key).state)
        assertEquals(BlockedReason.NOT_ORIGIN_DEVICE, rig.job(key).blockedReason)
        assertEquals(0, rig.server.putCount)
    }

    // ---- 第 6 项：等待网关 / 稍后重试 / 要用户处理 ----

    @Test
    fun `连不上网关：任务进入等待网关而不是失败，没有重试倒计时；网关回来后自动补传`() = runRig(gatewayOnLan = false) { rig ->
        val key = rig.publish()
        val second = rig.publish(SyncSamples.OTHER_RECORD_ID, 1)

        rig.engine.runOnce()

        for (k in listOf(key, second)) {
            assertEquals(SyncState.WAITING_GATEWAY, rig.job(k).state)
            assertNull(rig.job(k).retryAt)
        }
        assertEquals("一次连不上就停下，不对每条任务各探一遍", 1, rig.lan.attempts.count { it == SyncSamples.NAS_ADDRESS })

        rig.lan.host(SyncSamples.LAN_ADDRESS, TlsFixtures.gatewayA) { rig.server.handle(it) }
        rig.engine.runOnce()

        assertEquals(listOf(SyncState.STORED, SyncState.STORED), listOf(key, second).map { rig.job(it).state })
    }

    @Test
    fun `网关答 503：稍后重试，退避时间逐次翻倍，没到时间不会提前重试`() = runRig { rig ->
        val key = rig.publish()
        val start = rig.clock.now()
        rig.server.faults += retryFailure
        rig.engine.runOnce()
        assertEquals(start.plusSeconds(5), rig.job(key).retryAt)

        rig.scope.advanceTimeBy(4_999)
        rig.engine.runOnce()
        assertEquals("没到时间", 1, rig.server.calls.size)

        rig.scope.advanceTimeBy(1)
        val secondAttemptAt = rig.clock.now()
        rig.server.faults += retryFailure
        rig.engine.runOnce()

        assertEquals(2, rig.job(key).attempts)
        assertEquals("第二次失败后等 10 秒", secondAttemptAt.plusSeconds(10), rig.job(key).retryAt)
    }

    @Test
    fun `旧计时器响了：任务已经重新安排到更晚的时间，就不提前重试；到点之后才回到排队`() = runRig { rig ->
        val key = rig.publish()
        rig.server.faults += retryFailure
        rig.engine.runOnce()
        rig.scope.advanceTimeBy(1_000)

        rig.engine.retryDue(key)

        assertEquals(SyncState.RETRY_SCHEDULED, rig.job(key).state)
        assertTrue("没到点，不该有 RETRY_DUE", rig.decisionsFor(key).none { it.event == SyncEvent.RETRY_DUE })
        rig.advanceToRetry(key)
        rig.engine.retryDue(key)
        assertEquals(SyncState.PENDING, rig.job(key).state)
    }

    @Test
    fun `永久性拒绝不会被重试：停在需要用户处理，之后无论多少轮、过多久都不再请求网关`() = runRig { rig ->
        val cases = listOf(
            Triple(Fault.Reject(409, "RECORD_VERSION_CONFLICT", false), BlockedReason.VERSION_CONFLICT, SyncSamples.RECORD_ID),
            Triple(Fault.Reject(410, "RECORD_DELETED", false), BlockedReason.RECORD_DELETED, SyncSamples.OTHER_RECORD_ID),
            Triple(Fault.Reject(403, "RECORD_NOT_ORIGIN_DEVICE", false), BlockedReason.NOT_ORIGIN_DEVICE, thirdRecord),
            Triple(Fault.Reject(422, "REQUEST_INVALID", false), BlockedReason.REQUEST_REJECTED, fourthRecord),
        )
        val keys = cases.map { (fault, _, record) ->
            rig.publish(record, 1).also {
                rig.server.faults += fault
                rig.engine.send(it)
            }
        }

        keys.forEachIndexed { index, key ->
            assertEquals(SyncState.BLOCKED, rig.job(key).state)
            assertEquals(cases[index].second, rig.job(key).blockedReason)
        }
        val requests = rig.server.calls.size
        rig.passes(20, advanceMs = 60 * 60_000)

        assertEquals("被永久拒绝的任务不能再重试", requests, rig.server.calls.size)
    }

    @Test
    fun `令牌被撤销：整个连接停下等用户重新配对，别的任务也不再带着令牌发请求`() = runRig { rig ->
        val first = rig.publish(SyncSamples.RECORD_ID, 1)
        rig.scope.advanceTimeBy(1)
        val second = rig.publish(SyncSamples.OTHER_RECORD_ID, 1)
        rig.server.faults += Fault.Reject(401, "GATEWAY_AUTH_REVOKED", false)

        rig.engine.runOnce()

        assertEquals(SyncState.BLOCKED, rig.job(first).state)
        assertEquals(BlockedReason.AUTH_REJECTED, rig.job(first).blockedReason)
        assertEquals("第二条根本没发", SyncState.PENDING, rig.job(second).state)
        assertEquals(NeedsUser.AUTH_REJECTED, rig.pairing.gateway!!.needsUser)

        val requests = rig.lan.delivered.size
        rig.passes(5, advanceMs = 60_000)
        assertEquals("要用户处理期间不能再向网关发任何请求", requests, rig.lan.delivered.size)
    }

    @Test
    fun `重新配对之后：因连接问题停下的任务自动恢复，用新令牌继续发`() = runRig { rig ->
        val key = rig.publish()
        rig.server.faults += Fault.Reject(401, "GATEWAY_AUTH_INVALID", false)
        rig.engine.runOnce()
        assertEquals(SyncState.BLOCKED, rig.job(key).state)

        val newToken = DeviceToken.parse("fixture-device-token-not-a-real-secret-0002")!!
        rig.pairing.save(SyncSamples.pairedGateway(token = newToken, pairedAt = rePaired))
        rig.engine.resolveAfterRepair()
        assertEquals(SyncState.PENDING, rig.job(key).state)
        assertNull(rig.job(key).blockedReason)
        rig.engine.runOnce()

        assertEquals(SyncState.STORED, rig.job(key).state)
        assertEquals("Bearer fixture-device-token-not-a-real-secret-0002", rig.lan.delivered.last { it.second.method == "PUT" }.second.headers["Authorization"])
    }

    @Test
    fun `没重新配对时，resolveAfterRepair 什么也不做；它也不会解除与连接无关的永久拒绝`() = runRig { rig ->
        val auth = rig.publish(SyncSamples.RECORD_ID, 1)
        rig.server.faults += Fault.Reject(401, "GATEWAY_AUTH_REVOKED", false)
        rig.engine.runOnce()
        val conflict = rig.publish(SyncSamples.OTHER_RECORD_ID, 1)
        rig.jobs.update(rig.job(conflict).copy(state = SyncState.BLOCKED, blockedReason = BlockedReason.VERSION_CONFLICT))

        rig.engine.resolveAfterRepair()
        assertEquals("连接仍然要用户处理", SyncState.BLOCKED, rig.job(auth).state)

        rig.pairing.save(SyncSamples.pairedGateway(pairedAt = rePaired))
        rig.engine.resolveAfterRepair()

        assertEquals(SyncState.PENDING, rig.job(auth).state)
        assertEquals("版本冲突要用户另行处理，重新配对不能替他决定", SyncState.BLOCKED, rig.job(conflict).state)
    }

    @Test
    fun `用户处理单条任务：USER_RESOLVED 让它回到排队，次数与原因清零`() = runRig { rig ->
        val key = rig.publish()
        rig.server.faults += Fault.Reject(409, "RECORD_VERSION_CONFLICT", false)
        rig.engine.runOnce()
        assertEquals(SyncState.BLOCKED, rig.job(key).state)

        rig.engine.userResolved(key)

        val job = rig.job(key)
        assertEquals(SyncState.PENDING, job.state)
        assertEquals(0, job.attempts)
        assertNull(job.blockedReason)
    }

    @Test
    fun `网关换了公钥：拒绝连接、令牌一次也没发出去、任务停下，也没有任何继续的路`() = runRig { rig ->
        rig.lan.hosts.clear()
        rig.lan.host(SyncSamples.LAN_ADDRESS, TlsFixtures.gatewayB) { rig.server.handle(it) }
        val key = rig.publish()

        rig.engine.runOnce()

        assertEquals(SyncState.BLOCKED, rig.job(key).state)
        assertEquals(BlockedReason.KEY_MISMATCH, rig.job(key).blockedReason)
        assertEquals(NeedsUser.KEY_MISMATCH, rig.pairing.gateway!!.needsUser)
        assertTrue("冒充者没收到任何请求", rig.lan.delivered.isEmpty())
        assertTrue(rig.server.calls.isEmpty())

        val attempts = rig.lan.attempts.size
        rig.passes(5, advanceMs = 60 * 60_000)
        assertEquals("要用户重新配对之前，不会再去连（没有“忽略并继续”）", attempts, rig.lan.attempts.size)
        assertEquals(SyncState.BLOCKED, rig.job(key).state)

        // 连接还在“要用户处理”：之后新录的任务也不能因为是新的就再去敲一次冒充者（停下的那条任务自己不会再被挑中，拦住新任务的只有这道闸）
        rig.scope.advanceTimeBy(1)
        val later = rig.publish(SyncSamples.OTHER_RECORD_ID, 1)
        rig.passes(3, advanceMs = 60_000)
        assertEquals("新录的任务也不去连", attempts, rig.lan.attempts.size)
        assertEquals(SyncState.PENDING, rig.job(later).state)
    }

    @Test
    fun `等待网关的任务遇到换了公钥的网关：照样回到排队，发送时按状态机停下并要求重新配对，不会永远“等待”下去`() = runRig(gatewayOnLan = false) { rig ->
        val key = rig.publish()
        rig.engine.runOnce()
        assertEquals(SyncState.WAITING_GATEWAY, rig.job(key).state)

        rig.lan.host(SyncSamples.LAN_ADDRESS, TlsFixtures.gatewayB) { rig.server.handle(it) }
        rig.engine.runOnce()

        assertEquals(SyncState.BLOCKED, rig.job(key).state)
        assertEquals(NeedsUser.KEY_MISMATCH, rig.pairing.gateway!!.needsUser)
        assertTrue(rig.lan.delivered.isEmpty())
        assertEquals(
            listOf(SyncEvent.SEND_STARTED, SyncEvent.GATEWAY_UNREACHABLE, SyncEvent.GATEWAY_REACHABLE, SyncEvent.SEND_STARTED, SyncEvent.KEY_MISMATCH),
            rig.decisionsFor(key).map { it.event },
        )
    }

    @Test
    fun `PUT 发出时才发现公钥不符（探测与发送之间网关换了密钥）：同样停下，并且不当成可重试`() = runRig { rig ->
        val key = rig.publish()
        rig.server.faults += Fault.Transport(TransportFailure.KeyMismatch())

        rig.engine.runOnce()

        assertEquals(SyncState.BLOCKED, rig.job(key).state)
        assertEquals(BlockedReason.KEY_MISMATCH, rig.job(key).blockedReason)
        assertEquals(NeedsUser.KEY_MISMATCH, rig.pairing.gateway!!.needsUser)
        assertFalse("请求没有送到网关，不是“结果不确定”", rig.job(key).uncertain)
    }

    @Test
    fun `没有配对时什么都不发，任务留在队列里`() = runTest {
        val rig = SyncRig(this, gateway = null)
        val key = rig.publish()

        rig.engine.runOnce()

        assertEquals(SyncState.PENDING, rig.job(key).state)
        assertTrue(rig.lan.attempts.isEmpty())
        assertTrue(rig.decisions.isEmpty())
    }

    @Test
    fun `配对记录丢失（读不出来）等同没配对：不发请求`() = runRig { rig ->
        val key = rig.publish()
        rig.pairing.state = PairingState.Lost

        rig.engine.runOnce()

        assertEquals(SyncState.PENDING, rig.job(key).state)
        assertTrue(rig.lan.attempts.isEmpty())
    }

    // ---- 网络变化 ----

    @Test
    fun `网络断开：排队中与等待重试的任务都改为等待网关；已在等待的不变；发送中的由请求自己的结果决定`() = runRig { rig ->
        val pending = rig.publish(SyncSamples.RECORD_ID, 1)
        val retrying = rig.publish(SyncSamples.OTHER_RECORD_ID, 1)
        rig.jobs.update(rig.job(retrying).copy(state = SyncState.RETRY_SCHEDULED, retryAt = rig.clock.now().plusSeconds(30)))
        val sending = rig.publish(thirdRecord, 1)
        rig.jobs.update(rig.job(sending).copy(state = SyncState.SENDING))

        rig.engine.networkLost()

        assertEquals(SyncState.WAITING_GATEWAY, rig.job(pending).state)
        assertEquals(SyncState.WAITING_GATEWAY, rig.job(retrying).state)
        assertNull("等待网关没有重试时间", rig.job(retrying).retryAt)
        assertEquals(SyncState.SENDING, rig.job(sending).state)

        rig.engine.networkLost()
        assertEquals(Outcome.IGNORED, rig.decisionsFor(pending).last().outcome)
    }

    @Test
    fun `等待网关的任务：探测到网关就回到排队；探测不到就原地等着，不产生重试倒计时`() = runRig(gatewayOnLan = false) { rig ->
        val key = rig.publish()
        rig.engine.networkLost()
        assertEquals(SyncState.WAITING_GATEWAY, rig.job(key).state)

        rig.engine.probe()
        assertEquals(SyncState.WAITING_GATEWAY, rig.job(key).state)

        rig.lan.host(SyncSamples.LAN_ADDRESS, TlsFixtures.gatewayA) { rig.server.handle(it) }
        rig.engine.probe()
        assertEquals(SyncState.PENDING, rig.job(key).state)
    }

    // ---- 第 3 项：地址 ----

    @Test
    fun `先连配对时记下的地址，连不上才用 mDNS 找；成功的地址被记住，下次先连它`() = runRig { rig ->
        val moved = GatewayAddress("192.168.1.77", 8443)
        rig.lan.hosts.clear()
        rig.lan.host(moved, TlsFixtures.gatewayA) { rig.server.handle(it) }
        rig.discovery.result = { listOf(moved) }
        val key = rig.publish()

        rig.engine.runOnce()

        assertEquals(SyncState.STORED, rig.job(key).state)
        assertEquals("先试了配对时记下的两个地址，都不通，才问 mDNS", listOf(SyncSamples.NAS_ADDRESS, SyncSamples.LAN_ADDRESS, moved), rig.lan.attempts.distinct().take(3))
        assertEquals(1, rig.discovery.asked.size)
        assertEquals(moved, rig.pairing.gateway!!.lastGoodAddress)

        rig.lan.attempts.clear()
        rig.publish(SyncSamples.OTHER_RECORD_ID, 1)
        rig.engine.runOnce()
        assertEquals("下次先连上次成功的地址", moved, rig.lan.attempts.first())
        assertEquals("已经有地址可连，不再问 mDNS", 1, rig.discovery.asked.size)
    }

    @Test
    fun `发送途中用户重新配对了：晚到的旧观察不能覆盖新的配对记录`() = runRig { rig ->
        val key = rig.publish()
        val fresh = SyncSamples.pairedGateway(pairedAt = rePaired, lastGoodAddress = SyncSamples.NAS_ADDRESS)
        val gate = CompletableDeferred<Fault?>()
        rig.server.holdNext = gate
        val sending = rig.scope.launch { rig.engine.send(key) }
        rig.scope.runCurrent()

        rig.pairing.save(fresh)
        gate.complete(Fault.Reject(401, "GATEWAY_AUTH_REVOKED", false))
        sending.join()

        assertEquals("新记录原样保留，没有被打上旧记录的“要用户处理”", fresh, rig.pairing.gateway)
    }

    @Test
    fun `找网关的途中用户重新配对了：记最近连上的地址也不能覆盖新的配对记录`() = runRig { rig ->
        val key = rig.publish()
        val fresh = SyncSamples.pairedGateway(pairedAt = rePaired, lastGoodAddress = SyncSamples.NAS_ADDRESS)
        val inProbe = CompletableDeferred<Unit>()
        rig.lan.hosts.getValue(SyncSamples.LAN_ADDRESS).handler = { request ->
            if (request.path == "/health") inProbe.await()
            rig.server.handle(request)
        }
        val sending = rig.scope.launch { rig.engine.send(key) }
        rig.scope.runCurrent()

        rig.pairing.save(fresh)
        inProbe.complete(Unit)
        sending.join()

        assertEquals(fresh, rig.pairing.gateway)
    }

    // ---- 进程、取消、故障 ----

    @Test
    fun `进程在发送途中被杀：重启后卡在发送中的任务回到等待重试，并仍然记着结果不确定`() = runRig { rig ->
        val key = rig.publish()
        val gate = CompletableDeferred<Fault?>()
        rig.server.holdNext = gate
        val sending = rig.scope.launch { rig.engine.send(key) }
        rig.scope.runCurrent()
        assertEquals(SyncState.SENDING, rig.job(key).state)
        assertTrue("请求发出之前就已经记成不确定", rig.job(key).uncertain)

        sending.cancel() // 进程被杀：协程消失，什么收尾都没做
        sending.join()
        assertEquals("被杀的现场：任务停在发送中", SyncState.SENDING, rig.job(key).state)
        rig.server.stored[key] = rig.submissions.getValue(key).textSha256 // 被杀之前请求其实已经到了网关

        val restarted = rig.newEngine() // 新进程里的引擎：同一张任务表（真实应用里是磁盘上的 SQLite）
        restarted.recoverInterrupted()

        assertEquals(SyncState.RETRY_SCHEDULED, rig.job(key).state)
        assertTrue(rig.job(key).uncertain)
        restarted.runOnce()
        assertEquals(SyncState.STORED, rig.job(key).state)
        assertEquals("被杀之前的那一个 PUT 之后，重启只问了一次、没有再提交", listOf("PUT ${key.recordId}#1", "GET status ${key.recordId}"), rig.server.calls.toList())
    }

    @Test
    fun `正在发送的任务不会被当成“中断”误回收`() = runRig { rig ->
        val key = rig.publish()
        val gate = CompletableDeferred<Fault?>()
        rig.server.holdNext = gate
        val sending = rig.scope.launch { rig.engine.send(key) }
        rig.scope.runCurrent()

        rig.engine.recoverInterrupted()
        assertEquals(SyncState.SENDING, rig.job(key).state)

        gate.complete(null)
        sending.join()
        assertEquals(SyncState.STORED, rig.job(key).state)
    }

    @Test
    fun `同一条任务被同时发送两次：只有一个真正发出，网关只收到一次`() = runRig { rig ->
        val key = rig.publish()
        val gate = CompletableDeferred<Fault?>()
        rig.server.holdNext = gate

        val a = rig.scope.async { rig.engine.send(key) }
        val b = rig.scope.async { rig.engine.send(key) }
        rig.scope.runCurrent()
        gate.complete(null)

        assertEquals(setOf(SendResult.STORED, SendResult.SKIPPED), setOf(a.await(), b.await()))
        assertEquals(1, rig.server.putCount)
    }

    @Test
    fun `发送中被取消：取消照常向上抛，任务停在发送中且记着不确定（下次启动会处理），不会被改成别的状态`() = runRig { rig ->
        val key = rig.publish()
        val gate = CompletableDeferred<Fault?>()
        rig.server.holdNext = gate
        val sending = rig.scope.async { rig.engine.send(key) }
        rig.scope.runCurrent()

        sending.cancel()
        try {
            sending.await()
            fail("取消必须向上抛")
        } catch (_: CancellationException) {
        }

        assertEquals(SyncState.SENDING, rig.job(key).state)
        assertTrue(rig.job(key).uncertain)
        assertEquals(listOf(SyncEvent.SEND_STARTED), rig.decisionsFor(key).map { it.event })
        assertTrue("取消不是故障，不能被当成“收住的故障”处理", rig.log.lines.none { it.startsWith("send.contained") })
    }

    @Test
    fun `读本地 Record 库出错：任务不会卡在发送中，按可重试处理；库恢复后能发出`() = runRig { rig ->
        val key = rig.publish()
        rig.sourceFailure = IOException("disk")

        rig.engine.runOnce()

        assertEquals(SyncState.RETRY_SCHEDULED, rig.job(key).state)
        assertTrue(rig.lan.delivered.none { it.second.method == "PUT" })

        rig.sourceFailure = null
        rig.advanceToRetry(key)
        rig.engine.runOnce()
        assertEquals(SyncState.STORED, rig.job(key).state)
    }

    @Test
    fun `本机已经没有这条 Revision：停下等用户，不发请求，不无限重试`() = runRig { rig ->
        val key = rig.publish()
        rig.submissions.remove(key)

        rig.engine.runOnce()
        rig.passes(3, advanceMs = 60 * 60_000)

        assertEquals(SyncState.BLOCKED, rig.job(key).state)
        assertEquals(BlockedReason.LOCAL_DATA_MISSING, rig.job(key).blockedReason)
        assertTrue(rig.server.calls.isEmpty())
    }

    @Test
    fun `任务表写不进去：不会假装已经开始发送（先落盘后发送）`() = runRig { rig ->
        val key = rig.publish()
        val failing = object : SyncJobStore by rig.jobs {
            override suspend fun update(job: SyncJob) = throw IOException("disk full")
        }
        val engine = SyncEngine(failing, rig.pairing, { rig.submissions[it] }, rig.lan.factory, GatewayLocator(rig.lan.factory, rig.discovery), rig.clock)

        assertEquals(SendResult.SKIPPED, engine.send(key))

        assertTrue("落不了盘就不能发出请求", rig.server.calls.isEmpty())
        assertEquals(SyncState.PENDING, rig.job(key).state)
    }

    // ---- 日志 ----

    @Test
    fun `日志里没有令牌、地址、指纹、Record 标识和文字`() = runRig { rig ->
        val ok = rig.publish(SyncSamples.RECORD_ID, 1, text = "这是一段不该出现在日志里的口述内容")
        rig.scope.advanceTimeBy(1)
        val lost = rig.publish(SyncSamples.OTHER_RECORD_ID, 1)
        rig.server.faults += Fault.Reject(401, "GATEWAY_AUTH_REVOKED", false)
        rig.engine.runOnce()
        rig.pairing.save(SyncSamples.pairedGateway(pairedAt = rePaired))
        rig.engine.resolveAfterRepair()
        rig.server.faults += Fault.StoreThenLoseResponse
        rig.engine.runOnce()
        rig.advanceToRetry(ok)
        rig.engine.runOnce()
        rig.engine.networkLost()

        assertTrue("应当确实记了日志", rig.log.lines.size > 5)
        val everything = rig.log.lines.joinToString("\n")
        for (secret in listOf(SyncSamples.TOKEN_TEXT, "192.168.1.20", "study-pc.local", TlsFixtures.gatewayA.pin.hex, ok.recordId, lost.recordId, "口述内容", rig.submissions.getValue(ok).textSha256, SyncSamples.GATEWAY_ID, SyncSamples.DEVICE_ID)) {
            assertFalse("日志里不该出现 $secret", everything.contains(secret))
        }
        assertNotNull(rig.decisions.firstOrNull { it.event == SyncEvent.STORED_CONFIRMED })
    }
}
