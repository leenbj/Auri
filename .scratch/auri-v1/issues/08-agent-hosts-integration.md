# 08: Hermes 与 Claude Code 联调

**What to build:** 在产品负责人的电脑上，Hermes 和 Claude Code 各自连上 Gateway 的 MCP 端点，读到并确认一条文字。固定并记录两者共同支持的 MCP 版本和连接方式。

**Blocked by:** 03 网关：Agent 经 MCP 领取并确认；06 网关：局域网传输

**Status:** ready-for-agent

**Needs human:** 需要产品负责人在 Mac 和真机上构建、装机、操作并取回日志；Agent 负责写代码、写操作步骤和整理结论。没有真机结果的项标 `NOT_RUN` 或 `BLOCKED_EXTERNAL`，不得用模拟代替。

- [ ] 查清并记录 Hermes 和 Claude Code 连接自签证书加公钥绑定的 MCP 服务的配置方式，带来源
- [ ] 若某个宿主不能直连，提供本机桥接并说明原因
- [ ] 两个宿主各用自己的凭证领取并确认一条 Delivery，互不影响对方的状态
- [ ] 固定的 MCP SDK 版本和协议版本写进决策记录（ADR-007）
- [ ] 给出每个宿主的配置步骤
- [ ] 结果如实记录；某个宿主跑不通时标为外部阻塞，不写成通过
