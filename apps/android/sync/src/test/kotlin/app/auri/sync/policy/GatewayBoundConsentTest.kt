@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.sync.policy

import app.auri.sync.machine.SyncState
import app.auri.sync.pairing.GatewayAddress
import app.auri.sync.pairing.GatewayIdentity
import app.auri.sync.pairing.PairingState
import app.auri.sync.status.SyncHeadline
import app.auri.sync.status.SyncStatus
import app.auri.sync.testing.FakeGateway
import app.auri.sync.testing.InMemoryDataFlowPolicy
import app.auri.sync.testing.SyncRig
import app.auri.sync.testing.SyncSamples
import app.auri.sync.testing.TlsFixtures
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复评 R3（SEC-002：同意的是“把文字发给我指定的那个网关”）。
 * 以前：授权是一个全局开关。用户授权了网关 A，再配对网关 B，同一个开关继续放行，B 在用户没同意的情况下收到文字。
 * 现在：授权记着它是给哪个网关的（网关编号 + 公钥指纹）；换成别的网关，授权不再算数，B 要用户另行同意。
 * 引擎与同步任务是真的，两个网关与局域网是假的。
 */
class GatewayBoundConsentTest {
    private val addressB = GatewayAddress("192.168.1.30", 8443)
    private val gatewayA = SyncSamples.pairedGateway()
    private val gatewayB = SyncSamples.pairedGateway(pin = TlsFixtures.gatewayB.pin, addresses = listOf(addressB)).copy(gatewayId = "0f0f0f0f-1bfb-53b3-85a3-4d36b680fe6e")

    private fun SyncRig.hostGatewayB(): FakeGateway {
        val other = FakeGateway(now = clock::now)
        lan.host(addressB, TlsFixtures.gatewayB) { other.handle(it) }
        return other
    }

    @Test
    fun `授权给了网关 A，重新配对到网关 B 之后：B 一个字节也收不到，文字留在手机上`() = runTest {
        val rig = SyncRig(this) // 装配时授权的是默认网关 A
        val other = rig.hostGatewayB()
        val key = rig.publish()
        rig.pairing.save(gatewayB)

        rig.engine.runOnce()

        assertTrue("网关 B 不该收到任何调用：${other.calls}", other.calls.isEmpty())
        assertTrue("网关 A 也不该被联系（已经不是配对的网关）：${rig.server.calls}", rig.server.calls.isEmpty())
        assertTrue("连网关 B 的连接都不该尝试：${rig.lan.attempts}", rig.lan.attempts.none { it == addressB })
        assertEquals(SyncState.PENDING, rig.job(key).state)
        assertEquals("没有开始发送：连 SEND_STARTED 都不该落盘", 0, rig.job(key).attempts)
    }

    @Test
    fun `用户之后对 B 另行同意：积压的文字才发给 B；再配回 A 时，A 的授权已经让位给了 B，A 又收不到`() = runTest {
        val rig = SyncRig(this)
        val other = rig.hostGatewayB()
        val first = rig.publish(recordId = "a4a7c08d-2767-50d6-9238-6fa277033a49")
        rig.pairing.save(gatewayB)
        rig.engine.runOnce()
        assertTrue(other.calls.isEmpty())

        rig.policy.grantTo(gatewayB.identity) // 用户在界面上对现在配对的 B 同意
        rig.engine.runOnce()

        assertEquals(listOf("PUT ${first.recordId}#1"), other.calls.toList())
        assertEquals(SyncState.STORED, rig.job(first).state)

        rig.pairing.save(gatewayA)
        val second = rig.publish(recordId = "b5b8d19e-3878-5ae7-a349-7fb388144b5a")
        rig.engine.runOnce()

        assertTrue("A 的授权被 B 的替换掉了：A 一个字节也收不到：${rig.server.calls}", rig.server.calls.isEmpty())
        assertEquals(SyncState.PENDING, rig.job(second).state)
    }

    @Test
    fun `同一台网关重新配对（新令牌、新配对时刻、同一个编号与公钥）：授权还在，不用重新同意`() = runTest {
        val rig = SyncRig(this)
        val key = rig.publish()
        rig.pairing.save(SyncSamples.pairedGateway(pairedAt = Instant.parse("2026-11-16T09:00:00Z")))

        rig.engine.runOnce()

        assertEquals(listOf("PUT ${key.recordId}#1"), rig.server.calls.toList())
        assertEquals(SyncState.STORED, rig.job(key).state)
    }

    @Test
    fun `同一个网关编号、公钥换了：是另一台网关，原来的同意对它不算数`() = runTest {
        val rig = SyncRig(this)
        val rekeyed = FakeGateway(now = rig.clock::now)
        rig.lan.host(SyncSamples.LAN_ADDRESS, TlsFixtures.gatewayB) { rekeyed.handle(it) } // 同一个地址上换成了另一把密钥
        val key = rig.publish()
        rig.pairing.save(SyncSamples.pairedGateway(pin = TlsFixtures.gatewayB.pin)) // 编号与 A 相同，指纹是 B 的

        rig.engine.runOnce()

        assertTrue("换了密钥的网关一个字节也收不到：${rekeyed.calls}", rekeyed.calls.isEmpty())
        assertEquals(SyncState.PENDING, rig.job(key).state)
    }

    @Test
    fun `对配对着的网关算出来的设置：换了网关立刻变成没授权，没有配对也没有授权，换回来授权还在`() = runTest {
        val policy = InMemoryDataFlowPolicy(gateway = gatewayA.identity) // 授权给了 A
        val paired = MutableStateFlow<GatewayIdentity?>(gatewayA.identity)
        val seen = mutableListOf<DataFlowState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { policy.states(paired).toList(seen) }

        paired.value = gatewayB.identity
        paired.value = null
        paired.value = gatewayA.identity
        job.cancel()

        assertEquals(listOf(InMemoryDataFlowPolicy.GRANTED, DataFlowState.Unset, InMemoryDataFlowPolicy.GRANTED), seen)
    }

    @Test
    fun `同步状态：换到没授权的网关上不能说“正在发送”或“已同步”，而是文字被留住了`() = runTest {
        val policy = InMemoryDataFlowPolicy(gateway = gatewayA.identity)
        val effective = policy.current(gatewayB.identity)

        val status = SyncStatus.of(PairingState.Paired(gatewayB), emptyList(), effective)

        assertEquals(SyncHeadline.HELD_BY_POLICY, status.headline)
        assertFalse("开关要显示成关：用户对 B 还没同意", status.dataFlow.gatewayTextGranted)
    }

    // ---- 界面开关与启动对账 ----

    @Test
    fun `打开开关 = 同意发给现在配对着的网关；没配对（或配对记录读不出来）时没有可同意的，什么也不记`() = runTest {
        val policy = InMemoryDataFlowPolicy(DataFlowState.Unset, gateway = null)

        assertFalse(policy.setGrantFor(PairingState.NotPaired, granted = true))
        assertFalse(policy.setGrantFor(PairingState.Lost, granted = true))
        assertNull(policy.stored().grantedTo)

        assertTrue(policy.setGrantFor(PairingState.Paired(gatewayB), granted = true))
        assertEquals(gatewayB.identity, policy.stored().grantedTo)
    }

    @Test
    fun `收回开关不看配对：不论原来授权给了谁、现在配没配对，都收回`() = runTest {
        val policy = InMemoryDataFlowPolicy(gateway = gatewayA.identity)
        assertEquals(gatewayA.identity, policy.stored().grantedTo)

        assertTrue(policy.setGrantFor(PairingState.NotPaired, granted = false))

        assertNull(policy.stored().grantedTo)
    }

    @Test
    fun `启动对账：授权只留给现在配对着的网关；没配对就都作废；配对记录读不出来时不动`() = runTest {
        fun grantedToA() = InMemoryDataFlowPolicy(gateway = gatewayA.identity)

        grantedToA().also { it.reconcile(PairingState.Paired(gatewayA)) ; assertEquals("同一台网关：保留", gatewayA.identity, it.stored().grantedTo) }
        grantedToA().also { it.reconcile(PairingState.Paired(gatewayB)) ; assertNull("配的是 B：A 的授权作废", it.stored().grantedTo) }
        grantedToA().also { it.reconcile(PairingState.NotPaired) ; assertNull("没配对：作废", it.stored().grantedTo) }
        grantedToA().also { it.reconcile(PairingState.Lost) ; assertEquals("记录读不出来不等于没配对：不动（读的时候本来就按网关核对）", gatewayA.identity, it.stored().grantedTo) }
    }
}
