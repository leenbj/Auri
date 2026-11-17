import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  createInitialMatrix,
  formatMatrixMarkdown,
  recordObservation,
} from '../entry/src/main/ets/logic/ScenarioBookkeeper';

describe('ScenarioBookkeeper', () => {
  it('initializes all required headset × phone × OS × scenario rows as NOT_TESTED', () => {
    const rows = createInitialMatrix(
      ['HUAWEI FreeClip 2', 'HUAWEI FreeClip'],
      'HUAWEI Phone (TBD)',
      'HarmonyOS NEXT (TBD)'
    );

    // 2 headsets × 8 scenarios (S1..S7 + M1) = 16 rows
    assert.equal(rows.length, 16);
    for (const row of rows) {
      assert.equal(row.status, 'NOT_TESTED');
      assert.equal(row.evidenceLevel, null);
      assert.deepEqual(row.evidenceIds, []);
    }
  });

  it('rejects upgrading compatibility status using E2 unit-test evidence or missing evidenceIds', () => {
    const rows = createInitialMatrix(['HUAWEI FreeClip 2'], 'Phone A', 'HarmonyOS 6.0.1');

    assert.throws(() => {
      recordObservation(rows, 'HUAWEI FreeClip 2', 'S1_foreground_idle_events', {
        status: 'SUPPORTED_FOREGROUND',
        evidenceLevel: 'E2',
        evidenceIds: ['tests/control-normalizer.test.ts'],
        observedPlatformEvents: 'play',
        observedInputRoute: 'unknown',
        notes: 'unit test only',
      });
    }, /E3 or E4/);

    assert.throws(() => {
      recordObservation(rows, 'HUAWEI FreeClip 2', 'S1_foreground_idle_events', {
        status: 'SUPPORTED_FOREGROUND',
        evidenceLevel: 'E3',
        evidenceIds: [],
        observedPlatformEvents: 'play',
        observedInputRoute: 'bluetooth_hfp',
        notes: 'missing evidence id',
      });
    }, /evidenceIds/);
  });

  it('allows recording E3 device evidence for a single row without altering other rows', () => {
    const rows = createInitialMatrix(
      ['HUAWEI FreeClip 2', 'HUAWEI FreeClip'],
      'Phone A',
      'HarmonyOS 6.0.1'
    );

    const updated = recordObservation(rows, 'HUAWEI FreeClip 2', 'S3_stop_while_bluetooth_mic_active', {
      status: 'SUPPORTED_FOREGROUND',
      evidenceLevel: 'E3',
      evidenceIds: ['results/2026-11-18-freeclip2-phonea.md'],
      observedPlatformEvents: 'pause',
      observedInputRoute: 'bluetooth_hfp',
      notes: 'verified on real device',
    });

    const target = updated.find(
      (r) => r.headsetModel === 'HUAWEI FreeClip 2' && r.scenarioId === 'S3_stop_while_bluetooth_mic_active'
    );
    const untouched = updated.find(
      (r) => r.headsetModel === 'HUAWEI FreeClip' && r.scenarioId === 'S3_stop_while_bluetooth_mic_active'
    );

    assert.equal(target?.status, 'SUPPORTED_FOREGROUND');
    assert.equal(target?.evidenceLevel, 'E3');
    assert.equal(untouched?.status, 'NOT_TESTED');
  });

  it('renders a deterministic Markdown table containing every NOT_TESTED row', () => {
    const rows = createInitialMatrix(['HUAWEI FreeClip 2'], 'Phone TBD', 'HarmonyOS NEXT TBD');
    const md = formatMatrixMarkdown(rows);

    assert.ok(md.includes('| 耳机 | 手机 | 系统版本 | 场景 | 兼容状态 | 证据等级 |'));
    assert.ok(md.includes('HUAWEI FreeClip 2'));
    assert.ok(md.includes('S3_stop_while_bluetooth_mic_active'));
    assert.ok(md.includes('M1_mdns_discovery'));
    assert.ok(md.includes('NOT_TESTED'));
  });
});
