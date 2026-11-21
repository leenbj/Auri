@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.sync.destinations

import app.auri.sync.engine.JobKey
import app.auri.sync.engine.Refusal
import app.auri.sync.gateway.CreateDestinationRequest
import app.auri.sync.gateway.Destination
import app.auri.sync.gateway.Intent
import app.auri.sync.gateway.SendMode
import app.auri.sync.testing.DispatchRig
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 复评 R5（HIGH）的原子性：围栏不是“先看一眼配对、再写账”的两步，而是判断与写账在配对存储的同一把锁里。
 * 两步的做法即使通过了 StalePassFenceTest（那里的交错发生在判断之前），也留着“刚看完、还没写，用户重新配对了”的缝——
 * 旧配对的这一次写入照样落进新配对的账。这里把写账停在半路（假的发送账在指定的写操作里等放行；此时锁应当还被持着），
 * 让用户在这时重新配对：重新配对必须排在写账之后，不能插进去。
 */
class PairingFenceAtomicityTest {
    /** 在 [holdIn] 指定的写操作里停住（写之前），放行后才真正写；每次写完记一笔，用来比较它与重新配对的先后。 */
    private class HoldingLedger(private val inner: DispatchStore, private val holdIn: String, private val events: MutableList<String>) : DispatchStore by inner {
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        private suspend fun held(operation: String, write: suspend () -> Unit) {
            if (operation == holdIn && !inside.isCompleted) {
                inside.complete(Unit)
                release.await()
                write()
                events += "写账完成"
            } else {
                write()
            }
        }

        override suspend fun useGateway(gatewayId: String) = held("useGateway") { inner.useGateway(gatewayId) }

        override suspend fun gatewayHas(key: JobKey, destinationIds: Collection<String>) = held("gatewayHas") { inner.gatewayHas(key, destinationIds) }

        override suspend fun refused(key: JobKey, destinationIds: Collection<String>, reason: Refusal) = held("refused") { inner.refused(key, destinationIds, reason) }

        override suspend fun applyDestinations(gatewayId: String, destinations: List<Destination>, authoritative: Boolean, now: Instant) =
            held("applyDestinations") { inner.applyDestinations(gatewayId, destinations, authoritative, now) }
    }

    private class Held(val rig: DispatchRig, val ledger: HoldingLedger, val events: MutableList<String>)

    private fun TestScope.rigHoldingIn(operation: String): Held {
        val events = CopyOnWriteArrayList<String>()
        lateinit var ledger: HoldingLedger
        val rig = DispatchRig(this, wrapLedger = { HoldingLedger(it, operation, events).also { held -> ledger = held } })
        return Held(rig, ledger, events)
    }

    /** 写账停在半路时用户重新配对：重新配对必须等到写账完成之后才完成。 */
    private suspend fun TestScope.repairWhileWriting(held: Held, busy: Job) {
        held.ledger.inside.await()
        val repairing = launch {
            held.rig.repairWithAnotherGateway(grant = false)
            held.events += "重新配对完成"
        }
        runCurrent()
        held.ledger.release.complete(Unit)
        busy.join()
        repairing.join()
        assertEquals("重新配对只能排在写账之后：判断与写账之间不许夹进别的配对", listOf("写账完成", "重新配对完成"), held.events.toList())
    }

    @Test
    fun `引擎在入库之前换账本时，重新配对必须等这次写账完成`() = runTest {
        val held = rigHoldingIn("useGateway")
        held.rig.createDestination("Hermes", SendMode.AUTO_SEND)
        held.rig.transcribed("给 A 的文字")

        repairWhileWriting(held, launch { held.rig.engine.runOnce() })
    }

    @Test
    fun `入库成功后记账时，重新配对必须等这次记账完成`() = runTest {
        val held = rigHoldingIn("gatewayHas")
        held.rig.createDestination("Hermes", SendMode.AUTO_SEND)
        held.rig.transcribed("给 A 的文字")

        repairWhileWriting(held, launch { held.rig.engine.runOnce() })
    }

    @Test
    fun `追加那一遍开始换账本时，重新配对必须等这次写账完成`() = runTest {
        val held = rigHoldingIn("useGateway")

        repairWhileWriting(held, launch { held.rig.engine.runOnce() }) // 没有任何待入库的任务：第一次换账本就是追加那一遍的
    }

    @Test
    fun `管理页读写目的地之后写缓存时，重新配对必须等这次写缓存完成`() = runTest {
        val held = rigHoldingIn("applyDestinations")

        repairWhileWriting(held, launch { held.rig.service.create(CreateDestinationRequest("Claude Code", SendMode.AUTO_SEND, setOf(Intent.CONTEXT))) })
    }
}
