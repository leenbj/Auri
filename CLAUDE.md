# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目现状

截至 2026-11-15，仓库里有文档、目录骨架、一个 iOS 探针和已冻结的数据合同（含合同测试），还没有产品代码和网关代码。目前唯一的测试命令是合同测试，见下文“命令”一节：
- `VoiceBridge_PRD_Agent_Ready_v1.0.md`：需求基线（约 1580 行），v1.4 已同步 ADR-0015 至 0020、DEV-002 的合同冻结结论及合同 1.1 错误码补充。
- `GLOSSARY.md`：领域术语表，**命名以它为准**（Recording / Record / Revision / Destination / Delivery 等）。
- `docs/adr/`：已决策记录。文件编号沿用 PRD 第 28 章的 ADR 编号（0002、0008、0009），PRD 之外的新决策从 0013 起编（目前有 0013 至 0020）。

- `execution/baseline/README.md`：DEV-001 的基线记录（2026-10-20，开发机与三端工具链状态）。其中“网关主机需公网域名与 TLS”一项已被 ADR-0018 取代。
- `execution/p0-spikes/ios-probe/`：DEV-004 的 iOS 探针（Swift，一次性代码，不是产品代码）。构建、装机、取日志的命令见该目录的 `README.md`，需要 macOS、Xcode 和 XcodeGen 2.46.0；实测结论在 `results/`，原始日志不入库。
- `contracts/`：数据合同（当前版本 1.1；初始 1.0 由 DEV-002 冻结，1.1 新增三个网关错误码）。`schemas/` 是 JSON Schema，`fixtures/` 是有效/无效样本、哈希测试向量和四个状态机的转移表与事件序列样本。**字段名、枚举值和状态机规则以这里为准**，说明见 `contracts/README.md`。
- `tests/contract/`：合同测试（pytest）。
- `apps/`、`core/`、`services/`、`packages/` 以及 `tests/` 的其他子目录目前只有 `.gitkeep`。

任务进度：DEV-001（仓库基线）已完成；DEV-004（iOS 探针）已跑过 FreeClip 2 × iPhone Air，结论见 ADR-0014，遗留音量手势停止的设计与复测。DEV-002（合同冻结）已完成：网关与 MCP 消息、Headset Profile、八个原生适配器类型和四个状态机。V1 的规格在 `docs/spec/auri-v1-spec.md`。尚未开始：DEV-003（鸿蒙探针）、DEV-005（Android 探针）、网关。DEV-011/012（云端转录）已推迟到 V1 之后（ADR-0019）。新增工具链命令后请补充到本文件。

工具链与账号：iOS 只能在 macOS 上构建；Apple 目前是免费个人团队签名，鸿蒙签名证书尚未申请，P0 探针先用免费签名，付费账号在 P5 分发前再办。DevEco Studio 与 Android SDK 在基线记录时均未安装，开工前先核对当前机器的实际情况。

## 命令

合同测试（在仓库根目录执行，依赖版本固定在 `tests/contract/requirements.txt`）：

```bash
python3 -m venv .venv
.venv/bin/pip install -r tests/contract/requirements.txt
.venv/bin/pytest tests/contract                              # 全部
.venv/bin/pytest tests/contract/test_state_machines.py -k capture   # 单个状态机
```

网关与集成/安全测试（在仓库根目录执行，依赖版本固定在 `services/gateway/requirements.txt` 与 `tests/integration/requirements.txt`）：

```bash
.venv/bin/pip install -r services/gateway/requirements.txt -r tests/integration/requirements.txt
PYTHONPATH=services/gateway .venv/bin/python -m auri_gateway serve --data-dir /tmp/auri-gateway-data --port 8443
PYTHONPATH=services/gateway .venv/bin/python -m auri_gateway serve --data-dir /tmp/auri-gateway-data --host 127.0.0.1 --port 8080 --plaintext-loopback  # 仅本机开发回环明文
PYTHONPATH=services/gateway .venv/bin/python -m auri_gateway pair --data-dir /tmp/auri-gateway-data
PYTHONPATH=services/gateway .venv/bin/python -m auri_gateway renew-cert --data-dir /tmp/auri-gateway-data --validity-days 365
.venv/bin/pytest tests/contract tests/integration tests/security
```

证书有效期与续签运维说明见 `services/gateway/OPERATIONS.md`。

Android 探针（在 `execution/p0-spikes/android-probe/` 下执行，需 JDK 17 与 Android SDK 35 + `adb`；详情见该目录 `README.md`）：

```bash
cd execution/p0-spikes/android-probe
./gradlew test                                                       # 离线 JVM 单测（仅 E2）
./gradlew assembleDebug                                              # 构建 Debug APK
adb install -r app/build/outputs/apk/debug/app-debug.apk             # 装机
adb shell am start -n app.auri.probe.android/app.auri.probe.MainActivity
adb shell run-as app.auri.probe.android cat files/probe-log.jsonl > results/raw/probe-log.jsonl
```

改了 `contracts/` 下的任何文件都要跑一遍。不得通过改样本的期望或删无效样本让测试通过；改 schema、状态机或语义规则的流程见 `contracts/README.md` 的“修改规则”。

## 产品与已决前提

产品规范名称是 **Auri**；`VoiceBridge` 是原工作代号（PRD 文件名沿用，未做商标/重名核查）。PRD 状态为“需求基线草案”，其中的性能数字是建议目标，不是实测结果。

已决的关键前提（详见对应 ADR）：iOS 后台新建录音不可行时以 `PARTIAL` 受限交付，且任何情况下都不得改用手机麦克风（ADR-002）；iOS 录音中耳机按键（双击/三击）收不到，停止改用音量手势+屏幕按钮+时长上限，不得用 CallKit/PushToTalk 或华为私有 BLE 协议绕过（ADR-0014）；V1 网关单用户自托管，Tenant 只由认证结果映射得出（ADR-0013）；每个 Destination 一套专属凭证，App 只与网关通信（ADR-008）；自动发送按 Destination 显式授权（ADR-009）；MCP 验收宿主为 Hermes + Claude Code。2026-11-15 新增：Handy 只借处理流程设计，不 fork（ADR-0015）；手势决定录音开始与结束，VAD 只在会话内过滤静音，不做常开监听（ADR-0016）；qwen3-asr-1.7b 是默认本地模型的候选，实测后再定（ADR-0017）；网关只在局域网、自签证书加指纹绑定、不暴露公网，V1 不做手机直连 MCP，此条取代 ADR-008 的公网域名部分（ADR-0018）；V1 只做本地转录，云端转录推迟（ADR-0019）；只有录下 Record 的那台设备能为它写 Revision 或删除它（ADR-0020）。

## 产品概述

三端（鸿蒙 NEXT / iOS / Android）原生应用：通过蓝牙耳机（优先 HUAWEI FreeClip 2 / FreeClip）的双击手势开始/停止录音 → 录音**必须来自蓝牙耳机麦克风** → 本地或云端转录 → 文本经 HTTPS 入库到持久化网关 → 通过 MCP 工具供获授权的 Agent 读取并独立确认。

目标架构（详见 PRD 第 15–18 章）：
- **移动端**：每个平台使用原生实现（ArkTS / Swift / Kotlin），共享 C/C++ 的 sherpa-onnx 本地 ASR 与 JSON Schema 契约；不共享 UI 代码（跨平台 UI 需 ADR 论证）。
- **网关**（Python + 官方 MCP SDK，SQLite 起步）：HTTP 入库、持久化、每目标投递租约、MCP 端点、可选事件分发器。手机**不**作为 MCP Server。
- 建议目录：`apps/{harmonyos,ios,android}`、`core/{audio,asr}`、`contracts/{schemas,fixtures,compatibility}`、`services/{gateway,asr-proxy,dispatcher}`、`packages/mcp-stdio-bridge`、`tests/{contract,integration,security,hardware,asr-benchmarks}`、`execution/{baseline,p0-spikes,acceptance}`。

## 必须遵守的核心约束（来自 PRD，改动前先读对应章节）

- **控制链分三层**：耳机物理手势 → 平台事件（play/pause/toggle/key）→ 业务命令（START/STOP/TOGGLE/IGNORE）。不得假设存在原始 `doubleTap` 事件，也不得猜测左右耳（FR-HS-001）。
- **严格蓝牙输入**（`bluetooth_only`）：不得自动回退手机麦克风；“已配对/已连接/偏好设备”都不等于“当前采集真正来自耳机”，必须用实际路由证据。初始未验证帧只进内存隔离区，路由确认后才允许落盘/转录/上传（FR-REC-001/002）。
- **VAD/静音不能当作停止手势**（FR-REC-006）；不得用播放无声音乐、伪造通话、私有 API 等方式保活或绕过后台限制。
- **采集状态机**不得用单个布尔值：`DISARMED → ARMED → ROUTING → RECORDING → FINALIZING`；命令串行化，带 `capture_session_id` / generation / `route_epoch`，旧回调不得改写新会话；进程恢复后回到 DISARMED，不自动开麦（第 12 章）。
- **隐私授权分离**：音频上传、文本入网关、Agent 读取、自动发送各自独立授权；“严格仅本地”是统一策略门禁；本地失败不得自动上云（SEC-002、FR-ASR-002）。
- **文本版本不可变**：转录与用户修订都是新 revision；已发布版本不得被覆盖；删除使用墓碑，防止离线补传复活（FR-TXT-002/005、FR-SYNC-002）。
- **投递语义**：至少一次 + 幂等消费；唯一逻辑键 `tenant_id + record_id + revision + destination_id`；租约式 claim（`lease_token`）+ ack；身份由认证结果确定，不信请求体自报的 tenant/agent；“已入库 / 已通知 / 运行器已接单 / Agent 已处理”四种状态不得混淆（FR-MCP-006/007）。
- **转录文本是数据而非指令**（SEC-004）：不得 eval/执行口述内容；`intent=action_request` 不授予任何额外权限。
- **依赖锁定**：SDK、MCP 版本、模型（含哈希与许可证）必须固定版本；禁止追踪上游 `main`；参考仓库（BT Audio Recorder、Yunto、AudioMemo 等）只借思路，其手机麦克风回退、静音停录等策略不得照搬（第 26.4 节）。

## 证据与验收规则

- 证据等级 E0–E4（第 5.2 节）：mock/单测只算 E2，不能证明蓝牙/后台行为；兼容结论需 E3 真机证据。
- 每个耳机×手机×系统版本×生命周期场景**单独**赋值，状态枚举：`NOT_TESTED / CALIBRATION_REQUIRED / SUPPORTED_FOREGROUND / SUPPORTED_RECORDING_IN_BACKGROUND / SUPPORTED_BACKGROUND_RESTART / PARTIAL / UNSUPPORTED`。未知不得当通过。
- 没有真机/账号/签名时标 `NOT_RUN` 或 `BLOCKED_EXTERNAL`，不得用模拟或伪造日志替代。
- 若 P0 发现某平台关键需求不可行（尤其 iOS 后台新建录音），记录 BLOCKER 并提交范围决策；**开发 Agent 无权自行删需求、改用手机麦克风或宣布验收通过**。
- 不得通过改测试预期、删安全负例、加无条件成功 mock 来提高通过率。

## 文档编号体系

PRD 使用稳定编号，实现与提交信息中请保留引用：`U-`（用户需求）、`FR-`（功能）、`SEC-`、`NFR-`、`T-001～T-050`（测试用例）、`DEV-001～DEV-024`（原子任务及依赖）、`ADR-001～012`（待冻结决策）、`R-001～014`（风险）、`S01～S39`（来源）。

开发阶段：P0 可行性与合同冻结（门槛 G0）→ P1 稳定采集 → P2 双模式转录 → P3 网关与 MCP → P4 端到端回归 → P5 签名分发。P0 应先做三端最小探针（控制事件、输入路由、前台/锁屏/后台/进程终止场景，必测“蓝牙麦克风已激活后再次双击能否停止”），再铺 UI。ASR、数据合同、网关可与探针并行。

PRD 第 29 章含给执行 Agent 的启动指令，开始实施前应通读。

## 其他

- 修改同一 schema / 状态机 / 授权规则时由单一“合同负责人”合并，避免不兼容的相似字段（第 24.2 节）。
- 真实个人口述音频、密钥不得提交仓库；日志默认不含正文、音频或凭证。
