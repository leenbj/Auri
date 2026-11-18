package app.auri.capture.scheduler

import app.auri.capture.adapter.TranscriptionOutcome
import app.auri.capture.contract.ErrorCode
import app.auri.capture.contract.RevisionSource
import app.auri.capture.contract.ReviewState
import app.auri.capture.library.TranscriptionStatus
import app.auri.capture.machine.TranscriptionState.FAILED
import app.auri.capture.machine.TranscriptionState.NO_SPEECH
import app.auri.capture.machine.TranscriptionState.SUCCEEDED
import app.auri.capture.machine.TranscriptionState.WAITING_MODEL
import app.auri.capture.machine.TranscriptionState.WAITING_RESOURCES
import app.auri.capture.testing.BoundedSchedulerTest
import app.auri.capture.testing.Samples
import app.auri.capture.testing.SchedulerRig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重新转录（FR-TXT-002）：用户换了模型或想再转一次。结果永远是一条**新**的 Revision，写明这次用的模型；
 * 已发布的 Revision 不可变，一个字都不动；重转录没转出东西（没有语音、失败）也不能伤到已有的文字。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SchedulerRetranscribeTest : BoundedSchedulerTest() {
    private val newerModel = Samples.capabilities.copy(modelId = "newer-model", modelVersion = "2.0.0")

    @Test
    fun `重转录是新任务、新 Revision：写明这次用的模型，旧 Revision 一个字都没动`() = runTest {
        val rig = SchedulerRig(this)
        val id = rig.addRecord(1)
        rig.adapter.outcome = { TranscriptionOutcome.Text("第一次转出的文字", "zh", null) }
        rig.scheduler.submit(id)
        rig.settle()
        val firstJob = rig.jobOf(id)
        val firstRevision = rig.library.get(id)!!.revisions.single()
        assertEquals("fixture-model", firstRevision.modelId)

        rig.adapter.capabilities = newerModel // 用户换了模型
        rig.adapter.outcome = { TranscriptionOutcome.Text("第二次转出的文字", "zh", null) }
        rig.scheduler.retranscribe(id)
        rig.settle()

        val record = rig.library.get(id)!!
        assertEquals("旧的还在，新的追加在后面", 2, record.revisions.size)
        assertEquals("旧 Revision 完全没动", firstRevision, record.revisions[0])
        val second = record.revisions[1]
        assertEquals(2, second.number)
        assertEquals("第二次转出的文字", second.text)
        assertEquals("写明这次用的是哪个模型", "newer-model" to "2.0.0", second.modelId to second.modelVersion)
        assertEquals(RevisionSource.ASR, second.source)
        assertEquals(ReviewState.NOT_REVIEWED, second.reviewState)
        assertEquals(TranscriptionStatus.DONE, record.transcription)

        val jobs = rig.jobs.filter { it.recordId == id }
        assertEquals("两个任务：旧任务的记录还在，没被改写", 2, jobs.size)
        assertEquals(firstJob, jobs.single { it.jobId == firstJob.jobId })
        val rerun = jobs.single { it.kind == JobKind.RETRANSCRIBE }
        assertNotEquals(firstJob.jobId, rerun.jobId)
        assertEquals(SUCCEEDED, rerun.state)
        assertEquals(2, rerun.resultRevision)
        assertEquals("newer-model" to "2.0.0", rerun.modelId to rerun.modelVersion)
    }

    @Test
    fun `重转录没有语音：不加文字、不加 Revision，已有的文字和已转录状态都在`() = runTest {
        val rig = SchedulerRig(this)
        val id = rig.addRecord(1)
        rig.scheduler.submit(id)
        rig.settle()
        val before = rig.library.get(id)!!

        rig.adapter.outcome = { TranscriptionOutcome.NoSpeech }
        rig.scheduler.retranscribe(id)
        rig.settle()

        assertEquals(NO_SPEECH, rig.jobs.single { it.kind == JobKind.RETRANSCRIBE }.state)
        assertEquals("Record 原样", before, rig.library.get(id)!!)
    }

    @Test
    fun `重转录失败：任务失败，已有的文字和状态不受影响`() = runTest {
        val rig = SchedulerRig(this)
        val id = rig.addRecord(1)
        rig.scheduler.submit(id)
        rig.settle()
        val before = rig.library.get(id)!!

        rig.adapter.outcome = { TranscriptionOutcome.Failed(ErrorCode.ASR_RESOURCE_LIMIT) }
        rig.scheduler.retranscribe(id)
        rig.settle()

        val rerun = rig.jobs.single { it.kind == JobKind.RETRANSCRIBE }
        assertEquals(FAILED, rerun.state)
        assertEquals(ErrorCode.ASR_RESOURCE_LIMIT, rerun.failure)
        assertEquals("Record 的转录状态没有因为重转录失败而变成失败", before, rig.library.get(id)!!)
    }

    @Test
    fun `重转录在等模型或等资源时，Record 仍是已转录；条件好了才转`() = runTest {
        val rig = SchedulerRig(this)
        val id = rig.addRecord(1)
        rig.scheduler.submit(id)
        rig.settle()
        val before = rig.library.get(id)!!

        rig.models.missing()
        rig.settle()
        rig.scheduler.retranscribe(id)
        rig.settle()
        assertEquals(WAITING_MODEL, rig.jobs.single { it.kind == JobKind.RETRANSCRIBE }.state)
        assertEquals(before, rig.library.get(id)!!)

        rig.resources.low(ResourceReason.THERMAL)
        rig.models.ready()
        rig.settle()
        assertEquals(WAITING_RESOURCES, rig.jobs.single { it.kind == JobKind.RETRANSCRIBE }.state)
        assertEquals(before, rig.library.get(id)!!)

        rig.resources.ok()
        rig.settle()
        assertEquals(SUCCEEDED, rig.jobs.single { it.kind == JobKind.RETRANSCRIBE }.state)
        assertEquals(2, rig.library.get(id)!!.revisions.size)
    }

    @Test
    fun `已经有一个没结束的任务时再点重转录不会多出一个；结束之后可以再来，次数不限`() = runTest {
        val rig = SchedulerRig(this)
        val id = rig.addRecord(1)
        val gate = rig.hold(id)
        rig.scheduler.submit(id)
        rig.settle()

        rig.scheduler.retranscribe(id) // 第一次转录还没结束
        rig.settle()
        assertEquals(1, rig.jobs.size)

        gate.complete(TranscriptionOutcome.Text("第一次", "zh", null))
        rig.settle()
        rig.adapter.outcome = { TranscriptionOutcome.Text("再来一次", "zh", null) }
        rig.scheduler.retranscribe(id)
        rig.scheduler.retranscribe(id) // 连点两下
        rig.settle()
        assertEquals("连点只算一个", 2, rig.jobs.size)
        rig.scheduler.retranscribe(id)
        rig.settle()

        assertEquals(3, rig.jobs.size)
        assertEquals(3, rig.library.get(id)!!.revisions.size)
        assertEquals(listOf(1, 2, 3), rig.library.get(id)!!.revisions.map { it.number })
    }

    @Test
    fun `录完的第一次转录排在重转录前面，即使重转录先排上`() = runTest {
        val rig = SchedulerRig(this)
        val done = rig.addRecord(1)
        rig.scheduler.submit(done)
        rig.settle()
        val busy = rig.addRecord(2)
        val fresh = rig.addRecord(3)
        val gate = rig.hold(busy)
        rig.scheduler.submit(busy)
        rig.settle()

        rig.scheduler.retranscribe(done) // 先排上
        rig.scheduler.submit(fresh) // 后排上，但是刚录完的
        rig.settle()
        rig.adapter.requests.clear()
        gate.complete(TranscriptionOutcome.Text("忙的那个", "zh", null))
        rig.settle()

        assertEquals(listOf(fresh, done), rig.adapter.requests.map { it.recordId })
    }

    @Test
    fun `没有音频的 Record 不能重转录，不存在的 Record 不建任务`() = runTest {
        val rig = SchedulerRig(this)
        rig.library.addRecord(Samples.entry(1, audio = null))

        rig.scheduler.retranscribe(Samples.uuid(1))
        rig.scheduler.retranscribe(Samples.uuid(404))
        rig.settle()

        assertTrue(rig.jobs.isEmpty())
        assertTrue(rig.adapter.requests.isEmpty())
    }
}
