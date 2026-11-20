@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.sync.destinations

import app.auri.sync.gateway.Intent
import app.auri.sync.gateway.SendMode
import app.auri.sync.machine.SyncState
import app.auri.sync.testing.DispatchRig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
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
 * Ticket 22 第 3、4、5 项的因果测试：真正的同步引擎 + 真正的发送账接口实现 + 网关的小模型（含“投递只在第一次入库时创建”的真网关语义，G13）。
 * 要证明的是**网关上发生了什么**：自动发送的文字带着目标与“未审核”到了；预览确认的文字在用户确认之前网关上一条投递（甚至一条记录）都没有，
 * 确认一次与确认十次一样，只有一份。
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
    }

    @Test
    fun `没有任何 Destination 时照旧只入库：publication 的目标为空，网关上没有投递`() = run { rig ->
        val key = rig.transcribed()

        rig.engine.runOnce()

        assertEquals(emptyList<String>(), rig.server.putBodies.single().targets())
        assertTrue(rig.server.deliveries.isEmpty())
        assertEquals(SyncState.STORED, rig.jobs.get(key)!!.state)
    }

    // ---- 预览确认：用户确认之前不产生 Delivery ----

    @Test
    fun `预览确认：确认之前不管同步跑多少轮，网关上没有投递，连这条文字都没有；确认之后恰好一份`() = run { rig ->
        val preview = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = rig.transcribed()

        repeat(3) { rig.engine.runOnce() }

        assertEquals("确认之前一个 PUT 都不许有", 0, rig.server.putCount)
        assertTrue("确认之前网关上没有投递", rig.server.deliveries.isEmpty())
        assertTrue("确认之前网关上连这条文字都没有", rig.server.stored.isEmpty())
        assertNull("没有同步任务，引擎根本看不见它", rig.jobs.get(key))

        val decided = rig.dispatch.decide(key, preview.destinationId, Choice.CONFIRM, rig.clock.now())
        assertEquals(DecideResult.Decided(releasedForSending = true), decided)
        rig.engine.runOnce()

        assertEquals(1, rig.server.putCount)
        assertEquals(listOf(preview.destinationId), rig.server.putBodies.single().targets())
        assertEquals("not_reviewed", rig.server.putBodies.single().getValue("content").jsonObject.getValue("review_state").jsonPrimitive.content)
        assertEquals("PENDING", rig.server.deliveries.single().state)
    }

    @Test
    fun `重复确认（双击、重试）仍然只有一个 PUT、一份投递`() = run { rig ->
        val preview = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = rig.transcribed()

        val results = List(4) { rig.dispatch.decide(key, preview.destinationId, Choice.CONFIRM, rig.clock.now()) }
        repeat(3) { rig.engine.runOnce() }

        assertEquals(DecideResult.Decided(releasedForSending = true), results.first())
        assertTrue("后面三次都说“早就决定过了”", results.drop(1).all { it == DecideResult.AlreadyDecided(TargetDecision.CONFIRMED) })
        assertEquals(1, rig.server.putCount)
        assertEquals(1, rig.server.deliveries.size)
    }

    @Test
    fun `进程在确认之后、发送之前被杀：重启后同一个确认再来一次，网关上还是一份`() = run { rig ->
        val preview = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = rig.transcribed()
        rig.dispatch.decide(key, preview.destinationId, Choice.CONFIRM, rig.clock.now())

        rig.restart() // 确认已经落盘，但一个请求都还没发
        val again = rig.dispatch.decide(key, preview.destinationId, Choice.CONFIRM, rig.clock.now())
        repeat(2) { rig.engine.runOnce() }

        assertEquals(DecideResult.AlreadyDecided(TargetDecision.CONFIRMED), again)
        assertEquals(1, rig.server.putCount)
        assertEquals(1, rig.server.deliveries.size)
    }

    @Test
    fun `进程在用户还没决定时被杀：重启后仍然拦着，什么都不会自己发出去`() = run { rig ->
        rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = rig.transcribed()

        rig.restart()
        repeat(3) { rig.engine.runOnce() }

        assertEquals(0, rig.server.putCount)
        assertNull(rig.jobs.get(key))
        assertTrue(rig.dispatch.item(key)!!.awaitingUser)
    }

    @Test
    fun `用户选择跳过：文字只入库，那个 Destination 永远不会有这一条的投递`() = run { rig ->
        val preview = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = rig.transcribed()

        rig.dispatch.decide(key, preview.destinationId, Choice.SKIP, rig.clock.now())
        rig.engine.runOnce()

        assertEquals(emptyList<String>(), rig.server.putBodies.single().targets())
        assertTrue(rig.server.deliveries.isEmpty())
    }

    // ---- 自动与预览并存（G13）----

    @Test
    fun `自动发送与预览确认并存：文字等预览的决定做完才一起发出，一个请求同时带两个目标`() = run { rig ->
        val auto = rig.createDestination("写作助手", SendMode.AUTO_SEND)
        val preview = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = rig.transcribed()

        rig.engine.runOnce()
        assertEquals("预览那个还没决定，网关只在第一次入库时建投递，所以整条先不发（G13）", 0, rig.server.putCount)

        rig.dispatch.decide(key, preview.destinationId, Choice.CONFIRM, rig.clock.now())
        rig.engine.runOnce()

        assertEquals(1, rig.server.putCount)
        assertEquals(setOf(auto.destinationId, preview.destinationId), rig.server.putBodies.single().targets().toSet())
        assertEquals(2, rig.server.deliveries.size)
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

        assertEquals(IntentResult.Changed(releasedForSending = false), rig.dispatch.setIntent(key, Intent.ACTION_REQUEST, rig.clock.now()))
        rig.dispatch.decide(key, both.destinationId, Choice.CONFIRM, rig.clock.now())
        rig.engine.runOnce()

        val body = rig.server.putBodies.single()
        assertEquals("action_request", body.publication().getValue("intent").jsonPrimitive.content)
        assertEquals(listOf(both.destinationId), body.targets())
        assertEquals("请求执行只是标签：文字照样是 ASR 初稿、未审核，没有任何额外字段", "not_reviewed", body.getValue("content").jsonObject.getValue("review_state").jsonPrimitive.content)
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
    fun `已经交给同步的文字，标签不能再改（网关上这一版的意图不可更改）`() = run { rig ->
        rig.createDestination("写作助手", SendMode.AUTO_SEND)
        val key = rig.transcribed()
        rig.engine.runOnce()

        assertEquals(IntentResult.Locked, rig.dispatch.setIntent(key, Intent.ACTION_REQUEST, rig.clock.now()))
        assertEquals(Intent.CONTEXT, rig.dispatch.item(key)!!.intent)
        assertNotNull(rig.server.putBodies.single())
    }
}
