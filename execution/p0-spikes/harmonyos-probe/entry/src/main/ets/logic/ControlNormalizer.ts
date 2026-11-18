import type {
  BusinessCommand,
  ContractPlatformEvent,
  ControlMappingMode,
  ControlSource,
  NormalizedControlEvent
} from './ProbeTypes';

export type ControlChannel = 'avsession_command' | 'avsession_key_event' | 'screen_button';

export interface RawPlatformControlInput {
  channel: ControlChannel;
  platformCommand: string | null;
  keyCode: number | null;
  keyAction: 'down' | 'up' | 'unknown' | null;
  monotonicNs: number;
  rawEventId: string | null;
}

interface MappedPlatformEvent {
  platformEvent: ContractPlatformEvent;
  semanticCommand: BusinessCommand;
  toggleModeCommand: BusinessCommand;
  detail: string | null;
}

const KEYCODE_MEDIA_PLAY_PAUSE = 10;
const KEYCODE_MEDIA_STOP = 11;
const KEYCODE_MEDIA_NEXT = 12;
const KEYCODE_MEDIA_PREVIOUS = 13;
const KEYCODE_MEDIA_PLAY = 2076;
const KEYCODE_MEDIA_PAUSE = 2077;

const DEDUP_WINDOW_NS = 300_000_000;

export function keyEventRawId(keyCode: number, pressedTime: number | undefined): string | null {
  return pressedTime !== undefined && Number.isFinite(pressedTime) ? `${keyCode}-${pressedTime}` : null;
}

/**
 * Normalizes HarmonyOS AVSession commands, raw KeyEvents, and screen controls
 * into contract-aligned `NormalizedControlEvent` records without guessing
 * physical gestures or left/right ear (FR-HS-001 / FR-HS-004).
 */
export class ControlNormalizer {
  private readonly profileId: string;
  private readonly profileVersion: number;
  private sequenceCounter = 0;
  private readonly seenRawIds: Map<string, number> = new Map<string, number>();

  constructor(profileId: string, profileVersion: number) {
    this.profileId = profileId;
    this.profileVersion = profileVersion;
  }

  normalize(
    input: RawPlatformControlInput,
    mappingMode: ControlMappingMode,
    eventId: string
  ): NormalizedControlEvent {
    this.sequenceCounter += 1;
    const sequence = this.sequenceCounter;

    if (input.channel === 'screen_button') {
      const command: BusinessCommand = input.platformCommand === 'manual_stop' ? 'STOP' : 'START';
      return {
        event_id: eventId,
        source: 'screen_control',
        platform_event: 'screen_button',
        command,
        monotonic_ns: input.monotonicNs,
        sequence,
        profile_id: null,
        profile_version: null,
        raw_event_id: input.rawEventId,
        unmapped_platform_detail: null,
        deduplicated: false
      };
    }

    const deduplicated = this.isDuplicate(input.rawEventId, input.monotonicNs);
    const source: ControlSource = 'unknown_remote';
    const mapped = this.mapRemoteInput(input);

    let command: BusinessCommand = 'IGNORE';
    if (!deduplicated && mappingMode !== 'logOnly') {
      command = mappingMode === 'toggle' ? mapped.toggleModeCommand : mapped.semanticCommand;
    }

    return {
      event_id: eventId,
      source,
      platform_event: mapped.platformEvent,
      command,
      monotonic_ns: input.monotonicNs,
      sequence,
      profile_id: this.profileId,
      profile_version: this.profileVersion,
      raw_event_id: input.rawEventId,
      unmapped_platform_detail: mapped.detail,
      deduplicated
    };
  }

  private isDuplicate(rawEventId: string | null, monotonicNs: number): boolean {
    if (rawEventId === null || rawEventId.length === 0) {
      return false;
    }
    const previousNs = this.seenRawIds.get(rawEventId);
    this.seenRawIds.set(rawEventId, monotonicNs);
    if (previousNs !== undefined && monotonicNs - previousNs <= DEDUP_WINDOW_NS) {
      return true;
    }
    return false;
  }

  private mapRemoteInput(input: RawPlatformControlInput): MappedPlatformEvent {
    if (input.channel === 'avsession_command') {
      return this.mapAvSessionCommand(input.platformCommand);
    }
    return this.mapKeyEvent(input.keyCode, input.keyAction);
  }

  private mapAvSessionCommand(cmd: string | null): MappedPlatformEvent {
    switch (cmd) {
      case 'play':
        return {
          platformEvent: 'play',
          semanticCommand: 'START',
          toggleModeCommand: 'TOGGLE',
          detail: null
        };
      case 'pause':
        return {
          platformEvent: 'pause',
          semanticCommand: 'STOP',
          toggleModeCommand: 'TOGGLE',
          detail: null
        };
      case 'stop':
        return {
          platformEvent: 'pause',
          semanticCommand: 'STOP',
          toggleModeCommand: 'STOP',
          detail: 'avsession_stop_mapped_to_pause'
        };
      case 'playNext':
        return {
          platformEvent: 'next',
          semanticCommand: 'IGNORE',
          toggleModeCommand: 'IGNORE',
          detail: null
        };
      case 'playPrevious':
        return {
          platformEvent: 'previous',
          semanticCommand: 'IGNORE',
          toggleModeCommand: 'IGNORE',
          detail: null
        };
      default:
        return {
          platformEvent: 'toggle',
          semanticCommand: 'IGNORE',
          toggleModeCommand: 'IGNORE',
          detail: `unmapped_avsession_command:${cmd ?? 'null'}`
        };
    }
  }

  private mapKeyEvent(keyCode: number | null, action: 'down' | 'up' | 'unknown' | null): MappedPlatformEvent {
    if (action === 'up') {
      return {
        platformEvent: 'toggle',
        semanticCommand: 'IGNORE',
        toggleModeCommand: 'IGNORE',
        detail: `key_up_ignored:${keyCode ?? -1}`
      };
    }

    switch (keyCode) {
      case KEYCODE_MEDIA_PLAY_PAUSE:
        return {
          platformEvent: 'toggle',
          semanticCommand: 'TOGGLE',
          toggleModeCommand: 'TOGGLE',
          detail: 'avsession_key_10_toggle'
        };
      case KEYCODE_MEDIA_PLAY:
        return {
          platformEvent: 'play',
          semanticCommand: 'START',
          toggleModeCommand: 'TOGGLE',
          detail: null
        };
      case KEYCODE_MEDIA_PAUSE:
        return {
          platformEvent: 'pause',
          semanticCommand: 'STOP',
          toggleModeCommand: 'TOGGLE',
          detail: null
        };
      case KEYCODE_MEDIA_STOP:
        return {
          platformEvent: 'pause',
          semanticCommand: 'STOP',
          toggleModeCommand: 'STOP',
          detail: 'avsession_key_11_stop_mapped_to_pause'
        };
      case KEYCODE_MEDIA_NEXT:
        return {
          platformEvent: 'next',
          semanticCommand: 'IGNORE',
          toggleModeCommand: 'IGNORE',
          detail: null
        };
      case KEYCODE_MEDIA_PREVIOUS:
        return {
          platformEvent: 'previous',
          semanticCommand: 'IGNORE',
          toggleModeCommand: 'IGNORE',
          detail: null
        };
      default:
        return {
          platformEvent: 'toggle',
          semanticCommand: 'IGNORE',
          toggleModeCommand: 'IGNORE',
          detail: `unmapped_key_code:${keyCode ?? -1}`
        };
    }
  }
}
