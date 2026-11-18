# Ticket 20 验收：Android 模型管理与转录调度

对应 `.scratch/auri-v1/issues/20-android-model-management.md`（GitHub issue #21）。PRD 引用：FR-ASR-001/002（本地转录；本地失败不自动上云）、FR-TXT-002（Revision 不可变）、SEC-002（联网只为下载模型，音频与文字没有联网通道）、SEC-004（转录文字是数据）、ADR-0017（默认本地模型待实测后定，这里只放占位清单）、ADR-0019（V1 只做本地转录，云端状态保留但不产生）。

**状态（2026-11-18）**：第 3、4、7、10 节的数字是审查修复前的历史结果；本次修复与新的验证以本节及随附报告为准。**没有任何真机（E3）结果**：温度、内存、省电模式与电池、存储见底、真机下载和“边转录边录音”仍全部是 **`NOT_RUN`**；步骤在第 5 节，资源阈值尚未真机标定。

证据等级沿用 PRD 第 5.2 节：JVM 单测与模拟器 = **E2**；真机 = E3。那一次真实下载是在这台 Mac 的 JVM 上跑的（`HttpURLConnection`，不是 Android 的实现），所以它证明的是下载逻辑对真实服务器成立，仍是 E2。

### 审查发现修复（E2，真机仍为 NOT_RUN）

- F1：运行协程即使在启动前被取消，也由完成处理器恰好一次释放名额。
- F2：指针读入或解析失败保留全部模型代并报可恢复存储错误；安装后清理使用刚写好的目录名。
- F3：开始推理前持久化 RUNNING 与次数；任务 ID 在 Record 库内作为发布幂等键，重启只认领对应 Revision。
- F4：引擎校验与推理持有模型目录租约；换代后旧目录在最后一个租约释放时才清理。
- F5：拒绝归属于引擎实际使用的模型代；旧代迟到的拒绝不会阻挡新代。
- F6：旧引擎按使用计数退役，最后一次调用返回后在适配器线程关闭，不阻塞调度邮箱。
- F7：同模型传输及其收尾按提交顺序串行，暂停和取消的原因属于各自的传输。
- F8：资源变化流故障不逃逸；状态未知时等待并退避重订阅，已成功注册的系统监听均在清理路径中注销。
- F9：转录栈拥有自己的可取消生命周期；关闭时停止资源监听而不取消调用方作用域。
- F10：HTTPS 每一跳拒绝回环、私有、链路本地、IPv6 唯一本地地址及 localhost、`.local`、`.internal` 名称；实际连接使用经过校验的 DNS 地址，最多 5 次重定向，无主机白名单，固定哈希仍是完整性门禁。
- F11：资源等待显示原因；无信号也按 15、30、60、120、300 秒退避重查，此后每 5 分钟重查，变化信号与手动检查立即触发；等待不是失败，失败的自动重试仍有上限。
- F12：重转录仅在 Record 已有 Revision 时保留 DONE；此前没有 Revision 时显示本次进行中、无语音或失败。
- F13：新增真线程 CPU 负载测试，以 20 ms 节奏生产三秒帧并检查丢帧和到写入的延迟；这只是 Mac JVM 上的 E2，真机边转录边录音仍为 NOT_RUN。

F2 的解析失败测试用真实文件系统上的损坏指针模拟不可读结果，不能模拟每一种 Android I/O 故障；F8 的失败路径用可控注册回调测试清理，真实系统注册/注销仍须设备证据。假设：旧版 RUNNING 任务若存在无法归属的旧 ASR Revision，宁可标失败保留数据，也不盲目重复发布；已保存的旧 `needsUser` 等资源任务恢复自动重查。

## 1. 这里有什么

| 路径 | 作用 |
|---|---|
| `apps/android/modelmgmt/`（`:modelmgmt`） | 模型目录（清单 → 列表条目：体积、所需空间、语言、许可条目、固定哈希）、可暂停可续传的下载（HTTP Range + If-Range）、先校验后原子启用、更新失败保留旧模型、`UrlConnectionTransport`（只认 https，重定向逐跳检查）。**全应用唯一声明 `INTERNET` 的模块**；只依赖 `:asr`（复用 `ModelManifest` 与 `ModelVerifier`），看不到 Record、音频、文字 |
| `apps/android/manifests/` | 清单。现在只有占位的 SenseVoice-Small int8（`sensevoice-small-int8-2024-07-17.manifest.json`），不含权重；支持多份清单，默认模型是第一份 |
| `apps/android/capture/…/scheduler/`、`…/machine/TranscriptionMachine.kt` | 转录调度器（纯 Kotlin/JVM）：转移表、调度器、资源策略、录音优先监视器、低优先级专用线程、`TranscriptionRuntime`。`RecordPipeline` 不再自己排队，交给调度器 |
| `apps/android/platform/` | 粘合与 Android 实现：`ActiveModelTranscriptionAdapter`（按当前启用的模型转录）、`ModelManagerAvailability`、`LocalTranscriptionStack`（线上整条接法）、`SqliteTranscriptionJobStore`（任务库，独立文件 `auri-jobs.db`）、`AndroidResourceMonitor`（温度/省电/内存/存储） |
| `apps/android/app/` | `ModelsViewModel`（**唯一**的可观察对象是 `state: StateFlow<ModelsUiState>`，文件头有说明）、`ModelsDescriptions`（界面文字的纯函数）、极简 `ModelsScreen`、`MainActivity` 的两页（首页 / 模型）、`AuriRuntime` 接线 |
| `:modelmgmt:realDownloadCheck`（`RealDownloadCheck.kt`） | 联网对清单里的真实地址下载一次的检查（Gradle 任务，**不是单元测试**，单元测试里不许联网，也就没有“被跳过的测试”） |

## 2. 清单逐项对照

JVM 测试数：Ticket 16 审查后的基线是 `:capture` 72、`:platform` 84、`:app` 52、`:asr` 112；本 Ticket 之后见第 4 节。

| # | 清单项 | 覆盖它的测试 / 文件 | 证据等级 |
|---|---|---|---|
| 1 | 模型列表显示大小、所需空间、语言和许可证入口 | `ModelCatalogTest`（5）：条目的体积、所需空间、语言、许可条目、固定哈希逐项等于清单；所需空间 = 下载 + 解包 + 余量（总量 10% 与 64 MiB 取大）；已下载的字节不再占空间；多份清单；坏目录整个被拒。`ModelsViewModelTest`：一行的大小、所需空间、语言、许可证与哈希**逐项等于 `apps/android/manifests/` 里真实清单写的**。`ModelsScreen` 显示体积、所需空间、语言、许可名与再分发状态、许可链接和“查看许可”按钮（用浏览器打开） | E2；真机上的显示 `NOT_RUN` |
| 2 | 下载可暂停和恢复；暂停状态在进程重启后仍在（在磁盘上） | `ResumableDownloaderTest`（21）：暂停立刻生效，续传的 `Range` 起点就是磁盘上残片的长度并带 `If-Range` 校验器；服务器换了文件 / 不理 `If-Range` / 不支持 Range（回 200）/ 回 416 / 没给校验器 / 状态文件坏了 / 残片属于别的清单 / 残片比清单还长：每一种都从头来，不把新旧拼在一起。`ModelManagerTest`：暂停后重启（新管理器、同一块磁盘）仍显示已下载的字节、**不偷偷联网**、再开始从残片接着下；开始前先查空间；没有网络、服务器报错、空间写满都有明确状态。`UrlConnectionTransportTest`（10）：真实 `HttpURLConnection` 对本机假服务器，`Range`/`If-Range` 原样发出，重定向逐跳按策略检查，停住的连接以读超时失败。真实服务器见第 3 节 | E2 |
| 3 | 校验通过后才原子启用；校验失败不加载；更新失败保留旧模型 | `ModelManagerTest`：安装步骤顺序恒为 压缩包校验 → 解包 → 逐文件校验 → 目录改名 → 指针写入；下载没完成的整个过程中 `active` 一直是“没装”；哈希不符则丢弃、不解包、不启用，之后能干净重试；压缩包对但里面的文件不符也不启用；**失败的更新不动旧模型**；更新过程中任何一步被杀掉，重启后“指针之前旧模型可用、指针之后新模型可用、没有半成品”；已装文件缺了/变短重启后判为损坏、修好后换新的一代；加载时校验器拒绝后上报并判损坏，旧一代的上报不会冤枉刚换上的新模型。`ArchiveInstallerTest`（10）：路径逃逸、符号链接顶替清单里的文件名、缺文件、条目大小与清单不符（写盘之前就拒绝）、重复条目、不是 bzip2、被截断的包都被拒；说明文件与示例音频一个字节都不写。`ActiveModelTranscriptionAdapterTest`（13）：没装 / 损坏 / 加载被拒时转录等模型，不会拿坏模型去转 | E2 |
| 4 | 转录状态机的事件序列样本全部通过 | `TranscriptionScenarioReplayTest`：`transcription.scenarios.json` 的 **10 个场景**逐步回放到真调度器（每步的决定、转移后的状态都与合同一致），并要求 `transcription.machine.json` 的 **20 条规则**每条都被真实决定覆盖、也出现过被拒绝的；少回放一个场景、漏一条规则就失败。合同文件直接读仓库的 `contracts/`，不是拷贝。`TranscriptionMachineTableTest`（5）：状态/事件集合、初始状态、终态、保留状态与合同一致；20 条规则逐条一致；没列出的组合被拒；终态只会忽略取消（一个结束的任务不能复活）；没有任何规则出入云端的三个保留状态（`WAITING_NETWORK` / `WAITING_CONSENT` / `SUBMITTED_REMOTE`，ADR-0019） | E2 |
| 5 | 没有模型或资源不足时任务等待并显示原因，条件恢复后继续，不无限重试 | `SchedulerWaitingTest`、`SchedulerRobustnessTest`、`SchedulerRestartTest`：资源与未知状态等待并显示原因；没有信号时按退避重查，间隔最多 5 分钟；模型变化与用户重查立即生效；真正的失败和崩溃重试仍有上限。设备端 `AndroidResourceMonitorTest` 与任务库契约见设备报告。 | E2；真机阈值 `NOT_RUN` |
| 6 | 转录进行时开始新录音不掉帧 | `TranscriptionDoesNotDropFramesTest`：虚拟时间、独立真实线程、以及 CPU 密集型低优先级推理与 20 ms 实时帧生产并发；检查所有帧、丢弃计数与到写入的最大延迟。`LocalTranscriptionStackTest` 检查生产接法的线程优先级。 | E2；真机 `NOT_RUN` |
| 7 | 重新转录产生新的 Revision，旧的保留 | `SchedulerRetranscribeTest`（7）：重转录是新任务、新 Revision，**写明这次用的模型**，旧 Revision 一个字都没动；重转录没有语音 / 失败 / 在等模型或资源时，已有的文字与“已转录”状态都在；已有未结束的任务时再点不会多出一个；录完的第一次转录排在重转录前面。Revision 本身只增不改由 Ticket 16 的 SQLite 触发器在数据库层面保证（`SqliteRecordLibraryTest`） | E2 |
| 8 | 没有识别出语音时如实说明，不产生文字 | `SchedulerRunTest`：引擎说没有语音 → 任务是 `NO_SPEECH`、**没有 Revision**、Record 如实记为没有语音；引擎回了只有空白的文字同样不留空 Revision。`SchedulerRetranscribeTest`：重转录没有语音，不加文字。`ModelsViewModelTest`：界面写“没有识别出语音：没有生成文字” | E2 |

跨项的要求：

- **联网边界（唯一一处与 Ticket 14 不同的地方）**：下载模型需要 `INTERNET`，而 Android 的网络权限是整个应用一份，所以 Ticket 14 那条“合并清单里没有 `INTERNET`”的系统级证明**不再成立**——这是有意的，不是疏漏。改用分层证据：①静态：`NetworkIsolationTest`（`:platform`）扫五个生产模块的编译产物与源码，网络 API 只许出现在 `:modelmgmt`；`INTERNET` 只许在 `:modelmgmt` 自己的清单里声明；`:modelmgmt` 不依赖 `:capture` / `:platform` / `:app`、对 `:asr` 只用清单那个包——它**看不见**音频、文字与 Record；②合并后的应用清单：`NoNetworkManifestTest`（`:app`）要求 `INTERNET` 恰好一次、来源只有 `:modelmgmt`（读 AGP 的清单合并报告，来源解析器本身也有反例测试）；③设备上：`OfflineProofTest`（Ticket 14 的设备测试，已按新不变量改写）在飞行模式下转录、没有产生任何网络字节；④没有用户操作就没有下载字节（`ModelManagerTest` 里重启后“不偷偷联网”）。这弱于“根本没有权限”，真机上的网络流量抓包不在本 Ticket。
- **`:asr` 公共 API 未改**：`git diff origin/integration/auri-v2 -- apps/android/asr` 为空；`:asr` 的 112 个测试仍全部通过。
- **没有新增“样本结果 JSON”**，所以没有新写 Python 合同校验器：调度器发布的 Revision 走的是 Ticket 16 同一条路径（`RecordPipeline` → `publishRevision`），`ContractOutputTest` + `capture_contract_check.py` 仍然用真实 schema 校验它（它们现在经过调度器，全部通过）；状态机样本由 `TranscriptionScenarioReplayTest` 直接读 `contracts/` 回放。

## 3. 一次真实下载（在这台 Mac 上量的，E2）

`./gradlew :modelmgmt:realDownloadCheck -PauriRealDownload=$HOME/.cache/auri/models`：用线上同一套代码（`ModelManager` + `UrlConnectionTransport`）对清单里写的真实地址（GitHub 发布资产，会 302 到带签名的存储地址）走一遍。输出里没有地址：重定向后的地址是带签名、带有效期的临时地址，不进日志。缓存放在仓库外的 `~/.cache/auri/models/`，不入库；`.gitignore` 已有 `.cache/`、`*.onnx`、`*.tar.bz2`、`models/`，误放进仓库也不会被提交。

| 量 | 值（在这台 Mac 上测得，家用网络，JVM `HttpURLConnection`） |
|---|---|
| 压缩包 | 163,002,883 字节；解包后 239,549,735 字节；列表里写的所需空间 469,661,482 字节 |
| 暂停点 | 下到 9,549,376 字节（5.9%）时暂停，用时约 4 秒；立刻继续 |
| 请求 | 共 2 次。#1：无 `Range`，状态 200，收到 9,565,760 字节。#2：**`Range` + `If-Range`，状态 206**，收到 153,453,507 字节 |
| 重复收到 | 网络上共收到 163,019,267 字节，比压缩包多 16,384 字节：暂停时读线程多读的一块被丢掉，续传从**磁盘上的字节数**接着，没有从头再下 |
| 用时 | 全程 67 秒（下载、暂停续传、压缩包哈希、解包、逐文件校验、启用） |
| 独立校验 | 安装完成后**不经过本项目的校验器**重新算每个文件的 SHA-256：`model.int8.onnx`（239,233,841 字节）与 `tokens.txt`（315,894 字节）都与清单一致；暂存目录为空 |
| 交叉核对 | Ticket 14 缓存的同名压缩包 `shasum -a 256` = 清单里固定的 `7d1efa21…347e` |

这是 JVM 的 `HttpURLConnection`；Android 上是系统自带的实现，**没有在设备上下载过**（第 5 节）。

## 4. 在本机怎么跑

前置：`source ~/.config/auri/android-env.sh`（JDK 17、Android SDK 35、模拟器）；仓库根目录的 `.venv`（Python 3.12）。系统自带的 python3.9 不能用来跑仓库测试。

```bash
source ~/.config/auri/android-env.sh
cd apps/android

# JVM 单元测试（不需要设备）。:modelmgmt 是 Android 库，用 testDebugUnitTest（直接写 test 会把 debug 与 release 各跑一遍，数目翻倍）
./gradlew --no-daemon :capture:test :modelmgmt:testDebugUnitTest :platform:testDebugUnitTest :app:testDebugUnitTest :asr:testDebugUnitTest

# 构建 APK 与两个仪器测试 APK
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :platform:assembleDebugAndroidTest

# 一次真实下载（联网，163 MB；输出目录要在仓库外）。已经装好同一版本时不再下载，只做独立校验；要重来就删掉目录里的 model-store
./gradlew --no-daemon :modelmgmt:realDownloadCheck -PauriRealDownload="$HOME/.cache/auri/models"

# 模拟器验收（沿用 Ticket 16 的脚本与共享锁；:platform 的仪器测试现在还包含任务库与资源监视器的测试）
cd ../..
AURI_GRADLE_ARGS=--no-daemon execution/acceptance/android-capture/run_emulator_acceptance.sh

# Python 验收工具与合同
.venv/bin/pytest execution/acceptance/android-capture execution/acceptance/asr-android
.venv/bin/pytest tests/contract tests/integration tests/security
```

**最终运行的结果**（2026-11-18，分支 `ticket/20-model-management`，已合并 `origin/integration/auri-v2` 的 `e92cfa7`；JVM 单测跑之前先清过测试结果，所以不是 `UP-TO-DATE`；第 10 节缺陷 4 修好之后整套重新跑过，一次只跑一个 Gradle 调用，一个接一个，模拟器与 Gradle 单测不同时跑）：

| 套件 | 命令 | 结果 |
|---|---|---|
| `:capture` | `:capture:cleanTest :capture:test` | 158 个，0 失败，0 跳过 |
| `:modelmgmt` | `:modelmgmt:cleanTestDebugUnitTest :modelmgmt:testDebugUnitTest` | 71 个，0 失败，0 跳过 |
| `:platform` | `:platform:cleanTestDebugUnitTest :platform:testDebugUnitTest` | 108 个，0 失败，0 跳过 |
| `:app` | `:app:cleanTestDebugUnitTest :app:testDebugUnitTest` | 66 个，0 失败，0 跳过 |
| `:asr` | `:asr:cleanTestDebugUnitTest :asr:testDebugUnitTest` | 112 个，0 失败，0 跳过（公共 API 未改） |
| 构建 | `:app:assembleDebug :app:assembleDebugAndroidTest :platform:assembleDebugAndroidTest` | 成功（在模拟器脚本的第一步里构建） |
| 模拟器（E2） | 第 7 节，`run_emulator_acceptance.sh` | 平台 `OK (30 tests)`，应用 `OK (4 tests)`；核对器 30 + 4 个测试，0 个问题；退出码 0；AVD、锁、模拟器进程都已清掉 |
| Python 验收 | `.venv/bin/pytest execution/acceptance/android-capture execution/acceptance/asr-android` | 189 个通过 |
| 合同、集成、安全 | `.venv/bin/pytest tests/contract tests/integration tests/security` | 492 个通过（单独跑，232 秒） |
| 一次真实下载 | `:modelmgmt:realDownloadCheck`（联网） | 通过（第 3 节） |

**Gradle 的一个陷阱**：读源码、合同或构建产物的测试（`NetworkIsolationTest`、`NoNetworkManifestTest`、场景回放）如果没把这些文件声明成输入，改了它们之后 Gradle 会说 `UP-TO-DATE` 而不重跑。做最终验证前先清结果（`:capture:cleanTest`、`:modelmgmt:cleanTestDebugUnitTest` 等）。`realDownloadCheck` 只借单元测试的类路径，不要写成 `tasks.named<Test>(...).map { … }`：`TaskProvider.map` 会把整套单元测试变成它的依赖。

**共享机器的规矩**同 Ticket 16：同一时刻只许一台模拟器，靠 `~/.cache/auri/emulator.lock` 互斥；无头启动；用完 `adb emu kill`、删 AVD、释放锁；`export ADB_LIBUSB=1`。另外：**`./gradlew --stop` 是全机器范围的**，会停掉别的工作树正在跑的构建，共用机器上要给构建与 `--stop` 都加私有的 `-Dorg.gradle.daemon.registry.base=<目录>`（第 10 节缺陷 5）。

## 5. 产品负责人：真机上的验收（`NOT_RUN`）

**目的**：把代码里的假设换成这部手机上的实测：模型页的下载/暂停/继续、转录在各种资源状况下的等待与恢复、边转录边录音。这是 E3 证据，只有真机能给。**做不到的步骤标 `BLOCKED_EXTERNAL` 并写原因，不要用模拟或猜测补；不要为了让它“能转”去改阈值或绕过校验。**

**需要**：一部 Android 12 及以上、arm64 的手机（打开 USB 调试）、能上网、数据线、按第 4 节配好的 Mac；录音那几步还要已配对的 FreeClip。**不要提交**真实个人口述的录音或文字（用固定测试句）。`adb` 的原始输出（含设备地址）留在本机，只把相关几行摘要写进结果文件。

1. **装机**：`export ADB_LIBUSB=1; adb devices`（只应有这一部手机；多台时 `export ANDROID_SERIAL=<序列号>`）；`cd apps/android && ./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`。记下手机型号与系统版本。
2. **模型页**：打开应用，点顶部“模型”。应当看到：`转录现在用的模型` 那行写“还没有可用的转录模型：新录音会先保存，装好模型后自动转录”；模型条目显示 `sensevoice-small-int8 2024-07-17+7d1efa2138a6（默认）`、语言（zh、en、ja、ko、yue）、下载 163.0 MB / 解包后 239.5 MB / 从零装好需要可用空间 469.7 MB、许可名与“再分发未确认”、一串固定的 SHA-256。点“查看许可”应当用浏览器打开许可页。**截图①**。
3. **下载、暂停、杀进程、继续**：连着 Wi-Fi 点“下载”；到约 10% 点“暂停”，应显示“已暂停：x / 163.0 MB（n%）；点“继续”从这里接着下载”，**截图②**。从最近任务里划掉应用（杀进程），重新打开进“模型”页：应当仍显示同样的已下载字节、**不会自己开始下载**（可用 `adb shell dumpsys netstats` 或路由器流量佐证，或直接记“没有看到任何自动下载”）。点“继续”，等到“已安装，通过校验”，并且最上面一行变成“转录现在用的模型：sensevoice-small-int8 …”。**截图③**。记下总用时与网络（Wi-Fi/移动数据）。
4. **存储见底**（只在测试机上做）：`adb shell df /data` 看可用空间，用 `adb shell dd if=/dev/zero of=/data/local/tmp/fill bs=1M count=<N>` 把可用空间压到略小于“469.7 MB”，点“下载”：应当是“存储空间不够：需要 … 现有 …，腾出空间后重试”，**没有联网、没有下载文件**；做完 `adb shell rm /data/local/tmp/fill`。**截图④**。
5. **转录与重新转录**：用 Ticket 16 的步骤录一段固定测试句（或点“开始录音”并确认耳机路由，见 `execution/acceptance/android-capture/README.md` 第 4 节）。“模型”页的任务列表里应出现这段录音：排队中 → 转录中… → “已转出文字（第 1 条 Revision，模型 sensevoice-small-int8 …）”。点“重新转录”：出现新的一行“（重新转录）”，完成后写“第 2 条 Revision”，**第 1 条还在**。**截图⑤**。
6. **资源不够时等待并恢复**（每一项都先让条件成立，再录一段，再解除条件，观察任务）：
   - **省电模式**：下拉开启省电模式，录一段并停止：任务应写“等待：省电模式开着，关掉省电模式或充电后继续”；关掉省电模式后应自己开始转录。**截图⑥**。
   - **过热**：`adb shell cmd thermalservice override-status 3`（严重），录一段：应写“等待：手机过热，降温后继续”；`adb shell cmd thermalservice reset` 之后应继续。如果这条命令在这部手机上不可用，标 `BLOCKED_EXTERNAL`，**不要用别的办法假装过热**。
   - **内存**：没有安全的办法强行制造内存紧张。请记录：空闲时 `adb shell cat /proc/meminfo | head -3` 里的 `MemAvailable`，以及转录进行中的值。**如果这部手机空闲时 `MemAvailable` 就经常低于 800 MiB，转录会一直等**——这是阈值的问题，不是应用卡死，原样上报。
   - **等待不是无限的**：保持上述任一条件不解除，任务应在若干次自动重查之后写“已经多次重查仍不行，不再自动重试，请处理后点“重新检查””；解除条件后点“重新检查”应继续。
7. **边转录边录音**：录第一段并停止后立刻再录第二段（耳机路由同 Ticket 16）。第二段的录音状态应一直正常、停止后得到完整的一条 Record；记下第一段转录是否在第二段录音期间开始（**录音期间调度器不会开始新的转录**，录完才开始——“录音优先”）。这里能观察到的是“录音没有被转录拖坏”：第二段的文字应是完整的一句，时长与实际说话相符。
8. **取回材料**：六张截图、`adb logcat -d -s AuriCapture:I` 的状态行、`MemAvailable` 读数、手机/系统信息；写进 `execution/acceptance/android-models/results/<日期>-<手机型号>.md`，原始日志放 `results/raw/`（已被 `.gitignore` 的 `execution/**/raw/` 忽略）。结果表建议这样记：

   | 手机 / 系统 | 场景 | 结果 | 证据 | 备注 |
   |---|---|---|---|---|
   | | 模型页的显示 | | 截图① | |
   | | 下载、暂停、杀进程、继续 | | 截图②③ | |
   | | 存储见底 | | 截图④ | |
   | | 转录与重新转录（两条 Revision） | | 截图⑤ | |
   | | 省电模式等待并恢复 | | 截图⑥ | |
   | | 过热等待并恢复 | | | |
   | | 空闲与转录中的 `MemAvailable` | | | |
   | | 边转录边录音 | | | |

**真机上我们不知道、需要这次观察的事**（代码里的数字是假设，不是实测）：800 MiB 内存阈值对这部手机合不合适（太高会让转录永远等，太低会在转录中途被系统杀进程）；温度“严重（3）及以上才等”是否在手机真正降频之前就拦住了；省电模式开着时用户是否宁愿转录照常进行；Android 的 `HttpURLConnection.disconnect()` 是否像预期那样立刻让停住的下载结束（**代码的正确性不依赖它**：读线程是被放弃的，见第 6 节第 2 点；它只决定暂停后旧连接多久释放）；`MODEL_INTEGRITY_FAILED` 在真机文件系统上会不会有 JVM 测试里没有的触发方式（比如存储被系统清理了一半）。

## 6. 设计要点（iOS、鸿蒙照着做）

1. **清单是唯一的真相**：列表条目、所需空间、固定哈希、许可条目都从清单算出，没有第二份。信任锚是清单里固定的 SHA-256，**不是主机名**（GitHub 会把下载重定向到随时会变的存储主机），所以不做主机白名单；但明文 http 绝不允许，重定向时也不降级，错误信息里永远没有地址。
2. **续传的正确性来自“磁盘上的字节数 + 校验器”，不来自“收到了多少”**：状态文件（紧挨着 `.part`）记清单身份（大小与哈希）与服务器给的校验器（ETag，没有就 Last-Modified）；只信“身份与这份清单一致、并且有校验器”的状态，其余一律从头来。续传请求带 `Range` 与 `If-Range`；对 206 还要自己核对 `Content-Range` 与响应里的校验器（服务器可能不理 `If-Range`）；对续传回 200 或 416、响应声明的长度与清单不符、正文比清单还长，都作废残片。读线程多读但没写盘的字节在暂停时丢掉，所以真实下载里“多收 16 KB”不是从头再下。
3. **阻塞的读不在协程线程上做**：每个响应的正文由一条专用线程读，协程这边只从有界队列取块、写文件；暂停时读线程被放弃（最多再多读一块丢掉，然后自己关响应），`abort()` 在另一条线程上调。原因是 JDK 的 `HttpURLConnection` 里进行中的读占着流的锁，`disconnect()` 与 `close()` 都要等读返回（实测会一直阻塞到读超时），所以**暂停的生效不能取决于 abort 的速度**。`ResumableDownloaderTest` 的“暂停立刻生效”用真实传输层对着一个停住的服务器证明这一点。**因为暂停之后旧传输还要收尾，暂停或取消后马上再点“开始”时，新传输登记时记下前一个任务，等它彻底收完（放开残片）才联网、才读磁盘上的字节数**；收尾只摘登记在册的“自己”，接班的新传输始终受暂停与取消控制（第 10 节缺陷 4）。
4. **启用是两步原子操作**：校验过的暂存目录一次原子改名成全新的目录，再原子换掉指针文件 `active.json`；两步之间被杀，旧指针原样有效。新模型已经在用之后才清旧版本、没引用的目录与下载区。`active` 只会是“没装 / 损坏 / 就绪（校验过的目录）”，没有“正在装”这种中间态可被加载。每次换模型 `generation` 加一，完整性上报带着它：对旧一代的上报会被忽略，不会冤枉刚换上的新模型。
5. **调度器**：一个邮箱协程串行处理一切；**状态机是唯一的裁决者**——所有状态变化都先问转移表（`dispatch`），转移就套用并落盘，忽略或拒绝只记一条决定、什么都不改；并发度 1；引擎调用在专用的最低优先级守护线程 `auri-transcribe` 上（原生推理线程继承它的优先级），绝不占用协调器或邮箱的线程；任务写进任务库（`SqliteTranscriptionJobStore`，独立文件，坏了不影响 Record 库，重启按 Record 库对账）；`resume()` 幂等。
6. **等待的规则**：没有模型 → `WAITING_MODEL` 并记下原因码，**不轮询**，模型管理器一发信号就继续；资源不够 → `WAITING_RESOURCES` 并记下原因，按间隔自己重查，**次数有界**，用完标“需要用户处理”（任务仍在 `WAITING_RESOURCES`，持久化一个标记）；监视器读不出来按“可用”处理（引擎自己的失败路径兜底，不让一个读不出来的传感器永远挡住转录）。可重试的失败有退避与尝试上限；重启时“被杀时正在转”算一次尝试，崩溃循环有上限。
7. **录音优先**：采集在 `ROUTING` 或 `RECORDING` 时调度器不开始新的转录（原因 `RECORDING_ACTIVE`），录音一结束再开始；已经在跑的转录停不下来（真引擎 `cancel_support = none`），照常转完；封存中（`FINALIZING`）不算在录，否则每段录音自己的第一次转录都会白白经过一次“等待”。
8. **转录文字是数据**：界面只显示，日志里没有正文；`:modelmgmt` 看不到文字与音频。

## 7. 模拟器（E2）与设备端测试

三类会话，都在一次性无头模拟器上（Pixel 6 配置，Android 15 / API 35 / google_apis / arm64-v8a；每次先取共享锁 `~/.cache/auri/emulator.lock`，跑完 `adb emu kill`、删 AVD、放锁）。**全部是 E2，不是 E3**：模拟器没有蓝牙耳机，没有真实的发热与内存压力，也没有真实的移动网络。

| 会话 | 做了什么 | 结果 |
|---|---|---|
| A（RAM 2048 MB、data 4G；最终一次） | `AURI_GRADLE_ARGS=--no-daemon execution/acceptance/android-capture/run_emulator_acceptance.sh`（Ticket 16 的脚本，原样） | `:platform` 仪器测试 `OK (30 tests)`，`:app` 采集包 `OK (4 tests)`；核对器 30 + 4 个测试，0 个问题；脚本退出码 0 |
| B（RAM 4096 MB、data 10G） | Ticket 14 的 `execution/acceptance/asr-android/run_device_acceptance.sh`（取锁、建 AVD、起模拟器、清理由一个一次性包装脚本做，不入库） | `OK (29 tests)`（Ticket 14 的 25 个 + Ticket 16 的 4 个，含重写后的 `OfflineProofTest` 3 个）；核对器 29 个测试，0 个问题；三次冷启动都 `SUCCEEDED`；15 + 5 个场景的合同校验 0 个问题 |
| C（回归与变异；一次性脚本，不入库） | 同一个 `AndroidResourceMonitorTest` 连跑 15 次，再装两个变异版各跑一次（第 10 节 M-D1、M-D2） | 15 次全过；两个变异各让对应的一个测试红、其余 3 个过 |

会话 A 第一次跑（`OK (30 tests)`）被核对器判了失败，第二次跑里有一个设备测试偶发失败；两件事都在第 10 节“这次发现并修掉的缺陷”第 2、3 条里，修好后 A 重新完整跑到全过。B 跑在这两处修复之前；之后 `:app` 与它依赖的产品代码没有变（`AndroidResourceMonitor` 只改了注释），所以没有重跑 B。第 10 节缺陷 4（`ModelManager` 收尾竞争）修好之后又把 A 完整跑了一遍，结果相同（30 + 4 个测试，核对器 0 个问题，退出码 0）。

这些会话证明了什么（E2）：

- **任务库**（`SqliteTranscriptionJobStoreContractTest`，7 个）：内存任务库的同一份契约在真实的框架 SQLite 上也过——每个字段读出来一模一样、关库再开同一个文件（模拟重启）之后还在、同一任务再存是更新、按序号排、每种状态与每种等待原因都能存取、不要求 Record 存在。
- **资源监视器**（`AndroidResourceMonitorTest`，4 个）：读数来自系统（温度 0..6、可用内存、可用存储）；每个读数都接到了策略上（阈值取极端值，写死常数会被抓）；`cmd thermalservice override-status` 改温度、`cmd power set-mode` 改省电模式之后，变化信号**在触发之后**到达，信号到达时读数已是新的，且 `check()` 给出对应的等待原因，恢复后不再等。
- **飞行模式**（`OfflineProofTest`，会话 B）：应用现在申请了 INTERNET，所以“内核拒绝建 socket”那条旧证据不再成立（第 2 节“联网边界”）；新的事实是：飞行模式下 socket 能建出来、连出去在系统层以 `ENETUNREACH`/`EHOSTUNREACH` 失败（这个此前没在任何设备上验证过的假设**在这个模拟器镜像上成立**），转录照常成功且该 UID 的收发字节数没有变化（拉回的 `airplane-evidence.json`：`airplaneModeOn=1`、`Active default network: none`，等了 635 毫秒进入飞行模式；基准表里“网络字节增量”都是 0）。
- **新的 `AuriRuntime` 没有弄坏旧的设备测试**：Ticket 14 的 25 个与 Ticket 16 的 4 个设备测试在带着 `:modelmgmt`、调度器的应用上仍全过。冷启动基准（Ticket 14 的工具，模拟器，**数字不代表真机**）：中位冷启动 3760 毫秒、RTF 0.0418、VmHWM 519.4 MiB。

这些会话**没有**证明什么：Android 自带 `HttpURLConnection` 上 `abort()` 有多快（第 3 节）、在设备上真的下载一次模型、真机发热与内存吃紧时的表现、耗电。这些都是 `NOT_RUN`。

## 8. 假设（做决定时我没有问人，下面每一条都可以推翻）

1. **资源阈值是保守初值，没有真机标定**：温度 ≥ 3（严重）才等；省电模式开着就等；可用内存 < 800 MiB 或系统自报内存紧张就等；可用存储 < 100 MiB 就等。理由写在 `ResourcePolicy.kt` 的注释里。真机标定是第 5 节的事。
2. **所需空间 = 下载 + 解包 + max(64 MiB, 总量的 10%)**；续传时已下载的部分不再计入，但余量仍按完整大小算。
3. **只带占位清单**：SenseVoice-Small int8，再分发许可**未确认**（`docs/research/asr-candidates.md` 冲突 C1），所以不随应用分发、只在用户点“下载”时从清单里写的地址取；默认模型最终是谁由 Ticket 15 与产品负责人裁定，换了只需换清单。
4. **应用只在进程活着时下载**：没有前台服务/WorkManager 的后台下载；进程被杀后重开，下载显示为“已暂停”并保留已下的字节，由用户点“继续”。没有“仅 Wi-Fi”选项：下载由用户明确点击发起。
5. **`AuriRuntime` 的 `modelsDir` 参数不再被使用**（保留它是为了不改前六个位置参数，Ticket 16 的 `CaptureRefusalOnDeviceTest` 的调用照旧有效）。产品里的转录模型只来自 `ModelManager` 的 `model-store/`；Ticket 14 的 `install_on_device.sh` 放进 `files/models` 的模型现在只服务 Ticket 14 自己的设备测试，**不再喂给产品里的转录**。所以 Ticket 16 真机步骤里“装模型”那一步要改成在“模型”页下载（已在那份 README 里加了说明）。
6. **`restore()` 在应用启动时同步执行**（读几个小文件、不算哈希），所以首页出现前就知道有没有模型。
7. **任务库与 Record 库是两个文件**：任务库坏了不影响 Record，重启按 Record 库对账（没转完的补上任务，已完成的不碰）。
8. **云端三态不产生**（ADR-0019）：转移表照合同保留，调度器永不进入它们。
9. **界面刻意简陋**：另一个人会在 `ModelsUiState` / `ModelsViewModel` 上重做，这里只保证状态都来自 `ModelsUiState`、按钮出现与否由状态决定。
10. **录音时长上限不动**：仍是 `AuriRuntime.captureConfigFor` 里的临时 29.5 秒（引擎单次 30 秒减 0.5 秒），分段与长录音是 Ticket 18。
11. **模拟器 = E2**，**JVM = E2**。
12. **TDD 的接缝没能先问人**：被派发的 worker 没有人可问，我按“清单 → 列表条目、下载器、模型管理器、转录状态机与调度器、`ModelsUiState`”定了接缝（别人会依赖的是这几处），测试写在这些公共接口上。可以推翻。
13. **没有运行 Codex 评审**：本次的规则要求单一 agent、禁止起子 agent，所以评审只做了自己的变异验证（第 9 节）与对着清单的逐项核对。

## 9. 合同缺口与建议（没有改 `contracts/`，只记录）

| # | 现象 | 涉及字段 / 文件 | 建议 |
|---|---|---|---|
| G1 | 转录状态机里 `RUNNING` 没有直接去 `WAITING_MODEL` 的边；运行时才发现模型不可用时须经重试、到期、排队再等模型，已记录的尝试次数不回退。 | `contracts/fixtures/state-machines/transcription.machine.json` 的 `transitions` | 增加 `RUNNING + MODEL_MISSING → WAITING_MODEL` 并补场景。 |
| G2 | 状态机没有规定 `WAITING_RESOURCES` 的重查时间或无信号恢复策略；Android 按最多五分钟的间隔持续重查。 | 同上的 `WAITING_RESOURCES` 转移 | 由合同负责人补充跨平台恢复与退避语义。 |
| G3 | `RESOURCES_LOW` 事件不带原因，原因枚举（`THERMAL` / `BATTERY_SAVER` / `LOW_MEMORY` / `LOW_STORAGE` / `RECORDING_ACTIVE` / `UNKNOWN`）只存在于 Android 实现里 | 同上的 `events`；`error.schema.json` 没有对应的码 | 在合同里定义等待原因枚举，三端用同一套名字 |
| G4 | `QUEUED + RESOURCES_LOW` 的说明写“内存或温度不允许”，实际还会因省电模式、存储不足、正在录音而等 | 同上 `transitions[1].note` | 只是措辞；放宽说明即可 |

## 10. 测试的牙齿（变异验证）与 TDD 的诚实说明

没有为每个切片严格先红后绿：不少切片是把测试和实现一起写好再跑，测试第一次就通过。补救办法是**变异验证**：每次只改坏被测代码的一处，跑相关测试，记下变红的测试，再用 `git checkout` 还原（驱动脚本是一次性的、在 `/tmp`，没有入库；这里记录的是它实际跑出的结果）。

下表是实际跑出的结果：**26 个变异，全部被抓住**（20 个由驱动脚本跑，M-T、M-D1、M-D2 与后补的 M-R1a/b/c 是手工做的；M-D1/M-D2 在模拟器上跑设备端测试）。M1–M7 当时跑的是 `:modelmgmt:testDebugUnitTest` 的全部 69 个（之后补了两个测试，现在是 71 个）；M-R1a/b/c 只跑 `ModelManagerTest`（22、22、23 个）；`:capture` 的变异只跑调度器与状态机两个包（89 个）；`:platform` / `:app` 的联网边界与适配器变异只跑对应的守卫测试类。

| 编号 | 改坏的规则（清单项） | 改的文件 | 结果 | 变红的测试类 |
|---|---|---|---|---|
| M1 | 压缩包 SHA-256 不核对就解包（清单项 3） | `ModelManager.kt` | CAUGHT | 跑 69，红 2：ModelManagerTest×2 |
| M2 | 解出的文件不再逐个按清单核对（清单项 3） | `ModelManager.kt` | CAUGHT | 跑 69，红 1：ModelManagerTest |
| M3 | 安装一开始就清掉旧模型，失败的更新会丢掉旧模型（清单项 3） | `ModelManager.kt` | CAUGHT | 跑 69，红 2：ModelManagerTest×2 |
| M4 | 续传的 Range 总是从 0 开始（清单项 2） | `ResumableDownloader.kt` | CAUGHT | 跑 69，红 7：ModelManagerTest、ResumableDownloaderTest×6 |
| M5 | 续传不带 If-Range 校验器（清单项 2） | `ResumableDownloader.kt` | CAUGHT | 跑 69，红 3：ResumableDownloaderTest×3 |
| M6 | 所需空间不留余量（清单项 1） | `ModelCatalog.kt` | CAUGHT | 跑 69，红 2：ModelCatalogTest×2 |
| M7 | 下载状态（校验器）不落盘，进程重启后无法续传（清单项 2） | `ResumableDownloader.kt` | CAUGHT | 跑 69，红 13：ModelManagerTest×4、ResumableDownloaderTest×9 |
| M8 | 可重试的失败无限重试（清单项 5） | `TranscriptionScheduler.kt` | CAUGHT | 跑 89，红 1：SchedulerRetryTest |
| M9 | 资源不够时一直等、永远不交给用户（清单项 5） | `TranscriptionScheduler.kt` | CAUGHT | 跑 89，红 2：SchedulerWaitingTest×2 |
| M10 | 识别出空白文字也发布 Revision（清单项 8） | `TranscriptionScheduler.kt` | CAUGHT | 跑 89，红 1：SchedulerRunTest |
| M11 | Revision 不写明这次用的模型（清单项 7） | `TranscriptionScheduler.kt` | CAUGHT | 跑 89，红 2：SchedulerRetranscribeTest、SchedulerRunTest |
| M12 | 转移表里 `QUEUED + RUN_STARTED` 去了别的状态（清单项 4） | `TranscriptionMachine.kt` | CAUGHT（只跑状态机包，见下） | 跑 8，红 1：TranscriptionMachineTableTest |
| M13 | 引擎调用跑在调度器邮箱所在的线程上（清单项 6） | `TranscriptionScheduler.kt` | CAUGHT | 跑 89，红 1：TranscriptionDoesNotDropFramesTest |
| M14 | 转录线程优先级不再是最低（清单项 6） | `LowPriorityDispatcher.kt` | CAUGHT | 跑 89，红 1：TranscriptionDoesNotDropFramesTest |
| M15 | 省电模式不再让转录等待（清单项 5） | `ResourcePolicy.kt` | CAUGHT | 跑 89，红 2：ResourcePolicyTest×2 |
| M16 | 录音或确认路由时仍然开始新的转录（录音优先） | `RecordingAwareResourceMonitor.kt` | CAUGHT | 跑 89，红 3：RecordingAwareResourceMonitorTest×3 |
| M17 | `:platform` 的清单也申请 INTERNET（装音频与文字的模块有了联网权限） | `platform/…/AndroidManifest.xml` | CAUGHT | 跑 5，红 1：NetworkIsolationTest |
| M17b | 同一处改动，看应用侧的合并清单检查能不能也抓住（来源不再只有 `:modelmgmt`） | `platform/…/AndroidManifest.xml` | CAUGHT | 跑 4，红 1：NoNetworkManifestTest |
| M18 | `:platform` 的代码里引用网络 API（`java.net.URL`） | `LocalTranscriptionStack.kt` | CAUGHT | 跑 5，红 2：NetworkIsolationTest×2 |
| M19 | 引擎加载前校验器拒绝了模型，却不上报给模型管理器（损坏的模型不会被标记） | `ActiveModelTranscriptionAdapter.kt` | CAUGHT | 跑 29，红 3：ActiveModelTranscriptionAdapterTest×3 |
| M-T | 去掉 `UrlConnectionTransport` 里的 `readTimeout = readTimeoutMs`（手工） | `HttpTransport.kt` | CAUGHT | `UrlConnectionTransportTest` 的读超时测试，在它自己的 10 秒上限（实测 10.029 秒）后失败 |
| M-D1 | `AndroidResourceMonitor` 收到省电模式广播却不发信号（手工；模拟器） | `AndroidResourceMonitor.kt` | CAUGHT | 设备端 4 个跑 1 个红：`batterySaverIsAnnouncedAndMakesTheJobWait`，“触发之后 10 秒内没有收到变化信号”，其余 3 个过 |
| M-D2 | 温度监听收到变化却不发信号（手工；模拟器） | `AndroidResourceMonitor.kt` | CAUGHT | 设备端 4 个跑 1 个红：`aThermalStatusChangeIsAnnouncedAndMakesTheJobWait`，同样的信息，其余 3 个过 |
| M-R1a | 暂停之后马上点开始，新传输不等旧传输收尾就去读残片（手工；清单项 2） | `ModelManager.kt` | CAUGHT | 跑 22，红 1：ModelManagerTest 的暂停变体，旧传输还没放开残片时已经有第二个写入者（`expected:<1> but was:<2>`） |
| M-R1b | 旧传输收尾时不认自己，把登记在册的新传输也摘掉（手工；清单项 2） | `ModelManager.kt` | CAUGHT | 跑 22，红 1：ModelManagerTest 的暂停变体，新传输脱离了暂停的控制，等“已暂停”超时 |
| M-R1c | 新传输开始时无条件清掉上一个传输还没读的停止原因（手工；清单项 2） | `ModelManager.kt` | CAUGHT | 跑 23，红 1：ModelManagerTest 的取消变体，新传输带着 `Range: bytes=60000-` 接上了一个已经放弃的残片（`expected:<[null, null]> but was:<[null, bytes=60000-]>`） |

两点说明：

- **M12 第一次是对全部调度器测试跑的，结果是 `HANG`**：转移表错了之后调度器在同一个虚拟时刻里 `QUEUED → WAITING_MODEL → QUEUED …` 无限兜圈，每个调度器测试都被 `BoundedSchedulerTest` 的 30 秒看门狗判为失败，整套要 40 分钟以上，撞上了驱动脚本的 420 秒上限。这算“抓住了”（没有一个调度器测试能在错表下通过），但反馈很慢；表的守卫本来就是状态机测试，所以表里记的是只跑状态机包得到的干净的红。看门狗是更早的一次变异（把“引擎拒绝过的模型”忘掉的变体让测试 JVM 空转了十几分钟）之后加的。
- 变异验证只证明“这些规则改坏时测试会红”，不证明没有别的规则没人守。清单逐项对照（第 2 节）列出的是每一项最该红的测试。

### 这次发现并修掉的缺陷

1. **`UrlConnectionTransportTest` 里“abort 立刻让卡在读取上的线程失败”写错了，而且不可靠**：JDK 的 `HttpURLConnection` 里进行中的读占着 `MeteredStream` 的锁，`disconnect()` 与流的 `close()` 都要等这把锁（用独立的小程序抓到了阻塞线程的栈：卡在 `MeteredStream.lock`），所以在 JVM 上 abort 总是晚于读超时。旧测试把读超时设成 60 秒，只是碰巧等满 60 秒才“通过”，并且在假服务器自己的 60 秒停顿同时到期时读到“正常结束”而失败（连跑 6 次失败 3 次，每次都耗 60 秒）。这不是调高超时能修的——断言本身在 JDK 上就不成立。处理：换成传输层真正能保证的性质（停住的连接以读超时失败，不挂住、也不读到“正常结束”；去掉 `readTimeout` 后 10 秒超时失败，见 M-T）；“暂停立刻生效”本来就由 `ResumableDownloaderTest` 用同一个真实传输层证明（读线程被放弃、abort 在别的线程上调）；`TransportResponse.abort()` 与 `Pump.release()` 的注释也不再声称“Android 上立刻生效”，因为没有在设备上验证过。这个测试没有被“削弱后放过”：原来的强断言在 JDK 上不可能成立，Android 上是否成立仍是一个**未验证的假设**（第 5 节末尾）。
2. **`AndroidResourceMonitorTest` 的省电模式测试偶发失败，而且它的“变化信号”那一半断言是空的**：同一个脚本跑两次，第一次 `OK (30 tests)`，第二次 `Tests run: 30, Failures: 1`（`batterySaverIsAnnouncedAndMakesTheJobWait`：“系统报告省电模式已开”不成立）。单独连跑这个测试：12 次失败 4 次。两层原因：
   - **竞争**：测试先 `dumpsys battery unplug` 就立刻 `cmd power set-mode 1`。系统过几百毫秒才认到“没插电”，而省电状态机在“插着电”时会**悄悄吞掉**手动开启（读 AOSP android15 `BatterySaverStateMachine.updateStateLocked`：`STATE_OFF` 且 `mIsPowered` 时手动开启什么也不做）。用 shell 直接探：拔电后立刻开，5 次里 4 次被吞（状态机 `mState` 一直是 1）；等到 `dumpsys power` 里 `mIsPowered=false` 再开，5 次 5 次生效。
   - **断言是空的**：测试用 `changes.first()` 等“变化信号”，但订阅温度监听时系统立刻回报一次当前状态（读 AOSP `ThermalManagerService.registerThermalStatusListener`：注册即 `postStatusListener`），所以什么都没变 `first()` 也会返回。上一版的两个“变化会通知”测试（温度、省电）因此什么也没证明，还把上面那个竞争藏了起来（失败的 4 次各耗时约 2 秒，成功的 3–4.4 秒，与“`first()` 立刻返回、紧接着的第二次开启也落在系统认到拔电之前”一致；没有逐次抓到失败时的时间线，这一句是推断）。
   修法：先轮询到系统自己说没插电；订阅后先丢掉初始回报，只认**触发之后**的信号；信号来的时候读数必须已经是新的（否则调度器收到信号去重查会读到旧值）。验证：修后同一个测试类在模拟器上连跑 15 次全过（修前同一环境 12 次失败 4 次）；变异 M-D1、M-D2 各自让对应的一个测试红、其余过。**这是 Android 系统行为在模拟器上的观察（E2）**，真机上同一套命令是否一样要真机验证（第 5 节）。
3. **`check_capture_instrumentation.py`（Ticket 16 的工具）只认 `RecordLibraryContract` 一个契约基类**：`SqliteTranscriptionJobStoreContractTest` 继承来的 7 个测试在设备上都通过了，核对器却把它们判成“源码里找不到”，整个验收脚本以 1 退出。这个工具自己写明“有第二个契约基类时再泛化”；现在登记成 `CONTRACT_BASES`（两个），新增 3 个测试先红后绿，并用保存下来的真实日志重核对（30 + 4 个，0 个问题），之后又把整个脚本完整跑了一遍。
4. **`ModelManager`：暂停或取消之后立刻再点“开始”，新旧两个传输会同时碰同一个残片，而且新的那个脱离控制**（收尾前对整个模块的复查找出来的，不是测试挂了）。`start()` 只看旧任务是不是还“活着”，而被暂停或取消的任务在收尾（放开残片文件、发布最终状态）期间已经不算“活”：于是新传输马上开始，与还没收完尾的旧传输同时写一个 `.part`；旧传输收尾时又无条件把登记的任务摘掉，把新传输一并摘了，之后对它按暂停、按取消都找不到任务，界面上的“暂停”按钮失灵。先写失败的测试（`ModelManagerTest` 的暂停变体：第一个写入流在关闭时被卡住，复现“暂停后收尾期间再开始”，红：`expected:<1> but was:<2>`）再修：新传输登记时记下前一个任务，**等它彻底收完才读磁盘上的字节数**；收尾只摘登记在册的“自己”；前一个任务还没读的停止原因留给它自己读，只在它已经收完（没人会读了）时才清掉。取消变体另有一个测试钉住这一点（旧传输先把放弃的残片清掉，新传输从头开始，不接着一个已经放弃的残片）。三个变异 M-R1a/b/c 各被抓住。**这是 JVM 上用被卡住的写入流造出来的竞争（E2）**，真机上的时序不同，但“同一时刻一个残片只有一个写入者”这条不变量与时序无关。
5. **`./gradlew --stop` 是全机器范围的**：同一台机器上多个工作树共用 `~/.gradle` 的守护进程登记，一个工作树收尾时跑的 `--stop` 会停掉另一个工作树正在跑的构建（报 `Gradle build daemon has been stopped: stop command received`，不是代码的问题）。在共用机器上跑验收时给两条命令都加 `-Dorg.gradle.daemon.registry.base=<私有目录>`（这样 `--stop` 只停自己的）；`AURI_GRADLE_ARGS` 也能带上它。

## 11. 已知限制与后续

- **没有真机结果**（第 5 节）；资源阈值未标定；Android 的 `disconnect()` 速度未验证。
- 下载只在进程活着时进行，没有后台下载服务，没有“仅 Wi-Fi”选项。
- 清单里的下载地址是 GitHub 发布资产：如果上游换了资产，固定的哈希会不符、用户会看到“下载的内容与清单固定的哈希不符”，要换清单才能装。这是想要的行为（信任锚是哈希），但会表现为“装不上”。
- 只有占位清单；默认模型与许可裁定是 Ticket 15。
- `:modelmgmt` 的“没有 INTERNET 权限”那种系统级证明不复存在（第 2 节“联网边界”）。
- 手机上的耗电、转录期间的发热、长录音分段（Ticket 18）都没有评估。
