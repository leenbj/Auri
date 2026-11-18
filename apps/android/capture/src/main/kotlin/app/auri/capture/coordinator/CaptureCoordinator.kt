package app.auri.capture.coordinator

import app.auri.capture.adapter.AudioFrames
import app.auri.capture.adapter.CaptureAdapter
import app.auri.capture.adapter.CaptureSink
import app.auri.capture.adapter.ControlAdapter
import app.auri.capture.adapter.InputRequest
import app.auri.capture.adapter.RecordingStore
import app.auri.capture.adapter.RecordingWriter
import app.auri.capture.adapter.Subscription
import app.auri.capture.contract.AudioChunk
import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.CanStart
import app.auri.capture.contract.CaptureHandle
import app.auri.capture.contract.CaptureState
import app.auri.capture.contract.CaptureState.ARMED
import app.auri.capture.contract.CaptureState.BLOCKED
import app.auri.capture.contract.CaptureState.DISARMED
import app.auri.capture.contract.CaptureState.FINALIZING
import app.auri.capture.contract.CaptureState.RECORDING
import app.auri.capture.contract.CaptureState.ROUTING
import app.auri.capture.contract.CaptureStatus
import app.auri.capture.contract.ContractRules
import app.auri.capture.contract.ControlSource
import app.auri.capture.contract.EndReason
import app.auri.capture.contract.ErrorCode
import app.auri.capture.contract.GateState
import app.auri.capture.contract.NormalizedControlEvent
import app.auri.capture.contract.ObservedInput
import app.auri.capture.contract.PermissionState
import app.auri.capture.contract.PreparedInput
import app.auri.capture.contract.RecordingResult
import app.auri.capture.contract.RequestStatus
import app.auri.capture.contract.RouteChangeEvent
import app.auri.capture.contract.TimeRange
import app.auri.capture.contract.VerificationState
import app.auri.capture.control.ControlConfig
import app.auri.capture.machine.CaptureEvent
import app.auri.capture.machine.CaptureMachine
import app.auri.capture.machine.MachineRule
import app.auri.capture.machine.Outcome
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 采集协调器（Capture Coordinator）：全系统最深的模块，接口很小，竞态全在里面。
 *
 * 对外只有三类东西：
 *  - 输入：控制命令（经 [ControlAdapter]）、路由观测与音频帧（经每个会话各自的 [CaptureSink]）、
 *    界面操作（[enableControlMode] 等几个方法）；
 *  - 输出：[CaptureOutput.recordingSealed]（一个路由已确认的 Capture Session 封存后的 RecordingResult）；
 *  - 可观察：[snapshot]（状态、实际输入来源、帧计数）与 [decisions]（每个输入被如何处理）。
 *
 * 里面保证的事（Ticket 16 第 1–5 项，每条都有对应测试）：
 *  1. 命令串行：所有输入进同一个邮箱，由一个协程逐个处理，状态只在这个协程里被读写，没有锁也没有竞态。
 *  2. 严格按 capture.machine.json 转移；表里没有的组合被拒绝，状态不变。
 *  3. 路由确认之前的音频只进内存 Quarantine；确认之后也不提升，只有确认之后收到的帧才写入存储
 *     （[RecordingStore.begin] 在确认之后才被调用）。
 *  4. 拿不到蓝牙输入就什么都不录、给出原因；不存在改用手机麦克风的分支，也不自动重试——只有再收到 START 才重试。
 *  5. 每个会话带 capture_session_id、generation 与 route_epoch：旧会话/旧 generation 的回调、不够新的 epoch、
 *     封存期间发出的过时 TOGGLE，都改变不了当前会话。
 *
 * 线程：邮箱处理协程在 [scope] 里运行；适配器的挂起调用（准备输入、封存）在 [adapterDispatcher] 里运行，
 * 结果以消息的形式回到邮箱。[ControlAdapter]/[CaptureSink] 的回调可以来自任意线程。
 */
class CaptureCoordinator(
    private val control: ControlAdapter,
    private val capture: CaptureAdapter,
    private val store: RecordingStore,
    private val output: CaptureOutput,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val config: CaptureConfig = CaptureConfig(),
    private val scope: CoroutineScope,
    private val adapterDispatcher: CoroutineDispatcher,
) {
    // ---- 只在邮箱协程里读写的状态 ----
    private var state: CaptureState = CaptureMachine.initialState
    private var blockedReason: ErrorCode? = null
    private var session: Session? = null
    private var generation = 0L
    private var counters = FrameCounters()
    private var publishVersion = 0L
    private var lastFinalizedNs: Long? = null
    private var durationLimitMs: Long? = null
    private var controlModeEnabled = false

    // ---- 对外可观察 ----
    private val snapshotFlow = MutableStateFlow(buildSnapshot())
    val snapshot: StateFlow<CaptureSnapshot> = snapshotFlow.asStateFlow()

    private val decisionFlow = MutableSharedFlow<CaptureDecision>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val decisions: SharedFlow<CaptureDecision> = decisionFlow.asSharedFlow()

    private val mailbox = Channel<Message>(Channel.UNLIMITED)
    private val subscriptions: List<Subscription>

    init {
        subscriptions = listOf(
            control.onCommand { post(Message.Command(it)) },
            capture.onPermissionRevoked { post(Message.PermissionRevoked) },
        )
        scope.launch {
            for (message in mailbox) {
                try {
                    handle(message)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 适配器的 bug 不能让协调器停摆：放弃这条输入，留下一条可诊断的记录，继续处理后面的
                    emit(CaptureDecision(null, Outcome.REJECTED, state, state, Cause.INTERNAL_ERROR))
                    publish()
                }
            }
        }
    }

    // ---- 界面操作（任意线程，只入队） ----

    /** 用户开启控制模式（DISARMED → ARMED）。 */
    fun enableControlMode() = post(Message.EnableControlMode)

    /** 用户退出控制模式。录音中与封存中会被拒绝，必须先停止。 */
    fun disableControlMode() = post(Message.DisableControlMode)

    /** 用户在屏幕上取消准备（ROUTING → ARMED）。 */
    fun userCancel() = post(Message.UserCancel)

    /** 前台服务启动或提升失败：准备时受阻，录音时按系统中断封存。 */
    fun foregroundServiceFailed() = post(Message.ForegroundServiceFailed)

    /** 进程被杀后恢复时调用：一律回到 DISARMED，不自动开麦（第 12 章）。 */
    fun processRestored() = post(Message.ProcessRestored)

    /** 停止接收输入并取消所有定时器。不会强行中断已经在进行的封存。 */
    fun close() {
        subscriptions.forEach { it.cancel() }
        post(Message.Close)
    }

    private fun post(message: Message) {
        mailbox.trySend(message)
    }

    // ---- 消息 ----

    private class Tag(val sessionId: String, val generation: Long)

    private enum class TimerKind { ROUTE, DURATION }

    private sealed interface SealOutcome {
        class Done(val sealed: SealedRecording?) : SealOutcome
        class Failed(val recordId: String, val cause: Cause) : SealOutcome
    }

    private sealed interface Message {
        class Command(val event: NormalizedControlEvent) : Message
        data object EnableControlMode : Message
        data object DisableControlMode : Message
        data object UserCancel : Message
        data object ForegroundServiceFailed : Message
        data object ProcessRestored : Message
        data object PermissionRevoked : Message
        data object Close : Message
        class Route(val tag: Tag, val event: RouteChangeEvent) : Message
        class Frames(val tag: Tag, val frames: AudioFrames) : Message
        class InputUnavailable(val tag: Tag, val reason: ErrorCode) : Message
        class Interrupted(val tag: Tag) : Message
        class Timeout(val tag: Tag, val kind: TimerKind) : Message
        class Prepared(val tag: Tag, val prepared: PreparedInput?) : Message
        class Sealed(val tag: Tag, val outcome: SealOutcome) : Message
    }

    /** 一个事件附带的上下文：结束原因、错误码、路由证据、触发原因。 */
    private class Context(
        val endReason: EndReason? = null,
        val errorCode: ErrorCode? = null,
        val evidence: RouteChangeEvent? = null,
        val cause: Cause = Cause.MACHINE,
        val manual: Boolean = false,
    )

    private inner class TaggedSink(private val tag: Tag) : CaptureSink {
        override fun routeChanged(event: RouteChangeEvent) = post(Message.Route(tag, event))
        override fun frames(frames: AudioFrames) = post(Message.Frames(tag, frames))
        override fun inputUnavailable(reason: ErrorCode) = post(Message.InputUnavailable(tag, reason))
        override fun interrupted() = post(Message.Interrupted(tag))
    }

    /** 一次 Capture Session 的内部状态。 */
    private class Session(val id: String, val generation: Long, val operationId: String, val quarantine: Quarantine) {
        var lastEpoch = -1L
        var observed: ObservedInput? = null
        var handle: CaptureHandle? = null
        var evidence: RouteChangeEvent? = null
        var evidenceId: String? = null
        var recordId: String? = null
        var writer: RecordingWriter? = null
        var startedAt: Instant? = null
        var writtenSamples = 0L
        var routeTimer: Job? = null
        var durationTimer: Job? = null
        var prepareJob: Job? = null

        fun cancelJobs() {
            routeTimer?.cancel()
            durationTimer?.cancel()
            prepareJob?.cancel()
        }
    }

    // ---- 邮箱处理 ----

    private fun handle(message: Message) {
        when (message) {
            is Message.Command -> onCommand(message.event)
            Message.EnableControlMode -> {
                if (!controlModeEnabled && state != DISARMED) {
                    if (control.enableUserControlMode()) controlModeEnabled = true
                    else reject(CaptureEvent.ENABLE_CONTROL_MODE, Cause.CONTROL_NOT_AVAILABLE, ErrorCode.CONTROL_NOT_AVAILABLE)
                } else {
                    dispatch(CaptureEvent.ENABLE_CONTROL_MODE)
                }
            }
            Message.DisableControlMode -> dispatch(CaptureEvent.DISABLE_CONTROL_MODE)
            Message.UserCancel -> dispatch(CaptureEvent.USER_CANCEL)
            Message.ForegroundServiceFailed -> when (state) {
                ROUTING -> dispatch(CaptureEvent.ROUTE_UNVERIFIED, Context(errorCode = ErrorCode.BACKGROUND_START_DENIED))
                RECORDING -> dispatch(CaptureEvent.SYSTEM_INTERRUPTION, Context(errorCode = ErrorCode.BACKGROUND_START_DENIED))
                else -> Unit
            }
            Message.ProcessRestored -> dispatch(CaptureEvent.PROCESS_RESTORED)
            Message.PermissionRevoked -> dispatch(CaptureEvent.PERMISSION_REVOKED, Context(errorCode = ErrorCode.PERMISSION_MIC_DENIED))
            Message.Close -> {
                session?.cancelJobs()
                mailbox.close()
            }
            is Message.Route -> onRoute(message)
            is Message.Frames -> onFrames(message)
            is Message.InputUnavailable -> onInputUnavailable(message)
            is Message.Interrupted -> onInterrupted(message)
            is Message.Timeout -> onTimeout(message)
            is Message.Prepared -> onPrepared(message)
            is Message.Sealed -> onSealed(message)
        }
        publish()
    }

    private fun onCommand(event: NormalizedControlEvent) {
        val machineEvent = when (event.command) {
            BusinessCommand.START -> CaptureEvent.START
            BusinessCommand.STOP -> CaptureEvent.STOP
            BusinessCommand.TOGGLE -> CaptureEvent.TOGGLE
            BusinessCommand.IGNORE -> {
                emit(CaptureDecision(null, Outcome.IGNORED, state, state, Cause.IGNORE_COMMAND))
                return
            }
        }
        // 封存期间（或更早）发出、封存完成之后才送到的 START/TOGGLE 是过时命令：不能因为它开始下一段。
        // 命令的时间戳与 lastFinalizedNs 来自同一个单调时钟；封存完成之后新按下的命令时间戳不会更早（相等也算有效：分辨不出先后）。
        val finalizedAt = lastFinalizedNs
        val now = clock.monotonicNs()
        if ((machineEvent == CaptureEvent.START || machineEvent == CaptureEvent.TOGGLE) &&
            (event.monotonicNs > now || now - event.monotonicNs > ControlConfig().maxIssuedAgeNs)
        ) {
            reject(machineEvent, Cause.STALE_COMMAND)
            return
        }
        if ((machineEvent == CaptureEvent.START || machineEvent == CaptureEvent.TOGGLE) && finalizedAt != null && event.monotonicNs < finalizedAt) {
            emit(CaptureDecision(machineEvent, Outcome.IGNORED, state, state, Cause.STALE_COMMAND))
            return
        }
        if (event.source != ControlSource.SCREEN_CONTROL && !controlModeEnabled) {
            emit(CaptureDecision(machineEvent, Outcome.IGNORED, state, state, Cause.MACHINE))
            return
        }
        if (event.source == ControlSource.SCREEN_CONTROL && machineEvent == CaptureEvent.START && state == DISARMED) {
            dispatch(CaptureEvent.ENABLE_CONTROL_MODE, Context(manual = true))
        }
        dispatch(machineEvent, Context(endReason = endReasonFor(event.source)))
    }

    private fun endReasonFor(source: ControlSource): EndReason = when (source) {
        ControlSource.SCREEN_CONTROL -> EndReason.SCREEN_CONTROL
        ControlSource.VOLUME_GESTURE -> EndReason.VOLUME_GESTURE
        ControlSource.HEADSET_REMOTE, ControlSource.UNKNOWN_REMOTE -> EndReason.HEADSET_COMMAND
    }

    private fun onRoute(message: Message.Route) {
        val event = message.event
        val verified = event.verificationState == VerificationState.VERIFIED
        val nominal = if (verified) CaptureEvent.ROUTE_VERIFIED else CaptureEvent.ROUTE_UNVERIFIED
        val current = currentSession(message.tag, nominal) ?: return
        if (event.captureSessionId != current.id) {
            reject(nominal, Cause.STALE_SESSION)
            return
        }
        if (event.epoch <= current.lastEpoch) {
            reject(nominal, Cause.STALE_EPOCH)
            return
        }
        current.lastEpoch = event.epoch
        current.observed = event.observedInput
        when {
            // 闸门开着时，任何新的路由观测都意味着路由变了：epoch 递增，已有句柄随即失效，立刻关闸
            state == RECORDING -> dispatch(CaptureEvent.ROUTE_LOST, Context(errorCode = ErrorCode.INPUT_ROUTE_CHANGED))
            verified -> dispatch(CaptureEvent.ROUTE_VERIFIED, Context(evidence = event))
            event.verificationState == VerificationState.MISMATCH ->
                dispatch(CaptureEvent.ROUTE_UNVERIFIED, Context(errorCode = ErrorCode.INPUT_ROUTE_UNVERIFIED))
            // unknown：系统还没说清实际输入是什么，继续等，由路由确认超时兜底；这期间的帧只进 Quarantine
            else -> emit(CaptureDecision(null, Outcome.IGNORED, state, state, Cause.MACHINE))
        }
    }

    private fun onFrames(message: Message.Frames) {
        val current = session
        if (current == null || current.generation != message.tag.generation) {
            counters = counters.copy(droppedStale = counters.droppedStale + 1)
            return
        }
        val samples = message.frames.samples
        when (state) {
            ROUTING -> current.quarantine.offer(samples)
            RECORDING -> {
                val handle = current.handle ?: error("RECORDING 必有句柄")
                if (message.frames.routeEpoch != handle.verifiedRouteEpoch) {
                    counters = counters.copy(droppedGate = counters.droppedGate + 1)
                    return
                }
                try {
                    (current.writer ?: error("RECORDING 必有写入器")).append(handle, samples)
                } catch (e: IOException) {
                    dispatch(CaptureEvent.SYSTEM_INTERRUPTION, Context(errorCode = ErrorCode.STORAGE_EXHAUSTED, cause = Cause.STORAGE_FAILURE))
                    return
                }
                current.writtenSamples += samples.size
                counters = counters.copy(writtenSamples = counters.writtenSamples + samples.size)
            }
            else -> counters = counters.copy(droppedGate = counters.droppedGate + 1)
        }
    }

    private fun onInputUnavailable(message: Message.InputUnavailable) {
        val nominal = if (state == RECORDING) CaptureEvent.ROUTE_LOST else CaptureEvent.ROUTE_UNVERIFIED
        currentSession(message.tag, nominal) ?: return
        dispatch(nominal, Context(errorCode = message.reason))
    }

    private fun onInterrupted(message: Message.Interrupted) {
        currentSession(message.tag, CaptureEvent.SYSTEM_INTERRUPTION) ?: return
        dispatch(CaptureEvent.SYSTEM_INTERRUPTION, Context(errorCode = ErrorCode.AUDIO_INTERRUPTED))
    }

    private fun onTimeout(message: Message.Timeout) {
        val event = if (message.kind == TimerKind.ROUTE) CaptureEvent.ROUTE_TIMEOUT else CaptureEvent.DURATION_LIMIT
        currentSession(message.tag, event) ?: return
        dispatch(event, Context(errorCode = if (message.kind == TimerKind.ROUTE) ErrorCode.INPUT_ROUTE_UNVERIFIED else null))
    }

    private fun onPrepared(message: Message.Prepared) {
        val current = session
        if (current == null || current.generation != message.tag.generation) {
            // 这次准备所属的会话已经取消：把它可能已经打开的采集关掉（被系统拒绝的没有打开过任何东西）
            message.prepared?.takeIf { it.requestStatus != RequestStatus.REJECTED_BY_SYSTEM }?.let { releaseCapture(it.operationId) }
            return
        }
        val prepared = message.prepared
        if ((prepared == null || prepared.requestStatus == RequestStatus.REJECTED_BY_SYSTEM) && state == ROUTING) {
            // 适配器通常已经通过 inputUnavailable 说明了原因（那条消息先到，状态已是 BLOCKED，不会走到这里）
            dispatch(CaptureEvent.ROUTE_UNVERIFIED, Context(errorCode = ErrorCode.INPUT_ROUTE_UNVERIFIED))
        }
    }

    private fun onSealed(message: Message.Sealed) {
        val current = session
        val isCurrent = current != null && current.generation == message.tag.generation && state == FINALIZING
        val failure = message.outcome as? SealOutcome.Failed
        // 封存好的录音一定交出去，即使这期间会话已被重置（进程恢复）：已经落盘的音频不能悄悄丢掉；丢了也一定要通知
        (message.outcome as? SealOutcome.Done)?.sealed?.let(output::recordingSealed)
        failure?.let { output.recordingLost(it.recordId, ErrorCode.STORAGE_EXHAUSTED) }
        if (isCurrent) {
            dispatch(
                CaptureEvent.FINALIZE_COMPLETE,
                if (failure != null) Context(errorCode = ErrorCode.STORAGE_EXHAUSTED, cause = failure.cause) else Context(),
            )
        } else {
            reject(CaptureEvent.FINALIZE_COMPLETE, Cause.STALE_SESSION)
        }
    }

    // ---- 状态机推进 ----

    /** 找到回调所属的当前会话；已经没有或 generation 更旧就记一条拒绝并返回 null。 */
    private fun currentSession(tag: Tag, nominal: CaptureEvent): Session? {
        val current = session
        if (current == null) {
            reject(nominal, Cause.STALE_SESSION)
            return null
        }
        if (tag.generation != current.generation) {
            reject(nominal, Cause.STALE_GENERATION)
            return null
        }
        return current
    }

    private fun reject(event: CaptureEvent, cause: Cause, code: ErrorCode? = null) {
        emit(CaptureDecision(event, Outcome.REJECTED, state, state, cause, code))
    }

    private fun emit(decision: CaptureDecision) {
        decisionFlow.tryEmit(decision)
    }

    private fun dispatch(event: CaptureEvent, context: Context = Context()) {
        val rule = CaptureMachine.resolve(state, event)
        when {
            rule == null -> reject(event, context.cause, context.errorCode)
            rule.outcome == Outcome.IGNORED -> emit(CaptureDecision(event, Outcome.IGNORED, state, state, context.cause, context.errorCode))
            else -> transition(rule, context)
        }
    }

    private fun transition(rule: MachineRule, context: Context) {
        val before = state
        val errorCode = context.errorCode
        var eligibilityDenied: ErrorCode? = null
        var eligibilityEvent: CaptureEvent? = null
        when (rule.event) {
            CaptureEvent.ENABLE_CONTROL_MODE -> {
                if (!context.manual && !control.enableUserControlMode()) {
                    reject(rule.event, Cause.CONTROL_NOT_AVAILABLE, ErrorCode.CONTROL_NOT_AVAILABLE)
                    return
                }
                controlModeEnabled = !context.manual
            }

            CaptureEvent.START, CaptureEvent.TOGGLE -> when (rule.from) {
                ARMED, BLOCKED -> {
                    val eligibility = capture.inspectEligibility()
                    beginSession(eligibility.canStart == CanStart.YES)
                    if (eligibility.canStart != CanStart.YES) {
                        // 资格没通过：开始就受阻。机器里只有 ROUTING → BLOCKED 这两条边可走，原因写在错误码里
                        if (eligibility.permission != PermissionState.GRANTED) {
                            eligibilityEvent = CaptureEvent.PERMISSION_REVOKED
                            eligibilityDenied = eligibility.reason ?: ErrorCode.PERMISSION_MIC_DENIED
                        } else {
                            eligibilityEvent = CaptureEvent.ROUTE_UNVERIFIED
                            eligibilityDenied = eligibility.reason ?: ErrorCode.BACKGROUND_START_DENIED
                        }
                    }
                }

                ROUTING -> abandonSession()
                RECORDING -> stopRecording(rule.endReason ?: context.endReason ?: EndReason.HEADSET_COMMAND)
                else -> error("转移表不会给 ${rule.from} 配 ${rule.event}")
            }

            CaptureEvent.STOP -> when (rule.from) {
                ROUTING -> abandonSession()
                RECORDING -> stopRecording(rule.endReason ?: context.endReason ?: EndReason.HEADSET_COMMAND)
                else -> error("转移表不会给 ${rule.from} 配 STOP")
            }

            CaptureEvent.USER_CANCEL -> abandonSession()
            CaptureEvent.ROUTE_VERIFIED -> {
                val evidence = context.evidence ?: error("ROUTE_VERIFIED 必须带路由证据")
                if (!openGate(evidence)) {
                    // 存储打不开：不能录，按“无法确认输入”处理，受阻并说明是存储的原因
                    dispatch(CaptureEvent.ROUTE_UNVERIFIED, Context(errorCode = ErrorCode.STORAGE_EXHAUSTED, cause = Cause.STORAGE_FAILURE))
                    return
                }
            }

            CaptureEvent.ROUTE_UNVERIFIED, CaptureEvent.ROUTE_TIMEOUT -> abandonSession()
            CaptureEvent.PERMISSION_REVOKED -> when (rule.from) {
                ROUTING -> abandonSession()
                RECORDING -> stopRecording(EndReason.PERMISSION_REVOKED)
                else -> Unit
            }

            CaptureEvent.DISABLE_CONTROL_MODE -> {
                if (rule.from == ROUTING) abandonSession()
                control.disableUserControlMode()
                controlModeEnabled = false
            }

            CaptureEvent.DURATION_LIMIT, CaptureEvent.ROUTE_LOST, CaptureEvent.SYSTEM_INTERRUPTION ->
                stopRecording(rule.endReason ?: error("${rule.event} 的转移必须带 end_reason"))

            CaptureEvent.FINALIZE_COMPLETE -> {
                session = null
                lastFinalizedNs = clock.monotonicNs()
            }

            CaptureEvent.PROCESS_RESTORED -> {
                // 一律回到 DISARMED，不自动开麦。进程内能做的清理：取消准备、录音中的残段照常封存（end_reason=process_terminated），
                // 封存中的那次让它自己做完。封存结果晚到时会话已被重置，onSealed 照样把录音交出去。
                when (rule.from) {
                    ROUTING -> abandonSession()
                    RECORDING -> {
                        stopRecording(rule.endReason ?: EndReason.PROCESS_TERMINATED)
                        session = null
                    }

                    FINALIZING -> session = null
                    else -> Unit
                }
                control.disableUserControlMode()
                controlModeEnabled = false
            }
        }
        state = rule.to
        blockedReason = if (rule.to == BLOCKED) errorCode ?: ErrorCode.INPUT_ROUTE_UNVERIFIED else null
        emit(CaptureDecision(rule.event, Outcome.TRANSITION, before, rule.to, context.cause, errorCode))
        if (!controlModeEnabled && state == ARMED && rule.event != CaptureEvent.ENABLE_CONTROL_MODE) {
            dispatch(CaptureEvent.DISABLE_CONTROL_MODE)
        }
        if (eligibilityEvent != null) {
            dispatch(eligibilityEvent, Context(errorCode = eligibilityDenied, cause = Cause.NOT_ELIGIBLE))
        }
    }

    // ---- 副作用 ----

    /** 创建新的 Capture Session：分配标识与 generation，起路由确认超时，资格通过时请求蓝牙输入。 */
    private fun beginSession(eligible: Boolean) {
        durationLimitMs = null
        generation += 1
        val created = Session(
            id = ids.newUuid(),
            generation = generation,
            operationId = ids.newUuid(),
            quarantine = Quarantine(config.quarantineMaxMs * config.format.sampleRateHz * config.format.channels / 1000),
        )
        session = created
        val tag = Tag(created.id, created.generation)
        created.routeTimer = scope.launch {
            delay(config.routeTimeoutMs)
            post(Message.Timeout(tag, TimerKind.ROUTE))
        }
        if (!eligible) return
        val request = InputRequest(created.id, created.operationId, created.generation, config.format)
        val sink = TaggedSink(tag)
        created.prepareJob = scope.launch(adapterDispatcher) {
            val prepared = try {
                capture.prepareBluetoothInput(request, sink)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            post(Message.Prepared(tag, prepared))
        }
    }

    /** 放弃准备：取消定时器与准备任务，释放采集，丢弃 Quarantine。此时从没有过写入器，磁盘上没有任何东西。 */
    private fun abandonSession() {
        val current = session ?: return
        current.cancelJobs()
        releaseCapture(current.operationId)
        check(current.writer == null) { "路由确认之前不可能有写入器" }
        discardQuarantine(current)
        session = null
    }

    private fun discardQuarantine(current: Session) {
        val discarded = current.quarantine.overflowed + current.quarantine.discard()
        counters = counters.copy(discardedQuarantineSamples = counters.discardedQuarantineSamples + discarded)
    }

    /** 路由已确认：丢弃 Quarantine，创建 Record 的音频文件，开闸，起时长上限定时器。失败返回 false。 */
    private fun openGate(evidence: RouteChangeEvent): Boolean {
        val current = session ?: error("ROUTING 必有会话")
        val recordId = ids.newUuid()
        val handle = CaptureHandle(current.id, current.generation, evidence.epoch, GateState.OPEN)
        val writer = try {
            store.begin(recordId, handle, config.format)
        } catch (e: IOException) {
            return false
        }
        current.routeTimer?.cancel()
        discardQuarantine(current)
        current.recordId = recordId
        current.handle = handle
        current.writer = writer
        current.evidence = evidence
        current.evidenceId = ids.newUuid()
        current.startedAt = clock.now()
        val tag = Tag(current.id, current.generation)
        current.durationTimer = scope.launch {
            delay(config.maxDurationMs)
            post(Message.Timeout(tag, TimerKind.DURATION))
        }
        return true
    }

    /** 关闸、立刻释放麦克风，然后在后台封存；封存结果以消息回到邮箱。 */
    private fun stopRecording(endReason: EndReason) {
        durationLimitMs = if (endReason == EndReason.DURATION_LIMIT) config.maxDurationMs else null
        val current = session ?: error("RECORDING 必有会话")
        current.cancelJobs()
        current.handle = (current.handle ?: error("RECORDING 必有句柄")).closed()
        releaseCapture(current.operationId)

        val writer = current.writer ?: error("RECORDING 必有写入器")
        val recordId = current.recordId ?: error("RECORDING 必有 Record 标识")
        val evidence = current.evidence ?: error("RECORDING 必有路由证据")
        val evidenceId = current.evidenceId ?: error("RECORDING 必有证据标识")
        val startedAt = current.startedAt ?: error("RECORDING 必有开始时间")
        val chunkId = ids.newUuid()
        val written = current.writtenSamples
        val sessionId = current.id
        val epoch = evidence.epoch
        val tag = Tag(current.id, current.generation)

        scope.launch(adapterDispatcher) {
            val outcome = try {
                if (written == 0L) {
                    // 确认了路由却没收到任何采样：没有音频可留，不留空文件
                    writer.abort()
                    SealOutcome.Done(
                        SealedRecording(resultFor(recordId, sessionId, endReason, 0, null), evidence, evidenceId, startedAt, null),
                    )
                } else {
                    val audio = writer.seal()
                    SealOutcome.Done(
                        SealedRecording(resultFor(recordId, sessionId, endReason, audio.durationMs, ChunkSpec(chunkId, audio.sha256, epoch)), evidence, evidenceId, startedAt, audio),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 封存失败（含适配器没预料到的异常）：让存储清理残留，状态机照常走完，不能卡在 FINALIZING
                try {
                    writer.abort()
                } catch (cleanup: Exception) {
                    // 清理也失败了：能做的都做了，仍然通知录音丢失
                }
                SealOutcome.Failed(recordId, if (e is IOException) Cause.STORAGE_FAILURE else Cause.INTERNAL_ERROR)
            }
            post(Message.Sealed(tag, outcome))
        }
    }

    private fun releaseCapture(operationId: String) {
        try {
            capture.release(operationId)
        } catch (e: Exception) {
            emit(CaptureDecision(null, Outcome.REJECTED, state, state, Cause.INTERNAL_ERROR))
        }
    }

    private class ChunkSpec(val chunkId: String, val sha256: String, val routeEpoch: Long)

    /**
     * 组装合同里的 RecordingResult。时间线从路由确认那一刻开始：保留的音频是 [0, durationMs]；
     * 确认之前的帧不在时间线上（它们根本没有被保留）。capture_duration_ms 取已保留音频的时长（按采样数算）。
     * 没有 VAD（Ticket 18），所以保留下来的音频都算作“已确认的语音时间段”。
     */
    private fun resultFor(recordId: String, sessionId: String, endReason: EndReason, durationMs: Long, chunk: ChunkSpec?): RecordingResult {
        val result = RecordingResult(
            recordId = recordId,
            captureSessionId = sessionId,
            status = if (endReason.truncated) CaptureStatus.PARTIAL else CaptureStatus.COMPLETE,
            endReason = endReason,
            captureDurationMs = durationMs,
            speechDurationMs = durationMs,
            chunks = if (chunk == null) emptyList() else listOf(AudioChunk(chunk.chunkId, 0, chunk.sha256, chunk.routeEpoch, 0, durationMs)),
            verifiedRanges = if (durationMs > 0) listOf(TimeRange(0, durationMs)) else emptyList(),
            discardedRanges = emptyList(),
        )
        check(ContractRules.violations(result).isEmpty()) { "协调器组装出了违反合同语义规则的结果" }
        return result
    }

    // ---- 快照 ----

    private fun publish() {
        snapshotFlow.value = buildSnapshot()
    }

    private fun buildSnapshot(): CaptureSnapshot {
        val current = session
        publishVersion += 1
        return CaptureSnapshot(
            state = state,
            blockedReason = blockedReason,
            session = current?.let {
                SessionInfo(
                    captureSessionId = it.id,
                    generation = it.generation,
                    verifiedRouteEpoch = it.handle?.verifiedRouteEpoch,
                    gate = it.handle?.gateState ?: GateState.CLOSED,
                )
            },
            actualInput = current?.observed,
            counters = counters.copy(quarantinedSamples = current?.quarantine?.size ?: 0),
            version = publishVersion,
            durationLimitMs = durationLimitMs,
            controlModeEnabled = controlModeEnabled,
        )
    }
}
