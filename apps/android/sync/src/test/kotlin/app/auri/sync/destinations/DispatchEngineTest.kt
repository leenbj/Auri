@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.sync.destinations

import app.auri.sync.gateway.Intent
import app.auri.sync.gateway.SendMode
import app.auri.sync.machine.SyncState
import app.auri.sync.testing.DispatchRig
import app.auri.sync.testing.FakeGateway
import app.auri.sync.testing.SyncSamples
import app.auri.sync.testing.TlsFixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 22 第 3、4、5 项的因果测试：真正的同步引擎 + 真正的发送账接口实现 + 网关的小模型（投递在第一次入库时创建，已入库的 Revision 之后靠追加端点补，评审 B2）。
 * 要证明的是**网关上发生了什么**：自动发送的文字带着目标与“未审核”到了、不等任何人；预览确认的目的地在用户确认之前网关上没有给它的投递，
 * 确认之后对同一个已入库的 Revision 追加恰好一份，确认一次与确认十次一样。
 */
class DispatchEngineTest {
    private fun run(body: suspend TestScope.(DispatchRig) -> Unit) = runTest { body(DispatchRig(this)) }

    private fun JsonObject.publication(): JsonObject = getValue("publication").jsonObject

    private fun JsonObject.targets(): List<String> = publication().getValue("destination_ids").jsonArray.map { it.jsonPrimitive.content }

    // ---- 自动发送：转录完马上发，标着未审核 ----

    @Test
    fun `自动发送：转录完成后一轮同步就送到网关，请求带着这个 Destination，内容标为未审核，网关为它建了一条待领取的投递`() = run { rig ->
        val auto = rig.createDestination("写作助手", SendMode.AUTO_SEND)

        val key = rig.transcribed("明天检查小说记忆模块")
        rig.engine.runOnce()

        val body = rig.server.putBodies.single()
        assertEquals(listOf(auto.destinationId), body.targets())
        assertEquals("context", body.publication().getValue("intent").jsonPrimitive.content)
        assertEquals("自动发送的初稿必须标为未审核（合同字段 review_state）", "not_reviewed", body.getValue("content").jsonObject.getValue("review_state").jsonPrimitive.content)
        assertEquals("asr", body.getValue("content").jsonObject.getValue("source").jsonPrimitive.content)
        val delivery = rig.server.deliveries.single()
        assertEquals(auto.destinationId, delivery.destinationId)
        assertEquals("PENDING", delivery.state)
        assertEquals(SyncState.STORED, rig.jobs.get(key)!!.state)
        assertEquals("入库的回答里看到了这份投递：账上记下，不会再去追加", GatewayDelivery.CREATED, rig.dispatch.item(key)!!.targets.single().delivery)
        assertEquals(0, rig.server.additionCount)
    }

    @Test
    fun `没有任何 Destination 时照旧只入库：publication 的目标为空，网关上没有投递`() = run { rig ->
        val key = rig.transcribed()

        rig.engine.runOnce()

        assertEquals(emptyList<String>(), rig.server.putBodies.single().targets())
        assertTrue(rig.server.deliveries.isEmpty())
        assertEquals(SyncState.STORED, rig.jobs.get(key)!!.state)
    }

    // ---- 预览确认：文字照常入库，Delivery 在用户确认之后追加 ----

    @Test
    fun `预览确认：确认之前文字照常入库、网关上没有给它的投递；确认之后对同一个 Revision 追加恰好一份，不再 PUT`() = run { rig ->
        val preview = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = rig.transcribed()

        repeat(3) { rig.engine.runOnce() }

        assertEquals("文字不等任何决定：一个 PUT，把它存进用户自己的网关", 1, rig.server.putCount)
        assertEquals("预览的目标不在第一次提交里", emptyList<String>(), rig.server.putBodies.single().targets())
        assertEquals("确认之前一个追加请求都不许有", 0, rig.server.additionCount)
        assertTrue("确认之前网关上没有给任何 Destination 的投递：Agent 领不到这条文字", rig.server.deliveries.isEmpty())
        assertEquals(SyncState.STORED, rig.jobs.get(key)!!.state)

        val decided = rig.dispatch.decide(key, preview.destinationId, Choice.CONFIRM, rig.clock.now())
        assertEquals(DecideResult.Decided, decided)
        rig.engine.runOnce()

        assertEquals("追加不重新提交文字：Revision 不可变", 1, rig.server.putCount)
        assertEquals(1, rig.server.additionCount)
        val body = rig.server.additionBodies.single()
        assertEquals(listOf(preview.destinationId), body.getValue("destination_ids").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("追加带的是冻结的意图", "context", body.getValue("intent").jsonPrimitive.content)
        val delivery = rig.server.deliveries.single()
        assertEquals(preview.destinationId, delivery.destinationId)
        assertEquals("给的是同一个 Revision", key.revision, delivery.revision)
        assertEquals("PENDING", delivery.state)
        assertEquals(GatewayDelivery.CREATED, rig.dispatch.item(key)!!.targets.single().delivery)

        rig.engine.runOnce()
        assertEquals("账上记下了网关已有：再跑多少轮都不会再追加", 1, rig.server.additionCount)
    }

    @Test
    fun `重复确认（双击、重试）仍然只有一个追加请求、一份投递`() = run { rig ->
        val preview = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = rig.transcribed()
        rig.engine.runOnce()

        val results = List(4) { rig.dispatch.decide(key, preview.destinationId, Choice.CONFIRM, rig.clock.now()) }
        repeat(3) { rig.engine.runOnce() }

        assertEquals(DecideResult.Decided, results.first())
        assertTrue("后面三次都说“早就决定过了”", results.drop(1).all { it == DecideResult.AlreadyDecided(TargetDecision.CONFIRMED) })
        assertEquals(1, rig.server.putCount)
        assertEquals(1, rig.server.additionCount)
        assertEquals(1, rig.server.deliveries.size)
    }

    @Test
    fun `进程在确认之后、追加之前被杀：重启后同一个确认再来一次，网关上还是一份`() = run { rig ->
        val preview = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = rig.transcribed()
        rig.engine.runOnce()
        rig.dispatch.decide(key, preview.destinationId, Choice.CONFIRM, rig.clock.now())

        rig.restart() // 确认已经落盘，但追加请求一个都还没发
        val again = rig.dispatch.decide(key, preview.destinationId, Choice.CONFIRM, rig.clock.now())
        repeat(2) { rig.engine.runOnce() }

        assertEquals(DecideResult.AlreadyDecided(TargetDecision.CONFIRMED), again)
        assertEquals(1, rig.server.putCount)
        assertEquals(1, rig.server.additionCount)
        assertEquals(1, rig.server.deliveries.size)
    }

    @Test
    fun `追加请求已经到了网关、回答在路上丢了：账上不知道，重试时网关幂等地回答已有，网关上仍然只有一份`() = run { rig ->
        val preview = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = rig.transcribed()
        rig.engine.runOnce()
        rig.dispatch.decide(key, preview.destinationId, Choice.CONFIRM, rig.clock.now())
        rig.server.faults += FakeGateway.Fault.StoreThenLoseResponse // 网关建了投递，回答丢了

        rig.engine.runOnce()

        assertEquals("网关已经建好了", 1, rig.server.deliveries.size)
        assertEquals("但手机没有拿到回答，账上不知道", GatewayDelivery.NOT_YET, rig.dispatch.item(key)!!.targets.single().delivery)
        assertNotNull("排好了稍后重试", rig.engine.additionsRetryAt.value)

        rig.restart() // 再加一次进程被杀，重试靠落盘的账
        advanceTimeBy(20 * 60_000L)
        rig.engine.runOnce()

        assertEquals("重试是幂等的：网关回答已有，不多建", 1, rig.server.deliveries.size)
        assertEquals(2, rig.server.additionCount)
        assertEquals(GatewayDelivery.CREATED, rig.dispatch.item(key)!!.targets.single().delivery)
    }

    @Test
    fun `进程在用户还没决定时被杀：重启后仍然没有给它的投递，什么都不会自己发出去`() = run { rig ->
        rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = rig.transcribed()

        rig.restart()
        repeat(3) { rig.engine.runOnce() }

        assertEquals("文字照常入库了", 1, rig.server.putCount)
        assertEquals(0, rig.server.additionCount)
        assertTrue(rig.server.deliveries.isEmpty())
        assertTrue(rig.dispatch.item(key)!!.awaitingUser)
    }

    @Test
    fun `用户选择跳过：文字只入库，那个 Destination 永远不会有这一条的投递`() = run { rig ->
        val preview = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = rig.transcribed()

        rig.dispatch.decide(key, preview.destinationId, Choice.SKIP, rig.clock.now())
        repeat(2) { rig.engine.runOnce() }

        assertEquals(emptyList<String>(), rig.server.putBodies.single().targets())
        assertEquals(0, rig.server.additionCount)
        assertTrue(rig.server.deliveries.isEmpty())
    }

    // ---- 追加失败：文字已经入库，不受影响 ----

    @Test
    fun `追加时网关暂时不可用：文字不受影响，排好稍后重试，到点再做成；退避有上限`() = run { rig ->
        val preview = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = rig.transcribed()
        rig.engine.runOnce()
        rig.dispatch.decide(key, preview.destinationId, Choice.CONFIRM, rig.clock.now())
        rig.server.faults += FakeGateway.Fault.Reject(503, "GATEWAY_UNREACHABLE", retryable = true)

        rig.engine.runOnce()

        assertEquals(SyncState.STORED, rig.jobs.get(key)!!.state)
        assertTrue(rig.server.deliveries.isEmpty())
        val at = rig.engine.additionsRetryAt.value
        assertNotNull("排好了重试时间", at)
        assertTrue("有界的退避，不是立刻狂刷", at!! > rig.clock.now())

        advanceTimeBy(20 * 60_000L)
        rig.engine.runOnce()

        assertEquals(1, rig.server.deliveries.size)
        assertNull("做成了就清掉重试时间", rig.engine.additionsRetryAt.value)
    }

    @Test
    fun `追加被网关永久拒绝：记为没有送达、不再重试，界面如实说`() = run { rig ->
        val preview = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = rig.transcribed()
        rig.engine.runOnce()
        rig.dispatch.decide(key, preview.destinationId, Choice.CONFIRM, rig.clock.now())
        rig.server.faults += FakeGateway.Fault.Reject(422, "REQUEST_INVALID", retryable = false)

        repeat(3) { rig.engine.runOnce() }

        assertEquals("拒绝之后不再问", 1, rig.server.additionCount)
        assertEquals(GatewayDelivery.REFUSED, rig.dispatch.item(key)!!.targets.single().delivery)
        assertEquals(DeliveryView.Unknown(UnknownReason.DELIVERY_REFUSED), rig.service.deliveries(preview.destinationId, 10).single { it.item.key == key }.view)
    }

    @Test
    fun `追加答复成功却没有这个目标：记为没有送达，不假设追加一定建成`() = run { rig ->
        val preview = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = rig.transcribed()
        rig.engine.runOnce()
        rig.dispatch.decide(key, preview.destinationId, Choice.CONFIRM, rig.clock.now())
        rig.server.dropFromAdditions += preview.destinationId

        repeat(2) { rig.engine.runOnce() }

        assertEquals(GatewayDelivery.REFUSED, rig.dispatch.item(key)!!.targets.single().delivery)
        assertEquals(1, rig.server.additionCount)
        assertTrue(rig.server.deliveries.isEmpty())
    }

    // ---- 自动与预览并存（评审 B2）----

    @Test
    fun `自动发送与预览确认并存：自动的 Delivery 随入库马上就有，不等预览的决定；确认之后追加预览的，两份指向同一个 Revision`() = run { rig ->
        val auto = rig.createDestination("写作助手", SendMode.AUTO_SEND)
        val preview = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = rig.transcribed()

        rig.engine.runOnce()

        assertEquals("预览那个还没决定，也不拦着自动发送", 1, rig.server.putCount)
        assertEquals(listOf(auto.destinationId), rig.server.putBodies.single().targets())
        assertEquals(auto.destinationId, rig.server.deliveries.single().destinationId)
        rig.server.agentClaims(auto.destinationId, key.recordId)
        assertEquals("自动发送的 Agent 现在就领到了文字", "LEASED", rig.server.deliveries.single().state)

        rig.dispatch.decide(key, preview.destinationId, Choice.CONFIRM, rig.clock.now())
        rig.engine.runOnce()

        assertEquals(1, rig.server.putCount)
        assertEquals(1, rig.server.additionCount)
        assertEquals(setOf(auto.destinationId, preview.destinationId), rig.server.deliveries.map { it.destinationId }.toSet())
        assertTrue("同一个 Revision", rig.server.deliveries.all { it.revision == key.revision })
        assertEquals("追加不碰自动那份已经被领取的投递", "LEASED", rig.server.deliveries.first { it.destinationId == auto.destinationId }.state)
    }

    // ---- 发送那一刻现问，而不是发布时定死 ----

    @Test
    fun `排队期间 Destination 被撤销：发出去的 publication 不含它，网关也不会因为它整条拒收`() = run { rig ->
        val auto = rig.createDestination("写作助手", SendMode.AUTO_SEND)
        val key = rig.transcribed()
        rig.service.revoke(auto.destinationId) // 文字已排队、还没发

        rig.engine.runOnce()

        assertEquals(emptyList<String>(), rig.server.putBodies.single().targets())
        assertEquals(SyncState.STORED, rig.jobs.get(key)!!.state)
        assertTrue(rig.server.deliveries.isEmpty())
    }

    // ---- 意图标签：它只是数据 ----

    @Test
    fun `每条内容可以标成请求执行：标签随请求发出，网关据此只投给接收这种意图的 Destination`() = run { rig ->
        val both = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM, setOf(Intent.CONTEXT, Intent.ACTION_REQUEST))
        val key = rig.transcribed()

        assertEquals(IntentResult.Changed, rig.dispatch.setIntent(key, Intent.ACTION_REQUEST, rig.clock.now()))
        rig.engine.runOnce()
        rig.dispatch.decide(key, both.destinationId, Choice.CONFIRM, rig.clock.now())
        rig.engine.runOnce()

        val body = rig.server.putBodies.single()
        assertEquals("action_request", body.publication().getValue("intent").jsonPrimitive.content)
        assertEquals("预览的目标确认之后才追加", emptyList<String>(), body.targets())
        assertEquals("请求执行只是标签：文字照样是 ASR 初稿、未审核，没有任何额外字段", "not_reviewed", body.getValue("content").jsonObject.getValue("review_state").jsonPrimitive.content)
        val addition = rig.server.additionBodies.single()
        assertEquals("追加带的意图就是第一次提交冻结的那个", "action_request", addition.getValue("intent").jsonPrimitive.content)
        assertEquals(listOf(both.destinationId), addition.getValue("destination_ids").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `只收备忘的 Destination 遇到请求执行的文字：不在 publication 里（否则整条被网关 422 永久拒绝），文字照常入库`() = run { rig ->
        rig.createDestination("写作助手", SendMode.AUTO_SEND, setOf(Intent.CONTEXT))
        rig.dispatch.setDefaultIntent(Intent.ACTION_REQUEST)
        val key = rig.transcribed()

        rig.engine.runOnce()

        val body = rig.server.putBodies.single()
        assertEquals("action_request", body.publication().getValue("intent").jsonPrimitive.content)
        assertEquals(emptyList<String>(), body.targets())
        assertEquals(SyncState.STORED, rig.jobs.get(key)!!.state)
        assertTrue(rig.server.deliveries.isEmpty())
    }

    @Test
    fun `已经开始发送的文字，标签不能再改（网关上这一版的意图不可更改）`() = run { rig ->
        rig.createDestination("写作助手", SendMode.AUTO_SEND)
        val key = rig.transcribed()
        rig.engine.runOnce()

        assertEquals(IntentResult.Locked, rig.dispatch.setIntent(key, Intent.ACTION_REQUEST, rig.clock.now()))
        assertEquals(Intent.CONTEXT, rig.dispatch.item(key)!!.intent)
        assertNotNull(rig.server.putBodies.single())
    }

    @Test
    fun `没网时文字排着队，标签还能改，网关收到的是改后的（评审 M3）`() = run { rig ->
        rig.createDestination("写作助手", SendMode.AUTO_SEND, setOf(Intent.CONTEXT, Intent.ACTION_REQUEST))
        rig.lan.hosts.clear() // 手机离开了局域网
        val key = rig.transcribed()
        rig.engine.runOnce() // 没网：任务进入等待网关，但没有构造过提交
        assertEquals(SyncState.WAITING_GATEWAY, rig.jobs.get(key)!!.state)

        assertEquals("还没有为它构造过提交：标签可以改", IntentResult.Changed, rig.dispatch.setIntent(key, Intent.ACTION_REQUEST, rig.clock.now()))
        rig.lan.host(SyncSamples.LAN_ADDRESS, TlsFixtures.gatewayA) { rig.server.handle(it) }
        rig.engine.runOnce()

        assertEquals("action_request", rig.server.putBodies.single().publication().getValue("intent").jsonPrimitive.content)
        assertEquals("开始发送之后就冻结了", IntentResult.Locked, rig.dispatch.setIntent(key, Intent.CONTEXT, rig.clock.now()))
    }
}
