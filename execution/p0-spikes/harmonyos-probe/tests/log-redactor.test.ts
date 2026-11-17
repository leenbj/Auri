import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  redactDeviceName,
  redactLogLineJson,
  redactLogRecord,
  redactMacTail
} from '../entry/src/main/ets/logic/LogRedactor';
import type { ProbeLogRecord } from '../entry/src/main/ets/logic/ProbeTypes';

describe('LogRedactor', () => {
  it('masks Bluetooth MAC addresses to last 5 characters', () => {
    assert.equal(redactMacTail('AA:BB:CC:DD:EE:FF'), '**..EE:FF');
    assert.equal(redactMacTail('a1-b2-c3-d4-e5-f6'), '**..e5-f6');
    assert.equal(redactMacTail(''), '');
  });

  it('replaces personal device names with a fixed redaction token', () => {
    assert.equal(redactDeviceName("Ethan's FreeClip 2"), '[REDACTED_DEVICE]');
    assert.equal(redactDeviceName(''), '');
  });

  it('redacts sensitive fields in ProbeLogRecord while preserving route and mDNS diagnostics', () => {
    const raw: ProbeLogRecord = {
      seq: 12,
      launch_id: 'launch-1',
      mono_ns: 987654321,
      wall: '2026-11-17T01:00:00Z',
      kind: 'route_verified',
      app_state: 'foreground',
      data: {
        route_type: 'bluetooth_hfp',
        device_type: 'BLUETOOTH_SCO',
        address: '11:22:33:44:55:66',
        display_name: "Alice's HUAWEI FreeClip 2",
        transcript: 'secret dictation text',
        token: 'Bearer super-secret',
        service_type: '_auri-gateway._tcp',
        port: 8443,
        note: 'Connected peer AA:BB:CC:DD:EE:FF',
      },
    };

    const sanitized = redactLogRecord(raw);

    assert.equal(sanitized.seq, 12);
    assert.equal(sanitized.data['route_type'], 'bluetooth_hfp');
    assert.equal(sanitized.data['device_type'], 'BLUETOOTH_SCO');
    assert.equal(sanitized.data['service_type'], '_auri-gateway._tcp');
    assert.equal(sanitized.data['port'], 8443);
    assert.equal(sanitized.data['address'], '**..55:66');
    assert.equal(sanitized.data['display_name'], '[REDACTED_DEVICE]');
    assert.equal(sanitized.data['transcript'], '[REDACTED]');
    assert.equal(sanitized.data['token'], '[REDACTED]');
    assert.equal(sanitized.data['note'], 'Connected peer **..EE:FF');
    // Original object must not be mutated
    assert.equal(raw.data['address'], '11:22:33:44:55:66');
  });

  it('redacts nested device descriptor arrays inside data', () => {
    const raw: ProbeLogRecord = {
      seq: 13,
      launch_id: 'launch-1',
      mono_ns: 987654999,
      wall: '2026-11-17T01:00:01Z',
      kind: 'available_inputs',
      app_state: 'foreground',
      data: {
        inputs: [
          {
            id: 7,
            device_type: 'BLUETOOTH_SCO',
            display_name: "Bob's Headset",
            address: 'DE:AD:BE:EF:12:34',
          },
        ],
      },
    };

    const sanitized = redactLogRecord(raw);
    const inputs = sanitized.data['inputs'] as Array<Record<string, string | number | boolean | null>>;

    assert.equal(inputs[0]['device_type'], 'BLUETOOTH_SCO');
    assert.equal(inputs[0]['display_name'], '[REDACTED_DEVICE]');
    assert.equal(inputs[0]['address'], '**..12:34');
  });

  it('redacts a raw JSONL line string and preserves invalid lines as explicit error marker', () => {
    const validLine = JSON.stringify({
      seq: 1,
      launch_id: 'l1',
      mono_ns: 100,
      wall: '2026-11-17T01:00:00Z',
      kind: 'mdns_service_found',
      app_state: 'foreground',
      data: {
        service_type: '_auri-gateway._tcp',
        service_name: "Ethan's MacBook Gateway",
        port: 8443,
      },
    });

    const out = JSON.parse(redactLogLineJson(validLine)) as ProbeLogRecord;
    assert.equal(out.data['service_name'], '[REDACTED_DEVICE]');
    assert.equal(out.data['service_type'], '_auri-gateway._tcp');
    assert.equal(out.data['port'], 8443);

    const bad = JSON.parse(redactLogLineJson('{not-json')) as Record<string, string>;
    assert.equal(bad['kind'], 'redact_parse_failed');
  });
});
