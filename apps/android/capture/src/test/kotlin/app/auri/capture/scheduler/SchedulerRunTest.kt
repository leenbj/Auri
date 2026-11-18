package app.auri.capture.scheduler

import app.auri.capture.adapter.TranscriptionOutcome
import app.auri.capture.contract.ExecutionLocation
import app.auri.capture.contract.RevisionSource
import app.auri.capture.contract.ReviewState
import app.auri.capture.library.TranscriptionStatus
import app.auri.capture.machine.TranscriptionEvent.RESULT_NO_SPEECH
import app.auri.capture.machine.TranscriptionEvent.RESULT_TEXT
import app.auri.capture.machine.TranscriptionEvent.RUN_STARTED
import app.auri.capture.machine.TranscriptionState
import app.auri.capture.machine.TranscriptionState.NO_SPEECH
import app.auri.capture.machine.TranscriptionState.RUNNING
import app.auri.capture.machine.TranscriptionState.SUCCEEDED
import app.auri.capture.testing.BoundedSchedulerTest
import app.auri.capture.testing.SchedulerRig
import app.auri.capture.testing.Samples
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 20：调度器的基本路径。一个 Record 提交之后，任务经状态机走完，Revision 写明用了哪个模型，任务落盘；
 * 一次只转一个，按先后顺序；没有语音就是没有语音（没有文字，也没有 Revision）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SchedulerRunTest : BoundedSchedulerTest() {
    @Test
    fun `提交一个 Record：开始、发布第一条 Revision，任务每一步都经状态机，Revision 写明引擎与模型`() = runTest {
        val rig = SchedulerRig(this)
        val id = rig.addRecord(1)

        rig.scheduler.submit(id)
        rig.settle()

        val job = rig.jobOf(id)
        assertEquals(SUCCEEDED, job.state)
        assertEquals(JobKind.INITIAL, job.kind)
        assertEquals(1, job.attempts)
        assertEquals(1, job.resultRevision)
        assertEquals("任务记下产出文字的模型", "fixture-model" to "1.0.0", job.modelId to job.modelVersion)
        assertEquals(
            "决定里的每一步都是状态机的一条规则",
            listOf(RUN_STARTED to RUNNING, RESULT_TEXT to SUCCEEDED),
            rig.decisionsOf(job.jobId).map { it.trigger to it.stateAfter },
        )
        val record = rig.library.get(id)!!
        assertEquals(TranscriptionStatus.DONE, record.transcription)
        val revision = record.revisions.single()
        assertEquals(Triple("fixture-engine", "fixture-model", "1.0.0"), Triple(revision.engine, revision.modelId, revision.modelVersion))
        assertEquals(RevisionSource.ASR, revision.source)
        assertEquals(ReviewState.NOT_REVIEWED, revision.reviewState)
        assertEquals(ExecutionLocation.ON_DEVICE, revision.executionLocation)
        assertEquals("你好，这是转录出的文字。", revision.text)
    }

    @Test
    fun `每次状态变化都写进任务库：库里的行与内存里的任务一致`() = runTest {
        val rig = SchedulerRig(this)
        val id = rig.addRecord(1)
        val gate = rig.hold(id)

        rig.scheduler.submit(id)
        rig.settle()
        assertEquals("运行中也已经落盘（进程这时被杀，重启后还找得到这个任务）", RUNNING, rig.store.all().single().state)
        assertEquals(1, rig.store.all().single().attempts)

        gate.complete(TranscriptionOutcome.Text("文字", "zh", null))
        rig.settle()

        assertEquals(listOf(rig.jobOf(id)), rig.store.all())
        assertEquals(SUCCEEDED, rig.store.all().single().state)
    }

    @Test
    fun `一次只转一个，按提交的先后顺序`() = runTest {
        val rig = SchedulerRig(this)
        val ids = (1..3).map { rig.addRecord(it) }
        val gate = rig.hold(ids[0])

        ids.forEach(rig.scheduler::submit)
        rig.settle()

        assertEquals(listOf(ids[0]), rig.adapter.requests.map { it.recordId })
        assertEquals(listOf(RUNNING, TranscriptionState.QUEUED, TranscriptionState.QUEUED), ids.map { rig.jobOf(it).state })

        gate.complete(TranscriptionOutcome.Text("第一段", "zh", null))
        rig.settle()

        assertEquals(ids, rig.adapter.requests.map { it.recordId })
        assertEquals("一次只转一个", 1, rig.adapter.maxConcurrent)
        assertTrue(ids.all { rig.jobOf(it).state == SUCCEEDED })
    }

    @Test
    fun `没有语音：任务是 NO_SPEECH，没有 Revision，Record 如实记为没有语音`() = runTest {
        val rig = SchedulerRig(this)
        val id = rig.addRecord(1)
        rig.adapter.outcome = { TranscriptionOutcome.NoSpeech }

        rig.scheduler.submit(id)
        rig.settle()

        val job = rig.jobOf(id)
        assertEquals(NO_SPEECH, job.state)
        assertNull(job.resultRevision)
        assertEquals(listOf(RUN_STARTED to RUNNING, RESULT_NO_SPEECH to NO_SPEECH), rig.decisionsOf(job.jobId).map { it.trigger to it.stateAfter })
        val record = rig.library.get(id)!!
        assertEquals(TranscriptionStatus.NO_SPEECH, record.transcription)
        assertTrue("不编造文字，也不留空 Revision", record.revisions.isEmpty())
    }

    @Test
    fun `引擎回了只有空白的文字：同样是没有语音，不留一条空 Revision`() = runTest {
        val rig = SchedulerRig(this)
        val id = rig.addRecord(1)
        rig.adapter.outcome = { TranscriptionOutcome.Text("  \n\t ", "zh", null) }

        rig.scheduler.submit(id)
        rig.settle()

        assertEquals(NO_SPEECH, rig.jobOf(id).state)
        assertTrue(rig.library.get(id)!!.revisions.isEmpty())
        assertEquals(TranscriptionStatus.NO_SPEECH, rig.library.get(id)!!.transcription)
    }

    @Test
    fun `同一个 Record 重复提交：不论任务还在排队、正在转还是已经转完，都只有一个任务、只转一次`() = runTest {
        val rig = SchedulerRig(this)
        val id = rig.addRecord(1)
        val gate = rig.hold(id)

        rig.scheduler.submit(id)
        rig.scheduler.submit(id)
        rig.settle()
        rig.scheduler.submit(id) // 正在转
        rig.settle()
        gate.complete(TranscriptionOutcome.Text("文字", "zh", null))
        rig.settle()
        rig.scheduler.submit(id) // 已经转完
        rig.settle()

        assertEquals(1, rig.jobs.size)
        assertEquals(1, rig.adapter.requests.size)
        assertEquals(1, rig.library.get(id)!!.revisions.size)
    }

    @Test
    fun `库里没有这个 Record、或者它没有音频：不建任务，调度器不受影响`() = runTest {
        val rig = SchedulerRig(this)
        val noAudio = Samples.uuid(2)
        rig.library.addRecord(Samples.entry(2, audio = null))
        val real = rig.addRecord(3)

        rig.scheduler.submit(Samples.uuid(99))
        rig.scheduler.submit(noAudio)
        rig.scheduler.submit(real)
        rig.settle()

        assertEquals(listOf(real), rig.jobs.map { it.recordId })
        assertEquals(SUCCEEDED, rig.jobOf(real).state)
        assertEquals(listOf(real), rig.adapter.requests.map { it.recordId })
    }
}
