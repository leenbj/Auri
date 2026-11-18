# Ticket 17 最终验证：Android 耳机手势控制与校准（2026-11-18）

分支 `ticket/17-headset-control`，基线 `origin/integration/auri-v2` = `e92cfa7`（`git fetch origin` 后它没有前进，本分支已经包含它，无需合并）。被测代码 = 提交 `3bbf754`；之后的提交只改文档（`c473da8` 是 README，本文件与 CLAUDE.md 的用例数是最后一个提交），所以下面所有套件跑的都是同一份代码。

**证据等级**：下面所有自动化结果都是 **E2**（JVM 假对象，或一次性无头模拟器）。**用真手机 + FreeClip 的校准与手势验收是 `NOT_RUN`**：这台机器没有手机也没有耳机；产品负责人的步骤在 `README.md` 第 4 节。没有任何一项被写成 E3。

## 1. 套件全新运行结果（都是这次在本机上跑的，不是引用提交信息）

| 套件 | 命令（在仓库根目录；Gradle 都带 `--no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2`） | 结果 | 在哪测的 |
|---|---|---|---|
| JVM 单测，四个模块 | `cd apps/android && ./gradlew :capture:cleanTest :platform:cleanTestDebugUnitTest :app:cleanTestDebugUnitTest :asr:cleanTestDebugUnitTest :capture:test :platform:testDebugUnitTest :app:testDebugUnitTest :asr:testDebugUnitTest --continue …` | **通过**：`:capture` 225、`:platform` 136、`:app` 92、`:asr` 112，共 **565**；0 失败、0 错误、0 跳过（数字取自 Gradle 的 `build/test-results` XML，文件是刚生成的） | 这台 Mac，35 秒（编译产物是最新的，测试全部重跑） |
| 构建 APK | `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :platform:assembleDebugAndroidTest …` | **通过**（`app-debug.apk` 55.9 MB、`platform-debug-androidTest.apk` 38.4 MB、`app-debug-androidTest.apk` 1.3 MB；最后一个输入没变，`UP-TO-DATE`） | 这台 Mac，20 秒 |
| 模拟器验收 | `AURI_GRADLE_ARGS="--no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2" execution/acceptance/android-control/run_emulator_acceptance.sh` | **通过**：`:platform` 仪器测试 7 个 OK、`:app` 仪器测试 2 个 OK，`check_capture_instrumentation.py` 逐个核对每个 `@Test` 都真的跑了：7 个 0 个问题、2 个 0 个问题 | API 35 arm64 google_apis 无头模拟器（E2），全程 115 秒。脚本先取了 `~/.cache/auri/emulator.lock`；结束后锁目录没有了、一次性 AVD 删了、没有 emulator / qemu / adb 进程。`avdmanager` 打印了一条 `devices.xml` 加载警告，模拟器照常起来了、测试照常跑完（Ticket 16 的结果文件里记过同一条） |
| Python 验收工具 | `AURI_GRADLE_ARGS="--no-daemon -P… -D…" .venv/bin/pytest execution/acceptance/android-control` | **106 passed**，0 失败、0 跳过（含真实调用 Gradle 的合同输出校验） | 这台 Mac，58 秒 |
| 全仓库 Python | `.venv/bin/pytest tests/contract tests/integration tests/security` | **492 passed**，0 失败、0 跳过 | 这台 Mac，205 秒 |

模拟器上测得的事实（**是测量值，不是通过 / 失败的判据**；API 35 arm64 google_apis 模拟器上，没有任何音频在播放）：`media_button_session=null injected=4 delivered=0`，应用接线下 `media_key_decisions=0`。也就是说，系统里有我们的会话（`AuriHeadsetControl`，声明 `PAUSED`），但注入的 4 个媒体键一个都没交给它。**这不等于“耳机手势不能用”，也不等于“能用”**：注入的键不是耳机发的、模拟器也没有蓝牙耳机；真机上系统会不会把键交给一个只声明 `PAUSED`、什么都不播放的会话，要由产品负责人在 README 第 4 节第 6 步测量。项目禁止用静音音乐保活，所以我没有去绕开它。

## 2. 验收清单逐项对照

| # | 条目 | 覆盖它的测试 / 文件 | 证据 |
|---|---|---|---|
| 1 | 控制模式关着时，媒体事件不会开始录音 | `HeadsetControlChainTest`：控制模式没开：媒体来源根本没有被激活…、控制模式关闭之后：来源被释放，迟到的旧回调也不能开始录音…、对照：控制模式开着的时候，同一个按键会进入准备；`HeadsetControlAdapterTest`；设备端 `HeadsetMediaKeyOnDeviceTest.mediaKeysReachNothingWhileControlModeIsOff`、`ControlWiringOnDeviceTest.mediaKeysNeverReachTheCoordinatorWhileControlModeIsOff`（真实应用接线下协调器什么都没收到、系统里没有我们的会话） | E2（JVM + 模拟器） |
| 2 | 平台事件归一化成带来源与序号的控制事件；一次手势绝不先开始又立刻停止 | `HeadsetEventNormalizerTest`（21：每种媒体键 → 平台事件 → 命令、400 ms 去抖、余波、来源只在 `isExternal` 时才是 `HEADSET_REMOTE`）；`HeadsetControlChainTest`：进入协调器的事件带着来源、序号与档案；两个来源共用一套序号、一次手势发出一串事件：只开始一次，之后的录音不会被同一手势的余波停掉；`CompositeControlAdapterTest`（11）；`MediaKeysTest`（8） | E2 |
| 3 | 准备中的停止取消准备、不被吞掉；封存期间的过时切换键不会开始下一段 | `HeadsetControlChainTest`：准备中再按一次切换键：取消准备，回到待命，输入请求被释放、准备中收到明确的暂停键：同样取消准备、封存期间按下并在封存期间送到的切换键：被忽略，也不会排队到封存之后、封存期间按下、封存完成之后才送到的切换键：…协调器当成过时命令丢掉；`HeadsetEventNormalizerTest`：录音准备中的停止不会被去抖吞掉… | E2 |
| 4 | 校准向导覆盖四个场景；状态机（含跳过 / 重做 / 放弃）有测试 | `CalibrationWizardStateTest`（11：固定顺序、跳过、重做、放弃、完成后不能再动、没起作用只适用于第二到第四个场景）；`CalibrationObservationTest`（13）与 `ControlDecisionPairingTest`（10，同一个文件）；`CalibrationFindingsTest`（59）；`ControlViewModelTest`（36） | E2 |
| 5 | 产出的 Headset Profile 能用 Python 工具按合同校验（没有改 `contracts/`）；没测过的能力保持“未测试” | `HeadsetProfileContractTest`（4）、`ControlContractOutputTest`（3，把向导与归一化器的输出写成 JSON）、`ProfileIdentityTest`（4）；Python：`control_contract_check.py` 与 `test_control_contract_check.py`、`test_control_contract_output.py`（106 个里的一部分）；`CalibrationFindingsTest`：没做、跳过、没观察到的能力保持 `NOT_TESTED`，环境不是“真手机 + 真耳机”时只给 E2 证据，`startAfterProcessTermination` 永远未测试 | E2；真机档案的校验命令写在 README 第 4 节第 9 步，**`NOT_RUN`** |
| 6 | 开始 / 停止 / 失败三种反馈能分清；手动按钮始终都在 | `FeedbackTest`（6）、`FeedbackHubTest`（6）、`NoSoundIntoBluetoothTest`（2：不往蓝牙路由里播放任何声音）；`ControlViewModelTest`：屏幕上的开始与停止按钮在任何耳机状态、任何校准阶段下都在…、三种反馈的屏幕文字都能读到，互相不同…；设备端 `FeedbackPlayerOnDeviceTest`（每种反馈各自的静音通知、通知渠道没有声音） | E2；**凭振动手感分清三种反馈是主观判断，只有人能做：`NOT_RUN`**（README 第 4 节第 5 步） |
| 7 | 真手机 + FreeClip | 产品负责人步骤：`README.md` 第 4 节（含 `dumpsys media_session` 测量、手势、校准向导、取回档案、结果表模板） | **`NOT_RUN`**：没有手机、没有耳机 |

**已知阻断 / 需要范围决策（不是我能自行决定的）**：API 35 模拟器上，没有音频播放时系统一个媒体键也不交给我们的会话（上面测得 `delivered=0`；README 第 6 节与合同缺口 G7）。如果真机上也是这样，“只在控制模式开着时占用媒体键通道”这条路在“什么都没在播放”时收不到耳机手势，要产品负责人决定怎么办（例如换一种控制通道、或接受“只有媒体会话活跃时才有手势”）。项目规则禁止静音音乐保活，所以代码里没有任何绕开办法。

## 3. 对共享文件的改动（相对 `origin/integration/auri-v2`，一共 5 个已有文件）

| 文件 | 改动 |
|---|---|
| `CLAUDE.md` | “命令”一节追加了 Ticket 17 的命令块（+15 行），用例数更新为 225 + 136 + 92 + 112 |
| `apps/android/app/src/main/kotlin/app/auri/android/AuriRuntime.kt` | 接线：协调器的 `control` 换成组合控制适配器（屏幕按钮 + 耳机媒体键）；新增有默认值的构造参数 `headsetProfileStore`；暴露 `controlViewModelFactory` |
| `apps/android/app/src/main/kotlin/app/auri/android/MainActivity.kt` | 首页外面套了 `AppRoot`（首页 / 耳机控制两页）；新增 `ControlViewModel` |
| `apps/android/platform/src/main/AndroidManifest.xml` | 加 `VIBRATE`（普通权限，开始 / 停止 / 没能开始的振动） |
| `apps/android/platform/src/test/kotlin/app/auri/platform/system/PlatformManifestTest.kt` | **改了一个已有测试的期望**：权限白名单从六个变成七个（加入 `android.permission.VIBRATE`），测试名“六个”→“七个”。这是为了让清单测试继续钉住“权限恰好是这些”，只加了功能必需的这一个普通权限，没有放宽别的；请审阅 |

没有动：`CaptureCoordinator.kt` 与 `:capture` 已有的公共类型、`:asr`、`HomeViewModel.kt`、`contracts/`、`tests/contract/`、`services/gateway/**`、`.github/`。其余 59 个文件都是新增的。

GitNexus（`detect_changes`，对比 `origin/integration/auri-v2`，索引已在当前 HEAD 上重建）：397 个符号、64 个文件、28 个受影响流程，风险等级报 `critical`——这是新功能的体量（59 个新文件）造成的；受影响的流程全部落在本 Ticket 的新代码（`CalibrationSection` / `ControlScreen` / `Check_scenario`）以及 `MainActivity.onStartPressed` 开头的首页开始流程（`OnStartPressed → Start`；`MainActivity.kt` 是改过的已有文件），与上面“只改了 5 个已有文件”一致。

## 4. 变异验证与复审（细节在 README 第 9 节）

- 变异：这一轮（第三个 worker）新增规则的变异共 14（N 系列）+ 26（Q / S 系列）个，**全部被断言失败抓住**；每个变异都立即还原并核对无差异；一个等价变异（N5）与一个没运行的等价变异（R6）已写明理由。前两个 worker 留下的 K / S / W / D 系列变异我没有逐个复现，当作它们的报告，不是我的验证。
- 外部只读复审（`codex exec -s read-only`）：8 轮、45 条发现，修了 25 条真缺陷，其余是分诊后记录的假设；**复审没有收敛到 CLEAN**（第 8 轮仍有 3 条，都是需要两个独立事件落进毫秒级窗口的竞态，我封顶不改）。根本原因是“事件与协调器决定靠先后推断配对”这个设计；根治办法（让决定带上命令标识）要改共享的 `CaptureDecision` 与 `CaptureCoordinator.kt`，超出本 Ticket，见 README 第 7 节假设 24。

## 5. 没做到的 / 有保留的

- 真机 + FreeClip：`NOT_RUN`（清单第 7 项，以及第 5 项里真机档案的校验、第 6 项里振动手感的主观判断）。
- 复审循环没有收敛到 CLEAN（见上）；这与用户规则“复审直到没有 bug”不符，我如实写在这里。
- 第四步（锁屏）的“没反应”声明在真机上几乎不会被采纳（README 假设 20），这一步想得到结论要靠观察到的事件。
- 从首页点“开始录音”会顺带开启控制模式并一直开着（共享代码 `HomeViewModel.kt` 的既有行为，我没有改），需要产品负责人决定（README 假设 14）。
- 最后两次运行（Python 验收工具与全仓库 Python 套件）开始时，机器的交换区已用 3.6 GB（总 4 GB），超过我被要求的 3 GB 门槛；当时系统空闲内存 65–67%，我自己没有同时跑别的 Gradle 或模拟器；我没有等够 15 分钟就跑了（交换区被谁占着我不知道，macOS 上等待也不一定让它降下来），两次都顺利跑完。
- 开发过程中有两次 Gradle 构建被别的 worker 的整机范围 `./gradlew --stop` 杀掉，重跑即可，与代码无关。
