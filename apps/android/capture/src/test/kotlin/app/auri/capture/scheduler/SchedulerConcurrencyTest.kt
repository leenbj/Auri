package app.auri.capture.scheduler

import app.auri.capture.adapter.TranscriptionOutcome
import app.auri.capture.contract.AsrLimits
import app.auri.capture.contract.CancelSupport
import app.auri.capture.machine.TranscriptionState.QUEUED
import app.auri.capture.machine.TranscriptionState.RUNNING
import app.auri.capture.machine.TranscriptionState.SUCCEEDED
import app.auri.capture.machine.TranscriptionState.WAITING_RESOURCES
import app.auri.capture.testing.BoundedSchedulerTest
import app.auri.capture.testing.Samples
import app.auri.capture.testing.SchedulerRig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 并发名额（Ticket 20 第 4 项：默认并发 1）：同时转几个，取调度器配置与引擎声明的**较小值**；
 * 默认配置下无论引擎声明多少，都是一次一个（手机上两个模型实例同时算会把内存和温度都推高）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SchedulerConcurrencyTest : BoundedSchedulerTest() {
    private fun limits(concurrent: Int) = Samples.capabilities.copy(limits = AsrLimits(30_000, concurrent))

    private suspend fun SchedulerRig.submitHeld(count: Int): List<String> {
        val ids = (1..count).map { addRecord(it) }
        ids.forEach { hold(it) }
        ids.forEach(scheduler::submit)
        settle()
        return ids
    }

    @Test
    fun `默认配置：引擎声明能同时转 4 个，也还是一次只转一个`() = runTest {
        val rig = SchedulerRig(this)
        rig.adapter.capabilities = limits(4)

        val ids = rig.submitHeld(3)

        assertEquals(listOf(RUNNING, QUEUED, QUEUED), ids.map { rig.jobOf(it).state })
        assertEquals(1, rig.adapter.maxConcurrent)
    }

    @Test
    fun `配置允许 2 个、引擎也声明 2 个：同时转 2 个，第三个排队`() = runTest {
        val rig = SchedulerRig(this, config = SchedulerConfig(maxConcurrent = 2))
        rig.adapter.capabilities = limits(2)

        val ids = rig.submitHeld(3)

        assertEquals(listOf(RUNNING, RUNNING, QUEUED), ids.map { rig.jobOf(it).state })
        assertEquals(2, rig.adapter.maxConcurrent)
    }

    @Test
    fun `配置允许 2 个、但引擎只声明 1 个：取较小值，一次一个`() = runTest {
        val rig = SchedulerRig(this, config = SchedulerConfig(maxConcurrent = 2))
        rig.adapter.capabilities = limits(1)

        val ids = rig.submitHeld(2)

        assertEquals(listOf(RUNNING, QUEUED), ids.map { rig.jobOf(it).state })
        assertEquals(1, rig.adapter.maxConcurrent)
    }

    @Test
    fun `被打断的任务，上一次调用还没返回时不会同时再开一次；返回之后才重新开始，旧调用的结果作废`() = runTest {
        val rig = SchedulerRig(this, config = SchedulerConfig(maxConcurrent = 2))
        rig.adapter.capabilities = limits(2).copy(cancelSupport = CancelSupport.BEST_EFFORT)
        val id = rig.addRecord(1)
        val gate = rig.hold(id) // 声明了“尽力取消”，但这次调用一时半会儿停不下来
        rig.scheduler.submit(id)
        rig.settle()
        rig.resources.low(ResourceReason.THERMAL)
        rig.settle()
        assertEquals(WAITING_RESOURCES, rig.jobOf(id).state)

        rig.resources.ok()
        rig.settle()

        assertEquals("旧调用没返回，这个任务不会同时被转两遍", 1, rig.adapter.requests.size)
        assertEquals(QUEUED, rig.jobOf(id).state)

        gate.complete(TranscriptionOutcome.Text("旧调用终于算完的文字", "zh", null))
        rig.settle()

        assertEquals("旧调用返回之后任务重新开始", 2, rig.adapter.requests.size)
        assertEquals(SUCCEEDED, rig.jobOf(id).state)
        assertEquals("旧调用的结果作废，只有重新开始的那一次产生了 Revision", 1, rig.library.get(id)!!.revisions.size)
        assertEquals(1, rig.jobOf(id).attempts)
    }
}
