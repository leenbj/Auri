import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { ControlNormalizer } from '../entry/src/main/ets/logic/ControlNormalizer';

describe('ControlNormalizer', () => {
  it('maps AVSession play callback to START with unknown_remote source in semantic mode', () => {
    const normalizer = new ControlNormalizer('harmonyos-generic-probe', 1);

    const event = normalizer.normalize(
      {
        channel: 'avsession_command',
        platformCommand: 'play',
        keyCode: null,
        keyAction: null,
        monotonicNs: 1000,
        rawEventId: 'av-play-1',
      },
      'semantic',
      '11111111-1111-4111-8111-111111111111'
    );

    assert.equal(event.source, 'unknown_remote');
    assert.equal(event.platform_event, 'play');
    assert.equal(event.command, 'START');
    assert.equal(event.profile_id, 'harmonyos-generic-probe');
    assert.equal(event.profile_version, 1);
    assert.equal(event.raw_event_id, 'av-play-1');
    assert.equal(event.deduplicated, false);
    assert.equal(event.unmapped_platform_detail, null);
  });

  it('maps AVSession pause and stop callbacks to STOP in semantic mode', () => {
    const normalizer = new ControlNormalizer('harmonyos-generic-probe', 1);

    const pauseEvent = normalizer.normalize(
      {
        channel: 'avsession_command',
        platformCommand: 'pause',
        keyCode: null,
        keyAction: null,
        monotonicNs: 2000,
        rawEventId: null,
      },
      'semantic',
      '22222222-2222-4222-8222-222222222222'
    );

    const stopEvent = normalizer.normalize(
      {
        channel: 'avsession_command',
        platformCommand: 'stop',
        keyCode: null,
        keyAction: null,
        monotonicNs: 3000,
        rawEventId: null,
      },
      'semantic',
      '33333333-3333-4333-8333-333333333333'
    );

    assert.equal(pauseEvent.platform_event, 'pause');
    assert.equal(pauseEvent.command, 'STOP');
    assert.equal(stopEvent.platform_event, 'pause');
    assert.equal(stopEvent.command, 'STOP');
    assert.equal(stopEvent.unmapped_platform_detail, 'avsession_stop_mapped_to_pause');
  });

  it('keeps command as IGNORE in logOnly mode without suppressing platform_event', () => {
    const normalizer = new ControlNormalizer('harmonyos-generic-probe', 1);

    const event = normalizer.normalize(
      {
        channel: 'avsession_command',
        platformCommand: 'play',
        keyCode: null,
        keyAction: null,
        monotonicNs: 4000,
        rawEventId: null,
      },
      'logOnly',
      '44444444-4444-4444-8444-444444444444'
    );

    assert.equal(event.platform_event, 'play');
    assert.equal(event.command, 'IGNORE');
  });

  it('deduplicates repeated events with the same rawEventId within dedup window and marks IGNORE', () => {
    const normalizer = new ControlNormalizer('harmonyos-generic-probe', 1);

    const first = normalizer.normalize(
      {
        channel: 'avsession_command',
        platformCommand: 'play',
        keyCode: null,
        keyAction: null,
        monotonicNs: 10_000_000,
        rawEventId: 'dup-id-1',
      },
      'semantic',
      '55555555-5555-4555-8555-555555555555'
    );

    const duplicate = normalizer.normalize(
      {
        channel: 'avsession_key_event',
        platformCommand: null,
        keyCode: 10,
        keyAction: 'down',
        monotonicNs: 15_000_000,
        rawEventId: 'dup-id-1',
      },
      'semantic',
      '66666666-6666-4666-8666-666666666666'
    );

    assert.equal(first.deduplicated, false);
    assert.equal(first.command, 'START');
    assert.equal(duplicate.deduplicated, true);
    assert.equal(duplicate.command, 'IGNORE');
  });

  it('maps HarmonyOS key events KEYCODE_MEDIA_PLAY_PAUSE (10) on key down to TOGGLE and ignores key up', () => {
    const normalizer = new ControlNormalizer('harmonyos-generic-probe', 1);

    const downEvent = normalizer.normalize(
      {
        channel: 'avsession_key_event',
        platformCommand: null,
        keyCode: 10,
        keyAction: 'down',
        monotonicNs: 50_000_000,
        rawEventId: 'key-10-down',
      },
      'semantic',
      '77777777-7777-4777-8777-777777777777'
    );

    const upEvent = normalizer.normalize(
      {
        channel: 'avsession_key_event',
        platformCommand: null,
        keyCode: 10,
        keyAction: 'up',
        monotonicNs: 60_000_000,
        rawEventId: 'key-10-up',
      },
      'semantic',
      '88888888-8888-4888-8888-888888888888'
    );

    assert.equal(downEvent.platform_event, 'toggle');
    assert.equal(downEvent.command, 'TOGGLE');
    assert.equal(downEvent.unmapped_platform_detail, 'avsession_key_10_toggle');
    assert.equal(upEvent.command, 'IGNORE');
    assert.equal(upEvent.unmapped_platform_detail, 'key_up_ignored:10');
  });

  it('sets profile_id and profile_version to null for screen_button events', () => {
    const normalizer = new ControlNormalizer('harmonyos-generic-probe', 1);

    const event = normalizer.normalize(
      {
        channel: 'screen_button',
        platformCommand: 'manual_start',
        keyCode: null,
        keyAction: null,
        monotonicNs: 70_000_000,
        rawEventId: null,
      },
      'semantic',
      '99999999-9999-4999-8999-999999999999'
    );

    assert.equal(event.source, 'screen_control');
    assert.equal(event.platform_event, 'screen_button');
    assert.equal(event.command, 'START');
    assert.equal(event.profile_id, null);
    assert.equal(event.profile_version, null);
  });

  it('flags unknown keycodes (e.g. KEYCODE_HEADSETHOOK 6 / unknown) as IGNORE with contract gap detail', () => {
    const normalizer = new ControlNormalizer('harmonyos-generic-probe', 1);

    const event = normalizer.normalize(
      {
        channel: 'avsession_key_event',
        platformCommand: null,
        keyCode: 6,
        keyAction: 'down',
        monotonicNs: 80_000_000,
        rawEventId: 'key-6-down',
      },
      'semantic',
      'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
    );

    assert.equal(event.source, 'unknown_remote');
    assert.equal(event.command, 'IGNORE');
    assert.equal(event.unmapped_platform_detail, 'unmapped_key_code:6');
  });
});
