# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目现状

截至 2026-11-17，仓库里已有文档、目录骨架、已冻结的数据合同（1.1）、三端 P0 探针源码（均未在移动端构建工具链下编译），以及 Gateway 服务端实现与合同/集成/安全测试；移动端产品代码（`apps/`、`core/`）尚未开始。测试命令见下文“命令”一节，完整测试命令为 `.venv/bin/pytest tests/contract tests/integration tests/security`：
- `VoiceBridge_PRD_Agent_Ready_v1.0.md`：需求基线（约 1580 行），v1.4 已同步 ADR-0015 至 0020、DEV-002 的合同冻结结论及合同 1.1 错误码补充。
- `GLOSSARY.md`：领域术语表，**命名以它为准**（Recording / Record / Revision / Destination / Delivery 等）。
- `docs/adr/`：已决策记录。文件编号沿用 PRD 第 28 章的 ADR 编号（0002、0008、0009），PRD 之外的新决策从 0013 起编（目前有 0013 至 0021，其中 0013–0020 为 `accepted`，0021 为 `proposed`）。
- `docs/research/asr-candidates.md`：本地转录候选模型核实报告（E1 调研结论与第一轮真机评测名单）。
- `execution/baseline/README.md`：DEV-001 的基线记录（2026-10-20，开发机与三端工具链状态）。其中“网关主机需公网域名与 TLS”一项已被 ADR-0018 取代。
- `execution/p0-spikes/{android,harmonyos,ios}-probe/`：三端一次性 P0 探针源码（不是产品代码），当前在对应原生工具链下均**尚未编译**（`ios-probe` 另有 `results/2026-10-20-freeclip2-iphone-air.md` 早期实测记录，新版音量往返停止复测待编译运行；`android-probe` 与 `harmonyos-probe` 尚无真机实测结果）。构建、离线单测、装机、取日志命令见各目录 `README.md` 及下文“命令”一节；实测结论放各自 `results/`，原始日志不入库。
- `contracts/`：数据合同（当前版本 `1.1`；初始 1.0 由 DEV-002 冻结，1.1 新增三个网关错误码：`GATEWAY_AUTH_INVALID`、`REQUEST_INVALID`、`PAIRING_CODE_INVALID`）。`schemas/` 是 JSON Schema，`fixtures/` 是有效/无效样本、哈希测试向量和四个状态机的转移表与事件序列样本。**字段名、枚举值和状态机规则以这里为准**，说明见 `contracts/README.md`。
- `services/gateway/`：Gateway 服务端代码（Python + 官方 MCP SDK + SQLite，含配对与设备认证、Destination 与凭证管理、Revision 幂等入库、Delivery 状态机与 MCP 租约投递、局域网自签 TLS 与 mDNS 广播，运维说明见 `services/gateway/OPERATIONS.md`）。
- `tests/`：`tests/contract/`（合同测试）、`tests/integration/`（Gateway 黑盒集成测试）、`tests/security/`（安全与拒收测试），三目录合并执行为 `.venv/bin/pytest tests/contract tests/integration tests/security`；`tests/hardware/`、`tests/asr-benchmarks/` 目前只有 `.gitkeep`。
- `apps/`、`core/`、`services/asr-proxy/`、`services/dispatcher/`、`packages/` 目前只有 `.gitkeep`。

任务进度：DEV-001（仓库基线）与 DEV-002（合同冻结 1.1）已完成；V1 的规格在 `docs/spec/auri-v1-spec.md`。Gateway 已完成核心骨架（Ticket 01–03、06）；三端 P0 探针代码（DEV-003 鸿蒙、DEV-004 iOS 音量手势复测、DEV-005 Android）与 ASR 候选模型核实（Ticket 13）已入库但探针均未编译或真机复测；移动端产品代码与 Gateway 只读/删除扩展（Ticket 04+）尚未开始。DEV-011/012（云端转录）已推迟到 V1 之后（ADR-0019）。新增工具链命令后请补充到本文件。

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
PYTHONPATH=services/gateway .venv/bin/python -m auri_gateway migrate --data-dir /tmp/auri-gateway-data
PYTHONPATH=services/gateway .venv/bin/python -m auri_gateway list-devices --data-dir /tmp/auri-gateway-data
PYTHONPATH=services/gateway .venv/bin/python -m auri_gateway revoke-device --data-dir /tmp/auri-gateway-data --device-id <device_id>
PYTHONPATH=services/gateway .venv/bin/python -m auri_gateway revoke-credential --data-dir /tmp/auri-gateway-data --credential-id <credential_id>
PYTHONPATH=services/gateway .venv/bin/python -m auri_gateway delete-device-records --data-dir /tmp/auri-gateway-data --device-id <device_id>
PYTHONPATH=services/gateway .venv/bin/python -m auri_gateway backup --data-dir /tmp/auri-gateway-data --output /tmp/auri-gateway-backup.tar.gz
PYTHONPATH=services/gateway .venv/bin/python -m auri_gateway restore --backup /tmp/auri-gateway-backup.tar.gz --data-dir /tmp/auri-gateway-restored
.venv/bin/pytest tests/contract tests/integration tests/security   # 全部，约 2.5 分钟：租约到期测试必须真实等待（合同规定租约最短 10 秒）
```

MCP 端点（`/mcp`，Agent 经 Destination 凭证领取并确认投递）使用官方 MCP Python SDK，版本精确锁定在 `services/gateway/requirements.txt`；升级 SDK 后必须重跑全部网关测试。

Agent 宿主的 SPKI 绑定桥接与联调（配置与验收状态见 `execution/acceptance/agent-hosts/`）：

```bash
.venv/bin/pip install -r packages/mcp-stdio-bridge/requirements.txt
.venv/bin/pytest tests/integration/test_agent_hosts_bridge.py
```

证书有效期与续签运维说明见 `services/gateway/OPERATIONS.md`。

iOS 探针离线单测（macOS + XcodeGen 2.46.0 + Xcode 27.1）与探针日志汇总（跨平台 Python）：

```bash
# macOS：生成工程并跑离线单测（E2）
cd execution/p0-spikes/ios-probe && xcodegen generate --spec project.yml --project .
xcodebuild test -project execution/p0-spikes/ios-probe/AuriProbeIOS.xcodeproj -scheme AuriProbeIOS -destination 'platform=iOS Simulator,name=iPhone 16'

# 日志汇总脚本单测（Linux / macOS 均可）与原始日志汇总
.venv/bin/pytest execution/p0-spikes/ios-probe/scripts/test_summarize_log.py
python3 execution/p0-spikes/ios-probe/scripts/summarize_log.py execution/p0-spikes/ios-probe/results/raw/probe-log.jsonl
```

Android 探针（在 `execution/p0-spikes/android-probe/` 下执行，需 JDK 17 与 Android SDK 35 + `adb`；详情见该目录 `README.md`）：

```bash
cd execution/p0-spikes/android-probe
./gradlew test                                                       # 离线 JVM 单测（仅 E2）
./gradlew assembleDebug                                              # 构建 Debug APK
adb install -r app/build/outputs/apk/debug/app-debug.apk             # 装机
adb shell am start -n app.auri.probe.android/app.auri.probe.MainActivity
adb shell run-as app.auri.probe.android cat files/probe-log.jsonl > results/raw/probe-log.jsonl
```

Android 本地转录（Ticket 14，工程位于 `apps/android/`，需先 `source ~/.config/auri/android-env.sh`；AAR、模型、示例音频缓存在仓库外 `~/.cache/auri/`，不入库；真机步骤与结果见 `execution/acceptance/asr-android/README.md`）：

```bash
cd apps/android
./gradlew :asr:testDebugUnitTest :app:testDebugUnitTest              # JVM 单测（109 + 25），不需要设备
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest           # 构建会校验引擎 AAR 的大小与 SHA-256，不符即失败
cd ../..
.venv/bin/pytest execution/acceptance/asr-android                    # Python 验收工具与合同输出校验（会调用 Gradle，需要 JAVA_HOME）
.venv/bin/python execution/acceptance/asr-android/prepare_model.py   # 下载并校验模型包与示例音频到 ~/.cache/auri/
export ADB_LIBUSB=1                                                  # macOS：adb 服务端可能卡在 IOKit，必须设置
execution/acceptance/asr-android/install_on_device.sh                # 把模型放进已安装 debug 应用的私有目录
execution/acceptance/asr-android/run_device_acceptance.sh            # 设备（模拟器/真机）一键验收：仪器测试（check_instrumentation.py 逐个核对 @Test 都跑了）+ 三次冷启动基准 + 合同校验
adb shell am start -S -W -n app.auri.android/.TranscribeFileActivity --es wav samples/zh.wav --es out NAME --ei warm_repeats 3   # 转录一个文件；必须带 -S
```

鸿蒙探针（DEV-003，工程位于 `execution/p0-spikes/harmonyos-probe/`）：

```bash
# 离线纯逻辑单元测试（Node >= 24，E2 证据，不能证明真机蓝牙/后台行为）
(cd execution/p0-spikes/harmonyos-probe && node --import ./tests/resolve-ts.mjs --test tests/*.test.ts)

# 原始真机日志脱敏
(cd execution/p0-spikes/harmonyos-probe && node --import ./tests/resolve-ts.mjs ./tools/redact-log.mjs results/raw/probe-log.jsonl results/raw/sanitized-probe-log.jsonl)

# Mac 上用 DevEco Studio 6.0.1 工具链构建与装机（需先在 IDE 配好自动调试签名）
(cd execution/p0-spikes/harmonyos-probe && hvigorw assembleHap -p buildMode=debug --no-daemon)
hdc install execution/p0-spikes/harmonyos-probe/entry/build/default/outputs/default/entry-default-signed.hap
hdc shell aa start -a EntryAbility -b app.auri.probe.harmonyos
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
