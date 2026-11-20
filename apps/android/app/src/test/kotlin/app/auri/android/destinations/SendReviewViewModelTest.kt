@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.android.destinations

import app.auri.sync.destinations.TargetDecision
import app.auri.sync.engine.JobKey
import app.auri.sync.gateway.Intent
import app.auri.sync.gateway.SendMode
import app.auri.sync.policy.DataFlowState
import app.auri.sync.testing.DispatchRig
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 22 第 3、4、5 项：自动发送的初稿标为未审核；预览确认的 Destination 在用户确认前网关上没有给它的 Delivery（文字照常入库，评审 B2）；
 * 每条内容可标为备忘或请求执行。视图模型、发送账、同步引擎都是真的（发送账是内存实现，SQLite 实现与它共用同一份行为合同）；
 * 网关是带投递模型的假网关。
 */
class SendReviewViewModelTest {
    private class Review(val rig: DispatchRig, val viewModel: SendReviewViewModel, val woken: AtomicInteger)

    private fun run(body: suspend TestScope.(Review) -> Unit) = runTest {
        val rig = DispatchRig(this)
        val woken = AtomicInteger()
        val viewModel = SendReviewViewModel(rig.dispatch, LibraryItemTexts(rig.library), rig.clock, { woken.incrementAndGet() }, backgroundScope, rig.policy.states(), rig.log)
        settle()
        body(Review(rig, viewModel, woken))
    }

    private val Review.items get() = viewModel.state.value.items

    private fun JsonObject.publicationIntent() = getValue("publication").jsonObject.getValue("intent").jsonPrimitive.content

    private fun JsonObject.publicationTargets() = getValue("publication").jsonObject.getValue("destination_ids").jsonArray.map { it.jsonPrimitive.content }

    private fun JsonObject.reviewState() = getValue("content").jsonObject.getValue("review_state").jsonPrimitive.content

    // ---- 第 3 项：自动发送的初稿是未审核 ----

    @Test
    fun `自动发送：转录一完成就发出去，请求里这一版是 not_reviewed，界面上也标着未审核`() = run { review ->
        val auto = review.rig.createDestination("写作助手", SendMode.AUTO_SEND)

        review.rig.transcribed("明天检查小说记忆模块")
        settle()
        review.rig.engine.runOnce()
        settle()

        val body = review.rig.server.putBodies.single()
        assertEquals("not_reviewed", body.reviewState())
        assertEquals(listOf(auto.destinationId), body.publicationTargets())
        assertEquals(1, review.rig.server.deliveries.size)
        assertEquals("界面对每一条都写“未审核”", "未审核", UNREVIEWED_LABEL)
        assertTrue("请求已经构造过：标签冻结了", review.items.single().intentLocked)
        assertEquals(TargetDecision.AUTO, review.items.single().targets.single().decision)
    }

    @Test
    fun `自动发送不需要任何操作：没有“确认”这一步，也不会等用户`() = run { review ->
        review.rig.createDestination("写作助手", SendMode.AUTO_SEND)

        review.rig.transcribed()
        settle()

        assertFalse(review.items.single().awaitingUser)
        assertEquals(0, review.woken.get())
        review.rig.engine.runOnce()
        assertEquals("自己就发出去了", 1, review.rig.server.putCount)
    }

    // ---- 第 4 项：预览确认的 Destination 在用户确认前网关上没有给它的 Delivery ----

    @Test
    fun `预览确认：用户确认之前，文字照常入库，但网关上没有给它的 Delivery，同步跑多少轮都一样`() = run { review ->
        review.rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)

        review.rig.transcribed("还没决定要不要发")
        settle()
        repeat(3) { review.rig.engine.runOnce() }
        settle()

        assertEquals("文字不等任何决定，存进了用户自己的网关", 1, review.rig.server.putCount)
        assertEquals(1, review.rig.server.stored.size)
        assertEquals("预览的目标不在第一次提交里", emptyList<String>(), review.rig.server.putBodies.single().publicationTargets())
        assertTrue("没有 Delivery：它的 Agent 领不到这条文字", review.rig.server.deliveries.isEmpty())
        assertEquals(0, review.rig.server.additionCount)
        val item = review.items.single()
        assertTrue(item.awaitingUser)
        assertEquals("正文给用户看，让他决定", "还没决定要不要发", item.text)
        assertEquals(TargetDecision.AWAITING, item.targets.single().decision)
        assertEquals(0, review.woken.get())
    }

    @Test
    fun `用户确认：对已入库的这一版追加恰好一个 Delivery，请求里仍然是未审核的初稿；重复确认什么也不改`() = run { review ->
        val preview = review.rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = review.rig.transcribed()
        settle()
        review.rig.engine.runOnce() // 文字先入库（没有给预览目的地的 Delivery）

        review.viewModel.confirm(key, preview.destinationId)
        review.viewModel.confirm(key, preview.destinationId) // 双击
        settle()
        review.viewModel.confirm(key, preview.destinationId) // 过了一会儿又点一次
        settle()
        repeat(2) { review.rig.engine.runOnce() }
        settle()

        assertEquals("只有第一次确认叫醒了同步", 1, review.woken.get())
        assertEquals("追加不重新提交文字", 1, review.rig.server.putCount)
        assertEquals(1, review.rig.server.additionCount)
        assertEquals("恰好一个 Delivery，指向同一个 Revision", 1, review.rig.server.deliveries.size)
        assertEquals(key.revision, review.rig.server.deliveries.single().revision)
        assertEquals("用户确认发送不等于审核过正文：仍是未审核的初稿", "not_reviewed", review.rig.server.putBodies.single().reviewState())
        assertEquals(TargetDecision.CONFIRMED, review.items.single().targets.single().decision)
        assertFalse("已经没有要等的", review.items.single().awaitingUser)
    }

    @Test
    fun `用户在文字还没到网关时就确认了：目标随第一次提交一起走，不需要追加`() = run { review ->
        val preview = review.rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = review.rig.transcribed()
        settle()

        review.viewModel.confirm(key, preview.destinationId)
        settle()
        repeat(2) { review.rig.engine.runOnce() }
        settle()

        assertEquals(1, review.rig.server.putCount)
        assertEquals(listOf(preview.destinationId), review.rig.server.putBodies.single().publicationTargets())
        assertEquals(0, review.rig.server.additionCount)
        assertEquals(1, review.rig.server.deliveries.size)
    }

    @Test
    fun `确认之后进程被杀、重启后又点了一次确认：还是只有一个 Delivery`() = runTest {
        val rig = DispatchRig(this)
        val preview = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = rig.transcribed()
        rig.engine.runOnce()
        val woken = AtomicInteger()
        val before = SendReviewViewModel(rig.dispatch, LibraryItemTexts(rig.library), rig.clock, { woken.incrementAndGet() }, backgroundScope, rig.policy.states(), rig.log)
        before.confirm(key, preview.destinationId)
        settle()

        rig.restart() // 进程被杀：内存里的东西全没了，磁盘（发送账、任务表）还在
        val after = SendReviewViewModel(rig.dispatch, LibraryItemTexts(rig.library), rig.clock, { woken.incrementAndGet() }, backgroundScope, rig.policy.states(), rig.log)
        settle()
        after.confirm(key, preview.destinationId)
        settle()
        rig.engine.runOnce()
        rig.engine.runOnce()

        assertEquals("重启后的重复确认不再叫醒同步", 1, woken.get())
        assertEquals(1, rig.server.putCount)
        assertEquals(1, rig.server.additionCount)
        assertEquals(1, rig.server.deliveries.size)
    }

    @Test
    fun `用户选择不发送：不会为它产生 Delivery；决定是终局，之后点确认也改不回来`() = run { review ->
        val preview = review.rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = review.rig.transcribed()
        settle()

        review.viewModel.skip(key, preview.destinationId)
        settle()
        review.viewModel.confirm(key, preview.destinationId)
        settle()
        repeat(2) { review.rig.engine.runOnce() }

        assertTrue("没有 Delivery", review.rig.server.deliveries.isEmpty())
        assertEquals(TargetDecision.SKIPPED, review.items.single().targets.single().decision)
        assertEquals("文字仍然入库（那是另一项授权），只是不投给任何目的地", 1, review.rig.server.putCount)
        assertEquals(emptyList<String>(), review.rig.server.putBodies.single().publicationTargets())
    }

    @Test
    fun `自动发送与预览确认并存：自动的马上发出去，不等预览的决定；确认之后预览的 Delivery 追加到同一个 Revision`() = run { review ->
        val auto = review.rig.createDestination("写作助手", SendMode.AUTO_SEND)
        val preview = review.rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = review.rig.transcribed()
        settle()

        review.rig.engine.runOnce()
        assertEquals("自动发送的不被预览那个拦着", 1, review.rig.server.putCount)
        assertEquals(listOf(auto.destinationId), review.rig.server.deliveries.map { it.destinationId })

        review.viewModel.confirm(key, preview.destinationId)
        settle()
        review.rig.engine.runOnce()

        assertEquals(1, review.rig.server.putCount)
        assertEquals(setOf(auto.destinationId, preview.destinationId), review.rig.server.deliveries.map { it.destinationId }.toSet())
        assertEquals(2, review.rig.server.deliveries.size)
        assertTrue("两份投递指向同一个 Revision", review.rig.server.deliveries.all { it.revision == key.revision })
    }

    @Test
    fun `没有预览确认的目的地：界面没有任何要等的东西`() = run { review ->
        review.rig.createDestination("写作助手", SendMode.AUTO_SEND)
        review.rig.transcribed()
        settle()

        assertTrue(review.items.none { it.awaitingUser })
    }

    // ---- 第 5 项：每条内容可标为备忘或请求执行 ----

    @Test
    fun `每条内容的标签可以在发出去之前改：请求里带的就是最后选的那个；标签只是数据，不执行任何东西`() = run { review ->
        val preview = review.rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM, setOf(Intent.CONTEXT, Intent.ACTION_REQUEST))
        val key = review.rig.transcribed("把周报发给我")
        settle()
        assertEquals("默认是备忘", Intent.CONTEXT, review.items.single().intent)

        review.viewModel.setIntent(key, Intent.ACTION_REQUEST)
        settle()
        assertEquals(Intent.ACTION_REQUEST, review.items.single().intent)
        assertTrue(review.items.single().canChangeIntent)

        review.viewModel.confirm(key, preview.destinationId)
        settle()
        review.rig.engine.runOnce()
        settle()

        assertEquals("action_request", review.rig.server.putBodies.single().publicationIntent())
        // 发出去之后网关上这一版的标签不能再改：界面也不给改，调用了也不动
        assertFalse(review.items.single().canChangeIntent)
        review.viewModel.setIntent(key, Intent.CONTEXT)
        settle()
        assertEquals(Intent.ACTION_REQUEST, review.items.single().intent)
    }

    @Test
    fun `没改标签的内容默认是备忘；用户可以改“新录的文字默认标成什么”，已有的不受影响`() = run { review ->
        review.rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM, setOf(Intent.CONTEXT, Intent.ACTION_REQUEST))
        val first = review.rig.transcribed("第一条")
        settle()
        assertEquals(Intent.CONTEXT, review.viewModel.state.value.defaultIntent)

        review.viewModel.setDefaultIntent(Intent.ACTION_REQUEST)
        settle()
        val second = review.rig.transcribed("第二条")
        settle()

        assertEquals(Intent.ACTION_REQUEST, review.viewModel.state.value.defaultIntent)
        val byKey = review.items.associateBy { it.key }
        assertEquals(Intent.CONTEXT, byKey.getValue(first).intent)
        assertEquals(Intent.ACTION_REQUEST, byKey.getValue(second).intent)
    }

    @Test
    fun `目的地不接收“请求执行”：标成请求执行的文字不会发给它，也不会拦住别的目的地`() = run { review ->
        val memoOnly = review.rig.createDestination("只收备忘", SendMode.PREVIEW_CONFIRM, setOf(Intent.CONTEXT))
        val key = review.rig.transcribed("把周报发给我")
        settle()
        assertTrue(review.items.single().awaitingUser)

        review.viewModel.setIntent(key, Intent.ACTION_REQUEST)
        settle()

        val target = review.items.single().targets.single()
        assertEquals(memoOnly.destinationId, target.destinationId)
        assertFalse("它不接收这个标签：不会收到，也就没什么可决定的", target.eligible)
        assertFalse(review.items.single().awaitingUser)
        assertTrue("文字从来没被拦着：同步任务一开始就在", review.rig.jobs.get(key) != null)
    }

    // ---- 列表 ----

    @Test
    fun `等你确认的排在前面，其余新的在前；本机已经没有的文字显示为空而不是出错`() = run { review ->
        val preview = review.rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val decided = review.rig.transcribed("先录的，已经决定了")
        review.viewModel.confirm(decided, preview.destinationId)
        settle()
        delay(60_000)
        val waiting = review.rig.transcribed("后录的，还在等")
        settle()

        assertEquals(listOf(waiting, decided), review.items.map { it.key })
        assertEquals(listOf(true, false), review.items.map { it.awaitingUser })

        val ghost = JobKey("00000000-0000-4000-8000-000000000999", 1)
        assertEquals(null, LibraryItemTexts(review.rig.library).textOf(ghost))
    }

    @Test
    fun `日志里没有正文、没有目的地名字与编号`() = run { review ->
        val preview = review.rig.createDestination("机密项目助手", SendMode.PREVIEW_CONFIRM)
        val key = review.rig.transcribed("这是一段不该出现在日志里的口述")
        settle()
        review.viewModel.confirm(key, preview.destinationId)
        settle()
        review.rig.engine.runOnce()

        val everything = review.rig.log.lines.joinToString("\n")
        assertFalse(everything.contains("这是一段不该出现在日志里的口述"))
        assertFalse(everything.contains("机密项目助手"))
        assertFalse(everything.contains(preview.destinationId))
    }

    // ---- 评审 B1：文字进网关要单独授权；页面要如实说“现在不会发出”，授权后才发 ----

    @Test
    fun `没授权文字进网关时：页面状态如实标着没放行，同步一轮什么也没发；授权之后状态翻过来、积压的才发出去`() = runTest {
        val rig = DispatchRig(this, policyState = DataFlowState.Unset)
        val viewModel = SendReviewViewModel(rig.dispatch, LibraryItemTexts(rig.library), rig.clock, {}, backgroundScope, rig.policy.states(), rig.log)
        rig.createDestination("写作助手", SendMode.AUTO_SEND)
        rig.transcribed("别发出去")
        settle()

        rig.engine.runOnce()
        settle()
        assertFalse("没放行：页面据此说“现在不会发出”", viewModel.state.value.dataFlow.textMayEnterGateway)
        assertEquals("网关上什么也没有", 0, rig.server.putBodies.size)

        rig.policy.setGatewayTextGranted(true)
        settle()
        assertTrue(viewModel.state.value.dataFlow.textMayEnterGateway)
        rig.engine.runOnce()
        assertEquals("授权之后，积压的那一条才发出去", 1, rig.server.putBodies.size)

        rig.policy.setStrictLocal(true)
        settle()
        assertFalse("严格仅本地压过授权", viewModel.state.value.dataFlow.textMayEnterGateway)
    }

    @Test
    fun `还没读到放行状态之前页面按没放行算`() = runTest {
        val rig = DispatchRig(this)
        val never = kotlinx.coroutines.flow.emptyFlow<DataFlowState>()
        val viewModel = SendReviewViewModel(rig.dispatch, LibraryItemTexts(rig.library), rig.clock, {}, backgroundScope, never, rig.log)
        settle()

        assertFalse(viewModel.state.value.dataFlow.textMayEnterGateway)
    }
}
