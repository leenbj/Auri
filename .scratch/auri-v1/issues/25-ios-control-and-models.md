# 25: iOS：控制、校准与模型管理

**What to build:** iOS 上完成 Android 工单 17、20 的行为：Control Mode、校准与 Headset Profile、模型管理与转录调度。做法以 Android 的对应工单为样板；验收标准与之相同，以下只列本平台的差别。

**Blocked by:** 24 iOS：录音、转录与健壮性

**Status:** ready-for-agent

**Needs human:** 需要产品负责人在 Mac 和真机上构建、装机、操作并取回日志；Agent 负责写代码、写操作步骤和整理结论。没有真机结果的项标 `NOT_RUN` 或 `BLOCKED_EXTERNAL`，不得用模拟代替。

- [ ] 满足 Android 工单 17、20 中在 iOS 上可行的验收标准
- [ ] 界面明确告知：录音中耳机按键不可用；冷启动后不能只靠耳机手势开始
- [ ] Headset Profile 里做不到的能力标为不支持并附真机证据
- [ ] 真机上完成一次校准
