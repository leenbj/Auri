@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.android.destinations

import app.auri.sync.destinations.TargetDecision
import app.auri.sync.gateway.SendMode
import app.auri.sync.testing.DispatchRig
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评审 M1：待发送页只读最新的 30 条（SendReviewViewModel.ITEM_LIMIT）。更新的文字多过 30 条之后，更早的那条等你确认的预览文字就在页面上消失了，
 * 够不着它，也就没法确认或选择不发送——它的 Agent 永远领不到。等用户决定的文字不受条数限制：先把它们全找出来，再补上最近的。
 * 视图模型、发送账、同步引擎都是真的；网关是假网关。
 */
class SendReviewBacklogTest {
    private class Review(val rig: DispatchRig, val viewModel: SendReviewViewModel, val woken: AtomicInteger)

    private fun run(body: suspend TestScope.(Review) -> Unit) = runTest {
        val rig = DispatchRig(this)
        val woken = AtomicInteger()
        val viewModel = SendReviewViewModel(rig.dispatch, LibraryItemTexts(rig.library), rig.clock, { woken.incrementAndGet() }, backgroundScope, rig.policy.states(), rig.log)
        settle()
        body(Review(rig, viewModel, woken))
    }

    private val Review.items get() = viewModel.state.value.items

    @Test
    fun `更新的文字多过一页：最早那条等确认的预览仍在页面上，还能确认`() = run { review ->
        val preview = review.rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val oldest = review.rig.transcribed("最早的一条，还在等确认")
        repeat(SendReviewViewModel.ITEM_LIMIT + 1) { review.rig.transcribed("后来的第 ${it + 1} 条") }
        settle()

        assertEquals("全部 ${SendReviewViewModel.ITEM_LIMIT + 2} 条都在等确认，一条也不能少", SendReviewViewModel.ITEM_LIMIT + 2, review.items.size)
        val item = review.items.single { it.key == oldest }
        assertTrue("它还在等用户决定", item.awaitingUser)
        assertEquals("最早的一条，还在等确认", item.text)

        review.viewModel.confirm(oldest, preview.destinationId)
        settle()

        assertEquals("够得着，确认生效", TargetDecision.CONFIRMED, review.rig.dispatch.item(oldest)!!.targets.single().decision)
        assertEquals(1, review.woken.get())
    }

    @Test
    fun `更新的文字都已决定：页面是最近一页加上那条还在等确认的更早的文字，等确认的排在最前`() = run { review ->
        val preview = review.rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val oldest = review.rig.transcribed("最早的一条，还在等确认")
        val newer = List(SendReviewViewModel.ITEM_LIMIT + 1) { review.rig.transcribed("后来的第 ${it + 1} 条") }
        newer.forEach { review.viewModel.skip(it, preview.destinationId) }
        settle()

        assertEquals("最近一页加上更早那条等确认的", SendReviewViewModel.ITEM_LIMIT + 1, review.items.size)
        assertEquals("等确认的排在最前", oldest, review.items.first().key)
        assertTrue(review.items.first().awaitingUser)
        assertEquals("其余的是最近一页：最新的在前", newer.takeLast(SendReviewViewModel.ITEM_LIMIT).reversed(), review.items.drop(1).map { it.key })
        assertTrue("其余的都已决定", review.items.drop(1).none { it.awaitingUser })
    }

    @Test
    fun `更早那条等确认的预览被决定之后，不再占着页面`() = run { review ->
        val preview = review.rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val oldest = review.rig.transcribed("最早的一条，还在等确认")
        val newer = List(SendReviewViewModel.ITEM_LIMIT + 1) { review.rig.transcribed("后来的第 ${it + 1} 条") }
        newer.forEach { review.viewModel.skip(it, preview.destinationId) }
        settle()
        assertEquals(oldest, review.items.first().key)

        review.viewModel.skip(oldest, preview.destinationId)
        settle()

        assertEquals("只剩最近一页", SendReviewViewModel.ITEM_LIMIT, review.items.size)
        assertFalse(review.items.any { it.key == oldest })
    }
}
