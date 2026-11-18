import { common } from '@kit.AbilityKit';
import { fileIo } from '@kit.CoreFileKit';
import { systemDateTime } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { util } from '@kit.ArkTS';
import { redactLogRecord } from '../logic/LogRedactor';
import type { ProbeLogRecord } from '../logic/ProbeTypes';

const HILOG_DOMAIN = 0x00A1;
const HILOG_TAG = 'AuriProbe';

export type LogDataValue =
  | string
  | number
  | boolean
  | null
  | undefined
  | Array<string>
  | Array<Record<string, string | number | boolean | null>>;

export interface LogDataMap extends Record<string, LogDataValue> {
  accepted_bytes?: LogDataValue;
  accepted_duration_ms?: LogDataValue;
  attribute_keys?: LogDataValue;
  available_inputs?: LogDataValue;
  business_command?: LogDataValue;
  capture_state?: LogDataValue;
  change_reason?: LogDataValue;
  change_type?: LogDataValue;
  channel?: LogDataValue;
  channels?: LogDataValue;
  code?: LogDataValue;
  command?: LogDataValue;
  contract_gap_note?: LogDataValue;
  deduplicated?: LogDataValue;
  device_id?: LogDataValue;
  device_type?: LogDataValue;
  devices?: LogDataValue;
  discarded_bytes?: LogDataValue;
  enabled?: LogDataValue;
  epoch?: LogDataValue;
  error_code?: LogDataValue;
  error_message?: LogDataValue;
  event?: LogDataValue;
  first_accepted_mono_ns?: LogDataValue;
  force_type?: LogDataValue;
  gate_open?: LogDataValue;
  generation?: LogDataValue;
  gesture?: LogDataValue;
  granted?: LogDataValue;
  hint_type?: LogDataValue;
  host_address?: LogDataValue;
  key_action?: LogDataValue;
  key_code?: LogDataValue;
  last_accepted_mono_ns?: LogDataValue;
  mapping?: LogDataValue;
  market_name?: LogDataValue;
  message?: LogDataValue;
  mode?: LogDataValue;
  normalized_platform_event?: LogDataValue;
  note?: LogDataValue;
  observed_devices?: LogDataValue;
  observed_route_type?: LogDataValue;
  os_full_name?: LogDataValue;
  platform_command?: LogDataValue;
  playback_state_contrast?: LogDataValue;
  port?: LogDataValue;
  product_model?: LogDataValue;
  quarantined_bytes?: LogDataValue;
  quarantined_bytes_discarded?: LogDataValue;
  raw_action_code?: LogDataValue;
  raw_event?: LogDataValue;
  reason?: LogDataValue;
  rejection_reason?: LogDataValue;
  route_epoch?: LogDataValue;
  route_verified_after_capturer_start?: LogDataValue;
  sample_rate?: LogDataValue;
  screen_state?: LogDataValue;
  sdk_api_version?: LogDataValue;
  sequence?: LogDataValue;
  service_name?: LogDataValue;
  service_type?: LogDataValue;
  session_id?: LogDataValue;
  source?: LogDataValue;
  state?: LogDataValue;
  stream_count?: LogDataValue;
  target_device_id?: LogDataValue;
  trigger?: LogDataValue;
  unmapped_detail?: LogDataValue;
  verification_state?: LogDataValue;
  write_failures?: LogDataValue;
}

/**
 * Append-only JSON Lines logger writing into the app sandbox (`filesDir/probe-log.jsonl`).
 * Uses monotonic clock (`systemDateTime.getUptime`) and never writes audio bytes or transcripts.
 */
export class ProbeLogger {
  private static instance: ProbeLogger | null = null;

  readonly launchId: string = util.generateRandomUUID(true);
  private filesDir = '';
  private logFilePath = '';
  private seq = 0;
  private appState = 'unknown';
  private onLineCallback: ((line: string) => void) | null = null;

  static getInstance(): ProbeLogger {
    if (ProbeLogger.instance === null) {
      ProbeLogger.instance = new ProbeLogger();
    }
    return ProbeLogger.instance;
  }

  init(context: common.UIAbilityContext): void {
    this.filesDir = context.filesDir;
    this.logFilePath = `${this.filesDir}/probe-log.jsonl`;
  }

  getFilesDir(): string {
    return this.filesDir;
  }

  getLogFilePath(): string {
    return this.logFilePath;
  }

  setOnLine(callback: ((line: string) => void) | null): void {
    this.onLineCallback = callback;
  }

  setAppState(state: string): void {
    this.appState = state;
  }

  getAppState(): string {
    return this.appState;
  }

  static monoNanos(): number {
    try {
      return systemDateTime.getUptime(systemDateTime.TimeType.STARTUP, true);
    } catch {
      return systemDateTime.getTime(true);
    }
  }

  log(kind: string, data: LogDataMap = {}): void {
    this.seq += 1;
    const rawRecord: ProbeLogRecord = {
      seq: this.seq,
      launch_id: this.launchId,
      mono_ns: ProbeLogger.monoNanos(),
      wall: new Date().toISOString(),
      kind,
      app_state: this.appState,
      data
    };

    // Always redact sensitive identifiers before writing/displaying, keeping only diagnostic metadata.
    const safeRecord = redactLogRecord(rawRecord);
    const encoded = JSON.stringify(safeRecord);
    hilog.info(HILOG_DOMAIN, HILOG_TAG, '%{public}s', encoded);

    if (this.logFilePath.length > 0) {
      try {
        const file = fileIo.openSync(
          this.logFilePath,
          fileIo.OpenMode.CREATE | fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.APPEND
        );
        try {
          fileIo.writeSync(file.fd, `${encoded}\n`);
        } finally {
          fileIo.closeSync(file);
        }
      } catch (err) {
        hilog.error(HILOG_DOMAIN, HILOG_TAG, 'Failed writing probe-log.jsonl: %{public}s', JSON.stringify(err));
      }
    }

    if (this.onLineCallback !== null) {
      this.onLineCallback(encoded);
    }
  }
}
