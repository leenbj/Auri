# DEV-005 Android 探针结果模板（待真机实测填写）

> **当前状态声明**：本文件为结果模板。当前机器为无 Android SDK / JDK / 真机的 Linux 环境，所有真机与构建项均未运行（`NOT_RUN`），下表所有组合与场景的兼容状态全部保持 **`NOT_TESTED`**。不得填入推测、预期或模拟日志。

---

## 1. 环境与设备认证键（真机测试时逐项如实填写）

> 未知或系统未提供的字段填 `unknown`，不得猜测（PRD §5.3）。

| 项 | 设备组合 A（FreeClip 2） | 设备组合 B（FreeClip） |
|---|---|---|
| 测试日期 | `NOT_RUN` | `NOT_RUN` |
| 手机品牌与型号（`ro.product.model`） | `NOT_RUN` | `NOT_RUN` |
| Android 版本与 API 级别（`release` / `sdk_int`） | `NOT_RUN` | `NOT_RUN` |
| 系统构建号（`ro.build.display.id`）与 ROM 类型 | `NOT_RUN` | `NOT_RUN` |
| 耳机型号 | `HUAWEI FreeClip 2` | `HUAWEI FreeClip` |
| 可见耳机固件版本 | `unknown`（待真机核对是否可见） | `unknown`（待真机核对是否可见） |
| 耳机手势配置（华为智慧生活/AI 生活） | `NOT_RUN` | `NOT_RUN` |
| 被测探针版本与 Git Commit | `AuriProbeAndroid 0.0.1 (app.auri.probe.android)` | `AuriProbeAndroid 0.0.1 (app.auri.probe.android)` |
| 原始脱敏外本地日志路径（不入库） | `execution/p0-spikes/android-probe/results/raw/...` | `execution/p0-spikes/android-probe/results/raw/...` |

---

## 2. 两层权限与后台条件核实（PRD §4.4，分别赋值）

| 核实层 | 检查项 | FreeClip 2 组合结果 | FreeClip 组合结果 | 证据等级 |
|---|---|---|---|---|
| 第 1 层：运行时权限 | 未授权时点击开始是否准确被阻断（`PERMISSION_MIC_DENIED` / `INPUT_DEVICE_MISSING`） | `NOT_TESTED` | `NOT_TESTED` | `-` |
| 第 1 层：运行时权限 | 已授予 `RECORD_AUDIO` + `BLUETOOTH_CONNECT` + `POST_NOTIFICATIONS` 后能否确认 | `NOT_TESTED` | `NOT_TESTED` | `-` |
| 第 2 层：FGS（`microphone`） | 前台启动 `MicCaptureForegroundService` 后 `actual_fgs_type` 是否包含 `FOREGROUND_SERVICE_TYPE_MICROPHONE` | `NOT_TESTED` | `NOT_TESTED` | `-` |
| 第 2 层：FGS（`microphone`） | 后台（Home 回桌面）尝试启动 `MicCaptureForegroundService` 是否抛出 `ForegroundServiceStartNotAllowedException` / `SecurityException` | `NOT_TESTED` | `NOT_TESTED` | `-` |
| 第 2 层：FGS（`microphone`） | 锁屏状态尝试启动 `MicCaptureForegroundService` 是否被系统拒绝 | `NOT_TESTED` | `NOT_TESTED` | `-` |
| 隔离与严格蓝牙 | `checkSelfPermission == GRANTED` 但 FGS 未运行时，`CaptureEligibilityEvaluator` 是否不推断为 `yes` | `NOT_TESTED`（单测 `NOT_RUN`） | `NOT_TESTED`（单测 `NOT_RUN`） | `-` |

---

## 3. 兼容结论矩阵（每个 耳机 × 手机 × 系统版本 × 场景 单独赋值）

允许的状态枚举（`contracts/schemas/common.schema.json#/$defs/compat_status`）：
`NOT_TESTED` / `CALIBRATION_REQUIRED` / `SUPPORTED_FOREGROUND` / `SUPPORTED_RECORDING_IN_BACKGROUND` / `SUPPORTED_BACKGROUND_RESTART` / `PARTIAL` / `UNSUPPORTED`。
只有取得真机日志（`E3` / `E4`）后才可将状态改为 `NOT_TESTED` 以外的值。

| 耳机 | 手机 | 系统版本 | 场景 | 状态 | 证据等级 | 实际平台事件 | 实际输入路由（`observed_input_route_type`） | 隔离丢弃帧 / 接收帧 | 备注 / 脱敏日志行摘要 |
|---|---|---|---|---|---|---|---|---|---|
| HUAWEI FreeClip 2 | TBD | Android TBD | S1 前台待命收到什么平台事件 | `NOT_TESTED` | `-` | `-` | `-` | `-` | `NOT_RUN` |
| HUAWEI FreeClip 2 | TBD | Android TBD | S2 前台开始录音与路由证据 | `NOT_TESTED` | `-` | `-` | `-` | `-` | `NOT_RUN` |
| HUAWEI FreeClip 2 | TBD | Android TBD | **S3 录音中再次双击能否停止（必测）** | `NOT_TESTED` | `-` | `-` | `-` | `-` | `NOT_RUN` |
| HUAWEI FreeClip 2 | TBD | Android TBD | S4 录音中锁屏能否继续并停止 | `NOT_TESTED` | `-` | `-` | `-` | `-` | `NOT_RUN` |
| HUAWEI FreeClip 2 | TBD | Android TBD | S5a 停止后回桌面后台再开始 | `NOT_TESTED` | `-` | `-` | `-` | `-` | `NOT_RUN` |
| HUAWEI FreeClip 2 | TBD | Android TBD | S5b 停止后锁屏再开始 | `NOT_TESTED` | `-` | `-` | `-` | `-` | `NOT_RUN` |
| HUAWEI FreeClip 2 | TBD | Android TBD | S6a 划掉任务卡后开始 | `NOT_TESTED` | `-` | `-` | `-` | `-` | `NOT_RUN` |
| HUAWEI FreeClip 2 | TBD | Android TBD | S6b 系统回收（`am kill`）后开始 | `NOT_TESTED` | `-` | `-` | `-` | `-` | `NOT_RUN` |
| HUAWEI FreeClip 2 | TBD | Android TBD | S6c 强行停止（Force Stop）后开始 | `NOT_TESTED` | `-` | `-` | `-` | `-` | `NOT_RUN` |
| HUAWEI FreeClip 2 | TBD | Android TBD | S6d 设备重启后开始 | `NOT_TESTED` | `-` | `-` | `-` | `-` | `NOT_RUN` |
| HUAWEI FreeClip 2 | TBD | Android TBD | S7 路由丢失 / 无蓝牙拒绝回退 | `NOT_TESTED` | `-` | `-` | `-` | `-` | `NOT_RUN` |
| HUAWEI FreeClip 2 | TBD | Android TBD | S8 对照变量（`PlaybackState` / `AudioSource` / `RoutingMode`） | `NOT_TESTED` | `-` | `-` | `-` | `-` | `NOT_RUN` |
| HUAWEI FreeClip 2 | TBD | Android TBD | S9 来电/焦点抢占与权限撤销中断 | `NOT_TESTED` | `-` | `-` | `-` | `-` | `NOT_RUN` |
| HUAWEI FreeClip | TBD | Android TBD | S1 前台待命收到什么平台事件 | `NOT_TESTED` | `-` | `-` | `-` | `-` | `NOT_RUN` |
| HUAWEI FreeClip | TBD | Android TBD | S2 前台开始录音与路由证据 | `NOT_TESTED` | `-` | `-` | `-` | `-` | `NOT_RUN` |
| HUAWEI FreeClip | TBD | Android TBD | **S3 录音中再次双击能否停止（必测）** | `NOT_TESTED` | `-` | `-` | `-` | `-` | `NOT_RUN` |
| HUAWEI FreeClip | TBD | Android TBD | S4 录音中锁屏能否继续并停止 | `NOT_TESTED` | `-` | `-` | `-` | `-` | `NOT_RUN` |
| HUAWEI FreeClip | TBD | Android TBD | S5a 停止后回桌面后台再开始 | `NOT_TESTED` | `-` | `-` | `-` | `-` | `NOT_RUN` |
| HUAWEI FreeClip | TBD | Android TBD | S5b 停止后锁屏再开始 | `NOT_TESTED` | `-` | `-` | `-` | `-` | `NOT_RUN` |
| HUAWEI FreeClip | TBD | Android TBD | S6a 划掉任务卡后开始 | `NOT_TESTED` | `-` | `-` | `-` | `-` | `NOT_RUN` |
| HUAWEI FreeClip | TBD | Android TBD | S6b 系统回收（`am kill`）后开始 | `NOT_TESTED` | `-` | `-` | `-` | `-` | `NOT_RUN` |
| HUAWEI FreeClip | TBD | Android TBD | S6c 强行停止（Force Stop）后开始 | `NOT_TESTED` | `-` | `-` | `-` | `-` | `NOT_RUN` |
| HUAWEI FreeClip | TBD | Android TBD | S6d 设备重启后开始 | `NOT_TESTED` | `-` | `-` | `-` | `-` | `NOT_RUN` |
| HUAWEI FreeClip | TBD | Android TBD | S7 路由丢失 / 无蓝牙拒绝回退 | `NOT_TESTED` | `-` | `-` | `-` | `-` | `NOT_RUN` |
| HUAWEI FreeClip | TBD | Android TBD | S8 对照变量（`PlaybackState` / `AudioSource` / `RoutingMode`） | `NOT_TESTED` | `-` | `-` | `-` | `-` | `NOT_RUN` |
| HUAWEI FreeClip | TBD | Android TBD | S9 来电/焦点抢占与权限撤销中断 | `NOT_TESTED` | `-` | `-` | `-` | `-` | `NOT_RUN` |

---

## 4. 建议合同变更（供合同负责人评审；当前未改动 `contracts/`）

在编写 Android 探针时已发现以下 Android 公开事件/错误码与当前 `contracts/` v1.1 的枚举存在潜在缺口（是否真正触发待真机 E3 日志证实，已预先在 `ControlEventNormalizer` 中以 `contract_gap_note` 标记）：

1. **`contracts/schemas/common.schema.json#/$defs/platform_event` 与 `contracts/schemas/headset-profile.schema.json#/properties/command_mapping`**：
   - 当前 `platform_event` 枚举仅有 `["play", "pause", "toggle", "next", "previous", "volume_change", "screen_button"]`。
   - Android `MediaSession.Callback.onStop()` / `KeyEvent.KEYCODE_MEDIA_STOP`（`86`）与 `KeyEvent.KEYCODE_HEADSETHOOK`（`79`）是 Android 耳机与媒体会话中常见的公开平台事件（iOS 探针也注册过 `stopCommand`）。
   - **建议**（若真机日志观测到 `KEYCODE_MEDIA_STOP` 或 `KEYCODE_HEADSETHOOK`）：作为版本升版（`1.2`）变更，评估在 `platform_event` 与 `headset-profile.schema.json` 的 `command_mapping` 中补充 `"stop"` 与 `"headset_hook"`，避免平台层必须把它们强行折叠成 `"pause"` 或 `"toggle"` 而丢失原始语义。
2. **`contracts/schemas/common.schema.json#/$defs/error_code`（待真机确认是否需要）**：
   - Android 12+ 蓝牙路由与发现还依赖运行时权限 `BLUETOOTH_CONNECT`；当前合同只有 `PERMISSION_MIC_DENIED` 和 `INPUT_DEVICE_MISSING`，缺少独立的蓝牙权限拒绝码（目前探针暂映射至 `INPUT_DEVICE_MISSING` 并附 `layer1_bluetooth_connect_denied` 备注）。若真机产品需要区分“蓝牙未连”与“未授予 `BLUETOOTH_CONNECT` 权限”，建议后续评估新增 `PERMISSION_BLUETOOTH_DENIED`。
