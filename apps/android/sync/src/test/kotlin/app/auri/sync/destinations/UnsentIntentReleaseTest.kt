@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.sync.destinations

import app.auri.sync.engine.JobKey
import app.auri.sync.gateway.Intent
import app.auri.sync.gateway.SendMode
import app.auri.sync.http.TransportFailure
import app.auri.sync.machine.SyncState
import app.auri.sync.pairing.NeedsUser
import app.auri.sync.testing.DispatchRig
import app.auri.sync.testing.FakeGateway.Fault
import java.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 再评 S9：请求**一定没有离开手机**的失败，要放开构造提交时冻结的意图标签（用户还能改）；有一丁点可能已经送到的，绝不放开。
 *
 * 引擎在请求马上要发的时候才构造提交（构造提交 = 冻结标签，评审 M3）。以前只有“放行在最后一刻被收回”这一种没发出去的情形放开冻结；
 * 连接或 TLS 握手失败时，请求同样一个字节也没发出，标签却还冻着——用户在没网、网关连不上的整段时间里想改标签都改不了，可那时网关根本没见过它。
 *
 * “一定没发出”的证据来自 HTTP 客户端自己的分类（[TransportFailure]，PinnedHttpsClient.connect 之前的任何失败）：
 * Unreachable（连接或握手没成）与 KeyMismatch（公钥不符、拒绝连接）。Interrupted 是写出请求之后才出的事，网关可能已经收到；
 * 网关回了错误响应更是已经看到了请求。只有“第一次尝试”（这一次之前没有任何一次可能送到过）才放开：上一次可能已经把某个标签送到网关，
 * 这一次再没发出去也不能放开——否则用户改了标签，后来重发的就和网关上已经有的那一版对不上。
 */
class UnsentIntentReleaseTest {
    private fun DispatchRig.sentIntent() = server.putBodies.single().getValue("publication").jsonObject.getValue("intent").jsonPrimitive.content

    private suspend fun TestScope.advanceToRetry(rig: DispatchRig, key: JobKey) =
        advanceTimeBy(Duration.between(rig.clock.now(), rig.jobs.get(key)!!.retryAt!!).toMillis())

    private suspend fun DispatchRig.writer() = createDestination("写作助手", SendMode.AUTO_SEND, setOf(Intent.CONTEXT, Intent.ACTION_REQUEST))

    @Test
    fun `第一次尝试：连接层失败（客户端证明什么也没发出）——冻结的标签放开，用户还能改，下一次发出去的是改后的标签`() = runTest {
        val rig = DispatchRig(this)
        rig.writer()
        val key = rig.transcribed()
        rig.server.faults += Fault.Transport(TransportFailure.Unreachable())

        rig.engine.runOnce()

        val job = rig.jobs.get(key)!!
        assertEquals(SyncState.WAITING_GATEWAY, job.state)
        assertFalse("请求一定没发出：不是“可能已送到”", job.uncertain)
        assertTrue("网关上什么也没有", rig.server.stored.isEmpty())
        assertFalse("标签放开了", rig.dispatch.item(key)!!.intentLocked)
        assertEquals("没发出去：用户还能改", IntentResult.Changed, rig.dispatch.setIntent(key, Intent.ACTION_REQUEST, rig.clock.now()))

        rig.engine.runOnce()

        assertEquals("下一次发出去的是用户改的那个标签", "action_request", rig.sentIntent())
        assertEquals("请求真的发出去了才冻结", IntentResult.Locked, rig.dispatch.setIntent(key, Intent.CONTEXT, rig.clock.now()))
    }

    @Test
    fun `第一次尝试：公钥不符（拒绝连接，一定没发出）——配对记录打上标记，冻结的标签放开`() = runTest {
        val rig = DispatchRig(this)
        rig.writer()
        val key = rig.transcribed()
        rig.server.faults += Fault.Transport(TransportFailure.KeyMismatch())

        rig.engine.runOnce()

        assertEquals("公钥不符要用户处理", NeedsUser.KEY_MISMATCH, rig.pairing.gateway!!.needsUser)
        val job = rig.jobs.get(key)!!
        assertFalse("请求一定没发出", job.uncertain)
        assertTrue(rig.server.stored.isEmpty())
        assertFalse("标签放开了", rig.dispatch.item(key)!!.intentLocked)
        assertEquals("用户还能改", IntentResult.Changed, rig.dispatch.setIntent(key, Intent.ACTION_REQUEST, rig.clock.now()))
    }

    @Test
    fun `上一次可能已经送到：这一次问过网关、重新提交时连接层失败，冻结绝不放开，仍标着不确定`() = runTest {
        val rig = DispatchRig(this)
        rig.writer()
        val key = rig.transcribed()
        rig.server.faults += Fault.LoseRequest // 第一次尝试：请求写出去了，回答没拿到
        rig.engine.runOnce()
        assertTrue("请求可能已送到：必须记成不确定", rig.jobs.get(key)!!.uncertain)
        advanceToRetry(rig, key)
        // 第二次尝试：先问网关（回答“没有”），然后重新提交的那个 PUT 在连接层失败
        rig.server.onRequest = { request -> if (request.method == "PUT") rig.server.faults += Fault.Transport(TransportFailure.Unreachable()) }

        rig.engine.runOnce()

        val job = rig.jobs.get(key)!!
        assertEquals(SyncState.WAITING_GATEWAY, job.state)
        assertTrue("上一次可能已经送到的事实没有变", job.uncertain)
        assertTrue("上一次也许已经把这个标签送到了网关：冻结不放开", rig.dispatch.item(key)!!.intentLocked)
        assertEquals(IntentResult.Locked, rig.dispatch.setIntent(key, Intent.ACTION_REQUEST, rig.clock.now()))
    }

    @Test
    fun `只有“一定没发出”才放开：写出请求之后才出的事、网关回了错误响应，都不放开（网关可能已经看到了这个标签）`() {
        // 失败的样子 → 放开了吗
        val cases = listOf(
            "连接层失败" to (Fault.Transport(TransportFailure.Unreachable()) to true),
            "公钥不符" to (Fault.Transport(TransportFailure.KeyMismatch()) to true),
            "写出请求之后断线" to (Fault.Transport(TransportFailure.Interrupted()) to false),
            "请求在路上丢了" to (Fault.LoseRequest to false),
            "入库了但响应丢了" to (Fault.StoreThenLoseResponse to false),
            "网关回 503 可重试" to (Fault.Reject(503, "GATEWAY_UNREACHABLE", retryable = true) to false),
            "网关回 422 请求不合规" to (Fault.Reject(422, "REQUEST_INVALID", retryable = false) to false),
        )
        for ((name, case) in cases) runTest {
            val (fault, released) = case
            val rig = DispatchRig(this)
            rig.writer()
            val key = rig.transcribed()
            rig.server.faults += fault

            rig.engine.runOnce()

            assertEquals(
                "$name：标签" + if (released) "放开" else "不放开",
                if (released) IntentResult.Changed else IntentResult.Locked,
                rig.dispatch.setIntent(key, Intent.ACTION_REQUEST, rig.clock.now()),
            )
        }
    }
}
