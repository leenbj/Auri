import { abilityAccessCtrl, bundleManager, common, Permissions } from '@kit.AbilityKit';
import { audio } from '@kit.AudioKit';
import { BusinessError, deviceInfo } from '@kit.BasicServicesKit';
import { fileIo } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import { RouteEvidenceGate } from '../logic/RouteEvidenceGate';
import type { BusinessCommand, CaptureState, ObservedAudioDevice } from '../logic/ProbeTypes';
import { BackgroundTaskProbe } from './BackgroundTaskProbe';
import { ProbeLogger } from './ProbeLogger';

const SAMPLE_RATE_HZ = 16000;
const CHANNELS = 1;
const BYTES_PER_SAMPLE = 2;

interface LogDevice extends Record<string, string | number | boolean | null> {
  id: number;
  device_type_code: number;
  device_type: string;
  role: string;
  display_name: string;
  address: string;
}

/**
 * Strict Bluetooth-only capture and route-evidence probe for HarmonyOS NEXT.
 * - Never falls back to built-in phone microphone.
 * - Quarantines initial frames in memory before `getCurrentInputDevices()` verifies Bluetooth input.
 * - Immediately closes gate and stops capture on route change away from Bluetooth.
 */
export class CaptureProbe {
  private readonly routeGate: RouteEvidenceGate = new RouteEvidenceGate();
  private readonly bgTask: BackgroundTaskProbe = new BackgroundTaskProbe();
  private readonly audioManager: audio.AudioManager = audio.getAudioManager();
  private readonly routingManager: audio.AudioRoutingManager = this.audioManager.getRoutingManager();
  private readonly streamManager: audio.AudioStreamManager = this.audioManager.getStreamManager();
  private readonly sessionManager: audio.AudioSessionManager = this.audioManager.getSessionManager();

  private context: common.UIAbilityContext | null = null;
  private capturer: audio.AudioCapturer | null = null;
  private pcmFile: fileIo.File | null = null;
  private state: CaptureState = 'DISARMED';
  private useBackgroundTask = true;

  private sessionId = '';
  private activeGeneration = 0;
  private activeEpoch = 0;
  private acceptedBytes = 0;
  private quarantinedBytes = 0;
  private discardedBytes = 0;
  private writeFailures = 0;
  private firstAcceptedMono = 0;
  private lastAcceptedMono = 0;
  private stopInFlight = false;
  private listenersRegistered = false;
  private backgroundStart: Promise<boolean> | null = null;

  private onStateChangeCallback: ((state: CaptureState, sessionId: string) => void) | null = null;

  setOnStateChange(callback: ((state: CaptureState, sessionId: string) => void) | null): void {
    this.onStateChangeCallback = callback;
  }

  setUseBackgroundTask(enabled: boolean): void {
    this.useBackgroundTask = enabled;
    ProbeLogger.getInstance().log('bg_task_option_changed', { enabled });
  }

  getState(): CaptureState {
    return this.state;
  }

  init(context: common.UIAbilityContext): void {
    this.context = context;
    this.registerGlobalRouteListeners();
  }

  async setControlMode(armed: boolean): Promise<void> {
    if (armed) {
      if (this.state === 'DISARMED') {
        this.setState('ARMED');
      }
      return;
    }
    if (this.state === 'RECORDING' || this.state === 'FINALIZING') {
      ProbeLogger.getInstance().log('disable_control_mode_rejected', { state: this.state });
      return;
    }
    if (this.state === 'ROUTING') {
      await this.cancelRoutingAndCleanup('disable_control_mode');
    }
    this.setState('DISARMED');
  }

  async requestMicPermission(): Promise<boolean> {
    const logger = ProbeLogger.getInstance();
    if (this.context === null) {
      logger.log('mic_permission_error', { reason: 'missing_context' });
      return false;
    }

    const permissions: Array<Permissions> = ['ohos.permission.MICROPHONE'];
    const atManager = abilityAccessCtrl.createAtManager();

    try {
      const bundleFlags = bundleManager.BundleFlag.GET_BUNDLE_INFO_WITH_APPLICATION;
      const bundleInfo = await bundleManager.getBundleInfoForSelf(bundleFlags);
      const tokenId = bundleInfo.appInfo.accessTokenId;
      const grantStatus = atManager.checkAccessTokenSync(tokenId, 'ohos.permission.MICROPHONE');
      if (grantStatus === abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED) {
        logger.log('mic_permission', { granted: true, source: 'already_granted' });
        return true;
      }

      const result = await atManager.requestPermissionsFromUser(this.context, permissions);
      const granted = result.authResults.length > 0 && result.authResults[0] === 0;
      logger.log('mic_permission', { granted, source: 'user_prompt' });
      return granted;
    } catch (err) {
      const bizErr = err as BusinessError;
      logger.log('mic_permission_error', { code: bizErr.code, message: bizErr.message });
      return false;
    }
  }

  async apply(command: BusinessCommand, platformCommand: string): Promise<void> {
    const logger = ProbeLogger.getInstance();
    if (this.state === 'DISARMED') {
      logger.log('command_ignored_disarmed', { command, platform_command: platformCommand });
      return;
    }

    if ((command === 'START' || command === 'TOGGLE') && (this.state === 'ARMED' || this.state === 'BLOCKED')) {
      await this.start(platformCommand);
      return;
    }

    if (command === 'STOP' || command === 'TOGGLE') {
      if (this.state === 'ROUTING') {
        await this.cancelRoutingAndCleanup(`cancel_${platformCommand}`);
        return;
      }
      if (this.state === 'RECORDING') {
        await this.stop(`command_${platformCommand}`);
        return;
      }
    }

    if (command === 'START' && this.state === 'FINALIZING') {
      logger.log('command_ignored_busy', { command, platform_command: platformCommand });
      return;
    }
    if (command === 'TOGGLE' && this.state === 'FINALIZING') {
      logger.log('toggle_discarded_stale', { platform_command: platformCommand });
      return;
    }
    logger.log('command_noop', { command, state: this.state, platform_command: platformCommand });
  }

  async start(trigger: string): Promise<void> {
    const logger = ProbeLogger.getInstance();
    if (this.state !== 'ARMED' && this.state !== 'BLOCKED') {
      logger.log('capture_start_ignored', { state: this.state, trigger });
      return;
    }
    if (this.context === null) {
      await this.block('missing_ability_context');
      return;
    }

    this.sessionId = util.generateRandomUUID(true);
    this.activeGeneration = this.routeGate.beginRouting(this.sessionId);
    const genAtStart = this.activeGeneration;
    this.activeEpoch = 0;
    this.acceptedBytes = 0;
    this.quarantinedBytes = 0;
    this.discardedBytes = 0;
    this.writeFailures = 0;
    this.firstAcceptedMono = 0;
    this.lastAcceptedMono = 0;
    this.setState('ROUTING');

    const micGranted = await this.requestMicPermission();
    if (!this.isCurrentRouting(genAtStart)) {
      return;
    }
    if (!micGranted) {
      await this.block('PERMISSION_MIC_DENIED');
      return;
    }

    const snapshotBefore = await this.queryAvailableInputDevices();
    if (!this.isCurrentRouting(genAtStart)) {
      return;
    }
    logger.log('capture_start_requested', {
      trigger,
      session_id: this.sessionId,
      generation: this.activeGeneration,
      sdk_api_version: deviceInfo.sdkApiVersion,
      available_inputs: this.toLogDevices(snapshotBefore)
    });

    const bluetoothCandidate = this.findBluetoothInputCandidate(snapshotBefore);
    if (bluetoothCandidate === null) {
      await this.block('no_bluetooth_input_available');
      return;
    }

    // API 21+ (HarmonyOS 6.0.1+): select media input device explicitly via public AudioSessionManager API.
    if (deviceInfo.sdkApiVersion >= 21) {
      await this.trySelectMediaInputApi21(bluetoothCandidate.id);
    } else {
      logger.log('api21_input_selection_unavailable', {
        sdk_api_version: deviceInfo.sdkApiVersion,
        note: 'AudioSessionManager.selectMediaInputDevice requires API 21+; relying on runtime route verification'
      });
    }
    if (!this.isCurrentRouting(genAtStart)) {
      return;
    }

    if (this.useBackgroundTask && this.context !== null) {
      const pending = this.bgTask.startRecordingTask(this.context);
      this.backgroundStart = pending;
      await pending;
      if (this.backgroundStart === pending) {
        this.backgroundStart = null;
      }
      if (!this.isCurrentRouting(genAtStart)) {
        return;
      }
    }

    const streamInfo: audio.AudioStreamInfo = {
      samplingRate: audio.AudioSamplingRate.SAMPLE_RATE_16000,
      channels: audio.AudioChannel.CHANNEL_1,
      sampleFormat: audio.AudioSampleFormat.SAMPLE_FORMAT_S16LE,
      encodingType: audio.AudioEncodingType.ENCODING_TYPE_RAW
    };
    const capturerInfo: audio.AudioCapturerInfo = {
      source: audio.SourceType.SOURCE_TYPE_MIC,
      capturerFlags: 0
    };
    const capturerOptions: audio.AudioCapturerOptions = {
      streamInfo,
      capturerInfo
    };

    try {
      const cap = await audio.createAudioCapturer(capturerOptions);
      if (!this.isCurrentRouting(genAtStart)) {
        await cap.release();
        logger.log('stale_capturer_released_after_cancel', { generation: genAtStart });
        return;
      }

      this.capturer = cap;
      cap.on('readData', (buffer: ArrayBuffer) => {
        this.consumeAudioBuffer(genAtStart, buffer);
      });
      cap.on('audioInterrupt', (interruptEvent: audio.InterruptEvent) => {
        logger.log('audio_interruption', {
          force_type: interruptEvent.forceType,
          hint_type: interruptEvent.hintType,
          state: this.state
        });
        if (
          interruptEvent.forceType === audio.InterruptForceType.INTERRUPT_FORCE &&
          (interruptEvent.hintType === audio.InterruptHint.INTERRUPT_HINT_PAUSE ||
            interruptEvent.hintType === audio.InterruptHint.INTERRUPT_HINT_STOP) &&
          this.state === 'RECORDING'
        ) {
          void this.stop('system_interruption');
        }
      });

      await cap.start();
      if (!this.isCurrentRouting(genAtStart)) {
        if (this.capturer === cap) {
          await this.teardownCapturer();
        }
        logger.log('stale_start_cancelled_before_route_check', { generation: genAtStart });
        return;
      }

      // Route evidence check AFTER capturer starts: initial frames stay in memory quarantine until verified.
      const observedRaw = cap.getCurrentInputDevices();
      const observed = this.mapDescriptors(observedRaw);
      const verification = this.routeGate.evaluateObservedInput(genAtStart, observed);

      logger.log('route_verification_evaluated', {
        session_id: this.sessionId,
        generation: verification.generation,
        epoch: verification.epoch,
        observed_route_type: verification.observed_route_type,
        verification_state: verification.verification_state,
        gate_open: verification.gate_open,
        rejection_reason: verification.rejection_reason,
        contract_gap_note: verification.contract_gap_note,
        observed_devices: this.toLogDevices(observed)
      });

      if (!verification.gate_open || verification.verification_state !== 'verified') {
        await this.teardownCapturer();
        await this.block(verification.rejection_reason ?? 'INPUT_ROUTE_UNVERIFIED');
        return;
      }

      const pcmPath = `${this.context.filesDir}/rec-${this.sessionId}.pcm`;
      this.pcmFile = fileIo.openSync(
        pcmPath,
        fileIo.OpenMode.CREATE | fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.TRUNC
      );
      this.activeEpoch = verification.epoch;
      this.setState('RECORDING');

      logger.log('capture_started', {
        session_id: this.sessionId,
        route_epoch: this.activeEpoch,
        route_verified_after_capturer_start: true,
        observed_route_type: verification.observed_route_type,
        sample_rate: SAMPLE_RATE_HZ,
        channels: CHANNELS,
        quarantined_bytes_discarded: this.quarantinedBytes
      });
    } catch (err) {
      if (!this.isCurrentRouting(genAtStart)) {
        return;
      }
      const bizErr = err as BusinessError;
      await this.teardownCapturer();
      await this.block('capturer_start_failed', bizErr);
    }
  }

  async stop(reason: string): Promise<void> {
    const logger = ProbeLogger.getInstance();
    if (this.stopInFlight) {
      return;
    }
    if (this.state !== 'RECORDING' && this.state !== 'ROUTING') {
      logger.log('capture_stop_ignored', { state: this.state, reason });
      return;
    }
    this.stopInFlight = true;
    try {
      if (this.state === 'ROUTING') {
        await this.cancelRoutingAndCleanup(reason);
        return;
      }

      this.setState('FINALIZING');
      this.routeGate.closeGate();
      await this.teardownCapturer();
      if (this.context !== null) {
        await this.bgTask.stopRecordingTask(this.context);
      }

      const bytesPerSec = SAMPLE_RATE_HZ * CHANNELS * BYTES_PER_SAMPLE;
      const acceptedDurationMs = bytesPerSec > 0 ? Math.floor((this.acceptedBytes / bytesPerSec) * 1000) : 0;

      logger.log('capture_stopped', {
        session_id: this.sessionId,
        reason,
        accepted_bytes: this.acceptedBytes,
        quarantined_bytes: this.quarantinedBytes,
        discarded_bytes: this.discardedBytes,
        accepted_duration_ms: acceptedDurationMs,
        first_accepted_mono_ns: this.firstAcceptedMono,
        last_accepted_mono_ns: this.lastAcceptedMono,
        write_failures: this.writeFailures
      });

      this.setState('ARMED');
    } finally {
      this.stopInFlight = false;
    }
  }

  private async cancelRoutingAndCleanup(reason: string): Promise<void> {
    this.routeGate.cancelRouting(this.activeGeneration);
    await this.teardownCapturer();
    if (this.backgroundStart !== null) {
      await this.backgroundStart;
    }
    if (this.context !== null) {
      await this.bgTask.stopRecordingTask(this.context);
    }
    ProbeLogger.getInstance().log('capture_routing_cancelled', {
      session_id: this.sessionId,
      reason,
      quarantined_bytes_discarded: this.quarantinedBytes
    });
    this.setState('ARMED');
  }

  private consumeAudioBuffer(generation: number, buffer: ArrayBuffer): void {
    const decision = this.routeGate.acceptFrame(generation, this.activeEpoch);
    if (decision === 'quarantined') {
      this.quarantinedBytes += buffer.byteLength;
      return;
    }
    if (decision === 'discarded' || this.pcmFile === null) {
      this.discardedBytes += buffer.byteLength;
      return;
    }

    try {
      fileIo.writeSync(this.pcmFile.fd, buffer);
      this.acceptedBytes += buffer.byteLength;
      const now = ProbeLogger.monoNanos();
      if (this.firstAcceptedMono === 0) {
        this.firstAcceptedMono = now;
      }
      this.lastAcceptedMono = now;
    } catch {
      this.writeFailures += 1;
    }
  }

  private registerGlobalRouteListeners(): void {
    if (this.listenersRegistered) {
      return;
    }
    this.listenersRegistered = true;
    const logger = ProbeLogger.getInstance();

    try {
      this.routingManager.on('deviceChange', audio.DeviceFlag.INPUT_DEVICES_FLAG, (action: audio.DeviceChangeAction) => {
        const mapped = this.mapDescriptors(action.deviceDescriptors);
        logger.log('audio_routing_device_change', {
          change_type: action.type,
          state: this.state,
          devices: this.toLogDevices(mapped)
        });
        if (this.state === 'RECORDING' && this.capturer !== null) {
          this.verifyActiveCapturerRoute();
        }
      });
    } catch (err) {
      const bizErr = err as BusinessError;
      logger.log('routing_device_change_subscribe_failed', { code: bizErr.code, message: bizErr.message });
    }

    try {
      this.streamManager.on('audioCapturerChange', (infos: Array<audio.AudioCapturerChangeInfo>) => {
        const observed: ObservedAudioDevice[] = [];
        for (let i = 0; i < infos.length; i += 1) {
          const item = infos[i];
          if (item.deviceDescriptors) {
            const converted = this.mapDescriptors(item.deviceDescriptors);
            for (let j = 0; j < converted.length; j += 1) {
              observed.push(converted[j]);
            }
          }
        }
        logger.log('audio_capturer_stream_change', {
          state: this.state,
          stream_count: infos.length,
          devices: this.toLogDevices(observed)
        });
        if (this.state === 'RECORDING' && this.capturer !== null) {
          this.verifyActiveCapturerRoute();
        }
      });
    } catch (err) {
      const bizErr = err as BusinessError;
      logger.log('capturer_stream_change_subscribe_failed', { code: bizErr.code, message: bizErr.message });
    }

    if (deviceInfo.sdkApiVersion >= 21) {
      try {
        this.sessionManager.on('currentInputDeviceChanged', (event: audio.CurrentInputDeviceChangedEvent) => {
          const mapped = this.mapDescriptors(event.devices);
          logger.log('session_current_input_device_changed', {
            change_reason: event.changeReason,
            state: this.state,
            devices: this.toLogDevices(mapped)
          });
          if (this.state === 'RECORDING' || this.state === 'ROUTING') {
            const transition = this.routeGate.onRouteChanged(mapped);
            this.activeEpoch = transition.epoch;
            if (transition.shouldStop) {
              logger.log('gate_closed_route_not_bluetooth', {
                observed_route_type: transition.verification.observed_route_type,
                devices: this.toLogDevices(mapped)
              });
              void this.stop('route_lost');
            }
          }
        });
      } catch (err) {
        const bizErr = err as BusinessError;
        logger.log('session_input_change_subscribe_failed', { code: bizErr.code, message: bizErr.message });
      }
    }
  }

  private verifyActiveCapturerRoute(): void {
    if (this.capturer === null) {
      return;
    }
    try {
      const observed = this.mapDescriptors(this.capturer.getCurrentInputDevices());
      const transition = this.routeGate.onRouteChanged(observed);
      this.activeEpoch = transition.epoch;
      if (transition.shouldStop) {
        ProbeLogger.getInstance().log('gate_closed_route_not_bluetooth', {
          observed_route_type: transition.verification.observed_route_type,
          devices: this.toLogDevices(observed)
        });
        void this.stop('route_lost');
      }
    } catch (err) {
      const bizErr = err as BusinessError;
      ProbeLogger.getInstance().log('active_route_query_failed', {
        code: bizErr.code,
        message: bizErr.message
      });
      this.routeGate.closeGate();
      void this.stop('route_lost');
    }
  }

  private async queryAvailableInputDevices(): Promise<ObservedAudioDevice[]> {
    if (deviceInfo.sdkApiVersion >= 21) {
      try {
        const mediaDevices = this.sessionManager.getAvailableDevices(audio.DeviceUsage.MEDIA_INPUT_DEVICES);
        return this.mapDescriptors(mediaDevices);
      } catch (err) {
        const bizErr = err as BusinessError;
        ProbeLogger.getInstance().log('session_get_available_devices_failed', {
          code: bizErr.code,
          message: bizErr.message
        });
      }
    }

    try {
      const routingDevices = await this.routingManager.getDevices(audio.DeviceFlag.INPUT_DEVICES_FLAG);
      return this.mapDescriptors(routingDevices);
    } catch (err) {
      const bizErr = err as BusinessError;
      ProbeLogger.getInstance().log('routing_get_devices_failed', {
        code: bizErr.code,
        message: bizErr.message
      });
      return [];
    }
  }

  private async trySelectMediaInputApi21(targetDeviceId: number): Promise<void> {
    const logger = ProbeLogger.getInstance();
    try {
      await this.sessionManager.setBluetoothAndNearlinkPreferredRecordCategory(
        audio.BluetoothAndNearlinkPreferredRecordCategory.PREFERRED_DEFAULT
      );
      const available = this.sessionManager.getAvailableDevices(audio.DeviceUsage.MEDIA_INPUT_DEVICES);
      for (let i = 0; i < available.length; i += 1) {
        if (available[i].id === targetDeviceId) {
          await this.sessionManager.selectMediaInputDevice(available[i]);
          logger.log('session_select_media_input_succeeded', {
            device_id: available[i].id,
            device_type: available[i].deviceType
          });
          return;
        }
      }
      logger.log('session_select_media_input_candidate_missing', { target_device_id: targetDeviceId });
    } catch (err) {
      const bizErr = err as BusinessError;
      logger.log('session_select_media_input_failed', {
        code: bizErr.code,
        message: bizErr.message
      });
    }
  }

  private findBluetoothInputCandidate(devices: ObservedAudioDevice[]): ObservedAudioDevice | null {
    for (let i = 0; i < devices.length; i += 1) {
      const dev = devices[i];
      if (dev.deviceTypeCode === 7 || dev.deviceTypeCode === 26 || dev.deviceTypeCode === 31) {
        return dev;
      }
    }
    return null;
  }

  private mapDescriptors(descriptors: audio.AudioDeviceDescriptors): ObservedAudioDevice[] {
    const out: ObservedAudioDevice[] = [];
    for (let i = 0; i < descriptors.length; i += 1) {
      const d = descriptors[i];
      const code: number = d.deviceType as number;
      out.push({
        id: d.id,
        deviceTypeCode: code,
        deviceTypeName: this.deviceTypeLabel(code),
        deviceRole: d.deviceRole === audio.DeviceRole.INPUT_DEVICE ? 'input' : 'output',
        displayName: d.displayName ?? d.name ?? '',
        address: d.address ?? '',
        sampleRates: d.sampleRates ?? [],
        channelCounts: d.channelCounts ?? []
      });
    }
    return out;
  }

  private deviceTypeLabel(code: number): string {
    switch (code) {
      case 3:
        return 'WIRED_HEADSET';
      case 4:
        return 'WIRED_HEADPHONES';
      case 7:
        return 'BLUETOOTH_SCO';
      case 8:
        return 'BLUETOOTH_A2DP';
      case 15:
        return 'MIC';
      case 22:
        return 'USB_HEADSET';
      case 26:
        return 'BLE_HEADSET';
      case 31:
        return 'NEARLINK';
      default:
        return `DEVICE_TYPE_${code}`;
    }
  }

  private toLogDevices(
    devices: ObservedAudioDevice[]
  ): LogDevice[] {
    const out: LogDevice[] = [];
    for (let i = 0; i < devices.length; i += 1) {
      const d = devices[i];
      out.push({
        id: d.id,
        device_type_code: d.deviceTypeCode,
        device_type: d.deviceTypeName,
        role: d.deviceRole,
        display_name: d.displayName,
        address: d.address
      });
    }
    return out;
  }

  private async teardownCapturer(): Promise<void> {
    if (this.capturer !== null) {
      const current = this.capturer;
      this.capturer = null;
      try {
        current.off('readData');
        current.off('audioInterrupt');
      } catch {
        // Ignore cleanup errors on already-released capturer.
      }
      try {
        await current.stop();
      } catch {
        // Ignore stop errors if capturer never started.
      }
      try {
        await current.release();
      } catch {
        // Ignore release errors.
      }
    }

    if (this.pcmFile !== null) {
      try {
        fileIo.closeSync(this.pcmFile);
      } catch {
        // Ignore close error.
      }
      this.pcmFile = null;
    }
  }

  private async block(reason: string, error?: BusinessError): Promise<void> {
    this.routeGate.closeGate();
    if (this.context !== null) {
      await this.bgTask.stopRecordingTask(this.context);
    }
    this.setState('BLOCKED');
    ProbeLogger.getInstance().log('capture_blocked', {
      reason,
      session_id: this.sessionId,
      error_code: error ? error.code : null,
      error_message: error ? error.message : null
    });
  }

  private setState(next: CaptureState): void {
    this.state = next;
    if (this.onStateChangeCallback !== null) {
      this.onStateChangeCallback(next, this.sessionId);
    }
  }

  private isCurrentRouting(generation: number): boolean {
    return this.state === 'ROUTING' && generation === this.routeGate.currentGeneration();
  }
}
