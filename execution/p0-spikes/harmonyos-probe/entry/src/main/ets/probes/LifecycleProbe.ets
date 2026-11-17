import { BusinessError, commonEventManager } from '@kit.BasicServicesKit';
import { ProbeLogger } from './ProbeLogger';

/**
 * Observes UIAbility foreground/background transitions and system screen on/off
 * common events so every headset/route event is stamped with real lifecycle state.
 */
export class LifecycleProbe {
  private subscriber: commonEventManager.CommonEventSubscriber | null = null;
  private screenState = 'unknown';

  getScreenState(): string {
    return this.screenState;
  }

  start(): void {
    const logger = ProbeLogger.getInstance();
    const subscribeInfo: commonEventManager.CommonEventSubscribeInfo = {
      events: [
        commonEventManager.Support.COMMON_EVENT_SCREEN_ON,
        commonEventManager.Support.COMMON_EVENT_SCREEN_OFF,
        commonEventManager.Support.COMMON_EVENT_SCREEN_LOCKED,
        commonEventManager.Support.COMMON_EVENT_SCREEN_UNLOCKED
      ]
    };

    commonEventManager.createSubscriber(
      subscribeInfo,
      (err: BusinessError, sub: commonEventManager.CommonEventSubscriber) => {
        if (err) {
          logger.log('lifecycle_screen_subscribe_failed', {
            code: err.code,
            message: err.message
          });
          return;
        }
        this.subscriber = sub;
        commonEventManager.subscribe(sub, (subErr: BusinessError, data: commonEventManager.CommonEventData) => {
          if (subErr) {
            logger.log('lifecycle_screen_event_error', {
              code: subErr.code,
              message: subErr.message
            });
            return;
          }
          const action = data.event;
          if (action === commonEventManager.Support.COMMON_EVENT_SCREEN_OFF) {
            this.screenState = 'screen_off';
          } else if (action === commonEventManager.Support.COMMON_EVENT_SCREEN_ON) {
            this.screenState = 'screen_on';
          } else if (action === commonEventManager.Support.COMMON_EVENT_SCREEN_LOCKED) {
            this.screenState = 'locked';
          } else if (action === commonEventManager.Support.COMMON_EVENT_SCREEN_UNLOCKED) {
            this.screenState = 'unlocked';
          }
          logger.log('screen_common_event', {
            event: action,
            screen_state: this.screenState
          });
        });
      }
    );
  }

  stop(): void {
    if (this.subscriber !== null) {
      commonEventManager.unsubscribe(this.subscriber, (err: BusinessError) => {
        if (err) {
          ProbeLogger.getInstance().log('lifecycle_screen_unsubscribe_failed', {
            code: err.code,
            message: err.message
          });
        }
      });
      this.subscriber = null;
    }
  }

  onAbilityForeground(): void {
    const logger = ProbeLogger.getInstance();
    logger.setAppState('foreground');
    logger.log('app_did_enter_foreground', { screen_state: this.screenState });
  }

  onAbilityBackground(): void {
    const logger = ProbeLogger.getInstance();
    logger.setAppState('background');
    logger.log('app_did_enter_background', { screen_state: this.screenState });
  }

  onAbilityDestroy(): void {
    const logger = ProbeLogger.getInstance();
    logger.setAppState('destroyed');
    logger.log('app_will_terminate', { screen_state: this.screenState });
    this.stop();
  }
}
