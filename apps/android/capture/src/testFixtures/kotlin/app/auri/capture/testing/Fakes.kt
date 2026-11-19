package app.auri.capture.testing

import app.auri.capture.adapter.AudioFrames
import app.auri.capture.adapter.CaptureAdapter
import app.auri.capture.adapter.CaptureSink
import app.auri.capture.adapter.ControlAdapter
import app.auri.capture.adapter.InputRequest
import app.auri.capture.adapter.PcmFormat
import app.auri.capture.adapter.RecordingStore
import app.auri.capture.adapter.RecordingWriter
import app.auri.capture.adapter.SealedAudio
import app.auri.capture.adapter.Subscription
import app.auri.capture.adapter.TranscriptionAdapter
import app.auri.capture.adapter.TranscriptionOutcome
import app.auri.capture.adapter.TranscriptionRequest
import app.auri.capture.adapter.TranscriptionResult
import app.auri.capture.contract.AsrCapabilities
import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.CanStart
import app.auri.capture.contract.CaptureEligibility
import app.auri.capture.contract.CaptureHandle
import app.auri.capture.contract.CaptureState
import app.auri.capture.contract.ControlSource
import app.auri.capture.contract.ErrorCode
import app.auri.capture.contract.GateState
import app.auri.capture.contract.LifecycleState
import app.auri.capture.contract.NormalizedControlEvent
import app.auri.capture.contract.ObservedInput
import app.auri.capture.contract.ObservedInputRouteType
import app.auri.capture.contract.PermissionState
import app.auri.capture.contract.PlatformEvent
import app.auri.capture.contract.PreparedInput
import app.auri.capture.contract.RequestStatus
import app.auri.capture.contract.RequestedDevice
import app.auri.capture.contract.RouteChangeEvent
import app.auri.capture.contract.VerificationState
import app.auri.capture.coordinator.CaptureConfig
import app.auri.capture.coordinator.CaptureCoordinator
import app.auri.capture.coordinator.CaptureDecision
import app.auri.capture.coordinator.CaptureOutput
import app.auri.capture.coordinator.Clock
import app.auri.capture.coordinator.IdGenerator
import app.auri.capture.coordinator.SealedRecording
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

// 假适配器：协调器测试（JVM、无设备）的全部“系统”。它们只记录调用、按测试的指令回调，自己不实现任何闸门逻辑——
// 闸门、Quarantine、generation 与 epoch 校验都在真正的协调器里，否则测试就只是在验证假货。

/** 用测试调度器的虚拟时间当时钟。 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeClock(private val scheduler: TestCoroutineScheduler, private val wallBase: Instant = Instant.parse("2026-11-18T08:00:00Z")) : Clock {
    // 加一个偏移：虚拟时间 0 时单调时钟也不是 0，命令时间戳可以落在它前面
    override fun monotonicNs(): Long = (scheduler.currentTime + 1_000L) * 1_000_000L

    override fun now(): Instant = wallBase.plusMillis(scheduler.currentTime)

    /** 虚拟时间 [ms] 毫秒处对应的单调时钟读数。 */
    fun monotonicNsAt(ms: Long): Long = (ms + 1_000L) * 1_000_000L
}

/** 确定的小写 UUID：00000000-0000-4000-8000-000000000001、…002。 */
class SequentialIds(private val prefix: Int = 0) : IdGenerator {
    private var next = 0L

    @Synchronized
    override fun newUuid(): String {
        next += 1
        return "%08x-0000-4000-8000-%012x".format(prefix, next)
    }
}

class FakeControlAdapter(private val clock: Clock) : ControlAdapter {
    var canProvideControl = true
    var enableCalls = 0
        private set
    var disableCalls = 0
        private set
    private val handlers = CopyOnWriteArrayList<(NormalizedControlEvent) -> Unit>()
    private var sequence = 0L
    private var eventCounter = 0L

    /** 非空时 enableUserControlMode 抛它：模拟适配器自己的 bug。 */
    var throwOnEnable: RuntimeException? = null

    override fun enableUserControlMode(): Boolean {
        enableCalls += 1
        throwOnEnable?.let { throw it }
        return canProvideControl
    }

    override fun disableUserControlMode() {
        disableCalls += 1
    }

    override fun onCommand(handler: (NormalizedControlEvent) -> Unit): Subscription {
        handlers += handler
        return Subscription { handlers -= handler }
    }

    /** 递送一个控制命令。[atNs] 是命令发出的单调时钟读数（默认现在）。 */
    fun press(
        command: BusinessCommand,
        source: ControlSource = ControlSource.SCREEN_CONTROL,
        atNs: Long = clock.monotonicNs(),
    ): NormalizedControlEvent {
        val platformEvent = when (source) {
            ControlSource.SCREEN_CONTROL -> PlatformEvent.SCREEN_BUTTON
            ControlSource.VOLUME_GESTURE -> PlatformEvent.VOLUME_CHANGE
            else -> when (command) {
                BusinessCommand.TOGGLE -> PlatformEvent.TOGGLE
                BusinessCommand.START -> PlatformEvent.PLAY
                BusinessCommand.STOP -> PlatformEvent.PAUSE
                BusinessCommand.IGNORE -> PlatformEvent.NEXT
            }
        }
        eventCounter += 1
        val event = NormalizedControlEvent(
            eventId = "%08x-0000-4000-8000-%012x".format(0xC0, eventCounter),
            source = source,
            platformEvent = platformEvent,
            command = command,
            monotonicNs = atNs,
            sequence = sequence++,
            profileId = if (source == ControlSource.SCREEN_CONTROL) null else "fixture-profile",
            profileVersion = if (source == ControlSource.SCREEN_CONTROL) null else 1,
            rawEventId = null,
        )
        handlers.forEach { it(event) }
        return event
    }
}

class FakeCaptureAdapter : CaptureAdapter {
    var eligibility = CaptureEligibility(PermissionState.GRANTED, LifecycleState.FOREGROUND, CanStart.YES, null)

    /** 非空时模拟“耳机麦克风不可用”：通过 sink 报告原因并拒绝，不采集任何东西。 */
    var refuseWith: ErrorCode? = null

    /** 每次 prepare 开头执行；测试可以在这里挂起，制造“准备还没返回”的竞态。 */
    var prepareHook: (suspend (InputRequest) -> Unit)? = null
    var releaseFailure: RuntimeException? = null

    val requests = CopyOnWriteArrayList<InputRequest>()
    val releases = CopyOnWriteArrayList<String>()
    private val sinks = CopyOnWriteArrayList<CaptureSink>()
    private val permissionHandlers = CopyOnWriteArrayList<() -> Unit>()
    private val lastEpoch = mutableMapOf<Int, Long>()

    override fun inspectEligibility(): CaptureEligibility = eligibility

    override suspend fun prepareBluetoothInput(request: InputRequest, sink: CaptureSink): PreparedInput {
        requests += request
        sinks += sink
        prepareHook?.invoke(request)
        val refusal = refuseWith
        if (refusal != null) {
            sink.inputUnavailable(refusal)
            return PreparedInput(request.operationId, request.generation, RequestedDevice(null, null), RequestStatus.REJECTED_BY_SYSTEM)
        }
        return PreparedInput(request.operationId, request.generation, RequestedDevice(DEVICE_NAME, "fixture-device-ref"), RequestStatus.ACCEPTED_BY_SYSTEM)
    }

    override fun release(operationId: String) {
        releases += operationId
        releaseFailure?.let { throw it }
    }

    override fun onPermissionRevoked(handler: () -> Unit): Subscription {
        permissionHandlers += handler
        return Subscription { permissionHandlers -= handler }
    }

    fun revokePermission() = permissionHandlers.forEach { it() }

    /** 第 [session] 个会话（默认最近一个）的 sink：旧会话的 sink 在取消之后依然可以回调，用来模拟迟到的回调。 */
    fun sink(session: Int = requests.lastIndex): CaptureSink = sinks[session]

    private fun nextEpoch(session: Int, epoch: Long?): Long = (epoch ?: ((lastEpoch[session] ?: 0L) + 1)).also { lastEpoch[session] = it }

    /** 系统观测到的一次路由变化，经第 [session] 个会话的 sink 递送。 */
    fun route(
        verification: VerificationState,
        type: ObservedInputRouteType,
        epoch: Long? = null,
        session: Int = requests.lastIndex,
    ): RouteChangeEvent {
        val event = RouteChangeEvent(
            captureSessionId = requests[session].captureSessionId,
            epoch = nextEpoch(session, epoch),
            observedInput = ObservedInput(type, DEVICE_NAME),
            observedAtMonotonicNs = 0,
            verificationState = verification,
        )
        sinks[session].routeChanged(event)
        return event
    }

    fun verifiedRoute(epoch: Long? = null, session: Int = requests.lastIndex) =
        route(VerificationState.VERIFIED, ObservedInputRouteType.BLUETOOTH_HFP, epoch, session)

    fun mismatchRoute(epoch: Long? = null, session: Int = requests.lastIndex) =
        route(VerificationState.MISMATCH, ObservedInputRouteType.BUILT_IN_MIC, epoch, session)

    /** 递送 [samples] 个采样（值是确定的，便于核对哈希）。 */
    fun frames(samples: Int, epoch: Long, session: Int = requests.lastIndex) {
        sinks[session].frames(AudioFrames(epoch, ShortArray(samples) { (1_000 + it % 100).toShort() }))
    }

    fun interrupt(session: Int = requests.lastIndex) = sinks[session].interrupted()

    companion object {
        const val DEVICE_NAME = "Fixture Headset"
    }
}

/** 存储收到的每一次调用，不论成功与否——“确认之前一个字节都没写”就靠这份记录证明。 */
sealed interface StoreEvent {
    data class Begin(val recordId: String, val handle: CaptureHandle, val format: PcmFormat) : StoreEvent
    data class Append(val handle: CaptureHandle, val samples: Int, val accepted: Boolean) : StoreEvent
    data class Seal(val recordId: String) : StoreEvent
    data class Abort(val recordId: String) : StoreEvent
}

class FakeRecordingStore : RecordingStore {
    val events = CopyOnWriteArrayList<StoreEvent>()

    /** 非空时 begin 抛它。 */
    var failBegin: IOException? = null

    /** 写满这么多个采样之后，再 append 就抛 IOException；null 表示不失败。 */
    var failAppendAfterSamples: Long? = null

    /** 非空时 seal 挂起等它，测试借此把封存卡在 FINALIZING。 */
    var sealGate: CompletableDeferred<Unit>? = null

    var failSeal: IOException? = null

    /** 非空时 seal 抛它（不是 IOException）：模拟适配器自己的 bug。 */
    var failSealUnexpectedly: RuntimeException? = null

    /** 所有成功写入的采样（按顺序）。 */
    val written = mutableListOf<Short>()

    override fun begin(recordId: String, handle: CaptureHandle, format: PcmFormat): RecordingWriter {
        events += StoreEvent.Begin(recordId, handle, format)
        failBegin?.let { throw it }
        return object : RecordingWriter {
            private var sealed = false

            override fun append(handle: CaptureHandle, samples: ShortArray) {
                val limit = failAppendAfterSamples
                val accepted = handle.gateState == GateState.OPEN && !sealed && (limit == null || written.size < limit)
                events += StoreEvent.Append(handle, samples.size, accepted)
                if (handle.gateState != GateState.OPEN) throw IllegalStateException("闸门已关，不接受写入")
                if (limit != null && written.size >= limit) throw IOException("磁盘已满（测试注入）")
                written += samples.toList()
            }

            override suspend fun seal(): SealedAudio {
                events += StoreEvent.Seal(recordId)
                sealGate?.await()
                failSeal?.let { throw it }
                failSealUnexpectedly?.let { throw it }
                sealed = true
                val bytes = ByteArray(written.size * 2)
                written.forEachIndexed { i, sample ->
                    bytes[2 * i] = (sample.toInt() and 0xFF).toByte()
                    bytes[2 * i + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
                }
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                return SealedAudio("fake://$recordId.wav", sha, 44L + bytes.size, written.size.toLong(), format)
            }

            override fun abort() {
                events += StoreEvent.Abort(recordId)
            }
        }
    }

    /** 成功写入存储的采样总数。 */
    val writtenSamples: Int get() = events.filterIsInstance<StoreEvent.Append>().filter { it.accepted }.sumOf { it.samples }
}

class CollectingOutput : CaptureOutput {
    val recordings = CopyOnWriteArrayList<SealedRecording>()

    /** 封存失败的通知：(recordId, 原因)。 */
    val lost = CopyOnWriteArrayList<Pair<String, ErrorCode>>()

    /** 非空时，收到的每个结果在记下之后继续转交给它（把真正的 RecordPipeline 接在协调器后面）。 */
    var forwardTo: CaptureOutput? = null

    override fun recordingSealed(sealed: SealedRecording) {
        recordings += sealed
        forwardTo?.recordingSealed(sealed)
    }

    override fun recordingLost(recordId: String, reason: ErrorCode) {
        lost += recordId to reason
        forwardTo?.recordingLost(recordId, reason)
    }
}

/**
 * 假的转录适配器：按测试给的函数决定每个录音的结果，记下每次请求，并记录同时进行的转录任务数（验证“一次只转一个”）。
 */
class FakeTranscriptionAdapter(var capabilities: AsrCapabilities = Samples.capabilities) : TranscriptionAdapter {
    val requests = CopyOnWriteArrayList<TranscriptionRequest>()
    var maxConcurrent = 0
        private set
    private var running = 0

    /** 每个请求的结果；默认转出一句固定的话。 */
    var outcome: (TranscriptionRequest) -> TranscriptionOutcome =
        { TranscriptionOutcome.Text("你好，这是转录出的文字。", "zh", 0.9) }

    /** 转录开始时执行；测试可以在这里挂起，把转录卡住。 */
    var hook: (suspend (TranscriptionRequest) -> Unit)? = null

    override suspend fun capabilities() = capabilities

    override suspend fun transcribe(request: TranscriptionRequest): TranscriptionResult {
        requests += request
        running += 1
        maxConcurrent = maxOf(maxConcurrent, running)
        try {
            hook?.invoke(request)
            return TranscriptionResult(Samples.job(requests.size), capabilities, outcome(request))
        } finally {
            running -= 1
        }
    }
}

/**
 * 一套接好的协调器 + 假适配器，跑在 [scope]（runTest 的 TestScope）的虚拟时间里。
 * [settle] 只处理“当前虚拟时刻”已经就绪的工作（不推进时间，所以路由确认超时等定时器不会被提前触发）；
 * 需要让时间流逝时用 [advance]。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureRig(
    private val scope: TestScope,
    config: CaptureConfig = CaptureConfig(),
    // 默认排队：准备任务要等 settle 才开始，“发出又立刻取消”的请求永远不会运行，测试看不见它；
    // 传 UnconfinedTestDispatcher 则一发出就当场开始（线上的适配器跑在真线程上，取消可能来不及）
    adapterDispatcher: CoroutineDispatcher = StandardTestDispatcher(scope.testScheduler),
) {
    val clock = FakeClock(scope.testScheduler)
    val ids = SequentialIds()
    val control = FakeControlAdapter(clock)
    val capture = FakeCaptureAdapter()
    val store = FakeRecordingStore()
    val output = CollectingOutput()
    val config = config
    val decisions = CopyOnWriteArrayList<CaptureDecision>()

    val coordinator = CaptureCoordinator(
        control = control,
        capture = capture,
        store = store,
        output = output,
        clock = clock,
        ids = ids,
        config = config,
        scope = scope.backgroundScope,
        adapterDispatcher = adapterDispatcher,
    )

    init {
        scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
            coordinator.decisions.toCollection(decisions)
        }
    }

    val state: CaptureState get() = coordinator.snapshot.value.state

    fun settle() = scope.testScheduler.runCurrent()

    fun advance(ms: Long) {
        scope.testScheduler.advanceTimeBy(ms)
        scope.testScheduler.runCurrent()
    }
}
