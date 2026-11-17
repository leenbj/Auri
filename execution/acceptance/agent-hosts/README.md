# Hermes 与 Claude Code：Gateway 联调

调查及测试日期：2026-11-18。当前结果见 [Hermes](hermes.md) 与 [Claude Code](claude-code.md)；软件测试不代替真实宿主和手机验收。决策状态与固定版本见 [ADR-007](../../../docs/adr/0007-agent-host-mcp-versions.md)。

## 官方资料调查

以下来源均于 **2026-11-18** 检索；文档可能随宿主版本变化。

| 宿主 | 传输与凭证 | TLS 选项与结论 |
|---|---|---|
| Hermes | [官方 MCP 指南](https://hermes-agent.nousresearch.com/docs/user-guide/features/mcp)和[配置参考](https://hermes-agent.nousresearch.com/docs/reference/mcp-config-reference)支持本机 stdio、Streamable HTTP、旧 SSE（`transport: sse`），HTTP `headers` 可传 `Authorization: Bearer …`；stdio 用 `command`、`args`、`env`。 | `ssl_verify` 默认为系统 CA，可指定 PEM CA，也可设 `false` 禁用验证；后者不安全。资料未查到 MCP 客户端逐连接 SPKI pin 的设置。 |
| Claude Code | [官方 MCP 指南](https://code.claude.com/docs/en/mcp)支持 stdio、Streamable HTTP、旧 SSE，HTTP `--header` 可传 Bearer；SSE 已弃用。 | [网络配置](https://code.claude.com/docs/en/network-config)支持 `NODE_EXTRA_CA_CERTS` 自定义 CA；资料未查到 MCP 客户端逐连接 SPKI pin 的设置。[Node 文档](https://nodejs.org/api/cli.html#node_tls_reject_unauthorizedvalue)说明 `NODE_TLS_REJECT_UNAUTHORIZED=0` 会关闭证书验证，不能用来接入 Auri，Claude Code 在某些托管运行模式也会忽略该变量。 |

因此当前使用本机 stdio 桥接，由桥接向 Gateway 发 HTTPS Streamable HTTP。CA 信任、校验主机名与 SPKI pin 不等价：证书续签或 IP 变化时不能假定 CA 配置满足 ADR-0018。无校验选项不用于 Auri。[MCP 官方 Python SDK v2.3.0](https://github.com/modelcontextprotocol/python-sdk/tree/v2.3.0)支持新旧协议；与某一宿主的实际兼容性必须靠运行该宿主确认。

## 准备

1. 在运行 Gateway 的可信局域网启动 TLS Gateway，确认配对时取得的 `public_key_sha256` 是可信的 64 位小写 SPKI 指纹（不是证书文件哈希）；参见 [证书运维](../../../services/gateway/OPERATIONS.md)。地址例如 `https://127.0.0.1:8443/mcp`。桥接不发现 mDNS、不会自动切换地址，地址变化时需更新配置；不得使用明文 URL 或 TLS 跳过校验。
2. 为两个宿主分别创建 Destination、签发凭证并准备测试 Delivery。只给对应宿主它自己的凭证；不要写进仓库、命令历史、进程参数或日志。使用独立终端设置下面三个环境变量；提示后静默输入对应凭证。两个宿主使用不同终端和不同凭证。变量只在本次会话中有效；同用户的进程可能读取进程环境。

   ```bash
   export AURI_GATEWAY_URL='https://127.0.0.1:8443/mcp'
   export AURI_GATEWAY_SPKI_SHA256='<配对时可信获取的 64 位小写指纹>'
   printf 'Destination credential: '
   read -rs AURI_DESTINATION_CREDENTIAL; printf '\n'
   export AURI_DESTINATION_CREDENTIAL
   ```

3. 在仓库根目录用 Python 3.12 创建虚拟环境并安装已锁定依赖（以下示例为 `uv`）：

   ```bash
   uv venv --python 3.12 --seed .venv
   .venv/bin/pip install -r packages/mcp-stdio-bridge/requirements.txt
   ```

环境变量是传给桥接的机密；不要开启 shell 跟踪，不要上传原始日志。`bridge.py` 从同一份仓库加载 Gateway 的已锁定 TLS 实现，因此运行机器需要完整仓库。桥接只向 stderr 输出通用失败提示，stdio 协议数据不应另存日志。

## Hermes 配置

在 Hermes 活动配置的 `mcp_servers` 下添加以下条目；路径使用仓库绝对路径。将当前终端各环境变量传给启动的 Hermes；`env` 引用由 Hermes 展开，不要把凭证字面值写入 YAML。`protocol: legacy` 是待验证的 2025 握手基线，不宣称该宿主已实测。

```yaml
mcp_servers:
  auri:
    command: "/absolute/path/to/auri/.venv/bin/python"
    args: ["/absolute/path/to/auri/packages/mcp-stdio-bridge/bridge.py"]
    protocol: legacy
    env:
      AURI_GATEWAY_URL: "${AURI_GATEWAY_URL}"
      AURI_GATEWAY_SPKI_SHA256: "${AURI_GATEWAY_SPKI_SHA256}"
      AURI_DESTINATION_CREDENTIAL: "${AURI_DESTINATION_CREDENTIAL}"
```

使用 `hermes mcp test auri` 检查发现工具；再启动 Hermes，针对该 Destination 的测试 Delivery 让它调用 `delivery_claim`、处理后用返回的 `delivery_id` 与 `lease_token` 调用 `delivery_ack`。只记录版本、是否发现工具和最终状态，不保存正文或令牌。

## Claude Code 配置

在设置了 Claude 专属 Destination 环境变量的终端、仓库根目录执行。单引号使配置保存变量引用而不是凭证；`--scope user` 修改当前用户的 Claude Code MCP 配置，隔离试验请另用临时配置并确认不会读取或写入现有配置。仅在产品负责人准备真实验收时执行。

```bash
claude mcp add --transport stdio --scope user \
  --env 'AURI_GATEWAY_URL=${AURI_GATEWAY_URL}' \
  --env 'AURI_GATEWAY_SPKI_SHA256=${AURI_GATEWAY_SPKI_SHA256}' \
  --env 'AURI_DESTINATION_CREDENTIAL=${AURI_DESTINATION_CREDENTIAL}' \
  auri -- "$PWD/.venv/bin/python" "$PWD/packages/mcp-stdio-bridge/bridge.py"
claude mcp list
```

在同一环境启动 Claude Code，检查 `/mcp` 中 auri 的工具，再针对该 Destination 的测试 Delivery 调用 `delivery_claim` 和 `delivery_ack`。不要将租约令牌、正文或原始对话记录提交。与 Hermes 的真实状态分别记录，完成后检查两个 Destination 各自状态；一个宿主的确认不能替另一个确认。

## 软件回归与边界

```bash
.venv/bin/pytest tests/integration/test_agent_hosts_bridge.py
.venv/bin/pytest tests/contract tests/integration tests/security
```

测试使用隔离目录、合成文字及临时凭证；使用 SDK 客户端并不能证明 Hermes 或 Claude Code 真正可用。桥接启动前校验 URL 和 pin 格式；证书 pin 不符时拒绝，不应通过修改 pin 或关闭校验来绕过；应通过可信配对途径查明密钥是否更换。发生续签时同一公钥可继续使用，换密钥必须重新配对。桥接当前只支持工具接口，且缓冲 Gateway 的 JSON 响应；上游若变为 SSE 或主动推送需重新设计与测试。
