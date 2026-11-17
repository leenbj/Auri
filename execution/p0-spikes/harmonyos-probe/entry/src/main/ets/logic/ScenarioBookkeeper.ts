import type { CompatStatus, EvidenceLevel } from './ProbeTypes';

export interface ScenarioDefinition {
  id: string;
  label: string;
}

export const PROBE_SCENARIOS: ScenarioDefinition[] = [
  { id: 'S1_foreground_idle_events', label: 'S1 前台待命（不录音）收到什么平台事件' },
  { id: 'S2_foreground_start_stop', label: 'S2 前台手势开始和停止录音（含蓝牙路由证据）' },
  { id: 'S3_stop_while_bluetooth_mic_active', label: 'S3 蓝牙麦克风已激活后再次手势能否停止（必测）' },
  { id: 'S4_continue_recording_when_locked', label: 'S4 录音中锁屏能否继续并手势停止' },
  { id: 'S5_restart_after_stop_when_locked', label: 'S5 停止后锁屏/后台再次手势开始' },
  { id: 'S6_start_after_process_termination', label: 'S6 进程被杀后手势能否开始' },
  { id: 'S7_route_lost_protection', label: 'S7 录音中耳机断开/路由切走关闸保护' },
  { id: 'M1_mdns_discovery', label: 'M1 局域网 mDNS 发现 _auri-gateway._tcp' }
];

export interface ScenarioRecord {
  headsetModel: string;
  phoneModel: string;
  osVersion: string;
  scenarioId: string;
  scenarioLabel: string;
  status: CompatStatus;
  evidenceLevel: EvidenceLevel | null;
  evidenceIds: string[];
  observedPlatformEvents: string;
  observedInputRoute: string;
  notes: string;
}

export interface ScenarioObservationInput {
  status: CompatStatus;
  evidenceLevel: EvidenceLevel | null;
  evidenceIds: string[];
  observedPlatformEvents: string;
  observedInputRoute: string;
  notes: string;
}

export function createInitialMatrix(
  headsetModels: string[],
  phoneModel: string,
  osVersion: string
): ScenarioRecord[] {
  const rows: ScenarioRecord[] = [];
  for (let h = 0; h < headsetModels.length; h += 1) {
    const headsetModel = headsetModels[h];
    for (let s = 0; s < PROBE_SCENARIOS.length; s += 1) {
      const scenario = PROBE_SCENARIOS[s];
      rows.push({
        headsetModel,
        phoneModel,
        osVersion,
        scenarioId: scenario.id,
        scenarioLabel: scenario.label,
        status: 'NOT_TESTED',
        evidenceLevel: null,
        evidenceIds: [],
        observedPlatformEvents: 'NOT_TESTED',
        observedInputRoute: 'NOT_TESTED',
        notes: 'NOT_RUN（未在真机运行）'
      });
    }
  }
  return rows;
}

export function recordObservation(
  records: ScenarioRecord[],
  headsetModel: string,
  scenarioId: string,
  observation: ScenarioObservationInput
): ScenarioRecord[] {
  const requiresDeviceEvidence =
    observation.status !== 'NOT_TESTED' && observation.status !== 'CALIBRATION_REQUIRED';

  if (requiresDeviceEvidence) {
    if (observation.evidenceLevel !== 'E3' && observation.evidenceLevel !== 'E4') {
      throw new Error(
        `Status ${observation.status} requires E3 or E4 real-device evidence; got ${observation.evidenceLevel ?? 'null'}`
      );
    }
    if (observation.evidenceIds.length === 0) {
      throw new Error(`Status ${observation.status} requires non-empty evidenceIds`);
    }
  }

  let matched = false;
  const updated: ScenarioRecord[] = [];
  for (let i = 0; i < records.length; i += 1) {
    const row = records[i];
    if (row.headsetModel === headsetModel && row.scenarioId === scenarioId) {
      matched = true;
      updated.push({
        headsetModel: row.headsetModel,
        phoneModel: row.phoneModel,
        osVersion: row.osVersion,
        scenarioId: row.scenarioId,
        scenarioLabel: row.scenarioLabel,
        status: observation.status,
        evidenceLevel: observation.evidenceLevel,
        evidenceIds: observation.evidenceIds.slice(),
        observedPlatformEvents: observation.observedPlatformEvents,
        observedInputRoute: observation.observedInputRoute,
        notes: observation.notes
      });
    } else {
      updated.push(row);
    }
  }

  if (!matched) {
    throw new Error(`Scenario row not found for ${headsetModel} × ${scenarioId}`);
  }
  return updated;
}

export function formatMatrixMarkdown(records: ScenarioRecord[]): string {
  const lines: string[] = [
    '| 耳机 | 手机 | 系统版本 | 场景 | 兼容状态 | 证据等级 | 实际收到的平台事件 | 系统观测输入路由 | 证据标识 / 备注 |',
    '|---|---|---|---|---|---|---|---|---|'
  ];

  for (let i = 0; i < records.length; i += 1) {
    const r = records[i];
    const evidenceText =
      r.evidenceIds.length > 0 ? `${r.evidenceIds.join(', ')}; ${r.notes}` : r.notes;
    lines.push(
      `| ${r.headsetModel} | ${r.phoneModel} | ${r.osVersion} | ${r.scenarioId}（${r.scenarioLabel}） | \`${r.status}\` | ${r.evidenceLevel ?? 'null'} | ${r.observedPlatformEvents} | ${r.observedInputRoute} | ${evidenceText} |`
    );
  }
  return lines.join('\n');
}
