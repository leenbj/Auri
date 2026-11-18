package app.auri.capture.coordinator

import app.auri.capture.adapter.CaptureAdapter
import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.CanStart
import app.auri.capture.contract.CaptureEligibility
import app.auri.capture.contract.CaptureState
import app.auri.capture.contract.CaptureStatus
import app.auri.capture.contract.EndReason
import app.auri.capture.contract.ErrorCode
import app.auri.capture.contract.LifecycleState
import app.auri.capture.contract.ObservedInputRouteType
import app.auri.capture.contract.PermissionState
import app.auri.capture.contract.VerificationState
import app.auri.capture.machine.CaptureEvent
import app.auri.capture.testing.CaptureRig
import app.auri.capture.testing.StoreEvent
import java.io.File
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 16 第 3、4 项：路由确认之前的音频只存在于内存里，确认失败就丢弃，磁盘上与转录入口都没有任何东西；
 * 耳机麦克风不可用时什么都不录、给出原因，永远不会改用手机麦克风。
 * “没写过任何东西”不是靠断言某个布尔值，而是靠假存储记录下的每一次调用（FakeRecordingStore.events）。
 */
class CaptureGateTest {
    private fun TestScope.armed(rig: CaptureRig = CaptureRig(this)): CaptureRig {
        rig.coordinator.enableControlMode()
        rig.settle()
        return rig
    }

    private fun CaptureRig.start() {
        control.press(BusinessCommand.START)
        settle()
    }

    // ---- 第 3 项：确认之前只在内存 ----

    @Test
    fun `系统观测到输入是手机麦克风：丢弃隔离区里的音频，存储没有被碰过，没有东西交去转录`() = runTest {
        val rig = armed()
        rig.start()
        rig.capture.frames(samples = 1600, epoch = 0)
        rig.capture.frames(samples = 1600, epoch = 0)
        rig.settle()
        assertEquals("确认之前的音频在内存里", 3200L, rig.coordinator.snapshot.value.counters.quarantinedSamples)

        rig.capture.mismatchRoute()
        rig.settle()

        assertEquals(CaptureState.BLOCKED, rig.state)
        assertEquals(ErrorCode.INPUT_ROUTE_UNVERIFIED, rig.coordinator.snapshot.value.blockedReason)
        assertEquals("存储一次调用都没有收到", emptyList<StoreEvent>(), rig.store.events)
        assertTrue("没有录音结果，转录入口收不到任何东西", rig.output.recordings.isEmpty())
        val counters = rig.coordinator.snapshot.value.counters
        assertEquals(0L, counters.quarantinedSamples)
        assertEquals("丢弃的就是那 3200 个采样", 3200L, counters.discardedQuarantineSamples)
        assertEquals(0L, counters.writtenSamples)
        assertEquals("系统观测到的实际输入是手机麦克风，已经立即释放采集", listOf(rig.capture.requests.single().operationId), rig.capture.releases)
    }

    @Test
    fun `系统一直说不清实际输入：超时受阻，确认之前的音频同样被丢弃`() = runTest {
        val rig = armed()
        rig.start()
        rig.capture.route(VerificationState.UNKNOWN, ObservedInputRouteType.UNKNOWN)
        rig.capture.frames(samples = 800, epoch = 1)
        rig.settle()
        assertEquals("unknown 不是确认，也不是失败：继续等", CaptureState.ROUTING, rig.state)
        assertEquals(ObservedInputRouteType.UNKNOWN, rig.coordinator.snapshot.value.actualInput?.routeType)

        rig.advance(rig.config.routeTimeoutMs)

        assertEquals(CaptureState.BLOCKED, rig.state)
        assertEquals(ErrorCode.INPUT_ROUTE_UNVERIFIED, rig.coordinator.snapshot.value.blockedReason)
        assertEquals(emptyList<StoreEvent>(), rig.store.events)
        assertEquals(800L, rig.coordinator.snapshot.value.counters.discardedQuarantineSamples)
        assertTrue(rig.output.recordings.isEmpty())
    }

    @Test
    fun `用户在确认之前取消：隔离区里的音频被丢弃，存储没有被碰过`() = runTest {
        val rig = armed()
        rig.start()
        rig.capture.frames(samples = 1600, epoch = 0)
        rig.settle()

        rig.control.press(BusinessCommand.STOP)
        rig.settle()

        assertEquals(CaptureState.ARMED, rig.state)
        assertEquals(emptyList<StoreEvent>(), rig.store.events)
        assertTrue(rig.output.recordings.isEmpty())
        assertEquals(1600L, rig.coordinator.snapshot.value.counters.discardedQuarantineSamples)
    }

    @Test
    fun `隔离区有内存上限：超出的部分丢最旧的，不会无限增长`() = runTest {
        val rig = armed(CaptureRig(this, CaptureConfig(quarantineMaxMs = 1_000)))
        rig.start()
        repeat(3) { rig.capture.frames(samples = 8_000, epoch = 0) } // 3 × 0.5 s，上限 1 s（16000 个采样）
        rig.settle()

        assertEquals(16_000L, rig.coordinator.snapshot.value.counters.quarantinedSamples)

        rig.capture.mismatchRoute()
        rig.settle()
        assertEquals("总共 24000 个采样都被丢弃（含超出上限的那部分）", 24_000L, rig.coordinator.snapshot.value.counters.discardedQuarantineSamples)
        assertEquals(emptyList<StoreEvent>(), rig.store.events)
    }

    @Test
    fun `路由确认之后，确认之前的采样不会被补写进录音`() = runTest {
        val rig = armed()
        rig.start()
        rig.capture.frames(samples = 1600, epoch = 0) // 确认之前
        rig.capture.verifiedRoute(epoch = 1)
        rig.capture.frames(samples = 800, epoch = 1) // 确认之后
        rig.settle()

        assertEquals("只有确认之后的 800 个采样进了存储", 800, rig.store.writtenSamples)
        assertTrue("第一个存储调用是确认之后的 begin", rig.store.events.first() is StoreEvent.Begin)
    }

    @Test
    fun `确认之后存储打不开：受阻并说明是存储的原因，一个采样也没有写`() = runTest {
        val rig = armed()
        rig.store.failBegin = IOException("磁盘已满（测试注入）")
        rig.start()

        rig.capture.verifiedRoute(epoch = 1)
        rig.capture.frames(samples = 1600, epoch = 1)
        rig.settle()

        assertEquals(CaptureState.BLOCKED, rig.state)
        assertEquals(ErrorCode.STORAGE_EXHAUSTED, rig.coordinator.snapshot.value.blockedReason)
        assertEquals("只有那一次失败的 begin，没有任何写入", 1, rig.store.events.size)
        assertEquals(0, rig.store.writtenSamples)
        assertTrue(rig.output.recordings.isEmpty())
    }

    // ---- 第 4 项：耳机麦克风不可用就什么都不录，也不回退手机麦克风 ----

    @Test
    fun `耳机麦克风不可用：什么都不录，给出原因，不重试，也没有第二种输入请求`() = runTest {
        val rig = armed()
        rig.capture.refuseWith = ErrorCode.INPUT_DEVICE_MISSING

        rig.start()

        assertEquals(CaptureState.BLOCKED, rig.state)
        assertEquals("给出原因", ErrorCode.INPUT_DEVICE_MISSING, rig.coordinator.snapshot.value.blockedReason)
        assertEquals(ErrorCode.INPUT_DEVICE_MISSING, rig.decisions.last { it.trigger == CaptureEvent.ROUTE_UNVERIFIED }.errorCode)
        assertEquals(emptyList<StoreEvent>(), rig.store.events)
        assertTrue(rig.output.recordings.isEmpty())
        assertEquals("开始时只请求了一次蓝牙输入", 1, rig.capture.requests.size)

        rig.advance(60_000)
        assertEquals("不自动重试，也不换别的输入", 1, rig.capture.requests.size)
        assertEquals(CaptureState.BLOCKED, rig.state)

        // 只有用户再次开始才会重试；原因没消除就再次受阻
        rig.control.press(BusinessCommand.START)
        rig.settle()
        assertEquals(2, rig.capture.requests.size)
        assertEquals(CaptureState.BLOCKED, rig.state)
        assertEquals(emptyList<StoreEvent>(), rig.store.events)
    }

    @Test
    fun `没有麦克风权限：不请求任何输入，受阻并给出权限原因`() = runTest {
        val rig = armed()
        rig.capture.eligibility = CaptureEligibility(PermissionState.DENIED, LifecycleState.FOREGROUND, CanStart.NO, ErrorCode.PERMISSION_MIC_DENIED)

        rig.start()

        assertEquals(CaptureState.BLOCKED, rig.state)
        assertEquals(ErrorCode.PERMISSION_MIC_DENIED, rig.coordinator.snapshot.value.blockedReason)
        assertEquals("资格没过，连输入请求都没有发出", 0, rig.capture.requests.size)
        assertEquals(emptyList<StoreEvent>(), rig.store.events)
    }

    @Test
    fun `资格检查说不清能不能开始：按不能开始处理，未知不等于允许`() = runTest {
        val rig = armed()
        rig.capture.eligibility = CaptureEligibility(PermissionState.GRANTED, LifecycleState.LOCKED, CanStart.UNKNOWN, null)

        rig.start()

        assertEquals(CaptureState.BLOCKED, rig.state)
        assertEquals(ErrorCode.BACKGROUND_START_DENIED, rig.coordinator.snapshot.value.blockedReason)
        assertEquals(0, rig.capture.requests.size)
    }

    @Test
    fun `前台条件不满足：受阻并说明是后台不能开始`() = runTest {
        val rig = armed()
        rig.capture.eligibility = CaptureEligibility(PermissionState.GRANTED, LifecycleState.BACKGROUND, CanStart.NO, ErrorCode.BACKGROUND_START_DENIED)

        rig.start()

        assertEquals(CaptureState.BLOCKED, rig.state)
        assertEquals(ErrorCode.BACKGROUND_START_DENIED, rig.coordinator.snapshot.value.blockedReason)
        assertEquals(0, rig.capture.requests.size)
    }

    /**
     * 上面三个“资格没过 → 0 次请求”的测试对“没资格也先发出请求、随后在 BLOCKED 时取消”这种错误是空的：
     * 默认的排队调度器下，发出又取消的准备任务永远没机会运行，requests 照样是 0。线上适配器跑在真线程上，
     * 这样的任务可能在取消之前就已经开始碰系统（改音频模式、请求通信设备）。这里让准备任务一发出就当场开始，
     * 于是只有“根本没发出”才能得到 0（变异验证：把 beginSession(eligible) 改成 beginSession(true) 时它变红）。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `资格没过：准备任务根本没有发出，而不是发出之后赶在它开始之前取消`() = runTest {
        val denied = CaptureEligibility(PermissionState.DENIED, LifecycleState.FOREGROUND, CanStart.NO, ErrorCode.PERMISSION_MIC_DENIED)
        val unknown = CaptureEligibility(PermissionState.GRANTED, LifecycleState.LOCKED, CanStart.UNKNOWN, null)
        val background = CaptureEligibility(PermissionState.GRANTED, LifecycleState.BACKGROUND, CanStart.NO, ErrorCode.BACKGROUND_START_DENIED)
        for (eligibility in listOf(denied, unknown, background)) {
            val rig = armed(CaptureRig(this, adapterDispatcher = UnconfinedTestDispatcher(testScheduler)))
            rig.capture.eligibility = eligibility

            rig.start()

            assertEquals("$eligibility", CaptureState.BLOCKED, rig.state)
            assertEquals("$eligibility：不该发出任何输入请求", 0, rig.capture.requests.size)
        }
    }

    @Test
    fun `采集适配器的接口里只有“请求蓝牙输入”这一种请求输入的办法`() {
        // 接口层面的“没有回退分支”：协调器能对采集适配器说的话就这几句，谁加一个“请求默认输入/手机麦克风”的方法，这里就红
        val methods = CaptureAdapter::class.java.methods.map { it.name }.toSet()
        assertEquals(setOf("inspectEligibility", "prepareBluetoothInput", "release", "onPermissionRevoked"), methods)
    }

    @Test
    fun `协调器所在的模块不含任何麦克风采集 API，也不含 Android 类`() {
        val root = File(System.getProperty("auri.repo.root"), "apps/android/capture/src/main")
        val forbidden = Regex("""\b(AudioRecord|MediaRecorder|AudioSource|setCommunicationDevice|setPreferredDevice|android\.)""")
        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            // 注释里提到这些词不算
            file.readLines().withIndex().filter { (_, line) ->
                val code = line.substringBefore("//").trim()
                !code.startsWith("*") && !code.startsWith("/*") && forbidden.containsMatchIn(code)
            }.map { "${file.name}:${it.index + 1}" }
        }.toList()

        assertEquals(emptyList<String>(), offenders)
    }

    // ---- 没有可回退的东西：路由丢失后的录音只截断，不换输入继续 ----

    @Test
    fun `录音中输入变成别的来源：立即关闸并封存已确认的部分，之后的帧不再写入`() = runTest {
        val rig = armed()
        rig.start()
        rig.capture.verifiedRoute(epoch = 1)
        rig.capture.frames(samples = 1600, epoch = 1)
        rig.settle()

        rig.capture.mismatchRoute(epoch = 2) // 系统把输入换成了手机麦克风
        rig.capture.frames(samples = 1600, epoch = 2) // 换了之后的帧（不属于已确认的路由）
        rig.settle()

        assertEquals(CaptureState.ARMED, rig.state)
        assertEquals("换了输入之后的帧一个也没有写", 1600, rig.store.writtenSamples)
        assertEquals(1, rig.capture.requests.size)
        val result = rig.output.recordings.single().result
        assertEquals(EndReason.ROUTE_LOST, result.endReason)
        assertEquals(CaptureStatus.PARTIAL, result.status)
        assertEquals(100L, result.speechDurationMs)
        assertEquals(1600L, rig.output.recordings.single().audio?.sampleCount)
    }
}
