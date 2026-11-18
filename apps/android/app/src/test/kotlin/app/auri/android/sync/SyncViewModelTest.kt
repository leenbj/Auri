package app.auri.android.sync

import app.auri.sync.SyncController
import app.auri.sync.pairing.DeviceToken
import app.auri.sync.pairing.GatewayAddress
import app.auri.sync.pairing.PairedGateway
import app.auri.sync.pairing.PairingOutcome
import app.auri.sync.pairing.PairingPayload
import app.auri.sync.pairing.PayloadParse
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
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
    }

    private companion object {
        fun payloadText(gatewayId: String = "fa1b98ec-1bfb-53b3-85a3-4d36b680fe6e", code: String = "FIXTURE-PAIRING-CODE-NOT-A-SECRET") = """
            {"schema_version":"1.1","gateway_id":"$gatewayId","addresses":[{"host":"192.168.1.20","port":8443}],
             "mdns_service_type":"_auri-gateway._tcp","public_key_sha256":"${"ab".repeat(32)}","pairing_code":"$code","expires_at":"2099-01-01T00:00:00Z"}
        """.trimIndent().replace("\n", "")

        val OTHER_QR = payloadText(code = "ANOTHER-FIXTURE-CODE-NOT-A-SECRET")

        fun pairedGateway() = PairedGateway(
            "fa1b98ec-1bfb-53b3-85a3-4d36b680fe6e", "ef7b1358-df31-520f-90f3-d98fc5173b46", SpkiPin.parse("ab".repeat(32))!!,
            listOf(GatewayAddress("192.168.1.20", 8443)), null, DeviceToken.parse("fixture-device-token-not-a-real-secret-0001")!!,
            Instant.parse("2026-11-15T02:05:00Z"), null,
        )

        fun status(headline: SyncHeadline = SyncHeadline.UP_TO_DATE) = SyncStatus(headline, emptyList(), SyncCounts(0, 0, 0, 0, 0, 0), null)
    }

    private fun TestScope.viewModel(controller: FakeController, manualEntry: Boolean = true) =
        SyncViewModel(controller, CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob()), manualEntry)

    @Test
    fun `读到配对记录之前没有同步状态，读到之后原样给出，同步状态不被配对动作改写`() = runTest {
        val controller = FakeController()
        val vm = viewModel(controller)

        assertNull(vm.state.value.status)
        assertEquals(PairingProgress.Idle, vm.state.value.pairing)
        controller.statuses.emit(status(SyncHeadline.WAITING_FOR_GATEWAY))
        assertEquals(SyncHeadline.WAITING_FOR_GATEWAY, vm.state.value.status?.headline)
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
        vm.onQrText("""{"schema_version":"1.1"}""")

        assertEquals(PairingProgress.Failed(PairingProblem.INVALID_QR), vm.state.value.pairing)
        assertEquals(0, controller.pairCalls.get())
        vm.dismissPairingProblem()
        assertEquals(PairingProgress.Idle, vm.state.value.pairing)
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
    fun `release 版没有手动入口：手动输入的载荷被忽略，调试版则等同于扫码`() = runTest {
        val release = FakeController()
        val releaseVm = viewModel(release, manualEntry = false)
        val debug = FakeController()
        val debugVm = viewModel(debug, manualEntry = true)

        releaseVm.onManualPayload(payloadText())
        debugVm.onManualPayload(payloadText())

        assertFalse(releaseVm.state.value.manualEntryAvailable)
        assertEquals(0, release.pairCalls.get())
        assertTrue(debugVm.state.value.manualEntryAvailable)
        assertEquals(1, debug.pairCalls.get())
    }

    @Test
    fun `现在同步转给同步控制器`() = runTest {
        val controller = FakeController()
        val vm = viewModel(controller)

        vm.syncNow()

        assertEquals(1, controller.syncNowCalls)
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
            val vm = SyncViewModel(controller, scope, manualEntryAvailable = false)
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
