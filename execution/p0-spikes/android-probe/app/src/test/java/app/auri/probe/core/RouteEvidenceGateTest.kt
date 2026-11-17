package app.auri.probe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteEvidenceGateTest {

    @Test
    fun framesWhileRoutingStayInQuarantineAndAreDiscarded() {
        val gate = RouteEvidenceGate()
        val session = gate.beginSession(expectedDeviceId = 42)

        assertEquals(CaptureState.ROUTING, gate.state)
        assertFalse(gate.gateOpen)

        val disposition = gate.recordFrames(
            generation = session.generation,
            routeEpoch = session.routeEpoch,
            frameCount = 512L,
        )
        assertEquals(FrameDisposition.DISCARDED_GATE_CLOSED, disposition)
        assertEquals(0L, gate.acceptedFrames)
        assertEquals(512L, gate.quarantinedDiscardedFrames)
    }

    @Test
    fun builtInMicNeverOpensGateAndTimeoutBlocksSession() {
        val gate = RouteEvidenceGate()
        val session = gate.beginSession(expectedDeviceId = 42)

        val event = gate.onRouteObserved(
            generation = session.generation,
            deviceType = AndroidAudioDeviceTypes.TYPE_BUILTIN_MIC,
            deviceId = 1,
        )
        assertEquals(ObservedInputRouteType.BUILT_IN_MIC, event.routeType)
        assertEquals(VerificationState.MISMATCH, event.verificationState)
        assertFalse(gate.gateOpen)

        val frameCheck = gate.recordFrames(session.generation, gate.routeEpoch, 256L)
        assertEquals(FrameDisposition.DISCARDED_GATE_CLOSED, frameCheck)
        assertEquals(0L, gate.acceptedFrames)

        gate.onRoutingTimeout(session.generation)
        assertEquals(CaptureState.BLOCKED, gate.state)
        assertFalse(gate.gateOpen)
    }

    @Test
    fun initialBuiltInMicDuringNegotiationThenBluetoothScoOpensGateOnlyAfterVerified() {
        val gate = RouteEvidenceGate()
        val session = gate.beginSession(expectedDeviceId = 77)

        gate.onRouteObserved(session.generation, AndroidAudioDeviceTypes.TYPE_BUILTIN_MIC, 1)
        assertEquals(FrameDisposition.DISCARDED_GATE_CLOSED, gate.recordFrames(session.generation, gate.routeEpoch, 256L))

        val verified = gate.onRouteObserved(session.generation, AndroidAudioDeviceTypes.TYPE_BLUETOOTH_SCO, 77)
        assertEquals(VerificationState.VERIFIED, verified.verificationState)
        assertEquals(CaptureState.RECORDING, gate.state)
        assertTrue(gate.gateOpen)

        assertEquals(FrameDisposition.ACCEPTED, gate.recordFrames(session.generation, gate.routeEpoch, 1024L))
        assertEquals(1024L, gate.acceptedFrames)
        assertEquals(256L, gate.quarantinedDiscardedFrames)
        assertEquals(
            ObservedInputRouteType.BLUETOOTH_LE_AUDIO,
            RouteEvidenceGate.mapAndroidDeviceType(AndroidAudioDeviceTypes.TYPE_BLE_HEADSET),
        )
    }

    @Test
    fun routeChangeToBuiltInMicMidRecordingClosesGateImmediatelyAndRejectsLateFrames() {
        val gate = RouteEvidenceGate()
        val session = gate.beginSession(expectedDeviceId = 77)
        gate.onRouteObserved(session.generation, AndroidAudioDeviceTypes.TYPE_BLUETOOTH_SCO, 77)
        val openEpoch = gate.routeEpoch

        assertEquals(FrameDisposition.ACCEPTED, gate.recordFrames(session.generation, openEpoch, 1024L))

        val lostEvent = gate.onRouteObserved(
            generation = session.generation,
            deviceType = AndroidAudioDeviceTypes.TYPE_BUILTIN_MIC,
            deviceId = 1,
        )
        assertEquals(VerificationState.MISMATCH, lostEvent.verificationState)
        assertFalse(gate.gateOpen)
        assertEquals(CaptureState.FINALIZING, gate.state)

        val lateFrame = gate.recordFrames(session.generation, openEpoch, 512L)
        assertEquals(FrameDisposition.DISCARDED_GATE_CLOSED, lateFrame)
        assertEquals(1024L, gate.acceptedFrames)
        assertEquals(512L, gate.quarantinedDiscardedFrames)
    }

    @Test
    fun staleGenerationCallbackCannotOpenGateForNewSession() {
        val gate = RouteEvidenceGate()
        val staleSession = gate.beginSession(expectedDeviceId = 10)
        gate.cancelSession(staleSession.generation)

        val newSession = gate.beginSession(expectedDeviceId = 20)
        val staleResult = gate.onRouteObserved(
            generation = staleSession.generation,
            deviceType = AndroidAudioDeviceTypes.TYPE_BLUETOOTH_SCO,
            deviceId = 10,
        )
        assertTrue(staleResult.ignoredStaleGeneration)
        assertEquals(CaptureState.ROUTING, gate.state)
        assertFalse(gate.gateOpen)
        assertEquals(newSession.generation, gate.generation)
    }

    @Test
    fun bluetoothDeviceMismatchAgainstExpectedDeviceIdDoesNotVerify() {
        val gate = RouteEvidenceGate()
        val session = gate.beginSession(expectedDeviceId = 77)

        val event = gate.onRouteObserved(
            generation = session.generation,
            deviceType = AndroidAudioDeviceTypes.TYPE_BLUETOOTH_SCO,
            deviceId = 88,
        )
        assertEquals(VerificationState.MISMATCH, event.verificationState)
        assertFalse(gate.gateOpen)

        gate.onRoutingTimeout(session.generation)
        assertEquals(CaptureState.BLOCKED, gate.state)
    }
}
