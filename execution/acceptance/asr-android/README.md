# Ticket 14 验收：一台 Android 手机上跑通本地转录

对应 `.scratch/auri-v1/issues/14-asr-on-one-android-phone.md`（GitHub issue #15），PRD 引用：FR-ASR-001/004、NFR-006（RTF）、NFR-008（离线）、T-021/022/023/026。

**状态（2026-11-18）**：代码、JVM 单元测试、模拟器仪器测试全部通过。**没有任何真机（E3）结果**：本机没有 Android 手机，所有真机列都是 `NOT_RUN`，产品负责人的真机步骤在第 4 节。模拟器数字是 **E2**，只能说明管线通了，**不代表任何手机的性能**（模拟器跑在 Apple Silicon 主机上，硬件加速，比真机快得多）。

证据等级沿用 PRD 第 5.2 节：JVM 单测与模拟器 = E2；真机 = E3；没有真机/账号/签名的项标 `NOT_RUN` 或 `BLOCKED_EXTERNAL`，不用模拟或伪造替代。

## 1. 这里有什么

| 路径 | 作用 |
|---|---|
| `apps/android/` | Gradle 工程：库 `:asr`（引擎适配、清单与校验、重采样、合同输出）+ 极简宿主 `:app`（仪器测试 + debug 专用的“转录这个文件”入口；真正的录音界面是 Ticket 16） |
| `apps/android/engine/sherpa-onnx.properties` | 引擎固定信息：sherpa-onnx v2.0.1（Apache-2.0）预编译 AAR 的大小与 SHA-256、上游提交、ONNX Runtime 版本 |
| `apps/android/manifests/*.manifest.json` | 模型清单：名称、版本、文件（大小 + SHA-256）、许可证、语言、采样率；代码与具体模型无关，换模型只换清单 |
| `prepare_model.py` | 下载模型包到仓库外缓存 `~/.cache/auri/`，校验哈希，只解出清单列出的文件和示例音频 |
| `install_on_device.sh` | 把模型与示例音频放进应用私有目录（模拟器与真机共用） |
| `run_device_acceptance.sh` | 设备侧一键验收：构建 → 安装 → 仪器测试 → 三次冷启动基准 → 拉回输出 → 合同校验 → 汇总表 |
| `contract_check.py` | 用真实 `contracts/schemas/*.json`、语义规则 SR-01/SR-05、转录状态机转移表校验 Kotlin 写出的 JSON |
| `benchmark_summary.py` | 校验 `run-report.json`（RTF 定义、离线字节、峰值内存等），渲染 Markdown 结果表 |
| `test_*.py` | 上面这些 Python 工具的测试（含变异验证，见第 5.1 节） |

模型权重、示例音频、AAR、APK、Gradle 缓存都**不入库**：它们在 `~/.cache/auri/` 与 `apps/android/build/`（均被 `.gitignore` 覆盖）。

## 2. 清单逐项对照

| # | 要求 | 覆盖它的测试 / 文件 | 证据等级 |
|---|---|---|---|
| 1 | 引擎经原生绑定集成，版本固定，构建里有 AAR 哈希检查 | `test_engine_pin_guard.py`（7 个，真实跑 Gradle：翻一个字节、截断、缺失都拒绝构建，只接受固定的那份）；设备上 `EnginePinTest`（原生库自报版本、ONNX Runtime 版本、提交都等于固定值） | E2（模拟器），AAR 守卫是构建期检查，与设备无关 |
| 2 | 模型按清单加载，哈希不符在碰引擎之前拒绝 | JVM：`ModelManifestTest`（17）、`ModelVerifierTest`（9，含篡改与截断文件）、`ModelLoaderTest`（4）、`ShippedManifestTest`（4，真清单）；设备：`ModelRefusalOnDeviceTest`（4，真 239 MB 模型翻一个字节 / 截断到 1 MiB / 未安装，用计数包装器证明引擎一次都没被创建，随后完好模型仍能用） | E2 |
| 3 | 真正的重采样：48 kHz / 44.1 kHz 16 位 → 16 kHz float | `ResamplerTest`（16：合成正弦保频保幅、带外成分被抑制、不折叠、不产生镜像、直流增益为 1、零相位等）；设备上 `RealEngineTranscriptionTest.speechResampledFrom48kAnd44k1TranscribesLikeTheOriginal`；算法与引用见第 8 节 | E2 |
| 4 | 飞行模式下可用；证明模型路径不联网 | 三层证据，见 `OfflineProofTest`（3）与 JVM `NoNetworkManifestTest`（3）：①合并后的清单与已安装的包都没有 INTERNET 权限；②系统对该 UID 的网络 socket 一律“权限被拒”（同时用 AF_UNIX 做正对照）；③模拟器系统飞行模式下整条转录 SUCCEEDED，且该 UID 的收发字节增量为 0。真机飞行模式：`NOT_RUN`，步骤见第 4 节 | ①②③ 都是 E2；真机 E3 `NOT_RUN` |
| 5 | 记录 RTF、峰值内存、冷启动 | `run_device_acceptance.sh` 三次 `am start -S -W` 冷进程各跑一次样例音频，`benchmark_summary.py` 校验并出表；结果在第 5.2 节 | 模拟器 E2；真机列 `NOT_RUN` |
| 6 | 输出用合同类型，JSON 通过真实合同校验 | JVM：`ContractJsonTest`（5）、`ContractOutputTest`（7 个场景导出 JSON）；Python：`test_contract_output.py`（3，真实跑 Gradle 再用 `jsonschema` 校验，含 Kotlin 与 Python 对含 😀 文本的 SHA-256 一致）；设备输出：`contract_check.py --preset device`（15 个场景）与 `--preset runs`（4 个场景）全部 0 问题 | E2 |

另：`README`（本文件）、`CLAUDE.md` “命令”一节、`.gitignore` 都已更新。

## 3. 在本机怎么跑

前置：`source ~/.config/auri/android-env.sh`（JDK 17、Android SDK 35、build-tools 35.0.0、NDK、emulator；工具链记录见 `execution/baseline/android-toolchain.md`）；仓库根目录的 `.venv`（Python 3.12，依赖 `tests/contract/requirements.txt`：`jsonschema==4.26.0`、`referencing==0.37.0`、`pytest==9.2.0`）。系统自带的 python3.9 不能用来跑仓库测试。

```bash
source ~/.config/auri/android-env.sh
cd apps/android

# JVM 单元测试（不需要设备）：:asr 102 个 + :app 19 个
./gradlew :asr:testDebugUnitTest :app:testDebugUnitTest

# 构建 debug APK 与仪器测试 APK
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest

# Python 验收工具与合同输出校验（会真实调用 Gradle，需要 JAVA_HOME；约 1 分钟）
cd ../..
.venv/bin/pytest execution/acceptance/asr-android
```

AAR 与模型包第一次需要联网下载（AAR 49,441,341 字节；模型包 163,002,883 字节），之后走 `~/.cache/auri/` 缓存；哈希不符一律拒绝并删除坏数据，不会静默换一份。如果 GitHub 不通或要走镜像，Gradle 依赖可以用 `-I tools/mirror-init.gradle`（可选，见该文件注释；镜像下的 Gradle 依赖 **没有** 校验和，是信任假设，见第 7 节）。

### 在模拟器上跑设备侧验收（E2）

```bash
# 一次性：建一个一次性 AVD（Pixel 6 配置，Android 15 / API 35 / google_apis / arm64-v8a，RAM 4096 MB，data 10G）
export ANDROID_AVD_HOME="$HOME/.cache/auri/avd"
avdmanager create avd -n auri-t14-asr -k "system-images;android-35;google_apis;arm64-v8a" -d pixel_6
# 再把 $ANDROID_AVD_HOME/auri-t14-asr.avd/config.ini 里的 hw.ramSize 改成 4096、disk.dataPartition.size 改成 10G

# 无头启动（在一个常驻终端里跑，别放在会随命令结束而退出的子 shell 里）
emulator -avd auri-t14-asr -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect

# 另一个终端：
export ADB_LIBUSB=1                       # macOS 必需，见第 7 节“已知环境问题”
export AURI_GRADLE_ARGS="-I tools/mirror-init.gradle"   # 可选
execution/acceptance/asr-android/run_device_acceptance.sh

# 用完删掉 AVD 与磁盘镜像
adb emu kill
avdmanager delete avd -n auri-t14-asr
```

脚本做七件事：①`prepare_model.py`；②构建两个 APK；③`adb install -r -t` 安装（**不用** `gradle connectedAndroidTest`：它跑完会卸载应用，带走私有目录里的模型），再放模型与样例；④`am instrument` 跑全部仪器测试（必须 `OK (N tests)`，N=0 也算失败）；⑤三次 `am start -S -W` 冷进程基准；⑥`adb exec-out run-as … tar` 拉回 `files/contract-out` 与 `files/runs` 到 `apps/android/build/device-out/`；⑦两个严格预设校验（多出来的场景目录也算失败）并出汇总表 `benchmark.md`。任何一步不过就以非零退出。

## 4. 产品负责人：真机验收步骤（`NOT_RUN`，等一台真机）

适用：Android 12 及以上（minSdk 31）、arm64 的手机；**用 USB 线连电脑**（飞行模式会断掉无线调试）。本票的转录入口只吃 WAV 文件，没有任何麦克风代码，**与耳机无关**；“录音必须来自蓝牙耳机麦克风”的约束由 Ticket 16 落实。

1. **准备电脑**（一次性）：装 JDK 17、Android SDK（platform 35、build-tools 35.0.0）、platform-tools；在仓库根目录 `python3 -m venv .venv && .venv/bin/pip install -r tests/contract/requirements.txt`。手机：开发者选项 → 打开 USB 调试；`adb devices` 里能看到它且状态是 `device`。多台设备时 `export ANDROID_SERIAL=<序列号>`。macOS 上先 `export ADB_LIBUSB=1`。
2. **准备模型**：`.venv/bin/python execution/acceptance/asr-android/prepare_model.py`（约 163 MB，校验 SHA-256，示例音频是模型包自带的 5 段官方测试音频，不含任何个人录音）。
3. **构建并安装**：
   ```bash
   cd apps/android && ./gradlew :app:assembleDebug && cd ../..
   adb install -r -t apps/android/app/build/outputs/apk/debug/app-debug.apk
   ```
4. **放模型与样例**：`execution/acceptance/asr-android/install_on_device.sh`（放进应用私有目录，只有这个应用读得到）。
5. **转录一次并取日志**（**必须带 `-S -W`**：应用已在前台时不带 `-S` 的 `am start` 只会把任务提到前台，Intent 不会送达，什么也不会跑；`-S` 还保证每次是全新进程，冷启动才是真冷启动）：
   ```bash
   adb logcat -c
   adb shell am start -S -W -n app.auri.android/.TranscribeFileActivity \
     --es wav samples/zh.wav --es out phone-zh --ei warm_repeats 3
   adb logcat -d -s AuriAsr:I      # 期望一行：state=SUCCEEDED rtf=… out=runs/phone-zh
   adb shell run-as app.auri.android cat files/runs/phone-zh/run-report.json > phone-zh-run-report.json
   ```
   日志与界面只写状态和数字，不写转录正文；正文只在 `files/runs/phone-zh/revision-content.json`（私有目录里）。
6. **基准**：把第 5 步重复三次，`--es out` 依次换成 `phone-bench-1`、`phone-bench-2`、`phone-bench-3`，每次都取回各自的 `run-report.json`，然后：
   ```bash
   .venv/bin/python execution/acceptance/asr-android/benchmark_summary.py --evidence "E3 真机 <型号> / Android <版本>" --warm 3 \
     "冷启动 1=phone-bench-1-run-report.json" "冷启动 2=phone-bench-2-run-report.json" "冷启动 3=phone-bench-3-run-report.json"
   ```
   把输出的表贴到第 5.2 节的真机行。校验不过（RTF 对不上定义、网络字节没测到、峰值内存读不到等）会直接打印原因并非零退出，不会出表。
7. **飞行模式（E3 的离线证据）**：
   1. 手动打开飞行模式（下拉通知栏），**并确认 Wi-Fi、移动数据、蓝牙都是关的**（有的手机进飞行模式后允许再手动打开 Wi-Fi，别打开）；USB 线保持连接；
   2. 重复第 5 步，`--es out phone-airplane-zh`；
   3. 期望：`state=SUCCEEDED`；`run-report.json` 里 `process.uid_network_bytes_delta` 是 `0`（不是 `null`；`null` 表示这台手机读不到 TrafficStats，需要另想办法，不能当通过）；文本与非飞行模式的 `revision-content.json` 一致或几乎一致；
   4. 关掉飞行模式。
8. **合同校验（可选）**：
   ```bash
   mkdir -p phone-out && adb exec-out run-as app.auri.android tar -c -C files runs | tar -x -C phone-out
   .venv/bin/python execution/acceptance/asr-android/contract_check.py phone-out/runs phone-zh=SUCCEEDED phone-airplane-zh=SUCCEEDED
   ```
9. **也可以直接跑整套**：`execution/acceptance/asr-android/run_device_acceptance.sh`（会用 shell 命令切换飞行模式；个别厂商 ROM 可能拒绝，以第 7 步的手动流程为准）。脚本在真机上只标“真机运行（E3 待运行者核对后登记）”，是否算 E3 由你核对设备与步骤后在下表登记。
10. **请回传**：各 `*-run-report.json`、`adb logcat -d -s AuriAsr:I` 输出、手机型号与系统版本。**不要回传音频或转录正文。**

## 5. 结果

### 5.1 自动化测试（2026-11-18，合并 `origin/integration/auri-v2` 之后）

| 套件 | 命令 | 结果 |
|---|---|---|
| `:asr` JVM 单测 | `./gradlew :asr:testDebugUnitTest` | 102 通过，0 失败，0 跳过 |
| `:app` JVM 单测 | `./gradlew :app:testDebugUnitTest` | 19 通过，0 失败，0 跳过 |
| 仪器测试（API 35 arm64 模拟器，真引擎 + 真模型） | `run_device_acceptance.sh`（第 4 步） | `OK (21 tests)`：EnginePinTest 2、TestAudioTest 3、RealEngineTranscriptionTest 7、ModelRefusalOnDeviceTest 4、OfflineProofTest 3、TranscribeFileActivityTest 2 |
| 设备输出的合同校验 | 同上第 7 步 | `contract-out` 15 个场景 0 问题；`runs` 4 个场景 0 问题 |
| Python 验收工具 | `.venv/bin/pytest execution/acceptance/asr-android` | 67 通过（`test_benchmark_summary` 18、`test_contract_check` 30、`test_contract_output` 3、`test_engine_pin_guard` 7、`test_prepare_model` 9） |
| 合同 / 集成 / 安全（全仓库） | `.venv/bin/pytest tests/contract tests/integration tests/security` | 见交付报告 |

**变异验证**（证明测试真能失败；每次改完立即还原，已用 `diff` 核对）：
- 设备：关掉加载器的哈希比对 → `oneFlippedByteInTheRealModelIsRefusedBeforeTheEngineIsTouched` 失败（实际终态是 `FAILED`：被篡改的模型进了引擎）；不真正打开飞行模式 → `transcriptionSucceedsInAirplaneModeWithoutAnyNetworkBytes` 以“飞行模式没有生效”失败；给应用加上 INTERNET 权限 → `theInstalledPackageRequestsNoInternetPermission` 与 `theKernelRefusesEveryNetworkSocketButStillAllowsLocalOnes` 两个失败（注意这时飞行模式那条仍然通过：单靠飞行模式不能证明不联网，所以才要三层证据）。
- Python：`benchmark_summary.py` 六种变异（关掉 RTF 定义检查、允许非零网络字节、允许未知字段、允许峰值内存下降、忽略终态、把 KiB 当 1000）全部被抓；开发时还手工变异验证过 `contract_check.py`（5 种）与 `prepare_model.py`（3 种），全部被抓。
- JVM：Kotlin 侧对 SR-01 用 UTF-16 算哈希的变异被 `test_contract_output.py` 抓到；合并清单里加 INTERNET 被 `NoNetworkManifestTest` 抓到。
- 以上变异都是开发时手工做的，变异本身不入库（入库的是能抓住它们的测试）。

### 5.2 性能

被测音频：模型包自带的 `zh.wav`（5592 ms，16 kHz 单声道）。RTF = 推理耗时 / 音频时长（NFR-006）；**RTF 只含引擎推理**，校验（对 239 MB 模型算 SHA-256）和引擎加载算在“冷启动”里。每次冷启动都是 `am start -S -W` 的全新进程，但**文件页缓存是热的**（无 root 不能清缓存），所以真机“开机后第一次”会比这里更慢。线程数 2（假设，见第 7 节）。

**E2 · API 35 arm64 模拟器（Pixel 6 配置，`hardware=ranchu`，Apple Silicon 主机硬件加速）· 不代表任何真机**

| 运行 | 音频 ms | 冷启动 ms（进程启动→首个结果） | 校验 ms | 引擎加载 ms | 推理 ms | RTF | 同引擎再推理 ms | VmHWM MiB | VmRSS MiB | 网络字节增量 |
|---|---|---|---|---|---|---|---|---|---|---|
| 冷启动 1 | 5592 | 1688 | 390 | 897 | 105 | 0.0188 | 105, 103, 102 | 491.8 | 489.9 | 0 |
| 冷启动 2 | 5592 | 1683 | 497 | 838 | 105 | 0.0188 | 109, 106, 102 | 492.7 | 490.9 | 0 |
| 冷启动 3 | 5592 | 1664 | 419 | 913 | 104 | 0.0186 | 103, 102, 103 | 492.1 | 490.2 | 0 |
| 中位数 | 5592 | 1683 | 419 | 897 | 105 | 0.0188 | — | 492.1 | 490.2 | — |

引擎 sherpa-onnx 2.0.1（ONNX Runtime 1.28.2，提交 `af2d8d81`），模型 `sensevoice-small-int8` `2024-07-17+7d1efa2138a6`。VmHWM/VmRSS 来自 `/proc/self/status`（进程历史峰值 / 当前驻留）。

**E3 · 真机**

| 手机型号 / 系统 | 冷启动 ms | RTF | VmHWM MiB | 飞行模式转录 | 网络字节增量 |
|---|---|---|---|---|---|
| （待产品负责人填） | `NOT_RUN` | `NOT_RUN` | `NOT_RUN` | `NOT_RUN` | `NOT_RUN` |

**怎么读这些数字**：仪器测试进程里的 `run-report.json`（`files/contract-out/*`、`files/runs/activity-zh`）**不是基准**：那个进程在此之前已经加载过模型，`start_to_first_result_ms` 和 `vm_hwm_kb_before` 都被前面的测试污染了（本次实测 `activity-zh` 是 14092 ms、起步就已有约 499 MiB；冷进程 `bench-1` 是 1688 ms、起步约 144 MiB）。性能只看 `bench-*`（每个都是全新进程）。

### 5.3 一次观察到的识别输出（E2，回归基线而非准确率）

设备测试里 `RealEngineTranscriptionTest` 的期望文本是**这份 int8 模型包在这个引擎上的实测输出**，不是人工标注的真值，用相对编辑距离 ≤ 0.2 比较：它们只证明管线（WAV 读取 → 重采样 → 特征 → 引擎 → 解码）没被改坏，**不证明识别准确率**。准确率评测与默认模型决策属于 Ticket 15。五段样例（zh/en/ja/ko/yue）都成功，语言标签与样例语言一致；例如英文样例被识别成 “…50 pieces of code.”，这是模型的实际输出。

## 6. `NOT_RUN` / `BLOCKED_EXTERNAL`

| 项 | 状态 | 原因 / 下一步 |
|---|---|---|
| 任意真机上的转录、RTF、峰值内存、冷启动 | `NOT_RUN`（E3） | 本机没有 Android 手机；按第 4 节执行 |
| 真机飞行模式下转录 | `NOT_RUN`（E3） | 同上；模拟器的飞行模式只是“系统没有默认网络”的 E2 证据 |
| 连续多次推理后的发热降频、低内存机型 | `NOT_RUN` | 需要真机，且不在本票清单里 |
| 蓝牙耳机录音 → 转录链路 | 不在本票范围 | Ticket 16 及之后；本票入口只吃 WAV 文件 |
| 识别准确率（WER/CER）与默认模型 | 不在本票范围 | Ticket 15 / ADR-0017；SenseVoice 只是第一个占位模型 |
| SenseVoice 权重的再分发许可 | `UNCONFIRMED` | 模型协议是 FunASR Model Open Source License v1.1，清单里 `redistribution` 标 `unconfirmed`；本仓库不分发权重，也不随 APK 打包，只在评测时从本地缓存取 |

## 7. 发现、已知限制与环境问题

**发现（需要后续票注意）**
- **SenseVoice 对纯数字静音也会出字**：3 秒全零音频不会返回空文本，而是编出一两个字（观察到韩文 “그.”），合同状态是 `SUCCEEDED` 而不是 `NO_SPEECH`。所以 `NO_SPEECH` 只能靠“引擎返回空文本”触发，静音过滤必须由会话内的 VAD 在送进引擎前做（ADR-0016），否则静音会变成幻觉文本入库。`RealEngineTranscriptionTest.digitalSilenceIsNotRecognisedAsNoSpeechByTheEngineAlone` 把这个事实钉成测试：换了模型或加了前置闸门导致它不成立时测试会失败，逼着相关人员有意识地更新；`NO_SPEECH` 的状态映射本身由 `LocalTranscriberTest` 用假引擎覆盖。
- **逆文本规范化（ITN）已开启**：输出里数字是阿拉伯数字（“9点”“5点”）。关掉会改变文本，这个选择留给 Ticket 15。
- **模型校验是每次加载都算一遍 SHA-256**：对 239 MB 模型，模拟器上 390–497 ms；真机会更慢。是否缓存校验结果（例如记录 mtime+大小）没有做，留给产品化时决定。
- **Android 对缺 INTERNET 的应用，创建网络 socket 返回的 errno 因内核机制而异**：API 35 模拟器实测是 `EPERM`，旧内核是 `EACCES`。测试接受这两种“权限被拒”，其他错误码（如 `EAFNOSUPPORT`）不算数。

**限制 / 取舍**
- 模型文件校验发生在加载时，校验通过到引擎真正读取之间存在同 UID 进程替换文件的窗口（TOCTOU）。模型放在应用私有目录，同 UID 的恶意进程不在本票的威胁模型内。
- `TranscribeFileActivity` 在 `src/debug/` 里，且 `android:exported="true"`（`am start` 从 shell 启动非导出的 Activity 会被拒），因此**只存在于 debug 构建**，release 构建里没有。它只在本应用私有目录里读写，界面和日志不带路径与正文。
- 引擎只用 CPU（provider `cpu`），线程数 2 是假设（`SherpaOnnxEngineFactory.DEFAULT_THREADS`），Ticket 15 评测后再定。
- 单段音频最长 30 秒（清单 `limits.max_audio_duration_ms`），超过按 `FAILED` / `ASR_RESOURCE_LIMIT` 处理；长音频切分不在本票范围。
- 仪器测试需要先装好模型与样例，缺了会**失败并说明怎么装**，不会跳过（跳过会把“没测”显示成“通过”）。

**已知环境问题**
- **macOS 上 adb 的服务端可能卡死在 USB 初始化（IOKit）**：现象是 `adb devices`/`adb start-server` 永远不返回，还会堆积 fork-server 进程。解决：`export ADB_LIBUSB=1`，并在一个常驻终端里手动起服务 `adb -L tcp:5037 server nodaemon`。`ADB_MDNS_OPENSCREEN=0` 没有用。本目录的脚本默认已导出 `ADB_LIBUSB=1`。
- 长时间运行的进程（模拟器、adb 服务端）必须放在常驻终端里；放在随命令结束而退出的子 shell 里会被一起杀掉。
- `gradle connectedDebugAndroidTest` 跑完会卸载 APK，带走私有目录里的模型，所以验收脚本用 `adb install` + `am instrument`。
- Gradle 依赖（Maven Central / Google）经可选镜像时**没有校验和**，这是信任假设；固定并校验的只有 Gradle 发行版（`distributionSha256Sum`）、sherpa-onnx AAR 和模型文件。`gradle-wrapper.jar` 与 `execution/p0-spikes/android-probe/` 里已入库的那份逐字节相同（SHA-256 `2db75c40…8046`）。

## 8. 重采样算法与引用

有理数倍率的**多相（polyphase）FIR 重采样**，滤波器是**凯泽窗（Kaiser window）加窗的 sinc 低通**：Fin、Fout 约去最大公约数得到 L/M，等价于“补 L−1 个零 → 低通 → 每 M 个取 1 个”，但只计算最终要输出的点；截止频率取 min(Fin, Fout)/2（降采样挡混叠，升采样挡镜像），过渡带为截止频率的 ±10%，阻带衰减 80 dB，零相位（中心抽头为零时刻，没有群延迟）。窗参数 β = 0.1102·(A − 8.7)，抽头数 N ≈ (A − 7.95) / (2.285·Δω) + 1。实现与注释在 `apps/android/asr/src/main/kotlin/app/auri/asr/audio/Resampler.kt`。参考：
- J. F. Kaiser，“Nonrecursive digital filter design using the I0-sinh window function”，1974；Kaiser & Schafer，“On the use of the I0-sinh window for spectrum analysis”，IEEE Trans. ASSP，1980。
- A. V. Oppenheim & R. W. Schafer，《Discrete-Time Signal Processing》（凯泽窗 FIR 设计、采样率转换）。
- R. E. Crochiere & L. R. Rabiner，《Multirate Digital Signal Processing》，Prentice-Hall，1983。
- J. O. Smith，“Digital Audio Resampling Home Page”，CCRMA, Stanford：<https://ccrma.stanford.edu/~jos/resample/>。

## 9. 假设与测试接缝（任务要求不得提问，以下是我替产品负责人做的保守选择）

假设：
1. **minSdk 31、AGP 8.8.0、Kotlin 2.0.21、JUnit 4.13.2、Gradle 8.10.2** 沿用 `execution/p0-spikes/android-probe` 已有的工程约定；本票代码据我所知没有用到 API 31 才有的接口，但没有在更低版本上测过。
2. **第一个模型用 SenseVoice-Small int8**（`sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17`）：它是占位，不是默认模型决策；代码只认清单。
3. **清单目录名** `files/models/<model_id>/<model_version>/`，`model_version` 形如 `2024-07-17+<模型包 SHA-256 前 12 位>`，把哈希编进版本，换了内容就换版本。
4. **`FileTranscription` 的入口是“转录这个文件”**（WAV，16 位 PCM，单/双声道，任意 8000–192000 Hz），不带录音；这是为了让本票不碰麦克风。
5. **ASR 结果用 Revision 的 `content` 类型**承载（`source=ASR`、`execution_location=on_device`、引擎与模型字段），因为合同 1.1 没有独立的“转录结果”类型（见第 10 节）。`confidence` 为 `null`、`segments` 为空：当前引擎配置不给时间戳。
6. **冷启动基准取三次，中位数**；样例用 `zh.wav`；`warm_repeats=3`。
7. 用户规则里的“TDD 接缝需用户确认”无法确认，下面列出我选的接缝。

TDD 接缝（真实实现在设备上、假实现在 JVM 里）：
- `AsrEngine` / `AsrEngineFactory`：JVM 用假引擎，设备用 `SherpaOnnxEngineFactory`；工厂只接受 `VerifiedModel`，它的构造函数是 `internal`（`:asr` 之外的代码造不出来，`:asr` 的正式代码里只有 `ModelVerifier` 在造），所以“先校验后加载”有类型层面的保证。
- `SherpaOnnxConfigs.build`：清单 → 引擎配置的纯函数，JVM 测；引擎版本检查通过注入的 `engineVersion` 函数测。
- `ProcessProbe`（进程内存、启动时间、UID 字节数、设备信息）与 `FileTranscription` 的 `nanoTime`：JVM 用假读数与假时钟，设备用 `SystemProcessProbe`。
- 合同 JSON 文件：Kotlin 写、Python 读，是跨语言的接缝；`contract_check.py` 自己也有测试证明它会拦下坏数据。

## 10. 合同缺口（只记录，没有改 `contracts/`）

合同 1.1 的下列地方不够用；我没有绕开，只在这里记下精确位置与建议，请合同负责人决定：
1. `contracts/schemas/common.schema.json#/$defs/error_code` **没有“输入音频超过引擎最大时长”的错误码**。现在用 `ASR_RESOURCE_LIMIT`（`retryable=false`）。建议新增 `ASR_INPUT_TOO_LONG`，或在 `ASR_RESOURCE_LIMIT` 的语义说明里明确它包含这种情况。
2. 同一个枚举**没有“引擎内部失败”的通用错误码**。引擎抛异常时终态是 `FAILED`，但 `error.json` 不写（只有 `trace.json`），因为硬套别的码会说谎。建议新增 `ASR_ENGINE_FAILED`。`contract_check.py` 对 `FAILED` 允许没有错误文件，并有测试证明硬造 `ASR_ENGINE_FAILED` 会被真 schema 拦下。
3. `contracts/schemas/adapter.schema.json` 里只有 `asr_capabilities` 与 `asr_task_handle`，**没有“一次转录结果”的独立类型**；现在借用 `revision.schema.json#/$defs/content`。如果产品希望区分“转录结果”和“修订内容”（例如转录结果要带 `language`、段落时间戳而修订内容不需要），需要合同新增类型。
