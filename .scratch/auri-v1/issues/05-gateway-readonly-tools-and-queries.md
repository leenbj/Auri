# 05: 网关：只读 MCP 工具与查询接口

**What to build:** Agent 可以回查发给自己的内容；手机可以查询一次提交的结果和每个 Destination 的投递状态。开工前先把这部分的输入输出补进 contracts 并加样本。

**Blocked by:** 03 网关：Agent 经 MCP 领取并确认

**Status:** ready-for-agent

- [ ] contracts 新增：列出、读取、搜索三个只读工具，以及健康检查、能力查询、操作查询、Record 状态查询四个接口；每个都有有效和无效样本，合同测试通过
- [ ] Agent 只能列出、读取、搜索发给自己的 Record；按标识读取别的 Destination 的内容被拒绝
- [ ] 列表和搜索分页返回，游标不透明；长文本不被截断后假装成功
- [ ] 手机在提交结果不确定时能按操作标识查到真实结果
- [ ] 手机能查到一条 Record 在每个 Destination 上的状态：已入库、已领取、已确认
- [ ] 健康检查不返回正文、密钥或内部拓扑
- [ ] 测试把网关当黑盒：启动真实进程和真实数据库，只通过 HTTP 和 MCP 端点交互；请求与响应用 contracts 里的 schema 校验。
