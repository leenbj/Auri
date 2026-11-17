package app.auri.probe

import android.Manifest
import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import app.auri.probe.core.BusinessCommand
import app.auri.probe.core.CaptureEligibilityEvaluator
import app.auri.probe.core.CaptureEligibilityResult
import app.auri.probe.core.CaptureState
import app.auri.probe.core.ForegroundServiceState
import app.auri.probe.core.FrameDisposition
import app.auri.probe.core.NormalizedControlDecision
import app.auri.probe.core.PermissionState
import app.auri.probe.core.RouteEvaluation
import app.auri.probe.core.RouteEvidenceGate
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Android capture, two-layer permission/FGS, and strict bluetooth_only route probe.
 * Never falls back to built-in mic; never plays silent audio; never fakes calls.
 */
class CaptureProbe(
    private val context: Context,
    private val lifecycleProbe: LifecycleProbe,
) {
    enum class AudioSourceChoice(val wireValue: String, val androidSource: Int) {
        VOICE_COMMUNICATION("VOICE_COMMUNICATION", MediaRecorder.AudioSource.VOICE_COMMUNICATION),
        MIC("MIC", MediaRecorder.AudioSource.MIC),
    }

    enum class RoutingModeChoice(val wireValue: String) {
        SET_COMMUNICATION_DEVICE("setCommunicationDevice"),
        PREFERRED_DEVICE_ONLY("preferredDeviceOnly"),
    }

    var audioSourceChoice: AudioSourceChoice = AudioSourceChoice.VOICE_COMMUNICATION
    var routingModeChoice: RoutingModeChoice = RoutingModeChoice.SET_COMMUNICATION_DEVICE
    var onStateChange: ((CaptureState, String) -> Unit)? = null
    var onEligibilityChange: ((CaptureEligibilityResult) -> Unit)? = null

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gate = RouteEvidenceGate()
    private val gateLock = Any()
    private val lock = Any()

    private var captureState: CaptureState = CaptureState.DISARMED
    private var fgsState: ForegroundServiceState = ForegroundServiceState.NOT_RUNNING
    private var currentSessionId: String = ""
    private var activeGeneration: Long = 0L
    private var pendingFgsSessionId: String? = null
    private var pendingFgsGeneration: Long = -1L

    private var audioRecord: AudioRecord? = null
    private var pcmOut: FileOutputStream? = null
    private var readerThread: Thread? = null
    private var focusRequest: AudioFocusRequest? = null
    private var routingListener: AudioRouting.OnRoutingChangedListener? = null
    private var routingTimeoutRunnable: Runnable? = null
    private var firstAcceptedMonoNs: Long = 0L
    private var lastAcceptedMonoNs: Long = 0L
    private var writeFailures: Int = 0

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            ProbeLogger.log(
                "audio_devices_added",
                mapOf("devices" to addedDevices.map(::deviceInfoMap)),
            )
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            ProbeLogger.log(
                "audio_devices_removed",
                mapOf("devices" to removedDevices.map(::deviceInfoMap)),
            )
        }
    }

    private val commDeviceListener = AudioManager.OnCommunicationDeviceChangedListener { device ->
        ProbeLogger.log(
            "communication_device_changed",
            mapOf(
                "state" to state().name,
                "device" to device?.let(::deviceInfoMap),
            ),
        )
    }

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        ProbeLogger.log(
            "audio_focus_changed",
            mapOf("focus_change" to focusChange, "state" to state().name),
        )
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        ) {
            if (state() == CaptureState.RECORDING || state() == CaptureState.ROUTING) {
                stop(reason = "system_interruption_focus_$focusChange")
            }
        }
    }

    init {
        audioManager?.registerAudioDeviceCallback(deviceCallback, mainHandler)
        audioManager?.addOnCommunicationDeviceChangedListener(appContext.mainExecutor, commDeviceListener)
    }

    fun state(): CaptureState = synchronized(lock) { captureState }

    fun enableControlMode() {
        val prev = state()
        if (prev == CaptureState.DISARMED) {
            setCaptureState(CaptureState.ARMED)
            ProbeLogger.log("control_mode_enabled", mapOf("state_before" to prev.name))
        }
    }

    fun disableControlMode(): Boolean {
        val prev = state()
        return when (prev) {
            CaptureState.RECORDING, CaptureState.FINALIZING -> {
                ProbeLogger.log("control_mode_disable_rejected", mapOf("state" to prev.name))
                false
            }
            CaptureState.ROUTING -> {
                cancelRouting("disable_control_mode")
                setCaptureState(CaptureState.DISARMED)
                ProbeLogger.log("control_mode_disabled", mapOf("state_before" to prev.name))
                true
            }
            CaptureState.ARMED, CaptureState.BLOCKED, CaptureState.DISARMED -> {
                setCaptureState(CaptureState.DISARMED)
                ProbeLogger.log("control_mode_disabled", mapOf("state_before" to prev.name))
                true
            }
        }
    }

    fun evaluateTwoLayers(logEvent: Boolean = true): CaptureEligibilityResult {
        val micPerm = permissionStateOf(Manifest.permission.RECORD_AUDIO)
        val btPerm = permissionStateOf(Manifest.permission.BLUETOOTH_CONNECT)
        val notifPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionStateOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            PermissionState.GRANTED
        }
        val lifecycle = lifecycleProbe.currentState()
        val fgs = synchronized(lock) { fgsState }
        val result = CaptureEligibilityEvaluator.evaluate(
            micPermission = micPerm,
            bluetoothPermission = btPerm,
            lifecycle = lifecycle,
            fgsState = fgs,
        )
        if (logEvent) {
            ProbeLogger.log(
                "two_layer_eligibility_check",
                mapOf(
                    "layer1_mic_permission" to micPerm.wireValue,
                    "layer1_bt_connect_permission" to btPerm.wireValue,
                    "layer1_post_notifications" to notifPerm.wireValue,
                    "layer2_lifecycle" to lifecycle.wireValue,
                    "layer2_fgs_state" to fgs.wireValue,
                    "can_start" to result.canStart.wireValue,
                    "reason_code" to result.reasonCode,
                    "layer_note" to result.layerNote,
                ),
            )
        }
        onEligibilityChange?.invoke(result)
        return result
    }

    fun probeFgsOnly() {
        val sessionId = "fgs-probe-${UUID.randomUUID()}"
        ProbeLogger.log(
            "fgs_probe_only_requested",
            mapOf("session_id" to sessionId, "lifecycle" to lifecycleProbe.currentState().wireValue),
        )
        evaluateTwoLayers(logEvent = true)
        tryStartMicrophoneFgs(sessionId = sessionId, generation = -1L, probeOnly = true)
    }

    fun applyNormalizedCommand(decision: NormalizedControlDecision) {
        if (decision.command == BusinessCommand.IGNORE) return
        val current = state()
        when (decision.command) {
            BusinessCommand.START -> when (current) {
                CaptureState.DISARMED -> ProbeLogger.log("capture_ignored_disarmed", mapOf("source" to decision.source.wireValue))
                CaptureState.ARMED, CaptureState.BLOCKED -> start(trigger = decision.rawPlatformLabel)
                CaptureState.ROUTING, CaptureState.RECORDING -> ProbeLogger.log("capture_start_noop", mapOf("state" to current.name))
                CaptureState.FINALIZING -> ProbeLogger.log("command_ignored_busy", mapOf("command" to "START"))
            }
            BusinessCommand.STOP -> when (current) {
                CaptureState.DISARMED -> ProbeLogger.log("capture_ignored_disarmed", mapOf("source" to decision.source.wireValue))
                CaptureState.ROUTING -> cancelRouting(reason = "command_${decision.rawPlatformLabel}")
                CaptureState.RECORDING -> stop(reason = "command_${decision.rawPlatformLabel}")
                CaptureState.ARMED, CaptureState.BLOCKED, CaptureState.FINALIZING ->
                    ProbeLogger.log("capture_stop_noop", mapOf("state" to current.name))
            }
            BusinessCommand.TOGGLE -> when (current) {
                CaptureState.DISARMED -> ProbeLogger.log("capture_ignored_disarmed", mapOf("source" to decision.source.wireValue))
                CaptureState.ARMED, CaptureState.BLOCKED -> start(trigger = decision.rawPlatformLabel)
                CaptureState.ROUTING -> cancelRouting(reason = "toggle_${decision.rawPlatformLabel}")
                CaptureState.RECORDING -> stop(reason = "toggle_${decision.rawPlatformLabel}")
                CaptureState.FINALIZING -> ProbeLogger.log("toggle_discarded_stale", mapOf("raw" to decision.rawPlatformLabel))
            }
            BusinessCommand.IGNORE -> Unit
        }
    }

    fun start(trigger: String) {
        val current = state()
        if (current == CaptureState.DISARMED) {
            setCaptureState(CaptureState.ARMED)
        } else if (current != CaptureState.ARMED && current != CaptureState.BLOCKED) {
            ProbeLogger.log("capture_start_ignored", mapOf("state" to current.name, "trigger" to trigger))
            return
        }

        val micPerm = permissionStateOf(Manifest.permission.RECORD_AUDIO)
        val btPerm = permissionStateOf(Manifest.permission.BLUETOOTH_CONNECT)
        val sessionId = UUID.randomUUID().toString()
        val token = synchronized(gateLock) { gate.beginSession(expectedDeviceId = null) }

        synchronized(lock) {
            currentSessionId = sessionId
            activeGeneration = token.generation
            pendingFgsSessionId = sessionId
            pendingFgsGeneration = token.generation
            firstAcceptedMonoNs = 0L
            lastAcceptedMonoNs = 0L
            writeFailures = 0
        }
        setCaptureState(CaptureState.ROUTING)

        ProbeLogger.log(
            "capture_start_requested",
            mapOf(
                "trigger" to trigger,
                "session_id" to sessionId,
                "generation" to token.generation,
                "audio_source" to audioSourceChoice.wireValue,
                "routing_mode" to routingModeChoice.wireValue,
                "lifecycle" to lifecycleProbe.currentState().wireValue,
                "layer1_mic_permission" to micPerm.wireValue,
                "layer1_bt_connect_permission" to btPerm.wireValue,
                "available_inputs" to snapshotInputs(),
                "available_comm_devices" to snapshotCommDevices(),
            ),
        )

        if (micPerm != PermissionState.GRANTED) {
            blockSession(token.generation, "PERMISSION_MIC_DENIED", "layer1_record_audio_denied")
            return
        }
        if (btPerm != PermissionState.GRANTED) {
            blockSession(token.generation, "INPUT_DEVICE_MISSING", "layer1_bluetooth_connect_denied")
            return
        }

        val currentFgs = synchronized(lock) { fgsState }
        if (currentFgs == ForegroundServiceState.RUNNING_MICROPHONE) {
            continueAfterFgsReady(sessionId, token.generation)
        } else {
            tryStartMicrophoneFgs(sessionId, token.generation, probeOnly = false)
        }
    }

    fun onFgsStarted(
        state: ForegroundServiceState,
        sessionId: String,
        generation: Long,
        probeOnly: Boolean,
    ) {
        synchronized(lock) { fgsState = state }
        evaluateTwoLayers(logEvent = true)
        if (probeOnly) {
            mainHandler.postDelayed({ stopMicrophoneFgs() }, 600L)
            return
        }
        val matchesPending = synchronized(lock) {
            pendingFgsSessionId == sessionId && pendingFgsGeneration == generation
        }
        if (!matchesPending || this.state() != CaptureState.ROUTING) {
            ProbeLogger.log(
                "fgs_started_stale_ignored",
                mapOf("session_id" to sessionId, "generation" to generation, "state" to this.state().name),
            )
            return
        }
        if (state != ForegroundServiceState.RUNNING_MICROPHONE) {
            blockSession(generation, "BACKGROUND_START_DENIED", "fgs_wrong_type_${state.wireValue}")
            return
        }
        continueAfterFgsReady(sessionId, generation)
    }

    fun onFgsFailed(
        state: ForegroundServiceState,
        sessionId: String,
        generation: Long,
        errorMessage: String,
    ) {
        synchronized(lock) { fgsState = state }
        evaluateTwoLayers(logEvent = true)
        if (generation >= 0L && this.state() == CaptureState.ROUTING) {
            blockSession(generation, "BACKGROUND_START_DENIED", "fgs_failed:$errorMessage")
        }
    }

    fun onFgsStopped() {
        synchronized(lock) { fgsState = ForegroundServiceState.NOT_RUNNING }
        evaluateTwoLayers(logEvent = false)
    }

    private fun tryStartMicrophoneFgs(sessionId: String, generation: Long, probeOnly: Boolean) {
        val intent = Intent(appContext, MicCaptureForegroundService::class.java).apply {
            action = if (probeOnly) {
                MicCaptureForegroundService.ACTION_PROBE_ONLY
            } else {
                MicCaptureForegroundService.ACTION_START_FOR_CAPTURE
            }
            putExtra(MicCaptureForegroundService.EXTRA_SESSION_ID, sessionId)
            putExtra(MicCaptureForegroundService.EXTRA_GENERATION, generation)
        }

        try {
            ContextCompat.startForegroundService(appContext, intent)
            ProbeLogger.log(
                "fgs_start_service_dispatched",
                mapOf("session_id" to sessionId, "generation" to generation, "probe_only" to probeOnly),
            )
        } catch (e: ForegroundServiceStartNotAllowedException) {
            synchronized(lock) { fgsState = ForegroundServiceState.START_FAILED_NOT_ALLOWED }
            ProbeLogger.log(
                "fgs_start_service_exception",
                mapOf(
                    "session_id" to sessionId,
                    "exception_class" to e.javaClass.simpleName,
                    "error" to (e.message ?: "ForegroundServiceStartNotAllowedException"),
                ),
            )
            evaluateTwoLayers(logEvent = true)
            if (!probeOnly) {
                blockSession(generation, "BACKGROUND_START_DENIED", e.message ?: "ForegroundServiceStartNotAllowedException")
            }
        } catch (se: SecurityException) {
            synchronized(lock) { fgsState = ForegroundServiceState.START_FAILED_SECURITY }
            ProbeLogger.log(
                "fgs_start_service_exception",
                mapOf(
                    "session_id" to sessionId,
                    "exception_class" to se.javaClass.simpleName,
                    "error" to (se.message ?: "SecurityException"),
                ),
            )
            evaluateTwoLayers(logEvent = true)
            if (!probeOnly) {
                blockSession(generation, "BACKGROUND_START_DENIED", se.message ?: "SecurityException")
            }
        } catch (e: Exception) {
            synchronized(lock) { fgsState = ForegroundServiceState.START_FAILED_OTHER }
            ProbeLogger.log(
                "fgs_start_service_exception",
                mapOf(
                    "session_id" to sessionId,
                    "exception_class" to e.javaClass.simpleName,
                    "error" to (e.message ?: "Exception"),
                ),
            )
            evaluateTwoLayers(logEvent = true)
            if (!probeOnly) {
                blockSession(generation, "BACKGROUND_START_DENIED", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun continueAfterFgsReady(sessionId: String, generation: Long) {
        val manager = audioManager
        if (manager == null) {
            blockSession(generation, "INPUT_DEVICE_MISSING", "audio_manager_unavailable")
            return
        }

        val btInput = findBluetoothInputDevice(manager)
        val btComm = findBluetoothCommDevice(manager)
        if (btInput == null && btComm == null) {
            blockSession(generation, "INPUT_DEVICE_MISSING", "no_bluetooth_input_or_comm_device")
            return
        }

        requestTransientAudioFocus(manager)

        if (routingModeChoice == RoutingModeChoice.SET_COMMUNICATION_DEVICE && btComm != null) {
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            val setOk = manager.setCommunicationDevice(btComm)
            ProbeLogger.log(
                "set_communication_device_result",
                mapOf(
                    "session_id" to sessionId,
                    "success" to setOk,
                    "target_device" to deviceInfoMap(btComm),
                    "current_comm_device" to manager.communicationDevice?.let(::deviceInfoMap),
                ),
            )
            if (!setOk) {
                blockSession(generation, "INPUT_ROUTE_UNVERIFIED", "set_communication_device_false")
                return
            }
        }

        val resolvedBtInput = findBluetoothInputDevice(manager) ?: btInput
        synchronized(gateLock) {
            gate.updateExpectedDeviceId(generation, resolvedBtInput?.id)
        }
        val preferredTarget = resolvedBtInput ?: btComm!!

        val sampleRate = 16_000
        val channelMask = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBytes = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minBytes <= 0) {
            blockSession(generation, "INPUT_ROUTE_UNVERIFIED", "invalid_min_buffer_$minBytes")
            return
        }
        val bufferBytes = (minBytes * 2).coerceAtLeast(4096)

        val record: AudioRecord
        try {
            record = AudioRecord.Builder()
                .setAudioSource(audioSourceChoice.androidSource)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .setEncoding(encoding)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .build()
        } catch (e: Exception) {
            blockSession(generation, "INPUT_ROUTE_UNVERIFIED", "audio_record_build_failed:${e.message}")
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            blockSession(generation, "INPUT_ROUTE_UNVERIFIED", "audio_record_uninitialized")
            return
        }

        val prefOk = record.setPreferredDevice(preferredTarget)
        ProbeLogger.log(
            "audio_record_preferred_device_set",
            mapOf("session_id" to sessionId, "preferred_ok" to prefOk, "target_device" to deviceInfoMap(preferredTarget)),
        )

        val listener = AudioRouting.OnRoutingChangedListener { router ->
            val ar = router as? AudioRecord ?: return@OnRoutingChangedListener
            val routed = ar.routedDevice
            val eval = synchronized(gateLock) {
                gate.onRouteObserved(generation, routed?.type, routed?.id)
            }
            ProbeLogger.log(
                "audio_record_routing_changed",
                mapOf(
                    "session_id" to sessionId,
                    "generation" to generation,
                    "routed_device" to routed?.let(::deviceInfoMap),
                    "route_type" to eval.routeType.wireValue,
                    "verification_state" to eval.verificationState.wireValue,
                    "gate_open" to eval.gateOpen,
                    "route_epoch" to eval.routeEpoch,
                ),
            )
            if (eval.stateAfter == CaptureState.RECORDING && state() == CaptureState.ROUTING) {
                setCaptureState(CaptureState.RECORDING)
                cancelRoutingTimeout()
            } else if (eval.stateAfter == CaptureState.FINALIZING && state() == CaptureState.RECORDING) {
                ProbeLogger.log("gate_closed_route_not_bluetooth", mapOf("session_id" to sessionId))
                mainHandler.post { stop(reason = "route_lost") }
            }
        }
        record.addOnRoutingChangedListener(listener, mainHandler)

        try {
            record.startRecording()
        } catch (se: SecurityException) {
            record.removeOnRoutingChangedListener(listener)
            record.release()
            blockSession(generation, "PERMISSION_MIC_DENIED", "startRecording_security:${se.message}")
            return
        } catch (e: Exception) {
            record.removeOnRoutingChangedListener(listener)
            record.release()
            blockSession(generation, "BACKGROUND_START_DENIED", "startRecording_failed:${e.javaClass.simpleName}:${e.message}")
            return
        }

        val initialRouted = record.routedDevice
        val initialEval = synchronized(gateLock) {
            gate.onRouteObserved(generation, initialRouted?.type, initialRouted?.id)
        }
        ProbeLogger.log(
            "audio_record_started_initial_route",
            mapOf(
                "session_id" to sessionId,
                "generation" to generation,
                "routed_device" to initialRouted?.let(::deviceInfoMap),
                "route_type" to initialEval.routeType.wireValue,
                "verification_state" to initialEval.verificationState.wireValue,
                "gate_open" to initialEval.gateOpen,
                "route_epoch" to initialEval.routeEpoch,
            ),
        )

        synchronized(lock) {
            audioRecord = record
            routingListener = listener
            pcmOut = runCatching {
                FileOutputStream(File(appContext.filesDir, "rec-$sessionId.pcm"))
            }.getOrNull()
        }

        if (initialEval.stateAfter == CaptureState.RECORDING) {
            setCaptureState(CaptureState.RECORDING)
        } else {
            scheduleRoutingTimeout(generation)
        }

        startReadLoop(record, bufferBytes, sessionId, generation)
    }

    private fun startReadLoop(
        record: AudioRecord,
        bufferBytes: Int,
        sessionId: String,
        generation: Long,
    ) {
        val thread = Thread(
            {
                val buffer = ByteArray(bufferBytes)
                while (!Thread.currentThread().isInterrupted &&
                    synchronized(lock) { audioRecord === record && activeGeneration == generation }
                ) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read <= 0) {
                        if (read < 0) {
                            ProbeLogger.log("audio_record_read_error", mapOf("code" to read, "session_id" to sessionId))
                            break
                        }
                        continue
                    }

                    val frames = (read / 2).toLong()
                    val routed = record.routedDevice
                    var lostRouteInLoop = false
                    var verifiedEvalInLoop: RouteEvaluation? = null
                    val disposition = synchronized(gateLock) {
                        if (gate.state == CaptureState.ROUTING && routed != null) {
                            val eval = gate.onRouteObserved(generation, routed.type, routed.id)
                            if (eval.stateAfter == CaptureState.RECORDING) {
                                verifiedEvalInLoop = eval
                            }
                        } else if (gate.state == CaptureState.RECORDING &&
                            !RouteEvidenceGate.mapAndroidDeviceType(routed?.type).isVerifiedBluetoothInput()
                        ) {
                            gate.onRouteObserved(generation, routed?.type, routed?.id)
                            lostRouteInLoop = true
                        }
                        gate.recordFrames(generation, gate.routeEpoch, frames)
                    }

                    verifiedEvalInLoop?.let { eval ->
                        mainHandler.post {
                            if (state() == CaptureState.ROUTING) {
                                setCaptureState(CaptureState.RECORDING)
                                cancelRoutingTimeout()
                            }
                        }
                        ProbeLogger.log(
                            "capture_verified_in_read_loop",
                            mapOf(
                                "session_id" to sessionId,
                                "route_type" to eval.routeType.wireValue,
                                "route_epoch" to eval.routeEpoch,
                            ),
                        )
                    }

                    if (lostRouteInLoop) {
                        ProbeLogger.log(
                            "gate_closed_route_not_bluetooth_in_read_loop",
                            mapOf("session_id" to sessionId, "routed_device" to routed?.let(::deviceInfoMap)),
                        )
                        mainHandler.post { stop(reason = "route_lost") }
                        break
                    }

                    if (disposition == FrameDisposition.ACCEPTED) {
                        val now = ProbeLogger.monoNanos()
                        synchronized(lock) {
                            if (firstAcceptedMonoNs == 0L) firstAcceptedMonoNs = now
                            lastAcceptedMonoNs = now
                            try {
                                pcmOut?.write(buffer, 0, read)
                            } catch (_: Exception) {
                                writeFailures += 1
                            }
                        }
                    }
                }
            },
            "AuriProbeCaptureRead",
        )
        synchronized(lock) { readerThread = thread }
        thread.start()
    }

    private fun cancelRouting(reason: String) {
        val gen = synchronized(lock) { activeGeneration }
        val (discarded, accepted) = synchronized(gateLock) {
            gate.cancelSession(gen)
            gate.quarantinedDiscardedFrames to gate.acceptedFrames
        }
        cleanupAudioResources(deleteSessionFile = true)
        setCaptureState(CaptureState.ARMED)
        ProbeLogger.log(
            "capture_routing_cancelled",
            mapOf(
                "reason" to reason,
                "session_id" to synchronized(lock) { currentSessionId },
                "quarantined_discarded_frames" to discarded,
                "accepted_frames" to accepted,
            ),
        )
    }

    fun stop(reason: String) {
        mainHandler.post { stopOnMain(reason) }
    }

    private fun stopOnMain(reason: String) {
        val before = state()
        if (before != CaptureState.RECORDING && before != CaptureState.ROUTING) {
            ProbeLogger.log("capture_stop_ignored", mapOf("state" to before.name, "reason" to reason))
            return
        }
        setCaptureState(CaptureState.FINALIZING)
        val gen = synchronized(lock) { activeGeneration }
        val sessionId = synchronized(lock) { currentSessionId }
        val (accepted, discarded) = synchronized(gateLock) {
            gate.finishSession(gen)
            gate.acceptedFrames to gate.quarantinedDiscardedFrames
        }

        val deleteFile = (accepted == 0L)
        cleanupAudioResources(deleteSessionFile = deleteFile)

        val (firstNs, lastNs, failures) = synchronized(lock) {
            Triple(firstAcceptedMonoNs, lastAcceptedMonoNs, writeFailures)
        }
        ProbeLogger.log(
            "capture_stopped",
            mapOf(
                "session_id" to sessionId,
                "reason" to reason,
                "accepted_frames" to accepted,
                "quarantined_discarded_frames" to discarded,
                "accepted_duration_ms" to (accepted * 1000L / 16_000L),
                "first_accepted_mono_ns" to firstNs,
                "last_accepted_mono_ns" to lastNs,
                "write_failures" to failures,
                "route_after_inputs" to snapshotInputs(),
            ),
        )
        setCaptureState(CaptureState.ARMED)
    }

    private fun blockSession(generation: Long, errorCode: String, detail: String) {
        val discarded = synchronized(gateLock) {
            gate.onRoutingTimeout(generation)
            gate.quarantinedDiscardedFrames
        }
        cleanupAudioResources(deleteSessionFile = true)
        setCaptureState(CaptureState.BLOCKED)
        ProbeLogger.log(
            "capture_blocked",
            mapOf(
                "session_id" to synchronized(lock) { currentSessionId },
                "generation" to generation,
                "error_code" to errorCode,
                "detail" to detail,
                "quarantined_discarded_frames" to discarded,
                "available_inputs" to snapshotInputs(),
            ),
        )
    }

    private fun scheduleRoutingTimeout(generation: Long) {
        cancelRoutingTimeout()
        val runnable = Runnable {
            if (state() == CaptureState.ROUTING && synchronized(lock) { activeGeneration == generation }) {
                blockSession(generation, "INPUT_ROUTE_UNVERIFIED", "route_verification_timeout_2000ms")
            }
        }
        routingTimeoutRunnable = runnable
        mainHandler.postDelayed(runnable, 2000L)
    }

    private fun cancelRoutingTimeout() {
        routingTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        routingTimeoutRunnable = null
    }

    private fun cleanupAudioResources(deleteSessionFile: Boolean) {
        cancelRoutingTimeout()
        val (record, listener, thread, out, sessionId) = synchronized(lock) {
            val r = audioRecord
            val l = routingListener
            val t = readerThread
            val o = pcmOut
            val s = currentSessionId
            audioRecord = null
            routingListener = null
            readerThread = null
            pcmOut = null
            pendingFgsSessionId = null
            pendingFgsGeneration = -1L
            Quint(r, l, t, o, s)
        }

        thread?.interrupt()
        if (record != null) {
            if (listener != null) {
                runCatching { record.removeOnRoutingChangedListener(listener) }
            }
            runCatching {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            }
            runCatching { record.release() }
        }
        runCatching { out?.close() }
        if (deleteSessionFile && sessionId.isNotEmpty()) {
            runCatching { File(appContext.filesDir, "rec-$sessionId.pcm").delete() }
        }

        audioManager?.let { mgr ->
            focusRequest?.let { req -> runCatching { mgr.abandonAudioFocusRequest(req) } }
            focusRequest = null
            if (routingModeChoice == RoutingModeChoice.SET_COMMUNICATION_DEVICE) {
                runCatching { mgr.clearCommunicationDevice() }
                runCatching { mgr.mode = AudioManager.MODE_NORMAL }
            }
        }
        stopMicrophoneFgs()
    }

    private fun stopMicrophoneFgs() {
        val stopIntent = Intent(appContext, MicCaptureForegroundService::class.java).apply {
            action = MicCaptureForegroundService.ACTION_STOP
        }
        runCatching { appContext.stopService(stopIntent) }
    }

    private fun requestTransientAudioFocus(manager: AudioManager) {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(focusChangeListener, mainHandler)
            .build()
        focusRequest = req
        val granted = manager.requestAudioFocus(req)
        ProbeLogger.log("audio_focus_requested", mapOf("result" to granted))
    }

    private fun findBluetoothInputDevice(manager: AudioManager): AudioDeviceInfo? =
        manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }

    private fun findBluetoothCommDevice(manager: AudioManager): AudioDeviceInfo? =
        manager.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }

    private fun snapshotInputs(): List<Map<String, Any?>> =
        audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS)?.map(::deviceInfoMap) ?: emptyList()

    private fun snapshotCommDevices(): List<Map<String, Any?>> =
        audioManager?.availableCommunicationDevices?.map(::deviceInfoMap) ?: emptyList()

    private fun deviceInfoMap(device: AudioDeviceInfo): Map<String, Any?> {
        val routeType = RouteEvidenceGate.mapAndroidDeviceType(device.type)
        return mapOf(
            "id" to device.id,
            "type" to device.type,
            "route_type" to routeType.wireValue,
            "address_tail" to device.address.takeLast(6),
            "product_name" to device.productName?.toString(),
        )
    }

    private fun permissionStateOf(permission: String): PermissionState {
        val granted = ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
        return if (granted) PermissionState.GRANTED else PermissionState.DENIED
    }

    private fun setCaptureState(next: CaptureState) {
        val sessionId = synchronized(lock) {
            captureState = next
            currentSessionId
        }
        onStateChange?.invoke(next, sessionId)
    }

    private data class Quint<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)
}
