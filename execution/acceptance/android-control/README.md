# Ticket 17 验收：Android 耳机手势控制与校准

对应 `.scratch/auri-v1/issues/17-android-headset-control.md`（GitHub issue #18），PRD 引用：FR-HS-001/004（控制链分三层：耳机手势 → 平台事件 → 业务命令；单事件切换映射）、FR-REC-001（严格蓝牙输入：提示不得向蓝牙通路出声）、NFR-012（不靠假播放保活）、ADR-0014、ADR-0016。

**状态（2026-11-18）**

- **没有任何真机（E3）结果**：本机没有 Android 手机，也没有 FreeClip。“真机 + FreeClip 校准”是 **`NOT_RUN`**，产品负责人的步骤在第 4 节；模拟器没有蓝牙耳机，媒体键是 `adb shell input keyevent` 注入的，**最多是 E2**。
- **有一个需要范围决策的发现（第 6 节）**：在 Android 15（API 35）模拟器上实测，**没有音频在播放时，系统不会把媒体键交给我们的 MediaSession**（声明 PAUSED、PLAYING 或不声明状态都一样，6 种组合 0/6）；同一进程里真有音频在播时 6/6 送达。也就是说“戴着 FreeClip 双击就能开始录音”这个前提，**在没有音频播放时可能根本成立不了**，而项目规则禁止靠静音音频或假播放去换取它。这是 E2、一个模拟器镜像，真机可能不同——校准向导就是用来在真机上回答这个问题的。开发 Agent 无权自行决定怎么办，所以**记为 BLOCKER 并请求范围决策**，产品代码没有绕它。屏幕上的“开始录音 / 停止”按钮永远可用，是手动兜底。
- 其余清单项在 JVM 与模拟器上（E2）都有测试覆盖，结果见第 2、10 节；每条重要规则做过变异验证（第 9 节）。

证据等级沿用 PRD 第 5.2 节：JVM 单测与模拟器 = **E2**；真机 = E3。没有真机/账号/签名的项标 `NOT_RUN` 或 `BLOCKED_EXTERNAL`，不用模拟或伪造替代。

## 1. 这里有什么

| 路径 | 作用 |
|---|---|
| `apps/android/capture/`（`:capture`，纯 Kotlin/JVM） | `control/`：平台无关的按键（`RawMediaInput`、`MediaKind`）、`HeadsetEventNormalizer`（归一化为合同的 `normalized_control_event` + 去抖 + 时间戳取舍）、`ControlProfile`（平台事件 → 业务命令的映射，构造不出非法映射）、`ActiveControlProfile`、`HeadsetControlStatus`。`calibration/`：`CalibrationWizard`（四步校准的状态机）、`CalibrationFindings`（只从观察算结论）、`HeadsetProfile`（逐字段镜像 `headset-profile.schema.json`）、`ProfileIdentity`。`feedback/`：`Feedbacks`（开始 / 停止 / 没能开始三种反馈的内容与“协调器决定 → 反馈种类”的映射）、`FeedbackHub`。`testFixtures` 里还有 `MediaSessionDump`（`dumpsys media_session` 的解析器，设备测试用）。**没有改任何已有的 `:capture` 公共 API**（只新增文件） |
| `apps/android/platform/`（`:platform`） | `control/`：`AndroidMediaButtonSource`（唯一的 `MediaSession`，只在控制模式开着的 `activate`…`deactivate` 之间存在）、`MediaKeys`（系统键值 → 平台无关的按键）、`HeadsetControlAdapter`（实现 `ControlAdapter`，只在控制模式开着时占通道，失败如实报告不抛异常）、`CompositeControlAdapter`（与屏幕按钮合并）。`feedback/AndroidFeedbackPlayer`（振动 + 静音通知）、`system/AndroidScreenLockMonitor`、`storage/FileProfileStore`（档案写在应用私有目录，先写临时文件再改名） |
| `apps/android/app/`（`:app`） | `control/ControlViewModel`（**一个 `StateFlow<ControlUiState>`**，状态类型与全部操作的说明在文件头的注释块里）、`control/ControlWiring`（生产接线，没有别的逻辑）、`ui/ControlScreen`、`ui/CalibrationScreen`（刻意简陋的 Compose 页，只摆状态，不做判断）、`ui/AppRoot`（“录音 / 耳机控制”两个页面的切换） |
| `control_contract_check.py` | 用真实 `contracts/schemas/*.json` 校验向导与归一化器写出的 JSON；还有从规格推导、合同 schema 表达不了的语义规则（档案：不猜厂商/固件/左右耳，没有 `volume_change`，兼容结论只能来自真机 E3，E2 证据不得冒充真机；事件：来源如实、时间戳取舍、停止键只停止）。同一个脚本的 `--profile` 模式校验**从真机取回的**档案（做法沿用 Ticket 16 的 `capture_contract_check.py`，读它、不复制、不改它，不回显来自输出文件的任何值） |
| `run_emulator_acceptance.sh` | 一次性无头模拟器验收（E2）：取共享锁 → 建 AVD → 起模拟器 → 安装 → 仪器测试 → 核对每个 `@Test` 都真的跑了 → **无论成败都**杀模拟器、删 AVD、放锁 |
| `control_support.py`、`test_*.py` | 工具共用的 Gradle 调用（读 Ticket 16 的 `capture_support.py`）与这些工具自己的测试；`test_control_contract_output.py` 会真实调用 Gradle |
| `probes/MediaKeyRoutingProbe.kt.txt` | 第 6 节发现的**诊断探针源码（证据附件）**：不是产品代码，也不是验收测试，**不要提交到任何 `src/` 目录**。里面有一段只用来做正向对照的 `AudioTrack`，仅限一次性测量 |

共享文件的改动（都是追加式的最小改动）：`apps/android/app/.../AuriRuntime.kt`（构造参数加了 `headsetProfileStore`，协调器的控制适配器换成合成适配器，接上耳机控制的接线）、`MainActivity.kt`（用 `AppRoot` 放两个页面）、`apps/android/platform/src/main/AndroidManifest.xml`（加普通权限 `VIBRATE`）与 `PlatformManifestTest.kt`（权限集合“恰好六个”改为“恰好七个”，这个测试本来就是要钉死权限集合的）、仓库根 `CLAUDE.md`（只在“命令”一节追加）。**没有改动**：`CaptureCoordinator.kt`、`SqliteRecordLibrary.kt`、`HomeViewModel.kt`、`HomeScreen.kt`、`settings.gradle.kts`、`gradle/libs.versions.toml`、`app/build.gradle.kts`、`:asr` 的任何文件、`contracts/`。

## 2. 清单逐项对照

| # | 清单项（Ticket 17） | 覆盖它的测试 / 文件 | 证据等级 |
|---|---|---|---|
| 1 | 控制模式关着时，媒体键事件不会开始录音（真实协调器 + 假媒体来源） | `HeadsetControlChainTest`（10，整条链是真的，假的只有系统一侧）：`控制模式没开：媒体来源根本没有被激活，按键不触发任何东西`（没有激活、没有输入请求、没有事件、状态仍是 DISARMED）、`控制模式关闭之后：来源被释放，迟到的旧回调也不能开始录音`、对照 `控制模式开着的时候，同一个按键会进入准备`、`耳机控制开不起来时屏幕按钮仍然能开始`。`HeadsetControlAdapterTest`（13）：激活 / 释放 / 激活代数 / 框架抛异常的各条路径，以及“已过校验、正在递送的事件要递送完关闭才返回”。设备上 `HeadsetMediaKeyOnDeviceTest`、`ControlWiringOnDeviceTest`：真实系统的会话列表（`dumpsys media_session`）里，控制模式关着没有我们的会话，开着有且已激活，关掉后释放；真实应用接线下控制模式关着协调器什么都没收到 | E2 |
| 2 | 事件归一化为合同事件（来源、序号、单调时间戳），一串重复事件绝不会“先开始又立刻停止” | `HeadsetEventNormalizerTest`（21）：来源只在系统能证明是外接设备时才是 `headset_remote`，否则 `unknown_remote`（不猜左右耳、不假设存在原始双击）；每种键对应的平台事件与命令；停止键只能停止（即使档案把 pause 映射成切换）；抬起 / 长按重复 / 不认识的键不产生事件；同一原始事件递送两次只算一次；`一次手势发出一串事件：先开始再立刻停止永远不会发生`；窗口边界差一纳秒；被去抖丢掉的事件不延长窗口；没有原始标识的事件绝不因“标识相同”被吞；时间戳取手势发生时刻、不可信时退回接收时刻。`HeadsetControlChainTest`：`进入协调器的事件带着来源、序号与档案；两个来源共用一套序号`、`一次手势发出一串事件：只开始一次，之后的录音不会被同一手势的余波停掉`。`CompositeControlAdapterTest`（10）：两个来源同时来事件时，编号、旁听者与命令订阅者看到同一个先后顺序。`ControlContractOutputTest` + `test_control_contract_output.py`：真实的归一化器写出的事件文件通过真实 schema 与语义规则 | E2 |
| 3 | 录音准备中（ROUTING）的停止 / 切换不会被吞掉；封存中（FINALIZING）发出的过时切换键不会开始下一段 | `HeadsetControlChainTest`：`准备中再按一次切换键：取消准备，回到待命，输入请求被释放`、`准备中收到明确的暂停键：同样取消准备`、`封存期间按下并在封存期间送到的切换键：被忽略，也不会排队到封存之后`、`封存期间按下、封存完成之后才送到的切换键：带着按下的时刻，协调器当成过时命令丢掉`。`HeadsetEventNormalizerTest`：`录音准备中的停止不会被去抖吞掉：窗口过后第二次手势照常通过`、`去抖按手势时刻比较，不按递送时刻`。变异验证见第 9 节（K9、K10） | E2 |
| 4 | 校准向导覆盖四个场景（没录音时的事件 / 录音中第二次手势 / 停止后再开始 / 锁屏），状态机每一步（含跳过、重做、放弃）都有测试 | `CalibrationWizardStateTest`（11）：没开始时什么都不能做；四个场景按固定顺序走；**每一步都能跳过**（记为 SKIPPED 并进入下一步，最后一步跳过则完成）、**重做**（留在原地、尝试次数加一、不影响其他步骤）、**放弃**（之后没有档案、也不能再操作）；完成后冻结；“没起作用”只适用于后三步；第一步只观察、其余步骤任何播放类事件都当作切换；没有观察的整轮。`CalibrationObservationTest`（18）：怎样记录观察、怎样把控制事件与协调器的决定一对一配对（屏幕按钮不算耳机手势、被忽略 / 被拒绝的命令不算开始也不算停止、丢了决定也不会把点击记成耳机手势、重做清掉这一步的观察、跳过则作废）。`CalibrationFindingsTest`（32）：四个场景各自怎样才算观察到成功（锁屏未知不算前台、路由没确认不算开始成功、第一次开始不算“停止之后再开始”、锁屏期间录音被掐断不算撑过、手势开始被取消或受阻后别的开始录上的不算、“没反应”声明只回答它所指的那次手势）；没观察到 / 没声明 / 跳过的能力保持 NOT_TESTED；成功与“没效果”混合是 PARTIAL；进程被杀后启动永远未测试。`ControlViewModelTest`（31）：向导每一步在界面状态里的呈现与按钮可用性、证据等级由环境决定、放弃 / 保存失败 / 重复点击等路径 | E2 |
| 5 | 向导产出的 Headset Profile 符合合同；没有任何观察 ⇒ 每项能力都是未测试 | `HeadsetProfileContractTest`（4）：类型逐字段镜像 `headset-profile.schema.json`，合同自带的有效样本解码再编码一字不差，六个无效样本全部被拒绝；`ControlContractOutputTest`（3）把档案写成 JSON，`test_control_contract_output.py`（9）真实调用 Gradle 后交给 `control_contract_check.py`：真实 schema + 语义规则，每个环境下“没有观察”的档案五项能力都是 `NOT_TESTED`、没有证据等级与证据 id（`test_a_wizard_run_with_no_observations_leaves_every_capability_untested`，另独立读文件断言一次）；`test_control_contract_check.py`（82）用手造的坏数据证明检查器会拦，每条规则变异验证（第 9 节） | E2 |
| 6 | 开始 / 停止 / 没能开始三种反馈能分清（振动 + 屏幕文字 + 通知文字）；手动按钮永远在 | `FeedbackTest`（6）：三种反馈的屏幕文字、通知文字、振动节奏两两不同；“协调器决定 → 反馈种类”的映射（要等路由确认、录音真的起来才给“已开始”；被忽略的命令不给反馈；用户自己取消准备不算失败）；`FeedbackHubTest`（6）；`ControlViewModelTest`：手动按钮在每一种状态下都存在，可用性只来自状态。设备上 `FeedbackPlayerOnDeviceTest`（2）：三种反馈各发一条通知、文字与定义一致，渠道无声音 / 不自带振动 / 重要性低 | E2（振动在手上的感觉要真机、要有人，见第 4 节第 5 步） |
| 7 | 真机 + FreeClip 校准 | 第 4 节的步骤 | **`NOT_RUN`**（无真机、无 FreeClip）。模拟器的系统会话测试与注入键测量（第 6 节）是 E2，不能代替 |

另有几项跨项的要求：

- **不向蓝牙通路出声**：`NoSoundIntoBluetoothTest`（2）扫 `capture`、`asr`、`platform`、`app` 四个模块（含 debug 源集）的源码：没有任何播放声音的接口，通知渠道只许 `setSound(null, null)`，媒体会话从不声明 `STATE_PLAYING`；守卫自己有测试证明每一种被禁写法注入后都被抓到。与 Ticket 16 的“不存在手机麦克风路径”守卫并存，两者都通过。
- **只有一个新增权限**：`PlatformManifestTest` 钉死权限集合，新增的只有普通权限 `VIBRATE`。
- **`:asr` 与已有 `:capture` 公共 API 未改**：本 Ticket 没有修改 `apps/android/asr/` 下任何文件，`:capture` 只新增文件。

### 设备（模拟器）上实际跑的东西

`run_emulator_acceptance.sh` 在一次性无头模拟器（Pixel 6 配置，Android 15 / API 35 / google_apis / arm64-v8a，RAM 2048 MB）上跑：

| 套件 | 测试数 | 内容 |
|---|---|---|
| `:platform` 仪器测试（`control`、`feedback`、`system` 三个包） | 7 | `HeadsetMediaKeyOnDeviceTest`（4）：系统会话列表里的会话生命周期（关着没有、开着有且已激活且声明 PAUSED、关掉后释放、再开又有）、媒体键注入的**测量**（只记录、不当判据）；`FeedbackPlayerOnDeviceTest`（2）；`ScreenLockOnDeviceTest`（1）：熄屏后监测器报告“已锁定” |
| `:app` 仪器测试（`app.auri.android.control` 包） | 2 | `ControlWiringOnDeviceTest`：真实应用接线下，控制模式关着协调器什么都没收到、系统里没有我们的会话；开着时有会话，屏幕上的开始按钮走同一条链路、没有蓝牙耳机就按严格规则拒绝（零路由请求、零录音器），反馈给出“没能开始”，关掉后会话释放 |

`check_capture_instrumentation.py`（Ticket 16 的工具，读它不复制）逐个核对这 9 个测试都真的跑了并通过（只看 `OK (N tests)` 不够：被忽略的不计数，假设失败也算“通过”）。

## 3. 在本机怎么跑

前置：`source ~/.config/auri/android-env.sh`（JDK 17、Android SDK 35、模拟器）；仓库根目录的 `.venv`（Python 3.12，依赖 `tests/contract/requirements.txt`）。系统自带的 python3.9 不能用来跑仓库测试。

```bash
source ~/.config/auri/android-env.sh
cd apps/android

# JVM 单元测试（不需要设备）
./gradlew :capture:test :platform:testDebugUnitTest :app:testDebugUnitTest :asr:testDebugUnitTest --no-daemon

# 构建 debug APK 与两个仪器测试 APK
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :platform:assembleDebugAndroidTest --no-daemon

# Python 验收工具（含真实调用 Gradle 的合同输出校验；约 1 分钟）
cd ../..
AURI_GRADLE_ARGS=--no-daemon .venv/bin/pytest execution/acceptance/android-control

# 模拟器验收（E2；会取共享锁、建并删除一次性 AVD；约 4–5 分钟）
AURI_GRADLE_ARGS=--no-daemon execution/acceptance/android-control/run_emulator_acceptance.sh
```

校验某个输出目录：`.venv/bin/python execution/acceptance/android-control/control_contract_check.py <目录> --preset jvm`；校验从真机取回的档案：`.venv/bin/python execution/acceptance/android-control/control_contract_check.py --profile <headset-profile.json>`。

**强制重跑测试**：Gradle 会把输入没变的测试任务判为 `UP-TO-DATE` 而不执行。要确认测试真的重新跑了，先清掉再跑（`./gradlew :capture:cleanTest :platform:cleanTestDebugUnitTest :app:cleanTestDebugUnitTest :asr:cleanTestDebugUnitTest` 加上上面的测试任务），数字以 `build/test-results/**/*.xml` 为准。

**共享机器的规矩**（脚本已经遵守，手工跑时也要）：同一时刻只许一台模拟器，靠 `~/.cache/auri/emulator.lock`（`mkdir` 原子）互斥，owner 写分支名；超过 3 小时且没有 qemu/emulator 进程的锁视为残留；无头启动 `-no-window -no-audio`；用完 `adb emu kill`、删 AVD 与磁盘镜像、释放锁；`export ADB_LIBUSB=1`（macOS 上 adb 可能卡在 IOKit）；Gradle 用 `--no-daemon`，用完 `./gradlew --stop`。

## 4. 产品负责人：真机上用 FreeClip 校准（`NOT_RUN`）

**目的**：用 E3 证据回答三件事——① 控制模式开着、**没有任何东西在播放**时，FreeClip 的手势到底有没有到达 Auri（第 6 节的发现在真机上成不成立）；② 到达的话是哪种平台事件（播放 / 暂停 / 切换 / 耳机挂机键）；③ 录音中能不能用手势停止、停止后能不能用手势再开始、锁屏时呢。**做不到的步骤标 `BLOCKED_EXTERNAL` 并写原因，不要用模拟或猜测补；“没有收到任何耳机事件”是一个完全合法、而且很可能出现的结果，如实记录它。**

**需要**：一部 Android 12 及以上、arm64 的手机（开发者选项里打开 USB 调试）、已与 HUAWEI FreeClip（或 FreeClip 2）配对并连接的耳机、数据线、已按第 3 节配好工具链的 Mac。

**不要做的事**：不要为了“让耳机键有反应”去放音乐、静音音频或假装在通话——项目规则禁止这样保活，也会污染这次测量；不要为了能录而改用手机麦克风；不要把真实个人口述的录音或文字提交进仓库；`adb shell dumpsys audio` 与 `dumpsys media_session` 的原始输出含设备地址与其他应用的包名，留在本机，只把相关几行摘要写进结果文件。

1. **确认设备**。`export ADB_LIBUSB=1; adb devices`：只应看到这一部手机（多台时 `export ANDROID_SERIAL=<序列号>`）。记下手机型号、Android 版本、耳机型号；耳机固件版本系统不暴露，在华为智慧生活 App 里看，看不到就写“未知”。
2. **构建并安装**。`cd apps/android && ./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`。本 Ticket 不需要装转录模型：录出来的 Record 会显示“等待转录模型”，无碍。
3. **看初始状态**。手机蓝牙设置里确认 FreeClip 已连接。打开 Auri，点上方“耳机控制”。应当分开显示四行：`控制模式：关`、`耳机按键通道：没有占着…`、`采集状态：…`、`耳机档案：没校准过，用通用映射…`，下面是“开始录音 / 停止”两个按钮和“耳机手势校准”。**截图①**。
4. **手动按钮兜底**（不依赖耳机手势）。点“开始录音”，依次授权麦克风、“附近的设备”、通知；进入“录音中”后，再点“停止”。每一步页面上的“最近一次反馈”应当变化。
5. **三种反馈能不能分清**（主观，E3）：开始 = 一下短振（120 ms）+ 通知“录音中”；停止 = 两下短振 + 通知“录音已停止”；没能开始 = 一下长振（700 ms）+ 通知“录音没能开始，请在屏幕上重试”。让手机放在口袋里或桌上，不看屏幕，各触发几次（没能开始：断开 FreeClip 再点“开始录音”），记下每次你凭振动判断是哪一种、对不对。**这一步只有人能做**，结果记在表里，不要估计。
6. **开启控制模式并测量通道**。点“开启控制模式”。页面应显示 `控制模式：开`、`耳机按键通道：已向系统要到，在等耳机按键（能不能真收到，以校准结果为准）`。**此时什么都不要播放**，在 Mac 上执行 `adb shell dumpsys media_session > /tmp/media-session-on.txt`，看三处：`Media button session is …`（`null` 还是别的应用，还是 `app.auri.android/AuriHeadsetControl/…`）、`Last MediaButtonReceiver: …`、列表里 `AuriHeadsetControl` 那个会话的 `state=…`（应是 `PAUSED`）。**截图②** 与这三行摘要。这一步直接回答第 6 节的问题：系统有没有把媒体键指派给我们。
7. **做耳机手势**：对着 FreeClip 做你平常用来开始录音的手势（比如双击），做三次，每次间隔 2 秒以上。看页面的“采集状态”“最近一次反馈”有没有变化；`adb logcat -d -s AuriControl:I AuriCapture:I > /tmp/auricontrol.txt`（只有状态与原因，没有设备名、文字或标识）。**没有任何反应就写“没有反应”，并保留第 6 步的 `dumpsys` 摘要**。
8. **校准向导**（页面下方“耳机手势校准”）。设置页：如果这是真手机，会出现“我确认：这是真手机，戴的是真耳机”的复选框（模拟器不会出现）——勾选；“耳机型号”填 `FreeClip 2`（可不填）；点“开始校准”。然后四步，每一步都有“这一步做完了 / 跳过 / 重做 / 做了手势，没反应 / 放弃”：
   - 第 1 步（没有录音时）：用耳机做手势 2–3 次。页面显示“收到的耳机事件”（播放 / 暂停 / 切换各几次）；这一步只观察、**不会**开始录音。点“这一步做完了”。
   - 第 2 步（录音中）：先点屏幕上的“开始录音”，等进入“录音中”，再用耳机做一次手势，看录音会不会停。没反应就点“做了手势，没反应”，再点“这一步做完了”。
   - 第 3 步（停止之后）：录音停下来后，用耳机手势开始新的一段。没反应同样点“做了手势，没反应”。
   - 第 4 步（锁屏）：开始录音 → 锁屏 → 用耳机手势 → 解锁。看录音有没有继续、手势有没有用；没反应点“做了手势，没反应”。
   - 做不了的步骤点“跳过”（对应能力保持 `NOT_TESTED`，**未测就是未测，不能写通过**）。
9. **取回档案**。结果页点“保存档案”，然后 `adb shell run-as app.auri.android cat files/headset-profile.json > /tmp/headset-profile.json`（debug 构建才能 `run-as`），再 `.venv/bin/python execution/acceptance/android-control/control_contract_check.py --profile /tmp/headset-profile.json`：应当显示“通过”并列出五项能力的状态（不列证据 id）。检查失败就把输出原样记下来，**不要改档案让它通过**。
10. **写结果**：`execution/acceptance/android-control/results/<日期>-<手机型号>-freeclip.md`，原始日志放 `results/raw/`（已被 `.gitignore` 的 `execution/**/raw/` 忽略）。状态词用 PRD 的枚举，每项能力单独写：

   | 耳机 | 手机 / 系统 | 能力 | 状态 | 证据 | 备注 |
   |---|---|---|---|---|---|
   | | | 前台开始 / 停止 | | 档案、截图②、logcat | |
   | | | 蓝牙麦克风采集中用手势停止 | | 档案 | |
   | | | 锁屏后录音继续 | | 档案 | |
   | | | 锁屏时停止后用手势重新开始 | | 档案 | |
   | | | 进程被杀后用手势开始 | `NOT_TESTED`（这四个场景测不到：进程被杀后没有任何组件能收到按键） | | |
   | | | 三种反馈能否凭振动分清 | | 你的判断 | |

**真机上我们不知道、需要这次观察的事**（代码里的数字是假设，不是实测）：FreeClip 的双击发出的是哪种平台事件（播放 / 暂停 / 切换 / 耳机挂机键）、是否同时发出一串；去抖窗口 400 ms 够不够（太短会把一次手势的余波当成第二次，太长会把有意的第二次手势吞掉）；`InputDevice.isExternal` 在这部手机上对蓝牙耳机是否为真（决定来源是 `headset_remote` 还是 `unknown_remote`）；手机放音乐时按键会被送给音乐应用，这是系统的正常规则，不是故障。

## 5. 设计要点（iOS、鸿蒙照着做）

- **三层分开**：耳机物理手势 → 平台事件（play / pause / toggle / next / previous，加 iOS 的音量变化）→ 业务命令（START / STOP / TOGGLE / IGNORE）。应用看不到“双击”，也不猜左右耳；`normalized_control_event` 的 `source` 只有系统能证明来自外接设备时才是 `headset_remote`，其余是 `unknown_remote`。
- **控制模式 = 通道占用**：控制模式关着，系统里没有我们的媒体会话（`dumpsys media_session` 里看得到）；开着才创建并激活。关闭时先让所有在途的旧回调失效再碰框架，释放抛异常也不会让按键继续往下送。控制模式不持久化，进程重启后是关闭的（不自动开麦）。
- **归一化与去抖是纯逻辑**（`:capture`）：只有按下算一次手势；同一原始标识只算一次；窗口内（400 ms，假设）一次手势的一串事件只产生第一个；停止键永远只能停止；没有原始标识就是 `null`，绝不拿占位值冒充；时间戳取手势发生时刻（平台给了且可信），否则接收时刻——协调器靠它丢掉“封存期间按下、封存之后才送到”的过时命令。
- **每个框架调用都可能抛异常**：包住、照样清理、照样到达终态，绝不向协调器抛；激活失败如实报 `UNAVAILABLE`，不装作开着；屏幕按钮不受影响。
- **校准向导只从观察算结论**：没做、跳过、没观察到的能力保持 `NOT_TESTED`；“没收到事件”不推断为失败（可能是用户没按、耳机断了），只有用户明确声明“做了手势，没反应”才算 `UNSUPPORTED`；取证环境不是真机 + 真实耳机时，所有能力只能是 `NOT_TESTED`、最多带 E2 证据（合同不允许 E2 的兼容结论）；真机必须用户明确勾选确认，模拟器只认强特征（宁可漏判，因为误判真机会让真实校准永远拿不到 E3）。两条精确性规则（复审后补的，各有测试）：**“没反应”声明只回答它所指的那次手势**——第四步（锁屏）里它说不了“录音撑没撑过锁屏”，也只有锁屏中手势已经把录音停下来（之后用户要试的才是重新开始）时，才算对“锁屏时重新开始”的否定；**一次手势开始的结局属于它自己**——手势让录音进入准备之后，紧接着离开准备的那个决定必须是“到达录音中”才算开始成功，准备被取消或受阻后用户改点屏幕按钮录上的那一段不能算在手势头上。
- **反馈没有声音**：耳机麦克风正在（或即将）录音，任何提示音都会进入蓝牙通路。开始 = 一下短振，停止 = 两下短振，没能开始 = 一下长振，外加屏幕文字与静音通知；“已开始”要等路由确认、录音真的起来才给；用户自己取消准备不算失败。屏幕文字先于播放更新，振动或通知出任何问题用户仍能在屏幕上看到发生了什么。
- **界面只摆状态**：`ControlUiState` 把控制模式、耳机按键通道、采集状态、耳机档案分成四件事，不合并成一句话（通道要到了 ≠ 耳机手势一定能到）；手动按钮的可用性只来自状态。

## 6. 已知限制：媒体键在没有音频播放时送不到会话（BLOCKER，需要范围决策）

**现象（E2，Android 15 / API 35 google_apis arm64 模拟器；注入的键，没有耳机）**。创建一个活跃的 `MediaSession`，分别声明 无状态 / `PAUSED` / `PLAYING`，用两种方式注入播放 / 暂停键：

| 条件 | 状态 × 注入方式 | 系统自己的说法（`dumpsys media_session`） | 会话回调收到 |
|---|---|---|---|
| 没有任何音频在播放 | 无 / PAUSED / PLAYING × `input keyevent 85`、`cmd media_session dispatch play-pause`（6 种组合） | `Media button session is null`、`Last MediaButtonReceiver: null` | **0 / 6** |
| 同一进程里真有一个 `AudioTrack`（全零采样）在播放（**只作正向对照，产品代码里没有也不会有**） | 同上 6 种组合 | `Media button session is app.auri.platform.test/…`（就是我们的会话） | **6 / 6** |

正向对照证明注入链路本身是通的，所以第一行的“送不到”是系统的选择规则，不是注入手段的毛病：**系统按“哪个应用真的在出声”来选媒体键的接收方，而不是按会话声明的播放状态**。仪器测试里的测量行（`measured sdk=35 media_button_session=null injected=4 delivered=0`，应用接线下 `measured app media_key_decisions=0`）每次验收都会重新记录。复现探针：`probes/MediaKeyRoutingProbe.kt.txt` 的头注释。

**含义**：产品设想的路径是“FreeClip 双击 → 手机收到媒体键 → 控制模式下的媒体会话 → 开始录音”。如果真机的规则与模拟器一致，**在没有任何东西播放时这条路径收不到键**。这是 E2、一个系统镜像，真机（尤其是 OEM 改过的系统）可能不同——第 4 节第 6、7 步会给出真机上的答案。

**本 Ticket 做了什么 / 没做什么**：

- 做了：控制模式开着时我们的会话确实存在、已激活、声明 `PAUSED`（`HeadsetMediaKeyOnDeviceTest` 逐项断言，并用 `dumpsys` 的会话列表验证“关着没有、关掉后释放”）；键一旦到达，整条链（归一化、去抖、过时命令、反馈）有 E2 测试；屏幕按钮永远可用；校准向导会如实记录“没收到”。
- **没做**：靠播放静音音频 / 假播放 / 假通话换取按键（PRD NFR-012 与 CLAUDE.md 明令禁止）；把会话声明成 `PLAYING`（实测没用，6 种组合里的 `PLAYING` 同样 0 次送达，而且“声明在播放但什么都没播”本身需要合规评审，见 `execution/p0-spikes/android-probe/README.md` 的 S8）；`MediaSession.setMediaButtonReceiver` 回退路径（**未测量**，只是一个假设）。

**请产品负责人在以下方向里决定（开发 Agent 无权自行决定，也无权删需求或宣布验收通过）**：

1. **接受现状**：耳机手势是“尽力而为”的快捷方式，能不能用由真机校准结论决定；屏幕按钮是主路径；V1 对用户的说明不承诺“耳机开始录音”。
2. **评审一条回退路径**：用 `MediaSession.setMediaButtonReceiver` 指向清单里声明的广播接收器，让系统在没有“正在出声的会话”时把键交给它。这条路**未测量**；要先回答的设计问题：接收器在进程被杀后也会被系统拉起，与“进程恢复后回到 DISARMED、不自动开麦”（第 12 章）如何共存；广播能被别的应用伪造吗；Android 14+ 从后台启动麦克风类前台服务的限制（`while-in-use`）对它意味着什么。
3. **其他手势来源**：合同里已有 `volume_gesture`，Android 上用音量键的路线（ADR-0014 在 iOS 上的做法）本 Ticket 没有实现。

## 7. 假设（做决定时我没有问人，下面每一条都可以推翻）

1. **去抖窗口 400 ms、手势时刻最多比接收时刻早 5 秒仍可信、记住最近 16 个原始标识**：全部没有实测依据，要按真机事件日志（FreeClip × 手机）校准；代码里集中在 `ControlConfig`，有测试钉住边界（差一纳秒仍是同一次手势，刚好满窗口就是新的手势）。
2. **没校准时的通用映射**：play → START，pause → STOP，toggle → TOGGLE，next / previous → IGNORE，档案编号 `generic-media-control`、版本 1。校准保存之后只改“亲眼看到开始过 / 停止过”的事件种类（两样都做过是切换，只开始过是开始，只停止过是停止），其余保持默认。
3. **档案编号**：`android-` 加型号里小写字母与数字的部分（`FreeClip 2` → `android-freeclip-2`），型号没填就是 `android-headset`，版本 1；厂商与固件一律 `null`（未知不猜）；`raw_gesture_visible` 恒为 `false`。
4. **控制模式的含义**：开 = 向系统要媒体键通道、协调器进入就绪；它不开麦、不常开监听（ADR-0016），开始录音仍要一个手势或屏幕按钮；不持久化，进程重启后关闭。
5. **来源判定**：`KeyEvent.getDevice()?.isExternal` 为真才是 `headset_remote`，为假或系统没说都是 `unknown_remote`。
6. **锁屏**：`locked = !interactive || keyguardLocked`（熄屏也算锁定）；读不出来就是“未知”，未知不算“前台”。
7. **校准向导的临时映射**：第一步只观察（所有命令都是 IGNORE，观察期间不会开始录音）；之后的步骤里任何播放类事件都当作切换，这样耳机发哪种事件都能开始也能停止，向导再把“实际观察到的”记下来。
8. **证据 id 的格式**：`calibration:<运行编号>:<步骤>:attempt-N:<观察名>:<环境标签>`，环境标签是 `real-device` / `emulator` / `unconfirmed` / `unit-test`；运行编号只允许 1–80 个字母数字点下划线连字符，不会带设备名。
9. **没有任何可保存结论的结果不保存**，也不替换以前的档案；保存失败时结果仍在，可以再试，当前在用的映射不变。
10. **反馈**：开始 = 一下 120 ms，停止 = 两下 80 ms（间隔 120 ms），没能开始 = 一下 700 ms；通知渠道静音、重要性低、不自带振动；这些节奏是否“凭手感能分清”是真机、要人的事（第 4 节第 5 步）。
11. **录音中关闭控制模式不丢录音**：录音中点“关闭控制模式”，先停止录音、等这次录音保存完（回到待命）才真正关闭；开始校准时控制模式没开就先开。
12. **TDD 的接缝没能先问人**：这次是被派发的 worker，没有人可问，我按“归一化器与映射、控制适配器、向导与结论规则、视图模型、反馈映射”定了接缝，测试都写在这些接口上，不测内部。可以推翻。
13. **接手说明**：本 Ticket 前一个 worker 在 Orca 重启时丢失，我没有它的记忆，只接手了它留在这个 worktree 里的代码；它留下的每一项我都重新跑过测试再提交，没有以提交信息为准。
14. **【需要产品负责人决定】从首页点“开始录音”会顺带开启控制模式，之后它一直开着**：这是已有共享代码的行为（`HomeViewModel.kt` 的 `CoordinatorControls.start()`：协调器没开启就先开启），我没有改。后果：用户手动录过一段之后，协调器处于就绪状态——也就是本 Ticket 里的“控制模式开”——耳机的媒体会话一直被占着，耳机手势可以开始下一段录音，直到用户在“耳机控制”页把它关掉；界面如实显示“控制模式：开”。这与规格第 17 条（“没开 Control Mode 时耳机手势不会触发录音，平时听歌不会误录”）的精神是否冲突，是产品决定：要么接受（用户已经主动录过音、界面看得到开关），要么让首页的开始在那段录音结束后把控制模式恢复成关（要改共享文件 `HomeViewModel.kt`，超出本 Ticket “最小追加”的范围，所以没动）。（复审第 3 轮 P1）
15. **去抖的取舍**：窗口（假设 1 的 400 ms）内的第二个事件一律当作同一次手势的余波丢掉，包括真的停止键——这样才能保证“一串事件绝不先开始又立刻停止”（清单第 2 项）；代价是用户在 0.4 秒内连按两次想“开始后立刻取消”会被合并成一次开始。窗口过后的第二次手势照常取消准备（有测试，清单第 3 项）。两个要求在窗口内互相冲突，没有真机事件日志没法更精确。（复审第 1 轮 P1）
16. **事件时间戳的可信度**：平台给的“按下时刻”只有不晚于接收时刻、且不早于 5 秒时才采信，否则退回接收时刻；一个延迟超过 5 秒才送到的事件会被当作“刚发生的”，极端情况下协调器可能把它当成新命令。数字与假设 1 一样要靠真机事件日志校准。（复审第 1 轮 P1）
17. **复审里没有改的几条**（理由在第 9 节的表里）：媒体会话释放抛异常后不重试、锁屏监测器 `unregisterReceiver` 抛异常后不重试、“关闭控制模式时刚保存完就被排在前面的开始命令抢先”这一极窄竞态、决定流（256 条缓冲）溢出后向导会留着过时的待配对事件（要协调器积压 256 条决定，现实中到不了）、校准向导启动时不从协调器快照取当前状态、模拟器识别只认强特征（Cuttlefish 之类认不出来；认不出来的环境默认“未确认”，证据仍只算 E2）。

## 8. 合同缺口与建议（没有改 `contracts/`，只记录）

| # | 现象 | 涉及字段 / 文件 | 建议 |
|---|---|---|---|
| G7 | 合同的 `platform_event` 里没有“停止键（`KEYCODE_MEDIA_STOP`）”和“耳机挂机键（`KEYCODE_HEADSETHOOK`）”：归一化时只能把停止键并入 `pause`、挂机键并入 `toggle`，原始键值留在 `raw_event_id` 里。停止键因此只能靠 `raw_event_id` 里的 `key:86` 与普通暂停区分，“停止键永远只停止”这条规则只存在于适配器内部，合同层面看不出来 | `common.schema.json#/$defs/platform_event`、`adapter.schema.json#/$defs/normalized_control_event` | 增加 `stop` 与 `headset_hook` 两个平台事件，或明确规定它们并入哪个事件、并把“停止键只能映射成 STOP”写进合同 |

（G1–G6 是 Ticket 16 记下的，见 `execution/acceptance/android-capture/README.md` 第 7 节；G7 是本 Ticket 的编号。）

## 9. 测试的牙齿（变异验证）与 TDD 的诚实说明

没有为每个切片严格先红后绿：一部分切片是把测试和实现一起写好再跑，测试第一次就通过——Python 检查器就是这样（实现与 82 个测试都是我写的，第一次全绿，而这恰恰是该警惕的信号）。补救办法是**变异验证**：每次只改坏被测代码的一条规则，确认对应测试因断言失败而变红（不是编译 / 导入错误），再恢复并核对与原文件一致。变异脚本是一次性的、不入库。

### 接手后在最终验证里重新跑的（E2）

**JVM：控制链、向导、结论规则，26 个变异，全部被抓住。** 每个变异只改一条规则，**跑完立刻还原并核对与原文件一致**，再跑 `:capture` 的全部测试（适配器相关的 K1–K11 再加 `:platform`）；“被抓住”要求至少有一个测试用例因断言失败而红（编译错误不算，算无效变异）。“抓住它的测试”取自失败用例清单，是我挑出来说明这条规则由谁守着的，不是全部。

| 编号 | 改坏的规则 | 红了几个用例 | 抓住它的测试（举例） |
|---|---|---|---|
| K1 | 控制模式关闭后，迟到的旧回调仍然递送 | 3 | `HeadsetControlAdapterTest`：关闭之后才送到的旧回调什么都不触发，重新开启后上一次激活的旧回调依然无效 |
| K2 | 关闭控制模式不释放媒体来源 | 3 | `HeadsetControlAdapterTest`：开启后…关闭后来源被释放，之后不再递送；重复开启与重复关闭是幂等的 |
| K3 | 激活失败也报告通道已开 | 1 | `HeadsetControlAdapterTest`：激活失败，返回 false、状态是不可用，来源被清理 |
| K4 | 一次手势的一串事件不去抖（先开始再立刻停止） | 6 | `HeadsetControlChainTest`：一次手势发出一串事件，只开始一次；`HeadsetControlAdapterTest`：被去抖合并的按键不递送 |
| K5 | 来源一律说成耳机 | 3 | `HeadsetControlChainTest`：进入协调器的事件带着来源、序号与档案；`ControlContractOutputTest` |
| K6 | 序号不递增 | 6 | `ControlContractOutputTest`：来源如实，序号递增；`HeadsetControlAdapterTest`：关闭再开启 |
| K7 | 同一原始事件递送两次不去重 | 2 | `HeadsetEventNormalizerTest`：同一个原始事件被递送两次只算一次；原始标识的记忆有上限 |
| K8 | 停止键也可能被映射成开始 | 1 | `HeadsetEventNormalizerTest`：停止键只能停止，即使档案把 pause 映射成切换 |
| K9 | 去抖窗口大到（60 秒）把准备中的停止吞掉 | 19 | `HeadsetControlChainTest`：准备中再按一次切换键、准备中收到明确的暂停键；`HeadsetEventNormalizerTest`：录音准备中的停止不会被去抖吞掉（**清单第 3 项**） |
| K10 | 协调器不再丢掉“封存期间按下、封存之后才送到”的过时切换键（改的是 `CaptureCoordinator.kt`） | 2 | `HeadsetControlChainTest`：封存期间按下、封存完成之后才送到的切换键；`CaptureStalenessTest`（Ticket 16 的，同一条规则）（**清单第 3 项**） |
| K11 | 时间戳一律用接收时刻 | 4 | `HeadsetControlChainTest`：同上；`HeadsetEventNormalizerTest`：去抖按手势时刻比较，不按递送时刻 |
| S1 | 跳过的步骤，观察没有作废 | 1 | `CalibrationFindingsTest`：跳过的步骤什么都不算 |
| S2 | 重做不清掉这一步的观察 | 3 | `CalibrationFindingsTest`：重做之后只看最后一次尝试；`CalibrationObservationTest`：重做清掉这一步的观察 |
| S3 | 放弃之后还能产出档案 | 1 | `CalibrationWizardStateTest`：每一步都能放弃，之后没有档案，也不能再操作 |
| S4 | 第一步（只观察）也允许声明“没效果” | 1 | `CalibrationWizardStateTest`：“没起作用”只适用于第二到第四个场景 |
| S5 | 没跑完整轮也能产出档案 | 3 | `CalibrationFindingsTest`：没有完成的校准出不了档案；`CalibrationWizardStateTest` |
| S6 | 屏幕按钮的事件也算耳机事件 | 2 | `CalibrationObservationTest`：屏幕按钮开始和停止都不算耳机手势 |
| S7 | 一开始就当作已经封存过一段 | 1 | `CalibrationFindingsTest`：锁屏中手势开始，但这一轮从没封存过任何录音 |
| S8 | 第一步不是只观察（耳机事件会开始录音） | 1 | `CalibrationWizardStateTest`：校准期间的临时映射，第一步只观察 |
| W1 | 不是真机也给出兼容结论（E3） | 3 | `CalibrationFindingsTest`：模拟器、未确认、单测环境一律只是 E2；模拟器上的“没效果”声明也只是 E2 |
| W2 | 没观察到也没声明就当作不支持 | 22 | `CalibrationFindingsTest` 里凡是要求“没有结论就是未测试”的用例 |
| W3 | 进程被杀后启动也从观察里推出结论 | 3 | `CalibrationFindingsTest`：真机完整做完，进程终止后启动始终未测试 |
| W4 | 成功与没效果混合也算支持 | 1 | `CalibrationFindingsTest`：一项支持一项不支持是 PARTIAL |
| W5 | 锁屏时没在录音也当作录音着 | 1 | `CalibrationFindingsTest`：锁屏开始时没在录音，也不算“录音能在锁屏时继续” |
| W6 | 一项检查没有结论，整项能力也给出结论 | 22 | 同 W2（`CalibrationFindingsTest`） |
| W7 | 开始又停止都观察到的事件种类映射成开始 | 1 | `CalibrationFindingsTest`：命令映射只采用亲眼看到的 |

**Python 检查器**（`control_contract_check.py`，在提交的版本上重跑）：29 个变异（原 31 个编号里 C18、C23 是被证明永远触发不到、已删除的规则），**29 个全部被抓住**，每个都是断言失败而不是语法 / 导入错误，还原后与原文件一致。其中 C10（兼容结论不要求 E3）最初存活，补了一个 E4 用例；C18、C23 存活是因为那两条规则是死代码（schema 已经拦住，或根本触发不到），所以删规则而不是为它们硬凑测试。

**第一次批处理的结果作废，是我的脚本有缺陷（已修、已全部重跑）**：脚本只在最后统一还原，**没有在每个变异之后还原**，于是 K9 把去抖窗口改成 60 秒之后，后面的 K10、K11、S1–S8、W1–W7 全是背着这个坏代码跑的，失败数 20 多个只是因为底子已经坏了，那些“被抓住”什么也没证明。我是看到 S3（改向导的放弃规则）的“抓住它的测试”竟是归一化器的用例才起疑的。修正：每个变异后立刻还原并 `diff` 核对；只读这一轮真正跑过（且先清过）的模块的结果，不读上一轮残留的 XML；只把带源文件位置的 `e: file://…` 当作编译错误。重跑时 K1、K11 一度被误判成编译错误：其实是 Kotlin 编译守护进程第一次启动失败的提示行（`e: The daemon has terminated unexpectedly on startup attempt #1`），构建随后正常跑完了测试；修正分类后单独重跑，两个都被抓住（上表）。

### 外部只读复审（codex，3 轮 17 条发现）与处理

用户规则要求“完成后用 codex 复审，直到没有 bug”。我用 `codex exec -s read-only` 审了三轮（第 1 轮：`:capture` 的控制与校准；第 2 轮：`:platform` 的适配器与存储；第 3 轮：`:app` 的视图模型与接线），它只读代码、不改文件、不接手任务——**我把它当外部审阅工具，而不是第二个 agent**；用户规则里“单一 agent”与“用 codex 复审”两条并存，这样读是我的判断，请确认。三轮结论都是 `VERDICT: FINDINGS`。**每条我都先读了源码再分诊**，没有照单全收：8 处是真缺陷（修了，测试先红后绿，再做变异验证；1.4 的并发部分与 2.5 是同一处修复），其余的记在这里与第 7 节。

| # | 发现（codex 的级别） | 处理 | 守着它的测试 / 不改的理由 |
|---|---|---|---|
| 1.1 | 去抖窗口（400 ms）内相隔很近的明确停止 / 切换键，在准备中也被丢掉，取消不了准备（P1） | **不改**，记为假设 15 | 与清单第 2 项“一串事件绝不先开始又立刻停止”在窗口内互相冲突；窗口过后照常取消（`HeadsetControlChainTest`：准备中再按一次切换键） |
| 1.2 | 延迟超过 5 秒才送到的事件改用接收时刻，封存完成之后会被当成新命令（P1） | **不改**，记为假设 16 | 时间戳不可信时没有更好的依据；数字要靠真机事件日志 |
| 1.3 | 锁屏步骤里一条“没反应”声明，同时变成“重新开始”和“录音撑过锁屏”的否定（P1） | **已修** | `CalibrationFindingsTest`：锁屏步骤只声明没效果…（两个场景）、锁屏步骤里手势停止发生在没锁屏的时候…；原有的“锁屏步骤声明没效果：…重新开始是 UNSUPPORTED”**没有改、继续通过**；变异 R1–R3 |
| 1.4 | 控制事件与决定只按命令配对：并发回调下编号顺序与到达协调器的顺序可能不一致而配错；决定流（256 条缓冲）溢出会留下过时的待配对事件（P1） | 并发部分**已修**（同 2.5）；溢出部分**不改** | 溢出要协调器积压超过 256 条决定，一次手势至多产生几条，现实中到不了；根治要把事件标识带进协调器的决定里（改 `:capture` 公共 API），超出本 Ticket |
| 1.5 | 手势开始进入准备后被取消或失败，之后无关的手动开始录上了，也被算成“手势开始成功”（P1） | **已修** | `CalibrationFindingsTest`：手势开始之后准备没成功，之后用屏幕按钮重试才录上…、锁屏中手势开始之后准备被取消…；变异 R4、R5 |
| 2.1 | 回调通过激活代数校验后放开锁才递送，关闭可以抢在它前面完成（P1） | **已修** | `HeadsetControlAdapterTest`：已经过了校验、正在递送的事件，要递送完关闭才返回；变异 R8 |
| 2.2 | 媒体会话释放时 `isActive = false` 与 `release()` 都抛异常，引用已丢，之后没法重试（P1） | **不改**，记在假设 17 | 框架的 `release()` 抛异常基本只出现在对象已经失效时；只能在设备上测，没有注入手段；每一步已分别包住，不会让按键继续往下送（设备测试 D1–D3） |
| 2.3 | 写临时文件到一半失败，不进清理块，留下半份文件（P1） | **已修** | `FileProfileStoreTest`：写临时文件这一步失败时也要清理…；变异 R9。真实的“写到一半”没法注入，测试用被占着的临时位置模拟同一条路径 |
| 2.4 | `unregisterReceiver` 抛异常后 `registered` 已经是 false，无法重试（P1） | **不改**，记在假设 17 | `unregisterReceiver` 抛 `IllegalArgumentException` 的含义恰恰是“这个接收器没有注册”，没有可泄漏的东西 |
| 2.5 | 两个来源并发时，编号顺序与到达协调器的顺序可能相反（P2） | **已修** | `CompositeControlAdapterTest`：两个来源同时来事件…；变异 R7 |
| 3.1 | 从首页开始录音会顺带开启协调器，耳机媒体会话随之被占，手动录音之后耳机手势也能开始下一段（P1） | **不改，产品决定**（假设 14） | 这是已有共享代码（`HomeViewModel.kt`）的行为；改它超出“最小追加”；界面如实显示“控制模式：开” |
| 3.2 | 关闭控制模式时，“读状态”与“登记关闭请求”之间保存正好完成，会永远卡在“正在关闭”（P1） | **已修** | `ControlViewModelTest`：请求关闭的瞬间，保存正好完成…；变异 R10 |
| 3.3 | 保存完成后先清请求、关闭命令异步处理，排在前面的开始命令可能抢先：关闭取消新开的准备，或被拒绝而请求已被忘掉（P1） | **不改**，记在假设 17 | 需要“关闭”与“开始”在极窄的时间窗内竞争；结果看得见（控制模式仍显示为开，用户再点一次）；根治要协调器提供原子的“停止并关闭”（改 `:capture` 公共 API） |
| 3.4 | 等录音保存完再关闭控制模式期间，还能开始校准（P2） | **已修** | `ControlViewModelTest`：正在等录音保存完再关闭控制模式：此时开始校准不理会…；变异 R11 |
| 3.5 | “这一步做完了 / 跳过”快速连点会连跳两步（P2） | **已修** | `ControlViewModelTest`：同一步被连点两下…；变异 R12。**注意**：屏幕把“显示的步骤”传给视图模型的那一处（`CalibrationScreen.kt`）是 Compose 页面，**没有 JVM 测试**；参数省略时不做检查（保持原有调用方不变） |
| 3.6 | 模拟器识别漏掉 Cuttlefish 等，它们可能拿到 E3（P2） | **不改**，记在假设 17 | 认不出来的环境默认证据是“未确认”（E2），只有用户亲手勾选“这是真手机，戴的是真耳机”才是 E3（`ControlViewModelTest`：证据等级由环境决定） |
| 3.7 | 校准在录音中途开始时，向导不知道当前采集状态，锁屏观察可能漏掉“录音撑过锁屏”（P2） | **不改**，记在假设 17 | 只会漏判（保持“未测试”），不会误判；第四步本来就要求用户在那一步里重新开始录音 |
| 4.1 | 声明没有“先后”：先声明“没反应”、重试后锁屏中停止成功了，声明仍被用来否定“重新开始”（P2，第 4 轮对上面修复的复审） | **已修** | 向导收到耳机事件时清掉之前的声明（它说的是更早那次尝试；屏幕按钮的事件不清）。`CalibrationFindingsTest`：锁屏步骤里先声明没效果、重试之后锁屏中停止成功了…；`CalibrationObservationTest`：声明没效果之后又收到一次耳机事件：声明作废…；变异 R13 |
| 4.2 | 两个并发测试的 300 ms 负向等待，机器慢时没有修复也能通过（P2，第 4 轮） | **已修** | 改成等线程 `BLOCKED` / `TERMINATED` 再断言（`ThreadStates.kt`），不靠睡眠；R7、R8 改写后重跑，仍被抓住 |

**复审后新增规则的变异验证（R1–R12，没有 R6）：11 个全部被抓住**，每个都是各自新测试的断言失败，不是编译错误；每个变异跑完立刻还原并 `diff` 核对（脚本是一次性的、不入库）。R6（把“离开准备”的条件放宽成只看 `stateAfter`）**我没有运行**：真实的决定序列里，被忽略的决定前后状态相同，不会被选中，所以它与原规则等价，是等价变异，不值得为它硬凑测试。

| 编号 | 改坏的规则 | 红了几个用例 | 抓住它的测试 |
|---|---|---|---|
| R1 | “没效果”声明也被当成“录音没撑过锁屏”的证据 | 1 | `CalibrationFindingsTest`：锁屏步骤只声明没效果、没观察到锁屏中手势停掉过录音… |
| R2 | 锁屏重新开始：任何“没效果”声明都算否定 | 2 | 同上；`CalibrationFindingsTest`：锁屏步骤里手势停止发生在没锁屏的时候… |
| R3 | 锁屏重新开始：没锁屏时的手势停止也让声明生效 | 1 | `CalibrationFindingsTest`：锁屏步骤里手势停止发生在没锁屏的时候… |
| R4 | 手势开始：不看准备的结局，后来任何开始录上了都算 | 2 | `CalibrationFindingsTest`：手势开始之后准备没成功，之后用屏幕按钮重试才录上…；锁屏中手势开始之后准备被取消… |
| R5 | 手势开始：取最后一个离开准备的结局，不是紧接着的那个 | 2 | 同 R4 |
| R7 | 组合适配器：编号、旁听、递送不在同一把锁里 | 1 | `CompositeControlAdapterTest`：两个来源同时来事件… |
| R8 | 耳机适配器：校验后放开锁才递送 | 1 | `HeadsetControlAdapterTest`：已经过了校验、正在递送的事件，要递送完关闭才返回… |
| R9 | 档案存储：写临时文件失败不清理 | 1 | `FileProfileStoreTest`：写临时文件这一步失败时也要清理… |
| R10 | 请求关闭后不按最新状态补一次 | 1 | `ControlViewModelTest`：请求关闭的瞬间，保存正好完成… |
| R11 | 等录音保存完再关闭期间还能开始校准 | 1 | `ControlViewModelTest`：正在等录音保存完再关闭控制模式：此时开始校准不理会… |
| R12 | 连点不防：不比对屏幕上显示的步骤 | 1 | `ControlViewModelTest`：同一步被连点两下… |

**TDD 的诚实说明**：这一批修复是先写测试、看它红（并核对失败的值正是缺陷的样子，如 `SUPPORTED_BACKGROUND_RESTART E3`、`[1, 0]`、`expected:[disable] but was:[]`）、再改实现、再看绿。唯一例外是 3.5：先放了一个参数不起作用的签名占位，让测试能编译，才得到行为上的红，而不是编译错误。两个并发测试（R7、R8）第一版是“让第二个线程等 300 ms，看它有没有越过去”，第 4 轮复审指出机器极慢、第二个线程没被调度到时，没有修复也能通过（漏抓）；我改成确定性写法：启动竞争线程后，等它**要么卡在监视器上（`Thread.State.BLOCKED`），要么已经跑完（`TERMINATED`）**，再断言它是 `BLOCKED`，放行用 `finally` 保证一定执行（`ThreadStates.kt`）。被测代码正确时第二个线程必然被锁挡住；有缺陷时它直接跑完，断言立刻红，不靠睡眠。改写后 R7、R8 重新跑过，仍被抓住（见下表）。

### 本次会话早些时候做的（没有在最终验证里重跑；之后相关代码没有改动）

- **设备（模拟器）测试的 3 个变异 D1–D3**（在模拟器上各跑约 4 分钟，合并之后没有重跑；`AndroidMediaButtonSource.kt`、`HeadsetMediaKeyOnDeviceTest.kt`、`ControlWiringOnDeviceTest.kt` 自那之前的提交起没有再改动，`git log` 与 `git status` 可核对）：D1 关掉控制模式时不释放会话 → `HeadsetMediaKeyOnDeviceTest#sessionExistsInTheSystemOnlyWhileControlModeIsOn` 与 `ControlWiringOnDeviceTest#controlModeOnClaimsTheMediaSession…` 红；D2 会话声明 PLAYING（假装在播放）→ `HeadsetMediaKeyOnDeviceTest#sessionDeclaresPausedNeverPlayingBecauseNothingPlays` 与 `ControlWiringOnDeviceTest#controlModeOnClaimsTheMediaSession…` 红；D3 创建了会话却不激活 → `HeadsetMediaKeyOnDeviceTest#sessionExistsInTheSystemOnlyWhileControlModeIsOn` 红。那一轮的基线（没有变异）本身就是红的，原因是下面说的 `FeedbackPlayerOnDeviceTest` 竞态，所以 D2 的失败清单里混着一个与变异无关的 `FeedbackPlayerOnDeviceTest` 用例，**这里不算它**。
- `ControlViewModel` 22 个变异、`FeedbackHub` 5 个、模拟器检测 8 个、`run_emulator_acceptance.sh` 10 个、`MediaSessionDump` 7 个：全部被抓住。（这一组里只有 `run_emulator_acceptance.sh` 之后动过：收尾处把“测得的事实”重复打印了两遍，我删掉了多的一份，没有碰任何逻辑；它的 15 个行为测试在最终验证里重跑了，全部通过。）
- **变异验证发现并修掉的缺陷**：`MediaSessionDump` 的一个变异最初存活，原因是测试夹具里有真实输出里不会出现的空行；夹具改正之后 7 个全部被抓住。设备测试起初有“没有它也会通过”的空洞断言（只断言“控制模式关着时没收到键”，而键本来在任何情况下都收不到），改成用系统会话列表断言后才有了牙齿（见上面 D1–D3）。
- **变异验证没法覆盖的**：真机行为、振动手感、FreeClip 的实际事件——第 4 节 `NOT_RUN`。

### 本次会话里发现并处理的问题

- **设备测试自己的竞态（已修）**：`FeedbackPlayerOnDeviceTest.eachFeedbackKindPostsItsOwnSilentNotification` 以前每轮先 `cancel` 再立刻读通知，而通知的发出与取消都由系统异步处理，读到的可能是上一轮留下的同标识通知：连跑四次设备验收，有两次失败，读到的都是上一轮那一种的文字（`expected 录音没能开始… but was 录音已停止`）。改成不取消、依次播放、等到出现期望文字再断言（更贴近真实用法：后一条通知按同一标识替换前一条）。**产品代码没有问题。**
- **`ScreenLockOnDeviceTest` 有过一次间歇失败（根因未确认）**：三次完整运行里失败过一次，之后没有再出现：四次变异批处理的完整运行全部通过，另外专门的循环探针 4 个变体（含“从熄屏开始”）共 24 轮全部通过。我的假设是“刚唤醒就发熄屏键会被系统忽略”，**没有复现，所以没有证明**。测试已加固（唤醒后等待亮屏并稳定 1.5 秒、熄屏键每 2.5 秒重发、失败时区分“系统没有熄屏”和“监测器没报告”），仍当作“可能偶发”看待。

## 10. 本次运行结果（2026-11-18）

⟦RESULTS⟧
