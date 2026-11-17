import { AbilityConstant, UIAbility, Want } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { ProbeController } from '../probes/ProbeController';

const DOMAIN = 0x00A1;
const TAG = 'EntryAbility';

export default class EntryAbility extends UIAbility {
  private readonly controller: ProbeController = ProbeController.getInstance();

  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    hilog.info(DOMAIN, TAG, 'EntryAbility onCreate: %{public}s', JSON.stringify(launchParam));
    this.controller.init(this.context);
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    windowStage.loadContent('pages/Index', (err) => {
      if (err && err.code) {
        hilog.error(DOMAIN, TAG, 'Failed to load content: %{public}s', JSON.stringify(err));
      }
    });
  }

  onForeground(): void {
    this.controller.lifecycle.onAbilityForeground();
  }

  onBackground(): void {
    this.controller.lifecycle.onAbilityBackground();
  }

  onDestroy(): void {
    this.controller.lifecycle.onAbilityDestroy();
  }
}
