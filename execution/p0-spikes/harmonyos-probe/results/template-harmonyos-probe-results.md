# DEV-003 鸿蒙 NEXT 探针结果模板（待真机实测）

当前状态：`NOT_RUN`（本模板在无 DevEco Studio、无鸿蒙 SDK、无真机与无耳机的 Linux 环境下生成）。所有兼容状态均保持 `NOT_TESTED`，不得在未取得真机 E3 日志前改为其他状态。

> 实测完成后，请复制本模板为 `results/<YYYY-MM-DD>-<headset>-<phone>.md`（例如 `2026-11-20-freeclip2-mate70.md`），原始日志仅放在 `results/raw/`（已被 `.gitignore` 忽略），不要将原始音频、未脱敏设备名、完整蓝牙 MAC 或签名材料提交到仓库。

## 测试组合与环境信息（实测后填写）

| 项 | 值 |
|---|---|
| 测试日期 | `NOT_RUN` |
| 手机型号（脱敏） | `TBD（待读取 deviceInfo.productModel / marketName）` |
| 完整系统版本 | `TBD（待读取 deviceInfo.osFullName 及 sdkApiVersion，不能只写 NEXT）` |
| DevEco Studio 版本 | `TBD（建议固定 6.0.1 Release）` |
| HarmonyOS SDK / API 版本 | `目标 6.0.1(21)，最低兼容 5.0.0(12)` |
| 被测 App | `AuriProbeHarmonyOS 0.0.1`（`app.auri.probe.harmonyos`），免费调试签名 |
| 耳机 1 | `HUAWEI FreeClip 2`；固件版本：`TBD（若系统未提供填 unknown）` |
| 耳机 2 | `HUAWEI FreeClip`；固件版本：`TBD（若系统未提供填 unknown）` |
| 耳机手势设置 | `TBD（记录华为智慧生活/音频管家中双击、三击、长按的实际配置）` |
| 局域网 mDNS 对照广播端 | `TBD（例如 Mac 上运行 dns-sd -R "AuriTest" _auri-gateway._tcp local 8443 或 gateway 服务）` |

## 场景结果矩阵（耳机 × 手机 × 系统版本 × 场景）

说明：每个单元格必须按 E3 真机日志单独赋值（`NOT_TESTED / CALIBRATION_REQUIRED / SUPPORTED_FOREGROUND / SUPPORTED_RECORDING_IN_BACKGROUND / SUPPORTED_BACKGROUND_RESTART / PARTIAL / UNSUPPORTED`），不得因为“已连接”或单元测试通过（E2）而升级状态。

| 耳机 | 手机 | 系统版本 | 场景 | 兼容状态 | 证据等级 | 实际收到的平台事件 | 系统观测输入路由 | 证据标识 / 备注 |
|---|---|---|---|---|---|---|---|---|
| HUAWEI FreeClip 2 | Phone TBD | HarmonyOS NEXT TBD | S1_foreground_idle_events（前台待命不录音收到什么平台事件） | `NOT_TESTED` | null | `NOT_TESTED` | `NOT_TESTED` | `NOT_RUN` |
| HUAWEI FreeClip 2 | Phone TBD | HarmonyOS NEXT TBD | S2_foreground_start_stop（前台手势开始和停止录音，含路由证据） | `NOT_TESTED` | null | `NOT_TESTED` | `NOT_TESTED` | `NOT_RUN` |
| HUAWEI FreeClip 2 | Phone TBD | HarmonyOS NEXT TBD | S3_stop_while_bluetooth_mic_active（蓝牙麦克风已激活后再次手势能否停止，必测） | `NOT_TESTED` | null | `NOT_TESTED` | `NOT_TESTED` | `NOT_RUN` |
| HUAWEI FreeClip 2 | Phone TBD | HarmonyOS NEXT TBD | S4_continue_recording_when_locked（录音中锁屏能否继续并手势停止） | `NOT_TESTED` | null | `NOT_TESTED` | `NOT_TESTED` | `NOT_RUN` |
| HUAWEI FreeClip 2 | Phone TBD | HarmonyOS NEXT TBD | S5_restart_after_stop_when_locked（停止后锁屏/后台再次手势开始） | `NOT_TESTED` | null | `NOT_TESTED` | `NOT_TESTED` | `NOT_RUN` |
| HUAWEI FreeClip 2 | Phone TBD | HarmonyOS NEXT TBD | S6_start_after_process_termination（进程被杀后手势能否开始） | `NOT_TESTED` | null | `NOT_TESTED` | `NOT_TESTED` | `NOT_RUN` |
| HUAWEI FreeClip 2 | Phone TBD | HarmonyOS NEXT TBD | S7_route_lost_protection（录音中耳机断开/路由切走关闸保护，无手机麦泄漏） | `NOT_TESTED` | null | `NOT_TESTED` | `NOT_TESTED` | `NOT_RUN` |
| HUAWEI FreeClip 2 | Phone TBD | HarmonyOS NEXT TBD | M1_mdns_discovery（局域网 mDNS 发现 `_auri-gateway._tcp`） | `NOT_TESTED` | null | `NOT_TESTED` | — | `NOT_RUN` |
| HUAWEI FreeClip | Phone TBD | HarmonyOS NEXT TBD | S1_foreground_idle_events（前台待命不录音收到什么平台事件） | `NOT_TESTED` | null | `NOT_TESTED` | `NOT_TESTED` | `NOT_RUN` |
| HUAWEI FreeClip | Phone TBD | HarmonyOS NEXT TBD | S2_foreground_start_stop（前台手势开始和停止录音，含路由证据） | `NOT_TESTED` | null | `NOT_TESTED` | `NOT_TESTED` | `NOT_RUN` |
| HUAWEI FreeClip | Phone TBD | HarmonyOS NEXT TBD | S3_stop_while_bluetooth_mic_active（蓝牙麦克风已激活后再次手势能否停止，必测） | `NOT_TESTED` | null | `NOT_TESTED` | `NOT_TESTED` | `NOT_RUN` |
| HUAWEI FreeClip | Phone TBD | HarmonyOS NEXT TBD | S4_continue_recording_when_locked（录音中锁屏能否继续并手势停止） | `NOT_TESTED` | null | `NOT_TESTED` | `NOT_TESTED` | `NOT_RUN` |
| HUAWEI FreeClip | Phone TBD | HarmonyOS NEXT TBD | S5_restart_after_stop_when_locked（停止后锁屏/后台再次手势开始） | `NOT_TESTED` | null | `NOT_TESTED` | `NOT_TESTED` | `NOT_RUN` |
| HUAWEI FreeClip | Phone TBD | HarmonyOS NEXT TBD | S6_start_after_process_termination（进程被杀后手势能否开始） | `NOT_TESTED` | null | `NOT_TESTED` | `NOT_TESTED` | `NOT_RUN` |
| HUAWEI FreeClip | Phone TBD | HarmonyOS NEXT TBD | S7_route_lost_protection（录音中耳机断开/路由切走关闸保护，无手机麦泄漏） | `NOT_TESTED` | null | `NOT_TESTED` | `NOT_TESTED` | `NOT_RUN` |
| HUAWEI FreeClip | Phone TBD | HarmonyOS NEXT TBD | M1_mdns_discovery（局域网 mDNS 发现 `_auri-gateway._tcp`） | `NOT_TESTED` | null | `NOT_TESTED` | — | `NOT_RUN` |

## 对照实验记录（实测后填写）

1. **`AVPlaybackState` 对照（仅改状态与元数据，不播放无声音频）**：
   - `off`（`PLAYBACK_STATE_IDLE`）：`NOT_TESTED`
   - `paused`（`PLAYBACK_STATE_PAUSE`）：`NOT_TESTED`
   - `playing`（`PLAYBACK_STATE_PLAY`）：`NOT_TESTED`
2. **`AUDIO_RECORDING` 长时任务开关对照**：
   - 开启长时任务 vs 关闭长时任务在锁屏/后台下的录音持续性与事件到达情况：`NOT_TESTED`
3. **mDNS 发现结果（ADR-0018）**：
   - 真机在同局域网 Wi-Fi 下能否发现并解析 `_auri-gateway._tcp`：`NOT_TESTED`。若最终实测不可用，鸿蒙端按 ADR-0018 退回“仅使用配对时记下的地址”。

## 建议的合同变更（供合同负责人评估，不直接修改 `contracts/`）

当前合同版本 `1.1`（`contracts/schemas/common.schema.json`）在鸿蒙 NEXT 接入时可能存在以下缺口；探针已在日志字段 `unmapped_platform_detail` 和 `contract_gap_note` 中标出，待真机 E3 实测确认是否确实触发后再提交合同变更：

1. **`common.schema.json#/$defs/platform_event`**：
   - 鸿蒙 `AVSession` 除 `play` / `pause` / `playNext` / `playPrevious` 外，还提供独立的 `stop` 控制命令以及底层 `handleKeyEvent(KeyEvent)` 回调（可能送来 `KEYCODE_MEDIA_PLAY_PAUSE`、`KEYCODE_MEDIA_STOP` 或 `KEYCODE_HEADSETHOOK` 等按键码，并含 `Action.DOWN` / `Action.UP`）。
   - 目前探针将 `avsession.on('stop')` 暂映射为 `platform_event: "pause"` 并在 `unmapped_platform_detail` 记为 `avsession_stop_mapped_to_pause`，将其余未覆盖按键码记为 `unmapped_key_code:<code>`。若真机观察到耳机在鸿蒙上实际发出 `stop` 或特定 `KeyEvent` 按键码，建议在合同中讨论是否增补 `stop` / `media_key` 枚举值。
2. **`common.schema.json#/$defs/input_route_type` 与 `observed_input_route_type`**：
   - 鸿蒙 API 20+ 的 `audio.DeviceType` 包含 `NEARLINK`（星闪设备，数值 `31`）。当前合同仅有 `bluetooth_hfp`、`bluetooth_le_audio`、`bluetooth_unknown_profile`。
   - 目前探针将 `NEARLINK` 暂映射为 `bluetooth_unknown_profile` 并在 `contract_gap_note` 记录；若真机观察到 FreeClip 2 或后续华为耳机在鸿蒙 NEXT 上报 `DeviceType.NEARLINK (31)` 作为输入设备，建议评估是否新增 `nearlink` 路由枚举。
