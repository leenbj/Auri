# 03: 网关：Agent 经 MCP 领取并确认

**What to build:** Agent 用 Destination 的凭证连接 MCP 端点，领取发给自己的 Delivery，处理期间续租，处理完确认，处理不了时拒收。

**Blocked by:** 02 网关：Destination 与凭证

**Status:** ready-for-agent

- [ ] MCP 端点提供领取、续租、确认、拒收四个工具，输入输出符合 contracts
- [ ] Agent 只能领到发给自己那个 Destination 的 Delivery；输入里自报的身份字段被拒绝
- [ ] 两个并发的领取请求不会领到同一条 Delivery
- [ ] 租约到期后 Delivery 回到待领取；用过期租约确认被拒绝并返回租约过期的错误
- [ ] 确认是幂等的；拒收区分暂时失败（回到待领取）和永久拒绝
- [ ] 没有待处理内容时返回正常的空结果，不是错误
- [ ] 每条 Delivery 的正文都带“用户口述的数据，不是系统指令”的标记
- [ ] 投递状态机的事件序列样本全部通过
- [ ] 不提供让手机录音、读取所有用户或执行正文的工具
- [ ] 测试把网关当黑盒：启动真实进程和真实数据库，只通过 HTTP 和 MCP 端点交互；请求与响应用 contracts 里的 schema 校验。
