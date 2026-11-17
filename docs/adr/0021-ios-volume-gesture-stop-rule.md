---
status: proposed
---
# ADR-0021：iOS 录音中音量往返手势停止规则（提议）

状态：**提议**（等待真机 E3 复测日志与产品负责人决定）。本 ADR 在 [ADR-0014](0014-ios-stop-via-volume-gesture.md) 划定的边界内给出音量手势的具体语法、边界处理与锁屏/后台行为。

## 背景与约束

ADR-0014 已决定：iOS 录音中耳机双击/三击均无法送达第三方 App，停止改用“音量手势 + App 内屏幕按钮 + 时长上限”，且严禁：

- 使用 CallKit / PushToTalk 等通话类框架；
- 写入或依赖华为私有 BLE 协议；
- 使用播放无声音频等保活手段；
- 使用私有 API（如 `AVSystemController`）或遍历 `MPVolumeView` 内部 `UISlider` 子视图去强行改写系统音量。

[2026-10-20 真机探索结果](../../execution/p0-spikes/ios-probe/results/2026-10-20-freeclip2-iphone-air.md) 证实：

1. 录音期间滑动 FreeClip 2 耳机柄会触发 `AVAudioSession.outputVolume` KVO 回调，锁屏后仍可送达；不录音时无该回调；
2. 音量到顶（`1.0`）后再上滑只产生 1 个 `delta=0` 事件；到底（约 `0.05`）后再下滑完全没有事件。因此“任意音量变化即停止”在顶部/底部会失灵，且会把正常调音量误判为停止。

## 提议规则：双向往返手势（Reversal Pair）

仅在采集处于 `RECORDING`（探针 `recording`）状态且当前会话已开闸时生效；非录音状态下 `volume_change` 一律忽略（符合数据合同 `adapter.schema.json`：`volume_change` 只能映射为 `STOP` 或 `IGNORE`，不能用于 `START` 或 `TOGGLE`）。

### 1. 参数与判定步骤

1. **启动保护期（Arm Grace Window = `0.5s`）**：`capture_started` 后前 `500 ms` 内的 `outputVolume` 回调一律记为 `IGNORE(arm_grace_window)`，避免切换 `BluetoothHFP` 会话时系统初始同步音量污染状态。
2. **零增量过滤（Min Delta = `0.001`）**：`|new - previous| < 0.001`（例如音量到顶后再上滑触发的 `delta=0`）记为 `IGNORE(zero_delta)`，不进入方向状态，也不清空已有待配对方向。
3. **单向与普通调音量区分**：
   - 观测到首个有效方向变化（`+` 为 `up`，`-` 为 `down`），记录为待配对步骤（`armed_first_step`），输出 `IGNORE`；
   - 若超过时间窗口 **`W = 2.5s`** 仍未收到反向变化，该次单向记录过期；
   - 若在 `W` 内收到**相同方向**的变化（`up -> up` 或 `down -> down`），视为连续调音量（`refreshed_same_direction`），**不停止录音**，只更新最近一次同向步骤的时间戳（保留原始起始边界）。
4. **往返命中停止（Reversal Pair）**：
   - 若在 `W = 2.5s` 内、且距上一步不少于去抖最小间隔 `50 ms` 收到**相反方向**的有效变化（`up -> down` 或 `down -> up`），立即归一化为 `NormalizedControlEvent(source="volume_gesture", platform_event="volume_change", command="STOP", profile_id="ios-volume-reversal", profile_version=1)` 并停止封存（`end_reason = "volume_gesture"`）。
5. **会话隔离**：每次进入、退出或受阻结束录音会话时清空待配对状态，跨会话不残留。

### 2. 音量在底部、顶部与中间的行为

| 初始音量位置 | 区间判定（参考值） | 推荐停止手势 | 动作后音量 | 说明与边界行为 |
|---|---|---|---|---|
| **中间** | `0.065 < v < 0.935` | `上滑 → 下滑` 或 `下滑 → 上滑` | 回到原位 | 两个方向均可正常往返；单向滑 1～N 下只调音量，不误停 |
| **顶部** | `v >= 0.935`（含 `1.0`） | **`下滑 → 上滑`** | 回到顶部 | 到顶再上滑仅产生 `delta=0`（被忽略）。若先误上滑再下滑，只算单次 `-`（漏停但不会误停）；单次下滑被正确识别为“调小音量” |
| **底部** | `v <= 0.065`（HFP 实测底限约 `0.05`） | **`上滑 → 下滑`** | 回到底部 | 到底再下滑系统**无任何 KVO 事件**，公开 API 不可感知。因此底部必须先升一格再降回。单次上滑被识别为“调大音量” |

### 3. 锁屏与后台行为

- **采集持续性**：依赖 `Info.plist` 中的 `UIBackgroundModes: audio` 且录音必须在前台启动（ADR-002）。录音开始后锁屏或切到后台，若免费个人团队签名允许该后台能力，则 `AVAudioSession.outputVolume` KVO 与音频采集继续工作，往返手势停止规则照常生效（待真机 E3 逐项验证；若免费签名被系统拦截或无法生效，则按 `BLOCKED_EXTERNAL` 上报并需付费账号）。
- **锁屏屏幕停止入口**：在不使用通话框架和无声保活的前提下，需通过真机复测确认锁屏时（对比 `MPNowPlayingInfoCenter` 元数据 `off / paused / playing`）系统是否展示可用的暂停/停止控件。在 E3 实测证实前，**不承诺锁屏有屏幕停止按钮**；兜底仍为音量往返手势、解锁回 App 点屏幕按钮、以及时长上限（默认 30 分钟 / 硬上限 120 分钟）。

## 已知权衡与局限（诚实告知）

1. `AVAudioSession.outputVolume` KVO 无法区分变化来自耳机柄滑动还是 iPhone 机身物理音量键 / 控制中心滑块。
2. 若用户在 `2.5s` 内“调大一格觉得太响马上调回一格”，会触发停止（**误停**）。本次复测探针已加入 `intended_stop` / `ordinary_volume_adjust` / `false_stop` / `missed_stop` 计数日志，用于统计真实误停与漏停率，再由产品负责人拍板参数或是否采纳。

## Considered Options

- **任意 `outputVolume` 变化即停止**：否决。正常调音量必停；音量在底部再下滑无事件，会完全无法停止。
- **连续同向两次（如连滑两下加音量）**：否决。不仅会干扰正常连续调音量，且在顶部（只剩 `delta=0`）无法连上两次、在底部（无下事件）无法连下两次；虽然可规定“到顶连下两次、到底连上两次”，但仍与“普通调两格音量”无法区分，误停率高。
- **通过 `MPVolumeView` 内部 `UISlider` 子视图在开录时自动把音量拨离边界**：暂不采用。`MPVolumeView` 未公开可写音量接口，依赖私有子视图层级脆弱，且在后台/锁屏下可靠性未核实，还会在用户不知情时改变耳机音量导致提示音骤响。
- **引入 `ActivityKit` Live Activity + `LiveActivityIntent` 作为锁屏停止按钮**：保留为后续备选，暂不纳入本次 P0 探针。需新增 WidgetKit Extension Target 与 App Group，免费个人团队的 Weekly App ID 限额与 App Group 自动签名风险较高（且当前属于探针验证阶段，先测清 `Now Playing` 元数据与系统锁屏原生表现）。

## Consequences

- 本 ADR 当前状态为 `proposed`，只有当 `execution/p0-spikes/ios-probe/results/` 补齐顶/底/中、锁屏、后台、误停/漏停、锁屏控件及免费签名后台能力的 E3 真机证据，并经产品负责人确认后，才改为 `accepted`。
- `contracts/` 1.1 中 `platform_event = "volume_change"`、`source = "volume_gesture"`、`end_reason = "volume_gesture"` 已完备，本规则不需要修改数据合同。
