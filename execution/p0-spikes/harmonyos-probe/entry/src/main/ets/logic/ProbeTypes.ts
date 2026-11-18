/**
 * Pure ArkTS-compatible domain types aligned with contracts 1.1
 * (`adapter.schema.json`, `common.schema.json`, `headset-profile.schema.json`).
 * Zero HarmonyOS SDK imports so off-device tests can execute this module directly.
 */

export type ControlSource =
  | 'headset_remote'
  | 'unknown_remote'
  | 'volume_gesture'
  | 'screen_control';

export type ContractPlatformEvent =
  | 'play'
  | 'pause'
  | 'toggle'
  | 'next'
  | 'previous'
  | 'volume_change'
  | 'screen_button';

export type BusinessCommand = 'START' | 'STOP' | 'TOGGLE' | 'IGNORE';

export type ControlMappingMode = 'logOnly' | 'semantic' | 'toggle';

export type ObservedInputRouteType =
  | 'bluetooth_hfp'
  | 'bluetooth_le_audio'
  | 'bluetooth_unknown_profile'
  | 'built_in_mic'
  | 'wired_headset'
  | 'usb_audio'
  | 'other'
  | 'unknown';

export type VerificationState = 'verified' | 'mismatch' | 'unknown';

export type CaptureState =
  | 'DISARMED'
  | 'ARMED'
  | 'ROUTING'
  | 'RECORDING'
  | 'FINALIZING'
  | 'BLOCKED';

export type CompatStatus =
  | 'NOT_TESTED'
  | 'CALIBRATION_REQUIRED'
  | 'SUPPORTED_FOREGROUND'
  | 'SUPPORTED_RECORDING_IN_BACKGROUND'
  | 'SUPPORTED_BACKGROUND_RESTART'
  | 'PARTIAL'
  | 'UNSUPPORTED';

export type EvidenceLevel = 'E0' | 'E1' | 'E2' | 'E3' | 'E4';

export interface NormalizedControlEvent {
  event_id: string;
  source: ControlSource;
  platform_event: ContractPlatformEvent;
  command: BusinessCommand;
  monotonic_ns: number;
  sequence: number;
  profile_id: string | null;
  profile_version: number | null;
  raw_event_id: string | null;
  /** Non-null when the platform delivered a control event outside contracts 1.1 `platform_event`. */
  unmapped_platform_detail: string | null;
  deduplicated: boolean;
}

export interface ObservedAudioDevice {
  id: number;
  /** Numeric HarmonyOS `audio.DeviceType` value or symbolic label. */
  deviceTypeCode: number;
  deviceTypeName: string;
  deviceRole: 'input' | 'output' | 'unknown';
  displayName: string;
  address: string;
  sampleRates: number[];
  channelCounts: number[];
}

export interface RouteVerificationResult {
  capture_session_id: string;
  generation: number;
  epoch: number;
  observed_route_type: ObservedInputRouteType;
  observed_display_name: string | null;
  verification_state: VerificationState;
  gate_open: boolean;
  rejection_reason: string | null;
  contract_gap_note: string | null;
}

export interface ProbeLogRecord {
  seq: number;
  launch_id: string;
  mono_ns: number;
  wall: string;
  kind: string;
  app_state: string;
  data: Record<string, string | number | boolean | null | undefined | Array<string> | Array<Record<string, string | number | boolean | null>>>;
}
