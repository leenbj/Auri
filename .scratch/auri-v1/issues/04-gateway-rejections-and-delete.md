# 04: 网关：入库的拒绝规则与删除

**What to build:** Gateway 拒绝会破坏记录可信度的提交，并支持可靠的删除：删掉的 Record 不会因为补传而复活，新版本发布时未被领取的旧版本不再投递。

**Blocked by:** 02 网关：Destination 与凭证

**Status:** ready-for-agent

- [ ] 同一 Record 同一版本、正文不同的提交返回版本冲突，不覆盖已入库的内容
- [ ] 不是录下该 Record 的设备提交或删除它时被拒绝（ADR-0020）
- [ ] 删除 Record 后：之后对它的任何提交被拒绝；未完成的 Delivery 被取消；Agent 读不到它
- [ ] 已被 Agent 确认的 Delivery 在 Record 删除后保留确认记录
- [ ] 新 Revision 发给某个 Destination 时，同一 Record 发给它的旧版本若未被领取则取消；已领取或已确认的保留，新 Delivery 标明取代了哪些版本
- [ ] 请求体里的 Record 标识或版本号与路径不一致时被拒绝
- [ ] 测试把网关当黑盒：启动真实进程和真实数据库，只通过 HTTP 和 MCP 端点交互；请求与响应用 contracts 里的 schema 校验。
