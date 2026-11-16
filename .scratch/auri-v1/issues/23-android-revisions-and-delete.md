# 23: Android：修订、重发与删除

**What to build:** 用户可以改字，每次保存产生新的 Revision。新版本不会自动重发给已收到旧版本的 Agent，由用户确认。用户可以删除整条 Record，或只删除音频。

**Blocked by:** 22 Android：Destination 管理与发送方式

**Status:** ready-for-agent

**Needs human:** 需要产品负责人在 Mac 和真机上构建、装机、操作并取回日志；Agent 负责写代码、写操作步骤和整理结论。没有真机结果的项标 `NOT_RUN` 或 `BLOCKED_EXTERNAL`，不得用模拟代替。

- [ ] 每次保存产生一个新的不可变 Revision，旧的可回看
- [ ] 新 Revision 默认不自动重发；界面显示哪些 Destination 收到过旧版本，由用户逐个确认重发
- [ ] 删除 Record 后本地和 Gateway 上都不可见，离线补传不会让它复活
- [ ] 界面说明 Agent 已读走的内容无法收回
- [ ] 可单独删除音频而保留文字；可设置音频保留期，默认转录成功后 30 天
- [ ] 可按时间、关键词、发送状态筛选；可导出为 TXT 和 Markdown
- [ ] 日志默认不含正文、音频和凭证
