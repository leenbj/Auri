# Ticket 19 验收：Android 中断、断连与崩溃恢复

对应 `.scratch/auri-v1/issues/19-android-interruptions-and-recovery.md`（GitHub issue #20）。PRD 引用：FR-REC-001/002（严格蓝牙输入；路由不明立刻关闸）、FR-REC-003（分段落盘与崩溃恢复）、FR-REC-004（结束原因）、T-014/T-016/T-033/T-034，规格 `docs/spec/auri-v1-spec.md` 第 12 章（采集状态机：进程恢复后回到 `DISARMED`，不自动开麦），ADR-0014/0016/0020。

**状态（2026-11-20）**

- **没有任何真机（E3）结果**：本机没有 Android 手机和耳机，并且按共享机器的约定没有启动模拟器、AVD、Android Studio 或任何仪器测试。第 4 节的五个场景（断开耳机、来电、杀进程、撤销权限、存储写满）全部是 **`NOT_RUN (reason: deferred, emulator disabled to save memory)`**。JVM 单测与假适配器最多是 **E2**：证明不了蓝牙路由、系统打断回调、真实磁盘写满、进程真的被杀时的行为。
- 清单 1–6 项都有 JVM 测试（第 2 节）。其中“存储写满”和“进程被杀”是**真实协调器 + 真实 `WavRecordingStore` + 故障磁盘**的端到端扫描（第 9 节有量到的数字）。关键规则做过变异验证（第 8 节，共 42 条：独立评审之前的 26 条（含 C2b）在评审修复之后全部重跑过，仍然全部被杀死；评审修复新增 16 条，也全部被杀死；结果如实列出，包括一开始没被杀死的几条是怎么补的）。
- 合同有两处缺口，没有改 `contracts/`，只记录（第 7 节）。
- **独立评审（范围 `4162fa9..8d4f710`）的 7 个发现（B1、B2、H1、H2、M1、M2、M3）已全部修复**：每个发现一个提交、先写出会红的测试再改代码（H1 的红灯证据见第 8 节的诚实说明）、带变异验证；逐条对照在第 12 节。
- 本票只新增一个设备端测试用例（评审 H1：`:platform` 的 `SqliteRecordLibraryTest` 里“录音目录挪家之后…”），**`NOT_RUN`**（只编译、没有运行）；已有的设备端测试（如 `ForegroundServiceOnDeviceTest`）在这一票涉及的场景下同样 `NOT_RUN`。

证据等级沿用 PRD 第 5.2 节：JVM 单测 = **E2**；真机 = E3。没有真机的项标 `NOT_RUN`，不用模拟或伪造替代。

## 1. 这里有什么

| 路径（`apps/android/` 下） | 作用 |
|---|---|
| `capture/.../coordinator/CaptureCoordinator.kt` | 中断统一收场 `stopRecording(endReason, tail, interrupted)`：关闸、释放采集、封存已确认的部分；路由丢失时**丢掉分析窗里的尾巴**，并把跨在变化点上的一段记成 `route_unverified`；存储写不下按 `system_interruption` 收场；`snapshot.interruption` / `interruptionReason` 供界面与日志使用 |
| `capture/.../recovery/OrphanRecovery.kt`、`StartupRecovery.kt`；`capture/.../adapter/Recovery.kt` | 把磁盘上的孤儿录音登记成 `partial + process_terminated` 的 Record，交给与别的录音相同的管线转录；`StartupRecovery` 包住所有异常，日志只有计数 |
| `platform/.../storage/WavRecordingStore.kt`、`RecordingJournal.kt`、`StorageIo.kt` | 分段落盘：`<id>.wav.part` + `<id>.journal`，数据 fsync → 日志行 → 日志 fsync；`append` 全有或全无；下次启动逐段校验 SHA-256、截掉半截尾巴、修头、改名；封存要等最后一段的日志行落盘，写不进去就不封存（评审 B2）；没有语音的录音保留恢复记录直到登记（`finishWithoutAudio`，评审 M1）；恢复在 Record 登记之前不删任何东西（评审 M2） |
| `platform/.../audio/BluetoothCaptureAdapter.kt` | 麦克风 / 蓝牙连接权限被撤销的检测（Android 没有撤销回调：每读完一批核对一次，回到前台再核对一次；读线程发现的撤销带着会话标签走本次录音的 sink，评审 H2）；系统的路由通知本身使证据作废（评审 B1）；系统不给音频焦点就不开始录音（评审 M3） |
| `platform/.../library/AudioRelocation.kt`、`SqliteRecordLibrary.relocateAudio`；`app/.../AuriRuntime.kt`（`init`） | 评审 H1：升级时录音目录整个挪到 noBackup，库里登记的音频位置每次启动幂等地跟着改（只在老目录已经不在时改；在调度器与管线创建之前做完） |
| `app/.../home/HomeViewModel.kt`、`ui/HomeScreen.kt`、`AuriRuntime.kt`、`MainActivity.kt`、`CaptureLog.kt` | 首页说明这次为什么停、列表里标出被截断的 Record；启动时跑恢复（不碰协调器）；回到前台时核对权限；日志只有状态与原因 |
| `capture/src/test/.../InterruptionTest.kt`、`InterruptionScenarioReplayTest.kt`、`recovery/OrphanRecoveryTest.kt`、`StartupRecoveryTest.kt` | 协调器这一层的中断规则、合同场景回放、恢复登记 |
| `platform/src/test/.../storage/RecordingRecoveryTest.kt`、`InterruptionEndToEndTest.kt`、`RestartRecoveryTest.kt`、`NoSpeechRecordDurabilityTest.kt`、`FaultyDisk.kt`、`RealStoreRig.kt` | 真实存储的落盘顺序与恢复、写满与被杀的端到端扫描、重启后保持 `DISARMED`；`NoSpeechRecordDurabilityTest`（真协调器 + 真存储 + 真管线 + 真恢复）：没有语音的 Record 在登记前被杀、库写失败时仍能找回；`FaultyDisk` 是可以在选定字节处写满或“杀进程”的假磁盘 |
| `platform/src/test/.../audio/BluetoothCaptureAdapterTest.kt`、`RouteBoundaryTest.kt`、`PermissionRevocationRaceTest.kt`；`app/src/test/.../home/*`、`ui/InterruptionTextTest.kt` | 权限撤销的读线程行为、路由通知使证据作废、音频焦点被拒、迟到的旧通知不能打断新录音（评审 B1、H2、M3）；首页状态与文案 |
| `platform/src/test/.../library/RecordingsMoveUpgradeTest.kt`、`RelocateAudioSqlTest.kt` | 评审 H1：从旧版本的状态（真文件 + 真 SQLite）升级、三种被中断的升级、挪目录失败、两处都有目录、幂等；SQL 本身 |

## 2. 清单逐项对照

| 清单项（`.scratch/.../19-...md`） | 覆盖它的测试（JVM，E2） | 真机（E3） |
|---|---|---|
| 1. 路由变化或耳机断开时立即关闸，保存已确认的部分，标为被截断；变化前后的歧义帧丢弃，不保留 | `InterruptionTest`：路由变成手机麦克风（尾巴丢弃 + `route_unverified`）、耳机断开、**路由事件还没到时 epoch 不符的帧一个采样都不写**、正常停止对照（尾巴照常保留）；Ticket 16 的 `CaptureStalenessTest`、`CaptureGateTest`；`InterruptionScenarioReplayTest`；评审 B1：`BluetoothCaptureAdapterTest`、`RouteBoundaryTest`（路由通知被压到耳机恢复之后才递送：设备看上去没变也按 `route_lost` 收场，通知之后什么都没写） | 4.A **`NOT_RUN`** |
| 2. 系统中断后不自动续录 | `InterruptionTest`：七种中断各自收场后，把旧会话的路由确认与帧、重复的中断、权限、一个小时的时间都递一遍，适配器请求数和存储 `begin` 数都不变；多个中断同时到只收场一次；准备阶段被打断。`HomeFlowTest`：打断后按钮回到“能开始”，一小时内不自己开始 | 4.A、4.B **`NOT_RUN`** |
| 3. 录音分段写入；杀进程后重新打开能恢复出已写入的片段 | `RecordingRecoveryTest`（落盘顺序、撕断的日志行、被改的字节、幂等、恢复做到一半被杀、本进程正在写的不当孤儿……）；`InterruptionEndToEndTest` 在 45 个字节位置“杀进程”；`OrphanRecoveryTest`、`StartupRecoveryTest`：找回的是 `partial + process_terminated`，与别的 Record 同路径转录，重复恢复不重复；评审 B2/M1/M2：封存要等最后一行日志（`RecordingRecoveryTest`、`InterruptionEndToEndTest`）、没有语音的 Record 在登记前被杀或库写失败仍能找回（`NoSpeechRecordDurabilityTest`）、第一个分段不可信时恢复可重复（`RecordingRecoveryTest`，含逐字节被杀扫描） | 4.C **`NOT_RUN`** |
| 4. 进程恢复后采集处于未待命状态，不自动开麦 | `RestartRecoveryTest`：磁盘上有孤儿时，真协调器 + 真存储 + 真前台服务绑定器，重启后仍 `DISARMED`、采集适配器零请求、前台服务没起、没自动待命，一小时后也没有；`StartupRecoveryTest`；`InterruptionScenarioReplayTest` 回放五个 `process-restored-from-*` 场景；`ForegroundServiceBinderTest` | 4.C **`NOT_RUN`** |
| 5. 权限被撤销时安全结束 | `BluetoothCaptureAdapterTest`：麦克风、蓝牙连接权限分别被撤销时，那一批不递送、只通知一次、读线程退出、资源各放一次、读失败不再误报“路由变了”；`InterruptionTest`（两种权限）；`CaptureResultTest`：`permission_revoked`；`InterruptionTextTest`、`HomeFlowTest`：界面状态；评审 H2：读线程发现的撤销带会话标签，迟到的旧通知不能打断新录音（`PermissionRevocationRaceTest`、`InterruptionTest`） | 4.D **`NOT_RUN`** |
| 6. 存储空间不足时安全结束并保留可恢复的片段 | `RecordingRecoveryTest`：在选定字节写满、写满后封存与释放、`begin` 遇到写满、提交时日志写不进去、封存时改名失败；`InterruptionEndToEndTest`：写满扫描（每个位置跑“封存顺利”与“封存时改名失败”两遍）；`InterruptionTest`、`CaptureResultTest`（含 Ticket 18 评审修复的“首批写入失败”“停止时尾巴写不下”）；`InterruptionTextTest`：界面说“存储空间已满”；评审 B2：写满到放不下最后一行日志时封存不完成、不留 `.wav`、通知丢失（`InterruptionEndToEndTest`） | 4.E **`NOT_RUN`** |
| 7. 真机上分别验证断开耳机、来电、杀进程 | 第 4 节的操作步骤（另加撤销权限、存储写满） | **`NOT_RUN`** |

**合同场景回放**：`InterruptionScenarioReplayTest` 读 `contracts/fixtures/state-machines/capture.scenarios.json`，凡是含 `ROUTE_LOST`、`SYSTEM_INTERRUPTION`、`PERMISSION_REVOKED`、`PROCESS_RESTORED` 之一的场景（20 个里有 10 个）逐个回放：结束原因与合同一致、永不自己续录、进程恢复之后是 `DISARMED`。测试最后核对“回放过的场景名 == 选出来的场景名”，所以**漏回放一个场景它会红**（变异 P1 证明）；另有一个测试保证四种打断事件每种至少出现在一个场景里，不会因为合同样本变了而悄悄什么都没回放。

## 3. 在本机怎么跑

下面每一条都先取共享 Gradle 锁（`~/.cache/auri/gradle.lock`），并带上 `--no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2`；跑之前后看一眼内存（`memory_pressure | grep -i 'free percentage'`、`sysctl vm.swapusage`）。

```bash
source ~/.config/auri/android-env.sh
cd apps/android
./gradlew :capture:test :platform:testDebugUnitTest :app:testDebugUnitTest # JVM 单测（369 + 257 + 188），不需要设备
./gradlew :platform:testDebugUnitTest --tests app.auri.platform.storage.InterruptionEndToEndTest # 写满与被杀的扫描；报告里有 MEASURED 行
./gradlew :capture:test --tests 'app.auri.capture.coordinator.Interruption*' --tests 'app.auri.capture.recovery.*' # 协调器这一层
adb logcat -d -s AuriCapture:I # 真机：状态变化、最近一次打断的原因，以及启动恢复的计数（没有文字、路径、标识）
```

输入没变的测试会被判 `UP-TO-DATE` 而不执行，要强制重跑加 `--rerun`（或先 `:capture:cleanTest` 等）。

## 4. 产品负责人：真机验证流程（`NOT_RUN`）

**全部 `NOT_RUN (reason: deferred, emulator disabled to save memory)`**：没有手机和耳机，也没有用模拟器代替。以下步骤由产品负责人在装了 debug 构建的 Android 手机上做；不要把个人录音、转录文字、设备标识或凭证复制进仓库。

准备：记下手机型号、Android 版本、耳机型号与固件、应用提交（`git rev-parse --short HEAD`）；授予麦克风、蓝牙连接、通知权限；配对耳机并打开“耳机控制”或直接用屏幕按钮开始。开始前清空旧日志：`adb logcat -c`。每个场景做完用 `adb logcat -d -s AuriCapture:I` 取日志，应该能看到 `state=… reason=… input=… interrupted=<原因> code=<错误码>`（例如 `interrupted=ROUTE_LOST code=INPUT_DEVICE_MISSING`）。

**A. 断开耳机（清单 1、2）**

1. 戴上耳机，打开 Auri，确认首页“实际输入”是蓝牙；按“开始录音”，说一两句话（约 10 秒）。
2. 录音中把耳机关机（或放回充电盒、或在系统里断开蓝牙）。**记下从断开到界面提示用了几秒。**
3. 预期：录音自动停止；首页写明“耳机断开，或输入来源变了”以及“不会自动继续”；按钮回到“开始”；列表多出一条标着“被截断”的 Record，并照常转录出文字，内容只包含断开之前说的；手机没有改用手机麦克风录音（麦克风使用指示点熄灭）。
4. 再把耳机连回来，等 1 分钟：**不会自己开始录音**。
5. 另做一次：录音中让系统把输入切到别处（例如在系统蓝牙设置里关掉该耳机的“通话音频”；各品牌设置项名称不同），同样应停止并被截断，而且**不会**改用手机麦克风继续录。能不能触发这种切换取决于系统，请记下实际现象。

**B. 来电或抢占音频焦点（清单 2）**

1. 开始录音后，用另一部电话打进来；分别试“不接，等它响完”和“接听再挂断”。**记下是振铃时、接听时还是挂断时录音才停。**
2. 备选（没有第二部电话）：录音中打开音乐应用点播放，抢走音频焦点。
3. 预期：录音停止，提示“来电或其他应用占用了音频”；已录的部分成为被截断的 Record（`system_interruption`）；通话结束、音乐停止之后**不会自己续录**。代码把永久丢失与暂时丢失焦点都当作打断，只有“可压低音量”不算；各品牌系统在振铃时是否给焦点变化各不相同，以实测为准。

**C. 杀进程与重启（清单 3、4）**

1. 开始录音，说约 20 秒，然后任选一种方式杀掉进程：从最近任务划掉；`adb shell am force-stop app.auri.android`；debug 构建上 `adb shell run-as app.auri.android kill -9 $(adb shell pidof app.auri.android)`。三种各做一次。
2. 立刻重新打开应用。预期：几秒内列表出现一条被截断的 Record，写明“应用被中途关闭，已从磁盘找回”，并被转录；音频是被杀之前已经落盘的部分——**代码的设计上限是丢失最后约 1 秒多（一个提交间隔加一次写入加一个分析窗，推导值约 1.11 秒，没有在真机上量过）；请记下实际丢了多少。**
3. 首页处于“未待命/未开始”：没有麦克风指示点、没有前台服务通知；`adb shell dumpsys activity services app.auri.android | grep -i MicCapture` 没有输出。logcat 里有一行 `orphan recovery: recovered=1 withoutAudio=0 failed=0`。
4. 等 5 分钟：不会自己开始录音。
5. 再做一次：杀进程之后**不要**打开应用，等 5 分钟。预期：系统没有把前台服务拉起来（`MicCaptureForegroundService` 返回 `START_NOT_STICKY`，这一点 JVM 上测不了，只能在这里看），没有任何录音。

**D. 撤销权限（清单 5）**

1. 开始录音后，在系统设置里关闭 Auri 的“麦克风”权限；再单独做一次关闭“附近的设备/蓝牙”权限。也可以用 `adb shell pm revoke app.auri.android android.permission.RECORD_AUDIO` 与 `android.permission.BLUETOOTH_CONNECT`。
2. **Android 往往会在撤销权限时直接终止应用进程**。所以这里有两种合格的结果：进程没被杀——首页写明“麦克风权限被关闭”（或“蓝牙连接权限被关闭”），录音停止、已录部分成为被截断的 Record；进程被杀——按 C 的预期，重新打开后找回。两种都**不应崩溃、不应自己开始**。请记下你看到的是哪一种。
3. 重新授予权限后，应用不会自己开始录音。

**E. 存储写满（清单 6）——只在备用机上做**

1. `adb shell df /data` 看剩余空间；用填充文件把空间填到只剩几十 MB：`adb shell dd if=/dev/zero of=/sdcard/Download/auri-filler.bin bs=1M count=<剩余 MB 减 30>`（`/sdcard` 与 `/data` 在多数手机上是同一个分区）。
2. 开始录音，一直说话直到写满。预期：录音自动停止，首页写明“存储空间已满”；已写下的部分保留——列表里有一条被截断的 Record，或者在下次启动时由恢复找回；不崩溃。
3. 清理：`adb shell rm /sdcard/Download/auri-filler.bin`。腾出空间之后可以重新开始录音，旧的恢复记录不重复出现。

**F. 仪器测试（设备端，`NOT_RUN`）**

本票新增一个设备端测试用例（评审 H1：`:platform` 的 `SqliteRecordLibraryTest` 里“录音目录挪家之后…”，已编译、没有运行）；它与已有的设备端测试一起，等有设备时取共享锁后跑：

```bash
source ~/.config/auri/android-env.sh
cd apps/android
./gradlew :app:connectedDebugAndroidTest :platform:connectedDebugAndroidTest --no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2
```

## 5. 设计要点（iOS、鸿蒙照着做）

- **打断只有一种收场方式**：关闸（句柄先作废，之后到的帧一律丢弃）→ 释放采集（恰好一次）→ 封存已确认的部分 → 回到待命，**不发任何“开始”**。结束原因四选一：`route_lost`（路由变了、耳机断开）、`system_interruption`（来电或抢焦点、前台服务没了、存储写不下）、`permission_revoked`、`process_terminated`（只有启动恢复会产生）；只要结束原因属于这四个，Record 状态就是 `PARTIAL`。
- **路由丢失与别的打断不一样**：分析窗里攒着的那几个采样跨在变化点上，不知道属于哪一边，所以丢掉，并把“最后一个整窗 → 变化时刻”整段记成 `route_unverified` 丢弃区间，而不是混进静音。epoch 与已确认路由对不上的帧在录音中直接丢（路由事件可能比帧晚到）。别的原因（用户停止、时长上限）尾巴照常保留。**系统的路由通知本身使证据作废**（评审 B1）：耳机断开又恢复、通知被占住的主线程压到耳机恢复之后才递送时，读到的设备看上去没变，但中间变过；所以通知一律使已有证据作废、epoch 递增、按路由变化收场。唯一的例外是录音刚启动、还没递送过任何帧时的第一条通知（平台在启动时会发一条初始通知，证据还没被用过，只确认；见第 6 节假设 14）。判断一批帧与递送它在同一把锁里，通知不会插在“判断通过”与“递送”之间。
- **分段落盘与日志**：每个 Record 一个 `.wav.part`（头部先占位）加一个 `.journal`。日志一行一条记录：`<8 位 CRC32> <JSON>\n`；第一行 `begin`（Record、会话、开始时间、确认路由的那次观测、格式）在任何音频之前落盘；之后每个**已提交**的分段一行 `seg`（时间线位置、累计字节数、这段字节的 SHA-256）。提交顺序固定：数据 fsync → 写日志行 → 日志 fsync。一次 `append` 要么整批写下要么什么都不留，写不下时把写了一半的字节截掉再抛 `IOException`，所以协调器的计数与磁盘永远一致。封存先提交最后一段的日志行（要几十个字节的新空间），写不进去就**不封存**、不改名、不留 `.wav`（评审 B2：留下一个没有任何分段记录的 `.wav`，登记之前被杀，之后每次恢复都得拒收它），协调器通知“丢失（存储）”，已提交的分段下次启动找回；头部原地改写与改名本身不要新空间。提交间隔生产 1 秒。
- **恢复**：下次启动扫描目录，读日志时遇到第一条不完整、校验不对、读不懂的行就停（之后的一律不信）；逐段重算 SHA-256，对不上的段与其后的丢弃；数据文件截回最后一个有效边界、修头、改名，然后交给 Record 管线登记并转录。幂等（恢复做到一半被杀也能再恢复）：**恢复在 Record 登记之前不删任何还欠着 Record 的东西**（评审 M2）——没有可信字节的 `.part` 空壳连同日志原样留着，每次恢复得到同一个没有音频的 Record；Record 登记之后的那次启动才清掉空壳与日志（先删空壳、最后删日志）。已经登记过的 Record 不重复登记，只清理日志；本进程正在写、或已经封存交付的录音扫描绝不碰。**没有语音的录音**（路由确认了、一个采样也没写）同理（评审 M1）：不留音频文件，日志留到登记之后；登记之前被杀或库写失败，下次启动找回一个没有语音的 Record。只有“存储写不下导致一个采样都没写成”（用户收到的是“录音丢失”，没有 Record 欠着）才连日志一起删。找回的 Record 一律 `partial + process_terminated`，时间线只来自验证过的分段，空隙记为没有保留的静音，**采集时长是下限**。
- **重启之后永远是 `DISARMED`**：新进程的状态机本来就从 `DISARMED` 开始，恢复流程不持有协调器、不开麦、不起前台服务、不待命。前台服务只在 `ROUTING / RECORDING / FINALIZING` 才会被绑定器拉起，并且返回 `START_NOT_STICKY`。
- **权限撤销**：Android 没有撤销回调，只能轮询——读线程每读完一批核对一次，应用回到前台再核对一次；只在“有 → 没有”的那一刻通知一次；撤销时读到的那一批不递送；读失败时如果原因是权限，只报权限，不再另报“输入路由变了”。**读线程发现的撤销带着会话标签走本次录音的 sink**（评审 H2）：协调器按会话与代数核对，迟到的旧通知被拒收并记一笔，不能打断新的录音；界面线程的核对仍走全局处理器；已经结束的采集不认领这一次“有 → 没有”的边沿。iOS、鸿蒙的撤销通知机制不同，各端按平台调研结果做，但**收场方式相同**。
- **日志**只写状态、原因和计数，不写文字、音频、设备名、路径或标识。
- **音频焦点**（评审 M3）：系统不给焦点就不开始录音：采集以 `AUDIO_INTERRUPTED`（与来电抢占音频时同一个原因与文案）失败，不打开录音、不启动读线程，已经请求的通信路由撤销恰好一次。
- **升级迁移**（评审 H1）：升级时录音目录整个从 `filesDir` 挪到 `noBackupFilesDir`，库里登记的音频位置是绝对路径，必须跟着改。每次启动都做（幂等：改过的行不再匹配老前缀），只在老目录**已经不在**时改（还在说明没挪成，登记的位置仍指着有文件的地方）；在转录调度器与 Record 管线创建之前同步做完，所以没有谁读到旧位置。挪目录与改库之间无论在哪里被杀，下次启动都会补完。

## 6. 假设（做决定时我没有问人，下面每一条都可以推翻）

1. **原因 → 结束原因的映射**：路由变化与耳机断开 → `route_lost`；来电或抢焦点、前台服务没了、存储写不下、没预料到的内部错误 → `system_interruption`；权限撤销 → `permission_revoked`；启动恢复 → `process_terminated`。存储写满没有专属结束原因（合同缺口，见第 7 节），界面靠错误码 `STORAGE_EXHAUSTED` 区分。
2. **提交间隔 1 秒**：折中 fsync 的频率和掉尾的量。没有在真机上标定，电池和闪存磨损没有评估。
3. **找回的 Record 一律 `process_terminated`**：从磁盘上分不出是被杀、掉电还是崩溃。采集时长取已验证分段的末端（下限），不是精确值。
4. **一个采样都没写下就写满**：按“录音丢失（存储）”通知并且不留 Record，而不是留一个没有音频的 Record 冒充“无语音”（沿用 Ticket 18 评审 R2 的决定）；磁盘上不留残渣。
5. **蓝牙连接权限被撤销沿用 `INPUT_DEVICE_MISSING`**（合同没有专门的错误码）；界面靠结束原因 `permission_revoked` 说成“蓝牙连接权限被关闭”，与耳机断开的说法不同。
6. **权限撤销靠轮询**（每批读完、回到前台），不是即时回调；在设置里撤销权限时，系统通常会直接杀进程，那时走恢复路径（真机行为 `NOT_RUN`）。
7. **`PROCESS_RESTORED` 事件生产代码从不发送**：新进程本来就是 `DISARMED`，恢复只产生 `partial + process_terminated` 的 Record；该事件只在合同转移表里，由场景回放覆盖。
8. **`AuriRuntime` 的装配没有 JVM 测试**：它需要 Android `Application`。“重启不自动待命”由 `RestartRecoveryTest`（真协调器、真存储、真前台服务绑定器）与 `StartupRecovery`（构造上不持有协调器）覆盖；装配本身只有代码审阅与真机步骤。
9. **`MicCaptureForegroundService` 返回 `START_NOT_STICKY`**：JVM 上测不了，只有代码审阅；真机步骤 4.C 第 5 点验证。
10. **启动恢复与用户第一次开始录音之间的竞争**：存储对象里“正在写 / 已交付”的登记表保证扫描不碰本进程正在写、或已封存交付的录音。
11. **失去音频焦点**：永久丢失与暂时丢失都按打断结束，不等焦点回来（因为不自动续录）；“可压低音量”不算。
12. **界面文案**（“录音已停止：……不会自动继续”等）是我写的，没有经过产品负责人审阅。
13. **登记前被杀而恢复出来的“没有语音”的 Record 是 `partial + process_terminated`**（评审 M1）：原来的结束原因（用户停止还是时长上限）从磁盘上看不出来，与第 3 条同理；只有登记之前进程被杀、或库写失败时才走到这一步，正常登记的 Record 原样保留。
14. **初始路由通知豁免**（评审 B1）：假设平台在开始采集时会发一条初始路由通知（真机验证 `NOT_RUN`）；没有这条豁免，每次录音都会被这条通知自己打断。代价：恰好在“开始之后、第一批帧之前”再变一次、并且只有这一条通知到达时，这一次变化会被当成初始通知，不使证据作废（之后的任何通知都照常作废）。
15. **音频焦点被拒用 `AUDIO_INTERRUPTED`**（评审 M3）：沿用来电抢占音频时已有的原因与文案；合同没有更精确的错误码。
16. **权限撤销通知有两条路**（评审 H2）：界面线程的核对走全局处理器；读线程发现的走本次录音的 sink；一次“有 → 没有”的边沿只被一个观察者认领一次。
17. **没有修的残余竞争（评审 H2）**：`checkPermissions` 读系统权限状态在 `permissionLock` 之外；一个很慢的旧读线程可能在较新的核对之后写回过时的“已授权”标志，让之后的一次撤销被漏报一批或重复通知一次（要三步交错，下一批读取或下一次状态转换就自愈）。修法是把两次读放进锁里并加一个双线程测试；这一票没做，记在这里。
18. **H2 提交信息里有一句不准确的话**（“同一时刻被界面线程与读线程发现，最坏会通知协调器两次”）：边沿标志是共享的，只通知一次。提交已经推送，没有改写历史，以本 README 为准。
19. **Android 的路由回调线程**：`AndroidAudioSystem` 仍在主线程 Looper 上投递路由回调（JVM 上测不了）；改成专用 Handler 线程是真机验证之后的后续工作（评审 B1 没有改这一点）。

## 7. 合同缺口与建议（没有改 `contracts/`，只记录）

- **G1：缺“蓝牙连接权限被拒”的错误码。** `contracts/schemas/common.schema.json` 的 `error_code` 枚举里有 `PERMISSION_MIC_DENIED`，没有蓝牙连接权限对应的码。建议新增 `PERMISSION_BLUETOOTH_DENIED`（现在沿用 `INPUT_DEVICE_MISSING`）。
- **G2：缺“存储写满”的结束原因。** 同一文件的 `end_reason` 枚举里被截断的只有 `route_lost`、`system_interruption`、`permission_revoked`、`process_terminated`。建议新增 `storage_exhausted`（计入被截断），或者在采集结果里加一个可选的“打断错误码”字段；现在记成 `system_interruption`，界面从本机快照里的 `STORAGE_EXHAUSTED` 得知原因，Record 本身不带这个信息，重启之后就丢了。
- **G3（次要）：找回的 Record 没法声明“采集时长是下限”。** 建议采集结果里加一个标志（例如 `capture_duration_is_lower_bound`），否则下游把它当精确值。评审 M1 之后同样适用于“登记前被杀而恢复出来的没有语音的 Record”：原来的结束原因看不出来，一律 `partial + process_terminated`。

## 8. 测试的牙齿（变异验证）与 TDD 的诚实说明

**做法**：对每条要守住的规则，故意破坏一处生产代码，跑对应的测试，确认红，然后还原（逐条还原并核对 `git status` 干净）。下表是实际跑的结果，每条的原文与改成的文本在表后的折叠块里，可以手工重做。运行时先取共享 Gradle 锁。

| # | 破坏的规则 | 变异（改了什么） | 红了的测试（最多列 2 条，名字截短） | 结果 |
|---|---|---|---|---|
| C1 | 路由丢失时分析窗里攒着的采样不能保留（它跨在变化点上，不知道属于哪一边） | 路由丢失时分析窗里的尾巴改为保留（`Tail.DROP` → `Tail.KEEP`） | `InterruptionTest` › 路由变化：只封存已确认的整窗音频，跨越变化的那一点尾巴丢弃，并记成 route_…<br>`InterruptionTest` › 耳机断开（系统说输入没了）：同样立刻关闸，截断并保留已确认的部分，原因写在快照里 | 被杀死（红） |
| C2 | 路由 epoch 对不上的帧不能写进录音（歧义帧丢弃） | 录音中不再核对帧的路由 epoch（`routeEpoch != verifiedRouteEpoch` 的守卫改成永假） | `InterruptionTest` › 路由其实已经变了、路由事件还没到：epoch 对不上的帧一个采样都不写，事件到了… | 被杀死（红） |
| C3 | 被截断的录音状态必须是 PARTIAL | 被截断的录音也标 `COMPLETE` | `InterruptionTest` › 路由变化：只封存已确认的整窗音频，跨越变化的那一点尾巴丢弃，并记成 route_…<br>`InterruptionTest` › 读线程发现的权限撤销通知迟到：旧会话的通知不能结束新的录音，也不能把待命变成受阻…<br>…共 9 条 | 被杀死（红） |
| C4 | 权限被撤销的结束原因必须是 permission_revoked | 权限撤销的结束原因写成 `system_interruption` | `InterruptionScenarioReplayTest` › 回放每一个与中断有关的场景：结束原因与合同一致，永不自己续录，进程恢复之后是 D…<br>`InterruptionTest` › 读线程发现的权限撤销通知迟到：旧会话的通知不能结束新的录音，也不能把待命变成受阻…<br>…共 3 条 | 被杀死（红） |
| C5 | 打断之后停止采集、释放麦克风（恰好一次） | 停止录音时不释放采集（麦克风不关） | `InterruptionTest` › 路由变化：只封存已确认的整窗音频，跨越变化的那一点尾巴丢弃，并记成 route_…<br>`InterruptionTest` › 存储适配器抛出不是 IOException 的异常：同样安全收场，不会卡在录音中…<br>…共 4 条 | 被杀死（红） |
| C6 | 任何系统打断之后都不自动重新开始 | 被打断的录音一封存完就自己重新开始（先开控制模式再 `START`） | `InterruptionTest` › 路由变化：只封存已确认的整窗音频，跨越变化的那一点尾巴丢弃，并记成 route_…<br>`InterruptionTest` › 读线程发现的权限撤销通知迟到：旧会话的通知不能结束新的录音，也不能把待命变成受阻…<br>…共 8 条 | 被杀死（红） |
| C7 | 写入遇到 IOException（存储写满）必须结束录音 | 写入遇到 `IOException`（存储写满）时只返回、不收场 | `InterruptionTest` › 每一种中断：关闸封存已确认的部分，回到待命；把所有可能唤醒它的东西都递一遍也不会…<br>`InterruptionEndToEndTest` › 磁盘在任意位置写满：协调器都安全收场，已写下的音频一个采样都不丢，下次启动不重复… | 被杀死（红） |
| C8 | 封存失败不能删掉已写下的分段（要留给下次启动找回） | 封存失败时调 `writer.abort()`（删掉分段）而不是 `release()` | `CaptureResultTest` › 封存失败：通知录音没能封存并回到待命，已写下的部分留在磁盘上给恢复流程，不删除，…<br>`InterruptionEndToEndTest` › 磁盘在任意位置写满：协调器都安全收场，已写下的音频一个采样都不丢，下次启动不重复…<br>…共 3 条 | 被杀死（红） |
| S1 | 恢复时每个分段都要校验 SHA-256，不对的不收 | 恢复时不再校验分段 SHA-256 | `RecordingRecoveryTest` › 第一个分段不可信、交出的 Record 登记之前进程又被杀：再恢复得到同一个没有…<br>`RecordingRecoveryTest` › 数据被改了一个字节：这一段的哈希对不上，它和之后的丢弃，前面的保留<br>…共 3 条 | 被杀死（红） |
| S2 | 恢复时最后一个半截分段要截回有效边界 | 恢复时不把半截尾巴截回有效边界 | `RecordingRecoveryTest` › 在任意字节处被杀：恢复永远不抛异常；找回的音频逐字节等于当初写下的；多写的字节不…<br>`RecordingRecoveryTest` › 进程被杀：已提交的分段全部找回，哈希逐段对得上，没提交的尾巴被截掉，音频是完整可…<br>…共 4 条 | 被杀死（红） |
| S3 | 先 fsync 数据，再写日志行 | 写日志行之前不 fsync 数据文件 | `RecordingRecoveryTest` › 写入顺序：数据先 fsync、才写这一段的日志行并 fsync；下一段的数据在日… | 被杀死（红） |
| S4 | 日志行写完要 fsync | 日志行写完不 fsync | `RecordingRecoveryTest` › begin 一返回，恢复所需的一切（会话、开始时间、路由证据）已经落盘，早于任何…<br>`RecordingRecoveryTest` › 写入顺序：数据先 fsync、才写这一段的日志行并 fsync；下一段的数据在日… | 被杀死（红） |
| R1 | 找回的录音结束原因必须是 process_terminated | 找回的录音结束原因写成 `system_interruption` | `OrphanRecoveryTest` › 恢复出来的录音一律是 partial 加 process_terminated，…<br>`OrphanRecoveryTest` › 一段验证过的音频也没有：仍然是一个 Record，没有音频、没有分段，采集时长为…<br>…共 5 条 | 被杀死（红） |
| R2 | 找回的录音必须是 partial（不能冒充完整） | 找回的录音标 `COMPLETE` | `OrphanRecoveryTest` › 同一个录音每次恢复得到同样的结果，chunk 标识不随机：恢复可以重复<br>`OrphanRecoveryTest` › 重复运行不会产生重复的 Record，也不会重复转录<br>…共 10 条 | 被杀死（红） |
| R3 | 找回的录音要交给 Record 管线登记并转录 | 不把找回的录音交给 Record 管线 | `OrphanRecoveryTest` › 重复运行不会产生重复的 Record，也不会重复转录<br>`OrphanRecoveryTest` › 找回的录音和别的录音走同一条路：登记成 partial Record，有音频的交…<br>…共 5 条 | 被杀死（红） |
| R4 | 启动恢复失败不能让应用起不来（异常要被包住） | 启动恢复抛出的异常不再包住 | `StartupRecoveryTest` › 存储读不出来：不抛出、不让应用起不来，日志只记失败这个事实而不带路径与标识；下次…<br>`StartupRecoveryTest` › 登记到一半失败（Record 库出了错）：不抛出；已登记的下次不会重复，没登记的… | 被杀死（红） |
| R5 | 启动恢复里取消照常传出去 | 启动恢复里协程取消被当成“恢复失败”吞掉 | `StartupRecoveryTest` › 协程被取消时取消照常传出去，不被当成恢复失败吞掉 | 被杀死（红） |
| F1 | DISARMED 时不能起前台服务（重启后不自动起服务） | `DISARMED` 也算“需要前台服务” | `RestartRecoveryTest` › 磁盘上没有孤儿：重启后什么都没有，采集同样是 DISARMED<br>`RestartRecoveryTest` › 上一个进程被杀、磁盘上有孤儿：重启后录音被找回成被截断的 Record 并转录，…<br>…共 5 条 | 被杀死（红） |
| A1 | 录音中每读完一批要核对权限 | 录音中每读完一批不再核对权限 | `BluetoothCaptureAdapterTest` › 回到前台时已经发现了撤销：读线程之后每一批都不递送，也不重复通知<br>`BluetoothCaptureAdapterTest` › 录音中麦克风权限被撤销：撤销时读到的那一批不递送，通知一次，协调器释放之后读线程…<br>…共 4 条 | 被杀死（红） |
| A2 | 读失败的原因是权限时不能报成输入路由变了 | 权限被撤销后读失败，仍报“输入路由变了” | `BluetoothCaptureAdapterTest` › 权限被撤销之后读失败：原因是权限而不是路由，只通知权限撤销，不再另报“输入路由变… | 被杀死（红） |
| A3 | 权限撤销只在“有 → 没有”的那一刻通知一次 | 缺权限期间每次核对都重复通知（不只“有 → 没有”那一刻） | `BluetoothCaptureAdapterTest` › 回到前台时已经发现了撤销：读线程之后每一批都不递送，也不重复通知<br>`BluetoothCaptureAdapterTest` › 麦克风权限在运行期被撤销：回到前台时核对一次，通知一次，之后不重复<br>…共 3 条 | 被杀死（红） |
| U1 | 首页要带出这次打断的原因 | 首页不再带出这次打断的原因 | `HomeFlowTest` › 录音被打断：首页说清原因，已确认的部分成为被截断的 Record 并照常转录，按…<br>`HomeViewModelTest` › 录音被打断：首页如实带出结束原因与错误码（同一个错误码不能代替结束原因），按钮回… | 被杀死（红） |
| U2 | 被截断的 Record 在列表里要标出来 | 列表不再标出被截断的 Record | `HomeFlowTest` › 录音被打断：首页说清原因，已确认的部分成为被截断的 Record 并照常转录，按…<br>`HomeViewModelTest` › 被截断的 Record 在列表里标出结束原因；完整的、用户停止的没有标记 | 被杀死（红） |
| U3 | 打断提示要说明不会自动继续 | 打断提示里去掉“不会自动继续” | `InterruptionTextTest` › 每一种打断都有说明：录音已停止、已录的部分已保存、不会自动继续 | 被杀死（红） |
| P1 | 回放测试漏掉一个相关场景时必须失败 | （测试自己）回放循环少回放一个场景 | `InterruptionScenarioReplayTest` › 回放每一个与中断有关的场景：结束原因与合同一致，永不自己续录，进程恢复之后是 D… | 被杀死（红） |
| C2b | 路由 epoch 对不上的帧不能写进录音（歧义帧丢弃）——整个 :capture:test | 同 C2，整个 `:capture:test` | `CaptureStalenessTest` › 录音中 epoch 不符的帧被丢弃，不够新的 epoch 被拒绝，已确认的句柄不…<br>`InterruptionTest` › 路由其实已经变了、路由事件还没到：epoch 对不上的帧一个采样都不写，事件到了…<br>…共 3 条 | 被杀死（红） |
| B1a | 路由通知本身使已有证据作废：耳机断开又恢复、通知被压到恢复之后才递送时，设备看上去没变也要报路由变了（评审 B1） | 通知不再使证据作废（`invalidates` 恒为 `false`，回到“设备没变就不报”） | `BluetoothCaptureAdapterTest` › route notifications delayed past a heads…<br>`BluetoothCaptureAdapterTest` › a second route notification before any f…<br>…共 4 条 | 被杀死（红） |
| B1b | 录音刚启动时平台的第一条初始路由通知只确认、不打断（否则每次录音都被它自己打断）（评审 B1） | 第一条通知也使证据作废（`invalidates = fromNotification`） | `BluetoothCaptureAdapterTest` › the first route notification before any …<br>`BluetoothCaptureAdapterTest` › a second route notification before any f… | 被杀死（红） |
| B2a | 封存必须等最后一行分段日志落盘；写不进去就不封存、不留 `.wav`（评审 B2） | 封存时吞掉日志失败（`commit()` → `commitIfPossible()`） | `InterruptionEndToEndTest` › 磁盘在任意位置写满：协调器都安全收场，已写下的音频一个采样都不丢，下次启动不重复…<br>`InterruptionEndToEndTest` › audio fits but the last segment journal …<br>…共 4 条 | 被杀死（红） |
| H1a | 升级挪了录音目录之后，库里登记的音频位置要跟着改（评审 H1） | 位置改写什么也不做（`return relocate(from, to)` → `return 0`） | `RecordingsMoveUpgradeTest` › 升级被杀在“目录挪完、库还没改”之后：下次启动补改库，位置指向真文件<br>`RecordingsMoveUpgradeTest` › 每次启动都做：第二次启动什么也不再改，全新安装（什么都没有）也不出错<br>…共 4 条 | 被杀死（红） |
| H1b | 老目录还在时不改库：说明目录没挪成，登记的位置仍指着有文件的地方（评审 H1） | 去掉“老目录还在就不改”的守卫 | `RecordingsMoveUpgradeTest` › 目录挪不动（改名失败）：不改库里的位置，文件还在老位置，照样转录<br>`RecordingsMoveUpgradeTest` › 新老两处都有目录：place 不覆盖也不动老目录，库里的位置同样原样保留 | 被杀死（红） |
| H2a | 读线程发现的权限撤销只属于它那次录音：旧录音的迟到通知不能打断新录音（评审 H2） | 会话里的权限通知不带会话标签（发成全局的 `PermissionRevoked`） | `InterruptionTest` › 读线程发现的权限撤销通知迟到：旧会话的通知不能结束新的录音，也不能把待命变成受阻…<br>`PermissionRevocationRaceTest` › a revocation noticed during the first re… | 被杀死（红） |
| H2b | 已经结束的采集不能再认领权限“有 → 没有”的那一次边沿（评审 H2） | 去掉“采集已关闭就不核对”的守卫 | `BluetoothCaptureAdapterTest` › 已经结束的采集的读线程不认领权限撤销：后来开始的那次采集照样收到通知 | 被杀死（红） |
| H2c | 读线程发现的权限撤销走本次录音的 sink，不走全局处理器（评审 H2） | 读线程发现权限撤销时改走全局处理器 | `BluetoothCaptureAdapterTest` › 录音中麦克风权限被撤销：撤销时读到的那一批不递送，通知一次，协调器释放之后读线程…<br>`BluetoothCaptureAdapterTest` › 权限被撤销之后读失败：原因是权限而不是路由，只通知权限撤销，不再另报“输入路由变…<br>…共 5 条 | 被杀死（红） |
| M3 | 系统不给音频焦点就不开始录音（评审 M3） | 没拿到焦点时用一个空订阅继续（`?: Subscription { }`） | `BluetoothCaptureAdapterTest` › 系统不给音频焦点：不开始录音，说明是被占用，不打开任何录音，路由请求只撤销一次<br>`RouteBoundaryTest` › a denied audio focus blocks the start wi… | 被杀死（红） |
| M1a | 一个采样都没写下的录音：恢复记录留到 Record 登记之后（评审 M1） | `finishWithoutAudio()` 的行为等于 `abort()`（连日志一起删） | `NoSpeechRecordDurabilityTest` › 正常登记：库里一个 Record 原样保留，下次启动不重复登记也不改写它，只清理…<br>`NoSpeechRecordDurabilityTest` › 库写失败：通知 Record 没能保存，恢复记录仍在；下次启动登记恰好一个<br>…共 6 条 | 被杀死（红） |
| M1b | 协调器对“没有语音”的录音不能 abort（评审 M1） | 协调器的“没有语音”分支改调 `writer.abort()` | `CaptureResultTest` › 路由确认了却一个采样也没收到：不留空文件，仍然交出一个没有音频的结果<br>`NoSpeechRecordDurabilityTest` › 正常登记：库里一个 Record 原样保留，下次启动不重复登记也不改写它，只清理…<br>…共 4 条 | 被杀死（红） |
| M1c | 存储写不下导致一个采样都没写成时才 abort：通知丢失、不留 Record 也不留残渣（评审 M1） | 协调器的“丢失”分支改调 `finishWithoutAudio()` | `CaptureResultTest` › first speech append failure reports stor…<br>`InterruptionEndToEndTest` › 磁盘在任意位置写满：协调器都安全收场，已写下的音频一个采样都不丢，下次启动不重复… | 被杀死（红） |
| M1d | 没有语音的录音收场之后，本进程的恢复扫描不能把它当孤儿（评审 M1） | `finishWithoutAudio()` 不记进 `handedOver` | `RecordingRecoveryTest` › 一个采样都没写下就收场：不留音频文件，日志留到登记；登记之前被杀，下次启动找回一… | 被杀死（红） |
| M1e | 写过采样的录音不能当“没有音频”处理（那会删掉语音）（评审 M1） | 去掉 `check(dataBytes == 0L)` | `RecordingRecoveryTest` › 写过采样的录音不能当作没有音频：抛出，一个字节都不删，之后照常封存 | 被杀死（红） |
| M2a | 恢复在 Record 登记之前不删任何东西（评审 M2） | 没有可信字节时恢复又把 `.part` 空壳删掉 | `RecordingRecoveryTest` › 第一个分段不可信、交出的 Record 登记之前进程又被杀：再恢复得到同一个没有…<br>`RecordingRecoveryTest` › 第一个分段就不可信：没有任何音频留下，Record 仍然被找回（没有音频）；不可… | 被杀死（红） |
| M2b | 登记之后的那次启动要清掉没有可信字节的 `.part` 空壳（评审 M2） | “已登记”分支不再删 `.part` | `RecordingRecoveryTest` › 在任意字节处被杀：恢复永远不抛异常；找回的音频逐字节等于当初写下的；多写的字节不…<br>`RecordingRecoveryTest` › 第一个分段不可信、交出的 Record 登记之前进程又被杀：再恢复得到同一个没有… | 被杀死（红） |

**评审修复之后的复核**（2026-11-20）：把上面原有的 26 条（含 C2b）全部重跑了一遍，另外跑了评审修复新增的 16 条，**42 条全部被预期的测试杀死，没有一条存活、没有一条编译失败、没有一条只被不相干的测试杀死**；每条跑完都把文件还原并逐字节比对，工作树保持干净。复核时有三点与第一次不同，如实记下：

- **A1–A3 的原文变了**：评审 H2 把 `checkPermissions()` 改成了 `checkPermissions(this)`，`justRevoked` 那一段多了一层分支。变异的意图不变，原文与改成的文本已按现在的代码改写（见折叠块）。
- **R1 的命令改成按模块分组**：原来把 `app.auri.platform.storage.RestartRecoveryTest` 挂在 `:capture:test --tests` 后面，Gradle 在 `:capture` 里找不到它，实际没有跑到；这次改成 `:capture:test … :platform:testDebugUnitTest --tests …RestartRecoveryTest`，R1 因此多了 `RestartRecoveryTest` 的红（共 5 条）。其余各条的红的数量比第一次多，是因为这期间新增了测试（例如 S1 现在也被评审 M2 的测试杀死）。
- **新增的 16 条第一次跑就被杀死**，来源不同，如实分开写：B1a、B1b、B2a 是这次复核时才第一次跑（B1、B2 修复时没有跑变异）；H1a、H1b、H2a–H2c、M3 修复时跑过，这次用同一个驱动重跑，红的数量一致（4、2、2、1、5、2）；M1a–M1e、M2a、M2b 是修复时在同一棵代码树上跑的，红的数量取自当时的日志，这次复核没有重跑。

**没有被变异覆盖的地方**（如实列出）：评审 B1 里“判断一批帧与递送它在同一把锁里”是个竞争，JVM 上没有确定性的测试能把它撑开；评审 M2 里“先删 `.part`、最后删日志”的顺序，需要在两次 `File.delete` 之间注入被杀，`StorageIo` 没有这条缝；评审 H2 的残余竞争（第 6 节假设 17）没有修也没有测。

**第一轮没一次就杀死的几条与处理**（如实记录）：

- **C2**（录音中不核对帧的路由 epoch）第一轮**存活**：这一轮只跑了与中断直接相关的测试组（`InterruptionTest`、`InterruptionScenarioReplayTest`）。原因是中断测试里路由变化的事件总是先于新 epoch 的帧到达，帧到时状态早已离开 `RECORDING`，“事件比帧晚到”这个竞争没有被中断测试单独测过——这条守卫只被别的票的测试间接覆盖。处理：先补一个协调器层的测试（`InterruptionTest`：路由其实已经变了、路由事件还没到），再重跑，C2 被它杀死；C2b 在整个 `:capture:test`（当时 368 个测试）下有 3 个红：Ticket 16 的 `CaptureStalenessTest`、Ticket 18 的 `SilenceFilteringTest`，以及这个新测试。
- **C8**（封存失败时 `abort()` 代替 `release()`）第一轮**存活**，但那是我的变异清单写错了：把平台模块里的 `InterruptionEndToEndTest` 挂在 `:capture:test --tests` 后面，Gradle 在 `:capture` 里找不到这个类，实际根本没跑它，也没跑专门守这条规则的 `CaptureResultTest`。改成 `:capture:test --tests …CaptureResultTest :platform:testDebugUnitTest --tests …InterruptionEndToEndTest` 之后重跑，C8 被两者同时杀死（真实存储里，封存失败后 `abort()` 会真的删掉分段文件，所以端到端扫描也看得见）。所以第一轮的“存活”不是证据，补跑的才是。
- **C7** 同样因为参数写错，第一轮只被协调器层的测试杀死，端到端扫描没有跑；修正后重跑，两者都红。
- 其余 22 条（C1、C3–C6、S1–S4、R1–R5、F1、A1–A3、U1–U3、P1）第一轮就被预期的测试杀死；没有一条编译失败，也没有一条只被不相干的测试杀死。

<details><summary>每条变异的原文、改成的文本与运行命令</summary>

**C1** · `capture/src/main/kotlin/app/auri/capture/coordinator/CaptureCoordinator.kt`

原文：
```kotlin
rule.event == CaptureEvent.ROUTE_LOST -> Tail.DROP
```
改成：
```kotlin
rule.event == CaptureEvent.ROUTE_LOST -> Tail.KEEP
```
运行：`./gradlew :capture:test --tests app.auri.capture.coordinator.InterruptionTest --tests app.auri.capture.coordinator.InterruptionScenarioReplayTest --continue`

**C2** · `capture/src/main/kotlin/app/auri/capture/coordinator/CaptureCoordinator.kt`

原文：
```kotlin
if (message.frames.routeEpoch != handle.verifiedRouteEpoch) {
```
改成：
```kotlin
if (false) {
```
运行：`./gradlew :capture:test --tests app.auri.capture.coordinator.InterruptionTest --tests app.auri.capture.coordinator.InterruptionScenarioReplayTest --continue`

**C3** · `capture/src/main/kotlin/app/auri/capture/coordinator/CaptureCoordinator.kt`

原文：
```kotlin
status = if (endReason.truncated) CaptureStatus.PARTIAL else CaptureStatus.COMPLETE,
```
改成：
```kotlin
status = CaptureStatus.COMPLETE,
```
运行：`./gradlew :capture:test --tests app.auri.capture.coordinator.InterruptionTest --tests app.auri.capture.coordinator.InterruptionScenarioReplayTest --continue`

**C4** · `capture/src/main/kotlin/app/auri/capture/coordinator/CaptureCoordinator.kt`

原文：
```kotlin
RECORDING -> storageFailedDuringStop = stopRecording(EndReason.PERMISSION_REVOKED, interrupted = errorCode)
```
改成：
```kotlin
RECORDING -> storageFailedDuringStop = stopRecording(EndReason.SYSTEM_INTERRUPTION, interrupted = errorCode)
```
运行：`./gradlew :capture:test --tests app.auri.capture.coordinator.InterruptionTest --tests app.auri.capture.coordinator.InterruptionScenarioReplayTest --continue`

**C5** · `capture/src/main/kotlin/app/auri/capture/coordinator/CaptureCoordinator.kt`

原文：
```kotlin
        durationWarning = false
        releaseCapture(current.operationId)
        val handle = current.handle
```
改成：
```kotlin
        durationWarning = false
        val handle = current.handle
```
运行：`./gradlew :capture:test --tests app.auri.capture.coordinator.InterruptionTest --tests app.auri.capture.coordinator.InterruptionScenarioReplayTest --continue`

**C6** · `capture/src/main/kotlin/app/auri/capture/coordinator/CaptureCoordinator.kt`

原文：
```kotlin
                if (failure != null) Context(errorCode = ErrorCode.STORAGE_EXHAUSTED, cause = failure.cause) else Context(),
            )
```
改成：
```kotlin
                if (failure != null) Context(errorCode = ErrorCode.STORAGE_EXHAUSTED, cause = failure.cause) else Context(),
            )
            if (interruptionReason != null) {
                dispatch(CaptureEvent.ENABLE_CONTROL_MODE, Context(manual = true))
                dispatch(CaptureEvent.START, Context())
            }
```
运行：`./gradlew :capture:test --tests app.auri.capture.coordinator.InterruptionTest --tests app.auri.capture.coordinator.InterruptionScenarioReplayTest --continue`

**C7** · `capture/src/main/kotlin/app/auri/capture/coordinator/CaptureCoordinator.kt`

原文：
```kotlin
                    current.storageFailed = true
                    dispatch(CaptureEvent.SYSTEM_INTERRUPTION, Context(errorCode = ErrorCode.STORAGE_EXHAUSTED, cause = Cause.STORAGE_FAILURE))
                    return
```
改成：
```kotlin
                    current.storageFailed = true
                    return
```
运行：`./gradlew :capture:test --tests app.auri.capture.coordinator.InterruptionTest --tests app.auri.capture.coordinator.InterruptionScenarioReplayTest :platform:testDebugUnitTest --tests app.auri.platform.storage.InterruptionEndToEndTest --continue`

**C8** · `capture/src/main/kotlin/app/auri/capture/coordinator/CaptureCoordinator.kt`

原文：
```kotlin
                try {
                    writer.release()
                } catch (cleanup: Exception) {
```
改成：
```kotlin
                try {
                    writer.abort()
                } catch (cleanup: Exception) {
```
运行：`./gradlew :capture:test --tests app.auri.capture.coordinator.CaptureResultTest :platform:testDebugUnitTest --tests app.auri.platform.storage.InterruptionEndToEndTest --continue`

**S1** · `platform/src/main/kotlin/app/auri/platform/storage/WavRecordingStore.kt`

原文：
```kotlin
if (MessageDigest.getInstance("SHA-256").digest(bytes).hex() != segment.sha256) break // 数据被改过
```
改成：
```kotlin
if (false) break // 数据被改过
```
运行：`./gradlew :platform:testDebugUnitTest --tests app.auri.platform.storage.RecordingRecoveryTest --tests app.auri.platform.storage.InterruptionEndToEndTest --continue`

**S2** · `platform/src/main/kotlin/app/auri/platform/storage/WavRecordingStore.kt`

原文：
```kotlin
if (file.length() != validLength) file.setLength(validLength) // 截回最后一个有效边界
```
改成：
```kotlin
// 变异：不截回
```
运行：`./gradlew :platform:testDebugUnitTest --tests app.auri.platform.storage.RecordingRecoveryTest --tests app.auri.platform.storage.InterruptionEndToEndTest --continue`

**S3** · `platform/src/main/kotlin/app/auri/platform/storage/WavRecordingStore.kt`

原文：
```kotlin
            if (segmentSamples == 0L) return
            io.sync(data, part.name)
```
改成：
```kotlin
            if (segmentSamples == 0L) return
```
运行：`./gradlew :platform:testDebugUnitTest --tests app.auri.platform.storage.RecordingRecoveryTest --tests app.auri.platform.storage.InterruptionEndToEndTest --continue`

**S4** · `platform/src/main/kotlin/app/auri/platform/storage/RecordingJournal.kt`

原文：
```kotlin
            io.sync(file, name)
```
改成：
```kotlin
// （整行删掉）
```
运行：`./gradlew :platform:testDebugUnitTest --tests app.auri.platform.storage.RecordingRecoveryTest --tests app.auri.platform.storage.InterruptionEndToEndTest --continue`

**R1** · `capture/src/main/kotlin/app/auri/capture/recovery/OrphanRecovery.kt`

原文：
```kotlin
endReason = EndReason.PROCESS_TERMINATED,
```
改成：
```kotlin
endReason = EndReason.SYSTEM_INTERRUPTION,
```
运行：`./gradlew :capture:test --tests app.auri.capture.recovery.OrphanRecoveryTest --tests app.auri.capture.recovery.StartupRecoveryTest :platform:testDebugUnitTest --tests app.auri.platform.storage.RestartRecoveryTest --continue`

**R2** · `capture/src/main/kotlin/app/auri/capture/recovery/OrphanRecovery.kt`

原文：
```kotlin
status = CaptureStatus.PARTIAL,
```
改成：
```kotlin
status = CaptureStatus.COMPLETE,
```
运行：`./gradlew :capture:test --tests app.auri.capture.recovery.OrphanRecoveryTest --tests app.auri.capture.recovery.StartupRecoveryTest --continue`

**R3** · `capture/src/main/kotlin/app/auri/capture/recovery/OrphanRecovery.kt`

原文：
```kotlin
            output.recordingSealed(sealed)
```
改成：
```kotlin
// （整行删掉）
```
运行：`./gradlew :capture:test --tests app.auri.capture.recovery.OrphanRecoveryTest --tests app.auri.capture.recovery.StartupRecoveryTest --continue`

**R4** · `capture/src/main/kotlin/app/auri/capture/recovery/StartupRecovery.kt`

原文：
```kotlin
    } catch (e: Exception) {
        log("orphan recovery failed")
        null
    }
```
改成：
```kotlin
    } catch (e: Exception) {
        throw e
    }
```
运行：`./gradlew :capture:test --tests app.auri.capture.recovery.StartupRecoveryTest --continue`

**R5** · `capture/src/main/kotlin/app/auri/capture/recovery/StartupRecovery.kt`

原文：
```kotlin
        throw e // 应用在恢复中途退出：取消照常传出去，不当成恢复失败
```
改成：
```kotlin
        log("orphan recovery failed")
        null
```
运行：`./gradlew :capture:test --tests app.auri.capture.recovery.StartupRecoveryTest --continue`

**F1** · `platform/src/main/kotlin/app/auri/platform/service/ForegroundServiceBinder.kt`

原文：
```kotlin
setOf(CaptureState.ROUTING, CaptureState.RECORDING, CaptureState.FINALIZING)
```
改成：
```kotlin
setOf(CaptureState.DISARMED, CaptureState.ROUTING, CaptureState.RECORDING, CaptureState.FINALIZING)
```
运行：`./gradlew :platform:testDebugUnitTest --tests app.auri.platform.storage.RestartRecoveryTest --tests app.auri.platform.service.ForegroundServiceBinderTest --continue`

**A1** · `platform/src/main/kotlin/app/auri/platform/audio/BluetoothCaptureAdapter.kt`

原文：
```kotlin
                if (checkPermissions(this) != null) continue
```
改成：
```kotlin
// （整行删掉）
```
运行：`./gradlew :platform:testDebugUnitTest --tests app.auri.platform.audio.BluetoothCaptureAdapterTest --continue`

**A2** · `platform/src/main/kotlin/app/auri/platform/audio/BluetoothCaptureAdapter.kt`

原文：
```kotlin
                if (checkPermissions(this) == null) sink.inputUnavailable(ErrorCode.INPUT_ROUTE_CHANGED)
```
改成：
```kotlin
                sink.inputUnavailable(ErrorCode.INPUT_ROUTE_CHANGED)
```
运行：`./gradlew :platform:testDebugUnitTest --tests app.auri.platform.audio.BluetoothCaptureAdapterTest --continue`

**A3** · `platform/src/main/kotlin/app/auri/platform/audio/BluetoothCaptureAdapter.kt`

原文：
```kotlin
        if (justRevoked && missing != null) {
```
改成：
```kotlin
        if (missing != null) {
```
运行：`./gradlew :platform:testDebugUnitTest --tests app.auri.platform.audio.BluetoothCaptureAdapterTest --continue`

**U1** · `app/src/main/kotlin/app/auri/android/home/HomeViewModel.kt`

原文：
```kotlin
interruption = snapshot.interruptionReason?.let { InterruptionFact(it, snapshot.interruption) },
```
改成：
```kotlin
interruption = null,
```
运行：`./gradlew :app:testDebugUnitTest --tests app.auri.android.home.HomeViewModelTest --tests app.auri.android.home.HomeFlowTest --continue`

**U2** · `app/src/main/kotlin/app/auri/android/home/HomeViewModel.kt`

原文：
```kotlin
truncatedBy = entry.endReason.takeIf { it.truncated },
```
改成：
```kotlin
truncatedBy = null,
```
运行：`./gradlew :app:testDebugUnitTest --tests app.auri.android.home.HomeViewModelTest --tests app.auri.android.home.HomeFlowTest --continue`

**U3** · `app/src/main/kotlin/app/auri/android/ui/HomeScreen.kt`

原文：
```kotlin
return "录音已停止：$cause。$saved；不会自动继续。"
```
改成：
```kotlin
return "录音已停止：$cause。$saved。"
```
运行：`./gradlew :app:testDebugUnitTest --tests app.auri.android.ui.InterruptionTextTest --continue`

**P1** · `capture/src/test/kotlin/app/auri/capture/coordinator/InterruptionScenarioReplayTest.kt`

原文：
```kotlin
for (scenario in relevant) {
```
改成：
```kotlin
for (scenario in relevant.drop(1)) {
```
运行：`./gradlew :capture:test --tests app.auri.capture.coordinator.InterruptionScenarioReplayTest --continue`

**C2b** · `capture/src/main/kotlin/app/auri/capture/coordinator/CaptureCoordinator.kt`

原文：
```kotlin
if (message.frames.routeEpoch != handle.verifiedRouteEpoch) {
```
改成：
```kotlin
if (false) {
```
运行：`./gradlew :capture:test --continue`

**B1a** · `platform/src/main/kotlin/app/auri/platform/audio/BluetoothCaptureAdapter.kt`

原文：
```kotlin
            val invalidates = fromNotification && (framesDelivered || notified)
```
改成：
```kotlin
            val invalidates = false
```
运行：`./gradlew :platform:testDebugUnitTest --tests app.auri.platform.audio.BluetoothCaptureAdapterTest --tests app.auri.platform.audio.RouteBoundaryTest --continue`

**B1b** · `platform/src/main/kotlin/app/auri/platform/audio/BluetoothCaptureAdapter.kt`

原文：
```kotlin
            val invalidates = fromNotification && (framesDelivered || notified)
```
改成：
```kotlin
            val invalidates = fromNotification
```
运行：`./gradlew :platform:testDebugUnitTest --tests app.auri.platform.audio.BluetoothCaptureAdapterTest --tests app.auri.platform.audio.RouteBoundaryTest --continue`

**B2a** · `platform/src/main/kotlin/app/auri/platform/storage/WavRecordingStore.kt`

原文：
```kotlin
            commit()
            try {
                data.setLength(HEADER_BYTES + dataBytes)
```
改成：
```kotlin
            commitIfPossible()
            try {
                data.setLength(HEADER_BYTES + dataBytes)
```
运行：`./gradlew :platform:testDebugUnitTest --tests app.auri.platform.storage.RecordingRecoveryTest --tests app.auri.platform.storage.InterruptionEndToEndTest --continue`

**H1a** · `platform/src/main/kotlin/app/auri/platform/library/AudioRelocation.kt`

原文：
```kotlin
    return relocate(from, to)
```
改成：
```kotlin
    return 0
```
运行：`./gradlew :platform:testDebugUnitTest --tests app.auri.platform.library.RelocateAudioSqlTest --tests app.auri.platform.library.RecordingsMoveUpgradeTest --continue`

**H1b** · `platform/src/main/kotlin/app/auri/platform/library/AudioRelocation.kt`

原文：
```kotlin
    if (legacyDir.exists()) return 0
```
改成：
```kotlin
// （整行删掉）
```
运行：`./gradlew :platform:testDebugUnitTest --tests app.auri.platform.library.RelocateAudioSqlTest --tests app.auri.platform.library.RecordingsMoveUpgradeTest --continue`

**H2a** · `capture/src/main/kotlin/app/auri/capture/coordinator/CaptureCoordinator.kt`

原文：
```kotlin
        override fun permissionRevoked(reason: ErrorCode) = post(Message.SessionPermissionRevoked(tag, reason))
```
改成：
```kotlin
        override fun permissionRevoked(reason: ErrorCode) = post(Message.PermissionRevoked(reason))
```
运行：`./gradlew :capture:test --tests app.auri.capture.coordinator.InterruptionTest :platform:testDebugUnitTest --tests app.auri.platform.audio.PermissionRevocationRaceTest --continue`

**H2b** · `platform/src/main/kotlin/app/auri/platform/audio/BluetoothCaptureAdapter.kt`

原文：
```kotlin
            if (observer != null && observer.isClosed()) return@synchronized false
```
改成：
```kotlin
// （整行删掉）
```
运行：`./gradlew :platform:testDebugUnitTest --tests app.auri.platform.audio.BluetoothCaptureAdapterTest --tests app.auri.platform.audio.PermissionRevocationRaceTest --continue`

**H2c** · `platform/src/main/kotlin/app/auri/platform/audio/BluetoothCaptureAdapter.kt`

原文：
```kotlin
            if (observer == null) permissionHandlers.forEach { it(missing) } else observer.sink.permissionRevoked(missing)
```
改成：
```kotlin
            permissionHandlers.forEach { it(missing) }
```
运行：`./gradlew :platform:testDebugUnitTest --tests app.auri.platform.audio.BluetoothCaptureAdapterTest --tests app.auri.platform.audio.PermissionRevocationRaceTest --continue`

**M3** · `platform/src/main/kotlin/app/auri/platform/audio/BluetoothCaptureAdapter.kt`

原文：
```kotlin
audio.requestAudioFocus { sink.interrupted() } ?: return capture.fail(ErrorCode.AUDIO_INTERRUPTED)
```
改成：
```kotlin
audio.requestAudioFocus { sink.interrupted() } ?: Subscription { }
```
运行：`./gradlew :platform:testDebugUnitTest --tests app.auri.platform.audio.BluetoothCaptureAdapterTest --tests app.auri.platform.audio.RouteBoundaryTest --continue`

**M1a** · `platform/src/main/kotlin/app/auri/platform/storage/WavRecordingStore.kt`

原文：
```kotlin
            check(dataBytes == 0L) { "写过采样的录音不能当作没有音频" }
            finished = true
            closeQuietly(data)
            journal.close()
            part.delete() // 空的音频壳里什么都没有；恢复记录（日志）才是这个 Record 存在过的凭据，留到登记之后的那次启动（评审 M1）
            synchronized(writing) {
                writing -= recordId
                handedOver += recordId // 和封存好的录音一样：这个进程里的扫描不把它当孤儿
            }
```
改成：
```kotlin
            abort()
```
运行：`./gradlew :platform:testDebugUnitTest --tests app.auri.platform.storage.RecordingRecoveryTest --tests app.auri.platform.storage.NoSpeechRecordDurabilityTest --continue`

**M1b** · `capture/src/main/kotlin/app/auri/capture/coordinator/CaptureCoordinator.kt`

原文：
```kotlin
                        writer.finishWithoutAudio()
                        SealOutcome.Done(
```
改成：
```kotlin
                        writer.abort()
                        SealOutcome.Done(
```
运行：`./gradlew :capture:test --tests app.auri.capture.coordinator.CaptureResultTest :platform:testDebugUnitTest --tests app.auri.platform.storage.NoSpeechRecordDurabilityTest --continue`

**M1c** · `capture/src/main/kotlin/app/auri/capture/coordinator/CaptureCoordinator.kt`

原文：
```kotlin
                        writer.abort()
                        SealOutcome.Failed(recordId, Cause.STORAGE_FAILURE)
                    } else {
```
改成：
```kotlin
                        writer.finishWithoutAudio()
                        SealOutcome.Failed(recordId, Cause.STORAGE_FAILURE)
                    } else {
```
运行：`./gradlew :capture:test --tests app.auri.capture.coordinator.CaptureResultTest :platform:testDebugUnitTest --tests app.auri.platform.storage.InterruptionEndToEndTest --continue`

**M1d** · `platform/src/main/kotlin/app/auri/platform/storage/WavRecordingStore.kt`

原文：
```kotlin
                handedOver += recordId // 和封存好的录音一样：这个进程里的扫描不把它当孤儿
```
改成：
```kotlin
                // (mutated) 不记进 handedOver
```
运行：`./gradlew :platform:testDebugUnitTest --tests app.auri.platform.storage.RecordingRecoveryTest --continue`

**M1e** · `platform/src/main/kotlin/app/auri/platform/storage/WavRecordingStore.kt`

原文：
```kotlin
            check(dataBytes == 0L) { "写过采样的录音不能当作没有音频" }
```
改成：
```kotlin
// （整行删掉）
```
运行：`./gradlew :platform:testDebugUnitTest --tests app.auri.platform.storage.RecordingRecoveryTest --continue`

**M2a** · `platform/src/main/kotlin/app/auri/platform/storage/WavRecordingStore.kt`

原文：
```kotlin
                return Outcome.Recovered(recording(begin, emptyList(), null, dropped))
            }
            if (file.length() != validLength)
```
改成：
```kotlin
                dataFile.delete()
                return Outcome.Recovered(recording(begin, emptyList(), null, dropped))
            }
            if (file.length() != validLength)
```
运行：`./gradlew :platform:testDebugUnitTest --tests app.auri.platform.storage.RecordingRecoveryTest --continue`

**M2b** · `platform/src/main/kotlin/app/auri/platform/storage/WavRecordingStore.kt`

原文：
```kotlin
                if (!sealed.exists()) part.delete()
```
改成：
```kotlin
// （整行删掉）
```
运行：`./gradlew :platform:testDebugUnitTest --tests app.auri.platform.storage.RecordingRecoveryTest --continue`

</details>

**TDD 的诚实说明**：

- 分段落盘与恢复的核心（`99853fe`、`1833120` 等上一轮、被中断的运行写的提交）：当时的测试与实现是同一次工作写出来的，我无法证明每个测试都先红过。这一轮没有信它们：先跑基线，补了“写满”“被杀”的端到端扫描，并用上面的变异（S1–S4、R1–R5）证明这些测试真的拦得住。
- 这一轮新增的部分都先看到红灯再实现：启动恢复（`StartupRecoveryTest` 5 条红）、重启保持 `DISARMED`（`RestartRecoveryTest` 3 条红）、权限读失败不误报路由（`BluetoothCaptureAdapterTest` 1 条红）、首页打断说明与截断标记（`InterruptionTextTest` 5 条、`HomeFlowTest` 1 条、`HomeViewModelTest` 2 条红）。
- 合并 Ticket 18 的评审修复后，写满扫描里“第一批都没写下”的情形变成“通知丢失”而不是无语音 Record，测试按这个**有意的新语义**调整（加了 `written == 0` 的分支与相应的收场方式），不是放宽断言。
- **独立评审之后的修复**（B1、B2、H2、M3、M1、M2）都是先写测试、看到红灯、记下红的原因，再改生产代码：B1 旧代码得到 `[route:1, frames:1, frames:1]`（通知之后的帧仍被接受）；B2 旧代码封存成功并登记一个没有分段记录的 `.wav`；H2 旧代码里迟到的通知把 ARMED 变成 BLOCKED、把 RECORDING 变回 ARMED；M3 旧代码在拒绝焦点时照样开始录音；M1 旧代码收场后磁盘上什么都不剩（恢复记录被删）；M2 旧代码第二次恢复得到“空列表”（`audio_missing`）。**H1 是例外**：生产代码先于新测试写出，红灯证据来自一个对照测试（旧行为：待转录的 Record 以 FAILED 收场、引擎从没被调用）与两处生产代码变异（4 条、2 条红），不是严格的先红后绿，在这里如实说明。
- 没有请 Codex 或其它 agent 复审（本任务规定只能由一个 agent 完成），用变异验证与逐项自查代替；这不等于外部复审。

## 9. 实测数字（E2：在这台 Mac 的 JVM 上量的，不是手机）

所有数字都来自这台 Mac 的 JVM（假磁盘 `FaultyDisk`），是 E2，**不是手机上的数据**。`InterruptionEndToEndTest` 运行时把它们打印出来（Gradle 测试报告 `apps/android/platform/build/test-results/testDebugUnitTest/TEST-app.auri.platform.storage.InterruptionEndToEndTest.xml` 的 `system-out` 里有 `MEASURED` 行）。

| 量 | 值 | 怎么量的 |
|---|---|---|
| 写满扫描 | 106 个写满位置 × 2（封存顺利 / 封存时改名失败）= 212 次，位置范围是一次完整录音占的磁盘空间（数据 + 日志）40,478 字节；**六种**收场方式全都走到过：开始就写不下；第一批都没写下就写满（通知丢失，不留 Record 也不留残渣）；写满后封存了已写下的部分；完整结束；封存没完成但分段留在磁盘上，下次启动找回；封存时最后一行日志写不下（评审 B2）：不留 `.wav`，下次启动找回已提交的分段 | 同一段录音（12 批 × 100 ms）反复跑，磁盘在不同字节处写满；每次都核对：协调器收场、采集恰好释放一次、没有自己重新开始、写下的采样一个不丢、重启后不重复也不遗漏 |
| 进程被杀扫描 | 45 个字节位置；找回的音频始终是写下过的音频的前缀、哈希对得上；**最坏丢失 3200 个采样 = 200 ms（16 kHz）**，上限 3360 个采样 | 测试里的提交间隔是 100 ms（1600 个采样）；上限 = 一个提交间隔 + 一次写入（1600）+ 一个分析窗（160） |
| 线上的丢失上限 | 约 17,760 个采样 ≈ **1.11 秒**（推导值，**没有测量**） | 生产提交间隔 1 秒（16,000 个采样）+ 1600 + 160 |

## 10. 本次运行结果

**运行对象**：分支 `ticket/19-interruptions-and-recovery`，代码提交 `b692485`（评审修复 M2，运行时工作树干净；之后只有本 README 与 `CLAUDE.md` 的文档改动）。已含 `origin/ticket/18-silence-and-limits`（`4162fa9`）与 `origin/integration/auri-v2`（`9867842`，合并点 `a6958af`）；收尾前 `git fetch origin`，没有新的上游提交，所以不需要再合并、重跑。运行日期 2026-11-20（本机时间约 16:18–16:38），机器：这台 Mac（共享；没有启动任何模拟器、AVD、Android Studio 或别的 IDE）。

每个 Gradle 任务都带 `--rerun`，并核对过测试报告 XML 的时间晚于本次运行的开始、数量与下表一致；所有 Gradle 命令用的是 `--no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2`，每条先取共享锁、单独运行。

| 命令（每条都在共享锁里单独运行） | 结果 | 内存读数（运行前 → 运行后：空闲 % / 交换区已用 MB） |
|---|---|---|
| `:capture:test` | 369 个，失败 0、错误 0、跳过 0（rc=0） | 58% / 2690 → 47% / 2642 |
| `:modelmgmt:testDebugUnitTest` | 84 个，失败 0、错误 0、跳过 0（rc=0） | 48% / 2642 → 45% / 2642 |
| `:platform:testDebugUnitTest` | 257 个，失败 0、错误 0、跳过 0（rc=0） | 45% / 2642 → 45% / 2642 |
| `:app:testDebugUnitTest` | 188 个，失败 0、错误 0、跳过 0（rc=0） | 44% / 2642 → 43% / 2642 |
| `:asr:testDebugUnitTest` | 112 个，失败 0、错误 0、跳过 0（rc=0） | 43% / 2642 → 48% / 2642 |
| `:sync:test` | 267 个，失败 0、错误 0、跳过 0（rc=0） | 47% / 2642 → 46% / 2642 |
| `:syncplatform:testDebugUnitTest` | 19 个，失败 0、错误 0、跳过 0（rc=0） | 46% / 2642 → 47% / 2642 |
| `:sync:realGatewayTest`（带 `NO_PROXY=localhost,127.0.0.1,::1`） | 12 个，失败 0、错误 0、跳过 0（rc=0）；结束后没有留下 `auri_gateway` 进程 | 47% / 2642 → 49% / 2642 |
| `:app:assembleDebug :app:assembleDebugAndroidTest :platform:assembleDebugAndroidTest :syncplatform:assembleDebugAndroidTest` | 构建成功（rc=0；204 个任务，22 个执行、182 个最新）：`app-debug.apk` 与 `app`、`platform`、`syncplatform` 的仪器测试 APK 都已生成（含评审 H1 新增的设备端用例），只编译、没有运行 | 49% / 2642 → 42% / 2642 |
| `NO_PROXY=… .venv/bin/pytest tests/contract tests/integration tests/security` | 496 passed in 239.82s (0:03:59)（rc=0）；网关孤儿进程：运行前 0，运行后 0 | 44% / 2626 → 47% / 2626 |
| `NO_PROXY=… .venv/bin/pytest execution/acceptance/android-silence execution/acceptance/android-capture execution/acceptance/android-control execution/acceptance/android-models execution/acceptance/asr-android`（**第一次，作废**） | 279 passed、6 failed、16 errors（rc=1）：22 个失败**全部**是 `JAVA_HOME 未设置`，原因见下面“第一次作废”；不是代码问题，但按规定如实列出 | 42% / 2642 → 42% / 2626 |
| `NO_PROXY=… .venv/bin/pytest execution/acceptance/android-sync`（**第一次，作废**） | 811 passed、1 failed（rc=1）：失败的是 `test_real_gateway_suite.py::test_every_real_gateway_test_in_the_sources_runs_and_passes`，同样是 `JAVA_HOME 未设置` | 42% / 2626 → 44% / 2626 |
| 同上的 `android-silence … asr-android` 五个目录（**重跑**，子 shell 里先 `source ~/.config/auri/android-env.sh`） | 301 passed in 236.47s (0:03:56)（rc=0）；网关孤儿进程：运行前 0，运行后 0 | 34% / 2626 → 64% / 3565（系统在运行中把交换区总量从 3072 MB 扩到 4096 MB） |
| 同上的 `android-sync`（**重跑**，同样先 `source`） | 812 passed in 163.81s (0:02:43)（rc=0）；网关孤儿进程：运行前 0，运行后 0 | 64% / 3565 → 62% / 3501 |

**怎么读这张表**：

- 全部是 **E2**（JVM 单测、假适配器、真实文件系统上的假磁盘）；`android-sync` 那一行里，pytest 会真实调用 Gradle 跑 `:sync:realGatewayTest`（对着真实 Python 网关进程，12 个，0 失败），这条也单独跑了一次。没有任何一项是真机或模拟器结果。
- **第一次作废**：我的验证脚本在跑那两批验收 pytest 时没有 `source ~/.config/auri/android-env.sh`，`JAVA_HOME` 没设；这些验收测试按设计在缺前置条件时**失败而不是跳过**，所以共 23 个（第一批里 `engine_pin_guard` 6 个失败加调用 Gradle 的合同输出校验 16 个错误；`android-sync` 里 1 个）都以 `JAVA_HOME 未设置` 失败（日志里 `Failed: JAVA_HOME` 恰好出现 22 + 1 次，没有别的失败原因），没有一个失败与代码有关。修了脚本（子 shell 里先 `source`）之后原样重跑，两批都全绿（301、812），与评审修复之前的通过数量一致（301、812）。
- 内存读数取自 `memory_pressure | grep -i 'free percentage'` 与 `sysctl vm.swapusage`。每次运行前都过了“空闲 ≥ 25 %”的闸（运行前最低 34%），脚本另加了一道“交换区剩余 ≥ 200 MB”的闸（实际最低 382 MB），没有一次需要等待。“60 秒内交换区增长 ≤ 500 MB”这一条我没有写成自动化的闸，只是人工看了读数：除重跑第一批验收 pytest 以外，交换区已用量在整个验证期间没有增长（2690 → 2626 MB）；重跑那一批的过程中交换区已用量涨了约 940 MB（2626 → 3565 MB，之后回落到 3501 MB），同一时间机器上还有一个与本任务无关的 1.6 GB 的 `node` 进程和一个 1.4 GB 的 Apple Virtualization 进程，我分不清各自的份额，这一批 pytest 起的 Gradle JVM 是其中之一，如实记在这里。
- `pytest` 都带 `NO_PROXY=localhost,127.0.0.1,::1`；网关孤儿进程数是这一次运行结束后多出来的 `auri_gateway` 进程数（> 0 会被杀掉并判这次运行失败）。五次 pytest（含作废的两次）前后各量了一次全机的 `pgrep -f auri_gateway`，十次都是 0；`:sync:realGatewayTest` 之后也量了一次，是 0。

**没有运行的项（`NOT_RUN`）**：所有真机场景（第 4 节 A–E）、仪器测试 `:app:connectedDebugAndroidTest`、`:platform:connectedDebugAndroidTest`（含评审 H1 新增的 `SqliteRecordLibraryTest` 设备端用例；原因：`deferred, emulator disabled to save memory`；命令在 4.F）、模拟器验收脚本 `run_emulator_acceptance.sh`（同上）；另外 `:modelmgmt:realDownloadCheck`（联网下载 163 MB）与 `android-sync/mutation_check.py` 的整表真跑（约 2 小时）不属于本票，也没有运行。

## 11. 已知限制与后续

- 所有真机行为（蓝牙断开时 `AudioRecord` 是报错还是悄悄改读手机麦克风、来电时各品牌的焦点变化、设置里撤销权限时进程是否被杀、`START_NOT_STICKY` 的实际效果、真实闪存上的 fsync 开销）都是 `NOT_RUN`，需要产品负责人按第 4 节做。
- 被杀之后，录音要等到**下次打开应用**才出现在列表里；没有后台恢复任务（也不应该有：恢复不能自己开麦或起前台服务）。
- 提交间隔与掉尾上限是推导值与 JVM 假磁盘上的实测，不是手机上的实测。
- 评审修复之后新增的、真机才能验证的假设：路由回调是否真的在开始时发一条初始通知（第 6 节假设 14，决定 B1 的豁免是否恰好）；拒绝焦点时各品牌系统的行为（M3）；从旧版本真机升级时目录改名与改库的先后（H1，设备端用例 `NOT_RUN`）；权限标志的残余竞争（假设 17）。
- 没有为这一票另做“采集 → 合同 JSON”的 Python 校验工具：被截断与找回的结果在 Kotlin 里用 `ContractRules.violations(...)` 断言（`OrphanRecovery` 里还有 `check`），没有再走一遍真实 JSON Schema。

## 12. 独立评审的发现与修复（范围 `4162fa9..8d4f710`）

合并 `origin/integration/auri-v2`（`a6958af`）之后，逐条修复；每个发现一个提交、推送到 `origin/ticket/19-interruptions-and-recovery`。证据都是 **E2**（JVM 单测、假适配器、真文件系统上的假磁盘、真 SQLite 引擎）；真机一栏如实写 `NOT_RUN`，不用模拟代替。

| 编号 | 严重度 | 提交 | 问题 | 修复 | 覆盖它的测试（E2） | 变异 | 真机（E3） |
|---|---|---|---|---|---|---|---|
| B1 | 阻塞 | `9793617` | 耳机断开又恢复、路由通知被压到恢复之后才递送时，`observe()` 把它当“没变”吞掉，旧会话继续接收旧 epoch 的帧 | 通知本身使证据作废（epoch 递增、报路由变化，协调器按 `route_lost` 收场）；判断一批帧与递送在同一把锁里；开始之后、递送任何帧之前的第一条通知豁免（假设 14） | `BluetoothCaptureAdapterTest`（通知后的帧、延迟的“走了又回来”、豁免及其限度）、`RouteBoundaryTest`（真协调器 + 真适配器：通知之后什么都没写） | B1a、B1b | 4.A **`NOT_RUN`** |
| B2 | 阻塞 | `96ce271` | 磁盘写满时 `seal()` 吞掉最后一行日志失败，仍改名并登记；之后被杀留下永远被拒收的 `.wav`（`audio_unverified`） | `seal()` 先提交最后一行分段日志，写不进去就抛出：不改名、不登记，只通知丢失（存储）；已提交的分段下次找回 | `RecordingRecoveryTest`（日志行写不进去，零个与一个已提交分段）、`InterruptionEndToEndTest`（音频放得下、日志少 1 字节；写满扫描多出第六种收场） | B2a | 4.E **`NOT_RUN`** |
| H1 | 高 | `d379d0c` | 升级把录音目录挪到 noBackup，库里登记的绝对路径没跟着改：等转录、重新转录的 Record 打不开文件，以 FAILED 收场 | 每次启动幂等地改库（一条 `UPDATE`，子串比较不用 `LIKE`），只在老目录已经不在时改；在调度器与管线创建之前同步做完 | `RecordingsMoveUpgradeTest`（8：真文件 + 真 SQLite + 真 `NoBackupStorage.place` + 真转录栈）、`RelocateAudioSqlTest`（5）；设备端 `SqliteRecordLibraryTest` 一个用例 | H1a、H1b | 设备端用例与真机升级路径 **`NOT_RUN`** |
| H2 | 高 | `1e9f112` | 读线程发现的权限撤销通知不带会话：迟到的旧通知可能把新的待命变成受阻、把新的录音打断 | 读线程的通知走本次录音的 sink（带会话与代数），协调器核对后才处理，旧的拒收并记一笔；界面线程的核对仍走全局；已结束的采集不认领边沿 | `InterruptionTest`、`BluetoothCaptureAdapterTest`、`PermissionRevocationRaceTest`（真适配器 + 真协调器：旧录音的迟到通知到达第二次录音中途，状态仍是 `RECORDING`） | H2a、H2b、H2c | 4.D **`NOT_RUN`** |
| M1 | 中 | `86f4381` | 没有语音的录音收场时 `abort()` 连日志一起删；登记（异步）之前进程被杀、或库写失败，这个 Record 就永远丢了 | 新增 `RecordingWriter.finishWithoutAudio()`：不留空音频文件、日志留到登记之后；“丢失（存储）”那一路仍 `abort()` | `RecordingRecoveryTest`、`CaptureResultTest`、`NoSpeechRecordDurabilityTest`（真协调器 + 真存储 + 真管线 + 真恢复：登记前被杀、库写失败、正常登记） | M1a–M1e | 4.C **`NOT_RUN`** |
| M2 | 中 | `b692485` | 第一个分段不可信时恢复删了 `.part` 而日志还在；Record 登记前再被杀，下次变成 `audio_missing`，永远建不起来 | 恢复在登记之前不删任何东西；登记之后的那次启动才清掉空壳与日志（先 `.part`、后日志） | `RecordingRecoveryTest`（含逐字节被杀扫描：每个位置都要在登记之后清理干净） | M2a、M2b | — |
| M3 | 中 | `b874e09` | 系统不给音频焦点（返回 null）时仍开始录音 | 没拿到焦点就以 `AUDIO_INTERRUPTED` 拒绝：不打开录音、不启动读线程，路由请求撤销一次 | `BluetoothCaptureAdapterTest`、`RouteBoundaryTest` | M3 | 4.B **`NOT_RUN`** |

**命令（每条先取共享 Gradle 锁）**：
```bash
cd apps/android
./gradlew :platform:testDebugUnitTest --tests app.auri.platform.storage.NoSpeechRecordDurabilityTest --tests app.auri.platform.library.RecordingsMoveUpgradeTest --tests app.auri.platform.audio.PermissionRevocationRaceTest # 评审修复里新增的系统级测试
```
