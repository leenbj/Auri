package app.auri.capture.scheduler

import app.auri.capture.adapter.TranscriptionOutcome
import app.auri.capture.contract.ErrorCode
import app.auri.capture.library.TranscriptionStatus
import app.auri.capture.machine.TranscriptionEvent
import app.auri.capture.machine.TranscriptionEvent.MODEL_MISSING
import app.auri.capture.machine.TranscriptionEvent.PERMANENT_ERROR
import app.auri.capture.machine.TranscriptionEvent.RETRYABLE_ERROR
import app.auri.capture.machine.TranscriptionEvent.RETRY_DUE
import app.auri.capture.machine.TranscriptionEvent.RUN_STARTED
import app.auri.capture.machine.TranscriptionState.FAILED
import app.auri.capture.machine.TranscriptionState.RETRY_SCHEDULED
import app.auri.capture.machine.TranscriptionState.SUCCEEDED
import app.auri.capture.machine.TranscriptionState.WAITING_MODEL
import app.auri.capture.testing.BoundedSchedulerTest
import app.auri.capture.testing.Samples
import app.auri.capture.testing.SchedulerRig
import java.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 失败怎么处理：引擎说“可以重试”的才按有界的退避重试，次数用完就是失败；永久失败和适配器自己的 bug 直接失败；
 * 引擎说模型不可用时任务去等模型（而不是反复撞同一个坏模型）。每一种都不会无限重试。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SchedulerRetryTest : BoundedSchedulerTest() {
    private val transient = TranscriptionOutcome.Failed(ErrorCode.ASR_RESOURCE_LIMIT, retryable = true)

    @Test
    fun `可重试的失败：先等一段退避再重试，重试成功就是成功，失败的那次不留下 Revision`() = runTest {
        val rig = SchedulerRig(this)
        val id = rig.addRecord(1)
        var calls = 0
        rig.adapter.outcome = { if (++calls == 1) transient else TranscriptionOutcome.Text("重试之后转出的文字", "zh", null) }

        rig.scheduler.submit(id)
        rig.settle()

        val waiting = rig.jobOf(id)
        assertEquals(RETRY_SCHEDULED, waiting.state)
        assertEquals(1, waiting.attempts)
        assertEquals(ErrorCode.ASR_RESOURCE_LIMIT, waiting.failure)
        assertEquals("退避的到期时刻是失败的此刻加上第一档退避", rig.clock.now().plus(Duration.ofMillis(rig.config.retryBackoffMs[0])), waiting.retryAt)
        assertEquals("重试等待期间 Record 仍然是转录中", TranscriptionStatus.PENDING, rig.library.get(id)!!.transcription)
        assertTrue(rig.library.get(id)!!.revisions.isEmpty())

        rig.advance(rig.config.retryBackoffMs[0] - 1)
        assertEquals("退避没到不会提前重试", 1, rig.adapter.requests.size)
        rig.advance(1)

        val done = rig.jobOf(id)
        assertEquals(SUCCEEDED, done.state)
        assertEquals(2, done.attempts)
        assertNull("成功之后不再带着上一次的失败原因", done.failure)
        assertNull(done.retryAt)
        assertEquals(
            listOf(RUN_STARTED, RETRYABLE_ERROR, RETRY_DUE, RUN_STARTED, TranscriptionEvent.RESULT_TEXT),
            rig.decisionsOf(done.jobId).map { it.trigger },
        )
        assertEquals(1, rig.library.get(id)!!.revisions.size)
    }

    @Test
    fun `一直失败：尝试次数有界，退避逐档拉长，最后一次就是失败并带原因，之后再久也不再试`() = runTest {
        val rig = SchedulerRig(this)
        val id = rig.addRecord(1)
        rig.adapter.outcome = { transient }
        val backoff = rig.config.retryBackoffMs

        rig.scheduler.submit(id)
        rig.settle()
        assertEquals(1, rig.adapter.requests.size)

        rig.advance(backoff[0])
        assertEquals("第二次尝试", 2, rig.adapter.requests.size)
        assertEquals(RETRY_SCHEDULED, rig.jobOf(id).state)
        rig.advance(backoff[1] - 1)
        assertEquals("第二档退避比第一档长，没到不试", 2, rig.adapter.requests.size)
        rig.advance(1)

        val failed = rig.jobOf(id)
        assertEquals("用完 maxAttempts 次就失败", rig.config.maxAttempts, rig.adapter.requests.size)
        assertEquals(FAILED, failed.state)
        assertEquals(ErrorCode.ASR_RESOURCE_LIMIT, failed.failure)
        assertEquals(PERMANENT_ERROR, rig.decisionsOf(failed.jobId).last().trigger)
        assertEquals(TranscriptionStatus.FAILED, rig.library.get(id)!!.transcription)
        assertEquals(ErrorCode.ASR_RESOURCE_LIMIT, rig.library.get(id)!!.failureCode)
        assertTrue(rig.library.get(id)!!.revisions.isEmpty())

        rig.advance(7 * 24 * 60 * 60 * 1000L)

        assertEquals("失败之后没有任何自动重试", rig.config.maxAttempts, rig.adapter.requests.size)
    }

    @Test
    fun `引擎说不可重试：直接失败，一次都不重试`() = runTest {
        val rig = SchedulerRig(this)
        val id = rig.addRecord(1)
        rig.adapter.outcome = { TranscriptionOutcome.Failed(ErrorCode.ASR_RESOURCE_LIMIT) }

        rig.scheduler.submit(id)
        rig.advance(60 * 60 * 1000L)

        assertEquals(FAILED, rig.jobOf(id).state)
        assertEquals(1, rig.adapter.requests.size)
        assertEquals(listOf(RUN_STARTED, PERMANENT_ERROR), rig.decisionsOf(rig.jobOf(id).jobId).map { it.trigger })
    }

    @Test
    fun `转录适配器抛异常是 bug 不是引擎的结果：直接失败不重试，排在后面的任务照常转`() = runTest {
        val rig = SchedulerRig(this)
        val first = rig.addRecord(1)
        val second = rig.addRecord(2)
        rig.adapter.outcome = { if (it.recordId == first) throw IllegalStateException("适配器的 bug（测试注入）") else TranscriptionOutcome.Text("第二段", "zh", null) }

        rig.scheduler.submit(first)
        rig.scheduler.submit(second)
        rig.advance(60 * 60 * 1000L)

        assertEquals(FAILED, rig.jobOf(first).state)
        assertEquals(SUCCEEDED, rig.jobOf(second).state)
        assertEquals("异常的那个只试了一次", listOf(first, second), rig.adapter.requests.map { it.recordId })
    }

    @Test
    fun `发布 Revision 失败：任务失败，Record 里没有半截 Revision，下一个任务照常`() = runTest {
        var fail = true
        val library = app.auri.capture.testing.InMemoryRecordLibrary { if (fail) throw IllegalStateException("发布步骤失败（测试注入）") }
        val rig = SchedulerRig(this, library = library)
        val first = rig.addRecord(1)

        rig.scheduler.submit(first)
        rig.settle()

        assertEquals(FAILED, rig.jobOf(first).state)
        assertTrue(library.get(first)!!.revisions.isEmpty())
        fail = false
        val second = rig.addRecord(2)
        rig.scheduler.submit(second)
        rig.settle()
        assertEquals(SUCCEEDED, rig.jobOf(second).state)
    }

    @Test
    fun `引擎说模型不可用：任务去等模型并记下原因，不再反复撞同一个坏模型；模型换新之后继续`() = runTest {
        val rig = SchedulerRig(this)
        val id = rig.addRecord(1)
        rig.adapter.outcome = { TranscriptionOutcome.WaitingModel(ErrorCode.MODEL_INTEGRITY_FAILED) }

        rig.scheduler.submit(id)
        rig.advance(60 * 60 * 1000L)

        val waiting = rig.jobOf(id)
        assertEquals(WAITING_MODEL, waiting.state)
        assertEquals(WaitReason.Model(ErrorCode.MODEL_INTEGRITY_FAILED), waiting.wait)
        assertEquals("模型没被用上，不算一次尝试", 0, waiting.attempts)
        assertEquals("只撞了一次，之后没有空转", 1, rig.adapter.requests.size)
        assertEquals(listOf(RUN_STARTED, RETRYABLE_ERROR, RETRY_DUE, MODEL_MISSING), rig.decisionsOf(waiting.jobId).map { it.trigger })
        val record = rig.library.get(id)!!
        assertEquals(TranscriptionStatus.WAITING_MODEL, record.transcription)
        assertEquals(ErrorCode.MODEL_INTEGRITY_FAILED, record.failureCode)
        assertEquals("录音保留", Samples.audio(id), record.audio)

        rig.adapter.outcome = { TranscriptionOutcome.Text("换了新模型之后的文字", "zh", null) }
        rig.models.ready() // 模型管理器装好了新模型：generation 变了
        rig.settle()

        assertEquals(SUCCEEDED, rig.jobOf(id).state)
        assertEquals(TranscriptionStatus.DONE, rig.library.get(id)!!.transcription)
    }
}
