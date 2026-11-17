# Claude Code 联调结果

日期：2026-11-18；环境：产品负责人的 macOS Mac mini。各项状态独立记录，软件模拟客户端不是 Claude Code 实测。

| 检查项 | 状态 | 证据或原因 |
|---|---|---|
| 官方传输、认证头与 TLS 配置调查 | PASSED | [调研与来源](README.md#官方资料调查)，记录 stdio / HTTP / SSE、Bearer、自定义 CA 与禁止使用的不安全开关。 |
| 本机发现 Claude Code 可执行文件 | BLOCKED_EXTERNAL | `command -v claude` 和常见 Homebrew、用户本地、npm、应用目录检查无可执行文件；未读取或更改真实 Claude Code 凭据或配置。宿主版本未知。 |
| Claude Code 在隔离配置下添加桥接并发现 Gateway 工具、协商协议 | NOT_RUN | 未发现 Claude Code，无法执行隔离的 `claude mcp add` 和连接测试；实际协议未知。 |
| Claude Code 领取并确认自己的 Delivery | NOT_RUN | 未发现 Claude Code；未登录、使用用户配额或真实个人内容。 |
| 两 Destination 互不改变确认状态 | PASSED | `tests/integration/test_agent_hosts_bridge.py` 用两个官方 SDK 客户端与两个独立桥接进程验证；仅为 E2，不证明 Claude Code 运行。 |
| 真机上传并由 Claude Code 端到端领取确认 | NOT_RUN | 需要安装并配置实际 Claude Code，且手机产品端与人工验收尚未就绪。 |
