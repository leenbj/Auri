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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
    fun `failed and throwing route requests restore the prior audio mode`() {
        audio.routeRequestAccepted = false
        assertEquals(RequestStatus.REJECTED_BY_SYSTEM, prepare().requestStatus)
        assertEquals(0, audio.mode)
        assertEquals(1, audio.routeCleared)

        audio.routeRequestAccepted = true
        audio.onRouteRequest = { throw IllegalStateException("route failed") }
        assertEquals(RequestStatus.REJECTED_BY_SYSTEM, prepare().requestStatus)
        assertEquals(0, audio.mode)
        assertEquals(2, audio.routeCleared)
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
    fun `a different bluetooth headset cannot verify the requested input`() {
        val other = HEADSET.copy(id = 42, name = "Other headset")
        audio.initialRouted = other

        prepare()

        assertEquals(VerificationState.MISMATCH, sink.routeEvents.single().verificationState)
        audio.recorders.single().script += ReadStep(chunk(), other)
        readers.single().run()
        assertTrue(sink.frames.isEmpty())
    }

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
            listOf("route:1:VERIFIED", "frames:1", "route:2:MISMATCH"),
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
        recorder.script += ReadStep(chunk(), HEADSET)
        readers.single().run()

        assertEquals(
            listOf("route:1:VERIFIED", "frames:1", "frames:1", "frames:1", "route:2:UNKNOWN", "route:3:VERIFIED", "frames:3"),
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

    @Test
    fun `a route callback during a read discards that buffer even if the device is unchanged`() {
        prepare()
        val recorder = audio.recorders.single()
        var first = true
        recorder.onRead = {
            if (first) {
                first = false
                recorder.systemChangesRoute(HEADSET)
            }
        }
        recorder.script += ReadStep(chunk(value = 1), HEADSET)
        recorder.script += ReadStep(chunk(value = 2), HEADSET)

        readers.single().run()

        assertEquals(listOf(2.toShort()), sink.frames.single().samples.toSet().toList())
        assertEquals(1600L, adapter.droppedAmbiguousSamples)
    }

    @Test
    fun `a buffer returned after cancellation is discarded and counted`() {
        prepare()
        val recorder = audio.recorders.single()
        recorder.script += ReadStep(chunk(), HEADSET)
        recorder.onRead = { adapter.release(request.operationId) }

        readers.single().run()

        assertTrue(sink.frames.isEmpty())
        assertEquals(1600L, adapter.droppedAmbiguousSamples)
    }

    // ---- 释放 ----

    @Test
    fun `cancelling while route request is blocked releases any later acquisition`() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        audio.onRouteRequest = {
            entered.countDown()
            check(resume.await(5, TimeUnit.SECONDS))
        }
        try {
            val pending = executor.submit<app.auri.capture.contract.PreparedInput> { prepare() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            adapter.release(request.operationId)
            resume.countDown()

            assertEquals(RequestStatus.REJECTED_BY_SYSTEM, pending.get(5, TimeUnit.SECONDS).requestStatus)
            assertEquals(1, audio.routeCleared)
            assertEquals(audio.focusRequests.size, audio.focusAbandoned)
            assertTrue(audio.recorders.all { it.released })
        } finally {
            resume.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `cancelling during audio focus acquisition abandons the acquired focus`() {
        audio.onFocus = { adapter.release(request.operationId) }
        assertEquals(RequestStatus.REJECTED_BY_SYSTEM, prepare().requestStatus)
        assertEquals(1, audio.focusAbandoned)
        assertEquals(1, audio.routeCleared)
        assertTrue(audio.recorders.isEmpty())
    }

    @Test
    fun `late cleanup of a cancelled route cannot clear the next session route`() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val calls = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        val next = InputRequest(request.captureSessionId, "0c0c0c0c-0000-4000-8000-000000000002", 2, request.format)
        audio.onRouteRequest = {
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
            } else secondEntered.countDown()
        }
        try {
            val first = executor.submit<app.auri.capture.contract.PreparedInput> { prepare() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            adapter.release(request.operationId)
            val second = executor.submit<app.auri.capture.contract.PreparedInput> {
                runBlocking { adapter.prepareBluetoothInput(next, RecordingSink()) }
            }
            secondEntered.await(500, TimeUnit.MILLISECONDS)
            resume.countDown()
            assertEquals(RequestStatus.REJECTED_BY_SYSTEM, first.get(5, TimeUnit.SECONDS).requestStatus)
            assertEquals(RequestStatus.ACCEPTED_BY_SYSTEM, second.get(5, TimeUnit.SECONDS).requestStatus)
            assertEquals("the second session still owns communication mode", 1, audio.mode)
            adapter.release(next.operationId)
            assertEquals(2, audio.routeCleared)
        } finally {
            resume.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `reader startup failure releases every acquired resource`() {
        val brokenReader = BluetoothCaptureAdapter(audio, environment, FixedClock()) { throw IllegalStateException("reader failed") }
        val prepared = runBlocking { brokenReader.prepareBluetoothInput(request, sink) }
        assertEquals(RequestStatus.REJECTED_BY_SYSTEM, prepared.requestStatus)
        assertTrue(audio.recorders.single().released)
        assertEquals(1, audio.focusAbandoned)
        assertEquals(1, audio.routeCleared)
    }

    @Test
    fun `an unexpected recorder read failure releases resources and reports interruption`() {
        prepare()
        val recorder = audio.recorders.single()
        recorder.script += ReadStep(chunk(), HEADSET)
        recorder.onRead = { throw IllegalStateException("read failed") }

        readers.single().run()

        assertEquals(listOf(ErrorCode.INPUT_ROUTE_CHANGED), sink.unavailable)
        assertTrue(recorder.released)
        adapter.release(request.operationId)
        assertEquals(1, recorder.releaseCalls)
        assertEquals(1, recorder.subscriptionCancels)
        assertEquals(1, audio.focusAbandoned)
        assertEquals(1, audio.routeCleared)
    }

    @Test
    fun `recorder release failure still clears focus route and subscription exactly once`() {
        prepare()
        val recorder = audio.recorders.single()
        recorder.releaseFailure = IllegalStateException("release failed")
        try {
            adapter.release(request.operationId)
        } catch (_: IllegalStateException) { }
        adapter.release(request.operationId)
        assertEquals(1, recorder.releaseCalls)
        assertEquals(1, recorder.subscriptionCancels)
        assertEquals(1, audio.focusAbandoned)
        assertEquals(1, audio.routeCleared)
    }

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

    // ---- 录音中权限被撤销（Ticket 19 第 5 项）----

    @Test
    fun `录音中麦克风权限被撤销：撤销时读到的那一批不递送，通知一次，协调器释放之后读线程安静退出，资源各只放一次`() {
        prepare()
        val recorder = audio.recorders.single()
        val reasons = mutableListOf<ErrorCode>()
        adapter.onPermissionRevoked { reason ->
            reasons += reason
            adapter.release(request.operationId) // 协调器收到通知之后的反应：关闸、释放采集
        }
        recorder.script += ReadStep(chunk(value = 1), HEADSET)
        recorder.script += ReadStep(chunk(value = 2), HEADSET)
        recorder.script += ReadStep(chunk(value = 3), HEADSET)
        var reads = 0
        recorder.onRead = { if (++reads == 2) environment.mic = false } // 读第二批的时候权限被撤销

        readers.single().run()

        assertEquals("撤销之前的照常递送；撤销时读到的那一批不递送", listOf<Short>(1), sink.frames.flatMap { it.samples.toList() }.distinct())
        assertEquals(listOf(ErrorCode.PERMISSION_MIC_DENIED), reasons)
        assertTrue("释放之后读线程退出，不再报告“输入不可用”", sink.unavailable.isEmpty())
        assertEquals(1, recorder.releaseCalls)
        assertEquals(1, recorder.subscriptionCancels)
        assertEquals(1, audio.focusAbandoned)
        assertEquals(1, audio.routeCleared)
        assertEquals("没有任何“改用别的麦克风”的动作：只打开过这一个录音", 1, audio.recorders.size)
    }

    @Test
    fun `录音中蓝牙连接权限被撤销：同样不递送这一批并通知一次，原因沿用 INPUT_DEVICE_MISSING（合同没有专门的码）`() {
        prepare()
        val recorder = audio.recorders.single()
        val reasons = mutableListOf<ErrorCode>()
        adapter.onPermissionRevoked { reason ->
            reasons += reason
            adapter.release(request.operationId)
        }
        recorder.script += ReadStep(chunk(value = 1), HEADSET)
        recorder.script += ReadStep(chunk(value = 2), HEADSET)
        var reads = 0
        recorder.onRead = { if (++reads == 2) environment.bluetooth = false }

        readers.single().run()

        assertEquals(listOf<Short>(1), sink.frames.flatMap { it.samples.toList() }.distinct())
        assertEquals(listOf(ErrorCode.INPUT_DEVICE_MISSING), reasons)
        assertEquals(1, recorder.releaseCalls)
        assertEquals(1, audio.routeCleared)
    }

    @Test
    fun `回到前台时已经发现了撤销：读线程之后每一批都不递送，也不重复通知`() {
        prepare()
        val recorder = audio.recorders.single()
        val reasons = mutableListOf<ErrorCode>()
        adapter.onPermissionRevoked { reasons += it } // 协调器还没来得及处理：采集还开着
        recorder.script += ReadStep(chunk(value = 1), HEADSET)
        recorder.script += ReadStep(chunk(value = 2), HEADSET)

        environment.mic = false
        adapter.permissionsMayHaveChanged()
        readers.single().run()

        assertEquals("只通知了一次（前台核对那次）", listOf(ErrorCode.PERMISSION_MIC_DENIED), reasons)
        assertTrue("权限没了，读到的批次一个也不递送", sink.frames.isEmpty())
    }

    @Test
    fun `权限被撤销之后读失败：原因是权限而不是路由，只通知权限撤销，不再另报“输入路由变了”`() {
        prepare()
        val reasons = mutableListOf<ErrorCode>()
        adapter.onPermissionRevoked { reasons += it } // 协调器还没来得及处理：采集还开着
        environment.mic = false // 系统撤销了权限，读随之失败（脚本是空的，read 返回 -1）

        readers.single().run()

        assertEquals(listOf(ErrorCode.PERMISSION_MIC_DENIED), reasons)
        assertTrue("读失败的原因是权限：不能再把它记成路由变化（会让这次录音被误标成 route_lost）", sink.unavailable.isEmpty())
    }

    @Test
    fun `没有权限问题时读失败仍然报告输入不可用（路由变了）`() {
        prepare()
        adapter.onPermissionRevoked { throw AssertionError("权限没变，不该有通知") }

        readers.single().run() // 脚本是空的：read 返回 -1

        assertEquals(listOf(ErrorCode.INPUT_ROUTE_CHANGED), sink.unavailable)
    }

    @Test
    fun `权限没有变化时读线程不通知，帧照常递送；撤销后重新授予不算撤销`() {
        prepare()
        val recorder = audio.recorders.single()
        var revoked = 0
        adapter.onPermissionRevoked { revoked += 1 }
        recorder.script += ReadStep(chunk(value = 1), HEADSET)
        recorder.script += ReadStep(chunk(value = 2), HEADSET)

        readers.single().run()
        environment.mic = false
        adapter.permissionsMayHaveChanged()
        environment.mic = true // 重新授予
        adapter.permissionsMayHaveChanged()

        assertEquals(2, sink.frames.size)
        assertEquals("只有撤销那一刻通知了一次", 1, revoked)
    }
}
