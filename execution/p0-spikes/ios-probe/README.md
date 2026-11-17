# DEV-004 iOS 控制/路由/后台激活探针（含音量手势停止复测）

一次性探针，不是产品代码。目的是用真机回答 PRD 第 4、6、7 章及 [ADR-0014](../../../docs/adr/0014-ios-stop-via-volume-gesture.md) / [ADR-0021](../../../docs/adr/0021-ios-volume-gesture-stop-rule.md) 的问题，并留下可复现的 E3 证据。探针只用公开 API：不播放任何音频保活，不伪造通话（不用 CallKit / PushToTalk），不使用私有 API 或华为私有 BLE 协议，蓝牙路由证据不满足时拒绝录音，不回退手机麦克风。

## 固定工具链与依赖版本

- **Xcode**：`27.1`（`27A9275`）
- **iOS SDK**：`27.1`（Deployment Target：`iOS 18.0`，`SWIFT_VERSION: 5.0`）
- **XcodeGen**：`2.46.0`（`xcodegen.zip` sha256：`4d9e34b62172d645eed6457cac13fc222569974098ef4ee9c3368bedf0196806`）
- **日志汇总脚本**：Python `3.10+`（仅标准库）；单元测试运行依赖版本固定在 `tests/contract/requirements.txt`
- **构建产物说明**：`.xcodeproj` 与 `Info.plist` 由 `project.yml` 生成，不入库

## 公开 API 参考与未核实项

- `AVAudioSession.outputVolume`（只读 `Float`，`0.0`–`1.0`，通过 KVO 观测）：Apple 官方文档（`developer.apple.com/documentation/avfaudio/avaudiosession/outputvolume`）。注：`BluetoothHFP` 录音状态下到顶/到底的精确边界事件行为、以及 iOS 26.7.1 锁屏/后台 KVO 送达稳定性以真机实测为准（官方文档未写明具体硬件边界，标为**未核实**）。
- `AVAudioSession.CategoryOptions.allowBluetoothHFP`：Apple 官方文档（`developer.apple.com/documentation/avfaudio/avaudiosession/categoryoptions-swift.struct/allowbluetoothhfp`），取代已废弃的 `allowBluetooth`。
- `UIBackgroundModes: [audio]`：属于 `Info.plist` 配置键而非 `.entitlements` 加密权限键；免费个人团队（Free Personal Team）在 Xcode 27.1 + iOS 26.7.1 真机上是否允许签名并维持后台/锁屏持续录音，官方页面未单独承诺，标为**未核实**，由下方步骤 1 与步骤 5/6 真机核验。
- `MPNowPlayingInfoCenter` / `MPRemoteCommandCenter`：Apple 文档中锁屏控制卡片通常绑定活跃播放会话；仅发布元数据而不播放音频时 iOS 26.7.1 锁屏是否出现停止控件标为**未核实**，由步骤 5 真机核验。
- `ActivityKit` / `AudioRecordingIntent`（`LiveActivityIntent`）：需要额外 WidgetKit Extension 与 App Group，免费个人团队的 App ID 与 App Group 自动签名限制标为**未核实**，本次探针不引入。

## 构建、离线单测与安装（Mac 执行）

前置：macOS、Xcode `27.1`、XcodeGen `2.46.0`、已登录 Apple ID、已开启开发者模式的 iPhone。

```bash
cd execution/p0-spikes/ios-probe

# 1. 生成工程（.xcodeproj 与 Info.plist 不入库）
xcodegen generate --spec project.yml --project .

# 2. 运行离线单元测试（E2 证据，仅测手势判定/路由闸门/脱敏/计数逻辑，不证明蓝牙或后台）
xcodebuild test -project AuriProbeIOS.xcodeproj -scheme AuriProbeIOS \
  -destination 'platform=iOS Simulator,name=iPhone 16'

# 3. 真机构建与安装（团队 ID 从命令行传入，不写进仓库）
xcodebuild -project AuriProbeIOS.xcodeproj -scheme AuriProbeIOS \
  -destination 'id=<设备 UDID>' -derivedDataPath /tmp/auri-probe-build \
  -allowProvisioningUpdates DEVELOPMENT_TEAM=<你的团队 ID> build

xcrun devicectl device install app --device <UDID> \
  /tmp/auri-probe-build/Build/Products/Debug-iphoneos/AuriProbeIOS.app

xcrun devicectl device process launch --device <UDID> app.auri.probe.ios
```

- 免费账号首次安装后，要在手机上信任开发者描述文件：设置 → 通用 → VPN 与设备管理。
- 若 Simulator 名称不是 `iPhone 16`，先运行 `xcrun simctl list devices available` 查看可用模拟器名称并替换 `-destination`。

## 免费个人团队后台音频能力核验（必须最先确认）

`project.yml` 已包含 `UIBackgroundModes: [audio]`。按以下两步判定其是否受免费签名阻塞：

1. **编译签名阶段**：执行上面的真机 `xcodebuild ... build`。若 Xcode 报出 `UIBackgroundModes` / Background Modes 不被当前 Personal Team 配置文件允许、或签名直接失败，则将该项记为 **`BLOCKED_EXTERNAL`**（需付费 Apple Developer 账号），停止后续后台/锁屏测试并记录报错原文（脱敏后）。
2. **真机运行阶段**：若编译装机成功，在完成下方的**步骤 5（锁屏）**和**步骤 6（后台）**后检查日志：若 App 进入后台或锁屏后音频写入立刻中止（`accepted_frames` 停止增长或报中断/写入失败），说明免费签名或系统策略阻断了后台音频，同样记为 **`BLOCKED_EXTERNAL`**（如为系统策略原因需另写明）。

## 音量往返停止复测脚本（按顺序执行）

准备：只把受测耳机连到当前 iPhone（从其他设备断开）；耳机设置滑柄为音量加/减；打开探针 App，确认 `音量往返停止（上↔下）` 为开启状态。

| 步骤 | 场景与标签 | 操作步骤 | 判定与误停/漏停标记 |
|---|---|---|---|
| **1. 签名核验** | 构建阶段 | 按上节命令用个人团队签名构建并安装到真机 | 能装机进入下一步；否则记 `BLOCKED_EXTERNAL` |
| **2. 中间音量：停止与误停对照** | `复测场景标签 = middle` | ① 用耳机或手机把音量调到中间（屏幕显示 `0.25–0.75 (middle)`）。② 点 `手动开始` 开始录音，等 >1 秒，点 `打算停`，在 2.5 秒内做 `上滑 → 下滑`（测 5 次）与 `下滑 → 上滑`（测 5 次）。③ 再开始一段录音，等 >1 秒，点 `普通调音`，只做**单向** `上滑` 1 下（等 4 秒）、连续 `上滑` 2 下（间隔 <1 秒，等 4 秒）、`下滑` 1 下（等 4 秒） | ② 每次成功停录即计为命中；若 3 秒后仍在录音，点 **`漏停了`** 再点 `手动停止`。③ 若普通调音量意外停录，立即点 **`误停了`** |
| **3. 顶部音量边界** | `复测场景标签 = top` | ① 把音量调到最大（屏幕显示 `>= 0.94 (top)`）。② 点 `手动开始`，等 >1 秒，点 `打算停`，做 **`下滑 → 上滑`**（5 次）。③ 另起一段录音，点 `普通调音`，做 1 次普通 `下滑`（以及“先误上滑再单次下滑”对照） | ② 验证顶部 `下→上` 能否稳定停录；失败则点 **`漏停了`**。③ 验证单次下滑不会误停；若误停点 **`误停了`** |
| **4. 底部音量边界** | `复测场景标签 = bottom` | ① 把音量调到最小（屏幕显示 `<= 0.07 (bottom)`）。② 点 `手动开始`，等 >1 秒，点 `打算停`，做 **`上滑 → 下滑`**（5 次）。③ 另起一段录音，点 `普通调音`，做 1 次普通 `上滑`；另观察到底后直接 `下滑` 是否仍无事件 | ② 验证底部 `上→下` 能否稳定停录；失败则点 **`漏停了`**。③ 验证单次上滑不会误停；若误停点 **`误停了`** |
| **5. 锁屏 + 锁屏停止控件检查** | `复测场景标签 = locked` | ① `Now Playing 元数据` 选 `playing`，点 `手动开始`，确认已开录。② 按电源键锁屏，持续说话 5 秒。③ **点亮锁屏界面肉眼检查**：是否出现任何媒体/录音停止按钮或控件；如有，尝试点击并观察是否停止。④ 若无按钮（或另开一段），在锁屏下做音量往返手势（顶部用 `下→上`，底部用 `上→下`，中间任一），再说话 3 秒。⑤ 解锁回 App 查看状态。⑥ 切换 `Now Playing 元数据 = off` 重复检查锁屏控件 | 录下锁屏所见控件情况（有/无、可点/不可点）。解锁后若已停为 `idle` 且日志含 `protected_data_unavailable` 后仍有帧增长与 `volume_stop_triggered`，记为通过；未停则点 **`漏停了`** + `手动停止` |
| **6. 后台运行（未锁屏）** | `复测场景标签 = background` | ① `复测场景标签` 选 `background`，点 `手动开始`。② 上滑回桌面或切到其他 App（保持亮屏不锁屏），说话 5 秒。③ 做音量往返手势，再说话 3 秒。④ 切回探针 App 查看状态 | 若已自动停为 `idle` 且后台期间 `accepted_frames` 持续增长，记为通过；未停则点 **`漏停了`** + `手动停止` |
| **7. 汇总与取回日志** | 结束收尾 | 在 App 内点一次 **`记录汇总`**，按下方命令把日志拉回 Mac 并运行统计脚本 | 用统计结果填写 `results/retest-volume-gesture-template.md` 另存为当天日期的结果文件（不要修改 `2026-10-20` 旧文件） |

## 取日志与计数脚本

探针把脱敏后的事件追加写入 App 沙盒 `Documents/probe-log.jsonl`（JSON Lines，含 `mono_ns`、`app_state`、`boundary`、`decision` 与 `retest_marker`），音频写入 `Documents/rec-<session>.caf`。

```bash
# 拉取原始日志到被 .gitignore 忽略的 results/raw/
mkdir -p execution/p0-spikes/ios-probe/results/raw
xcrun devicectl device copy from --device <UDID> --domain-type appDataContainer \
  --domain-identifier app.auri.probe.ios --source Documents/probe-log.jsonl \
  --destination execution/p0-spikes/ios-probe/results/raw/

# 统计触发停止、误停、漏停、后台事件与写入失败
python3 execution/p0-spikes/ios-probe/scripts/summarize_log.py \
  execution/p0-spikes/ios-probe/results/raw/probe-log.jsonl
```

`results/raw/` 已被 `.gitignore` 忽略：原始日志和音频不入库，入库的只有脱敏后的结论与计数。

## 历史基础场景矩阵（S1–S8，保留对照）

每个场景单独给出状态：`NOT_TESTED / SUPPORTED_FOREGROUND / SUPPORTED_RECORDING_IN_BACKGROUND / SUPPORTED_BACKGROUND_RESTART / PARTIAL / UNSUPPORTED`。未跑的保持 `NOT_TESTED`，不得当通过。

| 场景 | 操作 | 看哪些日志事件 | 对应 PRD |
|---|---|---|---|
| S1 前台待命收到什么 | 打开 Armed，映射选 `logOnly`，双击耳机 | `remote_command` 的 `platform_command`、`event_timestamp` | FR-HS-001 |
| S2 前台开始录音 | 映射选 `semantic`，双击 | `capture_start_requested` → `available_inputs` → `capture_started`，`route.inputs[].type` 为蓝牙类型 | FR-REC-001/002 |
| S3 录音中再次双击能否停止（必测） | S2 之后再双击 | 是否出现 `remote_command` 与 `capture_stopped` | FR-HS-003 第 5 步 |
| S4 录音中锁屏 | 录音中锁屏，说几秒，再停止 | `protected_data_unavailable`、`accepted_frames`、`output_volume_changed` | 第 4 章 |
| S5 停止后后台再次启动 | 停止后锁屏或回桌面，再双击 | `remote_command` 是否到达；`capture_blocked` 的 `error_code` | iOS 最大风险 |
| S6 进程终止后 | 上滑关掉 App，再双击 | 是否有任何新事件（预期没有） | 第 12 章 |
| S7 路由丢失 | 录音中把耳机断开或关机 | `route_change` → `gate_closed_route_not_bluetooth` → `capture_stopped(reason=route_lost)`，没有手机麦克风帧 | FR-REC-004 |
| S8 对照变量 | 重复 S1、S3、S5，只切换“发布 Now Playing 元数据” | 两种设置下 `remote_command` 与锁屏控件表现 | 开放问题 |

## 证据等级

- 离线单元测试（`xcodebuild test` / `pytest`）仅算 **E2**，不能证明蓝牙或后台行为。
- 探针在真机上跑出的日志才算 **E3**。模拟器、单元测试、手动编造的日志都不能替代。失败也要保留日志，并附合法对照。
