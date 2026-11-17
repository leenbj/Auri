# DEV-005 Android 控制 / 路由 / 前台服务探针

一次性探针工程（不是产品代码），用于在真机上回答 GitHub issue #10（`.scratch/auri-v1/issues/09-android-probe.md`）与 PRD 第 4.4、6、7、12、22 章的核心问题。

硬性约束：
- 只用公开 API，**不**播放无声音频保活，**不**伪造通话（不用 Telecom `ConnectionService`），**不**用华为私有 BLE 协议。
- 严格蓝牙输入（`bluetooth_only`）：路由证据（`AudioRecord.getRoutedDevice()`）不满足时只进内存隔离区并丢弃，拒绝落盘，**绝不**回退到手机内置麦克风。
- 分两层分别核实权限与后台条件：**第 1 层**（`RECORD_AUDIO`、`BLUETOOTH_CONNECT`、`POST_NOTIFICATIONS` 运行时权限）与 **第 2 层**（`FOREGROUND_SERVICE_TYPE_MICROPHONE` 前台服务能否在当前生命周期启动并真正持有 `microphone` 类型），不用 `checkSelfPermission` 推断后台可以开麦。
- **当前环境声明**：本工程是在无 JDK / Gradle / Android SDK / 真机的 Linux 会话中编写的。**Gradle 构建、单测与真机运行均尚未执行（`NOT_RUN`），所有兼容结论保持 `NOT_TESTED`。**

---

## 1. 固定版本清单

| 组件 | 固定版本 | 说明 |
|---|---|---|
| Gradle Wrapper | `8.10.2`（`-bin.zip`） | `distributionSha256Sum = 31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26`；`gradle-wrapper.jar` SHA-256 = `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046` |
| Android Gradle Plugin (AGP) | `8.8.0` | 官方最低要求 Gradle 8.10.2、JDK 17，支持 `compileSdk = 35` |
| Kotlin Android Plugin | `2.0.21` | `org.jetbrains.kotlin.android` |
| JDK | `17` | AGP 8.x 要求 |
| `compileSdk` / `targetSdk` / `minSdk` | `35` / `35` / `31` | 覆盖 Android 12–15；`minSdk = 31` 对应 `setCommunicationDevice` 与 `ForegroundServiceStartNotAllowedException` 的原生公开 API |
| `androidx.core:core-ktx` | `1.15.0` | 需 `compileSdk >= 35` |
| `androidx.appcompat:appcompat` | `1.7.0` | `AppCompatActivity` / `SwitchCompat` |
| `androidx.activity:activity-ktx` | `1.10.0` | 需 `compileSdk >= 35` |
| `junit:junit` | `4.13.2` | 仅用于离线 JVM 单元测试（`E2` 证据，不代表真机蓝牙/后台行为） |

> 若待测真机为 Android 11（API 30）或更低版本，当前 `minSdk = 31` 会拒绝安装，需先在评审中决定是否扩展旧 API 路径；未核实前不猜旧版行为。

---

## 2. 公开 API 依据与未核实项

本探针所用接口核对自以下官方 Android 文档（引用日期 2026-11-17）：

1. **前台服务与后台/权限双层限制**：
   - [Restrictions on starting a foreground service from the background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)（Android 14/API 34 起，即使具备后台启动豁免，`microphone` 这类 while-in-use 类型在后台启动仍会抛出 `SecurityException` 或 `ForegroundServiceStartNotAllowedException`）
   - [Foreground service types: microphone](https://developer.android.com/develop/background-work/services/fg-service-types#microphone)（需声明 `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MICROPHONE`、`RECORD_AUDIO`，且在调用 `ServiceCompat.startForeground(..., ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)` 前已获授运行时权限）
2. **媒体按键与 MediaSession**：
   - [Responding to media buttons](https://developer.android.com/media/legacy/media-buttons) / [MediaSession](https://developer.android.com/reference/android/media/session/MediaSession)（`MediaSession.Callback.onMediaButtonEvent`、`onPlay`/`onPause`/`onStop`/`onSkipToNext`/`onSkipToPrevious`、`setMediaButtonBroadcastReceiver(ComponentName)`（API 33+）与 `setMediaButtonReceiver(PendingIntent)`（API 21–32））
3. **蓝牙输入路由与实测验证**：
   - [AudioManager.setCommunicationDevice](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo))、`MODE_IN_COMMUNICATION`、`AudioDeviceInfo.TYPE_BLUETOOTH_SCO` / `TYPE_BLE_HEADSET`（替代已废弃的 `startBluetoothSco()`）
   - [AudioRouting / AudioRecord.getRoutedDevice / addOnRoutingChangedListener](https://developer.android.com/reference/android/media/AudioRecord#getRoutedDevice())（`getPreferredDevice()` 不保证等于实际输入，`getRoutedDevice()` 仅在录音活动时有效）
   - [ServiceCompat.startForeground](https://developer.android.com/reference/androidx/core/app/ServiceCompat) 与 [IntentCompat.getParcelableExtra](https://developer.android.com/reference/androidx/core/content/IntentCompat)
4. **工具链版本**：
   - [Android Gradle plugin 8.8 release notes](https://developer.android.com/build/releases/past-releases/agp-8-8-0-release-notes)
   - [Gradle 8.10.2 Checksums](https://gradle.org/release-checksums/)

### 标为“未核实”的事项（须由 Mac 真机实测确认）

- **未核实**：在无任何音频播放（`AudioTrack` / `MediaPlayer` 均未创建）的前提下，不同 Android 版本与厂商 ROM（如 HarmonyOS/EMUI、小米 HyperOS、OPPO ColorOS、三星 OneUI、原生 Pixel）是否向仅注册了 `android.media.session.MediaSession`（且分别处于 `STATE_NONE` / `STATE_PAUSED` / `STATE_PLAYING`）的 App 分发蓝牙耳机双击事件。
- **未核实**：HUAWEI FreeClip 2 与 HUAWEI FreeClip 在 Android 上开启蓝牙麦克风（SCO/HFP 或 LE Audio）后，再次双击耳机是发送 `KEYCODE_MEDIA_PLAY`/`PAUSE`/`PLAY_PAUSE`/`STOP`/`HEADSETHOOK`，还是被系统通话层拦截为零事件。
- **未核实**：`AudioSource.MIC` + `setPreferredDevice()`（不调用 `setCommunicationDevice()` 与 `MODE_IN_COMMUNICATION`）能否在真机上激活蓝牙耳机输入，还是必须使用 `setCommunicationDevice()` + `MODE_IN_COMMUNICATION` + `VOICE_COMMUNICATION`。
- **未核实**：应用退到后台、锁屏、划掉任务卡、系统回收、强行停止后，`ProbeMediaButtonReceiver` 能否收到 `ACTION_MEDIA_BUTTON` 广播，以及此时能否合法启动 `FOREGROUND_SERVICE_TYPE_MICROPHONE`。

---

## 3. 在 Mac 上构建、跑单测、装机与取日志

### 前置条件（Mac）

1. 安装 Android Studio（含 Android SDK Platform 35、Build-Tools 35.0.0、Platform-Tools `adb`）与 JDK 17。
2. 配置 `ANDROID_HOME`（通常为 `~/Library/Android/sdk`），并将 `$ANDROID_HOME/platform-tools` 加入 `PATH`。
3. 手机开启“开发者选项”与“USB 调试”，通过 USB 连上 Mac，`adb devices` 显示状态为 `device`。
4. 耳机**仅连接当前待测 Android 手机**（先断开与 Mac、iPhone 或其他手机的蓝牙连接）。

### 构建与离线单测（E2）

```bash
cd execution/p0-spikes/android-probe

# 1. 运行离线 JVM 单元测试（E2 证据，不证明真机蓝牙/后台行为）
./gradlew test

# 2. 构建 Debug APK
./gradlew assembleDebug
```

### 装机与启动

```bash
# 安装 Debug APK（加 -s <设备序列号> 可指定多台中的一台）
adb install -r execution/p0-spikes/android-probe/app/build/outputs/apk/debug/app-debug.apk

# 查看手机型号与系统版本（记录到结果模板）
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.display.id

# 启动探针
adb shell am start -n app.auri.probe.android/app.auri.probe.MainActivity
```

### 取日志（原始日志不入库）

探针把事件追加写入应用私有目录 `files/probe-log.jsonl`，录音文件写入 `files/rec-<session>.pcm`。

```bash
mkdir -p execution/p0-spikes/android-probe/results/raw

# 拉取原始 JSONL 日志（results/raw/ 已被 .gitignore 忽略）
adb shell run-as app.auri.probe.android cat files/probe-log.jsonl \
  > execution/p0-spikes/android-probe/results/raw/probe-log-$(date +%Y%m%d-%H%M%S).jsonl

# 可选：若在 App 内点击过 "Write Redacted Log Copy"，也可直接拉取脱敏副本
adb shell run-as app.auri.probe.android cat files/probe-log-redacted.jsonl \
  > execution/p0-spikes/android-probe/results/raw/probe-log-redacted.jsonl

# 如需清理真机上的旧日志与 PCM 样本，开始新一轮前执行：
adb shell run-as app.auri.probe.android rm -f files/probe-log.jsonl files/probe-log-redacted.jsonl files/rec-*.pcm
```

> 若在强行停止（Force Stop）或杀进程后需要确认系统是否尝试唤醒广播，可额外抓取系统 logcat（同样只存入 `results/raw/`）：
> `adb logcat -d -s MediaSessionService MediaButtonReceiver AuriProbe > execution/p0-spikes/android-probe/results/raw/logcat.txt`

---

## 4. 日志脱敏步骤

原始日志（`probe-log.jsonl`）可能包含蓝牙 MAC 尾段、设备显示名（如“某某的 FreeClip 2”）、手机型号或系统内部报错文本；真实口述音频（`rec-*.pcm`）也可能在设备私有目录中。

1. **先在 App 内或脚本侧脱敏**：
   - 在 App 内点击 **Write Redacted Log Copy**，探针会调用 `LogRedactor.redactLine` 写出 `files/probe-log-redacted.jsonl`（将 `address`、`address_tail`、`mac`、`device_name`、`product_name`、`display_name`、`uid`、`serial`、`build_fingerprint`、`transcript`、`text`、`audio_base64` 统一替换为 `<REDACTED>`，并将正文中匹配到的 MAC 地址替换为 `<REDACTED_MAC>`）。
   - 或者在 Mac 上直接用 `grep -nE '([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}'` 检查待引用片段，确保没有任何 MAC 地址、个人设备昵称或音频内容。
2. **禁止入库项**：
   - 任何 `results/raw/` 下的文件、`*.pcm` / `*.wav` 音频文件、包含真实 MAC 地址或个人昵称的原始日志**绝不**提交 git。
   - 提交到仓库 `execution/p0-spikes/android-probe/results/` 的只能是脱敏后的测试结论 Markdown（基于 `results/RESULTS-TEMPLATE.md` 另存或填写）。

---

## 5. 真机测试脚本（FreeClip 2 与 FreeClip 各跑一轮）

每副耳机测试前先在“华为智慧生活 / AI 生活”中确认并记录耳机手势当前映射（如“双击 = 播放/暂停”或“左耳上一首/右耳下一首”），并清理一次真机日志。每个动作做完后**立即点击界面上的对应标记按钮**（`2x Tap` / `3x Tap` / `Long` / `Swipe`），以在日志中留下精确时间戳。

### 前置：两层条件基线检查（L1 / L2）

1. 启动探针，先**不授权**直接点击 `Check L1/L2` 与 `Manual Start`，确认日志记下 `PERMISSION_MIC_DENIED` 且不会绕过权限启动 FGS 或 `AudioRecord`。
2. 点击 `1. Request Perms`，依次授予麦克风（`RECORD_AUDIO`）、附近设备（`BLUETOOTH_CONNECT`）、通知（`POST_NOTIFICATIONS`）。
3. 点击 `Check L1/L2`，确认此时 `layer1_mic_permission=granted`，`layer2_fgs_state=not_running`，`can_start=unknown`（绝不误报 `yes`）。
4. 点击 `Probe FGS Start/Stop Only (Layer 2)`，确认前台状态下能否正常启动并停止 `FOREGROUND_SERVICE_TYPE_MICROPHONE`。

### 核心场景（S1–S9）

| 场景 | 操作步骤 | 核查日志事件 | 对应规范 / 状态维度 |
|---|---|---|---|
| **S1 前台待命事件** | 打开 `Armed`，`Mapping` 选 `logOnly`，在亮屏前台双击耳机 5 次（每次后点 `2x Tap`）；如有三击再做 5 次 | `media_session_key_event`、`media_session_transport`、`activity_key_down`、`media_button_broadcast` | FR-HS-001；核查是否收到标准外新事件（如 `KEYCODE_HEADSETHOOK` / `KEYCODE_MEDIA_STOP`） |
| **S2 前台手势开始** | `Mapping` 选 `semantic`（若 S1 发现耳机只发单一切换键则改用 `toggle` 复测），保持 `Armed` 开启，双击耳机 | `capture_start_requested` → `fgs_start_foreground_result` → `audio_record_started_initial_route` → `route_type` 为 `bluetooth_hfp` 或 `bluetooth_le_audio` | FR-REC-001/002；`SUPPORTED_FOREGROUND` 证据 |
| **S3 录音中再次双击停止（必测）** | 紧接 S2（确认已进入 `RECORDING` 且 `gate_open=true`），对耳机说话 3 秒后**再次双击耳机**（并点 `2x Tap`） | 是否收到按键事件，以及是否触发 `capture_stopped(reason=command_...)`；若无事件则用 `Manual Stop` 收尾并记录对照 | FR-HS-003 第 5 步、T-002；`stop_while_bluetooth_mic_active` |
| **S4 录音中锁屏** | 在前台用耳机双击（或 `Manual Start`）开始录音 → 按电源键锁屏 → 说话 5 秒 → **锁屏状态下双击耳机停止** → 解锁回 App | `screen_or_keyguard_broadcast(ACTION_SCREEN_OFF)`、帧数/时长是否继续增长、锁屏双击是否到达并停止 | 第 4 章、T-009；`continue_recording_when_locked` |
| **S5 停止后锁屏 / 后台再开始** | 确认当前已停止（状态为 `ARMED`）→ 分别在 ① 按 Home 回桌面（`background`）② 锁屏（`locked`）两种状态下等待 10 秒 → 双击耳机尝试开始 | `media_button_broadcast` / `media_session_key_event` 是否到达；第 2 层 `fgs_start_service_dispatched` 是否抛出 `ForegroundServiceStartNotAllowedException` / `SecurityException`（`BACKGROUND_START_DENIED`） | 第 4.4 章、T-010；`restart_after_stop_when_locked` |
| **S6 进程终止后开始（分项测）** | 在 `ARMED` 状态下，分别测以下 4 种终止方式后双击耳机：① 最近任务划掉卡片；② `adb shell am kill app.auri.probe.android`（模拟系统回收）；③ 系统设置中“强行停止”；④ 手机重启后未打开 App | 是否出现新的 `media_button_broadcast`；注意即使唤醒进程也必须停留在 `DISARMED`，不得自动开麦 | 第 4.4、12.1、22.2 章、T-011；`start_after_process_termination` |
| **S7 路由丢失与隔离门禁** | ① 断开蓝牙后点击 `Manual Start`（预期 `INPUT_DEVICE_MISSING`）；② 蓝牙连接并进入 `RECORDING` 后把耳机放回充电盒关盖或关闭手机蓝牙 | ① `capture_blocked` 且 `accepted_frames=0`；② `audio_record_routing_changed` / `gate_closed_route_not_bluetooth` → `capture_stopped(reason=route_lost)`，绝不混入内置麦克风音频 | FR-REC-001/002/004、T-012~T-014 |
| **S8 对照变量** | 在上述关键场景中切换对照变量：① `PlaybackState`（`none` / `paused` / `playing`，仅元数据、零播放）；② `AudioSource`（`VOICE_COMMUNICATION` / `MIC`）；③ `RoutingMode`（`setCommunicationDevice` / `preferredDeviceOnly`） | 比较不同组合下按键到达率与实际 `routed_device` 是否达到蓝牙输入 | 第 4.4、7.1 章；若依赖 `PLAYING` 元数据才收键，须先评审合规性 |
| **S9 中断与撤销（可选）** | ① 录音中给手机打来电（或用系统音乐播歌抢占音频焦点）；② 录音中在系统设置撤销麦克风权限 | `audio_focus_changed` → `capture_stopped(reason=system_interruption_...)`，挂断后**不自动续录** | FR-REC-004、T-016 |

---

## 6. 结果整理

1. 复制 `results/RESULTS-TEMPLATE.md` 为 `results/YYYY-MM-DD-<headset>-<phone>.md`。
2. 逐行填写每一个 `耳机 × 手机 × 系统版本 × 场景` 的实测状态（枚举：`NOT_TESTED` / `CALIBRATION_REQUIRED` / `SUPPORTED_FOREGROUND` / `SUPPORTED_RECORDING_IN_BACKGROUND` / `SUPPORTED_BACKGROUND_RESTART` / `PARTIAL` / `UNSUPPORTED`）与证据等级（真机日志为 `E3`）。
3. 若在实测日志中看到 `contracts/schemas/common.schema.json` 目前缺少的事件名（如 `KEYCODE_MEDIA_STOP`、`KEYCODE_HEADSETHOOK`），不要直接修改 `contracts/`，请把真实事件载荷与建议变更附在结果报告的“建议合同变更”一节提交合同负责人。
