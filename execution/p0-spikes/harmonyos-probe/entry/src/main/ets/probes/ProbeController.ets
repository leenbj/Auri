import { common } from '@kit.AbilityKit';
import { deviceInfo } from '@kit.BasicServicesKit';
import type { BusinessCommand, CaptureState, ControlMappingMode } from '../logic/ProbeTypes';
import { AVSessionProbe, PlaybackStateContrast } from './AVSessionProbe';
import { CaptureProbe } from './CaptureProbe';
import { LifecycleProbe } from './LifecycleProbe';
import { MdnsProbe } from './MdnsProbe';
import { ProbeLogger } from './ProbeLogger';

/**
 * Coordinates the throwaway HarmonyOS probe modules and surfaces state to the minimal UI.
 */
export class ProbeController {
  private static instance: ProbeController | null = null;

  readonly logger: ProbeLogger = ProbeLogger.getInstance();
  readonly lifecycle: LifecycleProbe = new LifecycleProbe();
  readonly avSession: AVSessionProbe = new AVSessionProbe();
  readonly capture: CaptureProbe = new CaptureProbe();
  readonly mdns: MdnsProbe = new MdnsProbe();

  private context: common.UIAbilityContext | null = null;
  private initialized = false;

  static getInstance(): ProbeController {
    if (ProbeController.instance === null) {
      ProbeController.instance = new ProbeController();
    }
    return ProbeController.instance;
  }

  init(context: common.UIAbilityContext): void {
    this.context = context;
    if (this.initialized) {
      return;
    }
    this.initialized = true;

    this.logger.init(context);
    this.logger.setAppState('launching');
    this.capture.init(context);
    this.lifecycle.start();

    this.avSession.setOnCommand((command: BusinessCommand, platformCommand: string) => {
      void this.capture.apply(command, platformCommand);
    });

    this.logger.log('app_launch', {
      os_full_name: deviceInfo.osFullName,
      sdk_api_version: deviceInfo.sdkApiVersion,
      market_name: deviceInfo.marketName,
      product_model: deviceInfo.productModel
    });

    void this.capture.requestMicPermission();
  }

  async setArmed(armed: boolean): Promise<boolean> {
    if (this.context === null) {
      return false;
    }
    if (armed) {
      await this.capture.setControlMode(true);
      const ok = await this.avSession.arm(this.context);
      if (!ok) {
        await this.capture.setControlMode(false);
        return false;
      }
      return true;
    }

    await this.capture.setControlMode(false);
    if (this.capture.getState() === 'DISARMED') {
      await this.avSession.disarm();
      return true;
    }
    return false;
  }

  setMapping(mode: ControlMappingMode): void {
    this.avSession.setMapping(mode);
  }

  async setPlaybackStateContrast(mode: PlaybackStateContrast): Promise<void> {
    await this.avSession.setPlaybackStateContrast(mode);
  }

  setUseBackgroundTask(enabled: boolean): void {
    this.capture.setUseBackgroundTask(enabled);
  }

  async manualStart(): Promise<void> {
    await this.capture.setControlMode(true);
    await this.capture.start('manual_screen_button');
  }

  async manualStop(): Promise<void> {
    await this.capture.stop('manual_screen_button');
  }

  startMdns(): void {
    if (this.context !== null) {
      this.mdns.start(this.context);
    }
  }

  stopMdns(): void {
    this.mdns.stop();
  }

  markGesture(gesture: string): void {
    this.logger.log('user_marker', {
      gesture,
      capture_state: this.capture.getState()
    });
  }

  getCaptureState(): CaptureState {
    return this.capture.getState();
  }
}
