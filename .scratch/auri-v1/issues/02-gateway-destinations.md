# 02: 网关：Destination 与凭证

**What to build:** 已配对的设备可以创建 Destination、为它签发或轮换专属凭证、撤销它。提交 Revision 时，Gateway 为请求里列出的每个 Destination 生成一条 Delivery。

**Blocked by:** 01 网关：配对并入库

**Status:** ready-for-agent

- [ ] 创建 Destination 必须带发送方式，缺省时创建失败
- [ ] 凭证只在签发响应里出现一次，库内只存哈希；查询 Destination 的响应不回显凭证
- [ ] 轮换后旧凭证立即失效
- [ ] 提交 Revision 时为每个列出的 Destination 生成一条待领取的 Delivery；目标列表为空时只入库
- [ ] 向不存在、已撤销或不允许该用途的 Destination 发送被拒绝
- [ ] 重复提交不产生重复的 Delivery
- [ ] 测试把网关当黑盒：启动真实进程和真实数据库，只通过 HTTP 和 MCP 端点交互；请求与响应用 contracts 里的 schema 校验。
