# 27: 鸿蒙：录音、转录与健壮性

**What to build:** 鸿蒙 NEXT 上完成 Android 工单 16、18、19 的全部行为。做法以 Android 的对应工单为样板；验收标准与之相同，以下只列本平台的差别。

**Blocked by:** 12 G0 汇总；16 Android：屏幕按钮录一段并转成文字；18 Android：静音过滤、无语音与时长上限；19 Android：中断、断连与崩溃恢复

**Status:** ready-for-agent

**Needs human:** 需要产品负责人在 Mac 和真机上构建、装机、操作并取回日志；Agent 负责写代码、写操作步骤和整理结论。没有真机结果的项标 `NOT_RUN` 或 `BLOCKED_EXTERNAL`，不得用模拟代替。

- [ ] 满足 Android 工单 16、18、19 的全部验收标准
- [ ] 共享转录引擎通过原生绑定接入鸿蒙
- [ ] contracts 里采集状态机的事件序列样本全部通过
- [ ] 真机上用 FreeClip 验证
