# 26: iOS：配对、发送、修订与删除

**What to build:** iOS 上完成 Android 工单 21、22、23 的行为。做法以 Android 的对应工单为样板；验收标准与之相同，以下只列本平台的差别。

**Blocked by:** 24 iOS：录音、转录与健壮性；23 Android：修订、重发与删除

**Status:** ready-for-agent

**Needs human:** 需要产品负责人在 Mac 和真机上构建、装机、操作并取回日志；Agent 负责写代码、写操作步骤和整理结论。没有真机结果的项标 `NOT_RUN` 或 `BLOCKED_EXTERNAL`，不得用模拟代替。

- [ ] 满足 Android 工单 21、22、23 的全部验收标准
- [ ] 申请“本地网络”权限；被拒绝时给出明确提示和恢复入口
- [ ] 真机上验证离开局域网后回来自动补传
