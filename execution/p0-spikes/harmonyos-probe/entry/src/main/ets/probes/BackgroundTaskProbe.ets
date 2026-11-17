import { common, wantAgent, WantAgent } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { backgroundTaskManager } from '@kit.BackgroundTasksKit';
import { ProbeLogger } from './ProbeLogger';

/**
 * Manages the public `AUDIO_RECORDING` continuous task while recording.
 * Never fakes playback or uses non-recording background modes.
 */
export class BackgroundTaskProbe {
  private active = false;

  isActive(): boolean {
    return this.active;
  }

  async startRecordingTask(context: common.UIAbilityContext): Promise<boolean> {
    const logger = ProbeLogger.getInstance();
    if (this.active) {
      return true;
    }

    const wantAgentInfo: wantAgent.WantAgentInfo = {
      wants: [
        {
          bundleName: 'app.auri.probe.harmonyos',
          abilityName: 'EntryAbility'
        }
      ],
      actionType: wantAgent.OperationType.START_ABILITY,
      requestCode: 0,
      actionFlags: [wantAgent.WantAgentFlags.UPDATE_PRESENT_FLAG]
    };

    try {
      const agentObj: WantAgent = await wantAgent.getWantAgent(wantAgentInfo);
      await backgroundTaskManager.startBackgroundRunning(
        context,
        backgroundTaskManager.BackgroundMode.AUDIO_RECORDING,
        agentObj
      );
      this.active = true;
      logger.log('bg_task_started', { mode: 'AUDIO_RECORDING' });
      return true;
    } catch (err) {
      const bizErr = err as BusinessError;
      this.active = false;
      logger.log('bg_task_start_failed', {
        mode: 'AUDIO_RECORDING',
        code: bizErr.code,
        message: bizErr.message
      });
      return false;
    }
  }

  async stopRecordingTask(context: common.UIAbilityContext): Promise<void> {
    const logger = ProbeLogger.getInstance();
    if (!this.active) {
      return;
    }
    try {
      await backgroundTaskManager.stopBackgroundRunning(context);
      this.active = false;
      logger.log('bg_task_stopped', { mode: 'AUDIO_RECORDING' });
    } catch (err) {
      const bizErr = err as BusinessError;
      logger.log('bg_task_stop_failed', {
        code: bizErr.code,
        message: bizErr.message
      });
    }
  }
}
