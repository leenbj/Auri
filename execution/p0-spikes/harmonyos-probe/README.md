# DEV-003 鸿蒙 NEXT 控制 / 路由 / 后台 / mDNS 探针

一次性 P0 探针，**不是产品代码**。用于在真机上获取 E3 证据，回答 PRD 第 4、6、7 章和 ADR-0018 的开放问题：
1. 鸿蒙 NEXT 上通过公开 `AVSession` 接口能否收到 FreeClip 2 / FreeClip 的耳机手势事件（含**蓝牙麦克风已激活后再次双击能否停止**、锁屏、后台与进程被杀场景）；
2. 系统观测到的真实音频输入路由是否能可靠区分蓝牙耳机麦克风与手机内置麦克风；
3. 局域网内 `@kit.NetworkKit` 的 `mdns` 能否发现并解析 `_auri-gateway._tcp`。

硬性约束：
- **只用公开 API**：不播放任何无声音频保活，不伪造通话，不用私有蓝牙协议。
- **严格蓝牙输入（`bluetooth_only`）**：路由证据未满足时拒绝录音并进入 `BLOCKED`，未验证的初始音频帧只停留在内存计数中并丢弃，绝不回退手机内置麦克风。
- **原始日志不入库**：`results/raw/` 已被 `.gitignore` 忽略；入库的只有脱敏后的结论报告与脱敏日志片段。

---

## 固定工具链与 SDK 版本

| 项 | 固定值 | 说明 |
|---|---|---|
| IDE 与构建模型 | DevEco Studio `6.0.1` Release（`modelVersion: "6.0.1"`） | 见 `hvigor/hvigor-config.json5` 与根目录 `oh-package.json5` |
| `runtimeOS` | `HarmonyOS` | 见根目录 `build-profile.json5` |
| `targetSdkVersion` | `6.0.1(21)` | 对应 HarmonyOS 6.0.1 Release SDK / API 21（`Ohos_sdk_public 6.0.1.112`） |
| `compatibleSdkVersion`（最低系统版本） | `5.0.0(12)` | 对应 HarmonyOS NEXT 5.0.0 / API 12；低于 API 21 的真机可安装，但 `AudioSessionManager.selectMediaInputDevice` 等 API 21 接口会在运行时按 `deviceInfo.sdkApiVersion >= 21` 跳过并记录日志 |
| 第三方依赖 | 无（`dependencies: {}`） | 仅使用系统 Kit 公开 API |

> 当前环境状态（Linux）：本机没有安装 DevEco Studio、HarmonyOS SDK、`hvigorw`、`hdc` 与真机设备，因此**构建、签名、装机与真机运行均为 `NOT_RUN`**。仅纯逻辑模块的离线单元测试在 Linux 上通过 Node v24.22.3 运行（属 E2 证据，不能证明蓝牙或后台行为）。

---

## 离线单元测试与日志脱敏（可在 Linux / Mac 运行）

纯逻辑模块位于 `entry/src/main/ets/logic/`（无 HarmonyOS SDK 依赖）：
- `ControlNormalizer.ts`：AVSession 命令 / KeyEvent / 屏幕按钮归一化与去重
- `RouteEvidenceGate.ts`：严格蓝牙路由门禁、隔离计数与 `generation` / `route_epoch` 校验
- `LogRedactor.ts`：MAC 地址末 5 位掩码、设备名与敏感字段脱敏
- `ScenarioBookkeeper.ts`：结果矩阵初始化（默认 `NOT_TESTED`）与 E3 门禁校验

在 `execution/p0-spikes/harmonyos-probe/` 目录执行：

```bash
# 运行全部离线单元测试（Node >= 24）
node --import ./tests/resolve-ts.mjs --test tests/*.test.ts

# 将从真机拉回的原始日志脱敏后再审阅
node --import ./tests/resolve-ts.mjs ./tools/redact-log.mjs \
  results/raw/probe-log.jsonl results/sanitized-probe-log.jsonl
```

---

## Mac 上构建、免费调试签名、装机与取日志步骤

前置条件：
1. 在 Mac 上安装 **DevEco Studio 6.0.1 Release**（自带 HarmonyOS 6.0.1 API 21 SDK、`hvigorw` 与 `hdc`）。
2. 在鸿蒙真机开启开发者模式与 USB 调试（设置 → 关于本机 → 连续点击软件版本开启开发者选项 → 打开 USB 调试），用 USB 数据线连接 Mac 并在手机端点击“信任此电脑”。
3. 准备一个已登录华为开发者门户的普通 **HUAWEI ID**（鸿蒙正式签名证书尚未申请，P0 探针先使用 DevEco Studio 的免费自动调试签名）。

### 第 1 步：验证真机连接与完整系统版本

```bash
# 查看已连接设备标识（如果 hdc 不在 PATH，可在 DevEco Studio 安装目录 sdk/default/openharmony/toolchains 下找到）
hdc list targets

# 读取设备系统信息（记录完整版本，不要只写 NEXT）
hdc shell param get const.product.model
hdc shell param get const.ohos.fullname
hdc shell param get const.ohos.apiversion
```

### 第 2 步：打开工程并配置免费自动调试签名

> 注意：免费调试签名仅用于本地真机调试，生成的签名文件（`.cer`、`.p7b`、`.p12`）与 `build-profile.json5` 中的 `signingConfigs` **绝对不要提交到 Git 仓库**（`.gitignore` 已忽略 `.cer`/`.p12`/`.p8` 等；如 `build-profile.json5` 被 IDE 写入本地 `signingConfigs`，提交结果前请用 `git checkout -- execution/p0-spikes/harmonyos-probe/build-profile.json5` 还原）。

1. 用 DevEco Studio 打开目录 `execution/p0-spikes/harmonyos-probe/`。
2. 等待 Sync 完成。若你的 DevEco Studio 小版本要求不同的 `modelVersion`（例如提示 `6.0.0` 与 `6.0.1` 不一致），按 IDE 提示保持 `hvigor/hvigor-config.json5` 与根目录 `oh-package.json5` 的 `modelVersion` 一致。
3. 打开菜单 **File > Project Structure... > Project > Signing Configs**。
4. 勾选 **Automatically generate signature**（若界面显示 **Support HarmonyOS** 也一并勾选），点击 **Sign In** 用 HUAWEI ID 授权登录。
5. DevEco Studio 会自动为当前 USB 连接的真机生成临时调试证书与 Profile，并写入本地 `build-profile.json5`。

### 第 3 步：构建与安装

可以直接点击 DevEco Studio 顶部绿色运行按钮（Run `'entry'`）安装到真机，或用命令行构建安装：

```bash
cd execution/p0-spikes/harmonyos-probe

# 使用项目本地配置好自动签名的配置构建 Debug HAP
hvigorw assembleHap -p buildMode=debug --no-daemon

# 确认产物路径后安装到真机（如更新安装失败可先 hdc uninstall app.auri.probe.harmonyos）
hdc install entry/build/default/outputs/default/entry-default-signed.hap

# 从命令行启动探针（也可在手机桌面点击 Auri Probe 图标启动）
hdc shell aa start -a EntryAbility -b app.auri.probe.harmonyos
```

首次运行弹出麦克风权限请求（`ohos.permission.MICROPHONE`）时，点击**允许**。

### 第 4 步：准备 mDNS 对照广播端（测 M1 用）

将手机与 Mac 连入**同一个局域网 Wi-Fi**。在 Mac 终端临时广播一个 `_auri-gateway._tcp` 服务（或启动本地 Auri Gateway），用于验证手机能否发现它：

```bash
# 在 Mac 上临时广播 _auri-gateway._tcp（仅测试用途，测完 Ctrl+C 停止）
dns-sd -R "AuriGatewayProbeTest" _auri-gateway._tcp local 8443
```

### 第 5 步：拉取真机日志与清理测试音频

探针将 JSON Lines 日志写入应用沙盒 `filesDir/probe-log.jsonl`，将通过路由验证的 PCM 写入 `filesDir/rec-<session>.pcm`。

推荐方式（DevEco Studio 图形界面，最稳妥）：
1. 打开 **View > Tool Windows > Device File Browser**。
2. 顶部下拉选择你的真机，切换到 **Application Sandbox View**。
3. 展开路径：`data > app > el2 > 100 > base > app.auri.probe.harmonyos > haps > entry > files`（若用户 ID 不是 `100`，按实际目录展开）。
4. 右键保存 `probe-log.jsonl` 到仓库的 `execution/p0-spikes/harmonyos-probe/results/raw/probe-log.jsonl`（该目录已被 `.gitignore` 忽略）。
5. 确认完成后删除沙盒内的 `rec-*.pcm` 测试录音。

命令行备选方式（视系统 Debug 沙盒权限策略而定，若直接 `hdc file recv` 提示无权限，请用上面的 Device File Browser）：

```bash
mkdir -p execution/p0-spikes/harmonyos-probe/results/raw
hdc file recv /data/app/el2/100/base/app.auri.probe.harmonyos/haps/entry/files/probe-log.jsonl \
  execution/p0-spikes/harmonyos-probe/results/raw/
```

### 第 6 步：日志脱敏与整理结果

```bash
cd execution/p0-spikes/harmonyos-probe
node --import ./tests/resolve-ts.mjs ./tools/redact-log.mjs \
  results/raw/probe-log.jsonl results/raw/sanitized-probe-log.jsonl
```

1. 检查 `results/raw/sanitized-probe-log.jsonl`（探针写入时已做过一次脱敏，脚本可做二次核对），确认无真实个人口述内容、无完整蓝牙 MAC、无个人设备昵称。
2. 复制 `results/template-harmonyos-probe-results.md` 为 `results/<YYYY-MM-DD>-<headset>-<phone>.md`，按实测日志逐场景填写结论，再提交脱敏后的 Markdown 结果文件（切勿提交 `results/raw/` 或 `.pcm` 文件）。

---

## 耳机测试剧本（FreeClip 2 与 FreeClip 分别执行）

测试前准备：
- 耳机**仅连接**当前测试的鸿蒙手机（先从 Mac、iPhone 等其他设备断开，避免多设备抢占音频通道）。
- 在华为“智慧生活 / 音频管家”中查看并记录当前耳机双击/三击/长按手势设置（出厂默认双击通常为播放/暂停）。
- 每次做完耳机手势后，尽量在 1 秒内点击界面上的 **“双击了 / 三击了 / 长按了”** 按钮打上 `user_marker` 时间戳，方便对照日志。

| 场景 | 操作步骤 | 日志核对重点 | 对应要求 |
|---|---|---|---|
| **S1 前台待命事件** | 打开 `Armed`，映射选 `logOnly`，依次测试 `AVPlaybackState` 为 `off` / `paused` / `playing`。分别做 **双击、三击、长按** 耳机。 | 查看 `remote_command` 与 `remote_key_event` 的 `platform_command`、`key_code`、`key_action`、`mono_ns`。 | FR-HS-001 |
| **S2 前台开始和停止** | 映射选 `semantic`（若 S1 发现耳机只发 `handleKeyEvent` toggle 或单事件，改选 `toggle`）。**双击**耳机开始录音，说几秒话后，记录状态是否为 `RECORDING`。 | `capture_start_requested` → `route_verification_evaluated`（确认 `observed_route_type` 为蓝牙类型、`verification_state=verified`）→ `capture_started`。 | FR-REC-001/002 |
| **S3 录音中再次手势停止（必测）** | 承接 S2 处于 `RECORDING`（蓝牙麦克风已激活，状态栏可见录音指示）时，**再次双击**耳机（并可补充三击对照）。 | 是否收到 `remote_command` / `remote_key_event` 并触发 `capture_stopped(reason=command_*)`；还是像 iOS 那样在录音通道下零事件。 | FR-HS-003 第 5 步、T-002 |
| **S4 录音中锁屏** | 保持 `RECORDING`，按手机电源键锁屏，等 5 秒并说几句话，在锁屏状态下**双击**耳机尝试停止，再亮屏解锁查看。 | `screen_common_event(SCREEN_OFF / SCREEN_LOCKED)` 之后 `accepted_bytes` 是否继续增长；锁屏期间手势命令是否到达；对照开关 `audioRecording` 长时任务。 | PRD 第 4 章、T-009 |
| **S5 停止后锁屏/后台再开始** | 在 `ARMED`（未在录音）状态下返回桌面或按电源键锁屏，等待 1 分钟（可追加 5 分钟），**双击**耳机尝试开始新录音。 | `app_did_enter_background` / `SCREEN_OFF` 之后 `remote_command` / `remote_key_event` 是否仍到达；若启动录音是否被系统拦截并记录 `capture_blocked`。 | PRD 第 4 章、T-010 |
| **S6 进程被杀后开始** | 在手机多任务界面上滑划掉 `Auri Probe` 卡片彻底关闭进程，等待数秒后**双击**耳机。 | 查看是否唤醒 App 或完全无事件（重新打开 App 看新旧 `launch_id`）。 | PRD 第 12 章、T-011 |
| **S7 路由丢失保护** | 前台开始 `RECORDING` 后，在手机控制中心关闭蓝牙或把耳机放回充电盒断连。 | 必须出现 `audio_routing_device_change` / `session_current_input_device_changed` → `gate_closed_route_not_bluetooth` → `capture_stopped(reason=route_lost)`，绝不切到手机内置麦继续写入。 | FR-REC-004、T-014 |
| **M1 局域网 mDNS** | 确保 Mac 上正在运行 `dns-sd -R ... _auri-gateway._tcp local 8443` 或 Gateway，点击界面 **“开始搜索 mDNS”**，等待 10 秒后点击停止。 | 查看 `mdns_discovery_started`、`mdns_service_found`、`mdns_service_resolved`（是否有真实局域网 IP 和端口 `8443`）。 | ADR-0018 |

---

## 官方文档引用与“未核实”清单

### 引用的官方文档与示例

1. HarmonyOS 6.0.1 Release (API 21) 版本说明与 DevEco Studio 6.0.1：`https://developer.huawei.com/consumer/cn/doc/harmonyos-releases/overview-601`（及 `build-profile.json5` / `hvigor-config.json5` 配置指南）
2. `AVSession Kit`（`@kit.AVSessionKit`：`createAVSession`、`setAVMetadata`、`setAVPlaybackState`、`on('play'|'pause'|'stop'|'playNext'|'playPrevious'|'handleKeyEvent')`、`PlaybackState`）：`https://developer.huawei.com/consumer/cn/doc/harmonyos-references/js-apis-avsession` 与 OpenHarmony 文档 `avsession-access-scene.md`
3. `Audio Kit`（`@kit.AudioKit`）：
   - `AudioSessionManager`（API 21+：`getAvailableDevices`、`selectMediaInputDevice`、`setBluetoothAndNearlinkPreferredRecordCategory`、`on('currentInputDeviceChanged')`）：HarmonyOS Audio Kit API 参考及华为示例 `managing-audio-input-devices`（`https://gitcode.com/HarmonyOS_Samples/managing-audio-input-devices/tree/dev`）
   - `AudioCapturer`（`createAudioCapturer`、`getCurrentInputDevices`、`on('readData')`、`on('audioInterrupt')`）与 `AudioRoutingManager`（`getDevices`、`on('deviceChange')`）、`AudioStreamManager`（`on('audioCapturerChange')`）
4. `Background Tasks Kit`（`@kit.BackgroundTasksKit`：`startBackgroundRunning`、`stopBackgroundRunning`、`BackgroundMode.AUDIO_RECORDING`）
5. `Network Kit`（`@kit.NetworkKit` / `@ohos.net.mdns`：`createDiscoveryService`、`startSearchingMDNS`、`resolveLocalService`、`stopSearchingMDNS`）
6. `Basic Services Kit`（`@kit.BasicServicesKit`：`systemDateTime.getUptime`、`deviceInfo`、`commonEventManager`）与 `Core File Kit`（`@kit.CoreFileKit`：`fileIo`）与 `Input Kit`（`@kit.InputKit`：`KeyEvent`、`Action`）
7. DevEco Studio 自动签名与 `hdc` / Device File Browser 调试指南

### 未核实项（需产品负责人在 Mac + DevEco Studio + 真机上核实）

以下细节因本机无 DevEco Studio / 商业 HarmonyOS SDK / 真机编译器而**未核实**，构建或首次运行时若编译器报错请同步记录并修正：

1. **未核实**：`@kit.AVSessionKit` 的 `session.on('handleKeyEvent', (event: KeyEvent) => ...)` 中，商业 SDK 6.0.1(21) 声明的 `KeyEvent` 字段究竟是 `event.key.code` + `event.action`（`@ohos.multimodalInput.keyEvent` 定义）还是 `event.keyCode`（ArkUI 简化定义）；若 DevEco Studio 报属性不存在，请以 IDE 跳转的 `.d.ts` 定义为准修改 `AVSessionProbe.ets`。
2. **未核实**：`audio.DeviceType.BLE_HEADSET` 的精确数值（代码内按 `26` 预留，仅 `WIRED_HEADSET=3`、`WIRED_HEADPHONES=4`、`BLUETOOTH_SCO=7`、`BLUETOOTH_A2DP=8`、`MIC=15`、`USB_HEADSET=22`、`NEARLINK=31` 在检索到的文档中有明确数值）；同时如果 `compatibleSdkVersion` 为 `5.0.0(12)` 而 `targetSdkVersion` 为 `6.0.1(21)`，`Code Linter` 对 API 21 接口（`getAvailableDevices`、`selectMediaInputDevice`、`setBluetoothAndNearlinkPreferredRecordCategory`、`currentInputDeviceChanged`）已设为 `warn`，但仍未核实商业 SDK 编译器是否允许在无 `@ohos` 动态导入下直接通过编译。
3. **未核实**：`hdc file recv` 能否直接从 `/data/app/el2/100/base/app.auri.probe.harmonyos/haps/entry/files/` 拉取文件，还是 HarmonyOS NEXT 6.x 真机必须通过 DevEco Studio 的 **Device File Browser (Application Sandbox View)** 导出。
4. **未核实**：免费调试签名是否允许申请 `ohos.permission.KEEP_BACKGROUND_RUNNING`（`audioRecording` 长时任务）以及是否完整支持 `@kit.NetworkKit` 的 mDNS 局域网广播发现。
5. **未核实**：`AVSessionProbe` 与 `CaptureProbe` 在真机运行 S1–S7 与 M1 的全部真实行为（包括 `KEYCODE_MEDIA_*` 实际收到的按键码、FreeClip 2 / FreeClip 在 HarmonyOS 上走 HFP 还是 NearLink、以及 `AUDIO_RECORDING` 长时任务在锁屏下的限制）。
