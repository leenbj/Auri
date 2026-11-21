@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.sync.resume

import app.auri.sync.destinations.Choice
import app.auri.sync.destinations.DecideResult
import app.auri.sync.destinations.DispatchStore
import app.auri.sync.destinations.TargetDecision
import app.auri.sync.engine.JobKey
import app.auri.sync.gateway.SendMode
import app.auri.sync.machine.SyncState
import app.auri.sync.pairing.NeedsUser
import app.auri.sync.testing.CollectingLog
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评审 H1：进程被杀之后谁来接着发，取决于“有活”期间系统那里始终登记着一个叫醒。有活的判断来自持久的账：
 * 任务表（文字还没入库）加上发送账（用户确认过预览、网关上还没有它的 Delivery）。这里测两件事：
 * 一、账上“欠追加”的结论不能比任务表旧——任务入库（STORED）本身就会让追加到期，它改的是任务表，发送账自己的信号不会响；
 * 二、提交（发布文字、用户确认）之后、叫醒登记之前进程被杀：下一个进程只凭磁盘上的账就能重新算出该有叫醒。
 * 三、（复评 R12）确认之前先登记叫醒：提交的那一刻系统里已经有叫醒，被杀发生在任何两个事件之间都有人来发；规划器手里过时的“没活了”也抹不掉它。
 * 四、（再评 S8）正在跑的叫醒不能被它自己收尾时的“没活了”带走：收尾判断与确认在同一把锁里（含旧接线的对照）；登记不上就不提交确认。
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

    /** 假系统里“此刻被杀会怎样”的检查点：账上欠着活而系统里不会有叫醒，就记一条违规。 */
    private interface Audited {
        fun audit(event: String)
    }

    /**
     * 系统里那一个叫醒（假的 JobScheduler）：状态跨“进程被杀”保留。每次登记、取消之后都查：账上欠着活，系统里就必须有叫醒——
     * 违反了就是“此刻被杀，再也没人来发”。[refusals] 次登记会被系统拒绝（抛异常）。
     */
    private class Os(private val owed: Owed) : ResumeScheduler, Audited {
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

        override fun audit(event: String) {
            if (owed.any && !pending) violations += "$event：账上欠着活，系统里却没有叫醒——此刻进程被杀就再也没人来发"
        }
    }

    /**
     * 比 [Os] 更像 JobScheduler 的假系统（再评 S8）：叫醒是个有“正在执行”阶段的任务，三个会让叫醒丢掉的平台事实照原样建模：
     * - ensureScheduled：已经有一个（含正在执行的、正在收尾的）就什么也不做——和 JobSchedulerResume 一样（getPendingJob 看得见它）；
     * - cancel：同步生效、立刻摘掉（含正在执行的），这之后 getPendingJob 就看不见它了；
     * - jobFinished(执行编号, 是否重排)：**异步**——[settle] 之前 getPendingJob 还看得见这个正在收尾的任务；对已经被摘掉的那一次执行，回调被忽略。
     * 账上欠着活的每一刻，“收尾处理完之后系统里还有没有叫醒”（[willExist]）都必须为真，否则就是“此刻被杀，再也没人来发”。
     */
    private class JobOs(private val owed: Owed) : ResumeScheduler, Audited {
        @Volatile var pending = false
            private set

        private var running = false
        private var execution = 0
        private var finishingReschedule: Boolean? = null // 收尾回调已到、系统还没处理；值 = 要不要重排
        val calls = CopyOnWriteArrayList<String>()
        val violations = CopyOnWriteArrayList<String>()

        val willExist get() = pending && finishingReschedule != false

        override fun ensureScheduled() {
            calls += "ensure"
            pending = true // 已经有就是原样（对应 getPendingJob != null 时直接返回）
            audit("登记之后")
        }

        override fun cancel() {
            calls += "cancel"
            pending = false
            running = false
            finishingReschedule = null
            execution += 1
            audit("取消之后")
        }

        /** 系统把排着的叫醒拉起来跑一轮；返回这次执行的编号（jobFinished 要带它）。 */
        fun start(): Int {
            check(pending && !running) { "没有排着的叫醒可以开跑" }
            running = true
            execution += 1
            return execution
        }

        fun jobFinished(token: Int, reschedule: Boolean) {
            if (running && token == execution) finishingReschedule = reschedule
            audit("收尾回调之后")
        }

        /** 系统处理掉排着的收尾回调。 */
        fun settle() {
            finishingReschedule?.let { reschedule ->
                pending = reschedule
                running = false
                finishingReschedule = null
            }
            audit("系统处理收尾之后")
        }

        override fun audit(event: String) {
            if (owed.any && !willExist) violations += "$event：账上欠着活，系统里却不会再有叫醒——此刻进程被杀就再也没人来发"
        }
    }

    /**
     * 发送账（内存版）外面的探针：确认落账的那一刻记下“账上开始欠追加”并立刻查一遍（紧接着被杀就是这个时刻）。
     * [beforeCommit] 在落账之前停住，用来摆“提交进行到一半”的当口。
     */
    private class CommitProbe(
        private val inner: DispatchStore,
        private val owed: Owed,
        private val os: Audited,
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

    /**
     * 再评 S8：**反转**了这条测试原来的期望。原来是“预先登记被系统拒绝，确认照样提交、叫醒由规划器随后补上”——可规划器补登记要的是同一个系统调用，
     * 系统这时多半仍然拒绝，于是账上欠着追加、应用一被杀就再没有任何东西把它送出去，而用户看到的是“已确认”。
     * 现在：登记不上就**不提交**，告诉用户没记下、请再点一次；账上什么也没变；系统恢复之后同样的确认照常落账。
     */
    @Test
    fun `预先登记被系统拒绝：确认不落账，用户得到“没记下、请重试”的结果；日志只有事件名；系统恢复后同样的确认照常落账`() = runTest {
        val rig = DispatchRig(this)
        val (key, destinationId) = rig.storedWithPreviewTarget()
        val owed = Owed()
        val os = Os(owed)
        val gate = WakeGate(os, rig.log)
        rig.plannerWith(os, gate).start(backgroundScope)
        runCurrent()
        os.refusals = 1 // 下一次登记（预先登记）被系统拒绝

        val user = WakeAheadDispatchStore(CommitProbe(rig.dispatch, owed, os), gate)
        assertEquals("登记不上就不提交：确认没有落账", DecideResult.WakeUnavailable, user.decide(key, destinationId, Choice.CONFIRM, rig.clock.now()))

        assertEquals("账上什么也没变：还在等用户", TargetDecision.AWAITING, rig.dispatch.item(key)!!.targets.single().decision)
        assertFalse("没有欠下追加", owed.additions)
        assertTrue("日志只有事件名与异常类名：${rig.log.lines}", "resume.prewake-failed IllegalStateException" in rig.log.lines)
        assertTrue("系统给的细节不进日志：${rig.log.lines}", rig.log.lines.none { "secret" in it })
        runCurrent()
        assertFalse("没有欠下的活：规划器没有理由登记", os.pending)

        assertEquals("系统恢复之后同样的确认照常落账", DecideResult.Decided, user.decide(key, destinationId, Choice.CONFIRM, rig.clock.now()))
        assertEquals(TargetDecision.CONFIRMED, rig.dispatch.item(key)!!.targets.single().decision)
        assertTrue("落账的那一刻系统里已经有叫醒", os.pending)
        assertEquals(emptyList<String>(), os.violations.toList())
    }

    @Test
    fun `“不发送”不需要叫醒：系统拒绝登记也照常落账（它不欠任何追加）`() = runTest {
        val rig = DispatchRig(this)
        val (key, destinationId) = rig.storedWithPreviewTarget()
        val owed = Owed()
        val os = Os(owed)
        val gate = WakeGate(os, rig.log)
        os.refusals = 5 // 系统一直拒绝

        val user = WakeAheadDispatchStore(CommitProbe(rig.dispatch, owed, os), gate)

        assertEquals(DecideResult.Decided, user.decide(key, destinationId, Choice.SKIP, rig.clock.now()))
        assertEquals(TargetDecision.SKIPPED, rig.dispatch.item(key)!!.targets.single().decision)
        assertEquals("根本没去登记", emptyList<String>(), os.calls.toList())
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

    // ---- 再评 S8：正在跑的叫醒不能在用户确认的当口被它自己的“没活了”带走 ----

    /** 一轮叫醒要做的事：把唯一那条文字发完（任务 STORED，账上不再欠任务），收尾判断读完账之后停住，等测试放行。 */
    private class FinishingPass(val rig: DispatchRig, val key: JobKey, val owed: Owed, val gate: WakeGate?) {
        val reading = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()

        /** 读账、停住、再用读到的（已经过时的）答案下结论——与旧接线、新接线的区别只有“在不在门的锁里”。 */
        private suspend fun conclude(): Boolean {
            val dueNow = rig.dispatch.due().isNotEmpty()
            reading.complete(Unit)
            proceed.await()
            return needsResume(rig.pairing.state, rig.jobs.all(), InMemoryDataFlowPolicy.GRANTED, dueNow)
        }

        val runner = ResumeRunner(
            ready = {},
            runPass = {
                rig.jobs.update(rig.jobs.get(key)!!.copy(state = SyncState.STORED))
                owed.jobs = false
            },
            stillNeeded = { if (gate != null) gate.finishing { conclude() } else conclude() },
        )
    }

    @Test
    fun `叫醒正在跑、收尾判断“没活了”的当口用户确认了：收尾在门的锁里现算并同步取消，确认的登记排在它后面登记新的，叫醒不会被这一轮的收尾带走`() = runTest {
        val rig = DispatchRig(this)
        val preview = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = rig.transcribed() // 有活
        val owed = Owed(jobs = true)
        val os = JobOs(owed)
        val gate = WakeGate(os)
        gate.reconcile { true } // 规划器登记了叫醒
        val execution = os.start() // 系统把它拉起来跑一轮
        val finishing = FinishingPass(rig, key, owed, gate)
        val pass = async { finishing.runner.run().also { os.jobFinished(execution, it) } }
        runCurrent()
        assertTrue("收尾判断已经读完账、停住了（锁在它手里）", finishing.reading.isCompleted)

        val user = WakeAheadDispatchStore(CommitProbe(rig.dispatch, owed, os), gate)
        val confirm = async { user.decide(key, preview.destinationId, Choice.CONFIRM, rig.clock.now()) }
        runCurrent() // 用户恰在这个当口确认
        assertFalse("门被收尾占着：确认排在它后面，还没落账", confirm.isCompleted)
        finishing.proceed.complete(Unit)
        runCurrent()

        assertFalse("这一轮收尾判断时账上确实不欠：没活", pass.await())
        assertEquals(DecideResult.Decided, confirm.await())
        os.settle() // 系统处理掉之前排着的收尾回调（对已经被摘掉的那一次执行，被忽略）
        assertTrue("确认之后系统里有叫醒", os.willExist)
        assertEquals("任何两个事件之间被杀都有叫醒", emptyList<String>(), os.violations.toList())
        assertEquals("收尾先同步取消、确认再登记新的", listOf("ensure", "cancel", "ensure"), os.calls.toList())
    }

    @Test
    fun `对照（旧接线：收尾判断不在门的锁里、没活时只 jobFinished 不取消）：同一个当口叫醒被这一轮的收尾带走，账上却欠着追加——证明假系统与测试真的能抓住这个缝`() = runTest {
        val rig = DispatchRig(this)
        val preview = rig.createDestination("Claude Code", SendMode.PREVIEW_CONFIRM)
        val key = rig.transcribed()
        val owed = Owed(jobs = true)
        val os = JobOs(owed)
        val gate = WakeGate(os)
        gate.reconcile { true }
        val execution = os.start()
        val finishing = FinishingPass(rig, key, owed, gate = null)
        val pass = async { finishing.runner.run().also { os.jobFinished(execution, it) } }
        runCurrent()
        assertTrue(finishing.reading.isCompleted)

        val user = WakeAheadDispatchStore(CommitProbe(rig.dispatch, owed, os), gate)
        assertEquals("旧接线里确认不用等收尾", DecideResult.Decided, user.decide(key, preview.destinationId, Choice.CONFIRM, rig.clock.now()))
        finishing.proceed.complete(Unit)
        runCurrent()
        assertFalse(pass.await())
        os.settle()

        assertFalse("叫醒被这一轮的收尾带走了", os.willExist)
        assertTrue("违规被记下来了：${os.violations}", os.violations.isNotEmpty())
        assertEquals("确认的预先登记看到“已经有一个”就什么也没做", listOf("ensure", "ensure"), os.calls.toList())
    }

    @Test
    fun `收尾判断有活：不动系统，请系统稍后再叫醒；门里记成“不知道”，规划器的下一次评估重新确认叫醒`() = runTest {
        val os = JobOs(Owed(jobs = true))
        val gate = WakeGate(os)
        gate.reconcile { true }
        val execution = os.start()

        val stillNeeded = gate.finishing { true }
        os.jobFinished(execution, stillNeeded)
        os.settle()

        assertTrue("有活：要求稍后再叫醒", stillNeeded)
        assertTrue("系统里还有叫醒", os.pending)
        assertEquals("收尾没有去动系统", listOf("ensure"), os.calls.toList())
        gate.reconcile { true } // 规划器随后的评估：门里不再认为“系统里有”，重新确认（已经有就是原样）
        assertEquals(listOf("ensure", "ensure"), os.calls.toList())
    }

    @Test
    fun `收尾判断没活：不信门里记的“告诉过系统什么”（这一轮可能是门不知道的任务），一律同步取消`() = runTest {
        val os = JobOs(Owed())
        val gate = WakeGate(os)
        gate.reconcile { false } // 门里记着：系统里没有叫醒
        os.ensureScheduled() // 可是系统里有一个门不知道的（持久任务重启后到点、或系统自己重排的）
        os.start()
        os.calls.clear()

        assertFalse(gate.finishing { false })

        assertEquals("门以为没有也照样取消，不能被自己的记忆骗过去", listOf("cancel"), os.calls.toList())
        assertFalse(os.pending)
    }

    @Test
    fun `收尾时取消被系统拒绝（异常）：运行器按“没得出结论”请系统稍后再叫醒，不丢叫醒；日志只有事件名与异常类名`() = runTest {
        val log = CollectingLog()
        val refusing = object : ResumeScheduler {
            override fun ensureScheduled() = Unit

            override fun cancel() = throw IllegalStateException("JobScheduler refused: secret-looking-detail")
        }
        val gate = WakeGate(refusing)
        val runner = ResumeRunner(ready = {}, runPass = {}, stillNeeded = { gate.finishing { false } }, log = log)

        assertTrue("没得出结论：当作还需要叫醒（jobFinished 要求重排）", runner.run())
        assertTrue("日志只有事件名与异常类名：${log.lines}", "resume.failed IllegalStateException" in log.lines)
        assertTrue("系统给的细节不进日志：${log.lines}", log.lines.none { "secret" in it })
    }

    @Test
    fun `启动总是重新规划：账上还欠着活而系统里什么也没有（叫醒曾被带走），新进程的第一次评估就重新登记，不信任何内存里的记忆`() = runTest {
        val rig = DispatchRig(this)
        val (key, destinationId) = rig.storedWithPreviewTarget()
        assertEquals(DecideResult.Decided, rig.dispatch.decide(key, destinationId, Choice.CONFIRM, rig.clock.now())) // 账上欠着追加
        val os = JobOs(Owed(additions = true)) // 系统里什么也没有

        rig.restart()
        rig.plannerWith(os, WakeGate(os)).start(backgroundScope)
        runCurrent()

        assertEquals(listOf("ensure"), os.calls.toList())
        assertTrue(os.willExist)
        assertEquals(emptyList<String>(), os.violations.toList())
    }

    // ---- 再评 S7：网关太旧期间叫醒留着，升级之后被叫醒的那一轮自己恢复 ----

    @Test
    fun `网关太旧期间进程被杀：重启后叫醒仍然登记着；用户更新网关之后被叫醒的一轮自己恢复、发完，发完才不再需要叫醒`() = runTest {
        val rig = DispatchRig(this)
        rig.server.supportedVersions = listOf("1.1")
        val key = rig.transcribed()
        rig.engine.runOnce() // 问出网关太旧：文字退回等待，配对记录打上标记
        assertEquals(NeedsUser.GATEWAY_TOO_OLD, rig.pairing.gateway!!.needsUser)
        assertEquals(SyncState.WAITING_GATEWAY, rig.jobs.get(key)!!.state)

        rig.restart() // 进程被杀：什么都不记得，只有磁盘上的任务表与配对记录
        val wakes = start(rig)
        assertEquals("网关太旧也要留着叫醒：以前这里取消了，升级之后没人再来问，要等用户打开应用", listOf("ensure"), wakes.calls.toList())

        rig.server.supportedVersions = listOf("1.2") // 用户更新了网关
        rig.engine.runOnce() // 系统叫醒后跑的那一轮：开头问出已经更新、清掉标记，照常发

        assertEquals(SyncState.STORED, rig.jobs.get(key)!!.state)
        assertNull("标记自己清掉了，不需要重新配对", rig.pairing.gateway!!.needsUser)
        assertFalse(
            "发完了：收尾判断是不必再叫醒",
            needsResume(rig.pairing.state, rig.jobs.all(), InMemoryDataFlowPolicy.GRANTED, rig.dispatch.due().isNotEmpty()),
        )
    }
}
