# Ticket 21 验收：Android 与网关配对，并把文字送到网关

对应 `.scratch/auri-v1/issues/21-android-pair-and-sync.md`（GitHub issue #22），PRD / ADR 引用：ADR-0018（网关只在局域网、自签证书加公钥指纹绑定）、ADR-0013（单用户网关，身份由认证结果映射）、ADR-0020（只有录下 Record 的那台设备能给它写 Revision）、FR-SYNC-002（离线补传不得复活已删除的 Record）、SEC-004（转录文字是数据，不是指令）。这是手机端第一次把文字送出手机，也是 iOS、鸿蒙两端同步层的样板，所以接缝与规矩比界面重要。

**状态（2026-11-18）**：**没有任何真机（E3）结果**：本机没有 Android 手机，所以清单最后一项“真机上入库、断开 Wi-Fi 录音、重连后自动补传”是 **`NOT_RUN`**；产品负责人的真机步骤在第 4 节。真实的相机扫码、真实的 mDNS 广播发现真网关也都是 `NOT_RUN`（模拟器没有摄像头，也和宿主机隔着 NAT，收不到多播）。已经有的证据是 **E2**：JVM 单测、**对着真实的 Python 网关进程**的 JVM 测试、模拟器上的仪器测试（含对宿主机真网关的一次配对与入库）。

证据等级沿用 PRD 第 5.2 节：JVM 与模拟器 = **E2**；真机 = E3。没有真机/账号/签名的项标 `NOT_RUN` 或 `BLOCKED_EXTERNAL`，不用模拟或伪造替代。

## 1. 这里有什么

| 路径 | 作用 |
|---|---|
| `apps/android/sync/`（`:sync`） | **纯 Kotlin/JVM，不含 Android 类**：配对载荷校验（合同 1.1）、固定公钥的 TLS 与最小 HTTP/1.1 客户端、网关接口与客户端、网关定位（记下的地址 → mDNS）、同步状态机（照 `sync.machine.json`）、同步引擎、同步循环、`SubmissionBuilder`（本地 Record + Revision → 合同的 submission）、同步状态汇总（`SyncStatus`）。`testFixtures` 里有任务库契约测试与内存实现，供 `:syncplatform` 复用 |
| `apps/android/syncplatform/`（`:syncplatform`） | Android 实现：Keystore 封存（`KeystoreSecretBox`）、SQLite 任务库（与 Record 库**同一个数据库文件**）、`NsdManager` 发现、`ConnectivityManager` 网络信号、CameraX 取景 + ZXing 解码、同步日志出口（`AuriSync` 标签）。**全 App 只有这个模块声明 `INTERNET`** |
| `apps/android/app/`（`:app`） | `SyncViewModel`（文件头注释就是文档：唯一的可观察入口是 `state: StateFlow<SyncUiState>`）、极简 Compose 的 `SyncScreen` / `SyncPage`、`AuriRuntime.sync`；首页多一个“同步与配对”入口 |
| `real_gateway_host.py` | 宿主机上的真网关辅助：`start` 起真网关并造配对载荷与一条 Record，`verify` 只读核对网关数据库（模拟器验收用） |
| `run_emulator_acceptance.sh` | 一次性无头模拟器验收（E2）：取共享锁 → 建 AVD → 起模拟器 → 起宿主机真网关 → 仪器测试 → 逐个核对 → 回头查网关数据库 → **无论成败都**停网关、杀模拟器、删 AVD、放锁 |
| `check_sync_instrumentation.py` | 读 `am instrument -r` 的原始输出，逐个核对每个 `@Test`（含 `SqliteSyncJobStoreTest` 继承来的 6 个契约测试）都跑了且通过 |
| `mutation_check.py` | 变异检查（第 9 节）：162 条，在 `git archive` 式的快照里跑，**绝不改真实工作树** |
| `test_*.py` | 上面这些工具的测试；`test_real_gateway_suite.py` 会真实调用 Gradle 跑 `:sync:realGatewayTest` 再核对每个 `@Test` 都跑了 |

对既有代码的改动（共享文件，都是最小的加法，清单在第 12 节末尾）：`SqliteRecordLibrary` 多一个让同库的别的表挂进来的接缝；`AuriRuntime` 把 Record 库换成 `SyncStorage.library(...)` 并多一个 `sync`；首页多一个入口按钮。

## 2. 清单逐项对照

| # | 要求 | 覆盖它的测试 / 文件 | 证据等级 |
|---|---|---|---|
| 1 | 扫码完成配对，设备令牌存入平台安全存储，**不在日志里、不在明文文件里、不在仓库里** | JVM：`PairingPayloadTest`（16：按合同 1.1 逐项严格校验，合同里的无效样本一个不漏地拒绝）、`QrDecoderTest`（10：含网关真实的约 380 字符载荷、四个方向、反色）、`QrFrameReaderTest`（6：CameraX 行距、每帧恰好关闭一次）、`PairingServiceTest`（15：每种结局的处置；**请求可能已送到就绝不重发配对码**；调用方被取消时令牌也要存下来）、`FilePairingStoreTest`（12）与 `AesGcmSecretBoxTest`（5）、`SyncViewModelTest`（14）。真网关（JVM）：`RealGatewayTest` 的“二维码配对”——网关命令行打印的**真终端二维码**经 `AsciiQr` 还原成图、由手机端的 `QrDecoder` 读出、配对成功，令牌只以密文落盘（磁盘上翻不到明文），网关登记了这台设备，配对码只能用一次。模拟器：`KeystoreSecretBoxTest`（10：真 AndroidKeyStore，密钥不可导出、IV 每次随机、改一个字节或截断都打不开、密钥丢失读成“丢失”而不是崩溃，配对记录落盘后私有目录里任何地方都没有令牌明文）、`RealGatewayOnDeviceTest`（1：对宿主机真网关配对、入库、重复提交幂等；令牌与配对码既不在 logcat 也不在明文文件里） | JVM 与模拟器 E2；**真机相机扫码 `NOT_RUN`** |
| 2 | 每次连接核对网关公钥指纹；不符就拒绝并提示重新配对，**没有“忽略并继续”** | JVM：`SpkiPinTrustManagerTest`（6）、`PinnedHttpsClientTest`（15：每次连接都是全新的 TLS 上下文、不符时服务器**一个请求都没收到**、网络故障不误报成不符）、`SyncEngineTest`（35）里“连接要用户处理期间一个带令牌的请求都不发”“新配对不被晚到的旧观察覆盖”、`SyncStatusTest`（17）与 `SyncScreenTextTest`（6）里“请重新配对”的话。**守卫**：`NoNetworkCodeGuardTest`（15）扫描生产代码编译出的类、源码与清单——没有 `HostnameVerifier`、没有“信任所有证书”的 `TrustManager`、没有明文 `http://`、唯一的信任管理器是 `:sync` 的 tls 包里的 `SpkiPinTrustManager`；每条规则都有“正对照”（故意写一段违规的类，规则必须认得出）；`NoNetworkManifestTest`（5）证明 `INTERNET` 只来自 `:syncplatform`、没有放开明文流量。真网关（JVM）：“换了一台网关（公钥不同）”（拒绝连接、需要重新配对、期间一个连接都不再发——**包括用户处理之前新录的任务**）、“网关吊销了这部手机”（令牌被拒、需要重新配对、不再重试） | E2 |
| 3 | 先连配对时记下的地址，连不上用 mDNS 查找 | JVM：`GatewayLocatorTest`（10：最近连上的地址 → 配对时记下的地址 → mDNS；某个地址是冒充者时继续找；全是不符的公钥才报“公钥不符”；取消不被吞）、`GatewayAdvertisementTest`（6：只认同一个 `gateway_id`，主机与端口不合法的广告不是候选）。真网关（JVM）：“地址搬家”——记着的地址没人听了，用发现（**mDNS 的替身**，JVM 里没有 `NsdManager`）找到网关的新地址，核对同一把公钥后送达，新地址被记住，下一条不再需要发现。模拟器：`NetworkAndDiscoveryTest`（2：网络信号跟着飞行模式走；真 `NsdManager` 在没有任何广告时按时返回空、不崩） | E2；**真实 mDNS 广播发现真网关 `NOT_RUN`**（要真实局域网） |
| 4 | 发布 Revision 和创建待同步任务**在同一个本地事务里**；任务在应用重启后保留 | 模拟器（真 SQLite）：`SyncPublicationTransactionTest`（7：同一个事务登记任务；登记失败整个事务回滚、Revision 也不留；没有这条 Record 时发布失败也不留任务；关掉重开文件两样都在；旧库没有任务表时打开即补建、版本号不变）、`ProcessKillSurvivalTest`（3：**真杀进程**——另一个进程在事务中途被 SIGKILL，什么都没留下；提交之后被杀，两样都在；发送途中被杀，任务以“结果不确定”留下，重启后引擎先问网关、网关已收下就不重发）、`SqliteSyncJobStoreTest`（继承任务库契约测试 6 条，JVM 里的内存实现跑同一套） | 模拟器 E2 |
| 5 | 同步状态机的事件序列样本全部通过 | `SyncScenarioReplayTest`（1）：`contracts/fixtures/state-machines/sync.scenarios.json` 里的 8 个场景，用假适配器回放到**真正的同步引擎**上（事件都由真实原因造出来：网关答 503 / 409 / 401、连不上、网络断开……，不是直接喂事件），都得到合同写的结果；转移表里每一条规则都必须被某个场景步骤走到过（少回放一个场景、漏掉一条规则，测试就失败）。合同文件直接从仓库 `contracts/` 读，不是拷贝。`SyncMachineTableTest`（4）：转移表逐格与 `sync.machine.json` 一致 | E2 |
| 6 | 界面区分三种情况：等网关、稍后重试、需要用户处理 | `SyncStatusTest`（17：六种互斥的情形——没配对 / 需要用户处理 / 稍后重试 / 等网关 / 发送中 / 已同步；**需要用户处理优先于稍后重试**；公钥不符是连接层面的问题，没有任务也要告诉用户）、`SyncScreenTextTest`（6：三种情形各自的话，等网关与稍后重试明说“不需要你做什么”，需要处理的那一种不这么说，并告诉用户该怎么办）、`SyncViewModelTest`（14）。界面是极简 Compose（`SyncScreen.kt`），数据与操作的说明在 `SyncViewModel.kt` 文件头 | E2；**界面本身没有截图/界面测试**（见第 11 节） |
| 7 | 提交结果不确定时**先查询再决定是否重发**，不产生重复记录 | `SyncEngineTest`（35，假网关的六种故障）、`GatewayClientTest`（18）、`ProcessKillSurvivalTest`“发送途中被杀”。**对着真实网关**（`RealGatewayTest`）：“响应在路上丢了、网关其实已经入库”——先查询得知已入库，**不重发**，库里只有一条；“请求没送到网关”——先查询得知没有，才重新提交一次，库里恰好一条；“重复提交同一版”——网关回 `duplicate`，库里仍是一条；“同一版号、正文不同”——版本冲突，永久停下，库里留着最初的那一版。故障注入在 `HttpExchange` 边界、包着真实的 `PinnedHttpsClient` 与真网关（网关不留请求日志），并用 TCP 转发器的连接计数做独立证据 | E2 |
| 8 | 真机上验证：在局域网内入库；断开 Wi-Fi 后录音，重连后自动补传 | **`NOT_RUN`**：本机没有手机。产品负责人的步骤在第 4 节；模拟器侧能证明的部分（任务表、事务、杀进程、飞行模式下网络信号变化、对真网关入库）在上面几行。JVM 里“什么时候跑一轮”由 `SyncLoopTest`（19）管：网络断开改成等网关且断网期间不探测；网络恢复**或默认网络换了一个而信号仍是“可用”**（手机常开着移动数据时，Wi-Fi 断开/回来就是这样）都立刻补传，不用干等最长 15 分钟的探测退避，网络一变退避就从头开始；一轮同步不会被任务表的变化或新的触发取消（取消发到一半的请求会留下“结果不确定”的任务） | `NOT_RUN`（E3） |

另：本文件与 `CLAUDE.md` “命令”一节已更新（`.gitignore` 没有改：已有的 `execution/**/raw/` 就覆盖真机原始日志目录）；`:platform` 的隐私守卫（`NoPhoneMicPathTest`）从四个模块扩到六个——**同步层里任何地方都不能引用 `AudioRecord` / `MediaRecorder` 等向系统要麦克风的类**（变异验证见第 9 节）。

## 3. 在本机怎么跑

```bash
source ~/.config/auri/android-env.sh
cd apps/android
# JVM 单测（不需要设备）
./gradlew --no-daemon :capture:test :platform:testDebugUnitTest :app:testDebugUnitTest :asr:testDebugUnitTest :sync:test :syncplatform:testDebugUnitTest
# 对着真实的 Python 网关进程（要仓库的 .venv；找不到解释器就失败，不会跳过）
./gradlew --no-daemon :sync:realGatewayTest
# 构建 APK 与仪器测试 APK
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :syncplatform:assembleDebugAndroidTest

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

**要新鲜证据就加 `--rerun`**：Gradle 在输入没变时会把测试任务判为 `UP-TO-DATE` 直接跳过，那不算“重新跑过”（接手这一轮第一次跑全量，六个模块里有三个就是这样被跳过的）。每个任务名后面跟 `--rerun`，例如 `./gradlew --no-daemon :sync:test --rerun :syncplatform:testDebugUnitTest --rerun`。内存紧张的机器（16 GB 且同时有别的工作进程）一次只开一个 Gradle，并加 `-Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2`；验收脚本和 Python 验收测试里的 Gradle 子进程可以经环境变量 `AURI_GRADLE_ARGS` 带上同样的参数。

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
- **网络面受控**：`INTERNET` 只来自 `:syncplatform`；`NoNetworkCodeGuardTest` 用类文件常量池扫描逐个类核对，`:capture`、`:asr`、`:platform` 里没有任何网络类，`:sync` 里网络类只出现在允许的包；规则表带正对照，规则被削弱时守卫自己会变红（第 9 节）。
- **转录文字是数据**：同步层只搬运文字，从不解释；日志里没有正文（`SyncLog` 的合同是只写事件名、枚举与数字）。

## 6. 假设（做决定时我没有问人，下面每一条都可以推翻）

1. **待同步任务在发布时就创建，没配对时先排着**：配对之后，之前录的文字会被补传。理由：用户选择把手机接到自己电脑上的网关，补传正是“离线队列”的意思。**这是产品决定**：如果希望“配对之前录的文字不上传”，只需要让没配对时发布不入队，不涉及别的改动。未配对时同步页只说“还没配对，文字只留在本机”。
2. **进程活着时才同步，没有 WorkManager**：需求写的是“离开局域网时显示等待网关，回来后自动补传”。我把“回来后”理解为 App 进程在时（回到前台、网络恢复、重试到点）；进程被系统杀掉之后，要等下次打开 App 的第一轮把任务接回来。理由：WorkManager 多一个依赖、而且后台限制（Doze、电池优化）下的时效不可控，做“可靠后台补传”是 Ticket 19（中断与恢复）的范畴。**限制见第 11 节**。
3. **只有 Record 的来源设备能提交它的 Revision**（ADR-0020）：别的设备改同一条 Record 被网关永久拒绝（`RECORD_NOT_ORIGIN_DEVICE`），任务停在“需要用户处理”，不重试。
4. **永久拒绝一律停下**：版本冲突（同一版号不同正文）、记录已删除、不是来源设备、请求不合规、本机数据读不到。没认出来的 4xx 也停下，不无限重试；网关在错误体里明说的 `retryable` 优先于按状态码的推断。
5. **重试退避**：5 秒起、翻倍、封顶 15 分钟；这些数字是假设，没有真机数据。
6. **调试版才有“手动输入配对载荷”**（模拟器没有摄像头），判据是 `FLAG_DEBUGGABLE`；release 版没有这个入口（`SyncViewModelTest` 与变异验证）。
7. **配对时报给网关的设备信息**：平台固定 `android`；应用版本、系统版本（`Android <版本> (API <级别>)`）、设备显示名（厂商 + 型号）按合同的长度上限截断（应用版本最长 64、其余最长 100）。**不含**序列号、IMEI、Android ID、构建指纹和用户给设备起的名字（`DeviceInfoTest`）。
8. **时间与随机**：设备上的时间用系统时钟，重试按单调时钟；没有时间同步的假设。
9. **TDD 的接缝没能先问人**：实现规范的 TDD 流程要求先和人确认要测的接缝；这次是被派发的 worker，没有人可问，我把接缝定在“网关接口（`GatewayApi` / `HttpExchange`）、任务库与配对库接口、同步引擎的公共方法、`SyncViewModel.state`”，测试都写在这些接口上，不测内部。可以推翻。
10. **没有运行 Codex 评审**：用户规则要求规范任务完成后用 codex 评审，但本次派发明确禁止子 agent 与帮手，所以没有做；这里的替代是第 9 节的变异验证与真网关测试。**没有使用 mcp-feedback-enhanced**（本环境没有这个工具）。

## 7. 合同与网关的缺口及建议（没有改 `contracts/` 与 `services/gateway/`，只记录）

编号接着 Ticket 16 的 G1–G6。

| # | 缺口 | 位置 | 建议 |
|---|---|---|---|
| G7 | 本地 Revision 没有保留转录引擎的逐段时间戳，提交时 `content.segments` 只能是 `[]`。合同允许为空，**真网关（`RealGatewayTest`）接受**；但以后若要在 Agent 侧按段定位，这里是空的 | `revision.schema.json#/$defs/submission/properties/content/properties/segments` | 要么在 Ticket 18（分段）落地时让 Record 库保留段，要么在合同里写明“手机端可以不带段”。现在不需要改 |
| G8 | `sync.machine.json` 只有 `VERSION_CONFLICT` 一个“永久拒绝”事件。记录已删除（`RECORD_DELETED`）、不是来源设备（`RECORD_NOT_ORIGIN_DEVICE`）、请求不合规、本机数据读不到，都借用它，真正的原因只记在手机本地的 `SyncJob.blockedReason`，状态机里看不出区别 | `contracts/fixtures/state-machines/sync.machine.json` 的 `events` 与 `BLOCKED` 的入边 | 增加 `PERMANENT_REJECTION`（带原因枚举）或为每种原因各一个事件，并在 `sync.scenarios.json` 里各补一个场景。现在不影响行为，只影响 iOS、鸿蒙两端要不要各自重新发明这个映射 |
| G9 | 手机端没有办法知道某个 Destination 或自动发送是否已授权，所以 `publication.destination_ids` 固定为 `[]`、`intent=context`。真网关接受 | `revision.schema.json` 的 `publication` | Ticket 22（Destination 与发送）落地时由那边决定如何带上目标；这里只是记录“目前只入库” |
| G10 | 网关在 WARNING 日志级别下不留请求日志，所以“网关到底收到过几次提交”无法从网关侧直接数，只能靠数据库行数（幂等会掩盖重复提交）。真网关测试里“不重发”的证据来自手机侧的请求记录与 TCP 转发器的连接计数 | `services/gateway/auri_gateway/` 的请求日志 | 可选：网关在 DEBUG 级别或一个只读运维命令里提供“某条 Record 收到的提交次数”，便于以后真机联调时在网关侧复核。不是缺陷 |
| G11 | 配对二维码的静区只有 1 个模块（`services/gateway/auri_gateway/cli.py` 里 `qrcode.QRCode(border=1)`），QR 规范建议 4 个；载荷约 380 个字符，二维码版本较高。**实测**：ZXing 3.5.4 读完整的配对载荷时，画面顺时针转 90 度会读不出来（抛 `FormatException`，另外三个方向正常），而手机竖着拿时相机帧通常正是转了 90 度的——`QrDecoder` 因此对四个方向与反色都试一遍（`QrDecoderTest`“四个方向都能读”钉住了）。**没有在真机相机上观察到扫不出来，静区那一半未经验证** | `cli.py` 的 `border`；`pairing.schema.json` 的载荷字段 | 若真机扫码不稳，把 `border` 提到 4，或缩短载荷（例如省掉合同里的常量字段）。先看第 4 节真机观察再决定 |

另：没有发现合同与网关行为不一致的地方——`RealGatewayTest` 里合同的 5 个有效 submission 样本（含被截断的与跳号的 Revision）真网关全部接受；错误码映射（`RECORD_VERSION_CONFLICT`、`RECORD_NOT_ORIGIN_DEVICE`、令牌被吊销）与引擎里的分类一致。

## 8. 与 Ticket 14 的冲突：离线证明（需要产品负责人决定）

Ticket 14 的第 4 项“证明模型路径不联网”当时靠三层证据：①包里没有 `INTERNET` 权限；②因此内核对该 UID 的网络 socket 一律“权限被拒”（`OfflineProofTest.theKernelRefusesEveryNetworkSocketButStillAllowsLocalOnes`）；③飞行模式下整条转录成功、字节增量为 0。**本票要求应用把文字发到局域网网关，必须声明 `INTERNET`，前两层从此不可能成立。** 两个要求直接冲突，我没有问人（不能问），按“让需求 21 成立、同时尽量保住 14 想要的保证”处理：

- **删掉**两个测试：`theInstalledPackageRequestsNoInternetPermission`、`theKernelRefusesEveryNetworkSocketButStillAllowsLocalOnes`（它们断言的前提已不存在）。JVM 的 `NoNetworkManifestTest` 改写为“合并后的清单里 `INTERNET` **只来自 `:syncplatform`**”。
- **换成**更弱但仍有牙齿的证据：①设备上已安装的包申请的网络与定位类权限恰好是 `INTERNET` 与 `ACCESS_NETWORK_STATE`、没有别的（`theInstalledPackageRequestsOnlyTheGatewayLinkNetworkPermissions`）；②**网络可用**时整条转录 SUCCEEDED，且该 UID 的收发字节增量为 0（`transcriptionSendsNoNetworkBytesEvenWhileTheNetworkIsAvailable`）——以前“零字节”是权限保证的，现在是**测出来的**；③飞行模式那条保持不变；④代码层：`NoNetworkCodeGuardTest` 逐个类核对 `:asr`、`:capture`、`:platform` 里没有任何网络类。
- **保证变弱了**：现在不能再说“转录路径在内核层面被禁止联网”，只能说“转录路径的代码里没有网络类、实测网络可用时零字节”。若产品负责人要保住原来那条强保证，办法是出一个**不带 `INTERNET` 的构建变体**（例如 `offline` flavor，同步层不编入）；我没有做，因为这是范围与分发决策。请在 Ticket 30（隐私与许可审计）前定下来。
- `execution/acceptance/asr-android/test_check_instrumentation.py` 里钉住的测试名随之更新；Ticket 14 的 README（`execution/acceptance/asr-android/README.md`）里这一项加了指向本节的说明，当时的实测数字保留为历史记录。**设备上改过的测试是否仍通过**见第 12 节。

## 9. 测试的牙齿（变异验证）

“只写会通过的测试”不能证明测试拦得住错误（Ticket 16 的评审教训）。`mutation_check.py` 把“破坏哪条规矩、哪些测试必须因此变红”写成一张表（162 条），每条对生产代码**恰好改一处**，跑对应的测试，必须有预期的测试变红；表本身有离线检查（`test_mutation_table.py`：原文在源码里恰好出现一次、预期变红的测试在源码里真的存在、没有变异碰到测试类，应用再还原后文件字节级不变）。**全部在 `git archive` 式的快照（`/tmp/auri-ticket21-mutation-snapshot`）里跑，真实工作树一个字节都不会动**；唯一的例外是网络卫士的规则表 `NetworkSurface.kt`（它在 `src/test` 下，不是 `*Test.kt`）——削弱卫士自己的规则也要变红，所以允许变异它，`test_mutation_table.py` 里有一个测试把这个例外钉死。

| 组（按主题） | 条数 | 覆盖 |
|---|---|---|
| 传输与指纹、网关客户端 | 15 | 比对永远通过 / 只看最后一张证书 / 空证书链 / 不符误报成不可达 / 网络故障误报成不符 / 请求发出后的故障报成“没发出” / TLS 上下文共用 / 回答的不是我们问的那一条 / 响应版本不核对 / 令牌格式不核对 / 令牌与请求头被打印 |
| 令牌封存与配对记录 | 14 | 令牌明文落盘 / 封存重用 IV 或忽略用途 / 读不出来当成“从没配过” / 配对记录读改写不互斥 / 可观察的配对状态乱序、不发布、先发布后存盘 |
| 定位与配对服务、设备信息 | 15 | 地址遍历遇到冒充者就停 / 忘记见过不符 / 重试已试过的地址 / 吞掉取消 / 配对码可能已送到却重发 / 不核对网关 id / 取消时令牌没存下 / mDNS 广告里的网关与主机端口不核对 / 设备信息字段不截断 |
| 引擎、状态机、重试、循环 | 46 | 不先落盘就发送 / 不确定不先问 / 只比较是否存在不比较哈希 / 永久拒绝被重试 / 连接闸门被忽略或不持久化 / 晚到的旧观察覆盖新配对 / 适配器出故障任务卡在发送中 / 取消被当成故障 / 退避不增长或溢出 / 启动不跑一轮 / 网络恢复不唤醒 / **默认网络换了一个（信号仍是“可用”）被去重吞掉** / 探测不退避 / **网络恢复或换网络时退避不从头开始、旧计时器不重新计时** / 一轮跑在会被取消的协程里 / **新的触发取消正在跑的一轮** / BLOCKED 被放行 |
| 状态汇总与 submission | 23 | 三种情形文字混淆 / 需要用户处理被“稍后重试”盖住 / 没配对显示成发送中 / 非蓝牙路由被送出 / 正文被修剪或按字符数限长 / 语音片段不排序不核对 / 猜耳机固件版本 |
| 二维码与取景 | 10 | 不读反色 / 不转方向 / 不按行距 / 尺寸对不上抛异常 / 帧不关闭或关两次 / 异常溢出分析线程 |
| 守卫、清单、隐私 | 13 | 主机名校验后门 / 应用壳信任所有证书 / `:capture` 里出现套接字 / 明文 `http://` / 信任类出了 tls 包 / **卫士自己被削弱的四种** / 应用主清单声明 `INTERNET` / 放开明文流量 / `:syncplatform` 与 `:sync` 里出现 `MediaRecorder` 或 `AudioRecord` |
| 视图模型与界面文案 | 15 | 配对不原子 / 重复帧 / 配对成功后同一张码再次触发 / 失败后码被标成已用 / release 有手动入口 / 意外异常卡在“正在配对” / 结局互相顶替 / 关闭提示无效 / 非配对二维码被悄悄忽略 / 三种情形的话被改混 |
| **真网关** | 11 | 同一批规矩换成对着真网关再破坏一次：比对永远通过、盲目重发、版本冲突被重试、`RECORD_NOT_ORIGIN_DEVICE` 被重试、令牌明文落盘、连接闸门被忽略或不持久化、排队任务不改成等网关、请求发出前不记“不确定”、新地址不被记住、正文被修剪 |

第 9、10、11 组本次的结果：28 条全部 `CAUGHT`；第 11 组首轮有 7 条没被抓到（`SURVIVED`），其中 5 条暴露的是**测试写得太松**（例如“用户未处理期间不再发请求”只数了已经停下的那条任务，对新任务没有约束），已先加强测试再重跑；1 条锚点选错（改的是“没认出来的 4xx”，而真网关给的是认得的错误码），换成 `RECORD_NOT_ORIGIN_DEVICE` 的分类；1 条对真网关层没有意义（二维码旋转只在相机帧里发生，网关打印的真二维码不需要旋转），已删除。最终结果见第 12 节。

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

其余依赖（`kotlinx-serialization-json`、`kotlinx-coroutines`、`androidx.test` 等）沿用既有版本，没有新增。没有模型权重、没有真实个人音频入库。

## 11. 已知限制与后续

- **进程被杀之后不会自己同步**（没有 WorkManager，见第 6 节第 2 条）：在后台被系统回收后录下的文字，要等下次打开 App 才补传。真机上的实际表现要看第 4 节第 6 步，Ticket 19 处理中断与恢复时应一并决定后台补传的方式。
- **网关所在的 Wi-Fi 没有外网、手机又开着移动数据时**，系统可能让蜂窝继续当默认网络，发往局域网地址的连接走蜂窝而连不上：界面显示“等网关”，不会错连（指纹核对照旧），但补传要等到系统把默认网络换回 Wi-Fi。要解决得把套接字绑到 Wi-Fi 网络（`Network.socketFactory`）上，这一版没有做；`AndroidNetworkSignal` 的文件头同样记着。这是真机项，第 4 节第 6 步的第二遍会碰到。
- **同步层的启动准备围堵了，界面侧的状态流没有**：`SyncRuntime.start()` 里读配对记录与任务表的故障现在只记一条日志（`start.failed <异常类名>`）、循环照常起来（`SyncRuntimeStartupTest`，模拟器上先红后绿）；但 `SyncViewModel.state` 用 `stateIn(Eagerly)` 收集 `controller.status`（里面读任务表），没有 `.catch`，数据库读异常会落到界面作用域上——那个作用域和应用共用的一样没有异常处理器，等于崩溃。`HomeViewModel`（Ticket 16，已评审）对 `library.observe()` 是同样的写法，所以我**照既有约定做、没有分叉**。区别要说清：数据库整体出故障时首页会先一步崩溃；只有任务表里某一行读不出来（比如旧版本读到新版本写下的状态名）时，打开同步页才会崩、首页不受影响——任务表只由本应用写、Android 不允许降级安装，正常使用里不会出现，但没有测试证明。建议跨票统一处理（给 `AuriRuntime.scope` 与各 ViewModel 的作用域装 `CoroutineExceptionHandler`，或在状态流上加 `.catch`），不在本票里改别人的约定。
- **界面本身没有截图或界面测试**：`SyncViewModel` 与文案映射有 JVM 测试，Compose 界面只保证编译通过、在模拟器上装得起来；界面会被别人重做，所以没有为它投入。
- **真实相机扫码、真实 mDNS 广播发现、真实局域网**都是 `NOT_RUN`（模拟器没有摄像头，网络与宿主机隔着 NAT）；JVM 里的发现用替身，模拟器里的 `NsdManager` 只验证了“能注册、能注销、没有广告时按时返回空”。
- **故障注入不在 TLS 记录层**：“响应丢了”和“请求没送到”是在 `HttpExchange` 边界注入的，包着真实的 `PinnedHttpsClient` 与真网关；在 TLS 记录层切断连接要改动客户端或网关，脆弱而且难复现，所以没做。独立证据是 TCP 转发器的连接计数与网关数据库。
- **网关侧没有请求计数**（G10）：“不重发”靠手机侧的请求记录。
- 配对记录读不出来（密钥丢失）时只能重新配对；没有令牌轮换或续期，合同里也没有。
- **没有处理删除**：Record 的删除与墓碑是 Ticket 23；`RECORD_DELETED` 的映射有单元测试，但没有真网关测试（网关的删除端点本分支尚未提供）。
- `CLAUDE.md` “命令”一节里 Ticket 16 那一行写的 `71 + 56 + 51 + 112` 是 Ticket 16 审查之前的旧基线（Ticket 16 的 README 第 10 节已是 `:capture` 72、`:platform` 84、`:app` 52、`:asr` 112，本次实测 `:capture` 72、`:platform` 84、`:asr` 112 一致，`:app` 是 52 + 本票的 37 = 89）；那是别的票留下的数字，我没有改它，只在“命令”里补了本票的命令。

## 12. 结果

2026-11-18，本机（16 GB 的 Apple silicon Mac，JDK 17、Gradle 8.10.2、Android SDK 35）。前一位 worker 在 Mac 内存耗尽、Orca 重启时中断；这一轮由接手的 worker 从磁盘重建现场，并**把所有套件重新跑了一遍**，不采信提交信息里的数字。每个 Gradle 调用都是 `--no-daemon -Pkotlin.compiler.execution.strategy=in-process`，工作线程上限 2 由 `apps/android/gradle.properties` 的 `org.gradle.workers.max=2` 固定；我这一侧一次只开一个 Gradle（这台机器上别的 worker 的进程另算）；测试任务带 `--rerun`（见第 3 节）。接手后复查发现并修好了五处问题（12.3：测试太松两处、测试缺口一处、代码缺陷两处）。**生产代码的最终版本是 `23afdff`**；最后一个代码提交 `4be0524` 只加了测试与变异表（`git diff --stat 23afdff 4be0524` 只有 `SyncLoopTest.kt` 与 `mutation_check.py` 两个文件）。下表每一行都写明是在哪个提交上跑的；更早的提交（`a3ab36c` 等）上跑出的数字作废，不引用。

### 12.1 各套件

| 套件 | 命令 | 结果 | 证据等级 |
|---|---|---|---|
| JVM 单测，六个模块（`4be0524`） | `./gradlew … --continue :capture:test --rerun :platform:testDebugUnitTest --rerun :app:testDebugUnitTest --rerun :asr:testDebugUnitTest --rerun :sync:test --rerun :syncplatform:testDebugUnitTest --rerun` | **588 个全过，0 失败、0 错误、0 跳过**：`:capture` 72、`:platform` 84、`:app` 89、`:asr` 112、`:sync` 221、`:syncplatform` 10（每个 JUnit XML 的时间戳都晚于本轮开始）；Gradle 整个调用 14 秒 | E2 |
| 对着真 Python 网关的 JVM 测试（`4be0524`） | `./gradlew … :sync:realGatewayTest --rerun` | **12 个全过**（`.venv` 的 Python 3.12.16、自签 TLS、临时数据目录、随机端口；XML 时间戳晚于本轮开始）；Gradle 整个调用 22 秒 | E2 |
| 构建（`4be0524`） | `./gradlew … :app:assembleDebug :app:assembleDebugAndroidTest :syncplatform:assembleDebugAndroidTest :platform:assembleDebugAndroidTest` | 成功（四个 APK 都在；Gradle 整个调用 6 秒，185 个任务里 183 个 `UP-TO-DATE`：`23afdff` 之后没有改过任何生产代码，对构建产物这是有效的） | — |
| Python 验收工具（三个目录一起跑，`4be0524`） | `AURI_GRADLE_ARGS='…' .venv/bin/pytest execution/acceptance/android-sync execution/acceptance/android-capture execution/acceptance/asr-android -rs` | **727 个全过，没有跳过**（android-sync 539，其中 494 个是变异表自检；android-capture 57；asr-android 131）；4 分 18 秒，含真实调用 Gradle 的几个测试 | E2 |
| 全仓库（`4be0524`） | `.venv/bin/pytest tests/contract tests/integration tests/security -rs` | **492 个全过，没有跳过**；3 分 42 秒 | E2 |
| 模拟器验收，含 Ticket 14 阶段（**`23afdff`**；`4be0524` 只改了 JVM 测试与变异表，没有动生产代码和仪器测试，所以没有重跑） | `AURI_ALSO_ASR_ACCEPTANCE=1 execution/acceptance/android-sync/run_emulator_acceptance.sh` | exit 0，两个阶段合计 2 分 21 秒（一次性 AVD：API 35、arm64-v8a、`google_apis`）。`:syncplatform` 仪器测试 **30 个全过，且逐个 `@Test` 核对都真的跑了**（Keystore 10、网络与发现 2、杀进程 3、真网关 1、任务库契约 6、发布事务 7、启动围堵 1）；宿主机回头查网关库：1 台设备、1 条 Record、1 版，正文与哈希一致、来源设备正确。Ticket 14 的 `:app` 仪器测试 **29 个全过**（包括被本票改写的 `OfflineProofTest` 三个：已安装的包只申请网关链路需要的网络权限、网络可用时转录零字节、飞行模式下转录成功），合同校验 16 个与 5 个场景都是 0 个问题，三次冷启动的网络字节增量都是 0。脚本退出后锁、AVD、宿主机网关进程、adb 服务都已清理 | E2（模拟器，不是 E3） |
| 变异检查，162 条 | 在 `git archive` 快照里：`mutation_check.py --in-place`。先整表（`23afdff`，159 条），补测试之后重跑整个循环组（`4be0524`，`--match loop-`，17 条） | 整表（`23afdff`）**158 条 `CAUGHT`、1 条 `SURVIVED`、0 条 `INVALID`**（存活的 `loop-pass-inside-collectlatest` 见 12.3）；补测试之后（`4be0524`）循环组 **17/17 `CAUGHT`**；其余 145 条沿用 `23afdff` 那一轮的 `CAUGHT`，没有在 `4be0524` 上重跑（见 12.2） | E2 |
| 真机、真局域网、真相机扫码、真 mDNS 广播 | 第 4 节 | **`NOT_RUN`**（本机没有手机） | — |

### 12.2 变异检查整表重跑

**第一次整表**（针对提交 `23afdff` 的 `git archive` 快照，159 条，我这一侧一次只开一个 Gradle）：21:27 开始、22:16 结束，约 49 分钟；**158 条 `CAUGHT`、1 条 `SURVIVED`、0 条 `INVALID`**，退出码 1。存活的是 `loop-pass-inside-collectlatest`；harness 的说明是“有测试失败，但预期会失败的这些没有失败：['正在发送的请求不会被任务表的变化打断']；实际失败：['SyncLoopTest.网络断开……']”——整套测试虽然红了，但红在一条无关的测试上，这条规矩本身没有被钉住（原因与修复见 12.3）。

**补测试之后**（针对提交 `4be0524` 的全新 `git archive` 快照，与提交逐字节核对一致）：先跑相关的 5 条（`loop-pass-inside-collectlatest`、新增的 `loop-wake-cancels-running-pass`、`loop-network-change-deduplicated`、`loop-network-event-keeps-backoff`、`loop-network-event-does-not-rearm`），5/5 `CAUGHT`，红的测试正是预期的那几条（`loop-pass-inside-collectlatest` 由新测试变红，另有 `网络断开……` 一条；`loop-wake-cancels-running-pass` 由旧测试 `正在发送的请求不会被任务表的变化打断` 与另外两条变红；三条网络类各由对应的新测试变红）；然后把整个循环组（`--match loop-`，17 条）整组重跑：**17/17 `CAUGHT`**，约 6 分钟。其余 145 条（不属于循环组）**没有**在 `4be0524` 上重跑：`4be0524` 对 `23afdff` 只往 `SyncLoopTest.kt` 里加了测试、往变异表里加了条目，这 145 条被变异的文件与对应的测试都没变，结果沿用 `23afdff` 那一轮的 `CAUGHT`（只加测试不会让已经被抓住的变异存活）。

**对照实验**（证明新测试补的是真缺口）：在快照里把 `SyncLoopTest.kt` 换回 `23afdff` 的旧版（17 个测试），跑 `loop-network-event-keeps-backoff` 与 `loop-network-event-does-not-rearm`：**两条都 `SURVIVED`**，“变异后 17 个测试全部通过”；换成新版（19 个）后各由 1 个测试抓住。这个实验只改了快照里的测试文件，工作树一个字节都没动。

内存：整表与重跑期间交换区用量 ≤ 1.3 GB（共 2 GB），空闲内存 ≥ 38 %，在守则以内。

### 12.3 接手这一轮发现并修好的事

- **`SyncEngineTest` 里一条测试管不到新任务**。“网关换了公钥……也没有任何继续的路”原来只数那条已经停下的任务：已停下的任务在 `attempt()` 里被状态机先拒绝，根本走不到 `usableGateway()` 这道闸，所以把闸拿掉，这条测试照样绿。实证：在快照里换回旧版测试再跑变异 `engine-ignores-connection-gate`，结果是 `SURVIVED`（预期变红的“没有任何继续的路”没有变红）；用现在的测试，同一条变异 `CAUGHT`（36 个里 3 个变红，包括这一条）。修复是 `fb96e5d`（测试里再发布一条新任务，几轮同步之后既不能多出连接尝试、也必须仍是 PENDING）。**也就是说，此前“变异检查全部被抓”在那个提交点并不成立**；第 9 节里“全部 CAUGHT”的说法以本节为准。
- **同步循环把“默认网络换了一个”吞掉了**（代码缺陷，修复 `c8f7cf9`）。`SyncLoop.watchNetwork` 对网络信号做了 `distinctUntilChanged()`。手机常开着移动数据时，Wi-Fi 断开是默认网络从 Wi-Fi 换到蜂窝、Wi-Fi 回来又换回去，信号一直是“可用”，两次切换都被去重吞掉，补传只能干等探测退避（封顶 15 分钟）。第 4 节原来写的“也关掉移动数据”恰好把它掩盖了；原来的 `FakeNetwork` 是 `StateFlow`，也表达不了“可用→可用”，所以旧测试看不到。实证（先红后绿）：新测试 `SyncLoopTest.默认网络换了一个而信号一直是可用……` 先红——`expected:<STORED> but was:<WAITING_GATEWAY>`（`SyncLoopTest` 17 个里只有它红）；去掉去重之后 `:sync` 219 个全绿；新变异 `loop-network-change-deduplicated`（把去重加回去）要由这条测试抓住（结果见 12.2）。真机上系统在默认网络切换时到底回调什么（只有 `onAvailable`，还是先 `onLost`）只有 E3 能回答，第 4 节第 6 步的第二遍专门去看。
- **`SyncRuntime.start()` 没有围堵启动准备**（代码缺陷，修复 `23afdff`）。`start()` 跑在 `AuriRuntime` 共用的作用域上（`SupervisorJob + Dispatchers.Default`，没有异常处理器），`pairing.refresh()` 与 `engine.resolveAfterRepair()`（要读任务表）逃出来的异常在 Android 上就是应用崩溃，连正在录的音一起带走。我最初把它记成“低严重度”（只想到“同步没启动”），复查时发现它和录音同进程，改判为应当修。实证（先红后绿，模拟器）：新仪器测试 `SyncRuntimeStartupTest`（配好对、删掉任务表模拟读库出故障）先红——`:syncplatform` 30 个里只有它红，`expected null, but was:<kotlin.Unit>`（`start` 的协程被异常带死）；围堵之后 30/30 全绿，且日志里有 `start.failed SQLiteException` 一类的一行（只有异常类名）。它是 Android 专属的装配代码，JVM 里没法测，所以**没有变异条目**，证据只有模拟器上的这一条仪器测试（E2）。同类的界面侧状态流没有改，原因见第 11 节。
- **“一轮同步不跑在会被取消的协程里”这条规矩，测试没有真的钉住它**（测试太松，修复 `4be0524`）。`23afdff` 的整表变异检查里，`loop-pass-inside-collectlatest`（把 `engine.runOnce()` 挪进任务表的 `collectLatest`）是 `SURVIVED`：变异之后红的只有无关的 `网络断开……` 一条，预期要红的 `正在发送的请求不会被任务表的变化打断` 没有红。**所以“变异检查全部被抓”在 `23afdff` 这个提交点同样不成立。** 原因：专用循环永远先抢到引擎锁，那条测试里在途的请求从来不是发在会被取消的协程里；这个变异只有在“专用循环正忙着发别的任务、重试计时器响了，`Mutex` 先来先得把锁先交给 `collectLatest` 里排队的那一轮”时才会伤人——那一轮一改任务状态，`collectLatest` 就把它连同在途的请求一起取消，留下“结果不确定”的任务，之后多一次状态查询和一次重发。修复：新增 `SyncLoopTest.别的任务正占着这一轮时重试计时器响了……`，在真实代码上是绿的（`SyncLoopTest` 19 个，0 失败），变异下变红，`loop-pass-inside-collectlatest` 改由它负责；旧测试 `正在发送的请求不会被任务表的变化打断` 则配上了它真能抓住的变异 `loop-wake-cancels-running-pass`（把 `runPasses` 改成对信号 `collectLatest`，新触发就会取消在途的一轮；3 个测试变红，包括旧测试），否则它就成了没有变异约束的测试。
- **网络事件让探测退避从头开始、让睡着的旧计时器重新计时，原来没有测试约束**（测试缺口，`4be0524`）。`SyncLoop.watchNetwork` 里的 `probeAttempt = 0` 与 `rearm.value += 1`：去掉任何一个，退避就会停在网络恢复之前涨到的间隔上（最长 15 分钟）；刚连上 Wi-Fi 那一刻网关未必已经应答，探不到就要干等。新测试 `SyncLoopTest.网络恢复或默认网络换了一个：立刻探一次，并且退避从头开始……` 与两条变异 `loop-network-event-keeps-backoff`、`loop-network-event-does-not-rearm`。对照实验（12.2）：旧版 `SyncLoopTest` 下这两条变异都 `SURVIVED`，新版下各被这一条测试抓住。
- **Gradle 把测试任务判成 `UP-TO-DATE` 不算重新跑过**（第 3 节已写）。
- 订正了本文件两处与事实不符的话（`.gitignore` 没有改；真机步骤里 `pair` 要加 `--host`，见 `services/gateway/OPERATIONS.md`）。

### 12.4 对既有文件的改动（共享文件，给合并的人）

都是最小的加法，除非注明。没有动 `contracts/`、`services/gateway/`、`tests/`、`.github/`、`.gitignore`、`CaptureCoordinator.kt`、`HomeViewModel.kt`。

| 文件 | 改了什么 |
|---|---|
| `apps/android/settings.gradle.kts` | +2：`include(":sync")`、`include(":syncplatform")` |
| `apps/android/gradle/libs.versions.toml` | +9：ZXing 3.5.4 与 CameraX 1.4.1 的固定版本（哈希见第 10 节） |
| `apps/android/app/build.gradle.kts` | +1：`implementation(project(":syncplatform"))` |
| `apps/android/app/…/AuriRuntime.kt` | +17/−2：`library` 改成 `SyncStorage.library(...)`（Record 库与待同步任务表同库）；新增 `sync`、`syncViewModelFactory`、`manualPairingEntryAvailable`（调试版才有手动输入配对载荷） |
| `apps/android/app/…/ui/HomeScreen.kt` | +2/−1：多一个可选参数 `onOpenSync`（“同步与配对”入口按钮） |
| `apps/android/app/…/MainActivity.kt`、`AuriApplication.kt` | +15/−1：同步页的导航（`onSyncPage`）、`SyncViewModel`、回到前台时 `sync.syncNow()`；+1：`sync.start()` |
| `apps/android/platform/…/SqliteRecordLibrary.kt` | +13：追加式接缝 `extraSchema`、`changeSignal`、`withDatabase`，不改 `SCHEMA_VERSION` |
| `apps/android/platform/build.gradle.kts` | +2/−1：隐私守卫依赖的编译任务从四个模块扩到六个 |
| `apps/android/platform/…/NoPhoneMicPathTest.kt` | +4/−4：守卫扫描的模块从四个扩到六个（`:sync`、`:syncplatform`） |
| Ticket 14 的 `OfflineProofTest.kt`（`:app` 仪器测试）、`NoNetworkManifestTest.kt`（`:app` JVM 测试） | **改写，不是加法**：应用必须声明 `INTERNET`，原来“包里没有 `INTERNET`”的整体证据不再成立（冲突与替代证据见第 8 节） |
| `execution/acceptance/asr-android/` 的 `README.md`、`contract_check.py`、`test_check_instrumentation.py`、`test_contract_check.py` | 登记被改写的离线证明新增的场景 `online-zh` 与新的测试名；README 加指向第 8 节的说明 |
| `CLAUDE.md` | +14：只动“命令”一节，补上本票的命令 |
