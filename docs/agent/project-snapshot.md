# 项目现状快照（2026-11-17，已过期，仅作历史参考）

这是从 `CLAUDE.md` 挪出的状态记录，截至原文所写日期，**不代表当前进度**。当前进度以 git 为准，工单在 `.scratch/auri-v1/issues/`。原文中的“下文”和“本文件”按历史语境保留；现行工具链命令及补充位置见 `docs/agent/commands.md`。

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
