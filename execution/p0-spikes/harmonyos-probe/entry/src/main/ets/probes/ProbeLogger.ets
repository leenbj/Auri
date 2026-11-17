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
  | Array<string>
  | Array<Record<string, string | number | boolean | null>>;

export type LogDataMap = Record<string, LogDataValue>;

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
