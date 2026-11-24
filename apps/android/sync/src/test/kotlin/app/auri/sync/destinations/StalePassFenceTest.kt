@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.sync.destinations

import app.auri.sync.engine.JobKey
import app.auri.sync.gateway.Destination
import app.auri.sync.gateway.SendMode
import app.auri.sync.machine.SyncState
import app.auri.sync.testing.DispatchRig
import app.auri.sync.testing.FakeGateway
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 复评 R5（HIGH）：发送账里的缓存与目标只属于“现在配对着的”那一份配对。
 * 一轮同步对着网关 A 说话时要等网络；这期间用户改配到 B、去发送页读了 B 的名单、又录了一条新文字（账已经是 B 的了）。
 * A 的这一轮醒来之后若还照旧对账（`useGateway(A)`），就会把 B 的缓存整个清掉，并且删掉 B 那条新文字还没送出去的目标——
 * 文字于是不带任何 Destination 去了 B，Agent 永远领不到它。
 * 现在：每次改账都在配对存储的锁里先确认“还是发请求时的那一份配对”，不是就什么也不碰，把这一轮丢掉。
 *
 * 交错是确定的：假网关在“定位网关的回答在路上”的这一刻（[FakeGateway.onRequest]）替用户完成整件事。
 * A 的授权故意留着（授权清理还没跑到或失败了——那是读的时候才绑定的安全网，不是这里要测的），好让 A 的这一轮醒来时看起来仍被允许。
 */
class StalePassFenceTest {
    private class Switched(val gatewayB: FakeGateway, val fresh: Destination, val second: JobKey)

    /** 用户在 A 的这一轮等定位回答的时候，改配到 B、读了 B 的名单（账换成 B 的）、录了新文字（按 B 的名单规划）。 */
    private fun DispatchRig.switchToBWhileTheNextPassIsLocating(): () -> Switched {
        var result: Switched? = null
        server.onRequest = { request ->
            if (request.path == "/health") {
                server.onRequest = null
                val gatewayB = repairWithAnotherGateway(grant = false)
                val fresh = createDestination("Claude Code", SendMode.AUTO_SEND)
                result = Switched(gatewayB, fresh, transcribed("给 B 的文字"))
            }
        }
        return { checkNotNull(result) { "A 的这一轮没有走到定位网关" } }
    }

    @Test
    fun `一轮对着网关 A 的同步醒来时配对已经是 B：它一个字也不碰账，B 的缓存与目标原样，A 的任务回到排队，没有一个字节发给 A`() = runTest {
        val rig = DispatchRig(this)
        rig.createDestination("Hermes", SendMode.AUTO_SEND) // A 的名单
        val first = rig.transcribed("给 A 的文字") // 按 A 的名单规划
        val switched = rig.switchToBWhileTheNextPassIsLocating()

        rig.engine.runOnce()

        val b = switched()
        assertEquals("账是 B 的：A 的这一轮不许把它清掉", listOf("Claude Code"), rig.dispatch.destinations().map { it.displayName })
        assertEquals("B 那条新文字的目标还在", listOf(b.fresh.destinationId), rig.dispatch.item(b.second)!!.targets.map { it.destinationId })
        assertEquals("旧配对的这一轮不该给 A 发入库请求", 0, rig.server.putCount)
        assertEquals("A 的任务退回排队，等按新配对重新来", SyncState.RETRY_SCHEDULED, rig.jobs.get(first)!!.state)

        rig.policy.grantTo(rig.pairing.gateway!!.identity) // 用户随后对 B 另行同意文字进网关（授权是给某个网关的，复评 R3）
        rig.engine.runOnce()

        assertEquals(listOf(SyncState.STORED, SyncState.STORED), listOf(first, b.second).map { rig.jobs.get(it)!!.state })
        assertEquals("两条文字都进了 B", 2, b.gatewayB.putCount)
        assertEquals("B 那条文字带着 B 的 Destination 去了，Agent 领得到", listOf(b.fresh.destinationId), b.gatewayB.deliveries.map { it.destinationId })
        assertEquals("A 自始至终没收到入库请求", 0, rig.server.putCount)
    }

    @Test
    fun `A 的入库回答在路上时用户改配到 B 并读了 B 的名单：回答回来后账不动，任务回到排队`() = runTest {
        val rig = DispatchRig(this)
        rig.createDestination("Hermes", SendMode.AUTO_SEND)
        val first = rig.transcribed("给 A 的文字")
        var switched: Switched? = null
        rig.server.onRequest = { request ->
            if (request.method == "PUT") { // 入库请求已经到了 A，回答还在路上
                rig.server.onRequest = null
                val gatewayB = rig.repairWithAnotherGateway(grant = false)
                val fresh = rig.createDestination("Claude Code", SendMode.AUTO_SEND)
                switched = Switched(gatewayB, fresh, rig.transcribed("给 B 的文字"))
            }
        }

        rig.engine.runOnce()

        val b = checkNotNull(switched)
        assertEquals("A 的回答是旧配对的结果，不能写进 B 的账", listOf("Claude Code"), rig.dispatch.destinations().map { it.displayName })
        assertEquals(listOf(b.fresh.destinationId), rig.dispatch.item(b.second)!!.targets.map { it.destinationId })
        assertEquals(SyncState.RETRY_SCHEDULED, rig.jobs.get(first)!!.state)
    }
}
