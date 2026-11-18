package app.auri.capture.scheduler

import app.auri.capture.adapter.TranscriptionOutcome
import app.auri.capture.contract.ErrorCode
import app.auri.capture.contract.RevisionSource
import app.auri.capture.library.TranscriptionStatus
import app.auri.capture.machine.Outcome
import app.auri.capture.machine.TranscriptionEvent
import app.auri.capture.machine.TranscriptionEvent.MODEL_READY
import app.auri.capture.machine.TranscriptionEvent.RETRYABLE_ERROR
import app.auri.capture.machine.TranscriptionEvent.RUN_STARTED
import app.auri.capture.machine.TranscriptionState
import app.auri.capture.machine.TranscriptionState.CANCELLED
import app.auri.capture.machine.TranscriptionState.FAILED
import app.auri.capture.machine.TranscriptionState.RETRY_SCHEDULED
import app.auri.capture.machine.TranscriptionState.RUNNING
import app.auri.capture.machine.TranscriptionState.SUCCEEDED
import app.auri.capture.machine.TranscriptionState.WAITING_MODEL
import app.auri.capture.machine.TranscriptionState.WAITING_RESOURCES
import app.auri.capture.testing.BoundedSchedulerTest
import app.auri.capture.testing.FakeModelAvailability
import app.auri.capture.testing.InMemoryRecordLibrary
import app.auri.capture.testing.Samples
import app.auri.capture.testing.SchedulerRig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务落在任务库里，进程被杀之后重启要能接着干：等着的继续等、该转的继续转、被杀时正在转的要么认领结果、要么按次数重试，
 * 而且崩溃循环有上限，不会无限地“启动→转到一半被系统杀掉→重启”。
 * “进程被杀”用取消第一个调度器的整个协程作用域来模拟；第二个调度器与第一个共用 Record 库与任务库，就像重启后打开同一份数据。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SchedulerRestartTest : BoundedSchedulerTest() {
    private fun TestScope.killableScope() = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

    private fun TestScope.restarted(old: SchedulerRig, prefix: Int, scope: CoroutineScope = backgroundScope) =
        SchedulerRig(this, library = old.library, store = old.store, schedulerScope = scope, idPrefix = prefix)

    @Test
    fun `开始状态无法落盘时不运行引擎，存储恢复后才能开始`() = runTest {
        val store = FailStateStore(RUNNING)
        val rig = SchedulerRig(this, store = store)
        val id = rig.addRecord(1)
        rig.scheduler.submit(id)
        rig.settle()

        assertTrue("没有持久化尝试就不能开始推理", rig.adapter.requests.isEmpty())
        assertEquals(TranscriptionState.QUEUED, store.inner.all().single().state)
        assertEquals(0, store.inner.all().single().attempts)

        store.failState = null
        rig.scheduler.resume()
        rig.settle()
        assertEquals(SUCCEEDED, rig.jobOf(id).state)
        assertEquals(1, rig.library.get(id)!!.revisions.size)
    }

    @Test
    fun `结果已发布但成功状态未落盘，重启认领本次结果而不是之后的用户修订`() = runTest {
        val store = FailStateStore(SUCCEEDED)
        val scope = killableScope()
        val first = SchedulerRig(this, store = store, schedulerScope = scope)
        val id = first.addRecord(1)
        first.scheduler.submit(id)
        first.settle()
        assertEquals(SUCCEEDED, first.jobOf(id).state)
        assertEquals(RUNNING, store.inner.all().single().state)
        first.library.publishRevision(Samples.draft(id, text = "用户后来修改").copy(source = RevisionSource.USER_EDIT))
        scope.cancel()

        store.failState = null
        val restarted = restarted(first, 0x5D)
        restarted.scheduler.resume()
        restarted.settle()

        assertEquals(SUCCEEDED, restarted.jobOf(id).state)
        assertEquals("应认领转录产生的第一条，而不是后来的修订", 1, restarted.jobOf(id).resultRevision)
        assertTrue(restarted.adapter.requests.isEmpty())
        assertEquals(2, restarted.library.get(id)!!.revisions.size)
    }

    @Test
    fun `等着的任务在重启之后原样回来，条件好了接着转，还是同一个任务`() = runTest {
        val scope = killableScope()
        val first = SchedulerRig(this, schedulerScope = scope)
        val id = first.addRecord(1)
        first.models.missing()
        first.settle()
        first.scheduler.submit(id)
        first.settle()
        val before = first.jobOf(id)
        assertEquals(WAITING_MODEL, before.state)
        scope.cancel() // 进程被杀

        // 重启之后模型管理器还没确认模型（先当作缺失）：任务应该原样回来，继续等
        val second = SchedulerRig(this, library = first.library, store = first.store, models = FakeModelAvailability(ModelReadiness.Missing(ErrorCode.MODEL_NOT_INSTALLED)), idPrefix = 0x5D)
        second.settle()
        assertEquals("重启之后任务一个字段都没变", before, second.jobOf(id))

        second.models.ready() // 模型管理器验证完了
        second.settle()

        val done = second.jobOf(id)
        assertEquals(before.jobId, done.jobId)
        assertEquals(SUCCEEDED, done.state)
        assertEquals(listOf(MODEL_READY, RUN_STARTED, TranscriptionEvent.RESULT_TEXT), second.decisionsOf(done.jobId).map { it.trigger })
        assertEquals(1, second.adapter.requests.size)
        assertEquals(1, second.library.get(id)!!.revisions.size)
    }

    @Test
    fun `被杀时正在转、没有结果：算一次尝试，按退避重试，最终成功，只有一条 Revision`() = runTest {
        val scope = killableScope()
        val first = SchedulerRig(this, schedulerScope = scope)
        val id = first.addRecord(1)
        first.hold(id)
        first.scheduler.submit(id)
        first.settle()
        assertEquals(RUNNING, first.jobOf(id).state)
        scope.cancel()

        val second = restarted(first, 0x5D)
        second.scheduler.resume()
        second.settle()

        val waiting = second.jobOf(id)
        assertEquals("被杀的那次没有结果，按可重试处理", RETRY_SCHEDULED, waiting.state)
        assertEquals("被杀的那次算一次尝试", 1, waiting.attempts)
        assertEquals(second.clock.now().plusMillis(second.config.retryBackoffMs[0]), waiting.retryAt)
        assertEquals(RETRYABLE_ERROR to JobCause.RUN_INTERRUPTED, second.decisionsOf(waiting.jobId).single().let { it.trigger to it.cause })
        assertTrue(second.adapter.requests.isEmpty())

        second.advance(second.config.retryBackoffMs[0])

        val done = second.jobOf(id)
        assertEquals(SUCCEEDED, done.state)
        assertEquals(2, done.attempts)
        assertEquals(1, second.library.get(id)!!.revisions.size)
    }

    @Test
    fun `被杀的时候结果其实已经发布了：重启之后直接认领成功，不再转第二遍，也不会多一条 Revision`() = runTest {
        val scope = killableScope()
        val first = SchedulerRig(this, schedulerScope = scope)
        val id = first.addRecord(1)
        first.hold(id)
        first.scheduler.submit(id)
        first.settle()
        assertEquals(RUNNING, first.jobOf(id).state)
        // Revision 已经落库，但任务还没来得及记成成功，进程就没了
        first.library.publishRevision(Samples.draft(id).copy(publicationKey = first.jobOf(id).jobId))
        scope.cancel()

        val second = restarted(first, 0x5D)
        second.scheduler.resume()
        second.settle()

        val done = second.jobOf(id)
        assertEquals(SUCCEEDED, done.state)
        assertEquals(1, done.resultRevision)
        assertEquals(Samples.draft(id).modelId, done.modelId)
        assertTrue("没有再转一遍", second.adapter.requests.isEmpty())
        assertEquals(1, second.library.get(id)!!.revisions.size)
    }

    @Test
    fun `崩溃循环有上限：连续被杀 maxAttempts 次之后，下一次重启任务就是失败，不再尝试`() = runTest {
        val library = InMemoryRecordLibrary()
        val store = InMemoryJobStore()
        val config = SchedulerConfig()
        val id = Samples.uuid(1)
        library.addRecord(Samples.entry(1))

        // 每一轮：重启（第一轮是首次启动），让任务开始转，转到一半被杀
        var starts = 0
        repeat(config.maxAttempts) { round ->
            val scope = killableScope()
            val rig = SchedulerRig(this, library = library, store = store, schedulerScope = scope, idPrefix = 0x61 + round)
            rig.hold(id)
            if (round == 0) rig.scheduler.submit(id) else rig.scheduler.resume()
            rig.settle()
            rig.advance(config.retryBackoffMs.max()) // 等过上一轮留下的退避
            starts += rig.adapter.requests.size
            assertEquals("第 ${round + 1} 次尝试在转的时候被杀", RUNNING, rig.jobOf(id).state)
            scope.cancel()
        }
        assertEquals("总共只开始了 maxAttempts 次", config.maxAttempts, starts)

        val last = SchedulerRig(this, library = library, store = store, idPrefix = 0x70)
        last.scheduler.resume()
        last.advance(24 * 60 * 60 * 1000L)

        val failed = last.jobOf(id)
        assertEquals("已经被杀 ${config.maxAttempts} 次：不再试第 ${config.maxAttempts + 1} 次", FAILED, failed.state)
        assertEquals(JobCause.RUN_INTERRUPTED, last.decisionsOf(failed.jobId).single().cause)
        assertTrue(last.adapter.requests.isEmpty())
        assertEquals(TranscriptionStatus.FAILED, library.get(id)!!.transcription)
        assertTrue(library.get(id)!!.revisions.isEmpty())
    }

    @Test
    fun `等重试的任务重启之后接着计时，不是从头等`() = runTest {
        val scope = killableScope()
        val first = SchedulerRig(this, schedulerScope = scope)
        val id = first.addRecord(1)
        first.adapter.outcome = { TranscriptionOutcome.Failed(ErrorCode.ASR_RESOURCE_LIMIT, retryable = true) }
        first.scheduler.submit(id)
        first.settle()
        assertEquals(RETRY_SCHEDULED, first.jobOf(id).state)
        first.advance(10_000)
        scope.cancel()

        val second = restarted(first, 0x5D)
        second.scheduler.resume()
        second.settle()
        second.advance(first.config.retryBackoffMs[0] - 10_000 - 1)
        assertTrue("退避还没到", second.adapter.requests.isEmpty())
        second.advance(1)

        assertEquals("按原来的到期时刻重试", 1, second.adapter.requests.size)
        assertEquals(SUCCEEDED, second.jobOf(id).state)
    }

    @Test
    fun `取消了的任务和已经结束的任务，重启之后不复活`() = runTest {
        val scope = killableScope()
        val first = SchedulerRig(this, schedulerScope = scope)
        val cancelled = first.addRecord(1)
        val finished = first.addRecord(2)
        first.models.missing()
        first.settle()
        first.scheduler.submit(cancelled)
        first.settle()
        first.scheduler.cancel(first.jobOf(cancelled).jobId)
        first.settle()
        first.models.ready()
        first.scheduler.submit(finished)
        first.settle()
        assertEquals(CANCELLED, first.jobOf(cancelled).state)
        assertEquals(SUCCEEDED, first.jobOf(finished).state)
        scope.cancel()

        val second = restarted(first, 0x5D)
        second.scheduler.resume()
        second.advance(60 * 60 * 1000L)

        assertEquals(CANCELLED, second.jobOf(cancelled).state)
        assertEquals(SUCCEEDED, second.jobOf(finished).state)
        assertEquals(2, second.jobs.size)
        assertTrue(second.adapter.requests.isEmpty())
        assertEquals(TranscriptionStatus.FAILED, second.library.get(cancelled)!!.transcription)
        assertEquals(1, second.library.get(finished)!!.revisions.size)
    }

    @Test
    fun `Record 库里有没转完的、任务库里没有它（升级前的数据、任务库写失败）：恢复时补上，已完成的不碰`() = runTest {
        val library = InMemoryRecordLibrary()
        library.addRecord(Samples.entry(1)) // 没转完
        library.addRecord(Samples.entry(2))
        library.markTranscription(Samples.uuid(2), TranscriptionStatus.WAITING_MODEL, ErrorCode.MODEL_NOT_INSTALLED)
        library.addRecord(Samples.entry(3))
        library.publishRevision(Samples.draft(Samples.uuid(3))) // 已转完
        library.addRecord(Samples.entry(4))
        library.markTranscription(Samples.uuid(4), TranscriptionStatus.NO_SPEECH)
        library.addRecord(Samples.entry(5))
        library.markTranscription(Samples.uuid(5), TranscriptionStatus.FAILED, ErrorCode.ASR_RESOURCE_LIMIT)
        val rig = SchedulerRig(this, library = library)

        rig.scheduler.resume()
        rig.settle()

        assertEquals(listOf(Samples.uuid(1), Samples.uuid(2)), rig.adapter.requests.map { it.recordId })
        assertEquals(setOf(Samples.uuid(1), Samples.uuid(2)), rig.jobs.map { it.recordId }.toSet())
        assertEquals("已转完的不会再多一条 Revision", 1, library.get(Samples.uuid(3))!!.revisions.size)
        assertEquals(TranscriptionStatus.FAILED, library.get(Samples.uuid(5))!!.transcription)
    }

    @Test
    fun `任务库读写都坏了：先等待存储恢复，重启后按 Record 库对账不丢也不重`() = runTest {
        val flaky = FlakyJobStore()
        val scope = killableScope()
        val first = SchedulerRig(this, store = flaky, schedulerScope = scope)
        val finished = first.addRecord(1)
        val waiting = first.addRecord(2)
        val alreadyDone = first.addRecord(3)
        first.library.publishRevision(Samples.draft(alreadyDone))
        flaky.failing = true
        first.scheduler.submit(finished)
        first.settle()
        assertEquals("任务库写不进去不能开始推理", TranscriptionState.QUEUED, first.jobOf(finished).state)
        first.models.missing()
        first.settle()
        first.scheduler.submit(waiting)
        first.settle()

        assertEquals(WAITING_MODEL, first.jobOf(finished).state)
        assertEquals(WAITING_MODEL, first.jobOf(waiting).state)
        assertTrue(first.adapter.requests.isEmpty())
        assertTrue("写失败的次数记下来了", first.scheduler.storeFailures > 0)
        assertTrue(flaky.inner.all().isEmpty())
        scope.cancel()

        flaky.failing = false
        flaky.failReads = true // 读失败不能当作空，否则会重复运行旧任务
        val second = SchedulerRig(this, library = first.library, store = flaky, idPrefix = 0x5D)
        second.scheduler.resume()
        second.settle()
        assertTrue(second.adapter.requests.isEmpty())
        assertTrue(second.jobs.isEmpty())

        flaky.failReads = false
        second.scheduler.resume()
        second.settle()

        assertEquals("已完成的不重复转", 1, second.library.get(alreadyDone)!!.revisions.size)
        assertEquals(SUCCEEDED, second.jobOf(finished).state)
        assertEquals("等模型的那个补回了任务并转完", SUCCEEDED, second.jobOf(waiting).state)
        assertEquals(setOf(finished, waiting), second.adapter.requests.map { it.recordId }.toSet())
        assertEquals(1, second.library.get(finished)!!.revisions.size)
        assertEquals(1, second.library.get(waiting)!!.revisions.size)
    }

    @Test
    fun `resume 会让被引擎拒绝过的模型再试一次（用户装好模型、或者点重试）`() = runTest {
        val rig = SchedulerRig(this)
        val id = rig.addRecord(1)
        rig.adapter.outcome = { TranscriptionOutcome.WaitingModel(ErrorCode.MODEL_INTEGRITY_FAILED) }
        rig.scheduler.submit(id)
        rig.advance(60_000)
        assertEquals(WAITING_MODEL, rig.jobOf(id).state)
        assertEquals(1, rig.adapter.requests.size)

        rig.adapter.outcome = { TranscriptionOutcome.Text("这次模型好了", "zh", null) }
        rig.scheduler.resume()
        rig.settle()

        assertEquals(SUCCEEDED, rig.jobOf(id).state)
        assertEquals(2, rig.adapter.requests.size)
    }

    @Test
    fun `正常运行时 resume 不打扰：正在转的不会被当成被杀的，等资源的定时器不会被重置`() = runTest {
        val rig = SchedulerRig(this)
        val running = rig.addRecord(1)
        val waiting = rig.addRecord(2)
        rig.hold(running)
        rig.scheduler.submit(running)
        rig.settle()
        rig.resources.status = ResourceStatus.Low(ResourceReason.LOW_MEMORY) // 不发信号，只有定时重查
        rig.scheduler.submit(waiting)
        rig.settle()
        assertEquals(WAITING_RESOURCES, rig.jobOf(waiting).state)
        val firstRecheck = rig.config.resourceRecheckMs.first()

        rig.advance(firstRecheck / 2)
        rig.scheduler.resume() // 比如界面回到前台
        rig.settle()

        assertEquals("正在转的不动", RUNNING, rig.jobOf(running).state)
        assertTrue(rig.decisionsOf(rig.jobOf(running).jobId).none { it.cause == JobCause.RUN_INTERRUPTED })
        rig.advance(firstRecheck - firstRecheck / 2)
        assertEquals("第一次重查还是在原来的时刻，没有被 resume 推迟", 1, rig.jobOf(waiting).waitChecks)
    }

    @Test
    fun `任务库的 retryAt 已经过去很久：重启后立刻重试`() = runTest {
        val store = InMemoryJobStore()
        val library = InMemoryRecordLibrary()
        library.addRecord(Samples.entry(1))
        val clock = SchedulerRig(this, library = library, store = store)
        store.put(
            TranscriptionJob(
                jobId = "00000000-0000-4000-8000-0000000000aa",
                recordId = Samples.uuid(1),
                kind = JobKind.INITIAL,
                seq = 1,
                state = RETRY_SCHEDULED,
                createdAt = clock.clock.now().minusSeconds(3600),
                updatedAt = clock.clock.now().minusSeconds(3600),
                attempts = 1,
                baseRevisions = 0,
                retryAt = clock.clock.now().minusSeconds(3000),
                failure = ErrorCode.ASR_RESOURCE_LIMIT,
            ),
        )
        val restarted = SchedulerRig(this, library = library, store = store, idPrefix = 0x5D)

        restarted.scheduler.resume()
        restarted.settle()

        assertEquals(SUCCEEDED, restarted.jobOf(Samples.uuid(1)).state)
        assertNull(restarted.jobOf(Samples.uuid(1)).retryAt)
        assertEquals(Outcome.TRANSITION, restarted.decisionsOf("00000000-0000-4000-8000-0000000000aa").first().outcome)
    }

    @Test
    fun `旧版已停重查的等待任务重启后恢复自动重查`() = runTest {
        val store = InMemoryJobStore()
        val library = InMemoryRecordLibrary()
        val id = Samples.uuid(1)
        library.addRecord(Samples.entry(1))
        val now = java.time.Instant.parse("2026-01-01T00:00:00Z")
        store.put(TranscriptionJob(
            jobId = Samples.uuid(99), recordId = id, kind = JobKind.INITIAL, seq = 1,
            state = WAITING_RESOURCES, createdAt = now, updatedAt = now,
            wait = WaitReason.Resources(ResourceReason.LOW_MEMORY), waitChecks = 5, needsUser = true,
        ))
        val rig = SchedulerRig(this, library = library, store = store)
        rig.resources.setSilently(ResourceStatus.Low(ResourceReason.LOW_MEMORY))
        rig.scheduler.resume()
        rig.settle()
        assertFalse(rig.jobOf(id).needsUser)
        rig.resources.setSilently(ResourceStatus.Ok)

        rig.advance(300_000)

        assertEquals(SUCCEEDED, rig.jobOf(id).state)
    }

    /** 一个会读写失败的任务库。 */
    private class FlakyJobStore : JobStore {
        val inner = InMemoryJobStore()

        @Volatile
        var failing = false

        @Volatile
        var failReads = false

        override suspend fun put(job: TranscriptionJob) {
            if (failing) throw IllegalStateException("任务库写入失败（测试注入）")
            inner.put(job)
        }

        override suspend fun all(): List<TranscriptionJob> {
            if (failReads) throw IllegalStateException("任务库读取失败（测试注入）")
            return inner.all()
        }
    }

    private class FailStateStore(var failState: TranscriptionState?) : JobStore {
        val inner = InMemoryJobStore()
        override suspend fun put(job: TranscriptionJob) {
            if (job.state == failState) error("injected storage failure")
            inner.put(job)
        }
        override suspend fun all() = inner.all()
    }
}
