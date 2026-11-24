@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.sync.status

import app.auri.sync.destinations.Choice
import app.auri.sync.gateway.SendMode
import app.auri.sync.testing.DispatchRig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 评审 M2：同步页只看任务表，预览确认的文字一入库页面就是“已同步：所有文字都已进入网关”——
 * 可预览确认保护的是“Agent 在你确认之前看不到这条文字”，页面一句“已同步”会让人以为 Agent 早就有了。
 * 页面要如实说有几条在等用户确认。文字本身确实在网关里（评审 B2：文字入库不等任何决定），所以“已同步”那半句仍然是真的，不能改成假话；
 * 要补的是另一半。数从发送账来（任务表里没有它），所以状态流要同时盯着发送账的变化。
 */
class SyncStatusAwaitingTest {
    private fun run(body: suspend TestScope.(DispatchRig) -> Unit) = runTest { body(DispatchRig(this)) }

    private suspend fun DispatchRig.statuses(): Flow<SyncStatus> = SyncStatus.watch(flowOf(pairing.load()), jobs.observe(), policy.states(), dispatch)

    @Test
    fun `文字都已入库、预览确认的目标还在等用户：状态数出有几条在等，不是只剩一个已同步`() = run { rig ->
        rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        rig.transcribed("第一条")
        rig.transcribed("第二条")
        rig.engine.runOnce()

        val status = rig.statuses().first()

        assertEquals("文字确实都进了用户自己的网关", SyncHeadline.UP_TO_DATE, status.headline)
        assertEquals("但有两条的投递在等用户确认，Agent 现在领不到", 2, status.awaitingYou)
    }

    @Test
    fun `没有等用户的文字时不多报：自动发送的目的地、没有目的地，都是零`() = run { rig ->
        rig.createDestination("写作助手", SendMode.AUTO_SEND)
        rig.transcribed()
        rig.engine.runOnce()

        assertEquals(0, rig.statuses().first().awaitingYou)
    }

    @Test
    fun `用户确认或选择不发送之后，等待的条数随之减少，直到为零（状态流会重新发，不用等别的变化）`() = run { rig ->
        val preview = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val first = rig.transcribed("第一条")
        val second = rig.transcribed("第二条")
        rig.engine.runOnce()
        val seen = mutableListOf<Int>()
        backgroundScope.launch { rig.statuses().collect { seen += it.awaitingYou } }
        runCurrent()

        rig.dispatch.decide(first, preview.destinationId, Choice.CONFIRM, rig.clock.now())
        runCurrent()
        rig.dispatch.decide(second, preview.destinationId, Choice.SKIP, rig.clock.now())
        runCurrent()

        assertEquals(listOf(2, 1, 0), seen)
    }

    @Test
    fun `文字还没入库时，只有预览确认目标的那一条也算在发送中：它不会因为在等人确认就从状态里消失`() = run { rig ->
        rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        rig.transcribed()

        val status = rig.statuses().first()

        assertEquals("任务照常登记（评审 B2），页面不能对着一条还没入库的文字说已同步", SyncHeadline.SENDING, status.headline)
        assertEquals(1, status.counts.queued)
        assertEquals(1, status.awaitingYou)
    }
}
