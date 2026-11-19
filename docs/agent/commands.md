# 工具链命令

这是按需阅读的文件，入口在根目录 `CLAUDE.md`。
新增工具链命令请补充到本文件。

GitNexus 查询和刷新（使用说明见 `docs/agent/code-intelligence.md`）：

```bash
/Users/ethan/.local/bin/gitnexus context claim_deliveries --repo /Users/ethan/Desktop/CODE/auri
(cd /Users/ethan/Desktop/CODE/auri && /Users/ethan/.local/bin/gitnexus analyze --index-only)
```

网关与集成/安全测试（在仓库根目录执行，依赖版本固定在 `services/gateway/requirements.txt` 与 `tests/integration/requirements.txt`）：

```bash
.venv/bin/pip install -r services/gateway/requirements.txt -r tests/integration/requirements.txt
PYTHONPATH=services/gateway .venv/bin/python -m auri_gateway serve --data-dir /tmp/auri-gateway-data --port 8443
PYTHONPATH=services/gateway .venv/bin/python -m auri_gateway serve --data-dir /tmp/auri-gateway-data --host 127.0.0.1 --port 8080 --plaintext-loopback  # 仅本机开发回环明文
PYTHONPATH=services/gateway .venv/bin/python -m auri_gateway pair --data-dir /tmp/auri-gateway-data
PYTHONPATH=services/gateway .venv/bin/python -m auri_gateway renew-cert --data-dir /tmp/auri-gateway-data --validity-days 365
.venv/bin/pytest tests/contract tests/integration tests/security   # 全部，约 2.5 分钟：租约到期测试必须真实等待（合同规定租约最短 10 秒）
```

MCP 端点（`/mcp`，Agent 经 Destination 凭证领取并确认投递）使用官方 MCP Python SDK，版本精确锁定在 `services/gateway/requirements.txt`；升级 SDK 后必须重跑全部网关测试。

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
