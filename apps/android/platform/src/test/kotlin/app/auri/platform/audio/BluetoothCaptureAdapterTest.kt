package app.auri.platform.audio

import app.auri.capture.adapter.InputRequest
import app.auri.capture.adapter.PcmFormat
import app.auri.capture.contract.CanStart
import app.auri.capture.contract.ErrorCode
import app.auri.capture.contract.LifecycleState
import app.auri.capture.contract.ObservedInputRouteType
import app.auri.capture.contract.PermissionState
import app.auri.capture.contract.RequestStatus
import app.auri.capture.contract.VerificationState
import app.auri.capture.coordinator.Clock
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 采集适配器的行为：严格蓝牙输入、路由证据只来自系统的观测、先报观测再递送帧、释放干净。
 * 这里的“系统”是假的（FakeAudioSystem），只验证适配器怎么对系统说话、怎么转述系统的话；真实的 Android 音频栈
 * 在模拟器（拒绝路径）和真机（Ticket 16 第 8 项的验收步骤）上看。
 */
class BluetoothCaptureAdapterTest {
    private class FixedClock : Clock {
        override fun monotonicNs() = 123_000_000L
        override fun now(): Instant = Instant.parse("2026-11-18T08:00:00Z")
    }

    private val audio = FakeAudioSystem()
    private val environment = FakeEnvironment()
    private val readers = mutableListOf<Runnable>()
    private val sink = RecordingSink()
    private val adapter = BluetoothCaptureAdapter(audio, environment, FixedClock()) { readers += it }
    private val request = InputRequest(
        captureSessionId = "0b0b0b0b-0000-4000-8000-000000000001",
        operationId = "0c0c0c0c-0000-4000-8000-000000000001",
        generation = 1,
        format = PcmFormat(16_000),
    )

    private fun prepare() = runBlocking { adapter.prepareBluetoothInput(request, sink) }

    private fun chunk(n: Int = 1600, value: Short = 7) = ShortArray(n) { value }

    /** 脚本读完就等于流结束，会多一条“输入不可用”；这些测试只看路由事件与帧的先后顺序。 */
    private fun routeAndFrameOrder() = sink.order.filterNot { it.startsWith("unavailable") }

    // ---- 严格蓝牙：没有蓝牙设备就什么都不打开 ----

    @Test
    fun `没有蓝牙输入设备：拒绝并说明原因，不请求任何路由，也不打开任何录音`() {
        audio.inputs = listOf(PHONE_MIC) // 手机麦克风在，但它不是可以请求的输入

        val prepared = prepare()

        assertEquals(RequestStatus.REJECTED_BY_SYSTEM, prepared.requestStatus)
        assertEquals(listOf(ErrorCode.INPUT_DEVICE_MISSING), sink.unavailable)
        assertTrue("没请求过通信路由", audio.routeRequests.isEmpty())
        assertTrue("一个录音也没打开", audio.recorders.isEmpty())
        assertTrue(readers.isEmpty())
    }

    @Test
    fun `能表示蓝牙输入的类型只有 SCO 与 BLE 耳机；手机内置麦克风、有线、USB 造不出蓝牙输入`() {
        assertNotNull(BluetoothInput.from(HEADSET))
        assertNotNull(BluetoothInput.from(LE_HEADSET))
        for (type in listOf(AudioDeviceTypes.BUILTIN_MIC, AudioDeviceTypes.WIRED_HEADSET, AudioDeviceTypes.USB_HEADSET, AudioDeviceTypes.BLUETOOTH_A2DP, 99)) {
            assertNull("类型 $type 不是蓝牙输入", BluetoothInput.from(InputDevice(1, type, "x")))
        }
    }

    @Test
    fun `系统不接受蓝牙通信路由：拒绝，不打开录音，已请求的路由被撤销`() {
        audio.routeRequestAccepted = false

        val prepared = prepare()

        assertEquals(RequestStatus.REJECTED_BY_SYSTEM, prepared.requestStatus)
        assertEquals(listOf(ErrorCode.INPUT_ROUTE_UNVERIFIED), sink.unavailable)
        assertTrue(audio.recorders.isEmpty())
    }

    @Test
    fun `打开录音失败：拒绝并清理，已请求的通信路由被撤销`() {
        audio.openFailure = IllegalStateException("系统拒绝（测试注入）")

        val prepared = prepare()

        assertEquals(RequestStatus.REJECTED_BY_SYSTEM, prepared.requestStatus)
        assertEquals(listOf(ErrorCode.INPUT_ROUTE_UNVERIFIED), sink.unavailable)
        assertEquals(1, audio.routeCleared)
        assertTrue(readers.isEmpty())
    }

    @Test
    fun `启动录音失败：拒绝，打开了的录音被释放，不留下读线程`() {
        audio.recorderStartFailure = IllegalStateException("startRecording 失败（测试注入）")

        val prepared = prepare()

        assertEquals(RequestStatus.REJECTED_BY_SYSTEM, prepared.requestStatus)
        assertTrue(audio.recorders.single().released)
        assertEquals(1, audio.routeCleared)
        assertTrue(readers.isEmpty())
    }

    // ---- 路由证据只来自系统的观测 ----

    @Test
    fun `系统确认录音在蓝牙耳机上：报告 verified 的蓝牙路由，之后的帧带这个 epoch`() {
        val prepared = prepare()
        audio.recorders.single().script += ReadStep(chunk(), HEADSET)
        readers.single().run()

        assertEquals(RequestStatus.ACCEPTED_BY_SYSTEM, prepared.requestStatus)
        assertEquals(listOf(HEADSET.id.toString()), listOf(prepared.requestedDevice.platformDeviceRef))
        assertEquals(listOf(audio.routeRequests.single()), listOf(BluetoothInput.from(HEADSET)))
        val event = sink.routeEvents.single()
        assertEquals(VerificationState.VERIFIED, event.verificationState)
        assertEquals(ObservedInputRouteType.BLUETOOTH_HFP, event.observedInput.routeType)
        assertEquals("Fixture Headset", event.observedInput.displayName)
        assertEquals(1L, event.epoch)
        assertEquals(request.captureSessionId, event.captureSessionId)
        assertEquals(listOf(1L), sink.frames.map { it.routeEpoch })
        assertEquals(1600, sink.frames.single().samples.size)
    }

    @Test
    fun `请求了蓝牙但系统还没说清实际输入：报告 unknown，不能当成已确认`() {
        audio.initialRouted = null

        prepare()

        val event = sink.routeEvents.single()
        assertEquals(VerificationState.UNKNOWN, event.verificationState)
        assertEquals(ObservedInputRouteType.UNKNOWN, event.observedInput.routeType)
    }

    @Test
    fun `系统把路由换成手机麦克风：报告 mismatch 与新的 epoch，适配器不去纠正路由`() {
        prepare()
        val recorder = audio.recorders.single()
        recorder.script += ReadStep(chunk(), HEADSET)
        // 这批读到的时候，系统已经悄悄切到手机麦克风（没有发路由变化通知）：只能靠读完之后再问一次系统才发现
        recorder.script += ReadStep(chunk(value = 9), PHONE_MIC, notify = false)
        readers.single().run()

        assertEquals(
            listOf("route:1:VERIFIED", "frames:1", "route:2:MISMATCH", "frames:2"),
            routeAndFrameOrder(),
        )
        val mismatch = sink.routeEvents.last()
        assertEquals(ObservedInputRouteType.BUILT_IN_MIC, mismatch.observedInput.routeType)
        assertEquals(1, audio.routeRequests.size)
    }

    @Test
    fun `同一个观测不重复报告；每次路由真的变化 epoch 才递增`() {
        prepare()
        val recorder = audio.recorders.single()
        repeat(3) { recorder.script += ReadStep(chunk(), HEADSET) }
        recorder.script += ReadStep(chunk(), null)
        recorder.script += ReadStep(chunk(), HEADSET)
        readers.single().run()

        assertEquals(
            listOf("route:1:VERIFIED", "frames:1", "frames:1", "frames:1", "route:2:UNKNOWN", "frames:2", "route:3:VERIFIED", "frames:3"),
            routeAndFrameOrder(),
        )
    }

    @Test
    fun `读线程在读之前，系统通知的路由变化也会被报告`() {
        prepare()
        audio.recorders.single().systemChangesRoute(PHONE_MIC)

        assertEquals(listOf(VerificationState.VERIFIED, VerificationState.MISMATCH), sink.routeEvents.map { it.verificationState })
        assertEquals(listOf(1L, 2L), sink.routeEvents.map { it.epoch })
    }

    // ---- 释放 ----

    @Test
    fun `释放：停止录音、撤销路由请求、放弃焦点，读线程退出时不再递送也不报错；重复释放无副作用`() {
        prepare()
        val recorder = audio.recorders.single()
        recorder.script += ReadStep(chunk(), HEADSET)
        recorder.script += ReadStep(chunk(), HEADSET)

        adapter.release(request.operationId)
        adapter.release(request.operationId)
        readers.single().run() // 被释放之后读线程才跑到：什么都不该递送

        assertTrue(recorder.released)
        assertEquals(1, audio.routeCleared)
        assertEquals(1, audio.focusAbandoned)
        assertTrue("释放之后不再递送帧", sink.frames.isEmpty())
        assertTrue("主动释放不是“输入不可用”", sink.unavailable.isEmpty())
    }

    @Test
    fun `释放一个不存在的操作：什么都不发生`() {
        adapter.release("0c0c0c0c-0000-4000-8000-0000000000ff")

        assertEquals(0, audio.routeCleared)
    }

    @Test
    fun `准备还没返回时就被取消：打开的录音立刻释放，不会开始采集`() {
        audio.onOpen = { adapter.release(request.operationId) } // 打开录音的那一刻，协调器取消了这次会话

        val prepared = prepare()

        assertEquals(RequestStatus.REJECTED_BY_SYSTEM, prepared.requestStatus)
        assertFalse("没有开始采集", audio.recorders.single().started)
        assertTrue(audio.recorders.single().released)
        assertTrue(readers.isEmpty())
    }

    @Test
    fun `读到错误（输入被拿走）：报告输入不可用，不再递送`() {
        prepare()
        audio.recorders.single().script += ReadStep(chunk(), HEADSET) // 脚本读完后 read 返回 -1

        readers.single().run()

        assertEquals(listOf(ErrorCode.INPUT_ROUTE_CHANGED), sink.unavailable)
        assertEquals(1, sink.frames.size)
    }

    @Test
    fun `音频焦点被抢走：报告系统中断`() {
        prepare()

        audio.focusRequests.single().invoke()

        assertEquals(1, sink.interrupted)
    }

    // ---- 资格 ----

    @Test
    fun `资格：麦克风权限与蓝牙权限与前台服务权限分开判断，原因各不相同`() {
        environment.mic = false
        assertEquals(
            Triple(PermissionState.DENIED, CanStart.NO, ErrorCode.PERMISSION_MIC_DENIED),
            adapter.inspectEligibility().let { Triple(it.permission, it.canStart, it.reason) },
        )

        environment.mic = true
        environment.bluetooth = false
        assertEquals(
            Triple(PermissionState.DENIED, CanStart.NO, ErrorCode.INPUT_DEVICE_MISSING),
            adapter.inspectEligibility().let { Triple(it.permission, it.canStart, it.reason) },
        )

        environment.bluetooth = true
        environment.microphoneService = false
        assertEquals(
            Triple(PermissionState.GRANTED, CanStart.NO, ErrorCode.BACKGROUND_START_DENIED),
            adapter.inspectEligibility().let { Triple(it.permission, it.canStart, it.reason) },
        )
    }

    @Test
    fun `资格：前台才允许开始；后台与锁屏不允许；说不清就是 unknown，不当作允许`() {
        assertEquals(CanStart.YES, adapter.inspectEligibility().canStart)
        assertNull(adapter.inspectEligibility().reason)

        environment.lifecycle = LifecycleState.BACKGROUND
        assertEquals(CanStart.NO, adapter.inspectEligibility().canStart)
        assertEquals(ErrorCode.BACKGROUND_START_DENIED, adapter.inspectEligibility().reason)

        environment.lifecycle = LifecycleState.LOCKED
        assertEquals(CanStart.NO, adapter.inspectEligibility().canStart)

        environment.lifecycle = LifecycleState.UNKNOWN
        assertEquals(CanStart.UNKNOWN, adapter.inspectEligibility().canStart)
    }

    @Test
    fun `麦克风权限在运行期被撤销：回到前台时核对一次，通知一次，之后不重复`() {
        var revoked = 0
        adapter.onPermissionRevoked { revoked += 1 }

        adapter.permissionsMayHaveChanged()
        assertEquals("权限没变，不通知", 0, revoked)

        environment.mic = false
        adapter.permissionsMayHaveChanged()
        adapter.permissionsMayHaveChanged()
        assertEquals(1, revoked)
    }
}
