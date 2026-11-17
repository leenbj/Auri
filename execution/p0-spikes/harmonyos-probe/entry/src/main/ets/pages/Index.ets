import type { CaptureState, ControlMappingMode } from '../logic/ProbeTypes';
import type { PlaybackStateContrast } from '../probes/AVSessionProbe';
import { ProbeController } from '../probes/ProbeController';

@Entry
@Component
struct Index {
  private readonly controller: ProbeController = ProbeController.getInstance();

  @State armed: boolean = false;
  @State mapping: ControlMappingMode = 'semantic';
  @State playbackContrast: PlaybackStateContrast = 'off';
  @State useBgTask: boolean = true;
  @State captureState: CaptureState = 'DISARMED';
  @State mdnsStatus: string = 'idle';
  @State logTail: string[] = [];

  aboutToAppear(): void {
    this.captureState = this.controller.getCaptureState();
    this.controller.logger.setOnLine((line: string) => {
      const updated = [line, ...this.logTail];
      if (updated.length > 30) {
        updated.pop();
      }
      this.logTail = updated;
    });
    this.controller.capture.setOnStateChange((state: CaptureState, _sessionId: string) => {
      this.captureState = state;
    });
    this.controller.mdns.setOnStatusChange((status: string) => {
      this.mdnsStatus = status;
    });
  }

  aboutToDisappear(): void {
    this.controller.logger.setOnLine(null);
    this.controller.capture.setOnStateChange(null);
    this.controller.mdns.setOnStatusChange(null);
  }

  build() {
    Scroll() {
      Column({ space: 12 }) {
        Text('Auri Probe HarmonyOS NEXT')
          .fontSize(20)
          .fontWeight(FontWeight.Bold)
          .width('100%')

        // 1. AVSession Control Section
        Column({ space: 8 }) {
          Text('1. 控制（AVSession 平台事件层，不播放无声音频）')
            .fontSize(14)
            .fontWeight(FontWeight.Medium)
            .width('100%')

          Row() {
            Text('Armed（激活 AVSession 监听）').fontSize(14)
            Blank()
            Toggle({ type: ToggleType.Switch, isOn: this.armed })
              .onChange((isOn: boolean) => {
                void this.controller.setArmed(isOn).then((ok: boolean) => {
                  if (!ok && isOn) {
                    this.armed = false;
                  } else {
                    this.armed = isOn;
                  }
                });
              })
          }.width('100%')

          Row({ space: 8 }) {
            Text('命令映射:').fontSize(13)
            Button(this.mapping === 'logOnly' ? '[logOnly]' : 'logOnly')
              .fontSize(12)
              .onClick(() => {
                this.mapping = 'logOnly';
                this.controller.setMapping('logOnly');
              })
            Button(this.mapping === 'semantic' ? '[semantic]' : 'semantic')
              .fontSize(12)
              .onClick(() => {
                this.mapping = 'semantic';
                this.controller.setMapping('semantic');
              })
            Button(this.mapping === 'toggle' ? '[toggle]' : 'toggle')
              .fontSize(12)
              .onClick(() => {
                this.mapping = 'toggle';
                this.controller.setMapping('toggle');
              })
          }.width('100%')

          Row({ space: 8 }) {
            Text('AVPlaybackState 对照:').fontSize(13)
            Button(this.playbackContrast === 'off' ? '[off]' : 'off')
              .fontSize(12)
              .onClick(() => {
                this.playbackContrast = 'off';
                void this.controller.setPlaybackStateContrast('off');
              })
            Button(this.playbackContrast === 'paused' ? '[paused]' : 'paused')
              .fontSize(12)
              .onClick(() => {
                this.playbackContrast = 'paused';
                void this.controller.setPlaybackStateContrast('paused');
              })
            Button(this.playbackContrast === 'playing' ? '[playing]' : 'playing')
              .fontSize(12)
              .onClick(() => {
                this.playbackContrast = 'playing';
                void this.controller.setPlaybackStateContrast('playing');
              })
          }.width('100%')
        }
        .padding(10)
        .backgroundColor('#F5F6F8')
        .borderRadius(8)
        .width('100%')

        // 2. Capture & Route Evidence Section
        Column({ space: 8 }) {
          Text('2. 采集与严格蓝牙路由（无手机麦回退）')
            .fontSize(14)
            .fontWeight(FontWeight.Medium)
            .width('100%')

          Text(`状态: ${this.captureState}`)
            .fontSize(14)
            .fontWeight(FontWeight.Bold)
            .width('100%')

          Row() {
            Text('录音时申请 audioRecording 长时任务').fontSize(13)
            Blank()
            Toggle({ type: ToggleType.Switch, isOn: this.useBgTask })
              .onChange((isOn: boolean) => {
                this.useBgTask = isOn;
                this.controller.setUseBackgroundTask(isOn);
              })
          }.width('100%')

          Row({ space: 12 }) {
            Button('手动开始')
              .onClick(() => {
                this.armed = true;
                void this.controller.manualStart();
              })
            Button('手动停止')
              .onClick(() => {
                void this.controller.manualStop();
              })
          }.width('100%')
        }
        .padding(10)
        .backgroundColor('#F5F6F8')
        .borderRadius(8)
        .width('100%')

        // 3. mDNS LAN Check Section
        Column({ space: 8 }) {
          Text('3. 局域网 mDNS 发现 (_auri-gateway._tcp)')
            .fontSize(14)
            .fontWeight(FontWeight.Medium)
            .width('100%')

          Text(`mDNS 状态: ${this.mdnsStatus}`)
            .fontSize(13)
            .width('100%')

          Row({ space: 12 }) {
            Button('开始搜索 mDNS')
              .onClick(() => {
                this.controller.startMdns();
              })
            Button('停止搜索')
              .onClick(() => {
                this.controller.stopMdns();
              })
          }.width('100%')
        }
        .padding(10)
        .backgroundColor('#F5F6F8')
        .borderRadius(8)
        .width('100%')

        // 4. Gesture Marker Section
        Column({ space: 8 }) {
          Text('4. 手势标记（做完耳机动作后立刻点）')
            .fontSize(14)
            .fontWeight(FontWeight.Medium)
            .width('100%')

          Row({ space: 8 }) {
            Button('双击了')
              .fontSize(12)
              .onClick(() => this.controller.markGesture('double_tap'))
            Button('三击了')
              .fontSize(12)
              .onClick(() => this.controller.markGesture('triple_tap'))
            Button('长按了')
              .fontSize(12)
              .onClick(() => this.controller.markGesture('long_press'))
          }.width('100%')
        }
        .padding(10)
        .backgroundColor('#F5F6F8')
        .borderRadius(8)
        .width('100%')

        // 5. Log Tail
        Column({ space: 6 }) {
          Text(`日志尾部（最新在上，文件: ${this.controller.logger.getLogFilePath()}）`)
            .fontSize(12)
            .fontWeight(FontWeight.Medium)
            .width('100%')

          ForEach(this.logTail, (line: string, index: number) => {
            Text(`${index}: ${line}`)
              .fontSize(10)
              .fontFamily('monospace')
              .width('100%')
          }, (line: string, index: number) => `${index}-${line}`)
        }
        .padding(10)
        .backgroundColor('#F5F6F8')
        .borderRadius(8)
        .width('100%')
      }
      .padding(16)
      .width('100%')
    }
    .width('100%')
    .height('100%')
  }
}
