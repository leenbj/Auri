package app.auri.capture.scheduler

import app.auri.capture.adapter.TranscriptionOutcome
import app.auri.capture.contract.CancelSupport
import app.auri.capture.contract.ErrorCode
import app.auri.capture.library.TranscriptionStatus
import app.auri.capture.machine.Outcome
import app.auri.capture.machine.TranscriptionEvent.CANCEL
import app.auri.capture.machine.TranscriptionEvent.RESULT_TEXT
import app.auri.capture.machine.TranscriptionState.CANCELLED
import app.auri.capture.machine.TranscriptionState.QUEUED
import app.auri.capture.machine.TranscriptionState.RETRY_SCHEDULED
import app.auri.capture.machine.TranscriptionState.RUNNING
import app.auri.capture.machine.TranscriptionState.SUCCEEDED
import app.auri.capture.machine.TranscriptionState.WAITING_MODEL
import app.auri.capture.machine.TranscriptionState.WAITING_RESOURCES
import app.auri.capture.testing.BoundedSchedulerTest
import app.auri.capture.testing.Samples
import app.auri.capture.testing.SchedulerRig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 取消：任何还没结束的任务都能取消，取消之后不会再有任何副作用——不开始、不重试、不发布 Revision。
 * 最难的是运行中：原生推理不能中途停，所以任务立刻是“已取消”，但转录名额要等那次调用真的返回才释放，迟到的结果被状态机拒绝。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SchedulerCancelTest : BoundedSchedulerTest() {
    private fun SchedulerRig.cancelAndSettle(recordId: String) {
        scheduler.cancel(jobOf(recordId).jobId)
        settle()
    }

    @Test
    fun `排队中、等模型、等资源、等重试：取消之后就是已取消，再久也不会开始或重试`() = runTest {
        // 排队中：被另一个正在转的任务挡在后面
        val rig = SchedulerRig(this)
        val running = rig.addRecord(1)
        val queued = rig.addRecord(2)
        val gate = rig.hold(running)
        rig.scheduler.submit(running)
        rig.scheduler.submit(queued)
        rig.settle()
        assertEquals(QUEUED, rig.jobOf(queued).state)
        rig.cancelAndSettle(queued)
        assertEquals(CANCELLED, rig.jobOf(queued).state)
        gate.complete(TranscriptionOutcome.Text("第一段", "zh", null))
        rig.advance(60 * 60 * 1000L)
        assertEquals("取消了的排队任务没有被开始", listOf(running), rig.adapter.requests.map { it.recordId })

        // 等模型
        val modelRig = SchedulerRig(this)
        val waitingModel = modelRig.addRecord(1)
        modelRig.models.missing()
        modelRig.settle()
        modelRig.scheduler.submit(waitingModel)
        modelRig.settle()
        assertEquals(WAITING_MODEL, modelRig.jobOf(waitingModel).state)
        modelRig.cancelAndSettle(waitingModel)
        modelRig.models.ready()
        modelRig.advance(60 * 60 * 1000L)
        assertEquals("模型装好之后取消了的任务也不复活", CANCELLED, modelRig.jobOf(waitingModel).state)
        assertTrue(modelRig.adapter.requests.isEmpty())

        // 等资源（带着重查定时器）
        val resourceRig = SchedulerRig(this)
        val waitingResources = resourceRig.addRecord(1)
        resourceRig.resources.status = ResourceStatus.Low(ResourceReason.THERMAL)
        resourceRig.scheduler.submit(waitingResources)
        resourceRig.settle()
        assertEquals(WAITING_RESOURCES, resourceRig.jobOf(waitingResources).state)
        resourceRig.cancelAndSettle(waitingResources)
        resourceRig.resources.setSilently(ResourceStatus.Ok)
        val checksAtCancel = resourceRig.resources.checks.get()
        resourceRig.advance(60 * 60 * 1000L)
        assertEquals(CANCELLED, resourceRig.jobOf(waitingResources).state)
        assertEquals("取消之后重查定时器作废，没有再查资源", checksAtCancel, resourceRig.resources.checks.get())
        assertTrue(resourceRig.adapter.requests.isEmpty())

        // 等重试（带着退避定时器）
        val retryRig = SchedulerRig(this)
        val retrying = retryRig.addRecord(1)
        retryRig.adapter.outcome = { TranscriptionOutcome.Failed(ErrorCode.ASR_RESOURCE_LIMIT, retryable = true) }
        retryRig.scheduler.submit(retrying)
        retryRig.settle()
        assertEquals(RETRY_SCHEDULED, retryRig.jobOf(retrying).state)
        retryRig.cancelAndSettle(retrying)
        retryRig.advance(60 * 60 * 1000L)
        assertEquals(CANCELLED, retryRig.jobOf(retrying).state)
        assertEquals("退避定时器作废，没有再试", 1, retryRig.adapter.requests.size)
        assertEquals(
            listOf(CANCEL to Outcome.TRANSITION),
            retryRig.decisionsOf(retryRig.jobOf(retrying).jobId).takeLast(1).map { it.trigger to it.outcome },
        )
    }

    @Test
    fun `运行中取消，引擎停不下来：任务立刻是已取消，名额要等调用返回才释放，迟到的文字不会变成 Revision`() = runTest {
        val rig = SchedulerRig(this)
        val first = rig.addRecord(1)
        val second = rig.addRecord(2)
        val gate = rig.hold(first) // 原生推理：协程取消拦不住它
        rig.scheduler.submit(first)
        rig.scheduler.submit(second)
        rig.settle()
        assertEquals(RUNNING, rig.jobOf(first).state)

        rig.cancelAndSettle(first)

        assertEquals("取消立刻生效", CANCELLED, rig.jobOf(first).state)
        assertEquals("原生调用还没返回，名额还占着：第二个不能开始", QUEUED, rig.jobOf(second).state)
        assertEquals(1, rig.adapter.maxConcurrent)

        gate.complete(TranscriptionOutcome.Text("取消之后才算出来的文字", "zh", null))
        rig.settle()

        assertEquals("迟到的结果没有改变已取消的任务", CANCELLED, rig.jobOf(first).state)
        val firstRecord = rig.library.get(first)!!
        assertTrue("没有 Revision", firstRecord.revisions.isEmpty())
        assertFalse("Record 没有被标成已转录", firstRecord.transcription == TranscriptionStatus.DONE)
        val late = rig.decisionsOf(rig.jobOf(first).jobId).last()
        assertEquals("迟到的结果在日志里是被拒绝的", RESULT_TEXT to Outcome.REJECTED, late.trigger to late.outcome)
        assertEquals("调用返回之后名额才释放，第二个才开始并完成", SUCCEEDED, rig.jobOf(second).state)
        assertEquals(1, rig.adapter.maxConcurrent)
    }

    @Test
    fun `运行中取消，引擎能中途停：调用被打断，名额立刻释放，下一个马上开始`() = runTest {
        val rig = SchedulerRig(this)
        rig.adapter.capabilities = Samples.capabilities.copy(cancelSupport = CancelSupport.IMMEDIATE)
        val first = rig.addRecord(1)
        val second = rig.addRecord(2)
        rig.hold(first, cancellable = true)
        rig.scheduler.submit(first)
        rig.scheduler.submit(second)
        rig.settle()

        rig.cancelAndSettle(first)

        assertEquals(CANCELLED, rig.jobOf(first).state)
        assertEquals(SUCCEEDED, rig.jobOf(second).state)
        assertTrue(rig.library.get(first)!!.revisions.isEmpty())
    }

    @Test
    fun `取消一个已经结束的任务、或者不存在的任务：什么都不改，已结束的记为被忽略`() = runTest {
        val rig = SchedulerRig(this)
        val id = rig.addRecord(1)
        rig.scheduler.submit(id)
        rig.settle()
        val done = rig.jobOf(id)
        assertEquals(SUCCEEDED, done.state)

        rig.scheduler.cancel(done.jobId)
        rig.scheduler.cancel("00000000-0000-4000-8000-000000000000")
        rig.settle()

        assertEquals("已结束的任务不会被取消改写", done, rig.jobOf(id))
        val last = rig.decisionsOf(done.jobId).last()
        assertEquals(CANCEL to Outcome.IGNORED, last.trigger to last.outcome)
        assertEquals(1, rig.library.get(id)!!.revisions.size)
    }

    @Test
    fun `取消的首次转录：Record 不再显示转录中，音频仍在`() = runTest {
        val rig = SchedulerRig(this)
        val id = rig.addRecord(1)
        rig.models.missing()
        rig.settle()
        rig.scheduler.submit(id)
        rig.settle()

        rig.cancelAndSettle(id)

        val record = rig.library.get(id)!!
        assertEquals(TranscriptionStatus.FAILED, record.transcription)
        assertEquals(null, record.failureCode)
        assertEquals(Samples.audio(id), record.audio)
    }
}
