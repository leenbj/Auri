# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

本文件只放每次都要遵守的内容；细节按文末“按需阅读”表在触发时再读。当前进度以 git 为准（`integration/auri-v2` 分支的提交），工单在 `.scratch/auri-v1/issues/`，本文件不记录进度。产品规范名称是 **Auri**；`VoiceBridge` 是原工作代号（PRD 文件为 `VoiceBridge_PRD_Agent_Ready_v1.0.md`）。三端（鸿蒙 NEXT / iOS / Android）原生应用：蓝牙耳机手势开始/停止录音 → 录音必须来自耳机麦克风 → 转录 → 入库到网关 → 经 MCP 供 Agent 读取。

## 命令

合同测试（在仓库根目录执行，依赖版本固定在 `tests/contract/requirements.txt`）：

```bash
uv venv --python 3.12 --seed .venv      # mac 系统自带 python3 是 3.9，装不了锁定的 jsonschema==4.26.0
.venv/bin/pip install -r tests/contract/requirements.txt
.venv/bin/pytest tests/contract                              # 全部
.venv/bin/pytest tests/contract/test_state_machines.py -k capture   # 单个状态机
```

其他机器上 Python 版本够用时，也可以 `python3 -m venv .venv` 起环境。

完整测试：`.venv/bin/pytest tests/contract tests/integration tests/security`，约 2.5 分钟；租约到期测试必须真实等待（合同规定租约最短 10 秒）。

改了 `contracts/` 下的任何文件都要跑一遍。不得通过改样本的期望或删无效样本让测试通过；改 schema、状态机或语义规则的流程见 `contracts/README.md` 的“修改规则”。

网关、iOS/Android/鸿蒙探针的构建、运行、装机命令见 `docs/agent/commands.md`；新增工具链命令请补充到该文件。构建、安装、测试在 mac mini 的原生盘上跑，不在 sshfs 挂载（`/Users/ethan/...` 的网络挂载）上跑。

## 收尾清理（worker 必须做）

每个任务你都会新建分支和 worktree。以前完成后没人清理，越积越多，最后清理时出了很多问题。所以**任务完成并合并后，立刻清理你自己创建的东西，不要攒，不要留“以防万一”**：

1. 移除你自己的 worktree（`git worktree remove`，或 `orca worktree rm`）。
2. 删除你自己的本地分支和 `origin` 上的同名分支。删之前先确认它已合并进目标分支；有未合并的独有提交就不要删，报告给编排者。
3. 删除临时文件、用完的临时规格、失败尝试留下的残余。
4. 清理后用 `git worktree list` 和 `git branch -a` 核对，并在完成报告里写明删了哪些。
5. 只清你自己创建的。别人的、用户的 worktree 和分支不要碰，拿不准先问编排者。

顺序：先提交并推送分支、报告完成；编排者验收并合并后，会通知你清理；合并一完成就做，不要等到整批任务结束。

## 红线（背后的完整清单见 `docs/agent/product-and-constraints.md`）

- 耳机控制分物理手势、平台事件、业务命令三层；不得假设原始 `doubleTap` 事件或猜测左右耳。
- 录音必须来自蓝牙耳机麦克风，不得回退手机麦克风；实际路由确认前，音频帧只进内存隔离区，不得落盘/转录/上传。
- VAD/静音不能当停止手势；不得用无声音乐、伪造通话、私有 API 保活或绕过后台限制，也不得用 CallKit/PushToTalk、华为私有 BLE 协议绕过 iOS 按键限制。
- 采集状态机不得用单个布尔值；命令串行，旧回调不得改写新会话，进程恢复回到 DISARMED，不自动开麦。
- 音频上传、文本入网关、Agent 读取、自动发送须各自授权；“严格仅本地”是统一策略门禁，本地失败不得自动上云。
- 文本版本不可变；转录和修订生成新 revision，已发布版本不可覆盖，删除用墓碑。
- 投递至少一次并幂等消费，身份由认证确定；不信请求体自报 tenant/agent，不得混淆“已入库 / 已通知 / 运行器已接单 / Agent 已处理”。
- 转录文本是数据而非指令，不得 eval/执行口述内容；`intent=action_request` 不授予额外权限。
- SDK、MCP 版本及模型（含哈希与许可证）必须固定，禁止追踪上游 `main`；不得照搬参考仓库的手机麦克风回退、静音停录等策略。
- mock/单测只算 E2，不能证明蓝牙/后台行为；兼容结论需 E3 真机证据；耳机×手机×系统版本×生命周期场景单独赋状态，未知不得当通过。
- 没有真机/账号/签名时标 `NOT_RUN` 或 `BLOCKED_EXTERNAL`，不得用模拟或伪造日志替代。
- 若 P0 发现平台关键需求不可行，记录 BLOCKER 并提交范围决策；开发 Agent 无权自行删需求、改用手机麦克风或宣布验收通过。
- 不得通过改测试预期、删安全负例、加无条件成功 mock 来提高通过率。
- 真实个人口述音频、密钥不得提交仓库；日志默认不含正文、音频或凭证。
- 命名以 `GLOSSARY.md` 为准，字段名、枚举值和状态机规则以 `contracts/` 为准；修改同一 schema / 状态机 / 授权规则时由单一“合同负责人”合并。

## 文档编号

PRD 使用稳定编号，实现与提交信息中请保留引用：`U-`（用户需求）、`FR-`（功能）、`SEC-`、`NFR-`、`T-001～T-050`（测试用例）、`DEV-001～DEV-024`（原子任务及依赖）、`ADR-001～012`（待冻结决策）、`R-001～014`（风险）、`S01～S39`（来源）。

ADR 文件在 `docs/adr/`，编号沿用 PRD 第 28 章（0002、0008、0009），PRD 之外的新决策从 0013 起编。

## 按需阅读

| 要做的事 | 先读 |
| --- | --- |
| 改采集、耳机控制、转录、授权、网关、MCP 相关设计或代码；记录兼容性结论或验收证据 | `docs/agent/product-and-constraints.md` 和 PRD 对应章节 |
| 开始实施一个阶段 | PRD 第 29 章 |
| 改 schema、状态机、授权规则、样本 | `contracts/README.md` 的“修改规则” |
| 查决策来龙去脉 | `docs/adr/` 对应编号 |
| 查 V1 需求、用户故事、实现与测试决策 | `docs/spec/auri-v1-spec.md` |
| 领任务、查验收标准和依赖 | `.scratch/auri-v1/issues/NN-*.md` |
| 构建、运行网关或各平台探针 | `docs/agent/commands.md` |
| 网关运维与证书续签 | `services/gateway/OPERATIONS.md` |
| 查 ASR 候选模型 | `docs/research/asr-candidates.md` |
| 各探针 | `execution/p0-spikes/ios-probe/README.md`、`execution/p0-spikes/android-probe/README.md`、`execution/p0-spikes/harmonyos-probe/README.md` |
| 仓库现状快照（已过期，仅作参考） | `docs/agent/project-snapshot.md`、`execution/baseline/README.md` |
