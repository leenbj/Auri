# Ticket 21 验收：Android 与网关配对，并把文字送到网关

对应 `.scratch/auri-v1/issues/21-android-pair-and-sync.md`（GitHub issue #22），PRD / ADR 引用：ADR-0018（网关只在局域网、自签证书加公钥指纹绑定）、ADR-0013（单用户网关，身份由认证结果映射）、ADR-0020（只有录下 Record 的那台设备能给它写 Revision）、FR-SYNC-002（离线补传不得复活已删除的 Record）、SEC-004（转录文字是数据，不是指令）。这是手机端第一次把文字送出手机，也是 iOS、鸿蒙两端同步层的样板，所以接缝与规矩比界面重要。

**状态（2026-11-19）**：**没有任何真机（E3）结果**：本机没有 Android 手机，所以清单最后一项“真机上入库、断开 Wi-Fi 录音、重连后自动补传”是 **`NOT_RUN`**；产品负责人的真机步骤在第 4 节。真实的相机扫码、真实的 mDNS 广播发现真网关也都是 `NOT_RUN`（模拟器没有摄像头，也和宿主机隔着 NAT，收不到多播）。已经有的证据是 **E2**：JVM 单测、**对着真实的 Python 网关进程**的 JVM 测试、上一轮在模拟器上跑过的仪器测试（含对宿主机真网关的一次配对与入库；**针对提交 `23afdff`，不是评审修复之后的代码的证据**，见下一段）。

**2026-11-19 这一轮（独立评审 F1–F14 的修复）按指示禁用了 Android 模拟器以节省内存**：没有启动模拟器、AVD、Android Studio，没有运行任何 `connectedAndroidTest`。这一轮新增或改写的**所有设备端检查**——`OfflineProofTest` 里两个“产品转录入口零字节”测试（F1）、`UpgradeThenSyncTest` 与重写的 `SyncPublicationTransactionTest`（F5）、`ResumeSchedulingTest`（F7）、真实的 `bmgr` 备份与设备迁移（F2）、已装 release 包里 JobScheduler 的登记与杀进程后被叫醒（F7）——**只编译过，没有运行：`NOT_RUN`（原因：推迟，为省内存禁用了模拟器）**，要跑的命令在第 12.4 节。**这一轮唯一的新证据是 JVM 单测、真网关的 JVM 测试与 Python 验收（都是 E2）**，结果在第 12 节。上一轮（`23afdff`）模拟器上的 30 个 `:syncplatform` 仪器测试与 29 个 Ticket 14 的 `:app` 仪器测试是历史记录，不覆盖这一轮改过的代码。

证据等级沿用 PRD 第 5.2 节：JVM 与模拟器 = **E2**；真机 = E3。没有真机/账号/签名的项标 `NOT_RUN` 或 `BLOCKED_EXTERNAL`，不用模拟或伪造替代。

## 1. 这里有什么

| 路径 | 作用 |
|---|---|
| `apps/android/sync/`（`:sync`） | **纯 Kotlin/JVM，不含 Android 类**：配对载荷校验（合同 1.1）、固定公钥的 TLS 与最小 HTTP/1.1 客户端、网关接口与客户端、网关定位（记下的地址 → mDNS）、同步状态机（照 `sync.machine.json`）、同步引擎、同步循环、`SubmissionBuilder`（本地 Record + Revision → 合同的 submission）、同步状态汇总（`SyncStatus`）、进程被杀之后的叫醒决策与执行（`ResumePlanner`、`ResumeRunner`，只依赖 `ResumeScheduler` 接口，评审 F7）、失败后有界退避重新订阅的帮手（`RestartingProducer`，F4）。`testFixtures` 里有任务库契约测试与内存实现，供 `:syncplatform` 复用 |
| `apps/android/syncplatform/`（`:syncplatform`） | Android 实现：Keystore 封存（`KeystoreSecretBox`）、SQLite 任务库（与 Record 库**同一个数据库文件**；每次打开时在同一个事务里建表并回填升级前已发布的 Revision，评审 F5）、JobScheduler 叫醒（`JobSchedulerResume`、`SyncResumeService`，F7）、`NsdManager` 发现、`ConnectivityManager` 网络信号、CameraX 取景 + ZXing 解码、同步日志出口（`AuriSync` 标签）。**全 App 只有这个模块与 `:modelmgmt`（下载模型）的清单声明 `INTERNET`**，网络类只许出现在登记过的传输类里（第 8 节） |
| `apps/android/app/`（`:app`） | `SyncViewModel`（文件头注释就是文档：唯一的可观察入口是 `state: StateFlow<SyncUiState>`）、极简 Compose 的 `SyncScreen` / `SyncPage`、`AuriRuntime.sync`；首页多一个“同步与配对”入口；`BrowserLink`（许可证链接，全 App 唯一发“查看网址”意图的类，F1）；调试版才有的 `ManualPairingEntry`（`src/debug`，release 是 `src/release` 里的空实现，F14）；备份与换机迁移规则 `xml/data_extraction_rules.xml`、`xml/backup_rules.xml`（F2） |
| `real_gateway_host.py` | 宿主机上的真网关辅助：`start` 起真网关并造配对载荷与一条 Record，`verify` 只读核对网关数据库（模拟器验收用） |
| `run_emulator_acceptance.sh` | 一次性无头模拟器验收（E2）：取共享锁 → 建 AVD → 起模拟器 → 起宿主机真网关 → 仪器测试 → 逐个核对 → 回头查网关数据库 → **无论成败都**停网关、杀模拟器、删 AVD、放锁 |
| `check_sync_instrumentation.py` | 读 `am instrument -r` 的原始输出，逐个核对每个 `@Test`（含 `SqliteSyncJobStoreTest` 继承来的 6 个契约测试）都跑了且通过 |
| `mutation_check.py` | 变异检查（第 9 节）：253 条，在仓库外的快照里跑（每次调用都重新从工作树同步），**绝不改真实工作树** |
| `test_*.py` | 上面这些工具的测试；`test_real_gateway_suite.py` 会真实调用 Gradle 跑 `:sync:realGatewayTest` 再核对每个 `@Test` 都跑了 |

对既有代码的改动（共享文件，清单在第 12.6 节）：`SqliteRecordLibrary` 多一个让同库的别的表挂进来的接缝；`AuriRuntime` 把 Record 库换成 `SyncStorage.library(...)` 并多一个 `sync`；首页多一个入口按钮；评审 F2 之后 `:platform` 多一个 `NoBackupStorage`（配对记录、录音、耳机配置放进 `noBackupFilesDir`，旧位置的文件首次访问时挪过去）。

## 2. 清单逐项对照

| # | 要求 | 覆盖它的测试 / 文件 | 证据等级 |
|---|---|---|---|
| 1 | 扫码完成配对，设备令牌存入平台安全存储，**不在日志里、不在明文文件里、不在仓库里** | JVM：`PairingPayloadTest`（16：按合同 1.1 逐项严格校验，合同里的无效样本一个不漏地拒绝）、`QrDecoderTest`（10：含网关真实的约 380 字符载荷、四个方向、反色）、`QrFrameReaderTest`（6：CameraX 行距、每帧恰好关闭一次）、`PairingServiceTest`（15：每种结局的处置；**请求可能已送到就绝不重发配对码**；调用方被取消时令牌也要存下来）、`FilePairingStoreTest`（12）与 `AesGcmSecretBoxTest`（5）、`SyncViewModelTest`（17）。真网关（JVM）：`RealGatewayTest` 的“二维码配对”——网关命令行打印的**真终端二维码**经 `AsciiQr` 还原成图、由手机端的 `QrDecoder` 读出、配对成功，令牌只以密文落盘（磁盘上翻不到明文），网关登记了这台设备，配对码只能用一次。模拟器：`KeystoreSecretBoxTest`（10：真 AndroidKeyStore，密钥不可导出、IV 每次随机、改一个字节或截断都打不开、密钥丢失读成“丢失”而不是崩溃，配对记录落盘后私有目录里任何地方都没有令牌明文）、`RealGatewayOnDeviceTest`（1：对宿主机真网关配对、入库、重复提交幂等；令牌与配对码既不在 logcat 也不在明文文件里） | JVM 与模拟器 E2；**真机相机扫码 `NOT_RUN`** |
| 2 | 每次连接核对网关公钥指纹；不符就拒绝并提示重新配对，**没有“忽略并继续”** | JVM：`SpkiPinTrustManagerTest`（6）、`PinnedHttpsClientTest`（25：每次连接都是全新的 TLS 上下文、不符时服务器**一个请求都没收到**、网络故障不误报成不符；另有 10 个管分块长度算术与整个往返的总时限，评审 F3/F8）、`SyncEngineTest`（46）里“连接要用户处理期间一个带令牌的请求都不发”“新配对不被晚到的旧观察覆盖”、`SyncStatusTest`（17）与 `SyncScreenTextTest`（7）里“请重新配对”的话。**守卫**：`NoNetworkCodeGuardTest`（26）扫描**七个生产模块**编译出的类、源码与清单（评审 F1/F13：登记以类为单位，见第 8 节）——没有 `HostnameVerifier`、没有“信任所有证书”的 `TrustManager`、没有明文 `http://`、唯一的信任管理器是 `:sync` 的 tls 包里的 `SpkiPinTrustManager`，`WebView`、`DownloadManager`、查看网址的意图与别的 HTTP 栈只出现在登记过的类里；每条规则都有“正对照”（故意写一段违规的类，规则必须认得出）；`NoNetworkManifestTest`（6）证明 `INTERNET` 只来自 `:syncplatform` 与 `:modelmgmt`，`usesCleartextTraffic="false"` 明确写出、合并后的清单没有放开明文流量。真网关（JVM）：“换了一台网关（公钥不同）”（拒绝连接、需要重新配对、期间一个连接都不再发——**包括用户处理之前新录的任务**）、“网关吊销了这部手机”（令牌被拒、需要重新配对、不再重试） | E2 |
| 3 | 先连配对时记下的地址，连不上用 mDNS 查找 | JVM：`GatewayLocatorTest`（10：最近连上的地址 → 配对时记下的地址 → mDNS；某个地址是冒充者时继续找；全是不符的公钥才报“公钥不符”；取消不被吞）、`GatewayAdvertisementTest`（6：只认同一个 `gateway_id`，主机与端口不合法的广告不是候选）。真网关（JVM）：“地址搬家”——记着的地址没人听了，用发现（**mDNS 的替身**，JVM 里没有 `NsdManager`）找到网关的新地址，核对同一把公钥后送达，新地址被记住，下一条不再需要发现。模拟器：`NetworkAndDiscoveryTest`（2：网络信号跟着飞行模式走；真 `NsdManager` 在没有任何广告时按时返回空、不崩） | E2；**真实 mDNS 广播发现真网关 `NOT_RUN`**（要真实局域网） |
| 4 | 发布 Revision 和创建待同步任务**在同一个本地事务里**；任务在应用重启后保留 | 模拟器（真 SQLite）：`SyncPublicationTransactionTest`（7：同一个事务登记任务；登记失败整个事务回滚、Revision 也不留；没有这条 Record 时发布失败也不留任务；关掉重开文件两样都在；旧库没有任务表时打开即补建，并把升级前已发布的每一版回填成待发送任务、版本号不变——**最后这一条是评审 F5 的改写**）、`UpgradeThenSyncTest`（1，**评审 F5 新增**：旧版本的数据库升级之后，第一轮同步就把升级前已发布的每一版送到网关）、`ProcessKillSurvivalTest`（3：**真杀进程**——另一个进程在事务中途被 SIGKILL，什么都没留下；提交之后被杀，两样都在；发送途中被杀，任务以“结果不确定”留下，重启后引擎先问网关、网关已收下就不重发）、`SqliteSyncJobStoreTest`（继承任务库契约测试 6 条，JVM 里的内存实现跑同一套）。回填语句本身在 JVM 里有 E2 证据：`SyncJobsBackfillTest`（7）在真 SQLite 引擎（sqlite-jdbc，只在测试类路径上）上跑生产的那几条语句——旧库每一版都得到任务、时间取发布时间、先发布的先同步、重复打开幂等且不动已有任务、中途被杀后下次补齐 | 模拟器 E2（上一轮，针对 `23afdff`）；**评审 F5 改写或新增的两个设备端测试这一轮只编译过，`NOT_RUN`**；回填语句 JVM E2 |
| 5 | 同步状态机的事件序列样本全部通过 | `SyncScenarioReplayTest`（1）：`contracts/fixtures/state-machines/sync.scenarios.json` 里的 8 个场景，用假适配器回放到**真正的同步引擎**上（事件都由真实原因造出来：网关答 503 / 409 / 401、连不上、网络断开……，不是直接喂事件），都得到合同写的结果；转移表里每一条规则都必须被某个场景步骤走到过（少回放一个场景、漏掉一条规则，测试就失败）。合同文件直接从仓库 `contracts/` 读，不是拷贝。`SyncMachineTableTest`（4）：转移表逐格与 `sync.machine.json` 一致 | E2 |
| 6 | 界面区分三种情况：等网关、稍后重试、需要用户处理 | `SyncStatusTest`（17：六种互斥的情形——没配对 / 需要用户处理 / 稍后重试 / 等网关 / 发送中 / 已同步；**需要用户处理优先于稍后重试**；公钥不符是连接层面的问题，没有任务也要告诉用户）、`SyncScreenTextTest`（7：三种情形各自的话，等网关与稍后重试明说“不需要你做什么”，需要处理的那一种不这么说，并告诉用户该怎么办）、`SyncViewModelTest`（17）。界面是极简 Compose（`SyncScreen.kt`），数据与操作的说明在 `SyncViewModel.kt` 文件头 | E2；**界面本身没有截图/界面测试**（见第 11 节） |
| 7 | 提交结果不确定时**先查询再决定是否重发**，不产生重复记录 | `SyncEngineTest`（46：假网关的六种故障，外加评审 F6/F9/F10/F11/F12 的状态分类、配对绑定与网络对账）、`GatewayClientTest`（18）、`ProcessKillSurvivalTest`“发送途中被杀”。**对着真实网关**（`RealGatewayTest`）：“响应在路上丢了、网关其实已经入库”——先查询得知已入库，**不重发**，库里只有一条；“请求没送到网关”——先查询得知没有，才重新提交一次，库里恰好一条；“重复提交同一版”——网关回 `duplicate`，库里仍是一条；“同一版号、正文不同”——版本冲突，永久停下，库里留着最初的那一版。故障注入在 `HttpExchange` 边界、包着真实的 `PinnedHttpsClient` 与真网关（网关不留请求日志），并用 TCP 转发器的连接计数做独立证据 | E2 |
| 8 | 真机上验证：在局域网内入库；断开 Wi-Fi 后录音，重连后自动补传 | **`NOT_RUN`**：本机没有手机。产品负责人的步骤在第 4 节；模拟器侧能证明的部分（任务表、事务、杀进程、飞行模式下网络信号变化、对真网关入库）在上面几行。JVM 里“什么时候跑一轮”由 `SyncLoopTest`（27）管（观察任务表与网络的协程出故障后按有界退避重新订阅，评审 F4）：网络断开改成等网关且断网期间不探测；网络恢复**或默认网络换了一个而信号仍是“可用”**（手机常开着移动数据时，Wi-Fi 断开/回来就是这样）都立刻补传，不用干等最长 15 分钟的探测退避，网络一变退避就从头开始；一轮同步不会被任务表的变化或新的触发取消（取消发到一半的请求会留下“结果不确定”的任务） | `NOT_RUN`（E3） |

另：本文件与 `CLAUDE.md` “命令”一节已更新（`.gitignore` 没有改：已有的 `execution/**/raw/` 就覆盖真机原始日志目录）；`:platform` 的隐私守卫（`NoPhoneMicPathTest`）从四个模块扩到六个——**同步层里任何地方都不能引用 `AudioRecord` / `MediaRecorder` 等向系统要麦克风的类**（变异验证见第 9 节）。

## 3. 在本机怎么跑

```bash
source ~/.config/auri/android-env.sh
cd apps/android
# JVM 单测（不需要设备；:modelmgmt 是 Android 库，要用 testDebugUnitTest；:app:testDebugUnitTest 现在会先编译 release 变体）
./gradlew --no-daemon :capture:test :modelmgmt:testDebugUnitTest :platform:testDebugUnitTest :app:testDebugUnitTest :asr:testDebugUnitTest :sync:test :syncplatform:testDebugUnitTest
# 对着真实的 Python 网关进程（要仓库的 .venv；找不到解释器就失败，不会跳过）
./gradlew --no-daemon :sync:realGatewayTest
# 构建 APK 与三个模块的仪器测试 APK（只编译打包、不运行；运行要设备或模拟器，见第 12.4 节）
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :syncplatform:assembleDebugAndroidTest :platform:assembleDebugAndroidTest

cd ../..
# Python 验收工具（含真实调用 Gradle 的 test_real_gateway_suite.py，约 1–2 分钟）
.venv/bin/pytest execution/acceptance/android-sync
# 模拟器验收（E2；会取共享锁、建并删除一次性 AVD、在宿主机起一个真网关；约 5–10 分钟，别的工作进程占着模拟器时会等）
execution/acceptance/android-sync/run_emulator_acceptance.sh
# 同一台模拟器上再跑 Ticket 14 的 :app 仪器测试（含被本票改写的离线证明；要真模型，先 prepare_model.py）
AURI_ALSO_ASR_ACCEPTANCE=1 execution/acceptance/android-sync/run_emulator_acceptance.sh
# 变异检查（第 9 节）；先 --list 看有哪些
.venv/bin/python execution/acceptance/android-sync/mutation_check.py --list
.venv/bin/python execution/acceptance/android-sync/mutation_check.py --match '^real-gw-' --report /tmp/mutation.md
# 真机或模拟器：同步日志（只有事件名、枚举与数字：没有文字、令牌、配对码、公钥指纹、主机名或地址）
adb logcat -d -s AuriSync:I
```

前置：JDK 17、Android SDK 35、仓库根目录的 `.venv`（`pip install -r tests/contract/requirements.txt -r services/gateway/requirements.txt`）。macOS 上 adb 可能卡在 IOKit，脚本已设 `ADB_LIBUSB=1`。模拟器必须是 arm64（`:asr` 的原生库只有 `arm64-v8a`）。

**要新鲜证据就加 `--rerun`**：Gradle 在输入没变时会把测试任务判为 `UP-TO-DATE` 直接跳过，那不算“重新跑过”（接手这一轮第一次跑全量，六个模块里有三个就是这样被跳过的）。每个任务名后面跟 `--rerun`，例如 `./gradlew --no-daemon :sync:test --rerun :syncplatform:testDebugUnitTest --rerun`。内存紧张的机器（16 GB 且同时有别的工作进程）一次只开一个 Gradle，并加 `-Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2`；验收脚本、Python 验收测试和变异检查（`mutation_check.py`）里的 Gradle 子进程可以经环境变量 `AURI_GRADLE_ARGS` 带上同样的参数（例如 `AURI_GRADLE_ARGS='--no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2'`）。**不要用 `./gradlew --stop`**：多个工作树共用一台机器时它会停掉别人正在跑的构建（这一轮的整表变异检查里有一条就是被别人的 `--stop` 杀掉守护进程而中断的，见第 12.3 节）。

## 4. 产品负责人：真机 + 真局域网（`NOT_RUN`）

**目的**：证明三件模拟器给不了的事：①手机在真实局域网里扫真二维码配对并把文字送进网关；②断开 Wi-Fi 后录的文字不丢，界面如实显示“等网关”，重连后自动补传；③网关换了地址（DHCP）时靠 mDNS 找回来。这是 E3 证据。**做不到的步骤标 `BLOCKED_EXTERNAL` 并写原因，不要用模拟或猜测补。**

**需要**：一部 Android 12 及以上、arm64 的手机（开发者选项里打开 USB 调试）、已与手机配对的 HUAWEI FreeClip（Record 只能由蓝牙耳机录出来，没有别的造 Record 的办法，所以入库必须真录一段）、手机与 Mac 连同一个 Wi-Fi、数据线、已按第 3 节配好工具链的 Mac。

**不要做的事**：不要为了让它“能连”而放行任何证书或改用明文 HTTP；不要把真实个人口述的文字提交进仓库（用下面的固定测试句）；`adb shell dumpsys` 的原始输出含设备地址，留在本机，只把相关几行摘要写进结果文件；配对码与设备令牌不要截图、不要贴进结果文件。

1. **起网关（Mac）**。`PYTHONPATH=services/gateway .venv/bin/python -m auri_gateway serve --data-dir ~/auri-gateway-data --port 8443`（默认只绑回环和私网局域网地址，并广播 mDNS `_auri-gateway._tcp`，见 `services/gateway/OPERATIONS.md`）。macOS 防火墙询问是否允许 Python 接受传入连接时选允许；记下 Mac 的局域网 IP。
2. **构建并安装**。`cd apps/android && ./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`（`ADB_LIBUSB=1`；多台设备时先 `export ANDROID_SERIAL=<序列号>`）。
3. **生成配对二维码**（另开一个终端）：`PYTHONPATH=services/gateway .venv/bin/python -m auri_gateway pair --data-dir ~/auri-gateway-data`。终端会打印一个二维码，**5 分钟内有效、只能用一次**；网关按深色终端的习惯反色打印，手机端两种极性都读，但深色背景的终端最稳。Mac 有多块网卡或开着 VPN 时，二维码里的地址未必是手机够得着的那个：加 `--host <Mac 的局域网 IP>` 指定（见 `services/gateway/OPERATIONS.md`）。
4. **配对**。手机上打开 Auri → “同步与配对” → 对准终端里的二维码（首次会要相机权限）。预期：界面显示已配对、没有需要处理的事；Mac 上 `PYTHONPATH=services/gateway .venv/bin/python -m auri_gateway list-devices --data-dir ~/auri-gateway-data` 多了一台 `android` 设备。**截图①**（同步页，已配对）。再用同一个二维码扫第二次应当失败（配对码只能用一次，界面说明原因）；**不要**为了再试一次去放宽任何东西，重新 `pair` 生成新码即可。
5. **在局域网内入库**。回首页，按 Ticket 16 的步骤用 FreeClip 录一段，说固定测试句：“今天是测试，Auri 录音转文字，一二三四五。”转录完成后回到“同步与配对”页：几秒内应显示已同步。在 Mac 上核对（只看条数与长度，不要把正文贴出来）：`sqlite3 -readonly ~/auri-gateway-data/gateway.db "SELECT record_id, revision, length(text), stored_at FROM revisions ORDER BY stored_at DESC LIMIT 3"`。记下从“转录完成”到“网关里出现”的秒数。**截图②**。
6. **断开 Wi-Fi 后录音，重连后补传**（做**两遍**）。**第一遍：移动数据关着**——手机关闭 Wi-Fi 并关掉移动数据（这时系统没有任何默认网络），再录一段、转录完成。同步页应显示 **“等网关”**（不是“需要你处理”，也没有让用户做什么），Mac 上核对网关里**没有**这一条。**截图③**。重新打开 Wi-Fi：不用点任何东西，应当在一分钟内自动补传，状态回到已同步，网关里恰好多一条。记下“重连”到“网关里出现”的秒数（重试退避从 5 秒起翻倍，封顶 15 分钟，网络恢复会立刻触发一轮，不用等退避）。**截图④**。**第二遍：移动数据开着**（手机的日常状态）——关闭 Wi-Fi 时系统只是把默认网络从 Wi-Fi 换到蜂窝，信号一直是“可用”，没有“断网”事件；同步循环把“默认网络换了一个”也当作一次网络变化（JVM 测试 `SyncLoopTest` 钉死了这一点，真机上系统是否照我们的假设回调是 E3 才能回答的）。同样再录一段：同步页应显示“等网关”；重新打开 Wi-Fi 后应在一分钟内补传。**如果这一遍要等很久**（探测退避最长 15 分钟），请原样记下秒数与现象，不要改代码绕过：那说明真机上系统的回调和假设不一样。另一个已知情形（见第 11 节）：网关所在的 Wi-Fi 没有外网时，系统可能让蜂窝继续当默认网络，发往局域网地址的连接会走蜂窝而连不上——这时“等网关”会一直持续到系统把默认网络换回 Wi-Fi，也请原样记录。
7. **反例一：网关换了密钥**。停掉网关，把 `~/auri-gateway-data/tls/` 改名备份后重新 `serve`（会生成新的密钥，指纹变了），手机上再录一段：同步页应显示**需要用户处理**，原因是“网关的公钥与配对时不同”，并让你重新配对；**不会**自己连上去，也没有“忽略并继续”。**截图⑤**。然后改回原来的 `tls/` 目录、重启网关，用 `pair` 生成新码重新配对，被扣下的文字应当补传。
8. **反例二：网关吊销了这部手机**。`auri_gateway revoke-device --data-dir ~/auri-gateway-data --device-id <list-devices 里的 device_id>`，手机上再录一段：应显示需要用户处理（令牌被拒），重新配对后补传。
9. **（选做）IP 变了靠 mDNS 找回来**。让 Mac 换一个局域网 IP（例如切换到另一个 Wi-Fi 再切回、或让路由器重新分配），重启 `serve`，手机不重新配对，录一段：应当自动补传，并且“记下的地址”被更新。路由器若拦截 mDNS 多播就会失败——这是 ADR-0018 写明的情形，原样记录，**不要**因此放宽指纹检查。
10. **取回材料**：五张截图、`adb logcat -d -s AuriSync:I > auri-sync.txt`（只有事件名与枚举，可以入证据）、手机/系统/路由器型号、第 5、6 步记下的秒数；写进 `execution/acceptance/android-sync/results/<日期>-<手机型号>-lan.md`，原始日志放 `results/raw/`（已被 `.gitignore` 的 `execution/**/raw/` 忽略）。结果表建议这样记，状态词用 PRD 的枚举：

   | 手机 / 系统 | 场景 | 结果 | 证据 | 备注 |
   |---|---|---|---|---|
   | | 局域网内扫码配对 | | 截图①、list-devices | |
   | | 局域网内入库 | | 截图②、网关数据库行数 | |
   | | 断开 Wi-Fi 录音显示“等网关”，重连后自动补传（移动数据关着） | | 截图③④、秒数 | |
   | | 同上，移动数据开着（默认网络从 Wi-Fi 换到蜂窝再换回） | | 秒数 | |
   | | 网关换密钥：拒绝并要求重新配对 | | 截图⑤ | |
   | | 令牌被吊销：要求重新配对 | | | |
   | | IP 变化后 mDNS 找回（选做） | | logcat | |

**真机上我们不知道、需要这次观察的事**（代码里的数字是假设，不是实测）：相机在室内光线下读终端里的二维码是否稳定（网关用的静区只有 1 个模块，规范建议 4 个，见第 7 节）；手机在真实 Wi-Fi 下 `ConnectivityManager` 回调与“重连”的实际延迟（尤其是默认网络在 Wi-Fi 与蜂窝之间切换时，系统回调的顺序：只有 `onAvailable`，还是先 `onLost` 再 `onAvailable`）；IPv6 链路本地地址（带 `%wlan0`）作为网关地址是否可用；部分路由器的客户端隔离（AP isolation）会让手机根本连不到 Mac——这时请原样记录，不要改代码绕过。

## 5. 设计要点（iOS、鸿蒙照着做）

- **信任根只有二维码里的指纹**：配对码只发给出示该公钥的服务器。一次兑换的结局只有三类，处置不同——没连上或对方公钥不符（请求没有发出，配对码还在，换下一个地址，最后用 mDNS）；网关明确拒绝（停下，如实报告）；请求可能已送到却没拿到可读的回答（**配对码可能已被消耗，停下，绝不自动重发**）。
- **TLS 与 HTTP**：自己写的最小 HTTP/1.1 客户端，跑在 `SSLSocket` 上。**根本没有 `HostnameVerifier`**：身份检查只能是公钥指纹，用通用 HTTP 栈就得在 App 里留一个“放行一切”形状的类。校验只有一处——握手中的 `SpkiPinTrustManager` 比对叶证书公钥（SubjectPublicKeyInfo DER）的 SHA-256；**每个请求新建连接、新的 `SSLContext`**，没有会话复用（复用的会话不再过指纹核对），令牌在指纹核对通过之后才写出；不跟随重定向、不走系统代理。失败分三类：连接/握手没成（请求一定没发出）、公钥不符（请求一定没发出）、请求可能已发出但没读完响应（**结果不确定**）。
- **找网关**：先试最近连上的地址，再试配对时记下的地址，都不通才用 mDNS；每个候选都用一次不带令牌的 `GET /health` 探测（经过固定公钥的通道，冒充者在这一步就被拒绝）。某个地址是冒充者时继续找；全是不符的公钥才报“公钥不符”。
- **状态机只有一个入口**：引擎把事件交给 `SyncMachine`（`sync.machine.json` 的转移表），状态由机器说了算，引擎只造事件、落盘、做事。
- **同步引擎的硬规矩**（每条都有测试，也有变异把它打破一次）：**先落盘后发送**——`SEND_STARTED` 把任务记成“发送中、结果不确定”并落盘之后，才会有第一个字节离开手机；进程在途中被杀，下次启动把它接回来并仍记着不确定。**不确定就先问**——`GET /v1/records/{id}/status`：网关说已入库且哈希相同才确认；明确说没有（404 或没有这个版本）才重新提交；问不到答案就不重发，稍后再问。**永久拒绝不重试**——停在 `BLOCKED`，只有用户处理能让它离开，没有“次数用尽”的捷径。**连接问题挂在配对记录上**——公钥不符、令牌被拒是对所有任务都成立的事实，记进配对记录；在用户重新配对之前，一个带令牌的请求都不会发出（包括之后新录的任务）。**只经 `PairingStore.update` 改配对记录**，用户刚重新配对写入的新记录不会被晚到的旧观察覆盖。
- **同步循环**决定什么时候跑一轮：启动时、有新任务、网络恢复**或默认网络换了一个**（网络信号**不去重**：手机常开着移动数据时，Wi-Fi 回来在系统看来只是“可用→可用”）、用户回到前台或刚配对（`nudge`）、重试到点；任务在等重试用有界指数退避（5 秒起、翻倍、封顶 15 分钟）；网关一直连不上（网络是好的）同样按退避去探，不密集也不放弃；断网期间不探测；被永久拒绝的任务不产生任何计时。一轮永远不会被别的触发取消或重叠（取消一个发到一半的请求会留下“结果不确定”的任务）。**只在进程活着时工作**，没有用 WorkManager（第 11 节）。
- **待同步任务与 Record 库同库**：发布 Revision 是一个本地事务，事务里留的发布步骤（Ticket 16 就留好的 `publicationStep`）里登记任务，所以“已发布”与“待同步”不会分家；任务表在每次打开数据库时幂等地补建（`CREATE TABLE IF NOT EXISTS`），不占用 Record 库的 `SCHEMA_VERSION`，不会和那边的迁移抢版本号。
- **设备令牌**：整条配对记录用 AES-256-GCM 封存，密钥放在 AndroidKeyStore 里（不可导出），附加认证数据绑定用途；读不出来（密钥丢失、文件被改）是“丢失”，要求重新配对，**不会当成“从没配对过”**；应用清单 `allowBackup=false`，所以配对记录不会被备份到别处而失去密钥。令牌与配对码用 `toString` 脱敏，不进日志。
- **提交什么**：只有文字的 Revision（`revision.schema.json#/$defs/submission`），**没有音频**；本地数据不符合合同（路由不是蓝牙、没有语音片段、状态与结束原因对不上、转录没写模型）时 `SubmissionBuilder` 返回 `null`，引擎把它记成“本机数据读不到”，**不去修它**（悄悄补一个蓝牙路由类型等于对网关撒谎）。固定值不是遗漏：`input_policy=bluetooth_only`、`input_verification=verified`（只有路由已确认的录音才会成为 Record）、`headset_firmware=null`（系统不暴露，不得猜）、`publication={intent: context, destination_ids: []}`（只入库，不创建 Delivery；Destination 与自动发送是 Ticket 22）、`content.segments=[]`（见 G7）。
- **网络面受控**：`INTERNET` 只来自联网模块（`:syncplatform`、`:modelmgmt`）；`NoNetworkCodeGuardTest` 扫七个生产模块的类文件常量池与源码，网络类只出现在逐类登记的传输里（第 8 节，评审 F1/F13）；规则表带正对照，规则被削弱时守卫自己会变红（第 9 节）。
- **转录文字是数据**：同步层只搬运文字，从不解释；日志里没有正文（`SyncLog` 的合同是只写事件名、枚举与数字）。
- **一个总时限管住整个往返（评审 F8）**：主机名解析、连接、握手、写请求、读响应共用同一个截止时刻（默认 30 秒）；到点由看门狗关掉底层套接字（**先关 TCP 再关 TLS 层**：TLS 层的关闭要先抢写锁去发 close_notify，而写锁正被一个卡在“对端不收数据”里的写占着），阻塞中的调用立刻抛出；解析不是套接字操作，放在限时的等待里，解析器卡住时丢下它的（守护）线程。分块响应的长度算术用 `Long`、先比后分配（评审 F3）。
- **结果不确定时，先问再分类（评审 F9/F10）**：网关说 Record 已删除、来源是别的设备、同一版号的正文哈希不同，都是**停下**（`RECORD_DELETED` / `NOT_ORIGIN_DEVICE` / `VERSION_CONFLICT`），不重发；只有同一版哈希相同、未删除、来源是本机才确认入库；没有这一版才重新提交。
- **请求的结果与缓存的网关地址都绑定在配对上（评审 F6）**：重新配对时在途的旧请求得到的结果（哪怕是“令牌被拒”“公钥不符”）作废、任务回到队列；一轮里缓存的网关地址只对同一份配对有效。同理，网络变了要让等待重试的任务重新计时（F11），没网时完成的失败按“等网关”处理（F12）。
- **进程被杀之后由系统叫醒（评审 F7）**：只要有没送达的任务且配对可用，就登记一个跨重启保留、要求有网络的 JobScheduler 叫醒；`ResumePlanner` 决定登记还是取消，`ResumeRunner` 在被叫醒时跑一轮有时限的同步并回答“还要不要再叫醒”。观察任务表与网络的协程共用一个“失败后有界退避重新订阅”的帮手（F4）。
- **敏感数据不进备份与换机迁移（评审 F2）**：配对记录、录音、耳机配置放在 `noBackupFilesDir`；清单声明 `dataExtractionRules`（云备份与设备迁移）与 `fullBackupContent`，每个域整个排除、不写任何 include（只有 `allowBackup=false` 拦不住 Android 12+ 的设备到设备迁移）。

## 6. 假设（做决定时我没有问人，下面每一条都可以推翻）

1. **待同步任务在发布时就创建，没配对时先排着**：配对之后，之前录的文字会被补传。理由：用户选择把手机接到自己电脑上的网关，补传正是“离线队列”的意思。**这是产品决定**：如果希望“配对之前录的文字不上传”，只需要让没配对时发布不入队，不涉及别的改动。未配对时同步页只说“还没配对，文字只留在本机”。
2. **进程活着时由同步循环负责；进程被系统杀掉之后靠 JobScheduler 叫醒（评审 F7）**：需求写的是“离开局域网时显示等待网关，回来后自动补传”。进程在时（回到前台、网络恢复、重试到点）由 `SyncLoop` 管；进程被杀之后，只要还有没送达的任务且配对可用，就有一个跨重启保留、要求有网络的 JobScheduler 叫醒（平台自带，没有新增依赖，也没有用 WorkManager），系统拉起进程后跑一轮有时限（5 分钟）的同步，再告诉系统还要不要再叫醒。理由：WorkManager 多一个依赖，而我们只需要这一种叫醒。**限制见第 11 节**；真实的登记、杀进程后被叫醒、重启后保留都是 `NOT_RUN`（要设备）。
3. **只有 Record 的来源设备能提交它的 Revision**（ADR-0020）：别的设备改同一条 Record 被网关永久拒绝（`RECORD_NOT_ORIGIN_DEVICE`），任务停在“需要用户处理”，不重试。
4. **永久拒绝一律停下**：版本冲突（同一版号不同正文）、记录已删除、不是来源设备、请求不合规、本机数据读不到。没认出来的 4xx 也停下，不无限重试；网关在错误体里明说的 `retryable` 优先于按状态码的推断。
5. **重试退避**：5 秒起、翻倍、封顶 15 分钟；这些数字是假设，没有真机数据。
6. **调试版才有“手动输入配对载荷”**（模拟器没有摄像头）：评审 F14 之前它写在 `src/main`、靠运行时的 `FLAG_DEBUGGABLE` 藏起来，release 包里仍带着界面、文案与入口；现在真实现只在 `src/debug`，release 是 `src/release` 里的空实现，视图模型里没有这个入口，release 版编译出的类里没有（`ReleaseVariantTest`、变异验证、release APK 的 dex 里搜不到）。
7. **配对时报给网关的设备信息**：平台固定 `android`；应用版本、系统版本（`Android <版本> (API <级别>)`）、设备显示名（厂商 + 型号）按合同的长度上限截断（应用版本最长 64、其余最长 100）。**不含**序列号、IMEI、Android ID、构建指纹和用户给设备起的名字（`DeviceInfoTest`）。
8. **时间与随机**：设备上的时间用系统时钟，重试按单调时钟；没有时间同步的假设。
9. **TDD 的接缝没能先问人**：实现规范的 TDD 流程要求先和人确认要测的接缝；这次是被派发的 worker，没有人可问，我把接缝定在“网关接口（`GatewayApi` / `HttpExchange`）、任务库与配对库接口、同步引擎的公共方法、`SyncViewModel.state`”，测试都写在这些接口上，不测内部。可以推翻。
10. **没有运行 Codex 评审**：用户规则要求规范任务完成后用 codex 评审，但本次派发明确禁止子 agent 与帮手，所以没有做；这里的替代是第 9 节的变异验证与真网关测试。**没有使用 mcp-feedback-enhanced**（本环境没有这个工具）。
11. **换机不迁移任何东西（评审 F2）**：配对记录（设备令牌）、录音、耳机配置在 `noBackupFilesDir`；Record 库（含已转录的文字与待同步任务）留在 `databases/`，**被备份与设备迁移规则整体排除而不是挪位置**。含义：换新手机后什么都不会跟过去，要重新配对。理由：令牌与口述内容不能离开这台手机；若产品希望换机保留 Record 库，需要单独设计加密迁移，不是放开规则。老位置（`filesDir`）里的旧文件在升级后第一次访问时挪到新位置，新位置已有就不覆盖。**可以推翻。**
12. **旧库回填的任务，时间取 Revision 的发布时间，状态是“等待发送”（评审 F5）**：升级前已发布、没有任务的 Revision 在打开数据库时被补成任务（同一个事务里，幂等，已有任务一律不动），先发布的先同步，与发布事务里入队的任务同一个形状（没有“结果不确定”）。
13. **网关说 Record 已删除、来源是别的设备、同一版号的正文哈希不同，一律停下（评审 F9/F10）**：分别记为 `RECORD_DELETED`、`NOT_ORIGIN_DEVICE`、`VERSION_CONFLICT`，**不重发**；只有“同一版哈希相同、未删除、来源是本机”才确认入库，“没有这一版”才重新提交。这是保守读法；合同层面的缺口见 G12。
14. **网络往返的时限数字是假设（评审 F8）**：总时限 30 秒、连接 3 秒、握手/写/读每一步 10 秒（`Timeouts` 默认值），叫醒后的一轮最长 5 分钟；都没有真机数据。
15. **不做“不带 `INTERNET` 的离线构建变体”（评审 F1）**：见第 8 节，是 Ticket 30 之前要定的产品与分发决定。

## 7. 合同与网关的缺口及建议（没有改 `contracts/` 与 `services/gateway/`，只记录）

编号接着 Ticket 16 的 G1–G6。

| # | 缺口 | 位置 | 建议 |
|---|---|---|---|
| G7 | 本地 Revision 没有保留转录引擎的逐段时间戳，提交时 `content.segments` 只能是 `[]`。合同允许为空，**真网关（`RealGatewayTest`）接受**；但以后若要在 Agent 侧按段定位，这里是空的 | `revision.schema.json#/$defs/submission/properties/content/properties/segments` | 要么在 Ticket 18（分段）落地时让 Record 库保留段，要么在合同里写明“手机端可以不带段”。现在不需要改 |
| G8 | `sync.machine.json` 只有 `VERSION_CONFLICT` 一个“永久拒绝”事件。记录已删除（`RECORD_DELETED`）、不是来源设备（`RECORD_NOT_ORIGIN_DEVICE`）、请求不合规、本机数据读不到，都借用它，真正的原因只记在手机本地的 `SyncJob.blockedReason`，状态机里看不出区别 | `contracts/fixtures/state-machines/sync.machine.json` 的 `events` 与 `BLOCKED` 的入边 | 增加 `PERMANENT_REJECTION`（带原因枚举）或为每种原因各一个事件，并在 `sync.scenarios.json` 里各补一个场景。现在不影响行为，只影响 iOS、鸿蒙两端要不要各自重新发明这个映射 |
| G9 | 手机端没有办法知道某个 Destination 或自动发送是否已授权，所以 `publication.destination_ids` 固定为 `[]`、`intent=context`。真网关接受 | `revision.schema.json` 的 `publication` | Ticket 22（Destination 与发送）落地时由那边决定如何带上目标；这里只是记录“目前只入库” |
| G10 | 网关在 WARNING 日志级别下不留请求日志，所以“网关到底收到过几次提交”无法从网关侧直接数，只能靠数据库行数（幂等会掩盖重复提交）。真网关测试里“不重发”的证据来自手机侧的请求记录与 TCP 转发器的连接计数 | `services/gateway/auri_gateway/` 的请求日志 | 可选：网关在 DEBUG 级别或一个只读运维命令里提供“某条 Record 收到的提交次数”，便于以后真机联调时在网关侧复核。不是缺陷 |
| G11 | 配对二维码的静区只有 1 个模块（`services/gateway/auri_gateway/cli.py` 里 `qrcode.QRCode(border=1)`），QR 规范建议 4 个；载荷约 380 个字符，二维码版本较高。**实测**：ZXing 3.5.4 读完整的配对载荷时，画面顺时针转 90 度会读不出来（抛 `FormatException`，另外三个方向正常），而手机竖着拿时相机帧通常正是转了 90 度的——`QrDecoder` 因此对四个方向与反色都试一遍（`QrDecoderTest`“四个方向都能读”钉住了）。**没有在真机相机上观察到扫不出来，静区那一半未经验证** | `cli.py` 的 `border`；`pairing.schema.json` 的载荷字段 | 若真机扫码不稳，把 `border` 提到 4，或缩短载荷（例如省掉合同里的常量字段）。先看第 4 节真机观察再决定 |
| G12 | 提交没有**客户端生成的稳定操作编号**。结果不确定时手机只能“先 `GET …/status` 再决定重发”，而回答只能用 `record_id + revision + text_sha256` 来认“是不是我这一次的提交”：哈希相同当作已入库（就算是另一次提交写进去的同一份正文，也无害）；同一版号上是**另一份**正文时无法分辨“是我丢了的那一次、被别的路径写成了另一份”还是“真的冲突”，评审 F10 之后手机保守地**停下并交给用户**（`VERSION_CONFLICT`），不再自己重发。若合同给 PUT 一个客户端生成、重发时不变的操作编号（例如 `submission_id`），并让网关在 `duplicate` 与 status 响应里回显，“先问再重发”就能精确到“这一次提交有没有被处理” | `revision.schema.json#/$defs/submission`；网关 `PUT /v1/records/{id}/revisions/{n}` 与 `GET …/status` | 合同下一版（1.2）可选地加 `submission_id`（可选字段，旧客户端不受影响），网关在两个响应里回显；手机端现有的分类逻辑只需把“同一份正文”换成“同一次提交”。**现在不需要改**，这里只是记录为什么冲突时停下而不是猜 |

另：没有发现合同与网关行为不一致的地方——`RealGatewayTest` 里合同的 5 个有效 submission 样本（含被截断的与跳号的 Revision）真网关全部接受；错误码映射（`RECORD_VERSION_CONFLICT`、`RECORD_NOT_ORIGIN_DEVICE`、令牌被吊销）与引擎里的分类一致。

## 8. 与 Ticket 14 的冲突：离线证明（评审 F1：内核层面的保证没有了，而且无法恢复）

Ticket 14 的第 4 项“证明模型路径不联网”当时靠三层证据：①包里没有 `INTERNET` 权限；②因此内核对该 UID 的网络 socket 一律“权限被拒”（`OfflineProofTest.theKernelRefusesEveryNetworkSocketButStillAllowsLocalOnes`）；③飞行模式下整条转录成功、字节增量为 0。**Ticket 20 要下载模型、本票要把文字发到局域网网关，所以应用必须声明 `INTERNET`；这个权限是按 UID（整个应用）授予的，同一个进程里没有办法让一部分代码有、另一部分没有。前两层从此不可能成立，评审 F1 的结论也是不恢复它们。** 这里把“还剩下什么”写清楚，不夸大：

- **没有了的**：“转录路径在内核层面被禁止联网”。现在没有任何测试、任何构建能证明这一点；之后也不应该再这样描述。
- **还剩下的**（都是我们自己加的约束，不是系统的保证）：
  1. **代码守卫（JVM，E2）**：七个生产模块（`:capture`、`:asr`、`:platform`、`:app`、`:modelmgmt`、`:sync`、`:syncplatform`）里，任何能碰网络的 API——套接字、`java.net.URL` 与各种 HTTP 栈（`HttpURLConnection`、`java.net.http`、OkHttp、Ktor 等）、`WebView`、`DownloadManager`、“查看网址”的意图（`ACTION_VIEW`）、`TrustManager` / `HostnameVerifier` / `SSLContext`——只许出现在 **`NetworkSurface.kt` 里逐个登记的传输类**中。类文件常量池（字节码）与源码两层都扫（源码层保留 `import` 行，所以别名导入与通配导入照样被认出）。登记**以类为单位，不是以包为单位**（评审 F13：此前整个 `sync/http` 包放行，里面随手写一个自己开套接字的类就能过）：同包的兄弟类、同前缀的类、别的模块里的同名类都不算。每条规则有**正对照**——测试里故意写出违规的类，规则必须认得出：不校验指纹的套接字客户端、`WebView`、起了别名的 `HttpURLConnection`、`DownloadManager`、`ACTION_VIEW` 意图、信任所有证书的实现、忽略主机名的写法、明文地址。登记表还有两道自检：每条登记都对应编译出来的类与源文件、并且真的用到了那条规则里的名字（不留“沾包”的多余登记）；引用了网络名字的生产类**恰好**都在登记里。
  2. **清单（JVM，E2）**：`INTERNET` 只出现在 `:syncplatform` 与 `:modelmgmt` 的清单里，应用主清单自己不声明；`android:usesCleartextTraffic="false"` **明确写出**（不靠平台默认值），合并后的清单没有放开明文流量。唯一发“查看网址”意图的地方（许可证链接）收进 `BrowserLink` 一个类，登记在表里。
  3. **实测零字节（设备，`NOT_RUN`）**：`OfflineProofTest` 原有的四个测试不变；新增两个（`everyProductTranscriptionEntryPointSendsNoNetworkBytesEvenWhileTheNetworkIsAvailable`、`everyProductTranscriptionEntryPointWorksInAirplaneModeWithoutAnyNetworkBytes`），由 `ProductTranscriptionHarness` 用**真实的 `AuriRuntime`**（只把模型下载的传输换成“被调用就记账并失败”的实现，模型库用产品自己的布局）把产品的**四个转录入口**各走一遍：协调器封存录音后交给管线（`recordingSealed`）、调度器提交（`submit`）、重新转录（`retranscribe`）、进程重启后的恢复（`resume`）；网络可用与飞行模式下各量一次该 UID 的收发字节，要求零字节、零次下载尝试，且每个入口都产出了 Revision。原来的零字节测试只测了验收用的调试入口 `FileTranscription`，不是产品走的路。这两个测试**只编译过**（`:app:assembleDebugAndroidTest` 成功），**没有运行**：这一轮禁用了模拟器以节省内存。`NOT_RUN`，命令见第 12.4 节。
- **守卫看不见的**（`NetworkSurface.kt` 文件头同样写明）：反射与动态拼出来的类名；sherpa-onnx AAR 里的原生代码（`.so`，守卫读不了，设备上的零字节测试是它唯一的把关）；第三方库内部自己开的连接（CameraX、ZXing 等）。这些只能靠依赖固定表里的版本与哈希、加上上面第 3 条的实测来约束，**不是证明**。
- **“不带 `INTERNET` 的离线构建变体”仍然是开放的**：这是唯一能恢复内核层面保证的办法（录音与转录所在的构建里没有任何联网模块），但它改的是分发与构建形态，不是本票能定的；请在 Ticket 30（隐私与许可审计）之前决定。
- **历史**：Ticket 20/21 当时删掉了 `theInstalledPackageRequestsNoInternetPermission` 与 `theKernelRefusesEveryNetworkSocketButStillAllowsLocalOnes`（它们断言的前提已不存在），换成：已安装的包申请的网络与定位类权限恰好是 `INTERNET` 与 `ACCESS_NETWORK_STATE`（`theInstalledPackageRequestsOnlyTheGatewayLinkNetworkPermissions`）、网络可用时转录零字节（`transcriptionSendsNoNetworkBytesEvenWhileTheNetworkIsAvailable`，以前“零字节”是权限保证的，现在是**测出来的**）、飞行模式那条保持不变；JVM 的 `NoNetworkManifestTest` 改写为“`INTERNET` 只来自联网模块”。`execution/acceptance/asr-android/test_check_instrumentation.py` 里钉住的测试名随之更新；Ticket 14 的 README 里这一项有指向本节的说明，当时的实测数字保留为历史记录。

## 9. 测试的牙齿（变异验证）

“只写会通过的测试”不能证明测试拦得住错误（Ticket 16 的评审教训）。`mutation_check.py` 把“破坏哪条规矩、哪些测试必须因此变红”写成一张表（**253 条**），每条对生产代码**恰好改一处**，跑对应的测试。判定有三种：**预期的那几个测试**真的变红才算 `CAUGHT`；整套测试虽然红了、但红的不是预期的那几个，是 `SURVIVED`（这条规矩没有被钉住，红的是别的测试碰巧拦下的）；一个测试都没跑出来（多半是变异让构建失败）是 `INVALID`。表本身有离线检查（`test_mutation_table.py`，767 个：原文在源码里恰好出现一次、预期变红的测试在源码里真的存在、没有变异碰到测试类，应用再还原后文件字节级不变）——**但它证明不了变异能编译、预期的测试名还对得上，只有真跑才行**：这一轮的整表真跑就抓出了五条表本身的问题（第 12.3 节）。**全部在仓库外的快照（`$TMPDIR/auri-ticket21-mutation-snapshot`，每次调用都重新从工作树同步）里跑，真实工作树一个字节都不会动**；唯一的例外是网络卫士的规则表 `NetworkSurface.kt`（它在 `src/test` 下，不是 `*Test.kt`）——削弱卫士自己的规则也要变红，所以允许变异它，`test_mutation_table.py` 里有一个测试把这个例外钉死。变异能改的生产源码是各模块的 `src/main`，以及 debug / release 变体各自的 `src/debug`、`src/release`（评审 F14），其余测试目录一律不许动。

| 组（按主题） | 条数 | 其中评审这一轮新增 | 覆盖 |
|---|---|---|---|
| 传输与指纹、网关客户端 | 24 | 9 | 比对永远通过 / 只看最后一张证书 / 空证书链 / 不符误报成不可达 / 网络故障误报成不符 / 请求发出后的故障报成“没发出” / TLS 上下文共用 / 回答的不是我们问的那一条 / 响应版本不核对 / 令牌格式不核对 / 令牌与请求头被打印 / **分块长度在 `Int` 里相加溢出、截成 `Int` 再比、不算前面的块、接受正负号与 `0x` 前缀（F3）/ 主机名解析不限时、看门狗从不动手、先关 TLS 再关 TCP、读阶段不受总时限管、取消不关套接字（F8）** |
| 令牌封存与配对记录 | 14 | 0 | 令牌明文落盘 / 封存重用 IV 或忽略用途 / 读不出来当成“从没配过” / 配对记录读改写不互斥 / 可观察的配对状态乱序、不发布、先发布后存盘 |
| 定位与配对服务、设备信息 | 15 | 0 | 地址遍历遇到冒充者就停 / 忘记见过不符 / 重试已试过的地址 / 吞掉取消 / 配对码可能已送到却重发 / 不核对网关 id / 取消时令牌没存下 / mDNS 广告里的网关与主机端口不核对 / 设备信息字段不截断 |
| 引擎、状态机、重试、循环 | 63 | 17 | 不先落盘就发送 / 不确定不先问 / 只比较是否存在不比较哈希 / 永久拒绝被重试 / 连接闸门被忽略或不持久化 / 晚到的旧观察覆盖新配对 / 适配器出故障任务卡在发送中 / 取消被当成故障 / 退避不增长或溢出 / 启动不跑一轮 / 网络恢复不唤醒 / **默认网络换了一个（信号仍是“可用”）被去重吞掉** / 探测不退避 / **网络恢复或换网络时退避不从头开始、旧计时器不重新计时** / 一轮跑在会被取消的协程里 / **新的触发取消正在跑的一轮** / BLOCKED 被放行 / **网关说已删除或来源是别的设备却被当成已入库（F9）、同一版号另一份正文被重发、没有这一版被当成已入库、查无此 Record（404）从此不重发（F10）、旧配对的结果与缓存地址污染新配对（F6）、网络变了不重新武装等待重试的任务（F11）、断网时完成的失败排重试倒计时、离线标记永不清除（F12）、观察者失败后失聪或密集重订阅（F4）** |
| 状态汇总与 submission | 23 | 0 | 三种情形文字混淆 / 需要用户处理被“稍后重试”盖住 / 没配对显示成发送中 / 非蓝牙路由被送出 / 正文被修剪或按字符数限长 / 语音片段不排序不核对 / 猜耳机固件版本 |
| 二维码与取景 | 10 | 0 | 不读反色 / 不转方向 / 不按行距 / 尺寸对不上抛异常 / 帧不关闭或关两次 / 异常溢出分析线程 |
| 守卫、清单、隐私 | 30 | 17 | 主机名校验后门 / 应用壳信任所有证书 / `:capture` 里出现套接字 / 明文 `http://` / 信任类出了 tls 包 / **卫士自己被削弱的各种：规则被删、对模块或类型描述符视而不见、按包放行、源码层不认 `import`、把注释算进去、登记表里多一条空登记、`:modelmgmt` 没被扫（F1/F13）** / **WebView、`DownloadManager`、第二个查看网址的意图、第二套 HTTP 栈、传输类旁边的裸套接字（F1/F13）** / 应用主清单声明 `INTERNET` / 放开明文流量，或不再明写 `usesCleartextTraffic="false"` / `:syncplatform` 与 `:sync` 里出现 `MediaRecorder` 或 `AudioRecord` |
| 视图模型与界面文案 | 24 | 10 | 配对不原子 / 重复帧 / 配对成功后同一张码再次触发 / 失败后码被标成已用 / 意外异常卡在“正在配对” / 结局互相顶替 / 关闭提示无效 / 非配对二维码被悄悄忽略 / 三种情形的话被改混 / **状态流故障冲出视图模型、存储故障状态不置位或不复位、重订阅不退避或不复位、日志里带异常消息、故障时的话要用户动手（F4）** / **release 里出现手动入口的文案、视图模型里的入口回来、release 的空实现画出调试入口（F14）** |
| 叫醒与恢复（F7） | 18 | 18 | 叫醒的判断把已停下或已入库的任务算进去、不看连接要用户处理、没配对也叫醒 / 规划器每次变化都重登记、从不取消、从不登记、系统拒绝后放弃 / 执行器不设时限、不等启动完成、失败当成做完、吞掉取消、永远要求再叫醒、日志里带异常消息 / 清单缺 `RECEIVE_BOOT_COMPLETED`、叫醒服务对外导出或不要求 `BIND_JOB_SERVICE` |
| 备份与换机（F2） | 12 | 12 | `allowBackup` 打开 / 清单缺换机迁移规则或旧版备份规则 / 换机迁移规则没把数据库排除、云备份规则里多了一条 include、旧版备份规则只排除一部分域 / 配对记录、录音、耳机配置放回 `filesDir` / 迁移旧位置的文件时忽略旧数据、覆盖新位置、返回旧位置 |
| 旧库回填（F5） | 9 | 9 | 回填什么都不选 / 不检查已有任务 / 覆盖已有任务 / 只回填每条 Record 的第一版 / 把任务标成已入库或结果不确定 / 创建时间不取发布时间 / 次序交给扫描顺序 / Record 库长出“已删除”这类列而回填没跟着改（哨兵测试要变红） |
| **真网关** | 11 | 0 | 同一批规矩换成对着真网关再破坏一次：比对永远通过、盲目重发、版本冲突被重试、`RECORD_NOT_ORIGIN_DEVICE` 被重试、令牌明文落盘、连接闸门被忽略或不持久化、排队任务不改成等网关、请求发出前不记“不确定”、新地址不被记住、正文被修剪 |

合计 253 条，其中评审这一轮新增 92 条、上一轮留下 161 条（上一轮表里的 `sync-vm-manual-entry-ignores-release-flag` 随 F14 删掉：运行时开关与视图模型入口整个没了，改由 release 变体的三条变异约束）。**这一轮的整表真跑结果（253/253 `CAUGHT`，含五条先暴露出表本身的问题、修正后重跑的）见第 12.3 节。**

（历史，上一轮把真网关组首次加进表时的记录：）真网关组与另外两组当时 28 条全部 `CAUGHT`；真网关组首轮有 7 条没被抓到（`SURVIVED`），其中 5 条暴露的是**测试写得太松**（例如“用户未处理期间不再发请求”只数了已经停下的那条任务，对新任务没有约束），已先加强测试再重跑；1 条锚点选错（改的是“没认出来的 4xx”，而真网关给的是认得的错误码），换成 `RECORD_NOT_ORIGIN_DEVICE` 的分类；1 条对真网关层没有意义（二维码旋转只在相机帧里发生，网关打印的真二维码不需要旋转），已删除。

## 10. 依赖固定表

没有仓库级的 Gradle 依赖校验元数据，所以新增依赖的版本固定在 `apps/android/gradle/libs.versions.toml`，哈希记录在这里（从 Gradle 缓存里的实际文件算出）。

| 依赖 | 版本 | 许可证 | 文件 | SHA-256 | 大小（字节） |
|---|---|---|---|---|---|
| `com.google.zxing:core` | 3.5.4 | Apache-2.0 | `core-3.5.4.jar` | `71de5d89341b5fcf5dd89da7f44e84d825d0e084cdf3ec77c9abe26b0f0ceb13` | 610364 |
| `androidx.camera:camera-core` | 1.4.1 | Apache-2.0 | `camera-core-1.4.1.aar` | `6c77068806cf259bbaa7ed955935bf2cd16dc103feb32f7ae3d55823fc9fcfc7` | 938840 |
| `androidx.camera:camera-camera2` | 1.4.1 | Apache-2.0 | `camera-camera2-1.4.1.aar` | `173253ed32c2f60e77081aab72e61dbcc4e767c9fe56cbb9b88f762b7725e99b` | 488750 |
| `androidx.camera:camera-lifecycle` | 1.4.1 | Apache-2.0 | `camera-lifecycle-1.4.1.aar` | `8324adb2c047905fffbd10a5bd978818157943619b91fcf410de94bd97b619a9` | 38454 |
| `androidx.camera:camera-view` | 1.4.1 | Apache-2.0 | `camera-view-1.4.1.aar` | `e4a1a2d1c11227ebbf58c472a7b8600216003dc155fd0a763f366bb14c698d7e` | 111164 |
| `androidx.camera:camera-video`（`camera-view` 的传递依赖） | 1.4.1 | Apache-2.0 | `camera-video-1.4.1.aar` | `9b1b33b94a9708848df1a2b941f7ad3d8e982b692634a779fc3722ac7236010e` | 384214 |
| `org.xerial:sqlite-jdbc`（**只在 `:syncplatform` 的 JVM 单元测试类路径上，不进任何 APK**：评审 F5，用真 SQLite 引擎跑旧库回填语句） | 3.46.1.3（内含 SQLite 3.46.1） | Apache-2.0 | `sqlite-jdbc-3.46.1.3.jar` | `4a4832720a65eaf7f4d6fd7ede52087b994dc5633c076f9e994dc0c8b4b0b4fa` | 14127503 |

其余依赖（`kotlinx-serialization-json`、`kotlinx-coroutines`、`androidx.test` 等）沿用既有版本，没有新增。没有模型权重、没有真实个人音频入库。

## 11. 已知限制与后续

- **进程被杀之后靠 JobScheduler 叫醒，但有一个限制，而且没有在设备上验证过**（评审 F7）：`NETWORK_TYPE_ANY` 要求系统判定“可以上互联网”的网络，**只有局域网、没有互联网的 Wi-Fi 不满足它**——那种情况下进程被杀之后要等用户再打开 App（启动时第一轮同步）才会接着发；进程活着时由 `SyncLoop` 负责，不受这个限制。叫醒的登记、杀进程后真的被叫醒、重启后保留（`RECEIVE_BOOT_COMPLETED`）都是 `NOT_RUN`（要设备；清单与规划/执行逻辑有 JVM 测试）。
- **网关所在的 Wi-Fi 没有外网、手机又开着移动数据时**，系统可能让蜂窝继续当默认网络，发往局域网地址的连接走蜂窝而连不上：界面显示“等网关”，不会错连（指纹核对照旧），但补传要等到系统把默认网络换回 Wi-Fi。要解决得把套接字绑到 Wi-Fi 网络（`Network.socketFactory`）上，这一版没有做；`AndroidNetworkSignal` 的文件头同样记着。这是真机项，第 4 节第 6 步的第二遍会碰到。
- **同步状态流的故障围堵了（评审 F4），但首页是别的票的写法**：`SyncRuntime.start()` 里读配对记录与任务表的故障只记一条日志（`start.failed <异常类名>`）、循环照常起来；`SyncLoop` 观察任务表与网络的协程失败后按有界退避重新订阅（`loop-observer-*` 变异）；`SyncViewModel` 的状态流用 `retryWhen` 兜住故障（取消与 `Error` 照常上抛），故障不再冲出视图模型的作用域（以前会落到没有异常处理器的应用作用域上，等于崩溃），界面会说“本机存储暂时读不了、会自动重试”并按有界退避重新订阅（`SyncViewModelTest`、`SyncScreenTextTest`）。`HomeViewModel`（Ticket 16，已评审）对 `library.observe()` 仍是没有 `.catch` 的旧写法，我**照既有约定、没有改别的票的代码**；建议跨票统一（给 `AuriRuntime.scope` 与各 ViewModel 的作用域装 `CoroutineExceptionHandler`，或在状态流上加 `.catch`）。
- **界面本身没有截图或界面测试**：`SyncViewModel` 与文案映射有 JVM 测试，Compose 界面只保证编译通过、在模拟器上装得起来；界面会被别人重做，所以没有为它投入。
- **真实相机扫码、真实 mDNS 广播发现、真实局域网**都是 `NOT_RUN`（模拟器没有摄像头，网络与宿主机隔着 NAT）；JVM 里的发现用替身，模拟器里的 `NsdManager` 只验证了“能注册、能注销、没有广告时按时返回空”。
- **故障注入不在 TLS 记录层**：“响应丢了”和“请求没送到”是在 `HttpExchange` 边界注入的，包着真实的 `PinnedHttpsClient` 与真网关；在 TLS 记录层切断连接要改动客户端或网关，脆弱而且难复现，所以没做。独立证据是 TCP 转发器的连接计数与网关数据库。
- **总时限（评审 F8）的覆盖范围**：解析卡住、握手卡住、写卡住、读时一点一点滴数据，都有测试证明“总时限一到就返回”（还有协程取消时立刻返回）；**TCP 连接阶段卡住（对着一个不回包的地址）没有测试**：回环地址上造不出不回 SYN 的对端。代码上 `connect` 有自己的 `connectMs` 上限，看门狗到点也会关掉那个套接字，但这条路径没有被测过。读阶段的 `applyDeadline` 与看门狗是两道互相冗余的防线（从外面看不出区别，所以变异表里只有“两道一起拿掉”的一条）。解析线程池是无界的守护线程池（代码里有 `shortcut:` 注释）：解析器卡住几分钟又被高频调用时线程会堆积，请求频率受同步退避约束，真遇到再换有界池。
- **备份与换机规则（评审 F2）只检查了清单、规则文件与代码里的存放位置**：真实的 `adb shell bmgr` 备份、设备到设备迁移没有在设备上跑过（`NOT_RUN`）；规则文件有带正负对照的检查器（`BackupRulesTest`），“不再把敏感文件放回 `filesDir`”有源码守卫（`StorageLocationGuardTest`）。
- **旧库回填（评审 F5）**：语句在真 SQLite 引擎（sqlite-jdbc，仅测试）上验证过，包括幂等、不动已有任务、发布时间排序；**在设备上打开一个真实的旧版本数据库再同步**（`UpgradeThenSyncTest`）与**发布事务的仪器测试**只编译过，`NOT_RUN`。**加墓碑的那个 Ticket（Ticket 23）必须在回填语句里排除已删除的 Record**，否则每次打开都会把它们的任务复活：现在库里没有墓碑列，`SyncJobsBackfillTest` 把两张表的列固定住，库里长出“已删除”这类列时哨兵测试会变红（变异 `library-gains-deleted-column-backfill-unaware`）。
- **网关侧没有请求计数**（G10）：“不重发”靠手机侧的请求记录。
- 配对记录读不出来（密钥丢失）时只能重新配对；没有令牌轮换或续期，合同里也没有。
- **没有处理删除**：Record 的删除与墓碑是 Ticket 23；`RECORD_DELETED` 的映射有单元测试，但没有真网关测试（网关的删除端点本分支尚未提供）。
- `CLAUDE.md` “命令”一节里别的票（Ticket 16、20）那几行写的测试数（`71 + 56 + 51 + 112`、`158 + 71 + 108 + 66 + 112`）是它们各自当时的数字，合并 Ticket 17 与 Ticket 20 又加了不少测试，现在的数字见 12.2；那是别的票的行，我没有改它们，只在“命令”里补了本票那一段，并改了 Ticket 20 那一行里已经不成立的“全应用唯一声明 `INTERNET`”。

## 12. 结果

### 12.0 这一轮是什么（2026-11-19）

这一轮是对本票**独立评审 F1–F14 的修复**，接在 12.7 记录的 `23afdff` / `4be0524` 那一轮之后。本机是 16 GB 的 Apple silicon Mac（JDK 17、Gradle 8.10.2、Android SDK 35），分支 `ticket/21-pair-and-sync`。开工前合并了 `origin/integration/auri-v2`（Ticket 17，`e94d523`，合并提交 `b80177b`；Ticket 20 在更早的 `0c17c20` 已经合并过）；收尾时 `git fetch` 之后它仍在 `e94d523`、是本分支的祖先，没有新的合并要做。按指示做的事与没做的事：

- **没有启动 Android 模拟器、AVD、qemu、Android Studio、DevEco Studio、Xcode 或任何模拟器，没有运行任何仪器测试（`connectedAndroidTest`）。** 需要设备的检查一律记 **`NOT_RUN`（原因：推迟，为省内存禁用了模拟器）**，要跑的命令在 12.4；仪器测试源码可以写、可以改，但只编译。**这也意味着设备端的新测试没有机会“先红再绿”**：F1 的两个零字节测试、F5 的 `UpgradeThenSyncTest` 与改写的 `SyncPublicationTransactionTest`、F7 的 `ResumeSchedulingTest` 都只经过了编译，没有任何一次运行（连红都没见过）；这几条的先红证据只来自它们对应的 JVM 测试。
- **内存守卫**：每个 Gradle / pytest 调用前后各记一次空闲内存百分比与交换区用量（12.2 的表里是这些读数）；空闲低于 25 % 或交换区一分钟涨过 500 MB 就等（`sleep 120`，最多 15 分钟；交换区高但稳定不算理由）；一次只跑一个 Gradle 或 pytest；Gradle 一律带 `--no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2`；**从不执行 `./gradlew --stop`**，每个套件跑完就退出我自己起的守护进程；变异检查只在仓库外的快照里改源码。守卫在整个过程中**只触发过一次**（12:39，交换区增速 529 MB/min，等了一轮 120 秒）；全部读数里空闲内存最低 27 %（12:48，设备端测试编译前，当时交换区增速 223 MB/min，低于 500 MB/min 的线）。
- **TDD**：每条发现先写一个能复现它的测试，看着它因为该有的原因变红，再修，测试留下；**例外与偏差都写在 12.1 的“先红”一栏里**（F3 的第一次红有三条是我自己的测试辅助函数的问题；F8 有一条测试在红的一轮里就被证明造不出该有的故障，已删掉）。**没有削弱、删除或跳过任何既有测试**；唯一改写既有测试的地方是 Ticket 14 内核层面的离线测试（F1，第 8 节）与 Ticket 20 的 `NetworkIsolationTest`（放宽登记的联网模块，12.6）。没有改 `contracts/`、`services/gateway/`、`tests/contract/`、`.github/`（`git diff --name-only` 相对集成分支与相对上一轮的 `379ab9f` 都核对过，为空）。
- 提交按发现分开：F3 `cecf2f0`、F8 `e45c87f`、F9/F10 `3ae610f`、F6 `8bab804`、F11/F12/F4 `e1aff15`、F7 `9f7827a`、F2 `7b10aad`、F5 `c254385`、F14 `44f6c5b`、F1/F13 `da877bc`、整表真跑之后的变异表修正 `6ed1a02`，最后是本说明文件与 `CLAUDE.md` 的提交。**生产代码与测试代码的最终版本就是 `da877bc`**；`6ed1a02` 起只改了变异表和文档（`git diff --stat da877bc HEAD -- apps services contracts tests .github` 为空）。

### 12.1 逐条发现（F1–F14）

“先红”一栏是修复**之前**真实跑出来的失败（`:sync:test`、`:app:testDebugUnitTest`、`:platform:testDebugUnitTest`、`:syncplatform:testDebugUnitTest`，红的原因是断言失败或预期的异常，不是编译错误）；“绿”一栏是 12.2 里**最终新鲜重跑**的结果。

| # | 发现 | 先红 | 修复（文件 · 一句话） | 绿 | 证据 |
|---|---|---|---|---|---|
| F1 | 应用必须声明 `INTERNET`，Ticket 14 内核层面的“没有联网权限”离线证明没有了而且无法恢复；要把还剩什么写清楚、换成能检查的替代，并且让零字节测试走产品真正走的转录路径 | `NoNetworkManifestTest` 1 条（没有明写 `usesCleartextTraffic`）+ `NoNetworkCodeGuardTest` 4 条（`:modelmgmt` 没被扫到；认不出 `WebView`、查看网址的意图、`DownloadManager`），与 F13 的 2 条一起：28 个里 7 个红。**设备端两个新测试没有先红（不能运行）** | `NetworkSurface.kt`（逐类登记表与规则）、`NoNetworkCodeGuardTest.kt`（七个生产模块、字节码与源码两层、每条规则有正对照）、`AndroidManifest.xml`（`usesCleartextTraffic="false"`）、`BrowserLink.kt`（唯一发 `ACTION_VIEW` 的类）；设备端 `ProductTranscriptionHarness.kt` 与 `OfflineProofTest` 两个新测试，用真实的 `AuriRuntime` 把产品的四个转录入口各量一次零字节。**第 8 节写明“没有了的”“还剩下的”“守卫看不见的”** | `NoNetworkCodeGuardTest` 26、`NoNetworkManifestTest` 6 全过（`:app` 177/177） | JVM E2；**设备零字节 `NOT_RUN`**（只编译） |
| F2 | 配对记录（设备令牌）、录音、耳机配置会被备份与设备迁移带走（只有 `allowBackup=false` 拦不住 Android 12+ 的设备到设备迁移） | `NoBackupStorageTest` 5 个里 4 个红（敏感文件的位置不在 `noBackup` 之下；旧位置的文件与整个录音目录不迁移；新位置已有时不能互相顶替）；`BackupRulesTest` + `StorageLocationGuardTest` 10 个里 5 个红（两份规则文件不存在 → `FileNotFoundException`；合并后的清单没有声明规则；三个敏感文件没有都经 `NoBackupStorage` 放置；生产源码里还有没登记的 `filesDir` 用法） | `NoBackupStorage.kt`（`:platform`：旧位置的文件首次访问时原样改名挪过来，新位置已有就不动）、`AuriRuntime.kt` 与 `SyncRuntime.kt` 经它放置；`data_extraction_rules.xml` 与 `backup_rules.xml`（每个域整个排除、不写任何 include）；清单声明两份规则 | `NoBackupStorageTest` 5、`BackupRulesTest` 8、`StorageLocationGuardTest` 2 全过 | JVM E2；**真实 `bmgr` 备份与设备迁移 `NOT_RUN`** |
| F3 | 分块响应：一个字节的块之后声称 `7fffffff` 字节，已读与块大小在 `Int` 里相加溢出成负数，越过响应上限去申请 2 GB 数组 | `PinnedHttpsClientTest` 21 个里 3 个红：`7fffffff` 那条抛 `OutOfMemoryError`（`PinnedHttpsClient.kt:196`，真的去分配了）；`+5` 一类带符号或前缀的块大小被接受；格式正确但超限的块大小被误报成“块大小畸形”而不是“响应过大”。**第一次跑是 6 个红，其中 3 个是我的测试辅助函数没有顺着因果链找 `IOException`、不是产品的原因；改好辅助函数重跑才是这三个** | `PinnedHttpsClient.kt`：声明的块大小与剩余额度用 `Long` 比较、先比后分配；块大小只认 1–16 位十六进制数字，超出 `Long` 是畸形 | `PinnedHttpsClientTest` 25 全过 | JVM E2 |
| F4 | 同步循环的观察者出故障后永远失聪；界面的状态流故障冲出视图模型（进程崩溃） | `SyncLoopTest` 26 个里 3 个红（任务表的变化信号暂时出故障后不再订阅、新任务不再自动发；网络信号出故障后不再订阅、网络回来的事件不再触发补传；一直出故障时重订阅不退避、会密集空转）；`SyncViewModelTest` 17 个里 3 个红（读存储出故障时异常冲出视图模型的作用域、界面不进入“存储故障”状态；不按有界退避重新订阅、恢复后状态不复位；日志里带出异常消息） | `SyncLoop.kt`（观察任务表信号与网络信号的协程出故障后只记不含内容的日志、按有界退避重新订阅；`9f7827a` 起抽成共用的 `RestartingProducer.kt`）、`SyncViewModel.kt`（状态流用 `retryWhen` 兜住故障、`storageProblem` 标志、有界退避重订阅、日志只记异常类名）、`SyncScreen.kt`（“本机存储暂时读不了、会自动重试”） | `SyncLoopTest` 27、`SyncViewModelTest` 17、`SyncScreenTextTest` 7 全过 | JVM E2 |
| F5 | 升级前（Ticket 16–20）已发布的 Revision 没有同步任务，永远不会被送出去 | `:syncplatform` 9 个里 5 个红，都是 `SyncJobsBackfillTest`（真 SQLite 引擎 sqlite-jdbc，仅测试）：升级后旧库里已发布的每一版都得不到任务（时间应取发布时间）；库里有 Revision 没任务时下一次打开补不上；进程死在建表与回填之间后补不齐；再打开一次的幂等（不重复、不重置已有任务的进度）；发布时间完全一样时的确定次序。**设备端 `UpgradeThenSyncTest` 与改写的 `SyncPublicationTransactionTest` 没有先红（不能运行）** | `SqliteRecordLibrary.kt`（`extraSchema` 每次打开在同一个事务里执行）、`SqliteSyncJobStore.kt`（幂等回填：`INSERT … WHERE NOT EXISTS`，先发布的先同步，时间取发布时间，已有任务不动）；依赖表多一行 sqlite-jdbc（只在测试类路径上） | `SyncJobsBackfillTest` 7、`SqliteJdbcPinTest` 2 全过（`:syncplatform` 19/19） | JVM E2；**设备端 `UpgradeThenSyncTest` 与改写的发布事务测试只编译，`NOT_RUN`** |
| F6 | 请求在途时用户重新配对：旧令牌的 401 / 旧公钥不符的结论把任务永远停住；一轮里缓存的旧网关地址被拿去配新网关的指纹，误报“公钥不符” | `SyncEngineTest` 43 个里 4 个红（发送途中改配了另一个网关，旧网关回的“已入库”被算成新网关的送达；一轮里缓存的旧网关地址配了新指纹；旧公钥不符的结论仍然生效；旧令牌被拒的 401 把任务永远停住） | `SyncEngine.kt`：请求结果只在配对没变时有效（`stillPaired`；作废后 `stale`：立刻重新排期、次数清零、仍记着结果不确定），“连接要用户处理”经 `PairingStore.update` 原子决定，一轮里缓存的网关位置按配对分开 | `SyncEngineTest` 46 全过 | JVM E2 |
| F7 | 进程被系统杀掉后，没送出的任务要等用户再打开 App 才继续 | `ResumePlannerTest` + `ResumeRunnerTest` 16 个里 15 个红（决策表、登记与取消、结论不变不重复登记、系统拒绝后的退避、执行器的时限与收尾判断、意外与取消的处置）；`ResumeManifestTest` + `NoNetworkManifestTest` 8 个里 2 个红（缺 `RECEIVE_BOOT_COMPLETED`；叫醒服务没声明为不导出且只允许系统绑定）。**设备端 `ResumeSchedulingTest` 没有先红（不能运行）** | `ResumePlanner.kt`（观察配对记录与任务表：已配对、连接可用、有没入库也没被永久拒绝的任务才需要叫醒，结论变了才登记或取消，系统拒绝后退避再试，复用 `restartingProducer`）、`ResumeRunner.kt`（被叫醒后先等启动、跑一轮、限时 5 分钟、问还要不要再叫醒）、`ResumeScheduler.kt`（接口）——都在 `:sync`，纯逻辑；`JobSchedulerResume.kt` 与 `SyncResumeService.kt`（`:syncplatform`：跨重启保留、要求有网络的 JobScheduler 任务 2101，没有新增依赖）；清单加 `RECEIVE_BOOT_COMPLETED`、服务不导出且要求 `BIND_JOB_SERVICE` | `ResumePlannerTest` 11、`ResumeRunnerTest` 6、`ResumeManifestTest` 2 全过 | JVM E2；**JobScheduler 真登记、杀进程后被叫醒、重启后保留 `NOT_RUN`**；限制见第 11 节 |
| F8 | 总时限只覆盖读响应；解析、连接、握手、写请求都可能一直卡住 | `PinnedHttpsClientTest` 26 个里 4 个红，红的一轮因看门狗缺失跑了 1 分 38 秒：解析卡住（要等到解析器自己放行才返回，约 10 秒）、握手卡住（对端接受了连接却不回 ServerHello）、握手后对端不收数据使写阻塞（要等到对端睡醒，约 60 秒）三条是为该有的原因红；第四条“对端监听着却从不接受”在 macOS 上 1 毫秒就被拒绝、根本造不出卡住，**不是为该有的原因红，我把它删了**，所以最终是 25 个 | `PinnedHttpsClient.kt`：一个截止时刻管整个往返，看门狗到点关掉底层套接字（先关 TCP 再关 TLS 层，反过来会陪着卡住的写一起卡），解析放在限时的等待里 | `PinnedHttpsClientTest` 25 全过（回环上造不出的“连接阶段不回 SYN”**没有测试**，见第 11 节） | JVM E2 |
| F9 | 结果不确定时，网关说 Record 已删除或来源是别的设备，只要哈希相同就被当成“已入库” | `SyncEngineTest` 39 个里 3 个红（已删除且哈希相同仍确认入库；别的设备录的、哈希相同仍确认入库；已删除且没有这一版仍去提交） | `SyncEngine.kt`：先对网关的状态回答分类（`SAME_STORED / MISSING / CONFLICTING / DELETED / FOREIGN`），已删除 → `BLOCKED(RECORD_DELETED)`，别的设备 → `BLOCKED(NOT_ORIGIN_DEVICE)`，都不再提交 | `SyncEngineTest` 46 全过 | JVM E2 |
| F10 | 同一版本号在网关上是另一份正文时，手机仍会再 PUT 一次 | `SyncEngineTest`（同一次红）1 个红：同一版号另一份正文仍再提交、没有直接停下 | 同一处分类：哈希不同 → `BLOCKED(VERSION_CONFLICT)` 且不 PUT；同哈希才确认；没有这一版才重发。**合同缺口 G12**（第 7 节）：没有客户端生成的操作编号，冲突时只能保守地停下 | 同上，另有“查无此 Record（404）等于没入库”“别的版本唯独没有这一版”两条对照 | JVM E2 |
| F11 | 网络变了，等待重试的任务仍按旧的退避干等（最长 15 分钟） | `SyncLoopTest` 2 个红（默认网络换了一个：等待重试的任务不再干等旧的重试时间；网络变了之后再失败：退避从头算，已持久化的次数清零） | `SyncEngine.networkChanged()`（等待重试的任务重新武装：`RETRY_DUE`、次数清零、保留“不确定”）与 `SyncLoop.kt` 对它的调用 | `SyncLoopTest` 27、`SyncEngineTest` 46 全过 | JVM E2 |
| F12 | 网络已知断了时完成的失败（发送途中被中断）被排成重试倒计时，而不是“等网关” | `SyncEngineTest` 1 个红 + `SyncLoopTest` 1 个红（发送途中网络没了、请求随之被中断：应按等待网关处理并保持结果不确定，网络一回来先问再补传） | `SyncEngine.kt`：引擎记着“网络已知断了”（`networkLost` 置位、`networkChanged` 清除），这时完成的可重试失败进 `WAITING_GATEWAY`、不排倒计时 | 同上 | JVM E2 |
| F13 | 网络守卫的白名单整个 `sync/http` 包放行：包里随手写一个自己开套接字的类也能过 | `NoNetworkCodeGuardTest` 2 个红（“登记的是具体的类：同包的兄弟类、同名前缀的类、别的模块里的同名类都不算”；“未登记的类自己开套接字：放在 `sync` 的 `http` 包里也一样”） | 随 F1：登记以类为单位；登记表自检（每条登记对应真实的类与源文件、真的用到了规则里的名字，引用了网络名字的生产类恰好都在登记里） | 同 F1 | JVM E2 |
| F14 | “手动输入配对载荷”只靠运行时的 `FLAG_DEBUGGABLE` 藏起来，release 包里仍带着界面、文案与入口 | `ReleaseVariantTest` 3 个里 2 个红（release 编译出的类里仍有入口、界面与文案；运行时开关与视图模型里的专用入口在 debug 版里也还在） | `src/debug` 里的 `ManualPairingEntry`（真实现）与 `src/release` 里的空实现；视图模型里专门给它开的入口与读 `FLAG_DEBUGGABLE` 的运行时开关整个删掉（调试入口复用扫码用的 `onQrText`）；`:app:testDebugUnitTest` 先编译 release 变体并把它的类声明为输入。当时在 `44f6c5b` 上实测 release APK 的 dex 里搜这些文案与入口名命中 0，debug APK 里能搜到 | `ReleaseVariantTest` 3 全过 | JVM E2（dex 搜索是 `44f6c5b` 时手工做的一次，没有在最终提交上重复） |

### 12.2 各套件（新鲜重跑，2026-11-19 16:24–16:52，生产与测试代码是 `da877bc`）

Gradle 一律在 `apps/android/` 下执行，带 `--no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2`，测试任务带 `--rerun`；Python 的套件带 `AURI_GRADLE_ARGS`（同样的参数）。每个模块的测试数是**该 Gradle 任务一结束就**从模块的 JUnit XML 加起来的，并核对每个 XML 的时间戳都晚于本轮开始；之后 Python 验收工具里真实调用的 Gradle（`android-capture` 与 `asr-android` 的合同输出校验只跑 `ContractOutputTest`）又覆盖了 `:capture` 与 `:asr` 的 XML，所以这两个目录里现在只剩 3 个与 9 个，**以下表里的数字为准**。内存读数是该套件调用**前 → 后**的“空闲 %，交换区已用 MB”；行的顺序就是实际跑的顺序（最后一行的强制重编译是在两个 pytest 之后跑的）。

| 套件 | 命令 | 结果 | 内存（前 → 后） |
|---|---|---|---|
| `:capture` | `:capture:test --rerun` | **330 个全过**，0 失败 0 错误 0 跳过 | 63 % → 54 %，3643 → 3643 MB |
| `:modelmgmt` | `:modelmgmt:testDebugUnitTest --rerun` | **84** 全过 | 60 % → 58 %，3643 → 3643 MB |
| `:platform` | `:platform:testDebugUnitTest --rerun` | **182** 全过 | 61 % → 63 %，3643 → 3643 MB |
| `:app` | `:app:testDebugUnitTest --rerun`（含 release 变体的类扫描） | **177** 全过 | 65 % → 64 %，3643 → 3643 MB |
| `:asr` | `:asr:testDebugUnitTest --rerun` | **112** 全过 | 64 % → 54 %，3643 → 3643 MB |
| `:sync` | `:sync:test --rerun` | **267** 全过 | 59 % → 62 %，3643 → 3619 MB |
| `:syncplatform` | `:syncplatform:testDebugUnitTest --rerun` | **19** 全过 | 64 % → 64 %，3619 → 3619 MB |
| 对着真 Python 网关 | `:sync:realGatewayTest --rerun`（仓库 `.venv`、自签 TLS、临时数据目录、随机端口） | **12 个全过** | 66 % → 66 %，3619 → 3619 MB |
| 构建 APK | `:app:assembleDebug` | 成功（18 s，117 个任务里 5 个真正执行） | 66 % → 65 %，3619 → 3619 MB |
| 三个模块的仪器测试 APK | `:app:assembleDebugAndroidTest :syncplatform:assembleDebugAndroidTest :platform:assembleDebugAndroidTest` | 成功（3 分 22 秒；186 个任务里 2 个真正执行，其余 `UP-TO-DATE`） | 46 % → 57 %，3611 → 4365 MB（交换区这一次涨了 754 MB，约 215 MB/min，低于 500 MB/min 的线） |
| Python 验收工具（三个目录一起） | `AURI_GRADLE_ARGS='--no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2' .venv/bin/pytest execution/acceptance/android-sync execution/acceptance/android-capture execution/acceptance/asr-android -rs` | **1002 个全过，没有跳过**：android-sync 812（767 个变异表自检 + 12 个 `test_check_sync_instrumentation` + 6 个 `test_real_gateway_host` + 8 个 `test_real_gateway_suite`（真实调用 Gradle 跑 `:sync:realGatewayTest` 并逐个核对）+ 19 个 `test_sync_run_emulator_acceptance`）、android-capture 59、asr-android 131；7 分 19 秒。其中模拟器验收脚本的 19 个测试只用假的 adb / emulator 可执行文件，**没有启动任何模拟器** | 60 % → 48 %，4365 → 4221 MB |
| 全仓库 | `.venv/bin/pytest tests/contract tests/integration tests/security -rs` | **492 个全过，没有跳过**；7 分 11 秒 | 43 % → 47 %，4213 → 4180 MB |
| **强制重编译**设备端测试源码 | `:app:compileDebugKotlin --rerun :app:compileDebugAndroidTestKotlin --rerun :syncplatform:compileDebugAndroidTestKotlin --rerun :platform:compileDebugAndroidTestKotlin --rerun`，再打包三个仪器测试 APK | 成功（47 秒，186 个任务里 6 个真正执行）——“只编译过”的证据不是靠 `UP-TO-DATE` | 43 % → 45 %，4180 → 4172 MB |

JVM 单测合计（不含真网关的 12 个）**1171 个，全部通过**（330 + 84 + 182 + 177 + 112 + 267 + 19）。变异表的离线自检在最后一次改表之后单独跑过：767 个全过（16:17 → 16:18，41 % → 48 %，3651 → 3651 MB），它也包含在上面 Python 验收的 812 里。

### 12.3 变异检查整表真跑（253/253 `CAUGHT`）

**为什么整表重跑**：评审修复新增了 92 条变异、改写了若干条原有的，而表的离线自检（767 个）只能证明“原文恰好出现一次、预期变红的测试名存在”，证明不了变异能编译、预期的测试真的会变红——所以整表在仓库外的快照（`$TMPDIR/auri-ticket21-mutation-snapshot`，每次调用都重新从工作树同步；真实工作树一个字节没动）里逐条真跑。

**过程与结果**（全部在 `da877bc` 的树上；之后工作树里只有变异表与文档变过）：

1. **先单独跑 33 条**：F1/F13 相关的 24 条（其中 17 条新增，其余 7 条是原有的守卫条目，因为守卫被改写而必须重跑）加 F3/F8 的 9 条（全是新增）：**33/33 `CAUGHT`**。F3/F8 的几条证明了：`Int` 相加溢出、截成 `Int` 再比、不算前面的块、接受正负号与前缀；解析不限时、看门狗从不动手、先关 TLS 再关 TCP（只有“对端不收数据”的写阻塞测试能抓到）、读阶段不受总时限管、取消不关套接字，都会被测试抓住。
2. **其余 219 条按 8 条一组**（当时表里共 252 条；第 253 条 `engine-never-resends-after-404` 是跑完之后才加的，见第 3 点。最后三组是真网关的，每组 4、4、3 条）跑，每组前后有内存守卫：14:10–16:10，约 2 小时。结果：**214 条 `CAUGHT`，3 条 `SURVIVED`，2 条 `INVALID`**。
3. 这 5 条**都是变异表本身的问题，没有一条是测试被削弱**：
   - `engine-confirms-missing-revision`（`SURVIVED`）：预期变红的“404 等于没入库”其实不走 `Standing.MISSING`（404 加 `REQUEST_INVALID` 走 `Rejected` 分支再落到 PUT），变异下真红的是“网关上有这条 Record 的别的版本、唯独没有这一版”。预期改成后者；同时发现**没有任何变异守着“404 → 重发”这条路**，新增 `engine-never-resends-after-404`（把 404 也当网关出错、从此不重发）：8 个测试变红，包括“网关里查不到这条 Record（404）等于没入库，再提交”。
   - `engine-new-pairing-overwritten-by-old-observation`（`SURVIVED`）：预期红的名字前缀同时匹配 3 个测试；F6 之后“旧观察晚到”的场景被 `stillPaired` 先拦住，不再碰 `PairingStore.update` 里那道原子检查（它才是防竞态的那一道）。真守着它的是“找网关的途中用户重新配对了，旧公钥的探测这时才报公钥不符”（1 个红），预期改成它。
   - `loop-ignores-network-change-for-scheduled-jobs`（`SURVIVED`）：F12 的“发送途中断了网”走成功路径、从不读“离线”记忆，变异下本来就绿；预期改成真会红的“默认网络换了一个：等待重试的任务不再干等旧的重试时间”与“网络回来之后：失败又按正常的重试倒计时算”（重跑时 3 个红）。
   - `manifest-allows-cleartext-traffic`（`INVALID`，一个测试也没跑）：F1 之后清单里已明确写着 `usesCleartextTraffic="false"`，旧变异再加一个同名属性，清单不是合法 XML，构建失败。改成把 `false` 改成 `true`，`CAUGHT`（1 个红）。**这一条离线自检看不出来**——表自检只检查原文能不能替换。
   - `real-gw-pending-not-parked`（`INVALID`）：Gradle 的守护进程在跑到一半时被外部请求停掉（日志：`daemon stop has been requested` 与 `Gradle build daemon disappeared unexpectedly`），是共用机器上别人的 `./gradlew --stop`，与变异无关；原样重跑，`CAUGHT`（1 个红）。
4. 改表之后（`6ed1a02`）把这 5 条加上新增的 `engine-never-resends-after-404` 一起重跑：**6/6 `CAUGHT`**，红的测试正是预期的那几个。

**终局**：表里 **253 条，253 条 `CAUGHT`，0 条 `SURVIVED`，0 条 `INVALID`**（33 + 214 + 6），逐条核对过每条的最后一次结果。其中评审这一轮新增 92 条、上一轮留下 161 条（`sync-vm-manual-entry-ignores-release-flag` 随 F14 删掉）。证据等级 E2。整表变异检查的内存读数：每组前后各一次、共 60 个，空闲内存最低 36 %，交换区已用 3.3–5.9 GB、没有增长；逐条跑的前 33 条里最低 37 %。

### 12.4 `NOT_RUN`：要设备才能证明的，与以后要跑的命令

原因都是**推迟：这一轮按指示禁用了模拟器以节省内存**（真机、真局域网的项原因是本机没有手机，见第 4 节）。下面的检查对应的测试源码都在，**只编译过**（12.2），一次也没有运行。

| 项 | 对应的测试 / 观察 | 以后要跑的命令 |
|---|---|---|
| F1：产品转录入口零字节（四个入口，网络可用与飞行模式各一次）；`OfflineProofTest` 其余 4 个 | `OfflineProofTest` 共 6 个（新增 `everyProductTranscriptionEntryPointSendsNoNetworkBytesEvenWhileTheNetworkIsAvailable`、`everyProductTranscriptionEntryPointWorksInAirplaneModeWithoutAnyNetworkBytes`），要真模型 | `export ADB_LIBUSB=1 && .venv/bin/python execution/acceptance/asr-android/prepare_model.py && AURI_ALSO_ASR_ACCEPTANCE=1 execution/acceptance/android-sync/run_emulator_acceptance.sh`（同一台一次性模拟器上先跑本票、再跑 Ticket 14 的 `:app` 仪器测试） |
| F5：旧版本数据库升级后第一轮同步送出旧版本发布的每一版；改写的发布事务测试 | `UpgradeThenSyncTest`、`SyncPublicationTransactionTest`（`:syncplatform` 仪器测试） | 同上（脚本会跑 `:syncplatform` 的全部仪器测试，并以源码为准逐个核对每个 `@Test` 都真的跑了） |
| F7：JobScheduler 的真登记、不重复登记、取消；叫醒服务只允许系统绑定 | `ResumeSchedulingTest`（4 个，`:syncplatform` 仪器测试） | 同上 |
| F7：杀进程后任务仍被叫醒并发出；重启后叫醒仍在 | 没有自动化测试，要设备 | 手机上造一条没送出的任务（断开网关），然后：`adb shell dumpsys jobscheduler \| grep app.auri.android`（应看到 job 2101：要求网络、持久化）→ `adb shell am force-stop app.auri.android` → 让网关回来，看任务是否送达、`adb logcat -d -s AuriSync:I`；再 `adb reboot` 后重复 `dumpsys jobscheduler` |
| F2：真实备份与设备迁移 | 没有自动化测试，要设备 | `adb shell bmgr list transports` 选一个传输（本地传输：`adb shell bmgr transport com.android.localtransport/.LocalTransport`），`adb shell bmgr backupnow app.auri.android`，再卸载重装后 `adb shell bmgr restore app.auri.android`：配对记录、录音、耳机配置、Record 库一个都不该回来；`adb shell run-as app.auri.android ls -R no_backup databases files` 核对敏感文件只在 `no_backup/` 下。设备到设备迁移（Android 12+）要两部手机，只能人工做，原样记录 |
| Ticket 16/20 的 `:platform` 仪器测试在本票改过的 `SqliteRecordLibrary`（F5 的 `extraSchema` 事务）上的表现 | `:platform` 仪器测试（含 Record 库的设备端测试） | `execution/acceptance/android-capture/run_emulator_acceptance.sh` |
| 真机：扫真二维码、真局域网入库与补传、真 mDNS、真网关换密钥与吊销 | 第 4 节全部步骤 | 第 4 节（需要一部 Android 手机与 FreeClip） |

### 12.5 没做的事与已知偏差

- **没有运行 Codex 评审。** 用户规则要求规范任务完成后用 codex 评审，但这次派发明确禁止子 agent 与帮手、要求单 agent 独自完成，两条冲突，按派发指示办；这里的替代是整表真跑的变异检查（253/253）与真网关测试，不能算同一件事。**没有使用 mcp-feedback-enhanced**（本会话没有加载这个工具）。
- **token 预算被远远超过**（规则：单任务 4000、单会话 30000）：14 条发现、逐条先红后绿、整表 253 条变异的真跑本身就是这个规模，会话经过多次摘要续接；不是悄悄超支。
- **TLS 测试夹具的临时目录没有清干净**：`TlsFixtures` 用 `deleteOnExit` 删非空目录，删不掉，每跑一次 JVM 测试在 `$TMPDIR` 里留一个 `auri-tls-fixtures*`（Ticket 14 的测试也有同类的 `auri-cfg*`）。我没有改它们（别的票的夹具），收尾时手工删掉了这一轮留下的。
- **F8 连接阶段卡住没有测试**（回环地址上造不出不回 SYN 的对端；我试过“监听却不接受”的写法，macOS 上 1 毫秒就被拒绝），代码上 `connect` 有 `connectMs` 上限且看门狗到点也会关套接字，但这条路径没有被测过；读阶段的 `applyDeadline` 与看门狗互相冗余，变异表里只有“两道一起拿掉”的一条。
- **F7 的限制**：`NETWORK_TYPE_ANY` 要求系统判定“可以上互联网”的网络，没有互联网的纯局域网 Wi-Fi 不满足；这种情况下进程被杀后要等用户再打开 App（启动时第一轮同步）。进程活着时不受影响。见第 11 节。
- **`da877bc` 的提交信息写成“F1/F13 十九条”，实际是 17 条新增加 2 条改写**（已经推送，没有强推改写）：以本节与第 9 节的数字为准。
- 评审指出的 Ticket 14 内核层面离线测试按 F1 的结论**不恢复**，也**没有做“不带 `INTERNET` 的离线构建变体”**（第 8 节：这是分发与构建形态的决定，要在 Ticket 30 之前定）。
- G12（合同没有客户端生成的稳定操作编号）只记在第 7 节，**没有改合同与网关**。
- 变异表整表真跑要约 2 小时，之后没有再整表重跑：`6ed1a02` 之后只改了变异表里 5 条加 1 条，这 6 条已单独重跑；其余 247 条的结果就是上面那次整表的结果（代码与测试在 `da877bc` 之后没有变过）。

### 12.6 对既有文件的改动（共享文件，给合并的人）

相对 `origin/integration/auri-v2`（`e94d523`）。本票自己的模块（`:sync`、`:syncplatform`）、自己的测试与验收工具不在表里。没有动 `contracts/`、`services/gateway/`、`tests/`、`.github/`、`.gitignore`、`CaptureCoordinator.kt`、`HomeViewModel.kt`。

| 文件 | 改了什么 |
|---|---|
| `CLAUDE.md` | +15/−1，只动“命令”一节：Ticket 20 那一行的“唯一声明 `INTERNET`”改成与 `:syncplatform` 并列；新增本票的命令段（含评审修复后的测试数、`--rerun`、`AURI_GRADLE_ARGS`、不要用 `./gradlew --stop`） |
| `apps/android/settings.gradle.kts` | +2：`include(":sync")`、`include(":syncplatform")` |
| `apps/android/gradle/libs.versions.toml` | +14：ZXing 3.5.4、CameraX 1.4.1、sqlite-jdbc 3.46.1.3（只在 `:syncplatform` 的 JVM 测试类路径上，不进 APK）；哈希见第 10 节 |
| `apps/android/app/build.gradle.kts` | +6：`implementation(project(":syncplatform"))`；`testDebugUnitTest` 先编译 release 变体并把它的类声明为输入（F14 的 `ReleaseVariantTest` 要读它） |
| `apps/android/app/src/main/AndroidManifest.xml` | +10/−4：`dataExtractionRules`、`fullBackupContent`（F2）、`usesCleartextTraffic="false"`（F1），注释更新（`INTERNET` 的来源恰好是 `:modelmgmt` 与 `:syncplatform`） |
| `apps/android/app/src/main/res/xml/data_extraction_rules.xml`、`backup_rules.xml` | 新增（F2）：每个域整个排除 |
| `apps/android/app/…/AuriRuntime.kt` | +17/−4：`library` 改成 `SyncStorage.library(...)`（Record 库与待同步任务表同库）；新增 `sync`、`syncViewModelFactory`；录音目录与耳机配置放进 `noBackupFilesDir`（F2） |
| `apps/android/app/…/AuriApplication.kt` | +7/−1：实现 `SyncRuntimeHost`（系统因为有网络把应用叫醒时，同步服务从这里拿运行时，F7）、`runtime.sync.start()` |
| `apps/android/app/…/MainActivity.kt` | +42/−27：同步页的导航（`onSyncPage`、`BackHandler`）、`SyncViewModel`、回到前台时 `sync.syncNow()`；`openLicense` 改经 `BrowserLink`（F1：唯一发 `ACTION_VIEW` 的类）。行数大部分是 `AppRoot(...)` 多缩进了一层 |
| `apps/android/app/…/ui/HomeScreen.kt` | +2/−1：可选参数 `onOpenSync` 与“同步与配对”入口按钮 |
| `apps/android/modelmgmt/src/main/AndroidManifest.xml`、`apps/android/platform/src/main/AndroidManifest.xml` | 只改注释（`INTERNET` 的来源现在是两个模块） |
| `apps/android/platform/build.gradle.kts` | +2/−1：隐私与网络守卫依赖的编译任务从五个模块扩到七个 |
| `apps/android/platform/…/SqliteRecordLibrary.kt` | +25/−2：追加式接缝 `extraSchema`（每次打开在一个事务里执行，F5）、`changeSignal`、`withDatabase`；`SCHEMA_VERSION` 不变 |
| `apps/android/platform/…/storage/NoBackupStorage.kt`、`NoBackupStorageTest.kt` | 新增（F2） |
| `apps/android/platform/…/NetworkIsolationTest.kt`（Ticket 20 的测试） | **改写**（+18/−7）：登记的联网模块从 `:modelmgmt` 扩到 `:modelmgmt`、`:sync`、`:syncplatform`，扫描范围从五个模块扩到七个；更严的类级守卫是 `:app` 的 `NoNetworkCodeGuardTest` |
| `apps/android/platform/…/NoPhoneMicPathTest.kt` | +4/−4：扫描范围扩到七个模块 |
| Ticket 14 的 `OfflineProofTest.kt`（`:app` 仪器测试）、`NoNetworkManifestTest.kt`（`:app` JVM 测试） | **改写，不是加法**：应用必须声明 `INTERNET`，原来“包里没有 `INTERNET`”的整体证据不再成立（冲突与替代证据见第 8 节） |
| `execution/acceptance/asr-android/` 的 `README.md`、`contract_check.py`、`test_check_instrumentation.py`、`test_contract_check.py` | 登记被改写的离线证明新增的场景 `online-zh` 与新的测试名（含 F1 新增的两个）；README 加指向第 8 节的说明 |

### 12.7 上一轮（2026-11-18）的记录——历史，数字已被上面的取代

下面是 `23afdff` / `4be0524` 那一轮的原文，保留是为了说明那一轮发现并修好了什么（其中的测试数、变异条数、模拟器结果都**不再适用**于现在的代码：评审修复之后测试数与变异条数都变了，也没有重跑模拟器）。文中的“12.x”指的是这一小节里的编号（12.7.x）。

2026-11-18，本机（16 GB 的 Apple silicon Mac，JDK 17、Gradle 8.10.2、Android SDK 35）。前一位 worker 在 Mac 内存耗尽、Orca 重启时中断；这一轮由接手的 worker 从磁盘重建现场，并**把所有套件重新跑了一遍**，不采信提交信息里的数字。每个 Gradle 调用都是 `--no-daemon -Pkotlin.compiler.execution.strategy=in-process`，工作线程上限 2 由 `apps/android/gradle.properties` 的 `org.gradle.workers.max=2` 固定；我这一侧一次只开一个 Gradle（这台机器上别的 worker 的进程另算）；测试任务带 `--rerun`（见第 3 节）。接手后复查发现并修好了五处问题（12.7.3：测试太松两处、测试缺口一处、代码缺陷两处）。**生产代码的最终版本是 `23afdff`**；最后一个代码提交 `4be0524` 只加了测试与变异表（`git diff --stat 23afdff 4be0524` 只有 `SyncLoopTest.kt` 与 `mutation_check.py` 两个文件）。下表每一行都写明是在哪个提交上跑的；更早的提交（`a3ab36c` 等）上跑出的数字作废，不引用。

#### 12.7.1 各套件

| 套件 | 命令 | 结果 | 证据等级 |
|---|---|---|---|
| JVM 单测，六个模块（`4be0524`） | `./gradlew … --continue :capture:test --rerun :platform:testDebugUnitTest --rerun :app:testDebugUnitTest --rerun :asr:testDebugUnitTest --rerun :sync:test --rerun :syncplatform:testDebugUnitTest --rerun` | **588 个全过，0 失败、0 错误、0 跳过**：`:capture` 72、`:platform` 84、`:app` 89、`:asr` 112、`:sync` 221、`:syncplatform` 10（每个 JUnit XML 的时间戳都晚于本轮开始）；Gradle 整个调用 14 秒 | E2 |
| 对着真 Python 网关的 JVM 测试（`4be0524`） | `./gradlew … :sync:realGatewayTest --rerun` | **12 个全过**（`.venv` 的 Python 3.12.16、自签 TLS、临时数据目录、随机端口；XML 时间戳晚于本轮开始）；Gradle 整个调用 22 秒 | E2 |
| 构建（`4be0524`） | `./gradlew … :app:assembleDebug :app:assembleDebugAndroidTest :syncplatform:assembleDebugAndroidTest :platform:assembleDebugAndroidTest` | 成功（四个 APK 都在；Gradle 整个调用 6 秒，185 个任务里 183 个 `UP-TO-DATE`：`23afdff` 之后没有改过任何生产代码，对构建产物这是有效的） | — |
| Python 验收工具（三个目录一起跑，`4be0524`） | `AURI_GRADLE_ARGS='…' .venv/bin/pytest execution/acceptance/android-sync execution/acceptance/android-capture execution/acceptance/asr-android -rs` | **727 个全过，没有跳过**（android-sync 539，其中 494 个是变异表自检；android-capture 57；asr-android 131）；4 分 18 秒，含真实调用 Gradle 的几个测试 | E2 |
| 全仓库（`4be0524`） | `.venv/bin/pytest tests/contract tests/integration tests/security -rs` | **492 个全过，没有跳过**；3 分 42 秒 | E2 |
| 模拟器验收，含 Ticket 14 阶段（**`23afdff`**；`4be0524` 只改了 JVM 测试与变异表，没有动生产代码和仪器测试，所以没有重跑） | `AURI_ALSO_ASR_ACCEPTANCE=1 execution/acceptance/android-sync/run_emulator_acceptance.sh` | exit 0，两个阶段合计 2 分 21 秒（一次性 AVD：API 35、arm64-v8a、`google_apis`）。`:syncplatform` 仪器测试 **30 个全过，且逐个 `@Test` 核对都真的跑了**（Keystore 10、网络与发现 2、杀进程 3、真网关 1、任务库契约 6、发布事务 7、启动围堵 1）；宿主机回头查网关库：1 台设备、1 条 Record、1 版，正文与哈希一致、来源设备正确。Ticket 14 的 `:app` 仪器测试 **29 个全过**（包括被本票改写的 `OfflineProofTest` 三个：已安装的包只申请网关链路需要的网络权限、网络可用时转录零字节、飞行模式下转录成功），合同校验 16 个与 5 个场景都是 0 个问题，三次冷启动的网络字节增量都是 0。脚本退出后锁、AVD、宿主机网关进程、adb 服务都已清理 | E2（模拟器，不是 E3） |
| 变异检查，162 条 | 在 `git archive` 快照里：`mutation_check.py --in-place`。先整表（`23afdff`，159 条），补测试之后重跑整个循环组（`4be0524`，`--match loop-`，17 条） | 整表（`23afdff`）**158 条 `CAUGHT`、1 条 `SURVIVED`、0 条 `INVALID`**（存活的 `loop-pass-inside-collectlatest` 见 12.7.3）；补测试之后（`4be0524`）循环组 **17/17 `CAUGHT`**；其余 145 条沿用 `23afdff` 那一轮的 `CAUGHT`，没有在 `4be0524` 上重跑（见 12.7.2） | E2 |
| 真机、真局域网、真相机扫码、真 mDNS 广播 | 第 4 节 | **`NOT_RUN`**（本机没有手机） | — |

#### 12.7.2 变异检查整表重跑

**第一次整表**（针对提交 `23afdff` 的 `git archive` 快照，159 条，我这一侧一次只开一个 Gradle）：21:27 开始、22:16 结束，约 49 分钟；**158 条 `CAUGHT`、1 条 `SURVIVED`、0 条 `INVALID`**，退出码 1。存活的是 `loop-pass-inside-collectlatest`；harness 的说明是“有测试失败，但预期会失败的这些没有失败：['正在发送的请求不会被任务表的变化打断']；实际失败：['SyncLoopTest.网络断开……']”——整套测试虽然红了，但红在一条无关的测试上，这条规矩本身没有被钉住（原因与修复见 12.7.3）。

**补测试之后**（针对提交 `4be0524` 的全新 `git archive` 快照，与提交逐字节核对一致）：先跑相关的 5 条（`loop-pass-inside-collectlatest`、新增的 `loop-wake-cancels-running-pass`、`loop-network-change-deduplicated`、`loop-network-event-keeps-backoff`、`loop-network-event-does-not-rearm`），5/5 `CAUGHT`，红的测试正是预期的那几条（`loop-pass-inside-collectlatest` 由新测试变红，另有 `网络断开……` 一条；`loop-wake-cancels-running-pass` 由旧测试 `正在发送的请求不会被任务表的变化打断` 与另外两条变红；三条网络类各由对应的新测试变红）；然后把整个循环组（`--match loop-`，17 条）整组重跑：**17/17 `CAUGHT`**，约 6 分钟。其余 145 条（不属于循环组）**没有**在 `4be0524` 上重跑：`4be0524` 对 `23afdff` 只往 `SyncLoopTest.kt` 里加了测试、往变异表里加了条目，这 145 条被变异的文件与对应的测试都没变，结果沿用 `23afdff` 那一轮的 `CAUGHT`（只加测试不会让已经被抓住的变异存活）。

**对照实验**（证明新测试补的是真缺口）：在快照里把 `SyncLoopTest.kt` 换回 `23afdff` 的旧版（17 个测试），跑 `loop-network-event-keeps-backoff` 与 `loop-network-event-does-not-rearm`：**两条都 `SURVIVED`**，“变异后 17 个测试全部通过”；换成新版（19 个）后各由 1 个测试抓住。这个实验只改了快照里的测试文件，工作树一个字节都没动。

内存：整表与重跑期间交换区用量 ≤ 1.3 GB（共 2 GB），空闲内存 ≥ 38 %，在守则以内。

#### 12.7.3 接手这一轮发现并修好的事

- **`SyncEngineTest` 里一条测试管不到新任务**。“网关换了公钥……也没有任何继续的路”原来只数那条已经停下的任务：已停下的任务在 `attempt()` 里被状态机先拒绝，根本走不到 `usableGateway()` 这道闸，所以把闸拿掉，这条测试照样绿。实证：在快照里换回旧版测试再跑变异 `engine-ignores-connection-gate`，结果是 `SURVIVED`（预期变红的“没有任何继续的路”没有变红）；用现在的测试，同一条变异 `CAUGHT`（36 个里 3 个变红，包括这一条）。修复是 `fb96e5d`（测试里再发布一条新任务，几轮同步之后既不能多出连接尝试、也必须仍是 PENDING）。**也就是说，此前“变异检查全部被抓”在那个提交点并不成立**（当时第 9 节里“全部 CAUGHT”的说法以这一条为准；现在的第 9 节与 12.3 是后来的新整表结果）。
- **同步循环把“默认网络换了一个”吞掉了**（代码缺陷，修复 `c8f7cf9`）。`SyncLoop.watchNetwork` 对网络信号做了 `distinctUntilChanged()`。手机常开着移动数据时，Wi-Fi 断开是默认网络从 Wi-Fi 换到蜂窝、Wi-Fi 回来又换回去，信号一直是“可用”，两次切换都被去重吞掉，补传只能干等探测退避（封顶 15 分钟）。第 4 节原来写的“也关掉移动数据”恰好把它掩盖了；原来的 `FakeNetwork` 是 `StateFlow`，也表达不了“可用→可用”，所以旧测试看不到。实证（先红后绿）：新测试 `SyncLoopTest.默认网络换了一个而信号一直是可用……` 先红——`expected:<STORED> but was:<WAITING_GATEWAY>`（`SyncLoopTest` 17 个里只有它红）；去掉去重之后 `:sync` 219 个全绿；新变异 `loop-network-change-deduplicated`（把去重加回去）要由这条测试抓住（结果见 12.7.2）。真机上系统在默认网络切换时到底回调什么（只有 `onAvailable`，还是先 `onLost`）只有 E3 能回答，第 4 节第 6 步的第二遍专门去看。
- **`SyncRuntime.start()` 没有围堵启动准备**（代码缺陷，修复 `23afdff`）。`start()` 跑在 `AuriRuntime` 共用的作用域上（`SupervisorJob + Dispatchers.Default`，没有异常处理器），`pairing.refresh()` 与 `engine.resolveAfterRepair()`（要读任务表）逃出来的异常在 Android 上就是应用崩溃，连正在录的音一起带走。我最初把它记成“低严重度”（只想到“同步没启动”），复查时发现它和录音同进程，改判为应当修。实证（先红后绿，模拟器）：新仪器测试 `SyncRuntimeStartupTest`（配好对、删掉任务表模拟读库出故障）先红——`:syncplatform` 30 个里只有它红，`expected null, but was:<kotlin.Unit>`（`start` 的协程被异常带死）；围堵之后 30/30 全绿，且日志里有 `start.failed SQLiteException` 一类的一行（只有异常类名）。它是 Android 专属的装配代码，JVM 里没法测，所以**没有变异条目**，证据只有模拟器上的这一条仪器测试（E2）。同类的界面侧状态流没有改，原因见第 11 节。
- **“一轮同步不跑在会被取消的协程里”这条规矩，测试没有真的钉住它**（测试太松，修复 `4be0524`）。`23afdff` 的整表变异检查里，`loop-pass-inside-collectlatest`（把 `engine.runOnce()` 挪进任务表的 `collectLatest`）是 `SURVIVED`：变异之后红的只有无关的 `网络断开……` 一条，预期要红的 `正在发送的请求不会被任务表的变化打断` 没有红。**所以“变异检查全部被抓”在 `23afdff` 这个提交点同样不成立。** 原因：专用循环永远先抢到引擎锁，那条测试里在途的请求从来不是发在会被取消的协程里；这个变异只有在“专用循环正忙着发别的任务、重试计时器响了，`Mutex` 先来先得把锁先交给 `collectLatest` 里排队的那一轮”时才会伤人——那一轮一改任务状态，`collectLatest` 就把它连同在途的请求一起取消，留下“结果不确定”的任务，之后多一次状态查询和一次重发。修复：新增 `SyncLoopTest.别的任务正占着这一轮时重试计时器响了……`，在真实代码上是绿的（`SyncLoopTest` 19 个，0 失败），变异下变红，`loop-pass-inside-collectlatest` 改由它负责；旧测试 `正在发送的请求不会被任务表的变化打断` 则配上了它真能抓住的变异 `loop-wake-cancels-running-pass`（把 `runPasses` 改成对信号 `collectLatest`，新触发就会取消在途的一轮；3 个测试变红，包括旧测试），否则它就成了没有变异约束的测试。
- **网络事件让探测退避从头开始、让睡着的旧计时器重新计时，原来没有测试约束**（测试缺口，`4be0524`）。`SyncLoop.watchNetwork` 里的 `probeAttempt = 0` 与 `rearm.value += 1`：去掉任何一个，退避就会停在网络恢复之前涨到的间隔上（最长 15 分钟）；刚连上 Wi-Fi 那一刻网关未必已经应答，探不到就要干等。新测试 `SyncLoopTest.网络恢复或默认网络换了一个：立刻探一次，并且退避从头开始……` 与两条变异 `loop-network-event-keeps-backoff`、`loop-network-event-does-not-rearm`。对照实验（12.7.2）：旧版 `SyncLoopTest` 下这两条变异都 `SURVIVED`，新版下各被这一条测试抓住。
- **Gradle 把测试任务判成 `UP-TO-DATE` 不算重新跑过**（第 3 节已写）。
- 订正了本文件两处与事实不符的话（`.gitignore` 没有改；真机步骤里 `pair` 要加 `--host`，见 `services/gateway/OPERATIONS.md`）。
