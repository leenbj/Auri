@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.sync.resume

import app.auri.sync.destinations.Choice
import app.auri.sync.destinations.DecideResult
import app.auri.sync.destinations.DispatchStore
import app.auri.sync.destinations.TargetDecision
import app.auri.sync.engine.JobKey
import app.auri.sync.gateway.SendMode
import app.auri.sync.machine.SyncState
import app.auri.sync.testing.DispatchRig
import app.auri.sync.testing.InMemoryDataFlowPolicy
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评审 H1：进程被杀之后谁来接着发，取决于“有活”期间系统那里始终登记着一个叫醒。有活的判断来自持久的账：
 * 任务表（文字还没入库）加上发送账（用户确认过预览、网关上还没有它的 Delivery）。这里测两件事：
 * 一、账上“欠追加”的结论不能比任务表旧——任务入库（STORED）本身就会让追加到期，它改的是任务表，发送账自己的信号不会响；
 * 二、提交（发布文字、用户确认）之后、叫醒登记之前进程被杀：下一个进程只凭磁盘上的账就能重新算出该有叫醒。
 * 三、（复评 R12）确认之前先登记叫醒：提交的那一刻系统里已经有叫醒，被杀发生在任何两个事件之间都有人来发；规划器手里过时的“没活了”也抹不掉它。
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

    // ---- 复评 R12：叫醒要在提交确认**之前**登记，并且不能被规划器手里过时的“没活了”取消 ----

    /** 账上有没有欠着的活，由测试按剧本自己记（进程被杀可以发生在任何两个事件之间，所以每个事件之后都要查一遍）。 */
    private class Owed(var jobs: Boolean = false, var additions: Boolean = false) {
        val any get() = jobs || additions
    }

    /**
     * 系统里那一个叫醒（假的 JobScheduler）：状态跨“进程被杀”保留。每次登记、取消之后都查：账上欠着活，系统里就必须有叫醒——
     * 违反了就是“此刻被杀，再也没人来发”。[refusals] 次登记会被系统拒绝（抛异常）。
     */
    private class Os(private val owed: Owed) : ResumeScheduler {
        @Volatile var pending = false
            private set

        @Volatile var refusals = 0
        val calls = CopyOnWriteArrayList<String>()
        val violations = CopyOnWriteArrayList<String>()

        override fun ensureScheduled() {
            calls += "ensure"
            if (refusals > 0) {
                refusals -= 1
                throw IllegalStateException("JobScheduler refused: secret-looking-detail")
            }
            pending = true
            audit("登记之后")
        }

        override fun cancel() {
            calls += "cancel"
            pending = false
            audit("取消之后")
        }

        /** 系统把这个叫醒跑完收走了（叫醒是一次性的）。 */
        fun ran() {
            pending = false
        }

        fun audit(event: String) {
            if (owed.any && !pending) violations += "$event：账上欠着活，系统里却没有叫醒——此刻进程被杀就再也没人来发"
        }
    }

    /**
     * 发送账（内存版）外面的探针：确认落账的那一刻记下“账上开始欠追加”并立刻查一遍（紧接着被杀就是这个时刻）。
     * [beforeCommit] 在落账之前停住，用来摆“提交进行到一半”的当口。
     */
    private class CommitProbe(
        private val inner: DispatchStore,
        private val owed: Owed,
        private val os: Os,
        private val beforeCommit: suspend () -> Unit = {},
    ) : DispatchStore by inner {
        override suspend fun decide(key: JobKey, destinationId: String, choice: Choice, now: Instant): DecideResult {
            beforeCommit()
            return inner.decide(key, destinationId, choice, now).also {
                if (it == DecideResult.Decided && choice == Choice.CONFIRM) {
                    owed.additions = true
                    os.audit("确认落账之后")
                }
            }
        }
    }

    /** 与 SyncRuntime 里一样接线，只是登记经过 [gate]；[additionsOwed] 可以换成会停住的读。 */
    private fun DispatchRig.plannerWith(os: ResumeScheduler, gate: WakeGate, additionsOwed: suspend () -> Boolean = { dispatch.due().isNotEmpty() }) = ResumePlanner(
        pairing = flowOf(pairing.state),
        jobs = jobs.observe(),
        policy = flowOf(InMemoryDataFlowPolicy.GRANTED),
        scheduler = os,
        clock = clock,
        additionsOwed = additionsOwed,
        additionsChanged = dispatch.changes(),
        gate = gate,
    )

    /** 文字已全部入库、一个预览目标等着确认：没活，叫醒本来是取消的。 */
    private suspend fun DispatchRig.storedWithPreviewTarget(): Pair<JobKey, String> {
        val preview = createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = transcribed()
        jobs.update(jobs.get(key)!!.copy(state = SyncState.STORED))
        return key to preview.destinationId
    }

    @Test
    fun `确认预览：提交的那一刻系统里已经登记着叫醒（先登记、再提交），不是提交之后等规划器异步去登记`() = runTest {
        val rig = DispatchRig(this)
        val (key, destinationId) = rig.storedWithPreviewTarget()
        val owed = Owed()
        val os = Os(owed)
        val gate = WakeGate(os)
        rig.plannerWith(os, gate).start(backgroundScope)
        runCurrent()
        assertEquals("对照：没活的时候系统里没有叫醒", listOf("cancel"), os.calls.toList())
        assertFalse(os.pending)

        val user = WakeAheadDispatchStore(CommitProbe(rig.dispatch, owed, os), gate)
        assertEquals(DecideResult.Decided, user.decide(key, destinationId, Choice.CONFIRM, rig.clock.now()))

        assertEquals("确认落账的那一刻（紧接着被杀也一样）系统里已经有叫醒", emptyList<String>(), os.violations.toList())
        runCurrent()
        assertTrue("规划器随后反应过来：欠着追加，叫醒留着", os.pending)
        assertEquals(emptyList<String>(), os.violations.toList())
    }

    @Test
    fun `确认提交之后、规划器还没来得及反应进程就被杀（规划器根本没跑）：系统里已经有叫醒，下一个进程凭磁盘上的账接着发`() = runTest {
        val rig = DispatchRig(this)
        val (key, destinationId) = rig.storedWithPreviewTarget()
        val owed = Owed()
        val os = Os(owed)
        val user = WakeAheadDispatchStore(CommitProbe(rig.dispatch, owed, os), WakeGate(os))

        user.decide(key, destinationId, Choice.CONFIRM, rig.clock.now()) // 提交完成；进程在这里被杀：规划器一次也没跑过

        assertTrue("被杀的那一刻系统里有叫醒：它会把应用拉起来", os.pending)
        assertEquals(emptyList<String>(), os.violations.toList())

        rig.restart() // 下一个进程：什么都不记得，只有磁盘上的账；系统里的叫醒还在
        rig.plannerWith(os, WakeGate(os)).start(backgroundScope)
        runCurrent()
        assertTrue("新进程凭磁盘上的账看到还欠着追加：叫醒仍在", os.pending)

        rig.dispatch.gatewayHas(key, listOf(destinationId)) // 追加做完了：账上不再欠
        owed.additions = false
        runCurrent()
        assertFalse("没活了才收走", os.pending)
        assertEquals(emptyList<String>(), os.violations.toList())
    }

    @Test
    fun `规划器正在现算“没活了”的当口用户确认了：过时的取消不能盖过确认，确认之后的每个事件之间系统里都有叫醒`() = runTest {
        val rig = DispatchRig(this)
        val preview = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = rig.transcribed() // 任务排队中：有活，叫醒登记着
        val owed = Owed(jobs = true)
        val os = Os(owed)
        val gate = WakeGate(os)
        var stall: CompletableDeferred<Unit>? = null
        val reading = CompletableDeferred<Unit>()
        rig.plannerWith(os, gate) {
            val owedNow = rig.dispatch.due().isNotEmpty() // 此刻账上不欠
            stall?.let {
                reading.complete(Unit)
                it.await() // 停在这里：带着这个答案过一会儿才往下走
            }
            owedNow
        }.start(backgroundScope)
        runCurrent()
        assertTrue("对照：有活，叫醒在", os.pending)

        val release = CompletableDeferred<Unit>()
        stall = release
        owed.jobs = false
        rig.jobs.update(rig.jobs.get(key)!!.copy(state = SyncState.STORED)) // 文字入库：规划器开始现算“没活了”，读完账，停在登记之前
        runCurrent()
        assertTrue("规划器已经读完账、停在登记或取消之前", reading.isCompleted)

        val user = WakeAheadDispatchStore(CommitProbe(rig.dispatch, owed, os), gate)
        val confirm = async { user.decide(key, preview.destinationId, Choice.CONFIRM, rig.clock.now()) }
        runCurrent() // 用户恰在这个当口确认
        release.complete(Unit) // 规划器带着已经过时的“没活了”继续
        runCurrent()

        assertEquals(DecideResult.Decided, confirm.await())
        runCurrent()
        assertTrue("确认之后系统里必须有叫醒", os.pending)
        assertEquals("任何两个事件之间被杀都有叫醒", emptyList<String>(), os.violations.toList())
    }

    @Test
    fun `确认正在提交（叫醒已经预先登记）的当口规划器来了新输入：它要等提交落账之后再现算，取消不会发生在提交之前`() = runTest {
        val rig = DispatchRig(this)
        val (key, destinationId) = rig.storedWithPreviewTarget()
        val owed = Owed()
        val os = Os(owed)
        val gate = WakeGate(os)
        rig.plannerWith(os, gate).start(backgroundScope)
        runCurrent()
        assertEquals(listOf("cancel"), os.calls.toList())

        val landing = CompletableDeferred<Unit>()
        val user = WakeAheadDispatchStore(CommitProbe(rig.dispatch, owed, os) { landing.await() }, gate)
        val confirm = async { user.decide(key, destinationId, Choice.CONFIRM, rig.clock.now()) }
        runCurrent() // 叫醒已登记，决定还没落账
        assertTrue(os.pending)

        rig.jobs.update(rig.jobs.get(key)!!.copy(attempts = 1)) // 这时规划器来了新输入
        runCurrent()
        assertEquals("规划器要等用户的提交做完：此刻没有任何取消", listOf("cancel", "ensure"), os.calls.toList())

        landing.complete(Unit) // 决定落账
        runCurrent()

        assertEquals(DecideResult.Decided, confirm.await())
        assertTrue("规划器随后现算：账上已经欠着，叫醒留着", os.pending)
        assertEquals(emptyList<String>(), os.violations.toList())
    }

    @Test
    fun `预先登记被系统拒绝：用户的确认照样提交（决定不等系统），日志只有事件名，叫醒由规划器随后补上`() = runTest {
        val rig = DispatchRig(this)
        val (key, destinationId) = rig.storedWithPreviewTarget()
        val owed = Owed()
        val os = Os(owed)
        val gate = WakeGate(os, rig.log)
        rig.plannerWith(os, gate).start(backgroundScope)
        runCurrent()
        os.refusals = 1 // 下一次登记（预先登记）被系统拒绝

        val user = WakeAheadDispatchStore(CommitProbe(rig.dispatch, owed, os), gate)
        assertEquals("拒绝不拦用户的决定", DecideResult.Decided, user.decide(key, destinationId, Choice.CONFIRM, rig.clock.now()))

        assertEquals(TargetDecision.CONFIRMED, rig.dispatch.item(key)!!.targets.single().decision)
        assertTrue("日志只有事件名与异常类名：${rig.log.lines}", "resume.prewake-failed IllegalStateException" in rig.log.lines)
        assertTrue("系统给的细节不进日志：${rig.log.lines}", rig.log.lines.none { "secret" in it })
        runCurrent()
        assertTrue("规划器看到发送账变了、账上欠着追加：补上登记", os.pending)
    }

    @Test
    fun `“不发送”不欠任何追加：不为它登记叫醒`() = runTest {
        val rig = DispatchRig(this)
        val (key, destinationId) = rig.storedWithPreviewTarget()
        val owed = Owed()
        val os = Os(owed)
        val gate = WakeGate(os)
        rig.plannerWith(os, gate).start(backgroundScope)
        runCurrent()

        val user = WakeAheadDispatchStore(CommitProbe(rig.dispatch, owed, os), gate)
        assertEquals(DecideResult.Decided, user.decide(key, destinationId, Choice.SKIP, rig.clock.now()))
        runCurrent()

        assertEquals(listOf("cancel"), os.calls.toList())
        assertFalse(os.pending)
    }

    @Test
    fun `预先登记之后账上什么也没变（没有信号叫醒规划器）、系统把那个叫醒跑完收走：之后新来的活要登记新的叫醒，规划器不能以为系统里还留着上一个`() = runTest {
        val rig = DispatchRig(this)
        val owed = Owed()
        val os = Os(owed)
        val gate = WakeGate(os)
        rig.plannerWith(os, gate).start(backgroundScope)
        runCurrent() // 空账：取消

        gate.ahead { } // 提交了个寂寞（决定早就做过，账上没变）：只留下一个预先登记的叫醒
        runCurrent()
        assertTrue(os.pending)
        os.ran() // 系统叫醒了应用、一看没活、收走

        rig.transcribed() // 新文字：任务入账，规划器收到信号
        owed.jobs = true
        runCurrent()

        assertTrue("新的活要有新的叫醒", os.pending)
        assertEquals(emptyList<String>(), os.violations.toList())
    }
}
