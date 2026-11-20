@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.sync.resume

import app.auri.sync.destinations.Choice
import app.auri.sync.gateway.SendMode
import app.auri.sync.machine.SyncState
import app.auri.sync.testing.DispatchRig
import app.auri.sync.testing.InMemoryDataFlowPolicy
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 评审 H1：进程被杀之后谁来接着发，取决于“有活”期间系统那里始终登记着一个叫醒。有活的判断来自持久的账：
 * 任务表（文字还没入库）加上发送账（用户确认过预览、网关上还没有它的 Delivery）。这里测两件事：
 * 一、账上“欠追加”的结论不能比任务表旧——任务入库（STORED）本身就会让追加到期，它改的是任务表，发送账自己的信号不会响；
 * 二、提交（发布文字、用户确认）之后、叫醒登记之前进程被杀：下一个进程只凭磁盘上的账就能重新算出该有叫醒。
 * 调度器是假的；系统真的把应用叫醒、跑一轮，是真机才能验证的部分（验收 README，NOT_RUN）。
 */
class ResumeRecoveryTest {
    private class Wakes : ResumeScheduler {
        val calls = CopyOnWriteArrayList<String>()

        override fun ensureScheduled() {
            calls += "ensure"
        }

        override fun cancel() {
            calls += "cancel"
        }
    }

    /** 与 SyncRuntime 里一样接线：配对、任务表、放行状态，加上“账上还欠不欠追加”（任务表变了也要重算）。 */
    private fun DispatchRig.planner(wakes: Wakes) = ResumePlanner(
        pairing = flowOf(pairing.state),
        jobs = jobs.observe(),
        policy = flowOf(InMemoryDataFlowPolicy.GRANTED),
        scheduler = wakes,
        clock = clock,
        additionsOwed = { dispatch.due().isNotEmpty() },
        additionsChanged = dispatch.changes(),
    )

    private fun TestScope.start(rig: DispatchRig): Wakes = Wakes().also {
        rig.planner(it).start(backgroundScope)
        runCurrent()
    }

    @Test
    fun `预览目标在文字入库之前就被确认：任务入库的那一刻追加到期，叫醒不能被取消，追加做完才取消`() = runTest {
        val rig = DispatchRig(this)
        val preview = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = rig.transcribed()
        rig.dispatch.decide(key, preview.destinationId, Choice.CONFIRM, rig.clock.now()) // 任务还是 PENDING：此刻没有追加到期
        val wakes = start(rig)
        assertEquals("文字还没入库：登记叫醒", listOf("ensure"), wakes.calls.toList())

        // 引擎把任务推到 STORED：文字进了网关，向网关追加预览目标的 Delivery 还没做——发送账自己的信号一点没响
        rig.jobs.update(rig.jobs.get(key)!!.copy(state = SyncState.STORED))
        runCurrent()
        assertEquals("追加欠着：叫醒必须留着；此刻被杀，下一个进程要靠它被叫醒", listOf("ensure"), wakes.calls.toList())

        rig.dispatch.gatewayHas(key, listOf(preview.destinationId))
        runCurrent()
        assertEquals("追加做完了才没活", listOf("ensure", "cancel"), wakes.calls.toList())
    }

    @Test
    fun `文字发布提交了、叫醒还没登记进程就被杀：下一个进程只凭磁盘上的任务表就登记叫醒`() = runTest {
        val rig = DispatchRig(this)
        rig.transcribed() // 提交完成；这一世的规划器还没来得及反应，进程在这里被杀——没有任何叫醒登记

        rig.restart()
        val wakes = start(rig)

        assertEquals(listOf("ensure"), wakes.calls.toList())
    }

    @Test
    fun `用户确认提交了、叫醒还没登记进程就被杀：下一个进程只凭磁盘上的账就登记叫醒`() = runTest {
        val rig = DispatchRig(this)
        val preview = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = rig.transcribed()
        rig.jobs.update(rig.jobs.get(key)!!.copy(state = SyncState.STORED)) // 文字已入库：此刻没活，叫醒本来是取消的
        rig.dispatch.decide(key, preview.destinationId, Choice.CONFIRM, rig.clock.now()) // 提交完成；进程在这里被杀

        rig.restart()
        val wakes = start(rig)

        assertEquals("账上欠着追加：登记叫醒", listOf("ensure"), wakes.calls.toList())
    }
}
