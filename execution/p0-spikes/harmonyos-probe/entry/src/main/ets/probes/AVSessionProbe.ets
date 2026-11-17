import { common } from '@kit.AbilityKit';
import { avSession } from '@kit.AVSessionKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { Action, KeyEvent } from '@kit.InputKit';
import { util } from '@kit.ArkTS';
import { ControlNormalizer } from '../logic/ControlNormalizer';
import type { BusinessCommand, ControlMappingMode } from '../logic/ProbeTypes';
import { ProbeLogger } from './ProbeLogger';

export type PlaybackStateContrast = 'off' | 'paused' | 'playing';

/**
 * Platform control-event probe via HarmonyOS AVSession Kit (`@kit.AVSessionKit`).
 * Never plays silent audio or creates AVPlayer/AudioRenderer; playback state metadata
 * is exposed strictly as an explicit contrast toggle (like iOS S8).
 */
export class AVSessionProbe {
  private session: avSession.AVSession | null = null;
  private mapping: ControlMappingMode = 'semantic';
  private contrastMode: PlaybackStateContrast = 'off';
  private readonly normalizer: ControlNormalizer = new ControlNormalizer('harmonyos-generic-probe', 1);
  private onCommandCallback: ((command: BusinessCommand, platformCommand: string) => void) | null = null;

  setOnCommand(callback: ((command: BusinessCommand, platformCommand: string) => void) | null): void {
    this.onCommandCallback = callback;
  }

  setMapping(mode: ControlMappingMode): void {
    this.mapping = mode;
    ProbeLogger.getInstance().log('control_mapping_selected', { mapping: mode });
  }

  getMapping(): ControlMappingMode {
    return this.mapping;
  }

  async setPlaybackStateContrast(mode: PlaybackStateContrast): Promise<void> {
    this.contrastMode = mode;
    const logger = ProbeLogger.getInstance();
    if (this.session === null) {
      logger.log('avsession_contrast_deferred', { mode });
      return;
    }

    try {
      if (mode === 'off') {
        await this.session.setAVPlaybackState({
          state: avSession.PlaybackState.PLAYBACK_STATE_IDLE
        });
      } else if (mode === 'paused') {
        await this.session.setAVMetadata({
          assetId: 'auri-probe-0',
          title: 'Auri Probe',
          artist: 'Auri'
        });
        await this.session.setAVPlaybackState({
          state: avSession.PlaybackState.PLAYBACK_STATE_PAUSE
        });
      } else {
        await this.session.setAVMetadata({
          assetId: 'auri-probe-0',
          title: 'Auri Probe',
          artist: 'Auri'
        });
        await this.session.setAVPlaybackState({
          state: avSession.PlaybackState.PLAYBACK_STATE_PLAY
        });
      }
      logger.log('avsession_playback_state_set', { mode });
    } catch (err) {
      const bizErr = err as BusinessError;
      logger.log('avsession_playback_state_failed', {
        mode,
        code: bizErr.code,
        message: bizErr.message
      });
    }
  }

  async arm(context: common.UIAbilityContext): Promise<boolean> {
    const logger = ProbeLogger.getInstance();
    if (this.session !== null) {
      return true;
    }

    try {
      const created = await avSession.createAVSession(context, 'AuriProbeSession', 'audio');
      this.session = created;

      // Metadata must be set before activate() for the system controller to route media commands.
      await created.setAVMetadata({
        assetId: 'auri-probe-0',
        title: 'Auri Probe',
        artist: 'Auri'
      });

      created.on('play', () => {
        this.dispatchCommand('avsession_command', 'play', null, null);
      });
      created.on('pause', () => {
        this.dispatchCommand('avsession_command', 'pause', null, null);
      });
      created.on('stop', () => {
        this.dispatchCommand('avsession_command', 'stop', null, null);
      });
      created.on('playNext', () => {
        this.dispatchCommand('avsession_command', 'playNext', null, null);
      });
      created.on('playPrevious', () => {
        this.dispatchCommand('avsession_command', 'playPrevious', null, null);
      });
      created.on('handleKeyEvent', (event: KeyEvent) => {
        const keyCode = event.key ? event.key.code : -1;
        let actionLabel: 'down' | 'up' | 'unknown' = 'unknown';
        if (event.action === Action.DOWN) {
          actionLabel = 'down';
        } else if (event.action === Action.UP) {
          actionLabel = 'up';
        }
        const rawId = `${keyCode}-${event.pressedTime}`;
        this.dispatchKeyEvent(keyCode, actionLabel, event.action, rawId);
      });

      await this.setPlaybackStateContrast(this.contrastMode);
      await created.activate();
      logger.log('remote_armed', {
        session_id: created.sessionId,
        mapping: this.mapping,
        playback_state_contrast: this.contrastMode
      });
      return true;
    } catch (err) {
      const bizErr = err as BusinessError;
      logger.log('remote_arm_failed', {
        code: bizErr.code,
        message: bizErr.message
      });
      await this.disarm();
      return false;
    }
  }

  async disarm(): Promise<void> {
    const logger = ProbeLogger.getInstance();
    if (this.session === null) {
      return;
    }
    const current = this.session;
    this.session = null;

    try {
      current.off('play');
      current.off('pause');
      current.off('stop');
      current.off('playNext');
      current.off('playPrevious');
      current.off('handleKeyEvent');
      await current.deactivate();
      await current.destroy();
      logger.log('remote_disarmed');
    } catch (err) {
      const bizErr = err as BusinessError;
      logger.log('remote_disarm_failed', {
        code: bizErr.code,
        message: bizErr.message
      });
    }
  }

  private dispatchCommand(
    channel: 'avsession_command',
    platformCommand: string,
    keyCode: number | null,
    keyAction: 'down' | 'up' | 'unknown' | null
  ): void {
    const monoNs = ProbeLogger.monoNanos();
    const normalized = this.normalizer.normalize(
      {
        channel,
        platformCommand,
        keyCode,
        keyAction,
        monotonicNs: monoNs,
        rawEventId: `${platformCommand}-${Math.floor(monoNs / 50_000_000)}`
      },
      this.mapping,
      util.generateRandomUUID(true)
    );

    ProbeLogger.getInstance().log('remote_command', {
      channel,
      platform_command: platformCommand,
      normalized_platform_event: normalized.platform_event,
      business_command: normalized.command,
      source: normalized.source,
      sequence: normalized.sequence,
      deduplicated: normalized.deduplicated,
      unmapped_detail: normalized.unmapped_platform_detail,
      mapping: this.mapping
    });

    if (normalized.command !== 'IGNORE' && this.onCommandCallback !== null) {
      this.onCommandCallback(normalized.command, platformCommand);
    }
  }

  private dispatchKeyEvent(
    keyCode: number,
    keyAction: 'down' | 'up' | 'unknown',
    rawActionCode: number,
    rawEventId: string
  ): void {
    const monoNs = ProbeLogger.monoNanos();
    const normalized = this.normalizer.normalize(
      {
        channel: 'avsession_key_event',
        platformCommand: null,
        keyCode,
        keyAction,
        monotonicNs: monoNs,
        rawEventId
      },
      this.mapping,
      util.generateRandomUUID(true)
    );

    ProbeLogger.getInstance().log('remote_key_event', {
      channel: 'avsession_key_event',
      key_code: keyCode,
      key_action: keyAction,
      raw_action_code: rawActionCode,
      normalized_platform_event: normalized.platform_event,
      business_command: normalized.command,
      source: normalized.source,
      sequence: normalized.sequence,
      deduplicated: normalized.deduplicated,
      unmapped_detail: normalized.unmapped_platform_detail,
      mapping: this.mapping
    });

    if (normalized.command !== 'IGNORE' && this.onCommandCallback !== null) {
      this.onCommandCallback(normalized.command, `key_${keyCode}`);
    }
  }
}
