@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.sync.policy

import app.auri.sync.destinations.DispatchStore
import app.auri.sync.destinations.IntentResult
import app.auri.sync.engine.JobKey
import app.auri.sync.gateway.Intent
import app.auri.sync.gateway.Publication
import app.auri.sync.gateway.SendMode
import app.auri.sync.machine.SyncState
import app.auri.sync.pairing.GatewayIdentity
import app.auri.sync.pairing.NeedsUser
import app.auri.sync.testing.DispatchRig
import app.auri.sync.testing.FakeGateway.Fault
import app.auri.sync.testing.SyncRig
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 再评 S3（HIGH）：“问放行”与“请求离开手机”之间不许留缝。
 *
 * 以前引擎在 PUT 之前问最后一次放行，然后才为它构造提交（冻结意图标签，一次本地数据库操作，会挂起）；
 * 构造提交挂着的时候用户收回了授权，构造完成后 PUT 照发——放行是“问完就算”，不是“发的时候还算”。
 * 合同版本自愈（healContract）在定位网关的回答回来之后也不再问就去探能力；问网关结果之前的本地步骤同样没有再对一遍。
 *
 * 现在授权设置有一个世代（[DataFlowPolicy.generation]，每次改设置的前后各加一）：每次问放行都先记下世代，
 * 每个请求交给 HTTP 客户端的前一刻比对它，变过就什么都不发。放行问的次数与位置不变，多的只是紧挨着请求的那一次比对。
 *
 * 交错是确定的，不靠睡眠：用闩卡在引擎的某一次本地操作里（构造提交、换账本），收回授权，再放行。
 */
class PolicyGateRaceTest {
    private fun JsonObject.publication(): JsonObject = getValue("publication").jsonObject

    private fun DispatchRig.putsSent() = lan.delivered.count { it.second.method == "PUT" }

    /** 发送账：[armed] 时构造提交（冻结意图）卡在 [resume] 上直到测试放行；[inside] 表示引擎已经走到这里（问过放行、PUT 还没发）。 */
    private class SuspendedFreeze(private val inner: DispatchStore) : DispatchStore by inner {
        @Volatile
        var armed = true
        val inside = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()

        override suspend fun publicationFor(key: JobKey): Publication {
            if (armed) {
                inside.complete(Unit)
                resume.await()
            }
            return inner.publicationFor(key)
        }
    }

    /** 发送账：[armed] 时，引擎换账本（请求之前的本地一步）做完之后立刻执行 [then] 一次。 */
    private class AfterUseGateway(private val inner: DispatchStore, private val then: suspend () -> Unit) : DispatchStore by inner {
        @Volatile
        var armed = false

        override suspend fun useGateway(gateway: GatewayIdentity) {
            inner.useGateway(gateway)
            if (armed) {
                armed = false
                then()
            }
        }
    }

    /** 第一次 PUT 在路上丢了：任务记成“结果不确定”，走到重试时间；下一轮先问网关。 */
    private suspend fun TestScope.becomeUncertain(rig: DispatchRig, key: JobKey) {
        rig.server.faults += Fault.LoseRequest
        rig.engine.runOnce()
        assertTrue("请求发出去了却没拿到结果：必须记成不确定", rig.jobs.get(key)!!.uncertain)
        advanceTimeBy(Duration.between(rig.clock.now(), rig.jobs.get(key)!!.retryAt!!).toMillis())
    }

    @Test
    fun `构造提交挂在本地数据库里时授权被收回：PUT 一个字节也不发，标签放开还能改，重新授权后发出去的是改后的标签`() = runTest {
        lateinit var held: SuspendedFreeze
        val rig = DispatchRig(this, wrapLedger = { SuspendedFreeze(it).also { wrapped -> held = wrapped } })
        rig.createDestination("写作助手", SendMode.AUTO_SEND, setOf(Intent.CONTEXT, Intent.ACTION_REQUEST))
        val key = rig.transcribed()

        val pass = launch { rig.engine.runOnce() }
        held.inside.await() // 引擎问过放行（那时授权还在），正卡在构造提交里
        rig.policy.setGatewayTextGranted(false) // 用户这一刻收回
        held.resume.complete(Unit)
        pass.join()

        assertEquals("收回之后 PUT 不许发：放行只在问的那一刻算数的话，这里已经发出去一条", 0, rig.putsSent())
        val job = rig.jobs.get(key)!!
        assertEquals(SyncState.WAITING_GATEWAY, job.state)
        assertFalse("请求没有离开手机：不是“可能已送到”", job.uncertain)
        assertEquals("请求没发出去：构造提交时冻结的标签要放开，用户还能改", IntentResult.Changed, rig.dispatch.setIntent(key, Intent.ACTION_REQUEST, rig.clock.now()))

        rig.policy.setGatewayTextGranted(true)
        rig.engine.runOnce()

        assertEquals("网关收到的是用户在被拦下期间改的那个标签", "action_request", rig.server.putBodies.single().publication().getValue("intent").jsonPrimitive.content)
        assertEquals("请求真的发出去了才冻结", IntentResult.Locked, rig.dispatch.setIntent(key, Intent.CONTEXT, rig.clock.now()))
    }

    @Test
    fun `严格仅本地在构造提交挂着的时候被打开：同样一个字节也不发（授权没动，是另一道开关）`() = runTest {
        lateinit var held: SuspendedFreeze
        val rig = DispatchRig(this, wrapLedger = { SuspendedFreeze(it).also { wrapped -> held = wrapped } })
        val key = rig.transcribed()

        val pass = launch { rig.engine.runOnce() }
        held.inside.await()
        rig.policy.setStrictLocal(true)
        held.resume.complete(Unit)
        pass.join()

        assertEquals(0, rig.putsSent())
        assertEquals(SyncState.WAITING_GATEWAY, rig.jobs.get(key)!!.state)
        assertEquals("没发出去：标签还能改", IntentResult.Changed, rig.dispatch.setIntent(key, Intent.ACTION_REQUEST, rig.clock.now()))
    }

    @Test
    fun `结果不确定的任务：构造提交挂着时授权被收回，PUT 不发，仍标着不确定，冻结不放开（上一次可能已经把它送过去了）`() = runTest {
        lateinit var held: SuspendedFreeze
        val rig = DispatchRig(this, wrapLedger = { SuspendedFreeze(it).also { wrapped -> held = wrapped } })
        val key = rig.transcribed()
        held.armed = false // 第一次尝试照常发出去、在路上丢了
        becomeUncertain(rig, key)
        held.armed = true // 第二次尝试：问过网关、网关说没有，然后卡在构造提交里
        val putsBefore = rig.putsSent()

        val pass = launch { rig.engine.runOnce() }
        held.inside.await()
        rig.policy.setGatewayTextGranted(false)
        held.resume.complete(Unit)
        pass.join()

        assertEquals("第二次尝试没有 PUT", putsBefore, rig.putsSent())
        assertEquals("先问过网关", 1, rig.server.statusCount)
        val job = rig.jobs.get(key)!!
        assertEquals(SyncState.WAITING_GATEWAY, job.state)
        assertTrue("上一次可能已经送到的事实没有变：仍然不确定", job.uncertain)
        assertEquals("上一次也许已经把这个标签送到了网关：冻结不能放开", IntentResult.Locked, rig.dispatch.setIntent(key, Intent.ACTION_REQUEST, rig.clock.now()))
    }

    @Test
    fun `合同版本自愈：定位网关的回答在路上时授权被收回，不再发能力探测，标记原样留着，重新授权后自愈并照常发`() = runTest {
        val rig = SyncRig(this)
        rig.server.supportedVersions = listOf("1.1")
        val key = rig.publish()
        rig.engine.runOnce() // 网关太旧：配对记录打上标记
        assertEquals(NeedsUser.GATEWAY_TOO_OLD, rig.pairing.gateway!!.needsUser)
        val probesBefore = rig.server.capabilityCalls.get()
        rig.server.supportedVersions = listOf("1.2") // 用户更新了网关
        rig.server.onRequest = { request -> if (request.path == "/health") rig.policy.setGatewayTextGranted(false) }

        rig.engine.runOnce()

        assertEquals("定位网关的回答回来时授权已经收回：探能力也是联系，不许发", probesBefore, rig.server.capabilityCalls.get())
        assertEquals("没问成，标记原样留着", NeedsUser.GATEWAY_TOO_OLD, rig.pairing.gateway!!.needsUser)
        assertEquals("什么数据也没发", 0, rig.server.putCount)

        rig.server.onRequest = null
        rig.policy.setGatewayTextGranted(true)
        rig.engine.runOnce()

        assertNull(rig.pairing.gateway!!.needsUser)
        assertEquals(SyncState.STORED, rig.job(key).state)
    }

    @Test
    fun `问网关结果之前的本地步骤做完时授权被收回：状态查询一个也不发，任务退回等待，仍标着不确定`() = runTest {
        lateinit var hook: AfterUseGateway
        lateinit var rig: DispatchRig
        rig = DispatchRig(this, wrapLedger = { inner -> AfterUseGateway(inner) { rig.policy.setGatewayTextGranted(false) }.also { hook = it } })
        val key = rig.transcribed()
        becomeUncertain(rig, key)
        hook.armed = true

        rig.engine.runOnce()

        assertEquals("换账本之后授权被收回：连状态查询都不该发（它也带着令牌、也是联系网关）", 0, rig.server.statusCount)
        assertEquals("只有最初丢了的那一次 PUT", 1, rig.server.putCount)
        val job = rig.jobs.get(key)!!
        assertEquals(SyncState.WAITING_GATEWAY, job.state)
        assertTrue("上一次可能已经送到的事实没有变", job.uncertain)

        rig.policy.setGatewayTextGranted(true)
        rig.engine.runOnce()

        assertEquals(SyncState.STORED, rig.jobs.get(key)!!.state)
        assertEquals("重新授权之后先问、网关说没有，才重新提交", 1, rig.server.statusCount)
    }
}
