import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { RouteEvidenceGate } from '../entry/src/main/ets/logic/RouteEvidenceGate';
import type { ObservedAudioDevice } from '../entry/src/main/ets/logic/ProbeTypes';

function makeDevice(partial: Partial<ObservedAudioDevice>): ObservedAudioDevice {
  return {
    id: partial.id ?? 1,
    deviceTypeCode: partial.deviceTypeCode ?? 15,
    deviceTypeName: partial.deviceTypeName ?? 'MIC',
    deviceRole: partial.deviceRole ?? 'input',
    displayName: partial.displayName ?? 'Built-in Mic',
    address: partial.address ?? '',
    sampleRates: partial.sampleRates ?? [16000],
    channelCounts: partial.channelCounts ?? [1],
  };
}

describe('RouteEvidenceGate', () => {
  it('refuses to open gate when observed input devices list is empty', () => {
    const gate = new RouteEvidenceGate();
    const gen = gate.beginRouting('session-1');

    const result = gate.evaluateObservedInput(gen, []);

    assert.equal(result.verification_state, 'unknown');
    assert.equal(result.observed_route_type, 'unknown');
    assert.equal(result.gate_open, false);
    assert.equal(result.rejection_reason, 'INPUT_DEVICE_MISSING');
  });

  it('refuses to open gate when observed input is built-in MIC (15)', () => {
    const gate = new RouteEvidenceGate();
    const gen = gate.beginRouting('session-2');

    const result = gate.evaluateObservedInput(gen, [
      makeDevice({ deviceTypeCode: 15, deviceTypeName: 'MIC', displayName: 'Phone Mic' }),
    ]);

    assert.equal(result.verification_state, 'mismatch');
    assert.equal(result.observed_route_type, 'built_in_mic');
    assert.equal(result.gate_open, false);
    assert.equal(result.rejection_reason, 'INPUT_ROUTE_UNVERIFIED');
  });

  it('refuses to open gate when observed device is BLUETOOTH_A2DP (8) because A2DP is not a headset mic input', () => {
    const gate = new RouteEvidenceGate();
    const gen = gate.beginRouting('session-3');

    const result = gate.evaluateObservedInput(gen, [
      makeDevice({ deviceTypeCode: 8, deviceTypeName: 'BLUETOOTH_A2DP', displayName: 'FreeClip 2' }),
    ]);

    assert.equal(result.verification_state, 'mismatch');
    assert.equal(result.observed_route_type, 'other');
    assert.equal(result.gate_open, false);
  });

  it('opens gate when all observed input devices are BLUETOOTH_SCO (7)', () => {
    const gate = new RouteEvidenceGate();
    const gen = gate.beginRouting('session-4');

    const result = gate.evaluateObservedInput(gen, [
      makeDevice({ deviceTypeCode: 7, deviceTypeName: 'BLUETOOTH_SCO', displayName: 'FreeClip 2' }),
    ]);

    assert.equal(result.verification_state, 'verified');
    assert.equal(result.observed_route_type, 'bluetooth_hfp');
    assert.equal(result.gate_open, true);
    assert.equal(result.epoch, 1);
    assert.equal(result.rejection_reason, null);
  });

  it('opens gate for NEARLINK (31) as bluetooth_unknown_profile and emits a contract gap note', () => {
    const gate = new RouteEvidenceGate();
    const gen = gate.beginRouting('session-5');

    const result = gate.evaluateObservedInput(gen, [
      makeDevice({ deviceTypeCode: 31, deviceTypeName: 'NEARLINK', displayName: 'FreeClip 2' }),
    ]);

    assert.equal(result.verification_state, 'verified');
    assert.equal(result.observed_route_type, 'bluetooth_unknown_profile');
    assert.equal(result.gate_open, true);
    assert.ok(result.contract_gap_note !== null && result.contract_gap_note.includes('NEARLINK'));
  });

  it('quarantines frames before verification, accepts only matching generation+epoch, and discards after gate closes', () => {
    const gate = new RouteEvidenceGate();
    const gen = gate.beginRouting('session-6');

    assert.equal(gate.acceptFrame(gen, 0), 'quarantined');

    const verified = gate.evaluateObservedInput(gen, [
      makeDevice({ deviceTypeCode: 7, deviceTypeName: 'BLUETOOTH_SCO', displayName: 'FreeClip 2' }),
    ]);
    assert.equal(gate.acceptFrame(gen, verified.epoch), 'accepted');
    assert.equal(gate.acceptFrame(gen, verified.epoch + 1), 'discarded');

    const transition = gate.onRouteChanged([
      makeDevice({ deviceTypeCode: 15, deviceTypeName: 'MIC', displayName: 'Phone Mic' }),
    ]);
    assert.equal(transition.shouldStop, true);
    assert.equal(transition.endReason, 'route_lost');
    assert.equal(gate.acceptFrame(gen, verified.epoch), 'discarded');
  });

  it('rejects stale generation callbacks after routing was cancelled or superseded', () => {
    const gate = new RouteEvidenceGate();
    const staleGen = gate.beginRouting('session-stale');
    gate.cancelRouting(staleGen);
    const activeGen = gate.beginRouting('session-active');

    const staleResult = gate.evaluateObservedInput(staleGen, [
      makeDevice({ deviceTypeCode: 7, deviceTypeName: 'BLUETOOTH_SCO', displayName: 'FreeClip 2' }),
    ]);

    assert.equal(staleResult.gate_open, false);
    assert.equal(staleResult.verification_state, 'unknown');
    assert.equal(staleResult.rejection_reason, 'STALE_GENERATION');
    assert.equal(gate.currentGeneration(), activeGen);
  });

  it('invalidates a cancelled routing generation before a new session starts', () => {
    const gate = new RouteEvidenceGate();
    const cancelled = gate.beginRouting('session-cancelled');

    gate.cancelRouting(cancelled);

    assert.notEqual(gate.currentGeneration(), cancelled);
    assert.equal(gate.acceptFrame(cancelled, 0), 'discarded');
    const late = gate.evaluateObservedInput(cancelled, [
      makeDevice({ deviceTypeCode: 7, deviceTypeName: 'BLUETOOTH_SCO' }),
    ]);
    assert.equal(late.rejection_reason, 'STALE_GENERATION');
    assert.equal(late.gate_open, false);
  });
});
