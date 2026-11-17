# DEV-004 复测结果模板：iOS 音量往返停止与锁屏/后台验证

> 状态说明：本文件为复测空白模板，所有真机项均为 `NOT_TESTED`，构建与设备验证在当前 Linux 环境为 `NOT_RUN`。请在 Mac 与真机实测后**另存为** `YYYY-MM-DD-<headset>-<device>.md`（不要修改历史实测文件 [2026-10-20-freeclip2-iphone-air.md](2026-10-20-freeclip2-iphone-air.md)）。原始日志只放在本机 `results/raw/`（已被 `.gitignore` 忽略），不得入库。

## 测试组合

| 项 | 值 |
|---|---|
| 测试日期 | `NOT_TESTED` |
| 证据等级 | `NOT_TESTED`（真机日志取回后方可记为 E3） |
| 手机型号与系统版本 | `NOT_TESTED`（如 iPhone Air，iOS 26.7.1） |
| 耳机型号与固件版本 | `NOT_TESTED`（系统未提供时填 `unknown`，不得猜测） |
| 耳机侧手势设置 | `NOT_TESTED`（需确认滑动=音量加/减） |
| 签名账号类型 | `NOT_TESTED`（免费个人团队 / 付费 Apple Developer 账号） |
| 被测工程基线 | `AuriProbeIOS`（`project.yml` / `ADR-0021` 提议规则 `ios-volume-reversal` v1） |
| 原始日志本机路径（不入库） | `execution/p0-spikes/ios-probe/results/raw/<filename>.jsonl` |

## 场景结果表（未跑一律保持 `NOT_TESTED`）

| 场景 | 兼容状态 | 计划次数 / 实际观测摘要 | 证据（日志事件关键字段与计数） |
|---|---|---|---|
| **签名与后台能力**：免费个人团队能否签名安装 `UIBackgroundModes: audio` 且后台/锁屏保持写入 | `NOT_TESTED` | `NOT_TESTED`（若报错或被系统阻断，改记 `BLOCKED_EXTERNAL` 并写明需付费账号） | `NOT_TESTED` |
| **音量在中间**：往返停止（`上→下` / `下→上`） | `NOT_TESTED` | `NOT_TESTED` | `NOT_TESTED` |
| **音量在中间对照**：普通调音量（单向 1 下 / 连续同向 2 下）是否误停 | `NOT_TESTED` | `NOT_TESTED` | `NOT_TESTED` |
| **音量在顶部**：`下→上` 停止 + 单次普通下滑对照 | `NOT_TESTED` | `NOT_TESTED` | `NOT_TESTED` |
| **音量在底部**：`上→下` 停止 + 单次普通上滑对照 | `NOT_TESTED` | `NOT_TESTED` | `NOT_TESTED` |
| **锁屏下录音继续与音量往返停止** | `NOT_TESTED` | `NOT_TESTED` | `NOT_TESTED` |
| **锁屏下屏幕停止入口检查**（`Now Playing = playing` vs `off`） | `NOT_TESTED` | `NOT_TESTED` | `NOT_TESTED` |
| **后台（未锁屏）录音继续与音量往返停止** | `NOT_TESTED` | `NOT_TESTED` | `NOT_TESTED` |
| **路由丢失与严格蓝牙输入**（非蓝牙绝不回退） | `NOT_TESTED` | `NOT_TESTED` | `NOT_TESTED` |

## 误停（False Stop）与漏停（Missed Stop）统计

在 Mac 上运行 `python3 execution/p0-spikes/ios-probe/scripts/summarize_log.py <raw-log.jsonl>` 后填入真实数字：

| 指标 | middle | top | bottom | locked | background | 合计 |
|---|---|---|---|---|---|---|
| 意图停止次数（`intended_stop`） | `NOT_TESTED` | `NOT_TESTED` | `NOT_TESTED` | `NOT_TESTED` | `NOT_TESTED` | `NOT_TESTED` |
| 触发音量停止次数（`volume_stop_triggered`） | `NOT_TESTED` | `NOT_TESTED` | `NOT_TESTED` | `NOT_TESTED` | `NOT_TESTED` | `NOT_TESTED` |
| 漏停次数（`missed_stop`） | `NOT_TESTED` | `NOT_TESTED` | `NOT_TESTED` | `NOT_TESTED` | `NOT_TESTED` | `NOT_TESTED` |
| 普通调音量测试次数（`ordinary_volume_adjust`） | `NOT_TESTED` | `NOT_TESTED` | `NOT_TESTED` | `NOT_TESTED` | `NOT_TESTED` | `NOT_TESTED` |
| 误停次数（`false_stop`） | `NOT_TESTED` | `NOT_TESTED` | `NOT_TESTED` | `NOT_TESTED` | `NOT_TESTED` | `NOT_TESTED` |
| 兜底手动停止次数（`screen_control`） | `NOT_TESTED` | `NOT_TESTED` | `NOT_TESTED` | `NOT_TESTED` | `NOT_TESTED` | `NOT_TESTED` |
| 写入失败总次数（`write_failures_total`） | — | — | — | — | — | `NOT_TESTED` |

## 开放结论待填写（仅填真机观测事实）

1. **锁屏屏幕停止入口结论**：`NOT_TESTED`（说明 `Now Playing = playing` / `off` 下锁屏是否出现任何控件、点击后是否产生 `remote_command` 并停录）。
2. **免费个人团队后台音频结论**：`NOT_TESTED`（说明签名构建是否通过、锁屏/后台期间 `accepted_frames` 是否持续增加、`write_failures` 是否为 0；若受阻，标为 `BLOCKED_EXTERNAL` 并说明需付费 Apple Developer 账号）。
3. **ADR-0021 参数是否需调整**：`NOT_TESTED`（依据实测 `dt_ms` 分布、误停与漏停情况决定是否调整 `reversalWindowNanos = 2.5s`、`armGraceNanos = 0.5s` 或边界阈值）。

## 合同变更说明（供合同负责人评估，不直接修改 `contracts/`）

- 现有数据合同 `1.1`（`common.schema.json`、`adapter.schema.json`）已包含 `platform_event: "volume_change"`、`source: "volume_gesture"` 与 `end_reason: "volume_gesture"`，且已规定 `volume_change` 只能映射为 `STOP` 或 `IGNORE`。**音量往返停止规则本身不需要修改现有合同。**
- 潜在的后续可选合同建议（仅当真机/产品需要时另走合同修改流程）：
  1. 若后续通过 `ActivityKit` / 锁屏小组件提供锁屏停止按钮，当前 `common.schema.json` 将 `screen_button` 描述为“App 内的屏幕操作”（`adapter.schema.json` 对应 `screen_control`），届时建议澄清 `screen_control` 涵盖锁屏交互或新增独立来源枚举；
  2. 若后续发现其他蓝牙耳机在录音中发送输入静音切换（`AVAudioApplication.inputMuteStateChangeNotification`）且拟将其作为控制事件，需在 `platform_event` 考虑新增对应枚举（目前探针仅记录日志，不作控制命令）。
