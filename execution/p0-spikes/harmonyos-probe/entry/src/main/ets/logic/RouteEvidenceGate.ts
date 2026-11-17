import type {
  ObservedAudioDevice,
  ObservedInputRouteType,
  RouteVerificationResult,
  VerificationState
} from './ProbeTypes';

export type FrameDecision = 'accepted' | 'quarantined' | 'discarded';

export interface RouteGateTransition {
  gateOpen: boolean;
  epoch: number;
  shouldStop: boolean;
  endReason: 'route_lost' | null;
  verification: RouteVerificationResult;
}

/**
 * HarmonyOS `audio.DeviceType` numeric values verified against docs:
 * - WIRED_HEADSET = 3
 * - WIRED_HEADPHONES = 4
 * - BLUETOOTH_SCO = 7
 * - BLUETOOTH_A2DP = 8
 * - MIC = 15
 * - USB_HEADSET = 22
 * - NEARLINK = 31 (API 20+)
 */
const DEVICE_TYPE_WIRED_HEADSET = 3;
const DEVICE_TYPE_BLUETOOTH_SCO = 7;
const DEVICE_TYPE_BLUETOOTH_A2DP = 8;
const DEVICE_TYPE_MIC = 15;
const DEVICE_TYPE_USB_HEADSET = 22;
const DEVICE_TYPE_BLE_HEADSET = 26;
const DEVICE_TYPE_NEARLINK = 31;

/**
 * Enforces strict `bluetooth_only` input routing (FR-REC-001/002):
 * - Audio frames remain quarantined/discarded until observed input is verified Bluetooth mic.
 * - Generation and route_epoch fencing prevents stale async callbacks from reopening the gate.
 * - Any transition to non-Bluetooth or empty input immediately closes the gate and demands stop.
 */
export class RouteEvidenceGate {
  private sessionId = '';
  private generation = 0;
  private epoch = 0;
  private routingActive = false;
  private gateOpen = false;

  currentGeneration(): number {
    return this.generation;
  }

  currentEpoch(): number {
    return this.epoch;
  }

  isGateOpen(): boolean {
    return this.gateOpen;
  }

  beginRouting(sessionId: string): number {
    this.generation += 1;
    this.sessionId = sessionId;
    this.routingActive = true;
    this.gateOpen = false;
    return this.generation;
  }

  cancelRouting(generation: number): void {
    if (generation !== this.generation) {
      return;
    }
    this.routingActive = false;
    this.gateOpen = false;
  }

  closeGate(): void {
    this.routingActive = false;
    this.gateOpen = false;
  }

  acceptFrame(generation: number, epoch: number): FrameDecision {
    if (generation !== this.generation) {
      return 'discarded';
    }
    if (this.gateOpen && epoch === this.epoch) {
      return 'accepted';
    }
    if (this.routingActive && !this.gateOpen) {
      return 'quarantined';
    }
    return 'discarded';
  }

  evaluateObservedInput(generation: number, devices: ObservedAudioDevice[]): RouteVerificationResult {
    if (generation !== this.generation || !this.routingActive) {
      return {
        capture_session_id: this.sessionId,
        generation,
        epoch: this.epoch,
        observed_route_type: 'unknown',
        observed_display_name: null,
        verification_state: 'unknown',
        gate_open: false,
        rejection_reason: 'STALE_GENERATION',
        contract_gap_note: null
      };
    }

    const classified = this.classifyDevices(devices);
    if (classified.verificationState === 'verified') {
      this.epoch += 1;
      this.gateOpen = true;
      this.routingActive = false;
    } else {
      this.gateOpen = false;
      this.routingActive = false;
    }

    return {
      capture_session_id: this.sessionId,
      generation: this.generation,
      epoch: this.epoch,
      observed_route_type: classified.routeType,
      observed_display_name: classified.displayName,
      verification_state: classified.verificationState,
      gate_open: this.gateOpen,
      rejection_reason: classified.rejectionReason,
      contract_gap_note: classified.contractGapNote
    };
  }

  onRouteChanged(devices: ObservedAudioDevice[]): RouteGateTransition {
    const classified = this.classifyDevices(devices);
    const wasOpen = this.gateOpen;

    if (classified.verificationState === 'verified' && (wasOpen || this.routingActive)) {
      this.epoch += 1;
      return {
        gateOpen: this.gateOpen,
        epoch: this.epoch,
        shouldStop: false,
        endReason: null,
        verification: {
          capture_session_id: this.sessionId,
          generation: this.generation,
          epoch: this.epoch,
          observed_route_type: classified.routeType,
          observed_display_name: classified.displayName,
          verification_state: 'verified',
          gate_open: this.gateOpen,
          rejection_reason: null,
          contract_gap_note: classified.contractGapNote
        }
      };
    }

    this.gateOpen = false;
    this.routingActive = false;
    this.epoch += 1;

    return {
      gateOpen: false,
      epoch: this.epoch,
      shouldStop: wasOpen,
      endReason: wasOpen ? 'route_lost' : null,
      verification: {
        capture_session_id: this.sessionId,
        generation: this.generation,
        epoch: this.epoch,
        observed_route_type: classified.routeType,
        observed_display_name: classified.displayName,
        verification_state: classified.verificationState,
        gate_open: false,
        rejection_reason: classified.rejectionReason,
        contract_gap_note: classified.contractGapNote
      }
    };
  }

  private classifyDevices(devices: ObservedAudioDevice[]): {
    routeType: ObservedInputRouteType;
    displayName: string | null;
    verificationState: VerificationState;
    rejectionReason: string | null;
    contractGapNote: string | null;
  } {
    if (devices.length === 0) {
      return {
        routeType: 'unknown',
        displayName: null,
        verificationState: 'unknown',
        rejectionReason: 'INPUT_DEVICE_MISSING',
        contractGapNote: null
      };
    }

    let firstBluetoothRoute: ObservedInputRouteType | null = null;
    let firstDisplayName: string | null = devices[0].displayName.length > 0 ? devices[0].displayName : null;
    let gapNote: string | null = null;

    for (let i = 0; i < devices.length; i += 1) {
      const dev = devices[i];
      const mapped = this.mapDeviceType(dev.deviceTypeCode, dev.deviceTypeName);
      if (mapped.contractGapNote !== null) {
        gapNote = mapped.contractGapNote;
      }
      if (!mapped.isStrictBluetoothMic) {
        return {
          routeType: mapped.routeType,
          displayName: dev.displayName.length > 0 ? dev.displayName : firstDisplayName,
          verificationState: 'mismatch',
          rejectionReason: 'INPUT_ROUTE_UNVERIFIED',
          contractGapNote: gapNote
        };
      }
      if (firstBluetoothRoute === null) {
        firstBluetoothRoute = mapped.routeType;
      }
    }

    return {
      routeType: firstBluetoothRoute ?? 'unknown',
      displayName: firstDisplayName,
      verificationState: 'verified',
      rejectionReason: null,
      contractGapNote: gapNote
    };
  }

  private mapDeviceType(code: number, name: string): {
    routeType: ObservedInputRouteType;
    isStrictBluetoothMic: boolean;
    contractGapNote: string | null;
  } {
    if (code === DEVICE_TYPE_BLUETOOTH_SCO || name === 'BLUETOOTH_SCO') {
      return { routeType: 'bluetooth_hfp', isStrictBluetoothMic: true, contractGapNote: null };
    }
    if (code === DEVICE_TYPE_BLE_HEADSET || name === 'BLE_HEADSET') {
      return { routeType: 'bluetooth_le_audio', isStrictBluetoothMic: true, contractGapNote: null };
    }
    if (code === DEVICE_TYPE_NEARLINK || name === 'NEARLINK') {
      return {
        routeType: 'bluetooth_unknown_profile',
        isStrictBluetoothMic: true,
        contractGapNote: 'HarmonyOS NEARLINK (deviceType=31) has no dedicated enum in contracts 1.1 input_route_type; mapped to bluetooth_unknown_profile'
      };
    }
    if (code === DEVICE_TYPE_MIC || name === 'MIC') {
      return { routeType: 'built_in_mic', isStrictBluetoothMic: false, contractGapNote: null };
    }
    if (code === DEVICE_TYPE_WIRED_HEADSET || name === 'WIRED_HEADSET') {
      return { routeType: 'wired_headset', isStrictBluetoothMic: false, contractGapNote: null };
    }
    if (code === DEVICE_TYPE_USB_HEADSET || name === 'USB_HEADSET') {
      return { routeType: 'usb_audio', isStrictBluetoothMic: false, contractGapNote: null };
    }
    if (code === DEVICE_TYPE_BLUETOOTH_A2DP || name === 'BLUETOOTH_A2DP') {
      return { routeType: 'other', isStrictBluetoothMic: false, contractGapNote: null };
    }
    return { routeType: 'unknown', isStrictBluetoothMic: false, contractGapNote: null };
  }
}
