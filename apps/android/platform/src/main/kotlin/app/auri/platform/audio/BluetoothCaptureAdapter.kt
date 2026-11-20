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
 *  - 系统的路由通知本身就使已有证据作废（见 [Capture.observe]）：通知可能被占住的主线程压到耳机已经恢复之后才递送，
 *    读到的设备看上去没变不代表中间没变过。
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
    private val permissionHandlers = CopyOnWriteArrayList<(ErrorCode) -> Unit>()
    private val discardedSamples = AtomicLong()
    /** Samples discarded because their input route was ambiguous. */
    val droppedAmbiguousSamples: Long get() = discardedSamples.get()

    /** 上一次核对时两项运行时权限在不在；只有“在 → 不在”的那一刻才通知，所以读写都在这把锁里。 */
    private val permissionLock = Any()
    private var micGranted = environment.micPermissionGranted()
    private var bluetoothGranted = environment.bluetoothPermissionGranted()

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

    override fun onPermissionRevoked(handler: (ErrorCode) -> Unit): Subscription {
        permissionHandlers += handler
        return Subscription { permissionHandlers -= handler }
    }

    /**
     * 界面回到前台时调用；录音中读线程每读完一批也会核对。Android 没有“权限被撤销”的回调，只能核对。
     * （在设置里撤销权限通常直接结束进程，那条路由恢复流程接手；这里管进程还活着的情况。）
     */
    fun permissionsMayHaveChanged() {
        checkPermissions()
    }

    /**
     * 核对两项运行时权限，返回现在缺的那一项（都在则 null）；刚从“在”变成“不在”的那一刻通知协调器一次，之后不重复。
     * 麦克风缺失用 PERMISSION_MIC_DENIED；蓝牙连接缺失没有专门的错误码，沿用资格检查的 INPUT_DEVICE_MISSING（待合同补码）。
     */
    private fun checkPermissions(): ErrorCode? {
        val mic = environment.micPermissionGranted()
        val bluetooth = environment.bluetoothPermissionGranted()
        val missing = when {
            !mic -> ErrorCode.PERMISSION_MIC_DENIED
            !bluetooth -> ErrorCode.INPUT_DEVICE_MISSING
            else -> null
        }
        val justRevoked = synchronized(permissionLock) {
            val lost = (micGranted && !mic) || (bluetoothGranted && !bluetooth)
            micGranted = mic
            bluetoothGranted = bluetooth
            lost
        }
        if (justRevoked && missing != null) permissionHandlers.forEach { it(missing) }
        return missing
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

        /** 是否已经向协调器递送过帧，即路由证据是否已被“用过”。受 this 保护。 */
        private var framesDelivered = false

        /** 系统是否已经发过路由通知。受 this 保护。 */
        private var notified = false

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
            observe(device(), fromNotification = true)
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

        /**
         * 系统观测到 [device]（null 表示系统还没说清）：与上一次不同才报告，epoch 随之递增。
         *
         * [fromNotification]：这次观测由系统的路由通知触发。通知到达时读到的设备可能已经变回原样（耳机断开又恢复、通知被占住的
         * 主线程压到现在才递送），所以**通知本身**就是“上次观测之后路由可能变过”的证据：设备看上去没变也照样报告、epoch 递增，
         * 协调器据此按 route_lost 收场。唯一的例外是录音刚启动、还没递送过任何帧时的第一条通知：平台在启动时会发一条
         * 初始路由通知，证据还没被用过，只确认；否则每次开始录音都会被这条通知自己打断。
         */
        @Synchronized
        fun observe(device: InputDevice?, fromNotification: Boolean = false) {
            if (closed) return
            val verification = when {
                device == null -> VerificationState.UNKNOWN
                device.id == input.device.id && BluetoothInput.from(device) != null -> VerificationState.VERIFIED
                else -> VerificationState.MISMATCH
            }
            val observation = verification to device?.id
            val invalidates = fromNotification && (framesDelivered || notified)
            if (fromNotification) notified = true
            if (observation == lastObservation && !invalidates) return
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
                // 权限被撤销时读也会失败：原因是权限，不是路由。核对会通知协调器；缺权限就不再另报“输入路由变了”
                // （否则同一次打断会被当成 route_lost 而不是 permission_revoked）
                if (checkPermissions() == null) sink.inputUnavailable(ErrorCode.INPUT_ROUTE_CHANGED)
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
                // 权限在录音中被撤销：这一批不可信，不递送；通知先于之后的任何帧进邮箱，协调器据此关闸并释放采集
                if (checkPermissions() != null) continue
                // 判断与递送在同一把锁里：通知引起的 epoch 递增要么在判断之前（这批丢弃），要么在递送之后（协调器先收到这批、再收到变化），
                // 不会出现“判断通过之后通知才到、这批却带着旧 epoch 递送出去”的缝
                synchronized(this) {
                    if (before == null || before.id != input.device.id || BluetoothInput.from(before) == null || before != after ||
                        version != routeChanges.get() || readEpoch != currentEpoch()
                    ) {
                        discardedSamples.addAndGet(read.toLong())
                    } else {
                        framesDelivered = true
                        sink.frames(AudioFrames(readEpoch, buffer.copyOf(read)))
                    }
                }
            }
        }

        @Synchronized
        private fun isClosed() = closed
    }
}
