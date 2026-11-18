# Ticket 16 验收：Android 屏幕按钮录一段并转成文字

对应 `.scratch/auri-v1/issues/16-android-record-and-transcribe.md`（GitHub issue #17），PRD 引用：FR-REC-001/002（严格蓝牙输入、路由确认前不落盘）、FR-ASR-001（本地转录）、FR-TXT-002（Revision 不可变）、SEC-004（转录文字是数据）、ADR-0014/0016/0020。这是手机端的第一条完整路径，也是 iOS、鸿蒙两端的样板，所以模块边界比界面重要。

**状态（2026-11-18）**：代码、JVM 单元测试、Python 验收工具、模拟器仪器测试全部通过。**没有任何真机（E3）结果**：本机没有 Android 手机，也没有 FreeClip，所以清单最后一项“真机上用 FreeClip 录一段并得到文字”是 **`NOT_RUN`**；产品负责人的真机步骤在第 4 节。模拟器没有蓝牙耳机，它证明的是**拒绝路径、存储、前台服务**，不是“用耳机录到音”。

证据等级沿用 PRD 第 5.2 节：JVM 单测与模拟器 = **E2**；真机 = E3。没有真机/账号/签名的项标 `NOT_RUN` 或 `BLOCKED_EXTERNAL`，不用模拟或伪造替代。

## 1. 这里有什么

| 路径 | 作用 |
|---|---|
| `apps/android/capture/`（`:capture`） | **纯 Kotlin/JVM 模块，不含任何 Android 类**：合同适配器类型（照 `contracts/schemas/adapter.schema.json`）、采集状态机、**采集协调器**、Record 库接口与 Record 管线（停止后自动转录）。测试用的假适配器、内存 Record 库和 Record 库契约测试在 `testFixtures`，供 `:platform` 的设备端测试复用 |
| `apps/android/platform/`（`:platform`） | 四个适配器与库的 Android 实现：屏幕控制、蓝牙采集（`AudioRecord` + 路由观测）、WAV 录音存储、本地转录（包 `:asr`）、SQLite Record 库、麦克风类前台服务、耳机在线状态 |
| `apps/android/app/`（`:app`） | 极简 Compose 首页 + `HomeViewModel`（`HomeUiState` 与操作的说明在 `HomeViewModel.kt` 文件头的注释里）+ `AuriRuntime`（把各层接起来，没有别的逻辑） |
| `apps/android/asr/`（`:asr`） | Ticket 14 的转录库，**公共 API 未改** |
| `capture_contract_check.py` | 用真实 `contracts/schemas/*.json`、语义规则 SR-01…06 与文件间一致性校验 Kotlin 写出的样本 JSON（做法沿用 Ticket 14 的 `asr-android/contract_check.py`，不回显来自输出文件的任何值） |
| `check_capture_instrumentation.py` | 读 `am instrument -r` 的原始输出，逐个核对每个 `@Test`（含 `SqliteRecordLibraryContractTest` 继承来的 11 个契约测试）都跑了且通过；复用 Ticket 14 的日志解析 |
| `run_emulator_acceptance.sh` | 一次性无头模拟器验收（E2）：建 AVD → 起模拟器 → 安装 → 仪器测试 → 逐个核对 → **无论成败都**杀模拟器、删 AVD、释放锁（共享机器的锁协议见脚本头） |
| `test_*.py` | 上面这些工具的测试（含变异验证，见第 8 节）；`test_capture_contract_output.py` 会真实调用 Gradle |
| `capture_support.py` | 测试共用：仓库路径、带环境检查的 Gradle 调用、仪器日志构造 |

## 2. 清单逐项对照

JVM 测试数是 2026-11-18 在 Mac 上 `./gradlew :capture:test :platform:testDebugUnitTest :app:testDebugUnitTest :asr:testDebugUnitTest` 的结果：`:capture` 70、`:platform` 53、`:app` 51（其中 Ticket 14 的 28 个）、`:asr` 112，全部通过，0 失败 0 跳过。

| # | 清单项（Ticket 16） | 覆盖它的测试 / 文件 | 证据等级 |
|---|---|---|---|
| 1 | 采集协调器实现采集状态机，只通过控制、采集、转录三个适配器接口接触系统 | `:capture` 是纯 JVM 模块，编译期就不可能引用 Android 类（`CaptureGateTest` 里还有一个源码扫描测试钉住它不含麦克风采集 API）；协调器的构造参数只有适配器接口、时钟、ID 生成器；`CaptureMachineTableTest`（3：状态/事件集合与合同一致、42 条转移规则逐条一致、没列出的组合被拒绝）、`CaptureHappyPathTest`（1）、`CaptureResultTest`（10）、`RecordPipelineTest`（14） | E2 |
| 2 | contracts 里采集状态机的事件序列样本全部通过（假适配器） | `CaptureScenarioReplayTest`：把 `contracts/fixtures/state-machines/capture.scenarios.json` 的 **20 个场景**逐步回放到真正的协调器上（每步恰好一个决定，触发事件、结果、状态都与合同一致），并要求 `capture.machine.json` 的 **42 条规则**每条都被某个场景步骤真正走到过；少回放一个场景、漏一条规则就失败。合同文件直接读仓库的 `contracts/`，不是拷贝 | E2 |
| 3 | 路由确认前的音频只在内存里，确认失败时丢弃，不落盘、不转录 | `CaptureGateTest`（13）：假存储记录每一次写入尝试；输入是手机麦克风 / 一直说不清 / 用户取消 / 隔离区超上限 / 确认后存储打不开，五种情形下确认前的采样都没有进过存储、没有东西交去转录；确认后确认前的采样也不会被补写。`CaptureHappyPathTest`：确认前只进内存，确认后才写入。`WavRecordingStoreTest`（7）：录音进行中磁盘上只有 `.part` 文件、闸门已关或属于别的会话的写入被拒且文件一个字节不增 | E2 |
| 4 | 耳机麦克风不可用时不录音并给出原因；任何情况下不改用手机麦克风 | 行为：`CaptureGateTest` 的“耳机麦克风不可用：什么都不录，给出原因，不重试，也没有第二种输入请求”“没有权限”“资格说不清按不能开始处理”；`BluetoothCaptureAdapterTest`（18）。结构：`BluetoothInput` 构造函数私有、`openRecorder` 只接受它，“请求手机麦克风”在类型上就写不出来；源码扫描：`NoPhoneMicPathTest`（5：全模块只有一处创建录音、录音源只用 `VOICE_COMMUNICATION`、偏好设备必须设上否则放弃、内置麦克风类型只出现在路由归类里、能开录音的接口只接受蓝牙输入）与 `CaptureGateTest` 里的“只有请求蓝牙输入这一种办法”。设备上：`CaptureRefusalOnDeviceTest`（模拟器没有蓝牙输入 → 拒绝，原因 `INPUT_DEVICE_MISSING`，**系统路由请求 0 次、`AudioRecord` 0 个、系统里没有任何录音配置、录音目录 0 个文件、转录请求 0 次**，库里 0 条） | E2（模拟器；真机的“耳机在但被系统切走”见第 4 节第 9 步 `NOT_RUN`） |
| 5 | 取消后迟到的回调、旧代次的写入、路由纪元失配、封存中的过期 TOGGLE 都不能改写当前会话（各有测试） | `CaptureStalenessTest`（10）：取消后迟到的路由确认/帧/中断/不可用；取消时还没返回的准备晚到后被关掉；旧 generation 的帧、路由、中断；封存中收到旧回调；录音中 epoch 不符的帧；准备阶段更旧 epoch 的确认；路由观测里会话标识不是当前会话；封存期间（及封存后才送到）的 TOGGLE；进程恢复后晚到的封存结果。`BluetoothCaptureAdapterTest`（18）钉住适配器侧：路由变化事件先于该批帧、系统把路由换成手机麦克风时只报告 mismatch 与新 epoch 而不去纠正；关闸与封存是协调器的事（`CaptureGateTest` 的“录音中输入变成别的来源：立即关闸”） | E2 |
| 6 | 停止后自动转录，一条 Record 和第一个 Revision，写明所用模型；Record 与音频同一个标识；路由已确认但没有语音 = 没有 Revision 的 Record | `RecordPipelineTest`（14）：停止之后没有任何人调“转录”，库里恰好一条 Record、一条 Revision（number=1，写明引擎/模型/版本，执行位置 on_device），Record 与音频文件共用标识；无语音 → 没有 Revision 的 Record，不编造文字；模型缺失 → 等模型，装好后重新转录；失败保留音频。`InMemoryRecordLibraryTest`（11）与设备上的 `SqliteRecordLibraryContractTest`（同一套 11 个契约测试）；`SqliteRecordLibraryTest`（8，设备）：重开仍在、`UPDATE`/`DELETE` 被触发器拒绝（Revision 不可变）、发布步骤与 Revision 同一个事务（失败整体回滚，Ticket 21 的“待同步任务”就接在这一步）。`ContractOutputTest`（3）+ `test_capture_contract_output.py`（4）把整条线的产物写成 JSON 交给真实 schema 校验 | E2 |
| 7 | 首页分开显示耳机连接、实际输入来源、当前录音状态 | `HomeViewModelTest`（11）、`HomeFlowTest`（4）：三个事实各自独立（耳机已连接 + 实际输入是手机麦克风；耳机未连接 + BLOCKED 等组合都存在），实际输入只来自协调器快照里系统的观测，不由“已连接”推出；按钮可用性由状态决定；`CaptureLogTest`（4）：真机复测用的状态日志不带耳机名/会话标识 | E2 |
| 8 | 真机上用 FreeClip 录一段并得到文字 | 第 4 节的步骤 | **`NOT_RUN`**（无真机、无 FreeClip）。模拟器的拒绝路径测试（上面第 4 项）是 E2，不能代替它 |

另有两项跨项的要求：

- **样本录音结果符合合同**：`test_capture_contract_output.py` 真实运行 `:capture` 的 `ContractOutputTest`（真实协调器 + 管线，只有系统边缘是假的），写出三个场景（完整+有文字、路由确认但无语音、路由丢失被截断的 partial），再由 `capture_contract_check.py` 校验：6 种文件各自过真实 schema（`recording_result`、`route_change_event`、`asr_capabilities`、`asr_task_handle`、`revision.capture`、`revision.content`）；语义规则 SR-01…06；文件间一致（路由证据必须 verified 且属于这个会话、音频块的 `route_epoch` 就是证据的纪元、capture 段与录音结果是同一次录音、Revision 写明的引擎与模型就是能力声明里的那个）。`test_capture_contract_check.py`（27）用手造的坏数据证明校验器会拦。
- **`:asr` 公共 API 未改**：本 Ticket 没有修改 `apps/android/asr/` 下任何文件（`git diff integration/auri-v2 -- apps/android/asr` 为空），`:asr` 的 112 个测试仍全部通过。

### 设备（模拟器）上实际跑的东西

`run_emulator_acceptance.sh` 在一次性无头模拟器（Pixel 6 配置，Android 15 / API 35 / google_apis / arm64-v8a，RAM 2048 MB，data 4G）上跑：

| 套件 | 测试数 | 内容 |
|---|---|---|
| `:platform` 仪器测试 | 19 | `SqliteRecordLibraryContractTest`（11，继承自 `:capture` testFixtures 的契约测试）+ `SqliteRecordLibraryTest`（8） |
| `:app` 仪器测试（`app.auri.android.capture` 包） | 2 | `CaptureRefusalOnDeviceTest`（没有耳机 → 拒绝、零写入、零转录、不开手机麦克风）、`ForegroundServiceOnDeviceTest`（麦克风类前台服务能起来、类型是 microphone、能停） |

`check_capture_instrumentation.py` 逐个核对这 21 个测试都真的跑了并通过（只看 `OK (N tests)` 不够：被忽略的不计数，假设失败也算“通过”）。Ticket 14 的设备测试要 239 MB 模型，不在这里跑。

## 3. 在本机怎么跑

前置：`source ~/.config/auri/android-env.sh`（JDK 17、Android SDK 35、build-tools、NDK、模拟器；工具链记录见 `execution/baseline/android-toolchain.md`）；仓库根目录的 `.venv`（Python 3.12，`uv venv --python 3.12 --seed .venv`，依赖 `tests/contract/requirements.txt`）。系统自带的 python3.9 不能用来跑仓库测试。

```bash
source ~/.config/auri/android-env.sh
cd apps/android

# JVM 单元测试（不需要设备）：:capture 70、:platform 53、:app 51、:asr 112
./gradlew :capture:test :platform:testDebugUnitTest :app:testDebugUnitTest :asr:testDebugUnitTest

# 构建 debug APK 与两个仪器测试 APK
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :platform:assembleDebugAndroidTest

# Python 验收工具（含真实调用 Gradle 的合同输出校验；约 1 分钟）
cd ../..
.venv/bin/pytest execution/acceptance/android-capture

# 模拟器验收（E2；会取共享锁、建并删除一次性 AVD；约 3–4 分钟）
execution/acceptance/android-capture/run_emulator_acceptance.sh
```

单独校验某个输出目录：`.venv/bin/python execution/acceptance/android-capture/capture_contract_check.py <目录> --preset jvm`。

**共享机器的规矩**（脚本已经遵守，手工跑时也要）：同一时刻只许一台模拟器，靠 `~/.cache/auri/emulator.lock`（`mkdir` 原子）互斥，owner 写分支名；超过 3 小时且没有 qemu/emulator 进程的锁视为残留；无头启动 `-no-window -no-audio`；用完 `adb emu kill`、删 AVD 与磁盘镜像、释放锁；`export ADB_LIBUSB=1`（macOS 上 adb 可能卡在 IOKit）。

**已知环境问题**：如果 `git` 或 `python3` 报 “You have not agreed to the Xcode license agreements”，是 Xcode 许可需要有 sudo 的人在终端执行 `sudo xcodebuild -license`；在那之前，没有 Xcode 项目的命令可以临时用 `DEVELOPER_DIR=/Library/Developer/CommandLineTools`（命令行工具自带的 git）。`install_on_device.sh`（Ticket 14）内部调用 `python3`，真机上也会受影响。

## 4. 产品负责人：真机上用 FreeClip 录一段（`NOT_RUN`）

**目的**：证明“用耳机麦克风录到音，并转成文字”，以及“耳机不可用时拒绝，且不改用手机麦克风”。这是 E3 证据，只有真机能给。**做不到的步骤标 `BLOCKED_EXTERNAL` 并写原因，不要用模拟或猜测补。**

**需要**：一部 Android 12 及以上、arm64 的手机（开发者选项里打开 USB 调试）、已与 HUAWEI FreeClip（或 FreeClip 2）配对的耳机、数据线、已按第 3 节配好工具链的 Mac。

**不要做的事**：不要为了让它“能录”而让应用改用手机麦克风；不要把真实个人口述的录音或文字提交进仓库（用下面的固定测试句）；`adb shell dumpsys audio` 的原始输出含设备地址，留在本机，只把相关几行摘要写进结果文件。

1. **确认设备**。`export ADB_LIBUSB=1; adb devices`：只应看到这一部手机（多台时 `export ANDROID_SERIAL=<序列号>`，别让 adb 选错设备）。记下手机型号、Android 版本、耳机型号；耳机固件版本系统不暴露，在华为智慧生活 App 里看，看不到就写“未知”。
2. **构建并安装**。`cd apps/android && ./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`。
3. **安装模型**（Ticket 14 的脚本，约 239 MB，放进应用私有目录，不入库）：`.venv/bin/python execution/acceptance/asr-android/prepare_model.py`，然后 `execution/acceptance/asr-android/install_on_device.sh`。没装模型也能录，Record 会显示“等待转录模型（MODEL_NOT_INSTALLED）”，装好后回到应用就会自动转录。
4. **连接耳机**。在手机蓝牙设置里确认 FreeClip 已连接。打开 Auri，首页三行应分开显示：`耳机：已连接（耳机名）`、`实际输入：未观测到`、`采集状态：未开启`。**截图①**。
5. **录一段（前台，屏幕按钮）**。点“开始录音”，依次授权麦克风、“附近的设备”（蓝牙连接）、通知。授权后采集状态应经 `正在确认耳机麦克风` 变成 `录音中`，`实际输入` 变成 `蓝牙耳机（HFP）（耳机名）`（或 LE Audio），通知栏出现常驻的 “Auri 正在录音”。**截图②**。此时在 Mac 上：`adb shell dumpsys audio > /tmp/dumpsys-audio-recording.txt`（录音配置段落里应看到本应用的录音来源是通信类（`VOICE_COMMUNICATION`），输入设备是蓝牙而不是内置麦克风）；`adb logcat -d -s AuriCapture:I > /tmp/auricapture.txt`（只有状态行，没有正文）。
6. **说一句固定测试句**，对着耳机，5–10 秒（目前最长约 29.5 秒会自动停止，见第 6 节限制）：“今天是测试，Auri 录音转文字，一二三四五。”
7. **停止**。点“停止”：采集状态 `保存中` → `待命`；列表出现一条 Record，先是 `转录中…`，随后显示文字，下面一行写着模型名与版本（现在装的是 `模型：sensevoice-small-int8 2024-07-17+7d1efa2138a6`）。**截图③**（文字是固定测试句，可以入证据）。记录从点“停止”到文字出现的秒数。
8. **反例一：耳机不可用**。在手机蓝牙设置里断开 FreeClip（或把耳机放回充电盒），点“开始录音”：应当 `采集状态：没有录音（原因：INPUT_DEVICE_MISSING）`，**手机麦克风不会被打开**（状态栏没有麦克风使用指示，`adb shell dumpsys audio` 里没有本应用的录音）。**截图④**。如果耳机明明已连接却仍然拒绝：保存 `adb shell dumpsys audio` 的设备列表（输入设备一栏），这很可能是“FreeClip 在这部手机上只暴露 A2DP、不暴露 SCO/LE 输入”的真发现，原样上报，**不要改代码绕过去**。
9. **反例二：录音中耳机掉线**。重新连上耳机，开始录音，等进入 `录音中` 后断开耳机：应当立刻结束这段录音并只保留断开之前已确认的部分，不会继续用手机麦克风录；列表里多一条较短的 Record。记下断开到状态变化的大致秒数。这一步验证的是第 2 节第 5 项的真机行为，模拟器与 JVM 测试都只用假的音频系统。
10. **取回材料**：四张截图、`auricapture.txt`、一份 `dumpsys audio` 的摘要（只写与本应用录音相关的几行，去掉设备地址）、手机/系统/耳机信息；写进 `execution/acceptance/android-capture/results/<日期>-<手机型号>-freeclip.md`，原始日志放 `results/raw/`（已被 `.gitignore` 的 `execution/**/raw/` 忽略）。结果表建议这样记，状态词用 PRD 的枚举（本 Ticket 只测前台 + 屏幕按钮，所以最好的结论是 `SUPPORTED_FOREGROUND`；锁屏、后台、进程被杀属于 Ticket 19，**未测就是 `NOT_TESTED`，不能写通过**）：

   | 耳机 | 手机 / 系统 | 场景 | 结果 | 状态 | 证据 | 备注 |
   |---|---|---|---|---|---|---|
   | | | 前台：屏幕按钮录一段并转文字 | | | 截图②③、logcat | |
   | | | 耳机不可用时拒绝 | | | 截图④ | |
   | | | 录音中耳机掉线 | | | logcat | |

**真机上我们不知道、需要这次观察的事**（代码里的数字是假设，不是实测）：路由确认的 5 秒超时对 FreeClip 够不够（SCO 建立耗时）；`setCommunicationDevice` + `MODE_IN_COMMUNICATION` 对手机上别的音频（正在播放的音乐）的副作用；FreeClip 只连 A2DP 时是否暴露输入设备；LE Audio 耳机上 `routedDevice` 的类型；录音一进后台系统是否仍然给音频（Ticket 19 才处理，这里只记录观察到的）。

## 5. 设计要点（iOS、鸿蒙照着做）

- **四个接缝**：`ControlAdapter`（控制命令）、`CaptureAdapter`（资格检查、请求蓝牙输入、路由证据、帧）、`RecordingStore`（封存的音频）、`TranscriptionAdapter`（转录）；外加 `RecordLibrary`。协调器是纯逻辑，只认这些接口，另外两端用 Swift / ArkTS 重写协调器时，应当回放**同一份** `capture.scenarios.json` 与 `capture.machine.json`，并复用同一套 `RecordLibrary` 契约测试的思路（`RecordLibraryContract` 的 11 条）。
- **协调器**：所有命令与回调进同一个邮箱、由一个循环串行处理；每个 Capture Session 带 `capture_session_id` + generation + route epoch；旧 generation 的回调、epoch 不符的帧、会话标识不对的路由观测，都不能改写当前会话；进程恢复后回到 DISARMED，不自动开麦。
- **Route Evidence 只来自系统的观测**：“已配对 / 已连接 / 偏好设备”都不是证据。路由确认前的帧只进内存隔离区（有上限，只丢弃不落盘），确认后才写存储；路由一变就立刻关闸；确认前的采样永远不会被补写。
- **Android 实现**：`AudioRecord` + `getRoutedDevice()` + `OnRoutingChangedListener`，另外每次读完帧后主动查一次 `routedDevice`，并保证“路由事件先于该批帧交付”；`setCommunicationDevice` + `MODE_IN_COMMUNICATION`（结束时恢复原来的音频模式）；`AudioSource.VOICE_COMMUNICATION`；偏好设备必须设上，设不上就释放并报错，绝不留下一个“默认输入”的录音；设备地址只在内部用来把输入设备对上同一个耳机的通信设备，不外传、不记录。
- **前台服务**是第二层：运行时麦克风权限与 `FOREGROUND_SERVICE_MICROPHONE`（API 34+）是两层，`BLUETOOTH_CONNECT`（API 31+）另算；服务在 `ROUTING`…`FINALIZING` 期间存在，`startForeground` 放在 `onCreate`，`START_NOT_STICKY`，通知里没有任何转录内容；服务起不来就取消这次采集。
- **Record 库**：一个 Record 对应一次已确认路由的录音，与音频文件共用 `record_id`；文字是只增不改的 Revision 链（SQLite 触发器在数据库层面拒绝 `UPDATE`/`DELETE`）；发布 Revision 是**一个本地事务**，事务里留了一个发布步骤（`publicationStep`），Ticket 21 的“待同步任务”入队就放在那里，保证“已发布”与“待同步”不会分家。字段名与枚举值照 `revision.schema.json` 的 `capture` / `content` 两段，`ContractOutputTest` 证明本地库里存的字段足够组装它们。
- **转录文字是数据**：界面只显示，不执行，日志里没有正文。日志只有一行状态（`AuriCapture` 标签：状态、受阻原因、系统观测到的输入类型），不带耳机名、设备地址、标识、文字或音频。

## 6. 假设（做决定时我没有问人，下面每一条都可以推翻）

1. **只有屏幕按钮**是控制来源；耳机手势、音量手势是 Ticket 17。协调器已按“控制适配器给出已归一化的命令”设计，加来源不用改它。
2. **界面刻意简陋**：另一个人会在 `HomeUiState` / `HomeViewModel` 上重做界面，这里只保证三个事实分开、按钮可用性来自状态。
3. **“把缓存目录加进 `.gitignore`”**：真正的缓存（模型、引擎 AAR、一次性 AVD、模拟器锁）在仓库外的 `~/.cache/auri/`，本来就不会被提交；我加了一条 `.cache/`（带注释），覆盖“用 `AURI_CACHE_DIR` 指进仓库里”的情形。
4. **录音时长上限 = 引擎一次能处理的时长（SenseVoice 清单里是 30 000 ms）减 500 ms**，到点自动停止（`end_reason=duration_limit`，状态 `complete`），否则录完转不了。分段与长录音是 Ticket 18/20；换了默认模型（Ticket 15）上限会跟着清单走。
5. **“耳机已连接”**只表示系统里有蓝牙音频设备（A2DP / SCO / BLE），**不是**输入来源的证据；输入来源只来自协调器快照里系统对这次录音的观测。
6. **权限**：开始前缺运行时麦克风权限（含“从未授予”），用合同里已有的 `PERMISSION_REVOKED`；蓝牙连接权限被拒按“没有蓝牙输入设备”（`INPUT_DEVICE_MISSING`）处理。见第 7 节的合同缺口。
7. **存储写不下**（打不开、写失败、封存失败）：合同的采集状态机没有“存储失败”事件，按已有事件映射（`ROUTE_UNVERIFIED` / `SYSTEM_INTERRUPTION`）并给出 `STORAGE_EXHAUSTED`，已写的部分尽量封存。
8. **录音文件**是应用私有目录下每个会话一个 WAV（16 kHz 单声道 16 位），V1 里没有加密、没有过期清理；删除由后面的 Ticket（23）与 ADR-0020 处理。
9. **转录在进程内、停止后一次一个**；转录没做完应用被杀，下次打开应用时 `pipeline.resume()` 会把 PENDING / 等模型且有音频的 Record 重新排队（没有后台任务，需要打开应用）。
10. **SQLite 用框架自带的 `SQLiteOpenHelper`**，不引入 Room/KSP（少一个依赖）；schema 版本 1，没有迁移。时间以“秒 + 纳秒”存。
11. **音频系统副作用**：录音期间手机的音频模式被切到通信模式、通信设备设成耳机，结束时恢复；这在真机上的副作用没有验证（第 4 节末尾）。
12. **模拟器 = E2**：`AVD` 没有蓝牙输入，所以设备端测试断言“系统里确实没有蓝牙输入”（有的话测试响亮地失败，而不是悄悄换个断言）。

## 7. 合同缺口与建议（没有改 `contracts/`，只记录）

| # | 现象 | 涉及字段 / 文件 | 建议 |
|---|---|---|---|
| G1 | 采集状态机没有“存储失败”事件，实现里借用 `ROUTE_UNVERIFIED` / `SYSTEM_INTERRUPTION` 表达 | `contracts/fixtures/state-machines/capture.machine.json` 的 `events` | 增加 `STORAGE_FAILED`（ROUTING → BLOCKED、RECORDING → FINALIZING），并补场景 |
| G2 | `recording_result.capture_duration_ms` 与 `revision.capture.capture_duration_ms` 写“按单调时钟计”，Android 实现按**采样数 ÷ 采样率**算（与墙钟的差不超过一个缓冲区，约 100 ms） | `adapter.schema.json#/$defs/recording_result`、`revision.schema.json#/$defs/capture` 的 description | 改成“按采样数与采样率计，误差不超过一个缓冲区”，或明确要求用单调时钟 |
| G3 | 没有蓝牙连接权限被拒的错误码，只能把“从未授予”并进 `PERMISSION_REVOKED`、蓝牙权限被拒并进 `INPUT_DEVICE_MISSING` | `common.schema.json#/$defs/error_code` | 增加 `PERMISSION_BLUETOOTH_DENIED`，并区分“撤销”和“从未授予” |
| G4 | `route_change_event` 没有 `route_evidence_id`，但 `revision.capture.route_evidence_id` 必填：二者无法互相校验，实现自己生成这个 UUID 并存进 Record | `adapter.schema.json#/$defs/route_change_event`、`revision.schema.json#/$defs/capture.route_evidence_id` | 给 `route_change_event` 加 `route_evidence_id`，或规定它由 `(capture_session_id, epoch)` 派生 |
| G5 | ASR 没有“音频超过模型单次上限”的错误码，实现里用 `ASR_RESOURCE_LIMIT` 表达；引擎内部失败也没有码（Ticket 14 已记） | `error.schema.json` 的 `code` 枚举 | 增加 `ASR_AUDIO_TOO_LONG` 与 `ASR_ENGINE_FAILED`，分段（Ticket 18/20）落地后再决定是否需要前者 |

## 8. 测试的牙齿（变异验证）与 TDD 的诚实说明

没有为每个切片严格先红后绿：一部分切片是把测试和实现一起写好再跑，测试第一次就通过。补救办法是**变异验证**：改坏被测代码，确认对应测试变红，再恢复。本轮（验收工具与设备测试）做过并记录的有：

- `capture_contract_check.py`：去掉路由纪元检查、`verified` 检查、模型一致检查、SR 语义检查、会话一致检查、语音片段一致检查、“无语音不得有文字”检查、让报错回显值——8 个变异各被预期的测试抓住。
- Kotlin → Python 整条线：把 `text_sha256` 写成全 0、把 capture 段 `status` 写死成 `complete`、把 Revision 的 `model_id` 写成别的——3 个变异都被 `test_capture_contract_output.py` 抓住（不是编译错误）。
- `run_emulator_acceptance.sh`：不释放锁、不删 AVD、不杀模拟器、不固定设备序列号、不加 `-no-window`、无条件停 adb 服务、不等锁、不清残留锁、残留锁不看模拟器进程、去掉“是否模拟器”检查、跳过日志核对——11 个变异各被至少一个行为测试抓住；另外证明 INT/TERM/HUP 三种信号中断时清理照样做完。
- 设备测试：在真机之外，在模拟器上做过两次带变异的验收：①把内置麦克风当成蓝牙输入 → `CaptureRefusalOnDeviceTest` 以断言失败（期望 `INPUT_DEVICE_MISSING`，实际 `INPUT_ROUTE_UNVERIFIED`）；②让前台服务不调用 `startForeground` → 进程被系统以 `ForegroundServiceDidNotStartInTimeException` 终结，`check_capture_instrumentation.py` 报出两个测试都没有通过记录。变异已还原。
- 状态日志：泄露耳机名、隐藏原因、把任何已观测输入都说成蓝牙——3 个变异各被预期的测试抓住。

`:capture`、`:platform`、`:app` 的 JVM 测试在编写期间同样做过变异验证（改坏实现看测试变红再还原），但没有逐条存档，这里不虚报条数。

## 9. 已知限制与后续

- **没有耳机手势、音量手势**（Ticket 17）；**没有静音检测与时长之外的限制**（Ticket 18；VAD 只在会话内过滤静音，不决定开始与结束，ADR-0016）；**没有来电/中断处理、没有进程被杀后的录音恢复**（Ticket 19：被杀时已写下的 WAV 不会被回收，也不会产出 `process_terminated` 的 Record）；**没有模型管理界面**（Ticket 20）；**没有配对与同步**（Ticket 21）；**没有改文字与删除**（Ticket 23）。
- 前台服务里 `startForeground` 若被系统拒绝（`IllegalStateException` / `SecurityException`），服务只是 `stopSelf()`，**不会把失败异步报回**协调器；在“服务起不来就取消采集”这一层，只有 `startForegroundService` 本身同步抛错时才会取消。下一次的资格检查（权限、前台条件）会拦住大部分原因。
- 路由观测有一个不超过一个缓冲区（约 100 ms）的窗口：路由刚变而系统还没告诉我们时，同一批帧里可能混着极少量变路由之后的音频。缓解：每批读完后主动查一次 `routedDevice`，且路由事件先于该批帧交付；真机上这个窗口的实际大小需要 E3 才知道。
- 真机行为（SCO 建立耗时、音频模式副作用、不同耳机的差异、LE Audio）全部 `NOT_RUN`，见第 4 节。
