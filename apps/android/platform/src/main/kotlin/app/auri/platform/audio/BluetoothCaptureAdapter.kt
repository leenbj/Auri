package app.auri.platform.audio

import app.auri.capture.adapter.AudioFrames
import app.auri.capture.adapter.CaptureAdapter
import app.auri.capture.adapter.CaptureSink
import app.auri.capture.adapter.InputRequest
import app.auri.capture.adapter.Subscription
import app.auri.capture.contract.CanStart
import app.auri.capture.contract.CaptureEligibility
import app.auri.capture.contract.ErrorCode
import app.auri.capture.contract.LifecycleState
import app.auri.capture.contract.ObservedInput
import app.auri.capture.contract.PermissionState
import app.auri.capture.contract.PreparedInput
import app.auri.capture.contract.RequestStatus
import app.auri.capture.contract.RequestedDevice
import app.auri.capture.contract.RouteChangeEvent
import app.auri.capture.contract.VerificationState
import app.auri.capture.coordinator.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Android 的采集适配器，只做协调器要求的三件事：向系统请求**蓝牙**输入、报告系统观测到的真实路由、递送音频帧。
 * 闸门、Quarantine、generation 与 route_epoch 的校验都在协调器里；这里不判断“能不能录”，只如实报告系统说了什么。
 *
 * 严格蓝牙输入：
 *  - 没有蓝牙输入设备就拒绝（INPUT_DEVICE_MISSING），**不打开任何录音**；不存在“改用手机麦克风”的分支。
 *    [AudioSystem.openRecorder] 只接受 [BluetoothInput]，类型上就没有别的设备可传。
 *  - Route Evidence 只来自系统的观测（录音的 routedDevice 与路由变化通知）。“已请求蓝牙”“已配对”“已连接”都不是证据：
 *    请求之后系统还没说清就报 unknown，系统说是手机麦克风就报 mismatch。
 *  - 每批读取前后核对实际路由；读取时有回调或两次观测不一致就丢弃，只有稳定的所选耳机帧才递送。
 *
 * 移植自 execution/p0-spikes/android-probe 的 CaptureProbe（setCommunicationDevice + 偏好设备 + routedDevice 的做法），
 * 没有照搬的：探针里日志带设备地址尾号（这里不记任何设备标识）、探针把蓝牙权限被拒映射成独立流程（这里统一由资格检查给出原因）。
 */
class BluetoothCaptureAdapter(
    private val audio: AudioSystem,
    private val environment: CaptureEnvironment,
    private val clock: Clock,
    /** 读线程怎么启动；默认起一个守护线程，测试里改成同步执行。 */
    private val startReader: (Runnable) -> Unit = { Thread(it, "auri-capture-reader").apply { isDaemon = true }.start() },
) : CaptureAdapter {
    private val captures = ConcurrentHashMap<String, Capture>()
    private val routeLock = Any()
    private val permissionHandlers = CopyOnWriteArrayList<() -> Unit>()
    private val discardedSamples = AtomicLong()
    /** Samples discarded because their input route was ambiguous. */
    val droppedAmbiguousSamples: Long get() = discardedSamples.get()

    @Volatile
    private var micWasGranted = environment.micPermissionGranted()

    override fun inspectEligibility(): CaptureEligibility {
        val lifecycle = environment.lifecycle()
        return when {
            !environment.micPermissionGranted() ->
                CaptureEligibility(PermissionState.DENIED, lifecycle, CanStart.NO, ErrorCode.PERMISSION_MIC_DENIED)
            // 没有 BLUETOOTH_CONNECT 就选不了蓝牙设备；合同里没有专门的错误码，按“没有可用的蓝牙输入”报告（待合同补码）
            !environment.bluetoothPermissionGranted() ->
                CaptureEligibility(PermissionState.DENIED, lifecycle, CanStart.NO, ErrorCode.INPUT_DEVICE_MISSING)
            // 前台服务是另一层：运行时权限都给了，麦克风类前台服务的权限缺失也不能开始
            !environment.microphoneServicePermissionGranted() ->
                CaptureEligibility(PermissionState.GRANTED, lifecycle, CanStart.NO, ErrorCode.BACKGROUND_START_DENIED)
            lifecycle == LifecycleState.FOREGROUND -> CaptureEligibility(PermissionState.GRANTED, lifecycle, CanStart.YES, null)
            lifecycle == LifecycleState.UNKNOWN -> CaptureEligibility(PermissionState.GRANTED, lifecycle, CanStart.UNKNOWN, null)
            else -> CaptureEligibility(PermissionState.GRANTED, lifecycle, CanStart.NO, ErrorCode.BACKGROUND_START_DENIED)
        }
    }

    override suspend fun prepareBluetoothInput(request: InputRequest, sink: CaptureSink): PreparedInput {
        val input = audio.bluetoothInputs().firstOrNull() ?: return refuse(request, sink, ErrorCode.INPUT_DEVICE_MISSING, null)
        val capture = Capture(request, sink, input)
        captures[request.operationId] = capture
        try {
            if (!capture.requestRoute()) return capture.fail(ErrorCode.INPUT_ROUTE_UNVERIFIED)
            if (!capture.attachFocus(audio.requestAudioFocus { sink.interrupted() })) return capture.fail(ErrorCode.INPUT_ROUTE_UNVERIFIED)
            val opened = audio.openRecorder(input, request.format)
            if (!capture.attach(opened)) return capture.fail(ErrorCode.INPUT_ROUTE_UNVERIFIED)
            capture.observe(opened.routedDevice())
            startReader { capture.readLoop() }
        } catch (e: Exception) {
            return capture.fail(ErrorCode.INPUT_ROUTE_UNVERIFIED)
        }
        return PreparedInput(
            request.operationId,
            request.generation,
            RequestedDevice(input.device.name, input.device.id.toString()),
            RequestStatus.ACCEPTED_BY_SYSTEM,
        )
    }

    override fun release(operationId: String) {
        captures.remove(operationId)?.close()
    }

    override fun onPermissionRevoked(handler: () -> Unit): Subscription {
        permissionHandlers += handler
        return Subscription { permissionHandlers -= handler }
    }

    /** 界面回到前台时调用：Android 没有“权限被撤销”的回调，进程回来之后核对一次。 */
    fun permissionsMayHaveChanged() {
        val granted = environment.micPermissionGranted()
        if (micWasGranted && !granted) permissionHandlers.forEach { it() }
        micWasGranted = granted
    }

    private fun refuse(request: InputRequest, sink: CaptureSink, reason: ErrorCode, device: InputDevice?): PreparedInput {
        sink.inputUnavailable(reason)
        return PreparedInput(
            request.operationId,
            request.generation,
            RequestedDevice(device?.name, device?.id?.toString()),
            RequestStatus.REJECTED_BY_SYSTEM,
        )
    }

    /** 一次采集的现场：打开的录音、路由请求、焦点，以及系统最近一次观测。 */
    private inner class Capture(val request: InputRequest, val sink: CaptureSink, val input: BluetoothInput) {
        private var recorder: Recorder? = null
        private var closed = false
        private var routeRequested = false
        private var routePending = false
        private var routingSubscription: Subscription? = null
        private var focus: Subscription? = null
        private var epoch = 0L
        private var lastObservation: Pair<VerificationState, Int?>? = null
        private val routeChanges = AtomicLong()

        fun requestRoute(): Boolean = synchronized(routeLock) {
            synchronized(this) {
                if (closed) return false
                routeRequested = true
                routePending = true
            }
            val accepted = try {
                audio.requestCommunicationRoute(input)
            } finally {
                val clear = synchronized(this) {
                    routePending = false
                    if (closed && routeRequested) {
                        routeRequested = false
                        true
                    } else false
                }
                if (clear) audio.clearCommunicationRoute()
            }
            accepted && !isClosed()
        }

        fun attachFocus(acquired: Subscription?): Boolean {
            synchronized(this) {
                if (!closed) {
                    focus = acquired
                    return true
                }
            }
            acquired?.cancel()
            return false
        }

        fun routeChanged(device: () -> InputDevice?) {
            routeChanges.incrementAndGet()
            observe(device())
        }

        /** 把打开的录音交给这次采集；已经被关闭（取消）就立刻释放它并返回 false。 */
        @Synchronized
        fun attach(opened: Recorder): Boolean {
            if (closed) {
                opened.stopAndRelease()
                return false
            }
            recorder = opened
            routingSubscription = opened.onRoutingChanged { routeChanged { opened.routedDevice() } }
            opened.startRecording()
            return true
        }

        fun fail(reason: ErrorCode): PreparedInput {
            close()
            captures.remove(request.operationId)
            return refuse(request, sink, reason, input.device)
        }

        fun close() {
            val rec: Recorder?
            val clearRoute: Boolean
            synchronized(this) {
                if (closed) return
                closed = true
                rec = recorder
                clearRoute = routeRequested && !routePending
                if (clearRoute) routeRequested = false
            }
            try {
                routingSubscription?.cancel()
            } finally {
                try {
                    rec?.stopAndRelease()
                } finally {
                    try {
                        focus?.cancel()
                    } finally {
                        if (clearRoute) synchronized(routeLock) { audio.clearCommunicationRoute() }
                    }
                }
            }
        }

        /** 系统观测到 [device]（null 表示系统还没说清）：与上一次不同才报告，epoch 随之递增。 */
        @Synchronized
        fun observe(device: InputDevice?) {
            if (closed) return
            val verification = when {
                device == null -> VerificationState.UNKNOWN
                device.id == input.device.id && BluetoothInput.from(device) != null -> VerificationState.VERIFIED
                else -> VerificationState.MISMATCH
            }
            val observation = verification to device?.id
            if (observation == lastObservation) return
            lastObservation = observation
            epoch += 1
            sink.routeChanged(
                RouteChangeEvent(
                    captureSessionId = request.captureSessionId,
                    epoch = epoch,
                    observedInput = ObservedInput(routeTypeOf(device), device?.name),
                    observedAtMonotonicNs = clock.monotonicNs(),
                    verificationState = verification,
                ),
            )
        }

        @Synchronized
        private fun currentEpoch() = epoch

        fun readLoop() {
            try {
                readFrames()
            } catch (e: Exception) {
                if (!isClosed()) failRead()
            }
        }

        private fun failRead() {
            try {
                sink.inputUnavailable(ErrorCode.INPUT_ROUTE_CHANGED)
            } finally {
                captures.remove(request.operationId, this)
                close()
            }
        }

        private fun readFrames() {
            val rec = synchronized(this) { recorder } ?: return
            val buffer = ShortArray(request.format.sampleRateHz / 10 * request.format.channels) // 100 ms 一批
            while (true) {
                val before = rec.routedDevice()
                observe(before)
                val version = routeChanges.get()
                val readEpoch = currentEpoch()
                val read = rec.read(buffer)
                if (isClosed()) {
                    if (read > 0) discardedSamples.addAndGet(read.toLong())
                    return
                }
                if (read < 0) {
                    failRead()
                    return
                }
                if (read == 0) continue
                val after = rec.routedDevice()
                observe(after)
                if (before == null || before.id != input.device.id || BluetoothInput.from(before) == null || before != after ||
                    version != routeChanges.get() || readEpoch != currentEpoch()
                ) {
                    discardedSamples.addAndGet(read.toLong())
                } else {
                    sink.frames(AudioFrames(readEpoch, buffer.copyOf(read)))
                }
            }
        }

        @Synchronized
        private fun isClosed() = closed
    }
}
