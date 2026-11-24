package app.auri.sync.resume

import app.auri.capture.testing.FakeClock
import app.auri.sync.engine.JobKey
import app.auri.sync.engine.RetryPolicy
import app.auri.sync.engine.SyncJob
import app.auri.sync.machine.SyncState
import app.auri.sync.pairing.PairingState
import app.auri.sync.policy.DataFlowState
import app.auri.sync.testing.InMemoryDataFlowPolicy
import app.auri.sync.testing.SyncSamples
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第四轮 F4：规划器“要不要叫醒”的结论，必须在门的锁里按**磁盘上的账**现算；流里带来的快照（配对、任务表、授权）可能是它在门外排队时读到的旧值。
 *
 * 老的做法是：流发出一组快照 → 规划器带着它去等门 → 拿到锁后把这组快照与**刚读的**“欠不欠追加”拼成一个结论。
 * 用户确认预览（[WakeGate.ahead]，在锁里先登记叫醒再提交）正握着锁的时候，规划器就停在门口；这段时间里账变了，
 * 锁一放开，规划器拿着变化之前的快照得出“没活了”，把刚登记的叫醒取消——此刻进程被杀，确认过的文字就没人再来发（后面那一拍新快照来得及补登记，但被杀发生在这两拍之间）。
 *
 * 这里的“账”（[Books]）是权威，规划器的三个输入流故意比它旧一拍；每次登记、取消之后查一遍：账上有活，系统里就必须有叫醒（[Os.violations]）。
 * 设备上的叫醒本身（JobScheduler）是真机才能验证的部分（验收 README，NOT_RUN）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResumeStaleSnapshotTest {
    /** 磁盘上的账（权威）：任务表里有没有没发完的任务、发送账里欠不欠追加、授权、配对。 */
    private class Books(var activeJob: Boolean, var additionsOwed: Boolean = false, var consent: Boolean = true, var paired: Boolean = true) {
        val needed get() = paired && consent && (activeJob || additionsOwed)

        /** 用户确认的提交正握着门的锁（剧本里的那一段）：这期间任何读账都发生在门外。 */
        @Volatile var gateHeldByConfirm = false
    }

    /** 假系统里的那一个叫醒：每次登记、取消之后查——账上有活而系统里没有叫醒，就是“此刻被杀，再也没人来发”。 */
    private class Os(private val books: Books) : ResumeScheduler {
        @Volatile var pending = false
            private set

        val calls = CopyOnWriteArrayList<String>()
        val violations = CopyOnWriteArrayList<String>()

        override fun ensureScheduled() {
            calls += "ensure"
            pending = true
            audit("登记之后")
        }

        override fun cancel() {
            calls += "cancel"
            pending = false
            audit("取消之后")
        }

        private fun audit(event: String) {
            if (books.needed && !pending) violations += "$event：账上有活，系统里却没有叫醒——此刻进程被杀就再也没人来发"
        }
    }

    private val t0 = Instant.parse("2026-11-18T10:00:00Z")
    private val paired = PairingState.Paired(SyncSamples.pairedGateway())

    private fun pendingJob() = SyncJob(JobKey(SyncSamples.RECORD_ID, 1), SyncState.PENDING, attempts = 0, uncertain = false, retryAt = null, blockedReason = null, createdAt = t0, updatedAt = t0)

    /** 规划器的输入流：配对、任务表、授权，加“发送账变了”的提示。它们是账的一份拷贝，更新总是慢一拍。 */
    private class Feeds(pairing: PairingState, jobs: List<SyncJob>, policy: DataFlowState) {
        val pairing = MutableStateFlow(pairing)
        val jobs = MutableStateFlow(jobs)
        val policy = MutableStateFlow(policy)
        val additionsChanged = MutableStateFlow(0)
    }

    private class Rig(scope: TestScope, val books: Books, val feeds: Feeds, currentNeed: (suspend () -> Boolean)?) {
        val os = Os(books)
        val gate = WakeGate(os)
        val planner = ResumePlanner(
            feeds.pairing, feeds.jobs, feeds.policy, os, FakeClock(scope.testScheduler), RetryPolicy(),
            additionsOwed = { books.additionsOwed }, additionsChanged = feeds.additionsChanged, gate = gate, currentNeed = currentNeed,
        )
    }

    private fun TestScope.rig(books: Books, feeds: Feeds, currentNeed: (suspend () -> Boolean)? = { books.needed }) = Rig(this, books, feeds, currentNeed).also {
        it.planner.start(backgroundScope)
        runCurrent()
    }

    /**
     * 剧本：用户确认预览的提交正握着门的锁（叫醒已预先登记）；规划器这时来了一拍输入，带着当时的快照停在门口；
     * 提交还没做完，账变了（[change]），对应的流也更新了，但那一拍新快照排在停着的那一拍后面；提交落账、锁放开。
     */
    private suspend fun TestScope.holdTheGateWhileTheBooksChange(rig: Rig, change: () -> Unit) {
        val landing = CompletableDeferred<Unit>()
        val confirm = launch { rig.gate.ahead { landing.await() } }
        runCurrent()
        assertTrue("确认的预先登记已经登了叫醒、锁在确认手里", rig.os.pending)

        rig.books.gateHeldByConfirm = true
        rig.feeds.additionsChanged.value += 1 // 规划器来了一拍输入：带着此刻的（旧）快照去等门
        runCurrent()
        change() // 账变了，对应的流更新排在停着的这一拍后面
        rig.books.gateHeldByConfirm = false
        landing.complete(Unit)
        runCurrent()
        confirm.join()
    }

    @Test
    fun `任务表的快照过时（账上其实已经有任务要发）：规划器在门里按账现算，不会把刚登记的叫醒取消`() = runTest {
        val books = Books(activeJob = false)
        val feeds = Feeds(paired, emptyList(), InMemoryDataFlowPolicy.GRANTED)
        val rig = rig(books, feeds)
        assertEquals("起点：没活，叫醒是取消的", listOf("cancel"), rig.os.calls.toList())

        holdTheGateWhileTheBooksChange(rig) {
            books.activeJob = true
            feeds.jobs.value = listOf(pendingJob())
        }

        assertEquals("账上有活：系统里必须有叫醒", emptyList<String>(), rig.os.violations.toList())
        assertTrue(rig.os.pending)
    }

    @Test
    fun `授权的快照过时（用户刚放行文字，账上授权已开）：规划器按账现算，不拿放行之前的快照取消叫醒`() = runTest {
        val books = Books(activeJob = true, consent = false)
        val feeds = Feeds(paired, listOf(pendingJob()), DataFlowState.Closed)
        val rig = rig(books, feeds)
        assertEquals("起点：文字不许进网关，叫醒是取消的", listOf("cancel"), rig.os.calls.toList())

        holdTheGateWhileTheBooksChange(rig) {
            books.consent = true
            feeds.policy.value = InMemoryDataFlowPolicy.GRANTED
        }

        assertEquals("账上有活、授权已开：系统里必须有叫醒", emptyList<String>(), rig.os.violations.toList())
        assertTrue(rig.os.pending)
    }

    @Test
    fun `配对的快照过时（刚配上对，账上已是已配对）：规划器按账现算，不拿配对之前的快照取消叫醒`() = runTest {
        val books = Books(activeJob = true, paired = false)
        val feeds = Feeds(PairingState.NotPaired, listOf(pendingJob()), InMemoryDataFlowPolicy.GRANTED)
        val rig = rig(books, feeds)
        assertEquals("起点：没配对，叫醒是取消的", listOf("cancel"), rig.os.calls.toList())

        holdTheGateWhileTheBooksChange(rig) {
            books.paired = true
            feeds.pairing.value = paired
        }

        assertEquals("账上有活、已配对：系统里必须有叫醒", emptyList<String>(), rig.os.violations.toList())
        assertTrue(rig.os.pending)
    }

    @Test
    fun `现算要等拿到门的锁之后再读：确认握着锁的那段时间里，规划器一次账也不读`() = runTest {
        val books = Books(activeJob = false)
        val feeds = Feeds(paired, emptyList(), InMemoryDataFlowPolicy.GRANTED)
        val readsWhileTheConfirmHoldsTheGate = CopyOnWriteArrayList<Boolean>()
        val rig = rig(books, feeds, currentNeed = { books.needed.also { if (books.gateHeldByConfirm) readsWhileTheConfirmHoldsTheGate += it } })

        holdTheGateWhileTheBooksChange(rig) {
            books.activeJob = true
            feeds.jobs.value = listOf(pendingJob())
        }

        assertEquals("门外读好再等锁，等锁期间变了的账就被漏掉：规划器必须先拿到锁再读", emptyList<Boolean>(), readsWhileTheConfirmHoldsTheGate.toList())
        assertEquals("账上有活：系统里必须有叫醒", emptyList<String>(), rig.os.violations.toList())
    }

    @Test
    fun `快照说有活、账上其实没活：不登记叫醒（登记了也只是白白打扰系统一次）`() = runTest {
        val books = Books(activeJob = false)
        val feeds = Feeds(paired, listOf(pendingJob()), InMemoryDataFlowPolicy.GRANTED)
        val rig = rig(books, feeds)

        assertEquals("账是权威：账上没活，不登记", listOf("cancel"), rig.os.calls.toList())
        assertEquals(emptyList<String>(), rig.os.violations.toList())
    }

    @Test
    fun `没有传现算的读取（旧的拼法）：照旧用快照加实时的“欠不欠追加”决定，行为不变`() = runTest {
        val books = Books(activeJob = true)
        val feeds = Feeds(paired, listOf(pendingJob()), InMemoryDataFlowPolicy.GRANTED)
        val rig = rig(books, feeds, currentNeed = null)

        assertEquals(listOf("ensure"), rig.os.calls.toList())
        assertTrue(rig.os.pending)
    }
}
