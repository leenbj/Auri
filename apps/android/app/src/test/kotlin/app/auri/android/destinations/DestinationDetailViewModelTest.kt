@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.android.destinations

import app.auri.sync.destinations.AuthPurpose
import app.auri.sync.destinations.AuthResult
import app.auri.sync.destinations.DeliveryView
import app.auri.sync.destinations.GatewayStage
import app.auri.sync.destinations.ServiceFailure
import app.auri.sync.destinations.UnavailableReason
import app.auri.sync.destinations.UnknownReason
import app.auri.sync.gateway.SendMode
import app.auri.sync.testing.DispatchRig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 22 第 2 项（凭证：生成或轮换前必须过本机核验；只显示一次）与第 6 项（详情页每条内容的状态）。
 * 服务、引擎、视图模型是真的；本机核验是按剧本回答的假的（ScriptedAuthenticator）；网关是带 Destination 与投递模型的假网关。
 */
class DestinationDetailViewModelTest {
    private class Page(val rig: DispatchRig, val destinationId: String, val viewModel: DestinationDetailViewModel)

    private fun run(mode: SendMode = SendMode.AUTO_SEND, body: suspend TestScope.(Page) -> Unit) = runTest {
        val rig = DispatchRig(this)
        val destination = rig.createDestination("写作助手", mode)
        val viewModel = DestinationDetailViewModel(destination.destinationId, rig.service, LibraryItemTexts(rig.library), backgroundScope, rig.log)
        settle()
        body(Page(rig, destination.destinationId, viewModel))
    }

    private val Page.credential get() = viewModel.state.value.credential

    private fun CredentialUi.revealed(): String = (this as? CredentialUi.Shown)?.credential?.reveal() ?: error("期望正在显示凭证，实际 $this")

    // ---- 凭证：每次先核验；只显示一次 ----

    @Test
    fun `生成凭证：核验通过后显示出来，显示的就是网关签发的那一个`() = run { page ->
        page.viewModel.requestCredential()
        settle()

        val shown = page.credential.revealed()
        assertTrue(shown.isNotBlank())
        assertEquals("界面上的就是网关签发的", page.rig.server.destinations.getValue(page.destinationId).credential, shown)
        assertEquals("核验过一次，目的是首次生成", listOf(AuthPurpose.ISSUE_CREDENTIAL), page.rig.authenticator.purposes.toList())
    }

    @Test
    fun `凭证只显示一次：复制之后、关掉面板之后、离开页面之后，都没有了，界面状态里再也找不到它`() {
        for (wipe in listOf<(DestinationDetailViewModel) -> Unit>({ it.credentialCopied() }, { it.dismissCredential() }, { it.leave() })) {
            runTest {
                val rig = DispatchRig(this)
                val id = rig.createDestination("A", SendMode.AUTO_SEND).destinationId
                val vm = DestinationDetailViewModel(id, rig.service, LibraryItemTexts(rig.library), backgroundScope, rig.log)
                settle() // 页面先读到缓存里的这个目的地，才点得了“生成”
                vm.requestCredential()
                settle()
                val secret = vm.state.value.credential.revealed()

                wipe(vm)
                settle()

                assertEquals(CredentialUi.Hidden, vm.state.value.credential)
                assertFalse("整个界面状态里都没有它", vm.state.value.toString().contains(secret))
                // 状态里也没有别的地方留着它：把当前状态里每个字段的字符串都过一遍
                assertFalse(vm.state.value.items.any { it.toString().contains(secret) })
            }
        }
    }

    @Test
    fun `凭证显示期间的界面状态，字符串形式里也没有凭证本身（日志里不小心打出状态也不会泄露）`() = run { page ->
        page.viewModel.requestCredential()
        settle()
        val secret = page.credential.revealed()

        assertFalse(page.viewModel.state.value.toString().contains(secret))
        assertFalse(page.credential.toString().contains(secret))
    }

    @Test
    fun `核验没通过（拒绝、取消、没有硬件、没登记、暂时不可用）：状态说明原因，一个凭证请求也没发给网关`() = run { page ->
        val failures = listOf(
            AuthResult.Denied,
            AuthResult.Cancelled,
            AuthResult.Unavailable(UnavailableReason.NO_HARDWARE),
            AuthResult.Unavailable(UnavailableReason.NONE_ENROLLED),
            AuthResult.Unavailable(UnavailableReason.TEMPORARILY_UNAVAILABLE),
        )
        for (result in failures) {
            page.rig.authenticator.next = result
            page.viewModel.requestCredential()
            settle()

            assertEquals(CredentialUi.NotAuthenticated(result), page.credential)
            page.viewModel.dismissCredential()
        }

        assertTrue("没有一个凭证请求到达网关", page.rig.server.credentialRequests.isEmpty())
        assertEquals("核验被问了 ${failures.size} 次，每次都是现场问的", failures.size, page.rig.authenticator.purposes.size)
        assertNull(page.rig.server.destinations.getValue(page.destinationId).credential)
    }

    @Test
    fun `生成凭证要核验的时候，核验框弹着期间是“进行中”，不会因为再点一下又弹一个`() = run { page ->
        val gate = CompletableDeferred<Unit>()
        page.rig.authenticator.gate = { gate.await() }

        page.viewModel.requestCredential()
        runCurrent()
        assertEquals(CredentialUi.Working, page.credential)
        page.viewModel.requestCredential()
        page.viewModel.requestCredential()
        gate.complete(Unit)
        settle()

        assertEquals("核验只弹了一次", 1, page.rig.authenticator.purposes.size)
        page.credential.revealed()
    }

    @Test
    fun `等核验或网关的时候离开了页面：之后才到的凭证不显示，也不留在任何地方`() = run { page ->
        val gate = CompletableDeferred<Unit>()
        page.rig.authenticator.gate = { gate.await() }
        page.viewModel.requestCredential()
        runCurrent()

        page.viewModel.leave()
        gate.complete(Unit)
        settle()

        assertEquals("页面都离开了，没人看得到这个凭证，也就不显示", CredentialUi.Hidden, page.credential)
        // 回到页面：还是空的，没有“上一次的凭证”可以找回
        page.viewModel.dismissCredential()
        runCurrent()
        assertEquals(CredentialUi.Hidden, page.credential)
    }

    @Test
    fun `进程被杀后重新进来：新建出来的页面上没有任何凭证（凭证没有被存在任何地方）`() = runTest {
        val rig = DispatchRig(this)
        val id = rig.createDestination("A", SendMode.AUTO_SEND).destinationId
        val scope1 = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val before = DestinationDetailViewModel(id, rig.service, LibraryItemTexts(rig.library), scope1, rig.log)
        settle()
        before.requestCredential()
        settle()
        before.state.value.credential.revealed()

        // 进程被杀：所有进程内的对象都没了，磁盘留着
        scope1.cancel()
        rig.restart()
        val after = DestinationDetailViewModel(id, rig.service, LibraryItemTexts(rig.library), backgroundScope, rig.log)
        settle()

        assertEquals(CredentialUi.Hidden, after.state.value.credential)
        assertTrue("网关记得签发过（所以再生成是轮换），但凭证本身没人记得", after.state.value.destination!!.credentialIssued)
    }

    @Test
    fun `界面状态的类都不是 Parcelable 也不是 Serializable：保存实例状态时不会把凭证带进去`() {
        val classes = listOf(
            DestinationDetailUiState::class.java,
            CredentialUi::class.java,
            CredentialUi.Hidden::class.java,
            CredentialUi.ConfirmRotate::class.java,
            CredentialUi.Working::class.java,
            CredentialUi.Shown::class.java,
            CredentialUi.NotAuthenticated::class.java,
            CredentialUi.Failed::class.java,
            DestinationsUiState::class.java,
            SendReviewUiState::class.java,
        )
        val parcelable = runCatching { Class.forName("android.os.Parcelable") }.getOrNull()

        for (type in classes) {
            assertFalse("${type.simpleName} 不能是 Serializable", java.io.Serializable::class.java.isAssignableFrom(type))
            if (parcelable != null) assertFalse("${type.simpleName} 不能是 Parcelable", parcelable.isAssignableFrom(type))
        }
        assertTrue("凭证类自己也不是", !java.io.Serializable::class.java.isAssignableFrom(app.auri.sync.gateway.DestinationCredential::class.java))
    }

    // ---- 轮换 ----

    @Test
    fun `已经签发过凭证再点生成就是轮换：先说清旧凭证会失效、等确认；确认前没有核验也没有请求`() = run { page ->
        page.viewModel.requestCredential()
        settle()
        val first = page.credential.revealed()
        page.viewModel.credentialCopied()
        settle()
        assertTrue(page.viewModel.state.value.destination!!.credentialIssued)

        page.viewModel.requestCredential()
        settle()

        assertEquals(CredentialUi.ConfirmRotate, page.credential)
        assertEquals("确认之前没有再弹核验", 1, page.rig.authenticator.purposes.size)
        assertEquals("确认之前没有再发凭证请求", 1, page.rig.server.credentialRequests.size)

        page.viewModel.cancelRotate()
        runCurrent()
        assertEquals(CredentialUi.Hidden, page.credential)
        assertEquals(first, page.rig.server.destinations.getValue(page.destinationId).credential)
    }

    @Test
    fun `确认轮换：再核验一次（不沿用上一次的通过），旧凭证作废，新凭证只显示这一次`() = run { page ->
        page.viewModel.requestCredential()
        settle()
        val first = page.credential.revealed()
        page.viewModel.credentialCopied()
        settle()

        page.viewModel.requestCredential()
        page.viewModel.confirmRotate()
        settle()

        val second = page.credential.revealed()
        assertNotEquals("轮换出来的是新的", first, second)
        assertEquals("网关上现在只认新的（旧的已作废）", second, page.rig.server.destinations.getValue(page.destinationId).credential)
        assertEquals(listOf(AuthPurpose.ISSUE_CREDENTIAL, AuthPurpose.ROTATE_CREDENTIAL), page.rig.authenticator.purposes.toList())
    }

    @Test
    fun `轮换时核验没通过：旧凭证原样有效，没有请求`() = run { page ->
        page.viewModel.requestCredential()
        settle()
        val first = page.credential.revealed()
        page.viewModel.credentialCopied()
        settle()

        page.rig.authenticator.next = AuthResult.Cancelled
        page.viewModel.requestCredential()
        page.viewModel.confirmRotate()
        settle()

        assertEquals(CredentialUi.NotAuthenticated(AuthResult.Cancelled), page.credential)
        assertEquals(first, page.rig.server.destinations.getValue(page.destinationId).credential)
        assertEquals(1, page.rig.server.credentialRequests.size)
    }

    // ---- 撤销 ----

    @Test
    fun `撤销：先确认；取消确认什么也不发；确认之后目的地显示已撤销，凭证面板也抹掉了`() = run { page ->
        page.viewModel.requestCredential()
        settle()
        page.credential.revealed()

        page.viewModel.requestRevoke()
        runCurrent()
        assertEquals(RevokeUi.CONFIRMING, page.viewModel.state.value.revoke)
        page.viewModel.cancelRevoke()
        settle()
        assertEquals(RevokeUi.IDLE, page.viewModel.state.value.revoke)
        assertTrue("取消确认没有发任何撤销请求", page.rig.server.adminCalls.none { it == "DELETE destination" })

        page.viewModel.requestRevoke()
        page.viewModel.confirmRevoke()
        page.viewModel.confirmRevoke() // 双击确认
        settle()

        assertEquals(1, page.rig.server.adminCalls.count { it == "DELETE destination" })
        assertFalse(page.viewModel.state.value.destination!!.active)
        assertEquals(CredentialUi.Hidden, page.credential)
        assertEquals(RevokeUi.IDLE, page.viewModel.state.value.revoke)
    }

    @Test
    fun `已撤销的目的地：不能再生成凭证（不弹核验、不联网）`() = run { page ->
        page.viewModel.requestRevoke()
        page.viewModel.confirmRevoke()
        settle()

        page.viewModel.requestCredential()
        settle()

        assertEquals(CredentialUi.Hidden, page.credential)
        assertTrue(page.rig.authenticator.purposes.isEmpty())
        assertTrue(page.rig.server.credentialRequests.isEmpty())
    }

    @Test
    fun `撤销失败（连不上网关）：原因交给界面，目的地仍然有效，确认状态回到初始`() = run { page ->
        page.rig.lan.hosts.clear()

        page.viewModel.requestRevoke()
        page.viewModel.confirmRevoke()
        settle()

        assertEquals(ServiceFailure.Unreachable, page.viewModel.state.value.problem)
        assertTrue(page.viewModel.state.value.destination!!.active)
        assertEquals(RevokeUi.IDLE, page.viewModel.state.value.revoke)
    }

    // ---- 第 6 项：每条内容的状态，网关怎么说就怎么显示 ----

    @Test
    fun `一条自动发送的内容依次显示：已入库 → 已领取 → 已确认；最后一步也只说“已确认”`() = run { page ->
        val key = page.rig.transcribed("明天检查小说记忆模块")
        settle()
        assertEquals(DeliveryView.Queued(app.auri.sync.destinations.QueuedPhase.SENDING), page.viewModel.state.value.items.single().view)

        page.rig.engine.runOnce()
        page.viewModel.refreshStatuses()
        settle()
        assertEquals(DeliveryView.OnGateway(GatewayStage.STORED), page.viewModel.state.value.items.single().view)

        page.rig.server.agentClaims(page.destinationId, key.recordId)
        page.viewModel.refreshStatuses()
        settle()
        assertEquals(DeliveryView.OnGateway(GatewayStage.CLAIMED), page.viewModel.state.value.items.single().view)

        page.rig.server.agentAcknowledges(page.destinationId, key.recordId)
        page.viewModel.refreshStatuses()
        settle()
        val row = page.viewModel.state.value.items.single()
        assertEquals(DeliveryView.OnGateway(GatewayStage.ACKNOWLEDGED), row.view)
        assertEquals(key, row.key)
        assertEquals("明天检查小说记忆模块", row.excerpt)
        assertFalse("没有一种说法把已确认说成已处理", PROCESSED_WORDS.any { describeDelivery(row.view).contains(it) })
    }

    @Test
    fun `网关给了不认识的状态、或问不到网关：显示未知，不显示成功`() = run { page ->
        page.rig.transcribed()
        page.rig.engine.runOnce()
        page.rig.server.deliveries.single().state = "SOMETHING_NEW_IN_V2"
        page.viewModel.refreshStatuses()
        settle()
        assertEquals(DeliveryView.Unknown(UnknownReason.UNRECOGNIZED_STATE), page.viewModel.state.value.items.single().view)

        page.rig.lan.hosts.clear()
        page.viewModel.refreshStatuses()
        settle()
        assertEquals(DeliveryView.Unknown(UnknownReason.GATEWAY_UNREACHABLE), page.viewModel.state.value.items.single().view)
    }

    @Test
    fun `预览确认的目的地：确认前这一条是“等你确认”，网关上一个投递也没有`() = run(SendMode.PREVIEW_CONFIRM) { page ->
        page.rig.transcribed()
        page.rig.engine.runOnce()
        page.viewModel.refreshStatuses()
        settle()

        assertEquals(DeliveryView.AwaitingYou, page.viewModel.state.value.items.single().view)
        assertTrue(page.rig.server.deliveries.isEmpty())
        assertEquals(0, page.rig.server.putCount)
    }

    @Test
    fun `详情页最多问网关 20 条的状态`() = run { page ->
        repeat(25) { page.rig.transcribed("第 $it 条") }
        page.rig.engine.runOnce()
        settle()
        val before = page.rig.server.statusCount

        page.viewModel.refreshStatuses()
        settle()

        assertEquals(20, page.viewModel.state.value.items.size)
        assertTrue("一次刷新最多问 20 条：${page.rig.server.statusCount - before}", page.rig.server.statusCount - before <= 20)
    }

    @Test
    fun `没有这个目的地（缓存里没有）：loaded 为真而 destination 为空，界面据此说找不到`() = runTest {
        val rig = DispatchRig(this)
        val vm = DestinationDetailViewModel("d0000000-0000-4000-8000-0000000000ff", rig.service, LibraryItemTexts(rig.library), backgroundScope, rig.log)
        assertFalse("还没读缓存：不是“找不到”", vm.state.value.loaded)
        settle()

        assertTrue(vm.state.value.loaded)
        assertNull(vm.state.value.destination)
        vm.requestCredential()
        settle()
        assertTrue("不认识的目的地不弹核验", rig.authenticator.purposes.isEmpty())
    }
}
