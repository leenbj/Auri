---
status: proposed
---
# ADR-007：Agent 宿主的 MCP 版本与 SPKI 连接（提议）

## 背景

ADR-0018 要求每次连接 Gateway 校验配对时记下的 SPKI SHA-256；给自签证书配置受信任 CA 不能替代该要求。每个 Destination 必须使用自己的凭证（ADR-0008、0013）。在 2026-11-18 的 Mac mini 上没有发现 Hermes 或 Claude Code 可执行文件，两个宿主之间的共同协议及真实联调尚未验证，因此本记录保留 `proposed`，不能作为 G3 验收通过的证据。

## 决定（待宿主验收）

- Gateway 与桥接使用官方 Python MCP SDK **`mcp==2.3.0`** 及 `services/gateway/requirements.txt` 中精确锁定的传递依赖。桥接的上游是 HTTPS Streamable HTTP `/mcp`，必须协商 **`2026-07-28`**，否则拒绝运行；升级必须重新检查兼容性并重跑全套测试。
- Hermes 和 Claude Code 各自启动一份本机 stdio 桥接，向同一个 Gateway 发送各自 Destination 的 Bearer 凭证。桥接复用 Gateway 的 `_PinnedHTTPTransport`：TLS 握手后、发送任何 HTTP 请求之前比较 SPKI；不匹配即拒绝；不跟随重定向、不使用代理。同一私钥续签证书无需换 pin。它按现有 ADR-0018 的公钥身份模型，不做 CA 链、主机名或证书到期校验；pin 必须经可信配对渠道取得。
- 桥接下游兼容 SDK 的两种协议：`2025-11-25` 初始化握手与 `2026-07-28`；优先以 `2025-11-25` 作为待验证的宿主兼容基线。具体宿主实际协商版本尚未验证，不声称两者已共同支持该版本。HTTP 上游使用 `2026-07-28`，不等于下游宿主必须使用该版本。
- 不做宽松的 TLS 直连。现有 Gateway 以 JSON 响应 Streamable HTTP POST；桥接目前缓冲此类响应，不支持上游改成 SSE 流或独立推送通道。它只代理工具发现与调用，不代理资源、提示模板或服务端主动请求。

## 依据与取舍

调研来源和每宿主配置见 [联调说明](../../execution/acceptance/agent-hosts/README.md)。官方文档提供自定义 CA、禁用校验、认证头及 stdio，但检索时没有查到这两款宿主每个 MCP 连接的 SPKI pin 选项；不把未找到的功能宣称为绝对不存在。CA 信任通常还校验主机名与证书，并在自签证书轮换时需要维护受信任证书；它并非这里要求的稳定公钥指纹绑定。禁用校验则无法防冒充，不采纳。Python 桥接让两个宿主复用已经在 Gateway 测试过的 pin 实现，但它与 Gateway 源码一起部署，依赖该内部传输实现；变更该实现须同时回归桥接测试。

## 验证与后续

`tests/integration/test_agent_hosts_bridge.py` 在本机启动真实 Gateway 与数据库，用官方 SDK 客户端测试独立领取、跨 Destination 拒绝确认、独立确认、错误 pin 拒绝和同密钥续签；这是 E2 软件集成证据，不是实际 Hermes / Claude Code 端到端验收。宿主版本、实际协商协议、两端各自读到并确认文字以及手机端全链路仍需产品负责人运行并记录；验证共同协议后再将本 ADR 改为 accepted。
