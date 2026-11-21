@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.sync.engine

import app.auri.sync.machine.SyncState
import app.auri.sync.pairing.NeedsUser
import app.auri.sync.policy.DataFlowState
import app.auri.sync.status.SyncHeadline
import app.auri.sync.status.SyncStatus
import app.auri.sync.status.UserCause
import app.auri.sync.testing.FakeGateway.Fault
import app.auri.sync.testing.InMemoryDataFlowPolicy
import app.auri.sync.testing.SyncRig
import app.auri.sync.testing.SyncSamples
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评审 R7：合同升到 1.2，手机只和说 1.2 的网关说话。以前遇到没升级的网关，手机照发、网关回 422，每条文字都被当成“请求不合规”永久拒绝，
 * 用户看到的是一排没法重试的失败。现在**联系网关传任何东西之前**先问它说哪些版本（GET /v1/capabilities）：
 * 太旧就什么都不发、说清楚“请先更新网关”，更新之后自动恢复，不需要重新配对，也没有任何一条文字被永久拒绝。
 */
class SyncEngineContractTest {
    private val oldGateway = listOf("1.1")
    private val thirdRecord = "c6c9e2af-4989-5bf8-b45a-8fc499255c6b"
    private val fourthRecord = "d7dafbb0-5a9a-5c09-856b-90d5aa366d7c"

    private fun runRig(policyState: DataFlowState = InMemoryDataFlowPolicy.GRANTED, body: suspend TestScope.(SyncRig) -> Unit) =
        runTest { body(SyncRig(this, policyState = policyState)) }

    @Test
    fun `网关太旧：一个数据请求都不发，配对记录标成网关太旧，任务没有被永久拒绝，界面说的是要用户处理`() = runRig { rig ->
        rig.server.supportedVersions = oldGateway
        val key = rig.publish()

        rig.engine.runOnce()

        assertEquals("不能往旧网关发任何数据请求（入库、查状态、追加）", emptyList<String>(), rig.server.calls.toList())
        assertEquals("只问了一次版本", 1, rig.server.capabilityCalls.get())
        assertEquals(NeedsUser.GATEWAY_TOO_OLD, rig.pairing.gateway!!.needsUser)
        val job = rig.job(key)
        assertNotEquals("旧网关不是永久拒绝：用户更新网关之后这条还要能发出去", SyncState.BLOCKED, job.state)
        assertFalse("没有数据请求离开手机，不能留着“可能已送到”的标记", job.uncertain)
        assertNull(job.blockedReason)
        val status = SyncStatus.of(rig.pairing.load(), rig.jobs.all(), InMemoryDataFlowPolicy.GRANTED)
        assertEquals(SyncHeadline.NEEDS_USER, status.headline)
        assertEquals(listOf(UserCause.GATEWAY_TOO_OLD), status.causes)
    }

    @Test
    fun `网关还是旧的：再来多少轮也不发，也不一轮一轮重复写配对记录或重复刷屏`() = runRig { rig ->
        rig.server.supportedVersions = oldGateway
        rig.publish()
        rig.engine.runOnce()
        val writesAfterFirstPass = rig.pairing.saves.get()

        repeat(2) { rig.engine.runOnce() }

        assertEquals(emptyList<String>(), rig.server.calls.toList())
        assertEquals("标记已经在了，后面的几轮不再改配对记录", writesAfterFirstPass, rig.pairing.saves.get())
        assertEquals(NeedsUser.GATEWAY_TOO_OLD, rig.pairing.gateway!!.needsUser)
    }

    @Test
    fun `更新网关之后自动恢复：不需要重新配对，标记清掉，排着的文字送到`() = runRig { rig ->
        rig.server.supportedVersions = oldGateway
        val key = rig.publish()
        rig.engine.runOnce()
        val before = rig.pairing.gateway!!

        rig.server.supportedVersions = listOf("1.2")
        rig.engine.runOnce()

        val after = rig.pairing.gateway!!
        assertNull("配对本身没问题：标记自己清掉", after.needsUser)
        assertEquals("还是同一份配对：设备、令牌、配对时刻都没变", Triple(before.deviceId, before.token, before.pairedAt), Triple(after.deviceId, after.token, after.pairedAt))
        assertEquals(SyncState.STORED, rig.job(key).state)
        assertEquals(1, rig.server.putCount)
    }

    @Test
    fun `兼容的网关：同一份配对在同一个进程里只问一次版本，重启后（新引擎）再问一次`() = runRig { rig ->
        rig.publish(SyncSamples.RECORD_ID)
        rig.publish(SyncSamples.OTHER_RECORD_ID)
        rig.publish(thirdRecord)

        rig.engine.runOnce()

        assertEquals(3, rig.server.putCount)
        assertEquals("三条文字、一次版本询问", 1, rig.server.capabilityCalls.get())

        rig.publish(fourthRecord)
        rig.newEngine().runOnce()

        assertEquals(4, rig.server.putCount)
        assertEquals("新进程不知道上一个进程问过什么，重新问一次", 2, rig.server.capabilityCalls.get())
    }

    @Test
    fun `重新配对之后：旧配对确认过的不算数，对新配对重新问`() = runRig { rig ->
        rig.publish(SyncSamples.RECORD_ID)
        rig.engine.runOnce()
        assertEquals(1, rig.server.capabilityCalls.get())

        rig.pairing.save(SyncSamples.pairedGateway(pairedAt = Instant.parse("2026-11-19T09:00:00Z")))
        rig.publish(SyncSamples.OTHER_RECORD_ID)
        rig.engine.runOnce()

        assertEquals(2, rig.server.putCount)
        assertEquals(2, rig.server.capabilityCalls.get())
    }

    @Test
    fun `问不到版本（网关出错、回答读不懂、通告了无法判断的版本）：什么都不发，也不冤枉网关太旧；问得到了才发`() = runRig { rig ->
        val key = rig.publish()
        val failures = listOf<(app.auri.sync.testing.FakeGateway) -> Unit>(
            { it.capabilitiesFault = Fault.Reject(503, "GATEWAY_UNREACHABLE", retryable = true) },
            { it.capabilitiesRaw = "这不是 JSON" },
            { it.capabilitiesRaw = """{"supported_schema_versions":["1.3"]}""" },
        )
        for (fail in failures) {
            fail(rig.server)

            rig.engine.runOnce()

            assertEquals("没确认版本就不发：${rig.server.calls}", 0, rig.server.putCount)
            assertNull("没确认不是太旧：不能给配对记录打标记", rig.pairing.gateway!!.needsUser)
            assertNotEquals(SyncState.BLOCKED, rig.job(key).state)
            assertNotEquals(SyncState.STORED, rig.job(key).state)
            assertFalse("没有数据请求离开手机", rig.job(key).uncertain)
        }

        rig.server.capabilitiesRaw = null
        rig.engine.runOnce()

        assertEquals(SyncState.STORED, rig.job(key).state)
        assertEquals(1, rig.server.putCount)
    }

    @Test
    fun `令牌在版本询问上被拒（401）：和别处一样是连接问题要重新配对，不是网关太旧`() = runRig { rig ->
        rig.server.capabilitiesFault = Fault.Reject(401, "GATEWAY_AUTH_REVOKED", retryable = false)
        val key = rig.publish()

        rig.engine.runOnce()

        assertEquals(NeedsUser.AUTH_REJECTED, rig.pairing.gateway!!.needsUser)
        assertEquals(0, rig.server.putCount)
        assertEquals(SyncState.BLOCKED, rig.job(key).state)
        assertEquals(BlockedReason.AUTH_REJECTED, rig.job(key).blockedReason)
    }

    @Test
    fun `没放行就不问版本：问版本也是联系网关，文字没放行时一个字节都不发`() = runRig(DataFlowState(gatewayTextGranted = false, strictLocal = false)) { rig ->
        rig.server.supportedVersions = oldGateway
        rig.publish()

        rig.engine.runOnce()

        assertTrue("不该有任何请求：${rig.lan.delivered.map { it.second.path }}", rig.lan.delivered.isEmpty())
        assertEquals(0, rig.server.capabilityCalls.get())
        assertNull(rig.pairing.gateway!!.needsUser)
    }
}
