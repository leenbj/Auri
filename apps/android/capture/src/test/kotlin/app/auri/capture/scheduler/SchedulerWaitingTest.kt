package app.auri.capture.scheduler

import app.auri.capture.adapter.TranscriptionOutcome
import app.auri.capture.contract.CancelSupport
import app.auri.capture.contract.ErrorCode
import app.auri.capture.library.TranscriptionStatus
import app.auri.capture.machine.TranscriptionEvent
import app.auri.capture.machine.TranscriptionEvent.MODEL_MISSING
import app.auri.capture.machine.TranscriptionEvent.MODEL_READY
import app.auri.capture.machine.TranscriptionEvent.RESOURCES_LOW
import app.auri.capture.machine.TranscriptionEvent.RESOURCES_OK
import app.auri.capture.machine.TranscriptionEvent.RUN_STARTED
import app.auri.capture.machine.TranscriptionState
import app.auri.capture.machine.TranscriptionState.QUEUED
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 20 第 4 项：没有模型、或者资源不够（存储、内存、温度、省电、正在录音）时，任务**等**并说明原因，条件好了自己继续，
 * 而且不会无限重试：重查有界，用完之后标“需要用户处理”。等的过程中转录适配器一次都不会被碰（不回退、不上云）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SchedulerWaitingTest : BoundedSchedulerTest() {
    private fun SchedulerRig.eventsOf(jobId: String): List<Pair<TranscriptionEvent, TranscriptionState>> =
        decisionsOf(jobId).map { it.trigger to it.stateAfter }

    @Test
    fun `没有模型：任务等模型并记下原因，Record 显示等模型；模型装好之后自己继续，没有人再去触发`() = runTest {
        val rig = SchedulerRig(this)
        val id = rig.addRecord(1)
        rig.models.missing(ErrorCode.MODEL_NOT_INSTALLED)
        rig.settle()

        rig.scheduler.submit(id)
        rig.settle()

        val waiting = rig.jobOf(id)
        assertEquals(WAITING_MODEL, waiting.state)
        assertEquals(WaitReason.Model(ErrorCode.MODEL_NOT_INSTALLED), waiting.wait)
        assertEquals(listOf(MODEL_MISSING to WAITING_MODEL), rig.eventsOf(waiting.jobId))
        assertTrue("等模型的时候转录适配器一次都没被碰", rig.adapter.requests.isEmpty())
        val record = rig.library.get(id)!!
        assertEquals(TranscriptionStatus.WAITING_MODEL, record.transcription)
        assertEquals(ErrorCode.MODEL_NOT_INSTALLED, record.failureCode)
        assertEquals("录音与音频保留", Samples.audio(id), record.audio)

        rig.models.ready()
        rig.settle()

        val done = rig.jobOf(id)
        assertEquals(SUCCEEDED, done.state)
        assertNull("不再等了", done.wait)
        assertEquals(
            listOf(MODEL_MISSING to WAITING_MODEL, MODEL_READY to QUEUED, RUN_STARTED to RUNNING, TranscriptionEvent.RESULT_TEXT to SUCCEEDED),
            rig.eventsOf(done.jobId),
        )
        assertEquals(TranscriptionStatus.DONE, rig.library.get(id)!!.transcription)
        assertNull(rig.library.get(id)!!.failureCode)
    }

    @Test
    fun `模型装了但校验没过：原因是 MODEL_INTEGRITY_FAILED，同样等，不会拿坏模型去转`() = runTest {
        val rig = SchedulerRig(this)
        val id = rig.addRecord(1)
        rig.models.missing(ErrorCode.MODEL_INTEGRITY_FAILED)
        rig.settle()

        rig.scheduler.submit(id)
        rig.settle()

        assertEquals(WaitReason.Model(ErrorCode.MODEL_INTEGRITY_FAILED), rig.jobOf(id).wait)
        assertEquals(ErrorCode.MODEL_INTEGRITY_FAILED, rig.library.get(id)!!.failureCode)
        assertTrue(rig.adapter.requests.isEmpty())
    }

    @Test
    fun `等模型不轮询：过了一整天也没有新的决定，也没有一次资源检查；模型一好就继续`() = runTest {
        val rig = SchedulerRig(this)
        val id = rig.addRecord(1)
        rig.models.missing()
        rig.settle()
        rig.scheduler.submit(id)
        rig.settle()
        val decisionsBefore = rig.decisions.size

        rig.advance(24 * 60 * 60 * 1000L)

        assertEquals("没有定时重试在空转", decisionsBefore, rig.decisions.size)
        assertEquals(WAITING_MODEL, rig.jobOf(id).state)
        rig.models.ready()
        rig.settle()
        assertEquals(SUCCEEDED, rig.jobOf(id).state)
    }

    @Test
    fun `模型在任务排队的时候消失：排队的任务一起等，不会白白开始又失败`() = runTest {
        val rig = SchedulerRig(this)
        val first = rig.addRecord(1)
        val second = rig.addRecord(2)
        val gate = rig.hold(first)
        rig.scheduler.submit(first)
        rig.scheduler.submit(second)
        rig.settle()
        assertEquals(QUEUED, rig.jobOf(second).state)

        rig.models.missing()
        rig.settle()

        assertEquals("排队的那个立刻改为等模型", WAITING_MODEL, rig.jobOf(second).state)
        assertEquals("已经在转的那个不受影响", RUNNING, rig.jobOf(first).state)
        gate.complete(TranscriptionOutcome.Text("第一段", "zh", null))
        rig.settle()
        assertEquals(SUCCEEDED, rig.jobOf(first).state)
        assertEquals(listOf(first), rig.adapter.requests.map { it.recordId })
    }

    @Test
    fun `资源不够：任务等并说明原因，不碰转录适配器；条件恢复之后继续，每一种原因都一样`() = runTest {
        for (reason in ResourceReason.entries) {
            val rig = SchedulerRig(this)
            val id = rig.addRecord(1)
            rig.resources.low(reason)
            rig.settle()

            rig.scheduler.submit(id)
            rig.settle()

            val waiting = rig.jobOf(id)
            assertEquals("$reason", WAITING_RESOURCES, waiting.state)
            assertEquals("$reason", WaitReason.Resources(reason), waiting.wait)
            assertEquals("$reason", listOf(RESOURCES_LOW to WAITING_RESOURCES), rig.eventsOf(waiting.jobId))
            assertEquals("$reason：Record 仍显示转录中（不是失败）", TranscriptionStatus.PENDING, rig.library.get(id)!!.transcription)
            assertTrue("$reason：资源不够时没有碰转录适配器", rig.adapter.requests.isEmpty())

            rig.resources.ok()
            rig.settle()

            val done = rig.jobOf(id)
            assertEquals("$reason", SUCCEEDED, done.state)
            assertEquals("$reason", listOf(RESOURCES_LOW, RESOURCES_OK, RUN_STARTED, TranscriptionEvent.RESULT_TEXT), rig.decisionsOf(done.jobId).map { it.trigger })
            assertNull("$reason", done.wait)
            assertFalse("$reason", done.needsUser)
        }
    }

    @Test
    fun `没收到变化信号也不会一直等：按间隔自己重查，发现好了就继续`() = runTest {
        val rig = SchedulerRig(this)
        val id = rig.addRecord(1)
        rig.resources.status = ResourceStatus.Low(ResourceReason.LOW_MEMORY) // 没有信号
        rig.scheduler.submit(id)
        rig.settle()
        assertEquals(WAITING_RESOURCES, rig.jobOf(id).state)

        rig.resources.setSilently(ResourceStatus.Ok) // 好了，但没有人通知
        rig.advance(rig.config.resourceRecheckMs.first() - 1)
        assertEquals("还没到第一次重查", WAITING_RESOURCES, rig.jobOf(id).state)
        rig.advance(1)

        assertEquals(SUCCEEDED, rig.jobOf(id).state)
        assertEquals(listOf(RESOURCES_LOW, RESOURCES_OK, RUN_STARTED, TranscriptionEvent.RESULT_TEXT), rig.decisionsOf(rig.jobOf(id).jobId).map { it.trigger })
    }

    @Test
    fun `一直不行：重查的次数有界，用完就标需要用户处理，之后不再自己重查（绝不无限重试）`() = runTest {
        val rig = SchedulerRig(this)
        val id = rig.addRecord(1)
        rig.resources.status = ResourceStatus.Low(ResourceReason.THERMAL)
        rig.scheduler.submit(id)
        rig.settle()
        assertFalse(rig.jobOf(id).needsUser)

        val total = rig.config.resourceRecheckMs.sum()
        rig.advance(total - 1)
        assertFalse("最后一次重查之前还不算用完", rig.jobOf(id).needsUser)
        rig.advance(1)

        val stuck = rig.jobOf(id)
        assertEquals("仍然在等，没有变成失败", WAITING_RESOURCES, stuck.state)
        assertTrue(stuck.needsUser)
        assertEquals(rig.config.resourceRecheckMs.size, stuck.waitChecks)
        val checksAtExhaustion = rig.resources.checks.get()
        val decisionsAtExhaustion = rig.decisions.size

        rig.advance(7 * 24 * 60 * 60 * 1000L)

        assertEquals("用完之后一次检查都没有再做", checksAtExhaustion, rig.resources.checks.get())
        assertEquals(decisionsAtExhaustion, rig.decisions.size)
        assertTrue(rig.adapter.requests.isEmpty())
    }

    @Test
    fun `需要用户处理的任务：用户点重新检查会清零重来；条件自己变好（有信号）也会继续`() = runTest {
        val rig = SchedulerRig(this)
        val id = rig.addRecord(1)
        rig.resources.status = ResourceStatus.Low(ResourceReason.BATTERY_SAVER)
        rig.scheduler.submit(id)
        rig.settle()
        rig.advance(rig.config.resourceRecheckMs.sum())
        assertTrue(rig.jobOf(id).needsUser)

        rig.scheduler.recheckResources() // 还是不行：计数清零，重新有界地重查
        rig.settle()
        assertFalse(rig.jobOf(id).needsUser)
        assertEquals(0, rig.jobOf(id).waitChecks)
        rig.advance(rig.config.resourceRecheckMs.sum())
        assertTrue("重新用完又回到需要用户处理，同样有界", rig.jobOf(id).needsUser)

        rig.resources.ok() // 用户把省电模式关了
        rig.settle()
        assertEquals(SUCCEEDED, rig.jobOf(id).state)
        assertFalse(rig.jobOf(id).needsUser)
    }

    @Test
    fun `资源监视器自己出故障：等待读数恢复后继续`() = runTest {
        val rig = SchedulerRig(this)
        val id = rig.addRecord(1)
        rig.resources.failure = IllegalStateException("平台接口故障（测试注入）")

        rig.scheduler.submit(id)
        rig.settle()

        assertEquals(WAITING_RESOURCES, rig.jobOf(id).state)
        assertEquals(WaitReason.Resources(ResourceReason.UNKNOWN), rig.jobOf(id).wait)
        rig.resources.failure = null
        rig.resources.ok()
        rig.settle()
        assertEquals(SUCCEEDED, rig.jobOf(id).state)
    }

    @Test
    fun `运行中资源变差，引擎能中途停：任务回到等资源，这次不算一次失败；好了之后重新转`() = runTest {
        val rig = SchedulerRig(this)
        rig.adapter.capabilities = Samples.capabilities.copy(cancelSupport = CancelSupport.IMMEDIATE)
        val id = rig.addRecord(1)
        val gate = rig.hold(id, cancellable = true)
        rig.scheduler.submit(id)
        rig.settle()
        assertEquals(RUNNING, rig.jobOf(id).state)

        rig.resources.low(ResourceReason.THERMAL)
        rig.settle()

        val paused = rig.jobOf(id)
        assertEquals(WAITING_RESOURCES, paused.state)
        assertEquals(WaitReason.Resources(ResourceReason.THERMAL), paused.wait)
        assertEquals("已记录的尝试次数不能回退", 1, paused.attempts)

        rig.resources.ok()
        rig.settle()
        gate.complete(TranscriptionOutcome.Text("重新转出的文字", "zh", null))
        rig.settle()

        val done = rig.jobOf(id)
        assertEquals(SUCCEEDED, done.state)
        assertEquals(2, done.attempts)
        assertEquals(2, rig.adapter.requests.size)
        assertEquals(1, rig.library.get(id)!!.revisions.size)
    }

    @Test
    fun `运行中资源变差，引擎不能中途停（真引擎就是这样）：这次照常转完，后面排队的才等`() = runTest {
        val rig = SchedulerRig(this)
        val first = rig.addRecord(1)
        val second = rig.addRecord(2)
        val gate = rig.hold(first)
        rig.scheduler.submit(first)
        rig.scheduler.submit(second)
        rig.settle()

        rig.resources.low(ResourceReason.THERMAL)
        rig.settle()

        assertEquals("不能停的调用不去打断它", RUNNING, rig.jobOf(first).state)
        assertEquals("排队的不再开始", WAITING_RESOURCES, rig.jobOf(second).state)
        gate.complete(TranscriptionOutcome.Text("第一段", "zh", null))
        rig.settle()
        assertEquals("转完的结果照常发布", SUCCEEDED, rig.jobOf(first).state)
        assertEquals(1, rig.library.get(first)!!.revisions.size)
        assertEquals(listOf(first), rig.adapter.requests.map { it.recordId })

        rig.resources.ok()
        rig.settle()
        assertEquals(SUCCEEDED, rig.jobOf(second).state)
    }

    @Test
    fun `正在录音时新的转录不开始，录音一结束就开始（录音优先）`() = runTest {
        val rig = SchedulerRig(this)
        val id = rig.addRecord(1)
        rig.resources.low(ResourceReason.RECORDING_ACTIVE)
        rig.settle()

        rig.scheduler.submit(id)
        rig.advance(60_000)

        assertEquals(WaitReason.Resources(ResourceReason.RECORDING_ACTIVE), rig.jobOf(id).wait)
        assertTrue(rig.adapter.requests.isEmpty())

        rig.resources.ok()
        rig.settle()
        assertEquals(SUCCEEDED, rig.jobOf(id).state)
    }
}
