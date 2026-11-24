package app.auri.android.sync

import app.auri.sync.SyncController
import app.auri.sync.SyncLog
import app.auri.sync.engine.RetryPolicy
import app.auri.sync.pairing.DeviceToken
import app.auri.sync.pairing.GatewayAddress
import app.auri.sync.pairing.PairedGateway
import app.auri.sync.pairing.PairingOutcome
import app.auri.sync.pairing.PairingPayload
import app.auri.sync.pairing.PayloadParse
import app.auri.sync.policy.DataFlowState
import app.auri.sync.status.SyncCounts
import app.auri.sync.status.SyncHeadline
import app.auri.sync.status.SyncStatus
import app.auri.sync.tls.SpkiPin
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同步页的数据层：配对的每一种结局界面都能分辨，取景重复的帧不会触发第二次配对，release 版没有手动入口。
 * 视图模型本身是真的；它只通过 SyncController 与外界相连，这里用假的（不需要 Android、网络与数据库）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncViewModelTest {
    private class FakeController : SyncController {
        val statuses = MutableSharedFlow<SyncStatus>(replay = 1)
        override val status: Flow<SyncStatus> = statuses
        val pairCalls = AtomicInteger()
        val payloads = CopyOnWriteArrayList<PairingPayload>()
        var syncNowCalls = 0

        /** 一次配对怎么结束：默认立刻成功；测试可以换成挂起或抛异常。 */
        var onPair: suspend (PairingPayload) -> PairingOutcome = { PairingOutcome.Paired(pairedGateway()) }

        override suspend fun pair(payload: PairingPayload): PairingOutcome {
            pairCalls.incrementAndGet()
            payloads += payload
            return onPair(payload)
        }

        override fun syncNow() {
            syncNowCalls += 1
        }

        val grants = CopyOnWriteArrayList<Boolean>()
        val strictLocals = CopyOnWriteArrayList<Boolean>()

        /** 写开关时抛的故障（null = 正常）。 */
        @Volatile var policyWriteFailure: Throwable? = null

        override suspend fun setGatewayTextGranted(granted: Boolean) {
            policyWriteFailure?.let { throw it }
            grants += granted
        }

        override suspend fun setStrictLocal(on: Boolean) {
            policyWriteFailure?.let { throw it }
            strictLocals += on
        }
    }

    private companion object {
        fun payloadText(gatewayId: String = "fa1b98ec-1bfb-53b3-85a3-4d36b680fe6e", code: String = "FIXTURE-PAIRING-CODE-NOT-A-SECRET") = """
            {"schema_version":"1.2","gateway_id":"$gatewayId","addresses":[{"host":"192.168.1.20","port":8443}],
             "mdns_service_type":"_auri-gateway._tcp","public_key_sha256":"${"ab".repeat(32)}","pairing_code":"$code","expires_at":"2099-01-01T00:00:00Z"}
        """.trimIndent().replace("\n", "")

        val OTHER_QR = payloadText(code = "ANOTHER-FIXTURE-CODE-NOT-A-SECRET")

        fun pairedGateway() = PairedGateway(
            "fa1b98ec-1bfb-53b3-85a3-4d36b680fe6e", "ef7b1358-df31-520f-90f3-d98fc5173b46", SpkiPin.parse("ab".repeat(32))!!,
            listOf(GatewayAddress("192.168.1.20", 8443)), null, DeviceToken.parse("fixture-device-token-not-a-real-secret-0001")!!,
            Instant.parse("2026-11-15T02:05:00Z"), null,
        )

        fun status(headline: SyncHeadline = SyncHeadline.UP_TO_DATE) = SyncStatus(headline, emptyList(), SyncCounts(0, 0, 0, 0, 0, 0), null, DataFlowState(gatewayTextGranted = true, strictLocal = false))
    }

    private fun TestScope.viewModel(controller: FakeController) =
        SyncViewModel(controller, CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob()))

    @Test
    fun `读到配对记录之前没有同步状态，读到之后原样给出，同步状态不被配对动作改写`() = runTest {
        val controller = FakeController()
        val vm = viewModel(controller)

        assertNull(vm.state.value.status)
        assertEquals(PairingProgress.Idle, vm.state.value.pairing)
        controller.statuses.emit(status(SyncHeadline.WAITING_FOR_GATEWAY))
        assertEquals(SyncHeadline.WAITING_FOR_GATEWAY, vm.state.value.status?.headline)
    }

    // ---- F4：同步状态流读本机的任务表，它抛出的异常不能把进程带崩 ----

    /** 读状态的那条流：第一次订阅先给一个状态再抛异常（SQLite 暂时读不出来），之后恢复正常。 */
    private class FlakyStatusController(private val failures: Int, private val firstValue: SyncStatus? = null) : SyncController {
        val subscriptions = AtomicInteger()

        override val status: Flow<SyncStatus> = flow {
            val round = subscriptions.incrementAndGet()
            if (round <= failures) {
                firstValue?.let { emit(it) }
                throw IllegalStateException("database is locked: secret-looking-detail")
            }
            emit(status(SyncHeadline.UP_TO_DATE))
        }

        override suspend fun pair(payload: PairingPayload): PairingOutcome = error("not used")

        override fun syncNow() = Unit

        override suspend fun setGatewayTextGranted(granted: Boolean) = Unit

        override suspend fun setStrictLocal(on: Boolean) = Unit
    }

    private class Escapes : CoroutineExceptionHandler {
        val caught = CopyOnWriteArrayList<Throwable>()
        override val key = CoroutineExceptionHandler.Key
        override fun handleException(context: kotlin.coroutines.CoroutineContext, exception: Throwable) {
            caught += exception
        }
    }

    private fun TestScope.viewModelOf(controller: SyncController, escapes: Escapes, log: SyncLog = SyncLog.None) =
        SyncViewModel(controller, CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob() + escapes), log)

    @Test
    fun `状态流读本机存储时出了故障：异常不会冲出视图模型的作用域（否则进程崩溃），界面进入“存储故障”状态并保留最后读到的状态`() = runTest {
        val escapes = Escapes()
        val vm = viewModelOf(FlakyStatusController(failures = 1, firstValue = status(SyncHeadline.SENDING)), escapes)
        runCurrent()

        assertTrue("异常冲出了作用域（Android 上就是进程崩溃）: ${escapes.caught}", escapes.caught.isEmpty())
        assertTrue("界面应当知道本机存储暂时读不了", vm.state.value.storageProblem)
        assertEquals("最后一次读到的状态还在，界面不闪回“读取中”", SyncHeadline.SENDING, vm.state.value.status?.headline)
    }

    @Test
    fun `状态流连续出故障：重新订阅的间隔 5 秒、10 秒地往上翻，读得出来了就恢复，存储故障状态消失`() = runTest {
        val escapes = Escapes()
        val controller = FlakyStatusController(failures = 2)
        val vm = viewModelOf(controller, escapes)
        runCurrent()
        assertEquals("第一次订阅就失败了", 1, controller.subscriptions.get())

        advanceTimeBy(RetryPolicy().delayMs(1) - 1)
        runCurrent()
        assertEquals("退避没到：不重新订阅", 1, controller.subscriptions.get())
        advanceTimeBy(1)
        runCurrent()
        assertEquals("第一次退避到了：重新订阅，又失败", 2, controller.subscriptions.get())
        assertTrue(vm.state.value.storageProblem)

        advanceTimeBy(RetryPolicy().delayMs(2) - 1)
        runCurrent()
        assertEquals("连续第二次失败，退避翻倍：还没到", 2, controller.subscriptions.get())
        advanceTimeBy(1)
        runCurrent()

        assertEquals(3, controller.subscriptions.get())
        assertFalse("读得出来了：存储故障状态消失", vm.state.value.storageProblem)
        assertEquals(SyncHeadline.UP_TO_DATE, vm.state.value.status?.headline)
        assertTrue(escapes.caught.isEmpty())
    }

    @Test
    fun `两次故障之间读到过状态：退避从头算，不是接着上一次往上翻`() = runTest {
        val controller = FlakyStatusController(failures = 2, firstValue = status(SyncHeadline.SENDING))
        val vm = viewModelOf(controller, Escapes())
        runCurrent()

        advanceTimeBy(RetryPolicy().delayMs(1))
        runCurrent()
        assertEquals(2, controller.subscriptions.get())
        advanceTimeBy(RetryPolicy().delayMs(1)) // 第二次订阅读到过状态才失败：这次只等 5 秒，不是 10 秒
        runCurrent()

        assertEquals(3, controller.subscriptions.get())
        assertFalse(vm.state.value.storageProblem)
    }

    @Test
    fun `状态流出故障只记一条不含内容的日志（异常类名），异常消息不进日志`() = runTest {
        val lines = CopyOnWriteArrayList<String>()
        viewModelOf(FlakyStatusController(failures = 1), Escapes(), SyncLog { lines += it })
        runCurrent()

        assertEquals(listOf("sync.status.failed IllegalStateException"), lines.toList())
    }

    @Test
    fun `合规的二维码开始配对，配对中是 Pairing，成功后回到 Idle`() = runTest {
        val controller = FakeController()
        val gate = CompletableDeferred<PairingOutcome>()
        controller.onPair = { gate.await() }
        val vm = viewModel(controller)

        vm.onQrText(payloadText())

        assertEquals(PairingProgress.Pairing, vm.state.value.pairing)
        assertEquals(1, controller.pairCalls.get())
        assertEquals("fa1b98ec-1bfb-53b3-85a3-4d36b680fe6e", controller.payloads.single().gatewayId)
        gate.complete(PairingOutcome.Paired(pairedGateway()))
        assertEquals(PairingProgress.Idle, vm.state.value.pairing)
    }

    @Test
    fun `不是配对载荷的二维码给出 INVALID_QR，不会去配对，关掉提示后回到 Idle`() = runTest {
        val controller = FakeController()
        val vm = viewModel(controller)

        vm.onQrText("https://example.com/not-auri")
        vm.onQrText("""{"schema_version":"1.2"}""")

        assertEquals(PairingProgress.Failed(PairingProblem.INVALID_QR), vm.state.value.pairing)
        assertEquals(0, controller.pairCalls.get())
        vm.dismissPairingProblem()
        assertEquals(PairingProgress.Idle, vm.state.value.pairing)
    }

    @Test
    fun `二维码是旧合同版本的网关出的：界面说网关太旧，不是二维码坏了，也不会去配对（评审 R7）`() = runTest {
        val controller = FakeController()
        val vm = viewModel(controller)

        vm.onQrText(payloadText().replace("\"schema_version\":\"1.2\"", "\"schema_version\":\"1.1\""))

        assertEquals(PairingProgress.Failed(PairingProblem.GATEWAY_TOO_OLD), vm.state.value.pairing)
        assertEquals(0, controller.pairCalls.get())
    }

    @Test
    fun `配对的每一种结局界面都分得清：失败原因两两不同`() = runTest {
        val outcomes = listOf(
            PairingOutcome.Expired to PairingProblem.EXPIRED,
            PairingOutcome.NotFound to PairingProblem.NOT_FOUND,
            PairingOutcome.KeyMismatch to PairingProblem.KEY_MISMATCH,
            PairingOutcome.CodeRejected to PairingProblem.CODE_REJECTED,
            PairingOutcome.GatewayError(500, null) to PairingProblem.GATEWAY_ERROR,
            PairingOutcome.Uncertain to PairingProblem.UNCERTAIN,
            PairingOutcome.WrongGateway to PairingProblem.WRONG_GATEWAY,
            PairingOutcome.NotSaved to PairingProblem.NOT_SAVED,
        )
        for ((outcome, problem) in outcomes) {
            val controller = FakeController().apply { onPair = { outcome } }
            val vm = viewModel(controller)

            vm.onQrText(payloadText())

            assertEquals("$outcome", PairingProgress.Failed(problem), vm.state.value.pairing)
        }
        assertEquals(outcomes.size, outcomes.map { it.second }.toSet().size)
    }

    @Test
    fun `配对失败之后同一张二维码可以重试（没有成功就没有用掉）`() = runTest {
        val controller = FakeController().apply { onPair = { PairingOutcome.NotFound } }
        val vm = viewModel(controller)

        vm.onQrText(payloadText())
        assertEquals(PairingProgress.Failed(PairingProblem.NOT_FOUND), vm.state.value.pairing)
        controller.onPair = { PairingOutcome.Paired(pairedGateway()) }
        vm.onQrText(payloadText())

        assertEquals(2, controller.pairCalls.get())
        assertEquals(PairingProgress.Idle, vm.state.value.pairing)
    }

    @Test
    fun `配对进行中取景重复送来的帧被忽略，只配对一次`() = runTest {
        val controller = FakeController()
        val gate = CompletableDeferred<PairingOutcome>()
        controller.onPair = { gate.await() }
        val vm = viewModel(controller)

        repeat(30) { vm.onQrText(payloadText()) }
        vm.onQrText(OTHER_QR)

        assertEquals(1, controller.pairCalls.get())
        gate.complete(PairingOutcome.Paired(pairedGateway()))
    }

    @Test
    fun `配对成功之后同一张二维码在路上的帧不会触发第二次配对，也不会冒出失败提示`() = runTest {
        val controller = FakeController()
        val vm = viewModel(controller)

        vm.onQrText(payloadText())
        repeat(10) { vm.onQrText("  " + payloadText() + "\n") }

        assertEquals(1, controller.pairCalls.get())
        assertEquals(PairingProgress.Idle, vm.state.value.pairing)
    }

    @Test
    fun `配对成功之后换一张新的二维码（重新配对）是允许的`() = runTest {
        val controller = FakeController()
        val vm = viewModel(controller)

        vm.onQrText(payloadText())
        vm.onQrText(OTHER_QR)

        assertEquals(2, controller.pairCalls.get())
    }

    @Test
    fun `配对流程抛了意料之外的异常：界面不会一直停在正在配对`() = runTest {
        val controller = FakeController().apply { onPair = { error("boom") } }
        val vm = viewModel(controller)

        vm.onQrText(payloadText())

        assertEquals(PairingProgress.Failed(PairingProblem.UNKNOWN), vm.state.value.pairing)
    }

    @Test
    fun `现在同步转给同步控制器`() = runTest {
        val controller = FakeController()
        val vm = viewModel(controller)

        vm.syncNow()

        assertEquals(1, controller.syncNowCalls)
    }

    // ---- 评审 B1：文字能不能进网关的两道开关 ----

    @Test
    fun `拨开关：授权与严格仅本地原样交给控制器，开关位置来自同步状态里的 dataFlow`() = runTest {
        val controller = FakeController()
        val vm = viewModel(controller)
        controller.statuses.emit(status(SyncHeadline.HELD_BY_POLICY).copy(dataFlow = DataFlowState.Unset))
        assertEquals(DataFlowState.Unset, vm.state.value.status?.dataFlow)

        vm.setGatewayTextGranted(true)
        vm.setStrictLocal(true)
        vm.setGatewayTextGranted(false)

        assertEquals(listOf(true, false), controller.grants.toList())
        assertEquals(listOf(true), controller.strictLocals.toList())
        assertFalse(vm.state.value.policyNotSaved)
    }

    @Test
    fun `开关写不进去：如实说没保存，不能把进程带崩；下一次拨成功就清掉提示`() = runTest {
        val controller = FakeController()
        val escapes = Escapes()
        val vm = viewModelOf(controller, escapes)
        controller.policyWriteFailure = IllegalStateException("disk I/O error: secret-looking-detail")

        vm.setGatewayTextGranted(true)

        assertTrue("界面要如实提示没保存", vm.state.value.policyNotSaved)
        assertTrue("异常不能冲出作用域（这个作用域没有异常处理器，冲出去就是应用崩溃）：${escapes.caught}", escapes.caught.isEmpty())
        assertTrue("没存下就不能假装存了", controller.grants.isEmpty())

        controller.policyWriteFailure = null
        vm.setGatewayTextGranted(true)

        assertFalse(vm.state.value.policyNotSaved)
        assertEquals(listOf(true), controller.grants.toList())
    }

    @Test
    fun `界面状态里没有令牌、配对码和公钥指纹`() = runTest {
        val controller = FakeController()
        val vm = viewModel(controller)

        vm.onQrText(payloadText())
        controller.statuses.emit(status())

        val text = vm.state.value.toString()
        assertFalse(text, "FIXTURE-PAIRING-CODE" in text || "fixture-device-token" in text || "ab".repeat(32) in text)
    }

    @Test
    fun `多个线程同时送来同一帧：恰好只有一次配对`() {
        repeat(200) {
            val controller = FakeController()
            val gate = CompletableDeferred<PairingOutcome>()
            controller.onPair = { gate.await() }
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val vm = SyncViewModel(controller, scope)
            val threads = 8
            val barrier = CyclicBarrier(threads)
            val workers = List(threads) {
                thread {
                    barrier.await()
                    vm.onQrText(payloadText())
                }
            }
            workers.forEach { it.join() }
            Thread.sleep(2)

            assertEquals("第 $it 轮", 1, controller.pairCalls.get())
            gate.complete(PairingOutcome.Paired(pairedGateway()))
            scope.cancel()
        }
    }

    @Test
    fun `测试用的载荷确实是合规的（否则上面的用例都在测 INVALID_QR）`() {
        assertTrue(PairingPayload.parse(payloadText()) is PayloadParse.Valid)
        assertTrue(PairingPayload.parse(OTHER_QR) is PayloadParse.Valid)
    }
}
