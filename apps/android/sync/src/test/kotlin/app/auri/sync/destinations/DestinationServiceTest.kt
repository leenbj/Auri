@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.sync.destinations

import app.auri.sync.gateway.CreateDestinationRequest
import app.auri.sync.gateway.Destination
import app.auri.sync.gateway.Intent
import app.auri.sync.gateway.Publication
import app.auri.sync.gateway.SendMode
import app.auri.sync.pairing.NeedsUser
import app.auri.sync.testing.DispatchRig
import app.auri.sync.testing.SyncSamples
import app.auri.sync.testing.TlsFixtures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 22 第 1、2、6 项：创建、列表、撤销、签发/轮换凭证（每次先本机核验）、每个 Destination 上每条内容的状态。
 * 服务与引擎是真的，网关是带 Destination 与投递模型的小模型（FakeGateway；与真网关的对照在 realgateway 测试里）。
 */
class DestinationServiceTest {
    private fun run(body: suspend TestScope.(DispatchRig) -> Unit) = runTest { body(DispatchRig(this)) }

    private fun <T> ServiceResult<T>.ok(): T = (this as? ServiceResult.Ok<T>)?.value ?: error("期望成功，实际 $this")

    private fun <T> ServiceResult<T>.failure(): ServiceFailure = (this as? ServiceResult.Failed)?.failure ?: error("期望失败，实际 $this")

    // ---- 1. 创建：发送方式是用户显式选的，请求里就是它 ----

    @Test
    fun `创建自动发送与预览确认的 Destination：网关收到的 send_mode 恰好是用户选的那个，本机缓存跟着有了它`() = run { rig ->
        val auto = rig.service.create(CreateDestinationRequest("写作助手", SendMode.AUTO_SEND, setOf(Intent.CONTEXT))).ok()
        val preview = rig.service.create(CreateDestinationRequest("Claude Code", SendMode.PREVIEW_CONFIRM, setOf(Intent.CONTEXT, Intent.ACTION_REQUEST))).ok()

        assertEquals(listOf("auto_send", "preview_confirm"), rig.server.createBodies.map { it.getValue("send_mode").jsonPrimitive.content })
        assertEquals(SendMode.AUTO_SEND, auto.sendMode)
        assertEquals(SendMode.PREVIEW_CONFIRM, preview.sendMode)
        assertEquals(setOf(auto.destinationId, preview.destinationId), rig.dispatch.destinations().map { it.destinationId }.toSet())
        assertEquals(listOf("context"), rig.server.createBodies[0].getValue("allowed_intents").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `有效的 Destination 已经有 50 个：再创建先说明白，请求没有发出（合同限制一条文字最多投给 50 个）`() = run { rig ->
        val gatewayId = SyncSamples.pairedGateway().gatewayId
        val existing = (0 until Publication.MAX_DESTINATIONS).map { index ->
            Destination("d0000000-0000-4000-8000-%012x".format(index), "目的地$index", SendMode.AUTO_SEND, setOf(Intent.CONTEXT), rig.clock.now(), "ef7b1358-df31-520f-90f3-d98fc5173b46", false, null)
        }
        rig.dispatch.applyDestinations(gatewayId, existing, authoritative = false, now = rig.clock.now())

        val result = rig.service.create(CreateDestinationRequest("第 51 个", SendMode.AUTO_SEND, setOf(Intent.CONTEXT)))

        assertEquals(ServiceFailure.LimitReached, result.failure())
        assertTrue("请求没有发出", rig.server.adminCalls.none { it == "POST destinations" })

        // 撤销了一个（本地缓存里标成已撤销）就又能创建了
        rig.dispatch.applyDestinations(gatewayId, listOf(existing[0].copy(revokedAt = rig.clock.now())), authoritative = false, now = rig.clock.now())
        assertEquals(SendMode.AUTO_SEND, rig.service.create(CreateDestinationRequest("第 51 个", SendMode.AUTO_SEND, setOf(Intent.CONTEXT))).ok().sendMode)
    }

    @Test
    fun `没有配对时创建失败，什么请求都没发出`() = runTest {
        val rig = DispatchRig(this, gateway = null)

        val result = rig.service.create(CreateDestinationRequest("写作助手", SendMode.AUTO_SEND, setOf(Intent.CONTEXT)))

        assertEquals(ServiceFailure.NotPaired, result.failure())
        assertTrue(rig.lan.attempts.isEmpty())
    }

    @Test
    fun `连不上网关：明说连不上，请求没有发出，本机缓存不变`() = runTest {
        val rig = DispatchRig(this, gatewayOnLan = false)

        val result = rig.service.create(CreateDestinationRequest("写作助手", SendMode.AUTO_SEND, setOf(Intent.CONTEXT)))

        assertEquals(ServiceFailure.Unreachable, result.failure())
        assertTrue(rig.dispatch.destinations().isEmpty())
    }

    @Test
    fun `网关回答读不懂或请求中途断了：报“结果不确定”，不是“失败”也不是“成功”，缓存不动`() = run { rig ->
        rig.lan.hosts.getValue(SyncSamples.LAN_ADDRESS).handler = { request ->
            if (request.method == "POST") app.auri.sync.testing.ScriptedExchange.json(200, """{"schema_version":"1.1"}""") else rig.server.handle(request)
        }

        val result = rig.service.create(CreateDestinationRequest("写作助手", SendMode.AUTO_SEND, setOf(Intent.CONTEXT)))

        assertEquals(ServiceFailure.Unreadable, result.failure())
        assertTrue(rig.dispatch.destinations().isEmpty())
    }

    @Test
    fun `令牌被网关拒绝：要用户处理（重新配对），和同步页是同一个说法`() = run { rig ->
        rig.lan.hosts.getValue(SyncSamples.LAN_ADDRESS).handler = { request ->
            if (request.path.startsWith("/v1/destinations")) app.auri.sync.testing.ScriptedExchange.json(401, """{"schema_version":"1.1","code":"GATEWAY_AUTH_REVOKED","message":"x","retryable":false,"required_action":null,"operation_id":null}""") else rig.server.handle(request)
        }

        assertEquals(ServiceFailure.NeedsRepair(NeedsUser.AUTH_REJECTED), rig.service.refresh().failure())
    }

    // ---- 列表与撤销 ----

    @Test
    fun `刷新：网关给的完整列表就是本机缓存；网关上已经没有的，缓存里也没有了`() = run { rig ->
        val kept = rig.createDestination("留下的", SendMode.AUTO_SEND)
        val gone = rig.createDestination("被删掉的", SendMode.PREVIEW_CONFIRM)
        rig.server.destinations.remove(gone.destinationId)

        val listed = rig.service.refresh().ok()

        assertEquals(listOf(kept.destinationId), listed.map { it.destinationId })
        assertEquals(listOf(kept.destinationId), rig.dispatch.destinations().map { it.destinationId })
        assertEquals(listOf(kept.destinationId), rig.service.destinations.first().map { it.destinationId })
    }

    @Test
    fun `撤销：网关取消它还没确认的投递，本机缓存显示已撤销，之后的文字不再规划它`() = run { rig ->
        val auto = rig.createDestination("写作助手", SendMode.AUTO_SEND)
        val key = rig.transcribed()
        rig.engine.runOnce()

        val revoked = rig.service.revoke(auto.destinationId).ok()

        assertFalse(revoked.active)
        assertEquals("CANCELLED", rig.server.deliveries.single().state)
        assertFalse(rig.dispatch.destinations().single().active)
        val later = rig.transcribed("撤销之后的文字")
        assertTrue("撤销后的新文字没有目标", rig.dispatch.item(later)!!.targets.isEmpty())
        assertEquals(DeliveryView.OnGateway(GatewayStage.CANCELLED), rig.service.deliveries(auto.destinationId, 10).single { it.item.key == key }.view)
    }

    @Test
    fun `撤销是幂等的：再撤一次网关还是同一个撤销时间，不会出错`() = run { rig ->
        val auto = rig.createDestination("写作助手", SendMode.AUTO_SEND)

        val first = rig.service.revoke(auto.destinationId).ok()
        val second = rig.service.revoke(auto.destinationId).ok()

        assertEquals(first.revokedAt, second.revokedAt)
    }

    // ---- 2. 凭证：先本机核验，通过才联网 ----

    @Test
    fun `核验没通过（拒绝、取消、没有硬件、没登记、暂时不可用）：一个凭证请求都不发给网关，也不返回凭证`() = run { rig ->
        val destination = rig.createDestination("写作助手", SendMode.AUTO_SEND)
        val refusals = listOf(
            AuthResult.Denied,
            AuthResult.Cancelled,
            AuthResult.Unavailable(UnavailableReason.NO_HARDWARE),
            AuthResult.Unavailable(UnavailableReason.NONE_ENROLLED),
            AuthResult.Unavailable(UnavailableReason.TEMPORARILY_UNAVAILABLE),
        )

        for (refusal in refusals) {
            rig.authenticator.next = refusal
            val outcome = rig.service.issueCredential(destination.destinationId)
            assertEquals("$refusal 必须原样回报", CredentialOutcome.NotAuthenticated(refusal), outcome)
        }

        assertFalse("核验没过，网关上不能有任何凭证请求", rig.server.adminCalls.contains("POST credential"))
        assertEquals(false, rig.server.destinations.getValue(destination.destinationId).credentialIssued)
        assertEquals(refusals.size, rig.authenticator.purposes.size)
    }

    @Test
    fun `核验通过才签发：第一次是生成，第二次是轮换（旧凭证作废），每一次都现场核验，不缓存上一次的通过`() = run { rig ->
        val destination = rig.createDestination("写作助手", SendMode.AUTO_SEND)

        val first = (rig.service.issueCredential(destination.destinationId) as CredentialOutcome.Issued).credential
        val firstText = first.credential.reveal()
        val second = (rig.service.issueCredential(destination.destinationId) as CredentialOutcome.Issued).credential

        assertEquals(listOf(AuthPurpose.ISSUE_CREDENTIAL, AuthPurpose.ROTATE_CREDENTIAL), rig.authenticator.purposes.toList())
        assertFalse(first.replacedPrevious)
        assertTrue("第二次是轮换", second.replacedPrevious)
        assertNotEquals("轮换给出新凭证", firstText, second.credential.reveal())
        assertEquals("网关上现在只认新的", second.credential.reveal(), rig.server.destinations.getValue(destination.destinationId).credential)
        assertTrue("本机缓存记着它已经签发过凭证（只记这个事实，不记凭证）", rig.dispatch.destinations().single().credentialIssued)
        assertEquals("请求体是空对象", listOf("{}", "{}"), rig.server.credentialRequests.toList())
    }

    @Test
    fun `凭证不会出现在日志里，也不会出现在结果的字符串形式里`() = run { rig ->
        val destination = rig.createDestination("写作助手", SendMode.AUTO_SEND)

        val outcome = rig.service.issueCredential(destination.destinationId) as CredentialOutcome.Issued
        val secret = outcome.credential.credential.reveal()

        assertTrue(rig.log.lines.none { secret in it })
        assertTrue("令牌也不进日志", rig.log.lines.none { SyncSamples.TOKEN_TEXT in it })
        assertTrue("名字、编号也不进日志", rig.log.lines.none { "写作助手" in it || destination.destinationId in it })
        assertFalse(outcome.toString().contains(secret))
        assertFalse(outcome.credential.toString().contains(secret))
        assertFalse(outcome.credential.credential.toString().contains(secret))
    }

    @Test
    fun `两次点击不会连着轮换两次：上一次还没结束时第二次直接回“忙”，核验只弹一次`() = run { rig ->
        val destination = rig.createDestination("写作助手", SendMode.AUTO_SEND)
        val release = CompletableDeferred<Unit>()
        rig.authenticator.gate = { release.await() }

        val first = async { rig.service.issueCredential(destination.destinationId) }
        runCurrent()
        val second = rig.service.issueCredential(destination.destinationId)
        release.complete(Unit)

        assertEquals(CredentialOutcome.Failed(ServiceFailure.Busy), second)
        assertTrue(first.await() is CredentialOutcome.Issued)
        assertEquals(1, rig.authenticator.purposes.size)
        assertEquals(1, rig.server.credentialRequests.size)
    }

    @Test
    fun `已撤销或本机不认识的 Destination：不弹指纹、不联网，直接说用不了`() = run { rig ->
        val destination = rig.createDestination("写作助手", SendMode.AUTO_SEND)
        rig.service.revoke(destination.destinationId).ok()
        val before = rig.server.adminCalls.size

        val revoked = rig.service.issueCredential(destination.destinationId)
        val unknown = rig.service.issueCredential("d0000000-0000-4000-8000-0000000000ff")

        assertEquals(CredentialOutcome.Failed(ServiceFailure.DestinationUnavailable), revoked)
        assertEquals(CredentialOutcome.Failed(ServiceFailure.DestinationUnavailable), unknown)
        assertTrue("一次核验都没弹", rig.authenticator.purposes.isEmpty())
        assertEquals(before, rig.server.adminCalls.size)
    }

    @Test
    fun `没配对或连接要用户处理时：同样在弹指纹之前就说`() = runTest {
        val unpaired = DispatchRig(this, gateway = null)
        assertEquals(CredentialOutcome.Failed(ServiceFailure.NotPaired), unpaired.service.issueCredential("d0000000-0000-4000-8000-000000000001"))
        assertTrue(unpaired.authenticator.purposes.isEmpty())

        val broken = DispatchRig(this, gateway = SyncSamples.pairedGateway(needsUser = NeedsUser.KEY_MISMATCH))
        assertEquals(CredentialOutcome.Failed(ServiceFailure.NeedsRepair(NeedsUser.KEY_MISMATCH)), broken.service.issueCredential("d0000000-0000-4000-8000-000000000001"))
        assertTrue(broken.authenticator.purposes.isEmpty())
    }

    @Test
    fun `核验通过后发现连不上网关：回报连不上，没有凭证`() = run { rig ->
        val destination = rig.createDestination("写作助手", SendMode.AUTO_SEND)
        rig.lan.hosts.clear() // 核验之后网络没了

        val outcome = rig.service.issueCredential(destination.destinationId)

        assertEquals(CredentialOutcome.Failed(ServiceFailure.Unreachable), outcome)
    }

    @Test
    fun `请求途中用户重新配对了另一台网关：旧网关的回答不写进缓存`() = run { rig ->
        val handler = rig.lan.hosts.getValue(SyncSamples.LAN_ADDRESS).handler
        rig.lan.hosts.getValue(SyncSamples.LAN_ADDRESS).handler = { request ->
            val reply = handler(request)
            // 回答在路上时，手机被重新配对了（换了设备令牌与配对时间）
            rig.pairing.update { it.copy(pairedAt = it.pairedAt.plusSeconds(60)) }
            reply
        }

        val result = rig.service.create(CreateDestinationRequest("写作助手", SendMode.AUTO_SEND, setOf(Intent.CONTEXT)))

        assertEquals(ServiceFailure.NotPaired, result.failure())
        assertTrue("旧配对的回答没有写进缓存", rig.dispatch.destinations().isEmpty())
    }

    // ---- 6. 每条内容的状态：网关怎么说就怎么显示 ----

    private suspend fun DispatchRig.view(destination: Destination, key: app.auri.sync.engine.JobKey): DeliveryView =
        service.deliveries(destination.destinationId, 20).single { it.item.key == key }.view

    @Test
    fun `自动发送的一条内容依次是：排队 → 已入库 → 已领取 → 已确认，和网关说的一致`() = run { rig ->
        val auto = rig.createDestination("写作助手", SendMode.AUTO_SEND)
        val key = rig.transcribed()
        // 用户在没网时录的：任务排着，还没发
        assertEquals(DeliveryView.Queued(QueuedPhase.SENDING), rig.view(auto, key))

        rig.engine.runOnce()
        assertEquals(DeliveryView.OnGateway(GatewayStage.STORED), rig.view(auto, key))

        rig.server.agentClaims(auto.destinationId, key.recordId)
        assertEquals(DeliveryView.OnGateway(GatewayStage.CLAIMED), rig.view(auto, key))

        rig.server.agentAcknowledges(auto.destinationId, key.recordId)
        assertEquals(DeliveryView.OnGateway(GatewayStage.ACKNOWLEDGED), rig.view(auto, key))
    }

    @Test
    fun `网关给的状态这个版本不认识：显示未知，不当成功`() = run { rig ->
        val auto = rig.createDestination("写作助手", SendMode.AUTO_SEND)
        val key = rig.transcribed()
        rig.engine.runOnce()
        rig.server.deliveries.single().state = "SOMETHING_NEW_IN_V2"

        assertEquals(DeliveryView.Unknown(UnknownReason.UNRECOGNIZED_STATE), rig.view(auto, key))
    }

    @Test
    fun `已入库但这会儿问不到网关：未知，不是“已入库”也不是失败`() = run { rig ->
        val auto = rig.createDestination("写作助手", SendMode.AUTO_SEND)
        val key = rig.transcribed()
        rig.engine.runOnce()
        rig.lan.hosts.clear()

        assertEquals(DeliveryView.Unknown(UnknownReason.GATEWAY_UNREACHABLE), rig.view(auto, key))
    }

    @Test
    fun `网关回答里没有这条投递，而本机认为它该有：未知（缺口要说明白，不能悄悄显示成功）`() = run { rig ->
        val auto = rig.createDestination("写作助手", SendMode.AUTO_SEND)
        val key = rig.transcribed()
        rig.engine.runOnce()
        rig.server.deliveries.clear()

        assertEquals(DeliveryView.Unknown(UnknownReason.NO_DELIVERY_RECORD), rig.view(auto, key))
    }

    @Test
    fun `网关答了错误：未知（回答不可用），不是已入库`() = run { rig ->
        val auto = rig.createDestination("写作助手", SendMode.AUTO_SEND)
        val key = rig.transcribed()
        rig.engine.runOnce()
        rig.server.faults += app.auri.sync.testing.FakeGateway.Fault.Reject(500, "GATEWAY_UNREACHABLE", retryable = true)

        assertEquals(DeliveryView.Unknown(UnknownReason.GATEWAY_ANSWER_UNUSABLE), rig.view(auto, key))
    }

    @Test
    fun `预览确认的内容：确认前是“等你确认”（网关上没有），跳过后是“已跳过”，不会被说成已入库`() = run { rig ->
        val preview = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val waiting = rig.transcribed("还没决定的")
        val skipped = rig.transcribed("选择跳过的")
        rig.dispatch.decide(skipped, preview.destinationId, Choice.SKIP, rig.clock.now())

        assertEquals(DeliveryView.AwaitingYou, rig.view(preview, waiting))
        assertEquals(DeliveryView.Skipped, rig.view(preview, skipped))
        assertEquals(0, rig.server.statusCount)
    }

    @Test
    fun `预览确认之后、追加做成之前：是“正在请网关交给它”，不是已入库；做成之后才是已入库（评审 B2）`() = run { rig ->
        val preview = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = rig.transcribed()
        rig.engine.runOnce() // 文字入库了，但没有给预览目的地的投递
        assertEquals("文字在网关上，但给它的 Delivery 还没有：只说等你确认", DeliveryView.AwaitingYou, rig.view(preview, key))

        rig.dispatch.decide(key, preview.destinationId, Choice.CONFIRM, rig.clock.now())
        assertEquals("已确认、还没追加：正在请网关交给它", DeliveryView.Queued(QueuedPhase.REQUESTING_DELIVERY), rig.view(preview, key))

        rig.engine.runOnce()
        assertEquals(DeliveryView.OnGateway(GatewayStage.STORED), rig.view(preview, key))
    }

    @Test
    fun `没有网络时排队的内容分开说：等网关、稍后重试、要你处理`() = run { rig ->
        val auto = rig.createDestination("写作助手", SendMode.AUTO_SEND)
        val key = rig.transcribed()
        rig.lan.hosts.clear()
        rig.engine.runOnce() // 连不上：等网关

        assertEquals(DeliveryView.Queued(QueuedPhase.WAITING_FOR_GATEWAY), rig.view(auto, key))
    }

    @Test
    fun `没有任何一个状态说“已处理”：网关能证明的最多是已确认`() {
        val stages = GatewayStage.entries.map { it.name }
        assertEquals(listOf("STORED", "CLAIMED", "ACKNOWLEDGED", "REJECTED", "CANCELLED"), stages)
        assertEquals(GatewayStage.ACKNOWLEDGED, GatewayStage.fromGateway("ACKNOWLEDGED"))
        assertNull("网关没有的状态不能被猜成别的", GatewayStage.fromGateway("PROCESSED"))
        assertNull(GatewayStage.fromGateway("acknowledged"))
        assertNull(GatewayStage.fromGateway(""))
    }

    @Test
    fun `租户内的网关状态与指纹：用的是配对时记下的那一个`() = run { rig ->
        rig.createDestination("写作助手", SendMode.AUTO_SEND)
        assertTrue(rig.lan.pinsUsed.all { it == TlsFixtures.gatewayA.pin })
    }
}
