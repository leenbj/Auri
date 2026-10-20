# DEV-001 基线记录

记录日期：2026-10-20。本文件只陈述实际观测到的事实；没有执行过的验证一律标 `NOT_RUN` 或 `BLOCKED_EXTERNAL`，不用模拟或推断替代。

## 仓库状态

| 项 | 值 |
|---|---|
| 分支 | `main` |
| HEAD | `93c13179f1dee7e283c28f13814083c6b4f51796`（`docs: 添加 VoiceBridge PRD 与 CLAUDE.md`） |
| 起始工作区 | 干净 |
| 本任务新增（未提交） | `GLOSSARY.md`、`docs/adr/`（0002、0008、0009、0013）、PRD v1.1 与 `CLAUDE.md` 的修改、目录骨架、`.gitignore`、本记录 |

## 开发机（本机）

| 项 | 值 |
|---|---|
| 系统 | macOS 26.6.2（build 25G83），arm64 |
| git | 2.50.1 |
| Swift / clang | Swift 6.3.3、Apple clang 21.0.0（仅 Command Line Tools） |
| Python | 系统 3.9.6；`uv` 0.11.30 可用，用于安装网关所需的较新 Python（官方 MCP Python SDK 的最低版本以锁定版本的声明为准） |
| Docker | 29.7.2 |
| sqlite3 | 3.51.0 |
| 缺失 | Homebrew、cmake、Node |

## 三端工具链与硬件

| 项 | 状态 | 说明 |
|---|---|---|
| Xcode / iOS SDK | `BLOCKED_EXTERNAL` | 仅装有 Command Line Tools，`xcodebuild`、`xctrace` 不可用；需用户安装 Xcode 并登录 Apple ID |
| DevEco Studio / 鸿蒙 SDK、`hdc` | `BLOCKED_EXTERNAL` | 未安装 |
| Android Studio / SDK、`adb`、Java | `BLOCKED_EXTERNAL` | 未安装 |
| 代码签名身份 | `BLOCKED_EXTERNAL` | `security find-identity` 返回 0 个有效身份 |
| iPhone / 鸿蒙 NEXT 手机 / Android 手机 | `NOT_RUN` | 用户称三端真机都有；型号与系统版本尚未记录，待工具链装好后从连接设备读取 |
| HUAWEI FreeClip 2 / FreeClip 及其他耳机 | `NOT_RUN` | 型号待 P0 记录时填写；所有耳机×手机×系统组合初始状态均为 `NOT_TESTED` |
| 云 ASR 账号（阿里云） | `BLOCKED_EXTERNAL` | G2 前需要用户提供账号与地域 |
| Hermes | `BLOCKED_EXTERNAL` | 本机 `~/.local/bin/hermes` 是包装脚本，指向的 `~/.hermes/hermes-agent/venv/bin/python` 不存在，无法运行；实际安装位置待用户确认 |
| 网关主机 | `BLOCKED_EXTERNAL` | 系统、Docker、公网域名与 TLS 的情况待用户确认 |

## 禁止操作规则（对所有开发 Agent 生效）

1. 录音输入只能是蓝牙耳机麦克风（`bluetooth_only`）；任何情况下不得回退手机麦克风（ADR-002）。
2. 不用播放无声音乐、伪造通话、私有 API、越狱、隐藏录音指示等方式保活或绕过后台限制。
3. VAD 或静音不得当作停止手势。
4. 开发 Agent 无权删需求、改用手机麦克风或宣布验收通过；关键场景不可行时记录 BLOCKER 并提交范围决策。
5. 不得改测试预期、删安全负例、加无条件成功的 mock 来提高通过率；mock 的 `verified=true` 不是真机证据。
6. 没有真机、账号或签名时标 `NOT_RUN` 或 `BLOCKED_EXTERNAL`，不得伪造日志。
7. 不 reset、不 clean、不删除用户未提交的修改。
8. 真实个人口述音频、密钥、签名材料不得提交仓库；日志默认不含正文、音频或凭证。
9. 依赖、SDK、MCP 版本与模型（含哈希与许可证）固定版本，禁止追踪上游 `main`。

## 下一步

- 用户：安装 Xcode 并登录 Apple ID（iOS 探针 DEV-004 的前置条件），随后安装 DevEco Studio、Android Studio。
- 并行、不依赖真机：DEV-002（领域 schema 与状态机样本）、DEV-015（网关认证与入库）。
- 依赖 Xcode 与真机：DEV-004（iOS 探针，P0 最先做）。
