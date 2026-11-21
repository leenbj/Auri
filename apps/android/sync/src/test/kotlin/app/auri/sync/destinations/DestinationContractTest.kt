@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.sync.destinations

import app.auri.sync.gateway.CreateDestinationRequest
import app.auri.sync.gateway.Intent
import app.auri.sync.gateway.SendMode
import app.auri.sync.machine.SyncState
import app.auri.sync.pairing.NeedsUser
import app.auri.sync.testing.DispatchRig
import app.auri.sync.testing.FakeGateway.Fault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评审 R7：Destination 管理和“追加 Delivery”也是联系网关，同样先问版本。对没升级的网关：
 * - 管理页读列表，网关回的是 1.1 的响应，手机严格读取会得到“读不懂，结果不确定”——对一次只读请求说这话是误导；
 * - 追加 Delivery 的端点 1.1 的网关没有，发过去只会被当成“永久拒绝”。
 * 所以：太旧就一个管理或追加请求都不发，界面说“网关太旧”；更新之后自动恢复。
 */
class DestinationContractTest {
    private val oldGateway = listOf("1.1")

    private fun run(body: suspend TestScope.(DispatchRig) -> Unit) = runTest { body(DispatchRig(this)) }

    private fun <T> ServiceResult<T>.failure(): ServiceFailure = (this as? ServiceResult.Failed)?.failure ?: error("期望失败，实际 $this")

    // ---- 管理 ----

    @Test
    fun `网关太旧：列表、创建、撤销、签发都不发请求，界面拿到的是网关太旧；配对记录标上，同步页同一个说法`() = run { rig ->
        rig.server.supportedVersions = oldGateway

        assertEquals(ServiceFailure.NeedsRepair(NeedsUser.GATEWAY_TOO_OLD), rig.service.refresh().failure())
        assertEquals(ServiceFailure.NeedsRepair(NeedsUser.GATEWAY_TOO_OLD), rig.service.create(CreateDestinationRequest("写作助手", SendMode.AUTO_SEND, setOf(Intent.CONTEXT))).failure())

        assertEquals("一个管理请求都不许发：${rig.server.adminCalls}", emptyList<String>(), rig.server.adminCalls.toList())
        assertEquals(NeedsUser.GATEWAY_TOO_OLD, rig.pairing.gateway!!.needsUser)
        assertTrue(rig.dispatch.destinations().isEmpty())
    }

    @Test
    fun `更新网关之后管理页自动恢复：不需要重新配对，标记清掉，列表读得出来`() = run { rig ->
        rig.server.supportedVersions = oldGateway
        rig.service.refresh()
        assertEquals(NeedsUser.GATEWAY_TOO_OLD, rig.pairing.gateway!!.needsUser)

        rig.server.supportedVersions = listOf("1.2")
        val result = rig.service.refresh()

        assertTrue("更新之后应该成功：$result", result is ServiceResult.Ok)
        assertNull(rig.pairing.gateway!!.needsUser)
        assertEquals(listOf("GET destinations"), rig.server.adminCalls.toList())
    }

    @Test
    fun `问不到版本不是太旧：网关出错、通告无法判断的版本，管理页按“连不上”处理，一个管理请求都没发，配对记录不打标记`() = run { rig ->
        rig.server.capabilitiesFault = Fault.Reject(503, "GATEWAY_UNREACHABLE", retryable = true)
        assertEquals(ServiceFailure.Unreachable, rig.service.refresh().failure())

        rig.server.capabilitiesRaw = """{"supported_schema_versions":["1.3"]}"""
        assertEquals(ServiceFailure.Unreachable, rig.service.refresh().failure())

        assertEquals(emptyList<String>(), rig.server.adminCalls.toList())
        assertNull(rig.pairing.gateway!!.needsUser)
    }

    @Test
    fun `令牌在版本询问上被拒：管理页和同步页一样说“网关不再承认这台手机”`() = run { rig ->
        rig.server.capabilitiesFault = Fault.Reject(401, "GATEWAY_AUTH_REVOKED", retryable = false)

        assertEquals(ServiceFailure.NeedsRepair(NeedsUser.AUTH_REJECTED), rig.service.refresh().failure())
        assertEquals(emptyList<String>(), rig.server.adminCalls.toList())
    }

    @Test
    fun `兼容的网关：管理操作之间只问一次版本`() = run { rig ->
        rig.createDestination("写作助手", SendMode.AUTO_SEND)
        rig.service.refresh()
        rig.service.refresh()

        assertEquals(1, rig.server.capabilityCalls.get())
    }

    @Test
    fun `生成凭证：“网关太旧”的标记可能已经过时，不在弹指纹之前拦；通过核验之后问一次版本，还旧就不发请求，更新了就恢复并签发`() = run { rig ->
        val destination = rig.createDestination("写作助手", SendMode.AUTO_SEND)
        rig.restart() // 新进程不记得刚才问过的版本（同一个进程里问过兼容就不再问）
        rig.server.supportedVersions = oldGateway
        rig.service.refresh()
        assertEquals(NeedsUser.GATEWAY_TOO_OLD, rig.pairing.gateway!!.needsUser)
        rig.server.adminCalls.clear()

        val stillOld = rig.service.issueCredential(destination.destinationId)

        assertEquals(CredentialOutcome.Failed(ServiceFailure.NeedsRepair(NeedsUser.GATEWAY_TOO_OLD)), stillOld)
        assertEquals("标记可能是旧的：先核验、再问版本，所以弹过一次指纹", 1, rig.authenticator.purposes.size)
        assertEquals("旧网关上没有发出任何管理请求", emptyList<String>(), rig.server.adminCalls.toList())

        rig.server.supportedVersions = listOf("1.2")
        val issued = rig.service.issueCredential(destination.destinationId)

        assertTrue("网关更新之后，同一个入口直接恢复并签发：$issued", issued is CredentialOutcome.Issued)
        assertNull("标记也清掉了，同步页同时恢复", rig.pairing.gateway!!.needsUser)
        assertEquals(listOf("POST credential"), rig.server.adminCalls.toList())
    }

    // ---- 追加 Delivery ----

    @Test
    fun `追加 Delivery 之前也问版本：网关换成了旧的（重启后的新进程），预览确认的追加不发，更新之后补上`() = run { rig ->
        val preview = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = rig.transcribed()
        rig.engine.runOnce()
        assertEquals(SyncState.STORED, rig.jobs.get(key)!!.state)
        assertEquals(DecideResult.Decided, rig.dispatch.decide(key, preview.destinationId, Choice.CONFIRM, rig.clock.now()))

        rig.restart() // 新进程不记得上一个进程确认过什么版本
        rig.server.supportedVersions = oldGateway
        rig.engine.runOnce()

        assertEquals("旧网关没有追加端点：发过去只会被当成永久拒绝", 0, rig.server.additionCount)
        assertEquals(NeedsUser.GATEWAY_TOO_OLD, rig.pairing.gateway!!.needsUser)
        assertTrue("账上没有被记成“被拒绝”：${rig.dispatch.item(key)}", rig.dispatch.item(key)!!.targets.none { it.delivery == GatewayDelivery.REFUSED })

        rig.server.supportedVersions = listOf("1.2")
        rig.engine.runOnce()

        assertEquals("更新之后补上，恰好一份", 1, rig.server.additionCount)
        assertEquals(1, rig.server.deliveries.count { it.destinationId == preview.destinationId })
        assertNull(rig.pairing.gateway!!.needsUser)
    }
}
