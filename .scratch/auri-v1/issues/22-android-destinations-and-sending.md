# 22: Android：Destination 管理与发送方式

**What to build:** 用户在手机上创建 Destination 并生成给 Agent 用的凭证。选了自动发送的在转录完成后立即收到初稿；选了预览确认的在用户确认后才收到。用户能看到每个 Destination 的状态。

**Blocked by:** 21 Android：配对并把文字送到网关；03 网关：Agent 经 MCP 领取并确认

**Status:** ready-for-agent

**Needs human:** 需要产品负责人在 Mac 和真机上构建、装机、操作并取回日志；Agent 负责写代码、写操作步骤和整理结论。没有真机结果的项标 `NOT_RUN` 或 `BLOCKED_EXTERNAL`，不得用模拟代替。

- [ ] 创建 Destination 时必须选择自动发送或预览确认，没有默认值
- [ ] 生成或轮换凭证前通过生物识别；凭证只显示一次
- [ ] 自动发送的初稿标为未审核
- [ ] 预览确认的 Destination 在用户确认前不产生 Delivery
- [ ] 每条内容可标为备忘或请求执行
- [ ] 详情页显示每个 Destination 的状态：已入库、已领取、已确认；不把已确认说成已处理
- [ ] 真机上完成：创建 Destination，Agent 用凭证领到一条自动发送的文字
