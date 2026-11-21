@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.sync.destinations

import app.auri.sync.engine.JobKey
import app.auri.sync.engine.SyncJob
import app.auri.sync.engine.SyncJobStore
import app.auri.sync.gateway.Publication
import app.auri.sync.machine.SyncState
import app.auri.sync.pairing.PairedGateway
import app.auri.sync.testing.DispatchRig
import app.auri.sync.testing.FakeGateway
import app.auri.sync.testing.FakeGateway.Fault
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 再评 S2（HIGH）：网关 A 的结果（入库成功、永久拒绝……）不能在用户改配到 B 之后才记到任务上。
 * 以前是“先看一眼配对还是不是 A、再把任务记成 STORED/BLOCKED”两步：看完、还没写，用户改配了，B 的任务就被 A 的结果记成已入库或被拒绝，
 * 文字永远到不了 B。现在判断、任务的转移、发送账的改动（含构造提交时冻结意图标签的写）是配对存储同一把锁里的一件事。
 *
 * 交错是确定的：在“结果要落账的那一次写”开始的瞬间，用户的改配在另一个协程里启动（UNDISPATCHED：锁空着就当场完成）。
 * 落账若在围栏里，改配要等锁，那次写发生时配对还是 A；落账若不在围栏里，改配当场完成，那次写发生时配对已经是 B——测试就记这一件事。
 */
class ResultFenceAtomicityTest {
    /** 一次写：写的是什么、写的那一刻配对着的还是不是最初的那份。 */
    private data class Write(val what: String, val stillOriginalPairing: Boolean)

    /** 一场交错：谁在哪次写的瞬间改配、各次写的那一刻配对着谁。 */
    private class Scene(private val scope: TestScope) {
        lateinit var rig: DispatchRig
        lateinit var original: PairedGateway
        val writes = CopyOnWriteArrayList<Write>()
        private var switched = false

        /** 第一次到这里时启动用户的改配（不等它）；然后记下这次写发生时配对着谁。 */
        fun beforeWrite(what: String, triggersSwitch: Boolean) {
            if (triggersSwitch && !switched) {
                switched = true
                scope.launch(start = CoroutineStart.UNDISPATCHED) { rig.repairWithAnotherGateway(grant = false) }
            }
            writes += Write(what, rig.pairing.gateway?.isSamePairing(original) == true)
        }

        fun assertWrittenBeforeTheSwitch(what: String) {
            val write = writes.singleOrNull { it.what == what } ?: error("没有恰好一次“$what”的写：$writes")
            assertTrue("“$what”是在用户改配到 B 之后才写的：旧配对的结果落到了新配对上（$writes）", write.stillOriginalPairing)
            assertFalse("改配最终应当完成（它排在这次写之后）", rig.pairing.gateway!!.isSamePairing(original))
        }
    }

    /** 同步任务表：到 [trigger] 状态的那一次写之前启动改配。 */
    private class SwitchingJobs(private val inner: SyncJobStore, private val scene: Scene, private val trigger: SyncState) : SyncJobStore by inner {
        override suspend fun update(job: SyncJob) {
            scene.beforeWrite(job.state.name, triggersSwitch = job.state == trigger)
            inner.update(job)
        }
    }

    /** 发送账：构造提交（冻结意图）的那一次写之前启动改配。 */
    private class SwitchingLedger(private val inner: DispatchStore, private val scene: Scene, private val onFreeze: Boolean) : DispatchStore by inner {
        override suspend fun publicationFor(key: JobKey): Publication {
            scene.beforeWrite(FREEZE, triggersSwitch = onFreeze)
            return inner.publicationFor(key)
        }
    }

    private fun TestScope.scene(trigger: SyncState? = null, onFreeze: Boolean = false, gatewayOnLan: Boolean = true): Scene {
        val scene = Scene(this)
        scene.rig = DispatchRig(
            this,
            gatewayOnLan = gatewayOnLan,
            wrapLedger = { SwitchingLedger(it, scene, onFreeze) },
            wrapJobs = { jobs -> if (trigger == null) jobs else SwitchingJobs(jobs, scene, trigger) },
        )
        scene.original = scene.rig.pairing.gateway!!
        return scene
    }

    /** 第一次 PUT 之后网关已经入库、回答丢了：任务记成结果不确定，走到重试时间；下一轮先问网关。 */
    private suspend fun TestScope.becomeUncertainAfterStoring(rig: DispatchRig, key: JobKey) {
        rig.server.faults += Fault.StoreThenLoseResponse
        rig.engine.runOnce()
        assertTrue("请求发出去了却没拿到结果：必须记成不确定", rig.jobs.get(key)!!.uncertain)
        advanceTimeBy(Duration.between(rig.clock.now(), rig.jobs.get(key)!!.retryAt!!).toMillis())
    }

    @Test
    fun `A 的入库成功回答落账时用户改配到 B：任务记成已入库的那次写必须排在改配之前`() = runTest {
        val scene = scene(trigger = SyncState.STORED)
        scene.rig.transcribed()

        scene.rig.engine.runOnce()
        advanceUntilIdle()

        scene.assertWrittenBeforeTheSwitch("STORED")
    }

    @Test
    fun `问网关结果得到“已入库”时用户改配到 B：确认入库的那次写必须排在改配之前`() = runTest {
        val scene = scene(trigger = SyncState.STORED)
        val key = scene.rig.transcribed()
        becomeUncertainAfterStoring(scene.rig, key)

        scene.rig.engine.runOnce()
        advanceUntilIdle()

        assertEquals("这一轮靠状态查询确认的", 1, scene.rig.server.statusCount)
        scene.assertWrittenBeforeTheSwitch("STORED")
    }

    @Test
    fun `A 的永久拒绝落账时用户改配到 B：任务被停下的那次写必须排在改配之前`() = runTest {
        val scene = scene(trigger = SyncState.BLOCKED)
        scene.rig.transcribed()
        scene.rig.server.faults += Fault.Reject(409, "RECORD_VERSION_CONFLICT", retryable = false)

        scene.rig.engine.runOnce()
        advanceUntilIdle()

        scene.assertWrittenBeforeTheSwitch("BLOCKED")
    }

    @Test
    fun `问网关结果得到“正文不同”时用户改配到 B：任务被停下的那次写必须排在改配之前`() = runTest {
        val scene = scene(trigger = SyncState.BLOCKED)
        val key = scene.rig.transcribed()
        becomeUncertainAfterStoring(scene.rig, key)
        scene.rig.server.stored[key] = "0".repeat(64) // 网关上这一版的正文与手机不同

        scene.rig.engine.runOnce()
        advanceUntilIdle()

        scene.assertWrittenBeforeTheSwitch("BLOCKED")
    }

    @Test
    fun `令牌被网关拒绝时用户改配到 B：任务被停下的那次写必须排在改配之前`() = runTest {
        val scene = scene(trigger = SyncState.BLOCKED)
        scene.rig.transcribed()
        scene.rig.server.faults += Fault.Reject(401, "GATEWAY_AUTH_REVOKED", retryable = false)

        scene.rig.engine.runOnce()
        advanceUntilIdle()

        scene.assertWrittenBeforeTheSwitch("BLOCKED")
    }

    @Test
    fun `找不到网关时用户改配到 B：任务退回等待网关的那次写必须排在改配之前`() = runTest {
        val scene = scene(trigger = SyncState.WAITING_GATEWAY, gatewayOnLan = false)
        scene.rig.transcribed()

        scene.rig.engine.runOnce()
        advanceUntilIdle()

        scene.assertWrittenBeforeTheSwitch("WAITING_GATEWAY")
    }

    @Test
    fun `回答读不懂时用户改配到 B：任务排重试的那次写必须排在改配之前`() = runTest {
        val scene = scene(trigger = SyncState.RETRY_SCHEDULED)
        scene.rig.transcribed()
        scene.rig.server.faults += Fault.LoseRequest

        scene.rig.engine.runOnce()
        advanceUntilIdle()

        scene.assertWrittenBeforeTheSwitch("RETRY_SCHEDULED")
    }

    @Test
    fun `构造提交冻结意图标签的那次写之前用户改配到 B：冻结必须排在改配之前，旧配对不能在改配之后去冻结`() = runTest {
        val scene = scene(onFreeze = true)
        val key = scene.rig.transcribed()

        scene.rig.engine.runOnce()
        advanceUntilIdle()

        scene.assertWrittenBeforeTheSwitch(FREEZE)
        assertEquals("冻结发生在改配之前：A 确实收到了这条文字", 1, scene.rig.server.putCount)
        assertEquals("A 的回答回来时已经改配了：结果作废、任务回到排队，不是已入库", SyncState.RETRY_SCHEDULED, scene.rig.jobs.get(key)!!.state)
    }

    // ---- 另一种先后：改配在落账之前就完成了，结果整个丢掉 ----

    @Test
    fun `A 的永久拒绝回来时用户已经改配到 B：任务回到排队重新来过，不记成被停下，B 照常收到文字`() = runTest {
        val rig = DispatchRig(this)
        val key = rig.transcribed()
        rig.server.faults += Fault.Reject(409, "RECORD_VERSION_CONFLICT", retryable = false)
        lateinit var gatewayB: FakeGateway
        rig.server.onRequest = { request ->
            if (request.method == "PUT") { // 请求已经到了 A，回答还在路上
                rig.server.onRequest = null
                gatewayB = rig.repairWithAnotherGateway(grant = false)
            }
        }

        rig.engine.runOnce()

        assertEquals("A 的拒绝是旧配对的结果，不能停下 B 的任务", SyncState.RETRY_SCHEDULED, rig.jobs.get(key)!!.state)

        rig.policy.grantTo(rig.pairing.gateway!!.identity)
        rig.engine.runOnce()

        assertEquals(SyncState.STORED, rig.jobs.get(key)!!.state)
        assertEquals("B 收到了这条文字", 1, gatewayB.putCount)
    }

    private companion object {
        const val FREEZE = "freeze"
    }
}
