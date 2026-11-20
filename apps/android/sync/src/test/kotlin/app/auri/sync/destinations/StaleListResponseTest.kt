@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.sync.destinations

import app.auri.sync.gateway.CreateDestinationRequest
import app.auri.sync.gateway.Intent
import app.auri.sync.gateway.SendMode
import app.auri.sync.testing.DispatchRig
import app.auri.sync.testing.SyncSamples
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评审 H3：向网关要列表的请求比后面的撤销、创建发出得早，回答却在它们之后才到。网关按“撤销之前”的状态答的列表如果照常写进缓存，
 * 已撤销的 Destination 就“复活”成有效的（手机会为它规划目标，网关随后以 422 拒绝整条文字），新建的会从缓存里被当成“网关没有”而删掉。
 * 这里用一个闩把“回答在路上耽搁”钉死：网关按请求到达那一刻的状态算好回答，闩放开之前谁也看不到它。网关与局域网是假的，服务与发送账是真的。
 */
class StaleListResponseTest {
    private val listPath = "/v1/destinations"

    /** 让下一个“读列表”的回答在路上耽搁：网关当下就算好了，闩放开之前不送到手机。返回的两个闩：回答已算好、放行。 */
    private fun DispatchRig.delayNextListAnswer(): Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>> {
        val computed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var armed = true
        val host = lan.hosts.getValue(SyncSamples.LAN_ADDRESS)
        host.handler = { request ->
            val reply = server.handle(request) // 按请求到达这一刻的网关状态算好
            if (armed && request.method == "GET" && request.path == listPath) {
                armed = false
                computed.complete(Unit)
                release.await()
            }
            reply
        }
        return computed to release
    }

    @Test
    fun `较早发出的列表请求的回答在撤销之后才到：已撤销的 Destination 不会被它复活`() = runTest {
        val rig = DispatchRig(this)
        val destination = rig.createDestination("Hermes", SendMode.AUTO_SEND)
        val (computed, release) = rig.delayNextListAnswer()
        val refresh = async { rig.service.refresh() }
        computed.await() // 网关按“还有效”答好了，回答还在路上

        val revoke = async { rig.service.revoke(destination.destinationId) }
        runCurrent() // 撤销能走多远就走多远
        release.complete(Unit)
        refresh.await()
        revoke.await()

        assertFalse("网关上已经撤销了", rig.server.destinations.getValue(destination.destinationId).revokedAt == null)
        assertFalse("缓存里也必须是已撤销：较早的回答不能把它改回有效", rig.dispatch.destinations().single().active)
    }

    @Test
    fun `较早发出的列表请求的回答在创建之后才到：新建的 Destination 不会被当成网关没有而从缓存里删掉`() = runTest {
        val rig = DispatchRig(this)
        rig.createDestination("Hermes", SendMode.AUTO_SEND)
        val (computed, release) = rig.delayNextListAnswer()
        val refresh = async { rig.service.refresh() }
        computed.await() // 网关按“只有一个”答好了

        val create = async { rig.service.create(CreateDestinationRequest("Claude Code", SendMode.PREVIEW_CONFIRM, setOf(Intent.CONTEXT))) }
        runCurrent()
        release.complete(Unit)
        refresh.await()
        create.await()

        assertEquals("网关上有两个", 2, rig.server.destinations.size)
        assertTrue("缓存里也必须有两个：新建的那个不能因为较早的列表没有它而消失", rig.dispatch.destinations().map { it.displayName }.containsAll(listOf("Hermes", "Claude Code")))
        assertEquals(2, rig.dispatch.destinations().size)
    }

    @Test
    fun `较早发出的列表请求的回答在签发凭证之后才到：缓存不会退回还没有凭证`() = runTest {
        val rig = DispatchRig(this)
        val destination = rig.createDestination("Hermes", SendMode.AUTO_SEND)
        val (computed, release) = rig.delayNextListAnswer()
        val refresh = async { rig.service.refresh() }
        computed.await() // 网关按“还没签发过凭证”答好了

        val issue = async { rig.service.issueCredential(destination.destinationId) }
        runCurrent()
        release.complete(Unit)
        refresh.await()
        val outcome = issue.await()

        assertTrue("签发成功了", outcome is CredentialOutcome.Issued)
        assertTrue("网关上签发了", rig.server.destinations.getValue(destination.destinationId).credentialIssued)
        assertTrue("缓存里也必须记着已签发：较早的列表不能把它退回“没有凭证”", rig.dispatch.destinations().single().credentialIssued)
    }
}
