@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.android.destinations

import app.auri.sync.destinations.ServiceFailure
import app.auri.sync.gateway.Intent
import app.auri.sync.gateway.SendMode
import app.auri.sync.testing.DispatchRig
import app.auri.sync.testing.ScriptedExchange
import app.auri.sync.testing.SyncSamples
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 22 第 1 项：创建 Destination 时必须选择自动发送或预览确认，没有默认值。
 * 视图模型是真的，服务与同步引擎是真的，网关是带 Destination 模型的假网关（FakeGateway）；真网关上的同一件事在 :sync:realGatewayTest。
 */
class DestinationsViewModelTest {
    private fun run(body: suspend TestScope.(DispatchRig, DestinationsViewModel) -> Unit) = runTest {
        val rig = DispatchRig(this)
        val viewModel = DestinationsViewModel(rig.service, backgroundScope, rig.log)
        settle()
        body(rig, viewModel)
    }

    @Test
    fun `一开始没有选发送方式：没有默认值，创建按钮不可用，提交时什么请求也不发`() = run { rig, vm ->
        assertNull("不存在默认的发送方式", vm.state.value.form.sendMode)

        vm.setName("Claude Code")
        runCurrent()
        assertEquals("状态已经跟上了输入", "Claude Code", vm.state.value.form.name)
        assertFalse("名字合格但没选发送方式：不能提交", vm.state.value.form.canSubmit)
        vm.submit()
        settle()

        assertTrue("没有创建请求发给网关", rig.server.createBodies.isEmpty())
        assertTrue(rig.server.destinations.isEmpty())
    }

    @Test
    fun `选了发送方式才能提交，请求里就是用户选的那个：两种都一样`() = run { rig, vm ->
        for (mode in SendMode.entries) {
            vm.setName("目的地-${mode.wire}")
            vm.chooseSendMode(mode)
            runCurrent()
            assertEquals(mode, vm.state.value.form.sendMode)
            assertTrue(vm.state.value.form.canSubmit)

            vm.submit()
            settle()

            assertEquals(mode.wire, rig.server.createBodies.last().getValue("send_mode").jsonPrimitive.content)
            assertNull("创建成功后表单清空：下一个目的地也要重新选，没有“沿用上一次”的默认值", vm.state.value.form.sendMode)
        }

        assertEquals(listOf("auto_send", "preview_confirm"), rig.server.createBodies.map { it.getValue("send_mode").jsonPrimitive.content })
        assertEquals(2, vm.state.value.destinations.size)
    }

    @Test
    fun `名字是空的或超过 100 个字符：不能提交，不发请求`() = run { rig, vm ->
        vm.chooseSendMode(SendMode.AUTO_SEND)
        for (name in listOf("", "   ", "字".repeat(101))) {
            vm.setName(name)
            runCurrent()
            assertEquals(name, vm.state.value.form.name)
            assertFalse("名字 ${name.length} 个字符", vm.state.value.form.canSubmit)
            vm.submit()
        }
        settle()

        assertTrue(rig.server.createBodies.isEmpty())
    }

    @Test
    fun `默认只接收备忘；勾上“请求执行”才在请求里带上它`() = run { rig, vm ->
        vm.setName("A")
        vm.chooseSendMode(SendMode.AUTO_SEND)
        vm.submit()
        settle()
        vm.setName("B")
        vm.chooseSendMode(SendMode.AUTO_SEND)
        vm.setAllowActionRequests(true)
        vm.submit()
        settle()

        val intents = rig.server.createBodies.map { body -> body.getValue("allowed_intents").jsonArray.map { it.jsonPrimitive.content }.toSet() }
        assertEquals(listOf(setOf("context"), setOf("context", "action_request")), intents)
    }

    @Test
    fun `连点两次“创建”只发一个请求（创建没有幂等键，重复请求会多出同名目的地）`() = run { rig, vm ->
        val gate = CompletableDeferred<Unit>()
        rig.lan.hosts.getValue(SyncSamples.LAN_ADDRESS).handler = { request ->
            if (request.method == "POST") gate.await()
            rig.server.handle(request)
        }
        vm.setName("写作助手")
        vm.chooseSendMode(SendMode.PREVIEW_CONFIRM)

        vm.submit()
        runCurrent()
        assertTrue("请求在途", vm.state.value.creating)
        vm.submit()
        vm.submit()
        gate.complete(Unit)
        settle()

        assertEquals(1, rig.server.createBodies.size)
        assertEquals(1, vm.state.value.destinations.size)
        assertFalse(vm.state.value.creating)
    }

    @Test
    fun `结果不确定（请求已到网关、回答读不懂）：表单不清空，原因交给界面，不自动重试`() = run { rig, vm ->
        var posts = 0
        rig.lan.hosts.getValue(SyncSamples.LAN_ADDRESS).handler = { request ->
            if (request.method == "POST") {
                posts++
                ScriptedExchange.json(200, """{"schema_version":"1.2"}""")
            } else {
                rig.server.handle(request)
            }
        }
        vm.setName("写作助手")
        vm.chooseSendMode(SendMode.AUTO_SEND)

        vm.submit()
        settle()

        assertEquals(ServiceFailure.Unreadable, vm.state.value.problem)
        assertEquals("名字还在", "写作助手", vm.state.value.form.name)
        assertEquals("选择还在", SendMode.AUTO_SEND, vm.state.value.form.sendMode)
        assertEquals("只发过一次请求，没有自动重试", 1, posts)

        vm.dismissProblem()
        runCurrent() // 状态是后台协程合并出来的：先让它跟上，再看
        assertNull(vm.state.value.problem)
    }

    @Test
    fun `没有配对：创建失败并说明原因，一个请求也没发`() = runTest {
        val rig = DispatchRig(this, gateway = null)
        val vm = DestinationsViewModel(rig.service, backgroundScope, rig.log)
        vm.setName("A")
        vm.chooseSendMode(SendMode.AUTO_SEND)

        vm.submit()
        settle()

        assertEquals(ServiceFailure.NotPaired, vm.state.value.problem)
        assertTrue(rig.server.createBodies.isEmpty())
    }

    @Test
    fun `列表：有效的在前、已撤销的在后，各自新的在前；每一项带着发送方式与接收的标签`() = run { rig, vm ->
        val first = rig.createDestination("第一个", SendMode.AUTO_SEND)
        rig.createDestination("第二个", SendMode.PREVIEW_CONFIRM, setOf(Intent.CONTEXT, Intent.ACTION_REQUEST))
        rig.service.revoke(first.destinationId)
        settle()

        val list = vm.state.value.destinations
        assertEquals(listOf("第二个", "第一个"), list.map { it.name })
        assertEquals(listOf(true, false), list.map { it.active })
        assertEquals(SendMode.PREVIEW_CONFIRM, list[0].sendMode)
        assertEquals(setOf(Intent.CONTEXT, Intent.ACTION_REQUEST), list[0].allowedIntents)
    }

    @Test
    fun `凭证被撤销（暂停）的目的地：列表项标着已暂停，仍然排在有效的那一组（它没有被撤销）；换了新凭证就不再暂停（评审 B3）`() = run { rig, vm ->
        val older = rig.createDestination("暂停的", SendMode.AUTO_SEND)
        rig.service.issueCredential(older.destinationId)
        delay(1_000) // 虚拟时间：创建时间不同，排序才不靠并列时的次序
        val newer = rig.createDestination("正常的", SendMode.AUTO_SEND)
        rig.server.revokeCredential(older.destinationId)
        rig.service.refresh()
        settle()

        val list = vm.state.value.destinations
        assertEquals(listOf("正常的", "暂停的"), list.map { it.name })
        assertEquals(listOf(false, true), list.map { it.suspended })
        assertEquals("暂停的不是撤销的", listOf(true, true), list.map { it.active })

        rig.service.issueCredential(older.destinationId)
        settle()

        assertEquals(listOf(false, false), vm.state.value.destinations.map { it.suspended })
        assertEquals(newer.destinationId, vm.state.value.destinations.first().destinationId)
    }

    @Test
    fun `启动时向网关要一次完整列表：网关上已有的出现在列表里`() = runTest {
        val rig = DispatchRig(this)
        val existing = rig.createDestination("网关上早就有的", SendMode.AUTO_SEND).destinationId
        rig.dispatch.forgetDestinations(rig.clock.now()) // 本机缓存是空的

        val vm = DestinationsViewModel(rig.service, backgroundScope, rig.log)
        settle()

        assertEquals(listOf(existing), vm.state.value.destinations.map { it.destinationId })
        assertFalse(vm.state.value.refreshing)
    }
}
