package app.auri.probe.core

/**
 * Android AudioDeviceInfo.TYPE_* integer constants decoupled from android.media.AudioDeviceInfo
 * for off-device JVM testing.
 */
object AndroidAudioDeviceTypes {
    const val TYPE_WIRED_HEADSET = 3
    const val TYPE_BLUETOOTH_SCO = 7
    const val TYPE_BLUETOOTH_A2DP = 8
    const val TYPE_USB_DEVICE = 11
    const val TYPE_BUILTIN_MIC = 15
    const val TYPE_USB_HEADSET = 22
    const val TYPE_BLE_HEADSET = 26
}

enum class FrameDisposition {
    ACCEPTED,
    DISCARDED_GATE_CLOSED,
    DISCARDED_STALE_GENERATION,
    DISCARDED_STALE_EPOCH,
}

data class GateSessionToken(
    val generation: Long,
    val routeEpoch: Long,
)

data class RouteEvaluation(
    val routeType: ObservedInputRouteType,
    val verificationState: VerificationState,
    val stateAfter: CaptureState,
    val gateOpen: Boolean,
    val routeEpoch: Long,
    val ignoredStaleGeneration: Boolean,
)

/**
 * Enforces strict bluetooth_only input routing with an in-memory quarantine gate (PRD FR-REC-001/002).
 * Never accepts built-in mic or falls back when Bluetooth route evidence is missing or lost.
 */
class RouteEvidenceGate {
    var state: CaptureState = CaptureState.ARMED
        private set
    var gateOpen: Boolean = false
        private set
    var generation: Long = 0L
        private set
    var routeEpoch: Long = 0L
        private set
    var acceptedFrames: Long = 0L
        private set
    var quarantinedDiscardedFrames: Long = 0L
        private set

    private var expectedDeviceId: Int? = null

    fun beginSession(expectedDeviceId: Int?): GateSessionToken {
        generation += 1
        routeEpoch += 1
        this.expectedDeviceId = expectedDeviceId
        state = CaptureState.ROUTING
        gateOpen = false
        acceptedFrames = 0L
        quarantinedDiscardedFrames = 0L
        return GateSessionToken(generation = generation, routeEpoch = routeEpoch)
    }

    fun updateExpectedDeviceId(sessionGeneration: Long, expectedDeviceId: Int?) {
        if (sessionGeneration != generation) return
        this.expectedDeviceId = expectedDeviceId
    }

    fun cancelSession(sessionGeneration: Long) {
        if (sessionGeneration != generation) return
        gateOpen = false
        state = CaptureState.ARMED
    }

    fun finishSession(sessionGeneration: Long) {
        if (sessionGeneration != generation) return
        gateOpen = false
        state = CaptureState.ARMED
    }

    fun onRoutingTimeout(sessionGeneration: Long) {
        if (sessionGeneration != generation) return
        if (state == CaptureState.ROUTING) {
            gateOpen = false
            state = CaptureState.BLOCKED
        }
    }

    fun onRouteObserved(
        generation: Long,
        deviceType: Int?,
        deviceId: Int?,
    ): RouteEvaluation {
        val routeType = mapAndroidDeviceType(deviceType)
        if (generation != this.generation) {
            return RouteEvaluation(
                routeType = routeType,
                verificationState = VerificationState.UNKNOWN,
                stateAfter = state,
                gateOpen = gateOpen,
                routeEpoch = routeEpoch,
                ignoredStaleGeneration = true,
            )
        }

        val verification = verifyRoute(routeType, deviceId)
        when (state) {
            CaptureState.ROUTING -> {
                if (verification == VerificationState.VERIFIED) {
                    routeEpoch += 1
                    gateOpen = true
                    state = CaptureState.RECORDING
                } else {
                    // Keep gate closed during ROUTING so initial unverified frames remain quarantined
                    // while Bluetooth SCO/LE negotiation completes or times out.
                    gateOpen = false
                }
            }
            CaptureState.RECORDING -> {
                if (verification == VerificationState.VERIFIED) {
                    routeEpoch += 1
                } else {
                    gateOpen = false
                    routeEpoch += 1
                    state = CaptureState.FINALIZING
                }
            }
            else -> {
                gateOpen = false
            }
        }

        return RouteEvaluation(
            routeType = routeType,
            verificationState = verification,
            stateAfter = state,
            gateOpen = gateOpen,
            routeEpoch = routeEpoch,
            ignoredStaleGeneration = false,
        )
    }

    fun recordFrames(
        generation: Long,
        routeEpoch: Long,
        frameCount: Long,
    ): FrameDisposition {
        if (frameCount <= 0L) return FrameDisposition.ACCEPTED
        if (generation != this.generation) {
            quarantinedDiscardedFrames += frameCount
            return FrameDisposition.DISCARDED_STALE_GENERATION
        }
        if (!gateOpen || state != CaptureState.RECORDING) {
            quarantinedDiscardedFrames += frameCount
            return FrameDisposition.DISCARDED_GATE_CLOSED
        }
        if (routeEpoch != this.routeEpoch) {
            quarantinedDiscardedFrames += frameCount
            return FrameDisposition.DISCARDED_STALE_EPOCH
        }
        acceptedFrames += frameCount
        return FrameDisposition.ACCEPTED
    }

    private fun verifyRoute(routeType: ObservedInputRouteType, deviceId: Int?): VerificationState {
        if (!routeType.isVerifiedBluetoothInput()) {
            return if (routeType == ObservedInputRouteType.UNKNOWN) {
                VerificationState.UNKNOWN
            } else {
                VerificationState.MISMATCH
            }
        }
        val expected = expectedDeviceId
        if (expected != null && deviceId != expected) {
            return VerificationState.MISMATCH
        }
        return VerificationState.VERIFIED
    }

    companion object {
        fun mapAndroidDeviceType(deviceType: Int?): ObservedInputRouteType = when (deviceType) {
            null -> ObservedInputRouteType.UNKNOWN
            AndroidAudioDeviceTypes.TYPE_BLUETOOTH_SCO -> ObservedInputRouteType.BLUETOOTH_HFP
            AndroidAudioDeviceTypes.TYPE_BLE_HEADSET -> ObservedInputRouteType.BLUETOOTH_LE_AUDIO
            AndroidAudioDeviceTypes.TYPE_BUILTIN_MIC -> ObservedInputRouteType.BUILT_IN_MIC
            AndroidAudioDeviceTypes.TYPE_WIRED_HEADSET -> ObservedInputRouteType.WIRED_HEADSET
            AndroidAudioDeviceTypes.TYPE_USB_DEVICE,
            AndroidAudioDeviceTypes.TYPE_USB_HEADSET -> ObservedInputRouteType.USB_AUDIO
            else -> ObservedInputRouteType.OTHER
        }
    }
}
