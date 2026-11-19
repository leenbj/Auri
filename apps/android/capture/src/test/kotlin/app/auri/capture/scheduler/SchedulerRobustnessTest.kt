package app.auri.capture.scheduler

import app.auri.capture.adapter.TranscriptionOutcome
import app.auri.capture.adapter.TranscriptionAdapter
import app.auri.capture.contract.ErrorCode
import app.auri.capture.library.RecordEntry
import app.auri.capture.library.RecordLibrary
import app.auri.capture.library.TranscriptionStatus
import app.auri.capture.machine.TranscriptionState.CANCELLED
import app.auri.capture.machine.TranscriptionState.FAILED
import app.auri.capture.machine.TranscriptionState.QUEUED
import app.auri.capture.machine.TranscriptionState.SUCCEEDED
import app.auri.capture.machine.TranscriptionState.WAITING_RESOURCES
import app.auri.capture.testing.BoundedSchedulerTest
import app.auri.capture.testing.FakeClock
import app.auri.capture.testing.FakeTranscriptionAdapter
import app.auri.capture.testing.InMemoryRecordLibrary
import app.auri.capture.testing.Samples
import app.auri.capture.testing.SchedulerRig
import app.auri.capture.testing.SequentialIds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 调度器依赖的东西（Record 库、适配器）出故障时：绝不能把用户的转录悄悄取消或卡死整个队列。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SchedulerRobustnessTest : BoundedSchedulerTest() {
    @Test
    fun `资源监听尚未注册完成时不能开始推理`() = runTest {
        val registration = CompletableDeferred<Unit>()
        val monitor = object : ResourceMonitor {
            override fun check() = ResourceStatus.Ok
            override val changes = flow<Unit> {
                registration.await()
                error("injected registration failure")
            }
        }
        val rig = SchedulerRig(this, monitor = monitor)
        val id = rig.addRecord(1)
        rig.scheduler.submit(id)
        rig.settle()
        assertTrue("资源状态尚不可知，不应开始推理", rig.adapter.requests.isEmpty())

        registration.complete(Unit)
        rig.settle()
        assertEquals(WAITING_RESOURCES, rig.jobOf(id).state)
    }

    @Test
    fun `资源变化流抛错时等待并重订阅，异常不逃到未捕获处理器`() = runTest {
        var broken = true
        val uncaught = mutableListOf<Throwable>()
        val schedulerScope = CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, error -> uncaught += error },
        )
        val monitor = object : ResourceMonitor {
            override fun check() = ResourceStatus.Ok
            override val changes = flow {
                if (broken) error("injected registration failure")
                emit(Unit)
                awaitCancellation()
            }
        }
        try {
            val rig = SchedulerRig(this, schedulerScope = schedulerScope, monitor = monitor)
            val id = rig.addRecord(1)
            rig.settle()
            rig.scheduler.submit(id)
            rig.settle()

            assertEquals(WAITING_RESOURCES, rig.jobOf(id).state)
            assertTrue(rig.adapter.requests.isEmpty())
            assertTrue(uncaught.isEmpty())

            broken = false
            rig.advance(rig.config.resourceRecheckMs.first())
            assertEquals(SUCCEEDED, rig.jobOf(id).state)
        } finally {
            schedulerScope.cancel()
        }
    }

    /** Record 库的包装：[unavailable] 为 true 时读取失败。 */
    private class FlakyLibrary(val inner: InMemoryRecordLibrary = InMemoryRecordLibrary()) : RecordLibrary by inner {
        @Volatile
        var unavailable = false

        @Volatile
        var markFailure: RuntimeException? = null

        /** 这些 Record 在库里“没有了”（被删）。 */
        val hidden: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        override suspend fun get(recordId: String): RecordEntry? {
            if (unavailable) throw IllegalStateException("Record 库暂时读不了（测试注入）")
            return if (recordId in hidden) null else inner.get(recordId)
        }

        override suspend fun markTranscription(recordId: String, status: TranscriptionStatus, failureCode: ErrorCode?) {
            markFailure?.let { throw it }
            inner.markTranscription(recordId, status, failureCode)
        }
    }

    @Test
    fun `Record 库暂时读不了：任务留在排队，不被取消；库好了之后接着转`() = runTest {
        val library = FlakyLibrary()
        val rig = SchedulerRig(this, library = library)
        val id = rig.addRecord(1)
        // 提交的时候库是好的（任务建出来了），开始转之前库坏了
        rig.resources.low(ResourceReason.RECORDING_ACTIVE)
        rig.settle()
        rig.scheduler.submit(id)
        rig.settle()
        library.unavailable = true
        rig.resources.ok()
        rig.settle()

        assertEquals("读不了不等于没有：任务还在排队", QUEUED, rig.jobOf(id).state)
        assertTrue(rig.adapter.requests.isEmpty())

        library.unavailable = false
        rig.scheduler.resume()
        rig.settle()

        assertEquals(SUCCEEDED, rig.jobOf(id).state)
    }

    @Test
    fun `Record 被删了：任务取消，不堵住后面的任务`() = runTest {
        val library = FlakyLibrary()
        val rig = SchedulerRig(this, library = library)
        val gone = rig.addRecord(1)
        val next = rig.addRecord(2)
        rig.resources.low(ResourceReason.RECORDING_ACTIVE)
        rig.settle()
        rig.scheduler.submit(gone)
        rig.scheduler.submit(next)
        rig.settle()
        library.hidden += gone

        rig.resources.ok()
        rig.settle()

        assertEquals(CANCELLED, rig.jobOf(gone).state)
        assertEquals(JobCause.RECORD_GONE, rig.decisionsOf(rig.jobOf(gone).jobId).last().cause)
        assertEquals("后面的任务照常转", SUCCEEDED, rig.jobOf(next).state)
        assertEquals(listOf(next), rig.adapter.requests.map { it.recordId })
    }

    @Test
    fun `写 Record 的转录状态失败：任务照常走完，不受影响`() = runTest {
        val library = FlakyLibrary()
        val rig = SchedulerRig(this, library = library)
        val id = rig.addRecord(1)
        library.markFailure = IllegalStateException("写状态失败（测试注入）")
        rig.models.missing()
        rig.settle()

        rig.scheduler.submit(id)
        rig.settle()
        rig.models.ready()
        rig.settle()

        assertEquals(SUCCEEDED, rig.jobOf(id).state)
        assertEquals(1, library.get(id)!!.revisions.size)
    }

    @Test
    fun `适配器连能力都读不出来：按一次一个处理，调度器不崩溃，任务照常转完`() = runTest {
        val library = InMemoryRecordLibrary()
        library.addRecord(Samples.entry(1))
        val failing = object : TranscriptionAdapter by FakeTranscriptionAdapter() {
            override suspend fun capabilities() = throw IllegalStateException("读能力失败（测试注入）")
        }
        val scheduler = TranscriptionScheduler(library, failing, FakeClock(testScheduler), backgroundScope, ids = SequentialIds(0x66))

        scheduler.submit(Samples.uuid(1))
        testScheduler.runCurrent()

        assertEquals(SUCCEEDED, scheduler.jobs.value.single().state)
    }

    @Test
    fun `重转录没有 Revision 的失败 Record：转录期间显示进行中，成功后变成已转录`() = runTest {
        val rig = SchedulerRig(this)
        val id = rig.addRecord(1)
        rig.adapter.outcome = { TranscriptionOutcome.Failed(ErrorCode.ASR_RESOURCE_LIMIT) }
        rig.scheduler.submit(id)
        rig.settle()
        assertEquals(FAILED, rig.jobOf(id).state)
        val gate = rig.hold(id)
        rig.adapter.requests.clear()

        rig.scheduler.retranscribe(id)
        rig.settle()

        val whileRunning = rig.library.get(id)!!
        assertEquals(TranscriptionStatus.PENDING, whileRunning.transcription)
        assertNull(whileRunning.failureCode)

        gate.complete(TranscriptionOutcome.Text("这次转出来了", "zh", null))
        rig.settle()

        val done = rig.library.get(id)!!
        assertEquals(TranscriptionStatus.DONE, done.transcription)
        assertNull(done.failureCode)
        assertEquals(1, done.revisions.size)
    }
}
