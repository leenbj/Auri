@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.sync.policy

import app.auri.sync.discovery.GatewayLocator
import app.auri.sync.engine.SendResult
import app.auri.sync.engine.SyncEngine
import app.auri.sync.machine.SyncState
import app.auri.sync.testing.InMemoryDataFlowPolicy
import app.auri.sync.testing.SyncRig
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第四轮 1（HIGH，S3 的缝）：一次授权设置的写，从“开始改”（世代加一）到“落定”（世代再加一）之间有一段窗口，
 * 窗口里读到的世代与读到的授权可以对不上——写还排在队里时问的放行读到的是**旧**授权，却记下了**新**世代；
 * 写提交之后、落定之前，请求交出去的前一刻比的世代恰好没变，于是一个请求在收回已经提交之后照样出去。
 *
 * 要的性质：任何一次改动没落定之前，没有任何请求能交出去；也不能被“落定之后世代变了”以外的东西放过——
 * 窗口里问的放行（记下的世代）永远对不上以后读到的世代。内存与 SQLite 两份实现共用 [PolicyGeneration]，
 * 这里用内存替身的两个卡点钩子确定地摆出两处缝，用 [PolicyGeneration] 本身钉住性质；SQLite 一侧的写入协议见 PolicyWriteProtocolTest。
 *
 * 交错是确定的，不靠睡眠：用 [CompletableDeferred] 卡住写，再卡住引擎。
 */
class PolicyWriteWindowTest {
    /** 引擎的策略：被武装之后，下一次读世代之后的那次读设置读完就停住（引擎已经拿到“旧授权 + 当时的世代”，还没往下走）。 */
    private class PausingPolicy(private val inner: InMemoryDataFlowPolicy) : DataFlowPolicy by inner {
        val armOnce = AtomicBoolean(false)
        private val pauseOnNextRead = AtomicBoolean(false)
        val readDone = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()

        override val generation: Long
            get() {
                if (armOnce.compareAndSet(true, false)) pauseOnNextRead.set(true)
                return inner.generation
            }

        override suspend fun stored(): StoredDataFlow {
            val result = inner.stored()
            if (pauseOnNextRead.compareAndSet(true, false)) {
                readDone.complete(Unit)
                resume.await()
            }
            return result
        }
    }

    private fun engineOn(rig: SyncRig, policy: DataFlowPolicy) = SyncEngine(
        jobs = rig.jobs,
        pairing = rig.pairing,
        source = { rig.submissions[it] },
        http = rig.lan.factory,
        locator = GatewayLocator(rig.lan.factory, rig.discovery, rig.log),
        clock = rig.clock,
        policy = policy,
    )

    @Test
    fun `收回还排在队里时问的放行：写提交之后、落定之前，请求不许交出去`() = runTest {
        val rig = SyncRig(this)
        val key = rig.publish()
        val queued = CompletableDeferred<Unit>()
        val commit = CompletableDeferred<Unit>()
        val committed = CompletableDeferred<Unit>()
        val settle = CompletableDeferred<Unit>()
        rig.policy.beforeCommit = { queued.complete(Unit); commit.await() }
        rig.policy.afterCommit = { committed.complete(Unit); settle.await() }
        val policy = PausingPolicy(rig.policy)
        val engine = engineOn(rig, policy)

        val revoke = launch { rig.policy.revokeGrant() }
        queued.await() // 世代动了、写还没提交：这时读到的授权还是旧的
        policy.armOnce.set(true)
        val send = async { engine.send(key) }
        policy.readDone.await() // 引擎在写排着的时候记了世代，读到的是“已授权”
        commit.complete(Unit)
        committed.await() // 收回已经提交，落定还没做：旧章的世代与现在读到的世代曾经恰好相等
        policy.resume.complete(Unit)
        val result = send.await()
        val reachedTheLan = rig.lan.delivered.size

        settle.complete(Unit)
        revoke.join()
        assertEquals("收回已经提交，之后一个请求都不许出去（连健康探测也不行）", 0, reachedTheLan)
        assertEquals(SendResult.WAITING_GATEWAY, result)
        assertEquals("这一轮什么都没发，任务留在等待", SyncState.WAITING_GATEWAY, rig.job(key).state)
    }

    @Test
    fun `写开始之前就问到的放行：写一落定就对不上，请求不发（原有的保证，守住）`() = runTest {
        val rig = SyncRig(this)
        val key = rig.publish()
        val policy = PausingPolicy(rig.policy)
        val engine = engineOn(rig, policy)

        policy.armOnce.set(true)
        val send = async { engine.send(key) }
        policy.readDone.await() // 引擎问到了放行（空闲时的世代），还没交出请求
        rig.policy.revokeGrant()
        policy.resume.complete(Unit)

        assertEquals(SendResult.WAITING_GATEWAY, send.await())
        assertTrue("收回之后没有请求出去", rig.lan.delivered.isEmpty())
    }

    @Test
    fun `不收回的写也一样：写没落定之前请求不交出去，落定之后重新问放行就能发（拦下不是永久的）`() = runTest {
        val rig = SyncRig(this)
        val key = rig.publish()
        val commit = CompletableDeferred<Unit>()
        val queued = CompletableDeferred<Unit>()
        rig.policy.beforeCommit = { queued.complete(Unit); commit.await() }
        val policy = PausingPolicy(rig.policy)
        val engine = engineOn(rig, policy)

        val write = launch { rig.policy.grantTo(rig.policy.gateway!!) } // 同样的授权再写一遍：设置没变，但这是一次改动
        queued.await()
        policy.armOnce.set(true)
        val send = async { engine.send(key) }
        policy.readDone.await()
        policy.resume.complete(Unit)

        assertEquals("改动没落定，请求不交出去", SendResult.WAITING_GATEWAY, send.await())
        assertTrue(rig.lan.delivered.isEmpty())

        commit.complete(Unit)
        write.join()
        rig.policy.beforeCommit = null
        engine.runOnce() // 等待网关的任务探到网关就回到排队，重新问放行、照常发出去
        assertEquals("落定之后重新问放行，照常发出去", SyncState.STORED, rig.job(key).state)
        assertTrue(rig.lan.delivered.isNotEmpty())
    }

    @Test
    fun `写进行中读到的世代每次都不同，也不会等于之后任何一次读到的`() {
        val generation = PolicyGeneration()
        val idle = generation.value
        generation.changing()

        val first = generation.value
        val second = generation.value
        assertNotEquals("窗口里记下的世代不能与窗口里别处读到的世代相同", first, second)
        assertNotEquals(idle, first)
        generation.changed()
        val settled = generation.value
        assertNotEquals(first, settled)
        assertNotEquals(second, settled)
        assertEquals("落定之后世代稳定：读它不是改动", settled, generation.value)
    }

    @Test
    fun `写没落定之前交接被拦下，落定之后拿窗口里的章交接同样被拦下`() = runBlocking {
        val generation = PolicyGeneration()
        generation.changing()
        val askedInside = generation.value

        assertNull("窗口里记的世代，窗口里交接：不许", PolicyGeneration.handoff({ generation.value == askedInside }) { "sent" })
        generation.changed()
        assertNull("窗口里记的世代，落定之后交接：不许", PolicyGeneration.handoff({ generation.value == askedInside }) { "sent" })
        val askedAfter = generation.value
        assertEquals("落定之后重新问的放行可以交接", "sent", PolicyGeneration.handoff({ generation.value == askedAfter }) { "sent" })
    }

    @Test
    fun `两次写重叠：两次都落定之前一直拦，都落定之后新问的才算数`() = runBlocking {
        val generation = PolicyGeneration()
        generation.changing()
        generation.changing()
        generation.changed() // 第一次落定了，第二次还开着
        val whileOneIsOpen = generation.value
        assertNull(PolicyGeneration.handoff({ generation.value == whileOneIsOpen }) { "sent" })

        generation.changed()
        val settled = generation.value
        assertEquals("sent", PolicyGeneration.handoff({ generation.value == settled }) { "sent" })
    }

    @Test
    fun `写抛了也落定：窗口关上，之后新问的放行可以交接`() = runBlocking {
        val generation = PolicyGeneration()
        runCatching { generation.around<Unit> { error("写到一半断电") } }
        val settled = generation.value

        assertEquals("抛了的写不能把窗口永远开着", "sent", PolicyGeneration.handoff({ generation.value == settled }) { "sent" })
    }
}
