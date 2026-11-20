package app.auri.platform.audio

import app.auri.capture.adapter.AudioFrames
import app.auri.capture.adapter.CaptureSink
import app.auri.capture.adapter.PcmFormat
import app.auri.capture.adapter.Subscription
import app.auri.capture.contract.ErrorCode
import app.auri.capture.contract.LifecycleState
import app.auri.capture.contract.RouteChangeEvent
import java.util.concurrent.CopyOnWriteArrayList

val HEADSET = InputDevice(id = 41, type = AudioDeviceTypes.BLUETOOTH_SCO, name = "Fixture Headset")
val LE_HEADSET = InputDevice(id = 43, type = AudioDeviceTypes.BLE_HEADSET, name = "Fixture LE Headset")
val PHONE_MIC = InputDevice(id = 2, type = AudioDeviceTypes.BUILTIN_MIC, name = "Phone microphone")

/**
 * 一次录音里的一步：系统递给调用方 [chunk]，同时把路由换成 [routedAfter]。
 * [notify] 为 false 时系统“悄悄”换了路由、不发通知：适配器只能靠每次读完之后再问一次 routedDevice 才发现。
 */
class ReadStep(val chunk: ShortArray, val routedAfter: InputDevice?, val notify: Boolean = true)

/** 假的录音：按脚本一批一批“读”，脚本读完就像被停止了一样返回 -1。路由变化时和系统一样回调监听器。 */
class FakeRecorder(val input: BluetoothInput, var routed: InputDevice?) : Recorder {
    val script = ArrayDeque<ReadStep>()
    var started = false
        private set
    var released = false
        private set
    var releaseCalls = 0
        private set
    var subscriptionCancels = 0
        private set
    var releaseFailure: RuntimeException? = null
    var startFailure: RuntimeException? = null
    var onRead: (() -> Unit)? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * true 时系统的路由通知先攒着、不递送：模拟占住的主线程（真机上通知经主线程的 Handler 递送）。
     * 攒下的通知由 [deliverHeldCallbacks] 一起递送；每一条递送时读到的都是**此刻**的路由，和真实系统一样。
     */
    var holdCallbacks = false
    private var heldCallbacks = 0

    override fun startRecording() {
        startFailure?.let { throw it }
        started = true
    }

    override fun read(buffer: ShortArray): Int {
        if (released) return -1
        val step = script.removeFirstOrNull() ?: return -1
        onRead?.invoke()
        val changed = routed != step.routedAfter
        routed = step.routedAfter
        if (changed && step.notify) notifyListeners()
        step.chunk.copyInto(buffer)
        return step.chunk.size
    }

    private fun notifyListeners() {
        if (holdCallbacks) heldCallbacks += 1 else listeners.forEach { it() }
    }

    /** 主线程腾出手：把攒下的通知按次数逐条递送，并恢复为即时递送。 */
    fun deliverHeldCallbacks() {
        val pending = heldCallbacks
        heldCallbacks = 0
        holdCallbacks = false
        repeat(pending) { listeners.forEach { it() } }
    }

    /** 每次被问到当前路由时执行；测试用它在读线程的两个语句之间插入“这段时间里发生的事”。 */
    var onRoutedDevice: (() -> Unit)? = null

    override fun routedDevice(): InputDevice? {
        onRoutedDevice?.invoke()
        return routed
    }

    override fun onRoutingChanged(callback: () -> Unit): Subscription {
        listeners += callback
        return Subscription { subscriptionCancels += 1; listeners -= callback }
    }

    override fun stopAndRelease() {
        releaseCalls += 1
        released = true
        releaseFailure?.let { throw it }
    }

    /** 系统自己改了路由（没有读数据的时候也会通知）。 */
    fun systemChangesRoute(to: InputDevice?) {
        routed = to
        notifyListeners()
    }
}

class FakeAudioSystem : AudioSystem {
    var inputs: List<InputDevice> = listOf(HEADSET)
    var routeRequestAccepted = true
    var onRouteRequest: (() -> Unit)? = null
    var mode = 0
    var openFailure: RuntimeException? = null
    var onOpen: (() -> Unit)? = null
    var onFocus: (() -> Unit)? = null

    /** true 时系统不给音频焦点：requestAudioFocus 返回 null。 */
    var focusDenied = false

    /** 非空时新打开的录音在 startRecording 时抛它。 */
    var recorderStartFailure: RuntimeException? = null

    /** 打开录音之后系统最初给出的路由；null 表示还没说清。 */
    var initialRouted: InputDevice? = HEADSET

    val routeRequests = CopyOnWriteArrayList<BluetoothInput>()
    var routeCleared = 0
        private set
    val recorders = CopyOnWriteArrayList<FakeRecorder>()
    val focusRequests = CopyOnWriteArrayList<() -> Unit>()
    var focusAbandoned = 0
        private set

    override fun bluetoothInputs(): List<BluetoothInput> = inputs.mapNotNull { BluetoothInput.from(it) }

    override fun requestCommunicationRoute(input: BluetoothInput): Boolean {
        routeRequests += input
        mode = 1
        onRouteRequest?.invoke()
        return routeRequestAccepted
    }

    override fun clearCommunicationRoute() {
        routeCleared += 1
        mode = 0
    }

    override fun requestAudioFocus(onLost: () -> Unit): Subscription? {
        focusRequests += onLost
        onFocus?.invoke()
        if (focusDenied) return null // 系统不给焦点（别的应用占着）
        return Subscription { focusAbandoned += 1 }
    }

    override fun openRecorder(input: BluetoothInput, format: PcmFormat): Recorder {
        onOpen?.invoke()
        openFailure?.let { throw it }
        return FakeRecorder(input, initialRouted).also {
            it.startFailure = recorderStartFailure
            recorders += it
        }
    }
}

class FakeEnvironment : CaptureEnvironment {
    var mic = true
    var bluetooth = true
    var microphoneService = true
    var lifecycle = LifecycleState.FOREGROUND

    override fun micPermissionGranted() = mic

    override fun bluetoothPermissionGranted() = bluetooth

    override fun microphoneServicePermissionGranted() = microphoneService

    override fun lifecycle() = lifecycle
}

/** 记下适配器向协调器报告的每一样东西。 */
class RecordingSink : CaptureSink {
    val routeEvents = CopyOnWriteArrayList<RouteChangeEvent>()
    val frames = CopyOnWriteArrayList<AudioFrames>()
    val unavailable = CopyOnWriteArrayList<ErrorCode>()
    val permissionRevocations = CopyOnWriteArrayList<ErrorCode>()

    /** 收到权限撤销通知时执行（协调器的反应：关闸、释放采集）。 */
    var onPermissionRevoked: ((ErrorCode) -> Unit)? = null
    var interrupted = 0
        private set

    /** 按到达顺序的全部事件，用来核对“先报观测、后递送帧”。 */
    val order = CopyOnWriteArrayList<String>()

    override fun routeChanged(event: RouteChangeEvent) {
        routeEvents += event
        order += "route:${event.epoch}:${event.verificationState.name}"
    }

    override fun frames(frames: AudioFrames) {
        this.frames += frames
        order += "frames:${frames.routeEpoch}"
    }

    override fun inputUnavailable(reason: ErrorCode) {
        unavailable += reason
        order += "unavailable:${reason.name}"
    }

    override fun interrupted() {
        interrupted += 1
        order += "interrupted"
    }

    override fun permissionRevoked(reason: ErrorCode) {
        permissionRevocations += reason
        order += "permission:${reason.name}"
        onPermissionRevoked?.invoke(reason)
    }
}
