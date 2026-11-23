# Ticket 22 验收：Android 的 Destination 管理与发送方式

范围：在手机上创建 / 查看 / 撤销 Destination（网关上的“收件人”，一个 Agent 一个），创建时**明确选**“自动发送”或“预览确认”；
生成 / 轮换凭证前必须过本机核验（`BiometricPrompt`：只认强生物识别，锁屏密码不行），凭证只显示一次；自动发送的初稿标未审核；
预览确认的 Destination 在用户确认之前不产生 Delivery；每条内容可标“备忘 / 请求执行”；详情页如实显示每条内容在这个 Destination 上
是已入库、已领取还是已确认（**绝不说“已处理”**）。全部走 Ticket 21 的固定公钥 HTTPS 客户端，没有新增网络栈、依赖或网络类。

证据等级：JVM 单测、对着**真实 Python 网关进程**的测试、Python 合同校验都是 E2；没有设备或模拟器（本机为省内存禁用），
所有要设备才能证明的都是 `NOT_RUN`，步骤与命令在第 4、12.4、13.3 节。

**评审修复轮（2026-11-21）**：独立评审提出 B1–B6、H1–H4、M1–M4 共 14 条发现，每条一个提交、先红后绿、推送一次，见**第 13 节**（发现表、变异、要在设备上核对的、最新验证结果、假设）。第 1–3、5–10 节里被它们取代的说法已就地改正（第 8 节的 30 条变异表是原始实现时的历史记录，其中 3 条已随 B2 删除，见 13.4）；第 12 节的数字是修复之前的历史记录，最新结果在 13.5。

**第三轮再评（2026-11-22 至 11-23）**：第 14 节是第二轮（R1–R13）；第三轮的 S1–S12、假设、残余风险、设备上要核对的、变异表整表真跑与**最新验证结果**在**第 15 节**（15.6 里有一个由本机 TUN 代理造成的 `repo` 套件失败，没有被藏起来）。第 4、6、10、13 节里被 S11 取代的几处剪贴板说法（归属标记、杀进程后补清）已就地加了指针。

## 1. 这里有什么

| 文件 | 作用 |
|---|---|
| `destination_contract_check.py`、`destination_support.py` | 校验 Kotlin 测试写出的**手机实际发给网关的请求**（创建请求、入库请求含 `publication`、追加 Delivery 的请求、网关侧观察）满足真实的 `contracts/schemas/` 与语义规则；做法同 Ticket 16/21 |
| `test_destination_contract_check.py`、`test_destination_contract_output.py` | 校验器自身（好样本通过、坏样本逐条报出）；以及真实调用 Gradle 跑 `DestinationContractOutputTest`、再逐场景校验 |
| `real_gateway_agent.py` | 一个一次性的 **Agent 客户端**：官方 MCP SDK + 与 stdio 桥接相同的固定公钥传输，`claim` / `ack` / `probe`。`:sync:realGatewayTest` 用它扮演 Agent（`RealAgent.kt`）；真机验收时也用它在电脑上领取文字（第 4 节）。凭证只经环境变量传入，不进命令行、不进输出 |
| `destination_mutation_check.py`、`test_destination_mutation_table.py` | 变异检查（第 8 节）与变异表的离线自检 |

代码在 `apps/android/`：`:sync` 的 `destinations/`（`DestinationService`、发送账接口 `DispatchStore`、状态 `DeliveryView`、核验接口 `LocalAuthenticator`）与 `gateway/`
（`DestinationModels`、创建/凭证/状态请求）；`:syncplatform` 的 `dispatch/`（`DispatchSql` + `SqliteDispatchStore`）与 `auth/`（`BiometricLocalAuthenticator`）；
`:app` 的 `destinations/`（三个视图模型，各有一个文档化的 `StateFlow`）与 `ui/`（`DestinationsScreen`、`DestinationDetailScreen`、`SendReviewScreen`、`DestinationsPage`、`SecretWindow`）。

## 2. 清单逐项对照

| # | 清单项 | 覆盖它的测试 | 证据 |
|---|---|---|---|
| 1 | 创建必须明确选发送方式，没有默认值 | `DestinationsViewModelTest`（一开始没有选发送方式：创建按钮不可用、提交不发请求；两种方式请求里就是选的那个）；`DestinationClientTest`（创建请求的构造函数不许省略发送方式；请求体与合同样本逐字段相同）；`DeliveryViewTest`（取值与合同一致、只有两种）；合同输出场景 `create-auto-send` / `create-preview-confirm`；真网关 `创建：自动发送与预览确认各建一个…` | E2 |
| 2 | 生成/轮换凭证前先核验；凭证只显示一次 | `DestinationServiceTest`（核验没通过的五种情形一个凭证请求都不发；每次现场核验、不沿用上次；凭证不进日志与字符串）；`BiometricResultsTest`（系统错误码 → 结果，没见过的码一律没通过）；`DestinationDetailViewModelTest`（复制 / 关闭 / 离开 / 退到后台之后都没有了；等待期间离开则之后到的不显示；进程被杀后新页面是空的；界面状态类不是 Parcelable / Serializable）；`CredentialSurfaceGuardTest`（源码里没有日志、偏好设置、保存状态、文件写入；读出明文的只有登记过的几处）；现有的 `BackupRulesTest` / `StorageLocationGuardTest` 原样保留（没有放宽）并对新代码同样生效：发送账在已被备份规则整域排除的库里，凭证从不落盘，新代码一处 `filesDir` 也没碰；真网关 `凭证：真网关只在签发时给一次…`（库、数据目录、网关日志、手机日志里都找不到明文；轮换后旧凭证在门口被拒）、`核验没通过…` | E2；真实 `BiometricPrompt`、`FLAG_SECURE`、剪贴板 = `NOT_RUN`（第 4 节） |
| 3 | 自动发送的初稿标未审核 | `SendReviewViewModelTest`（请求里这一版是 `not_reviewed`，界面上也标着“未审核”）、`DispatchEngineTest`（同样，请求里 `review_state=not_reviewed`）；`DestinationTextTest`（“自动发送”的说明写明发出去的是未审核的初稿）；合同输出场景 `auto-send-unreviewed`；真网关 `自动发送：转录完成后一轮同步…`（Agent 领到的就是 `not_reviewed`） | E2 |
| 4 | 预览确认的 Destination 在用户确认前不产生 Delivery（评审 B2 之后的语义：**文字照常入库**，等用户的只是这个目的地的 Delivery） | `DispatchEngineTest`（“确认之前文字照常入库、网关上没有给它的投递；确认之后对同一个 Revision 追加恰好一份，不再 PUT”；重复确认、确认后被杀、追加回答丢了、网关暂时不可用、被永久拒绝、答复里缺这个目标各一个用例）、`SendReviewViewModelTest`（同步跑多少轮都一样；用户确认只追加一个 Delivery、仍是未审核初稿；文字还没到网关时就确认的目标随第一次提交走；选择不发送是终局）；`DispatchStoreContract`（内存版与 **SQLite 版**共用，JVM 上跑真 SQLite 引擎；账上的“等用户”重启后不丢）；合同输出场景 `preview-held-before-confirm`（1 次 PUT、入库请求没有点名这个目的地、0 次追加、0 个 Delivery）与 `preview-after-confirm`（仍是 1 次 PUT；确认后恰好 1 次追加，请求体按 `revision.schema.json#/$defs/delivery_additions` 校验；1 个 Delivery）；真网关 `预览确认：文字照常入库，但用户确认之前网关上没有这个 Destination 的 Delivery…`（Agent 领不到；重复确认之后恰好一份、领到恰好一条、已入库的 Revision 没被重发）、`预览确认选了不发送…`、`自动发送与预览确认并存…` | E2；Android 自带 SQLite 上的同一套合同 `SqliteDispatchStoreOnDeviceTest` = `NOT_RUN` |
| 5 | 每条内容可标备忘 / 请求执行 | `SendReviewViewModelTest`（发出去之前可改标签；默认是备忘；请求里带的就是最后选的）；`DispatchEngineTest`；`DestinationTextTest`（说明里写明“只是数据上的标记，不会被执行，不授予额外权限”）；真网关 `请求执行只是数据上的标签…`（Agent 领到 `intent=action_request` 与 `content_trust` 标记；只收备忘的 Destination 一条也领不到、整条也没被拒收） | E2 |
| 6 | 详情页每条内容的状态：已入库 / 已领取 / 已确认，绝不说“已处理” | `DeliveryViewTest`（合同的每个投递状态都有对应阶段；不认识的状态是未知）；`DestinationServiceTest`（排队 → 已入库 → 已领取 → 已确认；问不到网关是未知；缺记录是未知）；`DestinationTextTest`（没有一句话说“已处理”）；`DestinationDetailViewModelTest`；真网关 `自动发送：…领取、确认之后手机上依次显示已领取、已确认` | E2 |
| 7 | 真机：创建 Destination，Agent 领到自动发送的文字 | 第 4 节的步骤；电脑上的等价证据是第 3 项的真网关测试（真实 MCP 客户端、真实网关，但不是真机、不是真局域网） | `NOT_RUN`（原因：没有设备；本机为省内存禁用模拟器） |

## 3. 在本机怎么跑

先 `source ~/.config/auri/android-env.sh`；多个工作树共用机器时先取共享锁 `~/.cache/auri/gradle.lock`，一次只跑一个 Gradle / pytest，Gradle 加 `--no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2`；**不要用 `./gradlew --stop`**。

```bash
cd apps/android
./gradlew :sync:test :syncplatform:testDebugUnitTest :app:testDebugUnitTest   # JVM 单测（评审修复之后的数字见 13.5；第 12.2 节是修复之前的）；输入没变会 UP-TO-DATE，要新鲜结果加 --rerun
./gradlew :sync:realGatewayTest      # 对着真实的 Python 网关与官方 MCP 客户端（共 25 个：Ticket 21 的 12 个 + 本票的 13 个，评审修复前是 9 个）；找不到 .venv 就失败，不跳过
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :syncplatform:assembleDebugAndroidTest :platform:assembleDebugAndroidTest   # 只编译打包，不运行
cd ../..
NO_PROXY=localhost,127.0.0.1,::1 no_proxy=localhost,127.0.0.1,::1 .venv/bin/pytest execution/acceptance/android-destinations   # Python 验收（含真实调用 Gradle 的合同输出校验，需要 JAVA_HOME）
.venv/bin/python execution/acceptance/android-destinations/destination_contract_check.py <输出目录> --preset jvm   # 校验 Kotlin 写出的 JSON 符合合同
.venv/bin/python execution/acceptance/android-destinations/destination_mutation_check.py --list                    # 变异检查（第 8 节），--only <id…> / --match <正则> 选条目
adb logcat -d -s AuriSync:I          # 真机：日志只有事件名、枚举与数字，没有凭证、令牌、配对码、地址、文字、目的地名字与编号
```

## 4. 产品负责人：真机 + 真局域网（`NOT_RUN`）

前提：一部 Android 手机（调试版，`./gradlew :app:installDebug` 或 `adb install -r app/build/outputs/apk/debug/app-debug.apk`）、与手机同一局域网的电脑上跑着网关、Ticket 21 的配对已经完成（“同步与配对”页显示已配对）。

```bash
# 电脑：起网关并取配对载荷（载荷里的 public_key_sha256 就是下面的 SPKI 指纹，必须是配对时取得的那个）
PYTHONPATH=services/gateway .venv/bin/python -m auri_gateway serve --data-dir /tmp/auri-gateway-data --port 8443
PYTHONPATH=services/gateway .venv/bin/python -m auri_gateway pair  --data-dir /tmp/auri-gateway-data
export AURI_GATEWAY_URL='https://<电脑的局域网地址>:8443/mcp' AURI_GATEWAY_SPKI_SHA256='<public_key_sha256>'
```

1. **创建（清单 1）**：手机“发送”页，名字留空或没选发送方式时“创建”是灰的，页面写“先选一种发送方式”；选“自动发送”、填名字、点“创建”。再按同样步骤建一个“预览确认”的。期望：两个都出现在列表里，各自标着发送方式（点“刷新”从网关取回来的也一样）；网关里存的 `send_mode` 与选的一致：电脑上 `sqlite3 /tmp/auri-gateway-data/gateway.db 'select display_name, send_mode from destinations'`（网关没有“列出 Destination”的命令行，直接读库）。
2. **凭证（清单 2）**：点“查看”→“生成凭证”。期望：弹系统的指纹 / 人脸核验（没有锁屏密码这个后备）；**取消**或核验失败 → 页面说明没有生成凭证，网关上没有凭证；没有登记任何生物识别的设备（哪怕设了锁屏密码）→ 不弹任何提示，明说没法核验、没有生成凭证（评审 B4；具体步骤见下面“核验只认强生物识别”一条）。通过后页面只显示一次凭证；**截图应当被系统挡住（`FLAG_SECURE`）**：`adb exec-out screencap -p > /tmp/auri-shot.png` 得到黑屏或失败。点“复制并关闭”后凭证面板消失、再也找不回；把凭证放进电脑：`export AURI_DESTINATION_CREDENTIAL='<粘贴>'`。
   - **核验只认强生物识别（评审 B4）**：① 一部只设了锁屏 PIN、没登记指纹和人脸的手机：点“生成凭证”→ 不应弹出任何系统框（更不能输入 PIN 通过），页面说明没法核验、锁屏密码不能代替；`sqlite3 /tmp/auri-gateway-data/gateway.db 'select count(*) from destination_credentials'` 不变。② 登记一枚指纹后再点：系统框只有指纹/人脸与系统自带的“取消”按钮，**没有“使用 PIN”**；点“取消”→ 页面说已取消、没有生成凭证（这个按钮的回调不经 `onAuthenticationError`，已按“取消”交回，只能在真机上核对）；指纹错误 / 锁定 → 同样没有凭证。③ 通过后才生成，网关库里恰好多一行凭证。
   - 显示凭证时按 Home（或锁屏）再回来：面板必须是空的（退到后台就抹掉）。显示凭证时杀掉进程（`adb shell am force-stop app.auri.android`）再进来：同样是空的。
   - 生成凭证的过程中（评审 B6）：核验框弹着期间页面**不应**收到 `ON_STOP`（`adb logcat -d | grep -i 'ActivityTaskManager.*stop'` 里没有本应用的 stop 记录；若有，生成凭证会一直落到“已丢掉”）。核验通过后、网关回答之前立刻按 Home（`adb shell input keyevent KEYCODE_HOME`）再回来：页面是空的，随后出现“已丢掉，请轮换”，**不会**蹦出凭证；照提示轮换后网关里只认新的（`real_gateway_agent.py probe`：旧凭证 `GATEWAY_AUTH_REVOKED`）。
   - 剪贴板（评审 B5；**离开应用就清的部分已被 R6 取代，现在的步骤与期望见 14.4**）：点“复制并关闭”后马上退到另一个应用，在那里粘贴：应当粘得出凭证（复制那一下没有把它当场删掉）；**65 秒后**再粘贴：应当是空的，`adb logcat -d -s AuriSync:I | grep clipboard` 应有 `clipboard.copied` 与 `clipboard.cleared`。再来一次：复制后在别的应用里复制一句别的话，**回到本应用的前台**待到 60 秒——别的话应当还在，日志是 `clipboard.kept-other`。又一次：复制后 `adb shell am force-stop app.auri.android`，65 秒后重新打开应用：凭证应被清掉（回到前台补查）。**【这一条已被第三轮 S11 取代：归属改按内存里的内容摘要认，进程被杀后摘要没了、认不出归属就不清，这一次凭证应当仍在剪贴板里；现在的步骤与期望见 15.4】**
   - 凭证不在设备上：`adb shell run-as app.auri.android sh -c "grep -rl '<凭证前 12 个字符>' . ; echo done"` 只应打印 `done`；`adb logcat -d | grep -c '<凭证前 12 个字符>'` 应为 0（连同别的应用的日志一起查）。
   - 再点“轮换凭证”：先说清旧凭证立刻失效、要你确认，确认后**再次**核验；轮换后 `real_gateway_agent.py probe`：旧凭证 `{"status": 401, "code": "GATEWAY_AUTH_REVOKED"}`，新凭证 `{"status": 200, "code": null}`。
3. **自动发送与 Agent 领取（清单 3、7）**：先在“同步与配对”页打开“允许把文字发送到我配对的网关”（评审 B1：默认关，配对不等于同意；“严格仅本地”开着时什么也发不出去）。用耳机录一句话，等转录完成（Ticket 14/16/20 的流程）。期望：不用再点任何东西；电脑上
   ```bash
   .venv/bin/python execution/acceptance/android-destinations/real_gateway_agent.py claim
   ```
   输出的 `payload.deliveries` 里有这句话，`review_state` 是 `not_reviewed`，带 `delivery_id` 与 `lease_token`；手机详情页该条显示“Agent 已领取，还没回确认”。再
   ```bash
   .venv/bin/python execution/acceptance/android-destinations/real_gateway_agent.py ack <delivery_id> <lease_token>
   ```
   刷新后手机显示“Agent 已确认（网关只知道它回了确认）”；全程没有任何一处写“已处理”。
4. **预览确认（清单 4）**：用“预览确认”的 Destination 的凭证再录一句。期望：“待发送与标签”里这条标着“等你确认”；电脑上 `claim` 返回空的 `deliveries`；点“确认发送”（连点几下）后 `claim` 恰好领到一条。点“不发送”的那条永远领不到。同时还有一个“自动发送”的 Destination 时，它**马上**领得到同一句话，不用等你对预览的决定（评审 B2）；同步页多一行“有 N 条文字在等你确认……”（评审 M2）。
5. **标签（清单 5）**：在“待发送与标签”里把一条标成“请求执行”再放行：Agent 领到的 `intent` 是 `action_request`；它只是数据，什么也不会被执行。
6. **撤销**：对一个还有没被领取的文字的 Destination 点“撤销这个目的地”（并确认）：旧凭证的 `probe` 立刻 401（`GATEWAY_AUTH_REVOKED`），那条文字在手机上显示“已取消”。

设备上的自动化测试（要设备或模拟器；本机禁用，`NOT_RUN`，原因：为省内存不起模拟器）：

```bash
# 设备端 SQLite 版发送账合同、发布事务、进程被杀等；会取共享锁、建并删除一次性 AVD
AURI_ALSO_ASR_ACCEPTANCE=1 execution/acceptance/android-sync/run_emulator_acceptance.sh
# 只想跑本票那一个类（已连上设备）：
cd apps/android && ./gradlew :syncplatform:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.auri.syncplatform.SqliteDispatchStoreOnDeviceTest
```

## 5. 设计要点（iOS、鸿蒙照着做）

- **创建请求没有默认值**：类型层面就不许省略 `send_mode`；表单里 `sendMode` 一开始是空，空着“创建”不可点。意图默认只有备忘；“请求执行”要用户勾。
- **核验每次现场做**：生成 / 轮换都先核验，通过才联网；没通过（拒绝、取消、没有硬件、没登记、暂时不可用）**零请求**；不缓存上一次的通过；两次点击只弹一次。
- **凭证只存在一个地方**：网关响应 → 一个 `toString` 永远脱敏的包装类型 → 一个只在内存里的“正在显示”状态。复制、关闭、离开页面、退到后台都抹掉它；等待期间页面离开或退到后台了，之后才到的凭证不显示、不留（只记一条“已丢掉，请轮换”）。面板状态与“第几代”在同一个值里原子更新，不存在“比完代数、赋值之前页面离开了”的缝（评审 B6）。不进日志、不进保存的实例状态（界面里唯一保存的是“现在在哪一页”）、不进磁盘、不进备份；网关库里也只有哈希。
- **预览确认只拦“Agent 看得到”，不拦文字入库**（评审 B2 取代了原先“整条等到最后一个决定”的做法）：手机在发布 Revision 的**同一个事务**里按当时的目的地缓存定下目标快照，同步任务一定创建、不等任何决定；“自动发送”的目标随第一次 PUT 建 Delivery，“预览确认”的目标要等用户点“确认发送”才由引擎向网关**追加**（`POST /v1/records/{record_id}/revisions/{revision}/deliveries`，幂等，同一个不可变 Revision；缺口 G13 就此关闭）。每个目标一本账：`NOT_YET`（网关上还没有它的 Delivery）、`CREATED`、`REFUSED`；网关的答复里没有这个目标就记没送达，不假设成功；重启后一启动就把账上欠的追加做完。决定是终局，重复确认认出“已经决定过”。
- **`publication` 在发送那一刻现问发送账**（不是发布时）：被撤销或不接收这个意图的目的地不进去，否则网关会把整条 422 永久拒绝。
- **状态只说网关证明得了的**：已入库（`PENDING`）、已领取（`LEASED`）、已确认（`ACKNOWLEDGED`），外加已拒收、已取消；不认识的状态值、问不到网关、网关答非所问、缺记录各自明说“未知”，不当成功。
- **意图标签只是数据**（SEC-004）：`action_request` 不授予任何额外权限，网关交给 Agent 时带着 `content_trust` 标记；界面文案里写明。
- **日志只有事件名与异常类名**：没有凭证、令牌、正文、目的地名字与编号、地址。

## 6. 假设（做决定时我没有问人，下面每一条都可以推翻）

1. **预览确认放行的仍是未审核的初稿**：用户点“确认发送”不等于他审阅并改过正文，请求里 `review_state` 仍是 `not_reviewed`（合同也没有办法在“确认发送”时标成已审核，见 G14）。
2. **退到后台（`ON_STOP`）与离开页面一样“换一代”**（评审 B6）：正在显示的凭证抹掉，还在路上的请求作废——之后才到的凭证不显示、不留，只告诉用户“已丢掉，请轮换”（网关那边已经签发了，所以要轮换）。这推翻了原先“在途的不抹”的假设（它会让凭证在退到后台之后又蹦出来）。代价：如果某个机型在核验框弹出时会让页面收到 `ON_STOP`，用户每次生成凭证都会落到“已丢掉”——这只能在真机上核对（第 4 节）；框架自带的 `BiometricPrompt` 是系统窗口，按设计不会让宿主 Activity 停掉。
3. **复制出去的凭证 60 秒后从剪贴板清掉，只清自己放的那一份**（评审 B5，`SecretClipboardGuard`）。评审要求“面板关闭、退到后台、到时间”三个时刻都清；我只做了“到时间”，**没有**在后台那一刻清：凭证复制出去的唯一用处是粘贴到别处，几乎总要退出本应用（而且复制的那一下面板本来就关了），一退后台就清，等于把刚复制的东西当场删掉，用户只能再轮换一次。要不要在那两个时刻也清是产品决定（改法：在退后台的回调里调 `clear()`）。后台时 Android 不让读剪贴板，认不出归属就照清（宁可多清一次，也不让凭证留着）；读得到时按标记认，别的应用后来复制的东西不动。**【“认不出归属就照清”与“离开时不清”已被 R6 取代：现在认不出归属绝不清、离开本应用（读得到的最后一刻）清；见 14.1 R6 与 14.2 第 1 条】**【“读得到时按标记认”又被第三轮 S11 取代：归属改按内存里记的内容摘要认，描述里的标记整个删除；见 15.1】**
4. **界面上“未审核”是常量**：V1 里没有任何代码能产生 `review_state=reviewed` 的初稿（只有枚举值），所以发送账里的每一条都是未审核初稿，常量是如实的。将来有审阅功能时，必须改成从 Revision 的 `review_state` 派生。
5. **核验只认强生物识别**（`BIOMETRIC_STRONG`；评审 B4 去掉了原先的 `DEVICE_CREDENTIAL` 后备），用框架自带的 `android.hardware.biometrics.BiometricPrompt`，没有新增依赖（minSdk 够用）。没登记指纹或人脸 → 不弹提示、不生成凭证并明说，锁屏密码不能代替（知道 PIN 的人不一定是用户本人）。因为不再允许设备凭据，系统要求自己给“取消”按钮，已加上（它的回调不经 `onAuthenticationError`，已按“取消”交回；这一处只能在真机上核对）。
6. **只有 ASR 初稿（第 1 版）走自动发送 / 预览确认**：用户修订、重新转录出的新版本只入库（ADR-009 授权的是“转录出来的文字发给这个 Agent”；修订要不要自动重发是单独的决定）。
7. **意图标签发出去之后不能改**（网关上一个版本的意图不可更改）；发出去之前随时可改；“新录的文字默认标成什么”由用户设，只影响之后新录的。
8. **创建没有幂等键，所以绝不自动重试**：结果不确定（响应丢了、读不懂）时让用户先“刷新”看列表，不叫人直接重试（会多出同名目的地）。有效目的地的 50 个上限由网关裁决（评审 M4）：手机不再拿本机缓存数来拒绝，网关回 `409 REQUEST_INVALID` 才说“到上限了”。
9. **本机缓存可能比网关旧**（别的设备撤销了某个目的地）：见第 7 节 G16 与第 10 节。
10. **TDD 的接缝没能先问人**：被派发的 worker 没有人可问，接缝定在“`DestinationController` / `DispatchStore` / `LocalAuthenticator` 接口、三个视图模型的 `state`、网关请求 / 响应”，测试都写在这些接口上。
11. **独立评审已经做过一轮**（B1–M4，第 13 节），修复都在分支上；修复同样由单个 worker 完成，没有再做第二轮评审，也没有 Codex 评审（单一 agent、禁止子 agent）。合入前仍应有人看一遍，尤其是第 5 节的发送账事务、B2 的网关追加端点与合同规则。
12. **前四个提交不是这一轮写的**：`6971bee`、`34a3a9b`、`6fc8331` 与不能编译的在制品 `62c0f8c` 是被 Orca 重启打断的前几轮留下的。我接手后让它们编译、补齐测试，没法证明那几处当初是先红后绿写成的，所以用第 8 节的 30 条变异（评审修复轮之后整张表共 132 条，见 13.4）证明这些测试在规则被改坏时真的会变红；这一轮新写的部分按检查项先写测试再写实现。

## 7. 合同与网关的缺口及建议（原始实现只记录；评审修复轮按 `contracts/README.md` 的“修改规则”改了 B2、B3、M4 三处，见 13.6）

编号接着 Ticket 21 的 G7–G12。

| # | 缺口 | 位置 | 建议 |
|---|---|---|---|
| G13（**已关闭**，评审 B2） | 网关只在一个 (record, revision) **第一次入库**时按 `publication.destination_ids` 创建 Delivery；同一版再 PUT 只回 `duplicate: true` 与**已有**的 `deliveries`，补不上新目标（`services/gateway/auri_gateway/store.py` 的 `existing` 分支）。后果：预览确认不能“先发给自动的、确认后补预览的”，只能整条等到最后一个决定，一次请求带齐；也没办法把已入库的文字补发给之后才创建的 Destination | `revision.schema.json#/$defs/submission/properties/publication`；网关 `PUT /v1/records/{id}/revisions/{n}` | 增加“给已有版本补投递”的操作（例如 `POST /v1/records/{id}/revisions/{n}/deliveries`，只增不减、幂等），或让重复 PUT 带来的 publication 作为增量处理。**已关闭**：评审 B2 照这条建议在网关加了该端点（合同当时仍是 1.1，R7 之后整个合同升到 1.2），手机端不再“整条等”。 |
| G14 | 合同里没有任何字段能表达“这段文字发出之前有人看过 / 用户点过确认”。预览确认放行的文字，Agent 看到的 `review_state` 仍是 `not_reviewed`，和自动发送的没有区别 | `revision.schema.json` 的 `review_state` 与 `publication` | 若产品希望 Agent 区分“用户预览确认过”与“自动发送”，需要新字段（例如 `publication.confirmed_by_user`）或把确认当作一次 `reviewed` 的新 Revision；否则这就是 V1 的约定 |
| G15 | 创建 Destination 没有客户端生成的稳定操作编号（`POST /v1/destinations`）。响应丢了之后重发会多出同名 Destination | `destination.schema.json#/$defs/create_request` | 合同 1.2 可选加 `client_request_id`，网关在窗口内幂等。现在手机端靠“不自动重试 + 让用户先刷新”绕开 |
| G16 | 提交里有一个已被撤销（或已不存在、或不接收该意图）的目标，网关把**整条**以 `422 REQUEST_INVALID` 拒收，错误消息不指明是哪一个。手机缓存可能比网关旧（别的设备刚撤销了某个 Destination），整条会停在“需要用户处理” | `store.py` 的 `_validate_publication_destinations` | 在错误体的 `details` 里带上有问题的 `destination_ids`，手机据此刷新缓存后重新规划再发；或网关对已撤销的目标跳过并在响应里说明 |
| G17 | 状态查询只给每个 Delivery 的 `state`：没有“哪个 Agent 领的、租约何时到期、确认备注”，详情页只能说“Agent 已领取，还没回确认”，没法说“租约还剩多久 / 过期会重新投递” | `GET /v1/records/{id}/status` 的 `destinations[]` | 可选：补 `lease_expires_at`（不含 token）。不是缺陷 |

另：没有发现合同与网关行为不一致的地方——真网关测试里创建（两种模式）、凭证（签发 / 轮换，旧凭证 `GATEWAY_AUTH_REVOKED`）、入库（含 `publication`）、MCP 领取与确认、状态查询、撤销（待领取的投递变 `CANCELLED`）都按合同工作。

## 8. 变异检查：故意写错，确认测试会红

只写“通过的测试”不能证明测试拦得住错误。`destination_mutation_check.py` 把“我破坏了什么、哪些测试必须因此失败”写成一张表，机器与 Ticket 21 的 `android-sync/mutation_check.py` 是同一套：**只在仓库外的快照里改源码，真实工作树一个字节都不动**（`/tmp/auri-ticket22-mutation-snapshot`，跑完删），应用 → 跑 Gradle → 还原；每条改动的原文必须恰好出现一次，找不到就是表无效（退出码 2），预期会红的那些测试名**每一个**都必须出现在失败的用例里（否则判“没被拦住”，退出码 1）。表自己的离线检查（`test_destination_mutation_table.py`，不跑 Gradle）确认每条原文真实存在、每个预期的测试名真实存在，并把清单 1–6 项逐项映射到至少一条变异。

原始实现的 30 条变异，**全部被预期的测试拦住**（2026-11-20 在本机逐条真跑；评审修复轮新增的条目与整张表 132 条的重跑结果见 13.4；“变红数”是该次运行里红的测试数 / 该次运行选中的测试数；名称片段对应的测试名见第 2 节与源码）：

| 编号 | 守住的规则 | 故意改坏的文件 | 必须变红的测试（名称片段） | 结果（变红数/本次运行的测试数） |
|---|---|---|---|---|
| app-send-mode-defaults-to-auto | 创建表单一开始必须没有发送方式（没有默认值）：默认成自动发送，就等于替用户授权了自动发送（ADR-009） | DestinationsViewModel.kt | 一开始没有选发送方式 | CAUGHT 2/9 |
| sync-send-mode-wire-renamed | 创建请求里的 send_mode 必须逐字是合同的取值（auto_send / preview_confirm） | DestinationModels.kt | 创建自动发送的 Destination；发送方式的合同取值与本机一致 | CAUGHT 5/23 |
| real-gw-send-mode-wire-renamed | 真网关：send_mode 写错了取值，网关不认，创建必须失败（而不是悄悄变成别的模式） | DestinationModels.kt | 创建：自动发送与预览确认各建一个 | CAUGHT 7/21 |
| service-skips-authentication | 生成/轮换凭证前必须现场核验：跳过核验就会在没人确认是机主的情况下拿到凭证 | DestinationService.kt | 核验没通过 | CAUGHT 1/26 |
| service-cancel-and-unavailable-pass | 取消、没有硬件、没登记生物识别都不能当作通过（只有明确被拒才拦住就是放行了没法核验的设备） | DestinationService.kt | 核验没通过 | CAUGHT 1/26 |
| real-gw-service-skips-authentication | 真网关：核验没通过时手机一个凭证请求也不能发，网关上不能出现凭证 | DestinationService.kt | 核验没通过 | CAUGHT 1/21 |
| biometric-unknown-error-is-authenticated | 系统生物识别给了没见过的错误码，必须按没通过处理（任何码都不可能得到“已通过”） | BiometricResults.kt | 锁定、超时、无法处理与没见过的码都是没通过；任何错误码都不可能得到 | CAUGHT 2/5 |
| vm-copy-does-not-wipe | 复制之后凭证必须立刻抹掉（只显示一次） | DestinationDetailViewModel.kt | 凭证只显示一次 | CAUGHT 4/21 |
| vm-leave-does-not-wipe | 离开页面必须抹掉凭证 | DestinationDetailViewModel.kt | 凭证只显示一次；等核验或网关的时候离开了页面 | CAUGHT 2/21 |
| vm-late-credential-after-leave-shown | 等核验或网关时页面离开了：之后才到的凭证不能再显示出来（没人看着，也不能留着） | DestinationDetailViewModel.kt | 等核验或网关的时候离开了页面 | CAUGHT 1/21 |
| vm-backgrounded-keeps-credential | 应用退到后台时正在显示的凭证必须抹掉（回来时看到的和进程被杀后一样是空的） | DestinationDetailViewModel.kt | 应用退到后台：正在显示的凭证抹掉 | CAUGHT 1/21 |
| credential-tostring-leaks | 凭证对象的字符串形式不能含凭证（日志、异常消息、调试输出里不小心带上它也不会泄露） | DestinationModels.kt | 凭证对象的 toString 不含凭证；凭证不会出现在日志里 | CAUGHT 2/43 |
| service-logs-credential | 签发凭证的日志里不能出现凭证 | DestinationService.kt | 凭证不会出现在日志里 | CAUGHT 1/26 |
| guard-sees-credential-in-log | 源码守卫：日志里插值凭证、在登记之外读出凭证明文，守卫必须变红 | DestinationService.kt | 日志只写事件名与异常类名；读出凭证明文的地方只有两处 | CAUGHT 2/6 |
| guard-sees-saved-state-on-the-page | 源码守卫：详情页多存一个保存状态（进程被杀后还在），守卫必须变红 | DestinationsPage.kt | 没有任何会带走凭证的 API；界面里唯一保存的状态 | CAUGHT 2/6 |
| dispatch-preview-not-held | 预览确认的 Destination 在用户确认之前必须拦住这条文字（否则网关马上就为它建 Delivery）：等用户决定的目标必须被认出来 | DispatchStore.kt | a_preview_confirm_destination_holds_the_item；a_held_item_stays_held_after_a_restart | CAUGHT 16/44 |
| dispatch-sql-preview-not-held | Android 的 SQLite 发送账：预览确认的目标必须记成“等用户”，不能记成自动 | DispatchSql.kt | a_preview_confirm_destination_holds_the_item | CAUGHT 18/44 |
| dispatch-sql-decide-not-idempotent | 重复确认必须被认出来（已经决定过）：不能当成新的决定再处理一遍 | DispatchSql.kt | confirming_releases_the_job_exactly_once_and_repeated_confirms_change_nothing | CAUGHT 4/33 |
| dispatch-ignores-eligibility | 不接收这种意图标签（或已撤销）的 Destination 不能出现在 publication 里（否则网关把整条 422 永久拒收） | DispatchStore.kt | a_destination_revoked_after_the_release_but_before_the_send_is_not_in_the_publication；an_auto_send_destination_that_does_not_accept_the_intent_is_not_in_the_publication | CAUGHT 3/45 |
| default-intent-is-action-request | 没有任何设置时标签默认必须是备忘，绝不是请求执行 | DispatchSql.kt | the_default_intent_is_context_and_what_the_user_sets_survives_a_restart | CAUGHT 23/33 |
| sync-source-ignores-publication | 每次发送都要现问发送账要 publication：不问就只会入库，Destination 永远收不到 | RecordLibrarySubmissionSource.kt | 发布时的 publication 每次发送都现问发送账；自动发送：转录完成后一轮同步就送到网关 | CAUGHT 8/32 |
| real-gw-source-ignores-publication | 真网关：自动发送的 Destination 必须在同一次同步里收到待领取的投递 | RecordLibrarySubmissionSource.kt | 自动发送：转录完成后一轮同步 | CAUGHT 5/21 |
| sync-submission-marks-reviewed | 自动发送的初稿在请求里必须是 not_reviewed | SubmissionBuilder.kt | 自动发送：转录完成后一轮同步就送到网关 | CAUGHT 7/32 |
| real-gw-submission-marks-reviewed | 真网关：Agent 领到的自动发送文字必须标着未审核 | SubmissionBuilder.kt | 自动发送：转录完成后一轮同步 | CAUGHT 2/21 |
| ui-unreviewed-label-blank | 界面必须在自动发送的每一条上写明“未审核” | DestinationDescriptions.kt | 两种发送方式的话不同；自动发送：转录一完成就发出去 | CAUGHT 2/25 |
| status-unknown-state-counts-as-acknowledged | 网关给了没见过的投递状态，必须显示未知，不能悄悄算成已确认 | DeliveryView.kt | 不认识的状态值不是任何一档；网关给的状态这个版本不认识 | CAUGHT 3/32 |
| status-leased-reads-as-acknowledged | 已领取（LEASED）不能说成已确认：Agent 领走了不等于它回了确认 | DeliveryView.kt | 已入库、已领取、已确认：三个阶段各对应网关的一个状态 | CAUGHT 3/32 |
| real-gw-leased-reads-as-acknowledged | 真网关：Agent 领取之后手机上必须显示已领取，不是已确认 | DeliveryView.kt | 自动发送：转录完成后一轮同步 | CAUGHT 1/21 |
| status-unreachable-shown-as-stored | 已入库但这会儿问不到网关（或网关答非所问）：必须是未知，不能显示成已入库 | DestinationService.kt | 已入库但这会儿问不到网关；网关答了错误 | CAUGHT 2/26 |
| status-wording-says-processed | 任何文案都不能说“已处理”：网关只知道 Agent 回了确认，不知道它有没有读懂或执行 | DestinationDescriptions.kt | 没有任何一句话说“已处理”；网关能证明的三步 | CAUGHT 2/12 |

怎么重跑：`destination_mutation_check.py --list` 列出全部；`--only <编号…>` 或 `--match '<正则>'` 选条目；Gradle 加 `AURI_GRADLE_ARGS="--no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2"`（整表真跑约 1 小时；先取共享 Gradle 锁）。退出码 0 = 每条都被预期的测试拦住，1 = 有没被拦住的，2 = 变异本身无效。

**第一次跑出来的问题和处理**（都是改变异表或把守卫改严，没有放宽任何测试）：

1. `guard-sees-credential-in-log` 没被完全拦住：日志里插值凭证时，“日志插值”那条守卫红了，但“读出凭证明文的只有两处”那条没红。原因是它复用的 `SourceText.withoutCommentsAndStrings` 会把整个字符串连同里面的 `${...}` 模板表达式一起抹掉，字符串模板里的 `reveal()`（日志、异常消息都能这样泄露）它看不见。已把这条守卫改严：字符串模板里的 `reveal()` 也算读出明文，并给守卫自己加了会抓到坏例子的断言；重跑后两条都红。
2. `dispatch-preview-not-held`、`dispatch-ignores-eligibility` 一开始改在 `:sync` 的单测上，要么全绿、要么没拦住预期的测试：真正拦住文字、决定谁进 `publication` 的生产代码是 SQLite 发送账（`DispatchSql.releaseIfReady` / `publicationFor`），`:sync` 的单测与真网关测试用的是内存实现（价值在“网关一侧看到什么”）。已改成跑 `:syncplatform` 里的 SQLite 合同测试（JVM 里跑真 SQLite 引擎）。改完之后 `dispatch-ignores-eligibility` 仍有一个预期的测试没红，查出来是**测试缺口**：合同里没有“授权了自动发送、却不接收这个标签的目的地不能进 `publication`”这一条（原来的“不接收标签”用例用的是预览确认的目标，它本来就收不到，变异碰不到它）。放进去网关会把整条 422 永久拒收，文字就丢在手机上了，所以补了合同用例 `an_auto_send_destination_that_does_not_accept_the_intent_is_not_in_the_publication_and_does_not_block_the_others`（内存版、SQLite 版、设备端版共用同一份），内存版与 SQLite 版都绿，重跑变异后它红了。原来的两条“真网关版”（`real-gw-preview-not-held`、`real-gw-publication-ignores-eligibility`）因此**删掉**：那一层根本没有能被变异的生产拦截逻辑，留着只会是一条永远活着的假变异。真网关层对预览确认与标签的保障是：确认之后恰好一份（评审 B2 之后：确认之前文字已入库、但网关上没有这个目的地的 Delivery，见第 2 节），再加 `real-gw-source-ignores-publication`（这一条随 B2 删除，见 13.4）。
3. `status-unreachable-shown-as-stored` 一开始改的是 `remote == null` 那条防御分支，它在正常流程里到不了（等价变异）。已改到真正会走的 `Remote.Unusable` 分支。

## 9. 合同输出校验的场景

`:sync` 的 `DestinationContractOutputTest` 用真实的视图模型、服务与同步引擎，把手机实际发给网关的请求按场景写进目录（Gradle 属性 `-PauriContractOut=<目录>`，默认在 `sync/build/contract-out/jvm`），`destination_contract_check.py --preset jvm` 严格检查：这六个场景一个不能少、不能多。

| 场景 | 写出的东西 | 检查 |
|---|---|---|
| `create-auto-send` / `create-preview-confirm` | `create-request.json` | 按 `destination.schema.json#/$defs/create_request` 校验；`send_mode` 恰好是场景名对应的取值 |
| `auto-send-unreviewed` | `submission.json` | 按 `revision.schema.json#/$defs/submission` 校验；`content.review_state=not_reviewed`；`publication.destination_ids` 就是那个目的地；SR-01/05/06 |
| `preview-held-before-confirm` | `submission.json`、`scenario.json` | 文字**已经入库**（恰好 1 次 PUT，未审核初稿），但入库请求**没有点名任何目的地**；网关侧观察到 0 次追加、0 个 Delivery（点名就会在确认之前建出 Delivery，评审 B2） |
| `preview-after-confirm` | `submission.json`、`additions-request.json`、`scenario.json` | 入库请求仍没有点名这个目的地、网关仍只收到 1 次 PUT；用户确认（含重复确认）之后恰好 1 次追加请求，按 `revision.schema.json#/$defs/delivery_additions` 校验，追加的目的地就是用户确认的那个；网关上恰好 1 个 Delivery |
| `action-request-tag` | `submission.json` | `publication.intent=action_request`（只是数据上的标签） |

`test_destination_contract_check.py` 证明校验器自己拦得住坏样本（缺 `send_mode`、取值不在合同里、该有 `publication` 的没有、`held` 场景里入库请求点名了预览目的地或网关上已有 Delivery 或有追加请求、`addition` 场景缺追加请求或追加的目的地对不上或 Revision 被重发、追加请求不符合 `delivery_additions`、报错不回显输出文件里的值……）；它的 46 个测试对着评审修复之前的校验器跑有 22 个失败（先红），对着现在的全过。

## 10. 已知限制（没做、做不到或需要产品决定的）

- **真机行为全是 `NOT_RUN`**：真实 `BiometricPrompt` 的弹出与各种结果、`FLAG_SECURE` 对截图与最近任务的效果、剪贴板敏感标记、`ON_STOP` 在真实 Activity 生命周期里的触发、Android 自带 SQLite 上的发送账合同（`SqliteDispatchStoreOnDeviceTest`）、进程被系统杀掉后的发送接力、整条链在局域网真机上的样子。JVM 测试证明了逻辑，不证明这些平台行为。
- **剪贴板里的凭证只有 60 秒，且有三处认不全**（评审 B5）：① 进程在 60 秒内被系统杀掉，定时器随之没了，下次窗口拿到焦点时才补查（标记里带着放进去的时刻；**第三轮 S11 取代：标记整个删除，进程被杀后内存里的摘要没了，不再补查，见 15.3**），在那之前凭证还留在剪贴板里（Android 13 起系统自己约一小时后清，更早的版本只等被覆盖）；② 后台到点时 Android 不让读，认不出归属就照清，所以用户在那 60 秒里于别处复制的东西可能被一并清掉；③ 敏感标记（预览里不显示）是 API 33 才有的常量，旧系统忽略它。`clearPrimaryClip` 是 API 28 才有的，应用 minSdk 31，没有缺它的系统（`ClipboardWiringTest` 钉着 minSdk ≥ 28，谁调低它这条先红）。后台能否写与清剪贴板、描述里的标记能否读回，都没在真机上看过，`NOT_RUN`（第 4 节）。
- **“核验”不是“机主确认”的全部**：通过的是系统的强生物识别（指纹 / 人脸）；手机若被人解锁着递给别人，核验拦不住。这是系统核验的边界，不是本票能解决的。
- **本机缓存可能比网关旧**（缺口 G16）：别的设备撤销了某个 Destination 后，本机在下次“刷新”或同步前不知道；手机在发送那一刻只排除**本机已知**已撤销的。网关会把含已撤销目标的整条 422 拒收，那条文字会停在“需要用户处理”，刷新缓存后重新规划才能发。
- **文字在用户确认之前就已经在用户自己的网关里**（评审 B2 的取舍）：预览确认只保证“确认之前没有任何 Agent 读得到”（没有 Delivery，Agent 领不到），不再保证“确认之前网关上什么也没有”。网关是单用户自托管；要严格做到“确认之前不出手机”，用“严格仅本地”（评审 B1）。手机不会把已入库的文字事后补发给**之后才创建**的 Destination（目标在发布时按当时的目的地定下，评审 B2/H2）。
- **创建 Destination 没有幂等键**（缺口 G15）：结果不确定时只能让用户先刷新再决定，不自动重试。
- **详情页只列最近 20 条**，每条已入库的文字向网关问一次状态（一次刷新最多 20 个请求，刷新有最小间隔）；更早的条目不显示状态。
- **撤销 Destination 不可逆**：撤销后凭证立刻失效；按合同状态机，还没确认的投递（待领取的、已领取的）都变成已取消，已确认的不变。本票的真网关测试只覆盖“待领取的被取消”这一种；“已领取后被撤销”靠合同状态机的转移表，没有单独的端到端用例。
- **V1 没有“用户审阅并修改正文”的功能**，所以没有 `reviewed` 的初稿，也就没有“审阅后再发”的路径；见假设 4。
- **没有通知**：Agent 领走了或确认了，手机不会提醒，要打开详情页（或下拉刷新）才看到。

## 11. 依赖与权限

- **没有新增任何第三方依赖**：`gradle/libs.versions.toml` 没改；`:sync` 的测试固件被 `:app` 的单测引用（`testImplementation(testFixtures(project(":sync")))`）、`:sync` 的 `build.gradle.kts` 多了一个把 Gradle 属性 `auriContractOut` 转给测试进程（系统属性 `auri.contract.out`，与别的合同输出测试同名）的设置。生物识别用的是系统框架类 `android.hardware.biometrics.BiometricPrompt`（minSdk 31），没有引 `androidx.biometric`。
- **没有新增网络类、没有新增 `INTERNET` 声明**：所有联网都走 Ticket 21 的 `PinnedHttpsClient`（`NoNetworkCodeGuardTest` 的白名单没改，照样绿）。
- **新增一个权限 `USE_BIOMETRIC`**（`:syncplatform` 清单；普通权限，安装时自动授予，不读取任何生物特征数据）。

## 12. 评审修复之前的运行结果（历史记录；评审修复之后的最新结果见 13.5）

合并 `origin/integration/auri-v2`（Ticket 19，`10be78c`；与本票只有 `AuriRuntime.kt` 一个文件两边都改过，git 自动合并没有冲突，合并后全部重新编译、重新跑）之后的最终验证。

### 12.1 环境与方法

- 本机 macOS，交换区常年接近 4 GB，与 Ticket 19 的 worker 共用，所以：每个 Gradle / pytest 都单独取共享锁 `~/.cache/auri/gradle.lock`、一次只跑一个、跑完立刻放锁；Gradle 参数 `--no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2`，每个测试任务加 `--rerun` 保证是新鲜结果（表里的数字是该次运行写出的 JUnit XML 的合计，不是缓存）；没有执行过 `./gradlew --stop`。
- 每次运行前后各读一次 `memory_pressure` 的空闲百分比与 `sysctl vm.swapusage`（表里“空闲 前 → 后；交换区已用”）；空闲低于 25% 或 60 秒内交换区涨超 500 MB 就等（本次没有一次因此等待：最低的读数是 `asr-android` 结束时的 34%，仍高于 25%）。
- pytest 一律带 `NO_PROXY=localhost,127.0.0.1,::1 no_proxy=localhost,127.0.0.1,::1`，前后各查 `pgrep -fl auri_gateway`：每一次都是 0 个残留。
- **没有**启动模拟器、AVD、Android Studio、Xcode、DevEco 或任何仿真器，没有运行任何仪器测试（`connectedAndroidTest`）；仪器测试只编译打包（`androidtest-apks`）。
- **技能与代码索引的核对（如实）**：`tdd`、`implement-spec` 技能在 `~/.claude/plugins/cache/claude-plugins-official/mattpocock-skills/1.3.1/skills/engineering/` 与 `~/.agents/skills/`；我前面的搜索漏了 `implement-spec`，收尾时才找到并读了它：它写的是协调者的流程，对 implementer 的三条要求（工作树基于集成分支、用 `tdd` 做票、报告前合入集成分支最新提交）都满足（合并提交 `e19d2f6`）；`tdd` 里“测试前先与用户确认接缝”无人可问，见第 6 节第 10 条；它的 `code-review` 步骤属于协调者，我没做，也没有 Codex 评审（单一 agent、禁止子 agent）。
- **GitNexus**：查过主检出 `/Users/ethan/Desktop/CODE/auri` 的索引（`Auri`，`main` @ `eedf123`，171 个文件），对 `PinnedHttpsClient`、`GatewayClient`、`AuriRuntime`、`DestinationService` 都没有 Kotlin 命中，帮不上本票的调用者与影响分析；没有给本工作树建索引（占内存，派发说明要求用主检出的索引），所以没跑 `impact` / `detect_changes`。替代证据：`rg` 与源码、`git diff --stat` 对集成分支（`contracts/`、`tests/`、`services/`、`.github/`、`apps/android/gradle/`（含 `libs.versions.toml`）、`settings.gradle.kts` 与根 `build.gradle.kts` 都没有改动；也没有删除任何文件，没有新增被跳过 / 禁用的测试）、合并后全部套件重跑、第 8 节的变异检查。**AOCI**：本分支、`origin/main`、`origin/integration/auri-v2` 都没有 `aoci.txt`（只有主检出的本地 `main` 有），没读也没维护，也没自动启用。

### 12.2 JVM 单测、真网关与构建（`apps/android`，合并 `integration/auri-v2` 之后，每个套件单独取锁、`--no-daemon … --rerun`）

| 套件 | 命令 | 结果 | 起止（本机时间） | 内存空闲（前 → 后）与交换区已用 |
|---|---|---|---|---|
| `assemble-debug` | `./gradlew :app:assembleDebug` | exit 0：构建成功 | 23:53:00–23:53:53 | 48% → 47%；交换区 3922 → 4019 MB |
| `capture` | `./gradlew :capture:test --rerun` | exit 0：369 个，失败 0、出错 0、跳过 0 | 23:53:53–23:54:16 | 47% → 56%；交换区 4019 → 4019 MB |
| `modelmgmt` | `./gradlew :modelmgmt:testDebugUnitTest --rerun` | exit 0：84 个，失败 0、出错 0、跳过 0 | 23:54:16–23:55:00 | 56% → 50%；交换区 4019 → 3995 MB |
| `platform` | `./gradlew :platform:testDebugUnitTest --rerun` | exit 0：257 个，失败 0、出错 0、跳过 0 | 23:55:00–23:55:23 | 50% → 53%；交换区 3995 → 3987 MB |
| `app` | `./gradlew :app:testDebugUnitTest --rerun` | exit 0：249 个，失败 0、出错 0、跳过 0 | 23:55:23–23:56:06 | 53% → 49%；交换区 3987 → 3987 MB |
| `asr` | `./gradlew :asr:testDebugUnitTest --rerun` | exit 0：112 个，失败 0、出错 0、跳过 0 | 23:56:06–23:56:16 | 49% → 55%；交换区 3987 → 3979 MB |
| `sync` | `./gradlew :sync:test --rerun` | exit 0：373 个，失败 0、出错 0、跳过 0 | 23:56:16–23:56:38 | 55% → 50%；交换区 3979 → 3979 MB |
| `syncplatform` | `./gradlew :syncplatform:testDebugUnitTest --rerun` | exit 0：69 个，失败 0、出错 0、跳过 0 | 23:56:38–23:56:49 | 50% → 56%；交换区 3979 → 3979 MB |
| `realgateway` | `./gradlew :sync:realGatewayTest --rerun` | exit 0：21 个，失败 0、出错 0、跳过 0 | 23:56:49–23:57:25 | 56% → 57%；交换区 3979 → 3971 MB |
| `androidtest-apks` | `./gradlew :app:assembleDebugAndroidTest :syncplatform:assembleDebugAndroidTest :platform:assembleDebugAndroidTest` | exit 0：构建成功 | 23:57:25–23:57:50 | 57% → 46%；交换区 3971 → 3971 MB |

### 12.3 Python 验收与仓库套件（每个都是单独一次 `NO_PROXY=… pytest`，前后查网关进程）

| 套件 | 命令 | 结果 | 起止 | 内存空闲（前 → 后）与交换区已用 |
|---|---|---|---|---|
| `android-capture` | `pytest execution/acceptance/android-capture` | exit 0：59 passed | 23:58:41–23:59:40 | 57% → 59%；交换区 3955 → 3955 MB |
| `android-control` | `pytest execution/acceptance/android-control` | exit 0：106 passed | 23:59:40–00:00:40 | 59% → 54%；交换区 3955 → 3939 MB |
| `android-silence` | `pytest execution/acceptance/android-silence` | exit 0：3 passed | 00:00:40–00:00:40 | 54% → 53%；交换区 3939 → 3939 MB |
| `android-models` | `pytest execution/acceptance/android-models` | exit 0：2 passed | 00:00:40–00:00:40 | 53% → 53%；交换区 3939 → 3939 MB |
| `android-sync` | `pytest execution/acceptance/android-sync` | exit 0：814 passed | 00:00:40–00:03:40 | 53% → 53%；交换区 3939 → 3923 MB |
| `android-destinations` | `pytest execution/acceptance/android-destinations` | **第一次 exit 1：2 failed, 125 passed**（见下）；修复后重跑 exit 0：127 passed | 00:03:40–00:03:52；重跑 00:09:03–00:09:42 | 54% → 51%；交换区 3923 → 3923 MB；重跑 41% → 51%；交换区 4025 → 4001 MB |
| `android-interruptions` | `pytest execution/acceptance/android-interruptions` | exit 5：没有收集到测试（该目录只有 README，没有 Python 测试；Ticket 19 的区域，没动） | 00:03:52–00:03:53 | 51% → 51%；交换区 3923 → 3923 MB |
| `asr-android` | `pytest execution/acceptance/asr-android` | exit 0：131 passed | 00:03:53–00:04:53 | 51% → 34%；交换区 3923 → 3915 MB |
| `repo` | `pytest tests/contract tests/integration tests/security` | exit 0：496 passed | 00:04:53–00:08:48 | 34% → 52%；交换区 3915 → 4025 MB |

说明：

- `android-destinations` 第一次跑有 2 个失败，都是同一个原因：我自己的校验器在“预览确认没确认之前网关上有东西”时的报错文字（“必须都是 0”）与它自己的测试要找的文字（“都必须是 0”）对不上。**测试没动**，校验器的报错文字改成与测试一致，重跑 127 个全过。
- `android-silence`（3 个）、`android-models`（2 个）是纯静态检查，不调用 Gradle，所以只要零点几秒。
- 仓库套件 `tests/contract tests/integration tests/security` 共 496 个全过（约 3 分 54 秒，租约到期测试必须真实等待）。

### 12.4 没有运行的（`NOT_RUN`，原因：没有设备；本机为省内存禁用模拟器与仪器测试）

| 项 | 要做什么 | 命令 / 步骤 |
|---|---|---|
| 设备端 SQLite 发送账合同 `SqliteDispatchStoreOnDeviceTest`（与 Ticket 21 已有的设备端用例） | 在 Android 自带的 SQLite 上跑同一套发送账合同（含预览确认前不放行、重启后仍拦着） | `AURI_ALSO_ASR_ACCEPTANCE=1 execution/acceptance/android-sync/run_emulator_acceptance.sh`（一次性无头模拟器，先取共享锁），或已连设备时 `cd apps/android && ./gradlew :syncplatform:connectedDebugAndroidTest` |
| 清单第 7 项：真机上创建 Destination、Agent 领到自动发送的文字 | 第 4 节整套步骤 | 第 4 节 |
| 真实 `BiometricPrompt`、`FLAG_SECURE`、剪贴板敏感标记、`ON_STOP` 抹掉凭证的真实生命周期 | 第 4 节第 2 步的几个小项 | 第 4 节 |
| 局域网真机（mDNS 发现、自签证书、手机与电脑真实网络） | 同第 4 节 | 第 4 节 |
| 独立评审 | 合入前由人或评审工具看一遍（尤其发送账事务与凭证只显示一次） | — |

### 12.5 共享文件与既有文件的改动（原始实现：都是追加式的；不碰 `contracts/`、`tests/contract/`、`services/gateway/`、`.github/`。评审修复轮改了 `contracts/`、`services/gateway/` 并新增网关测试，见 13.6）

| 文件 | 改动（增 / 删行数） | 为什么 |
|---|---|---|
| `apps/android/app/.../AuriRuntime.kt` | +28 / −0 | 装配本票的服务、发送账与三个视图模型的工厂（与 Ticket 19 的启动恢复、录音目录迁移并存，自动合并） |
| `apps/android/app/.../MainActivity.kt` | +13 / −1 | 三个视图模型的接线，第四页“发送” |
| `apps/android/app/.../ui/AppRoot.kt` | +3 / −1 | `AppRoot` 多一个可选参数 `destinations` 与“发送”标签 |
| `apps/android/app/build.gradle.kts` | +1 / −0 | `:app` 的单测引用 `:sync` 的测试固件（假网关、内存发送账） |
| `apps/android/sync/build.gradle.kts` | +7 / −0 | 把 Gradle 属性 `auriContractOut` 转给测试进程（合同输出） |
| `apps/android/sync/.../engine/RecordLibrarySubmissionSource.kt` | +24 / −3 | 每次发送现问发送账要 `publication` |
| `apps/android/sync/.../gateway/GatewayClient.kt`、`GatewayModels.kt`、`SubmissionBuilder.kt`、`Wire.kt` | +94/−1、+11/−2、+6/−4、+13/−0 | 目的地的创建/列表/撤销/凭证/状态请求，入库请求带 `publication` 与 `review_state`；没有新增网络栈，走同一个固定公钥客户端 |
| `apps/android/sync/src/test/.../SubmissionBuilderTest.kt`、`testFixtures/.../FakeGateway.kt` | +85/−0、+171/−7 | 对应的测试与假网关的目的地行为 |
| `apps/android/syncplatform/.../SyncRuntime.kt`、`jobs/SqliteSyncJobStore.kt` | +26/−9、+45/−35 | 装配发送账与核验；同步任务可在同一个事务里登记（`enqueueIn`） |
| `apps/android/syncplatform/src/main/AndroidManifest.xml` | +2 / −0 | `USE_BIOMETRIC` 权限 |
| `execution/acceptance/android-sync/check_sync_instrumentation.py`、`test_check_sync_instrumentation.py` | +13/−10、+26/−0 | 设备端检查器认得“继承发送账合同”的设备端测试类（Ticket 21 的工具，14 个自测全过） |
| `CLAUDE.md` | 只在“命令”一节追加一段 | 本票的命令 |


## 13. 独立评审修复（B1–B6、H1–H4、M1–M4）

评审对象是 `origin/integration/auri-v2...59bf9e6`。顺序 B1…B6、H1…H4、M1…M4，**每条一个提交、红测试与修复同一个提交、提交完立刻推送**（`git push origin ticket/22-destinations-and-sending`）；提交信息以 `Ticket 22 review <编号>:` 开头。M3 与 M4 的手机一半并进了 B2（同一处代码），M4 的网关一半是它自己的提交。证据等级：JVM 单测、真实 Python 网关进程的测试（`:sync:realGatewayTest`）、网关 pytest、Python 校验工具都是 E2；没有设备或模拟器（为省内存禁用），凡是只有设备才能证明的一律 `NOT_RUN`，步骤在 13.3。

### 13.1 发现与修复

| 编号 | 提交 | 做了什么 | 先红后绿的测试（主要的） | 变异 | 证据 |
|---|---|---|---|---|---|
| B1 阻断：配对就上传 | `890d8a9` | 新增持久化的“文字可以进网关”授权（默认关；读不出来、读到不认识的值都当没授权）与“严格仅本地”（压过授权；读不出来当开；不改走任何别的通道）。引擎在每个会联系网关的点（一轮同步、直接发送、重试、探测、取到内容之后真正发出之前）先过门禁，ResumePlanner、SyncStatus、SyncRuntime 一并接上；同步页两个开关，待发送页在没放行时说“确认只是记下决定，现在不会发出”。命名沿用 PRD SEC-002 的“授权分离”“严格仅本地”，只在手机端落地，没有新增合同字段 | `DataFlowGateTest`（13 个；红时 13/25 失败）、`DataFlowPolicySqlTest`（8）、`SyncViewModelTest`、`SendReviewViewModelTest`、`ResumePlannerTest`、真网关“配对之后零 Destination、没授权：网关上一个字节也没有”。原来默认“配对就同步”的测试在装配处（`SyncRig`/`DispatchRig`）显式授权，没有削弱任何断言 | 10 条新增（`policy-*`、`ui-send-page-*`、`real-gw-policy-gate-open`） | E2 |
| B2 阻断：预览拦住自动发送 | `9e9ee03` | 网关新增 `POST /v1/records/{id}/revisions/{n}/deliveries`（给已入库的不可变 Revision 追加 Delivery，幂等，来源设备专用）；手机：同步任务在发布 Revision 的事务里一定创建、不等任何决定，自动目标随第一次 PUT 建 Delivery，预览目标在确认后由引擎追加（每个目标一本账 `NOT_YET/CREATED/REFUSED`，失败有界退避，重启后补做）。合同当时仍是 1.1（只加可选端点与两个消息，既有样本一个字没动，细节见 13.6；R7 之后是 1.2） | 网关 `test_delivery_additions.py`（11 个，红时 11/11）；真网关 2 个（自动与预览并存、预览确认后追加）红时失败；`DispatchEngineTest`、`DispatchStoreContract`、`DispatchSqlTest`、`DeliveryAdditionsLoopTest`、`ResumePlannerTest`、`SendReviewViewModelTest` 按新语义重写 | 11 条新增（含 M3 的 3 条）；原 30 条里 3 条因旧语义消失而删除，见 13.4 | E2 |
| B3 阻断：撤销凭证不停投递 | `e6bebd6` | 撤销**最后一条有效凭证** = 该 Destination “暂停”：撤销那一刻 `PENDING`/`LEASED` 的 Delivery 取消（沿用状态机的 `DESTINATION_REVOKED`，不新增状态）；暂停期间提交里点名它不报错、只是不建 Delivery；签发新凭证才恢复，且只对之后入库的文字生效，之前和暂停期间的文字永远不会补给新凭证。Destination 资源多一个可选字段 `credential_active`（合同当时仍是 1.1；严格校验的旧客户端会拒收带它的响应，所以 R7 把版本升到 1.2）。手机读它、不为暂停的目的地规划新文字、列表和详情页显示“已暂停” | 网关 `test_credential_suspension.py`（7）、`DestinationClientTest`、`DestinationServiceTest`（4）、`DispatchStoreContract`（6，内存与 SQLite 共用）、`DispatchSqlTest`、应用的列表/详情/文案测试、两条真网关用例（经网关命令行撤销凭证）；Ticket 07 的一条断言被**有意翻转**（见 13.2 第 2 条） | 14 条新增 | E2 |
| B4 阻断：PIN 能通过核验 | `248c882` | 只要 `BIOMETRIC_STRONG`，去掉 `DEVICE_CREDENTIAL`；没登记生物识别就不弹框、不生成凭证并明说。策略搬进 `BiometricGate`（接缝是小接口 `BiometricFramework`），`BiometricLocalAuthenticator` 只剩 Android 适配；没有设备凭据后系统要求自带“取消”按钮，已加并映射成“已取消” | `BiometricGateTest`（“只有 PIN 的手机”只在请求里带 `DEVICE_CREDENTIAL` 时才通过，所以应用请求什么决定测试结果；红于旧的掩码）、`DestinationTextTest` | 3 条新增 | E2；真实弹框与取消按钮 `NOT_RUN`（第 4 节第 2 步，13.3） |
| B5 阻断：复制的凭证不清 | `404504b` | `SecretClipboardGuard`：复制时标记为敏感并在描述里记下标记与时刻（不含凭证）；60 秒到点只清自己放的那一份，别的应用后来复制的东西不动；再复制重新计时；后台读不到时照清（**R6 之后改成读不到绝不清**）；进程被杀靠窗口拿到焦点时补查（**第三轮 S11 取代：描述里的标记整个删除，归属改按内存里的内容摘要认，进程被杀后不再补查，见 15.1**）；面板写真实的秒数 | `SecretClipboardGuardTest`（8 个，红时 6 个；假剪贴板按 Android 规则：后台读不到、写与清仍可）、`ClipboardWiringTest`（钉住 minSdk ≥ 28） | 9 条新增 | E2；剪贴板适配器 `NOT_RUN`（第 4 节） |
| B6 阻断：凭证面板的竞态 | `ea766a0` | 面板状态与“第几代”合成一个值 `Panel(generation, ui)`，所有改动都是一次 `MutableStateFlow.update`（比较并交换），不存在“比完代数、赋值之前页面离开了”的缝；离开、退到后台（正在显示或等待时）、`onCleared` 都换一代，旧一代的结果不显示、不留；若那是已签发的凭证就记“已丢掉，请轮换” | `DestinationDetailViewModelTest`（受控调度器决定“离开”与“结果到达”谁先，两种次序都测；第二个请求遇到第一个在途；旧结果到达时新请求在进行）、`CredentialSurfaceGuardTest`（没有单独的代数变量、面板只经 `update` 写） | 5 条新增，另有 4 条旧条目按新语义重述 | E2；“检查与赋值之间”是线程抢占窗口，协作式测试调度器复现不了，这半边由源码守卫钉住，不是红→绿的行为测试 |
| H1 高：确认后被杀没人叫醒 | `3b96dcf` | `ResumePlanner` 不再把“欠的追加”当独立的流，在配对、任务表、策略、发送账任一输入变化的那一刻一起重新算，不会比任务表晚一拍；重启的进程只从持久化的表推出同一个答案 | `ResumeRecoveryTest`：确认发生在入库之前时唤醒保持到追加做完（红时 `[ensure, cancel]`）；提交之后、唤醒之前被杀与确认之后、唤醒之前被杀两个用例（回归守卫，起初就绿，因为启动时的评估读持久化的账）；`ResumeWiringGuardTest` | 4 条新增 | E2；真实 `JobScheduler` 与系统叫醒被杀的进程 `NOT_RUN`（13.3） |
| H2 高：重新配对后旧网关的目的地 | `808081e` | `DeliveryAdditions.useGateway(gatewayId)`：引擎在构造提交前、读欠的追加前调用；网关不同就在一个事务里清空目的地缓存，并**撤回**所有还没有 Delivery 的目标（已发生、已拒收的当历史留着）；`applyDestinations` 与 `forgetDestinations` 走同一个例程；同一网关再调用不改变也不发信号 | `GatewaySwitchTest`（待发文字、欠的追加、先开发送页三个次序；红时 422 与错发给 B 的追加）、`DispatchStoreContract` 5 个新用例（内存与真 SQLite） | 6 条新增 | E2 |
| H3 高：旧列表盖掉新撤销 | `0cf76f2` | 两层互相独立：`DestinationService` 的管理往返（读列表、创建、撤销、签发）一次只一个（共用一把往返锁，核验仍在锁外）；发送账 `upsert` 不让较早算好的快照把已撤销的改回有效 | `StaleListResponseTest`（3，用闩把“回答在路上耽搁”钉死：撤销、创建、签发之后才到）、`DispatchStoreContract.a_revoked_destination_is_never_restored_by_an_older_snapshot`（内存与 SQLite） | 3 条新增 | E2 |
| H4 高：读发送账失败杀进程 | `9f4bf6d` | `containReadFailures` 包住会读 SQLite 的流：只记事件名与异常类名，发出“读不到”（带最近读到的），2 秒起翻倍、30 秒封顶、读到一次从头算、永不放弃；取消、`Error`、下游异常照常外抛。三个视图模型的状态多 `unreadable`，三个页面摆出说明；`AuriRuntime` 所有作用域经 `scopeOf`（`SupervisorJob` + 调度器 + 只记类名的异常处理器） | `ReadFailureContainmentTest`（10：真视图模型、真服务、真发送账只在读的入口抛异常；真录音协调器在读失败期间与恢复之后都没受影响并能正常停下）、`RuntimeScopeGuardTest`（2） | 20 条新增 | E2；进程真的不退出 `NOT_RUN`（13.3） |
| M1 中：第 31 条之外的预览够不着 | `828bf17` | 发送账新增 `awaiting()`（所有还有目标等用户决定的文字，新的在前，不限条数）；待发送页取“最近一页 ∪ 更早的等确认文字”去重 | `SendReviewBacklogTest`（31+ 条）、`DispatchStoreContract` 两个用例 | 4 条新增 | E2 |
| M2 中：只剩一句“已同步” | `56d9867` | `SyncStatus` 多 `awaitingYou`（等用户确认的条数，不放进 `counts`），`SyncStatus.watch` 同时盯配对、任务表、数据流、发送账；同步页另说一句“有 N 条文字在等你确认：确认之前不会发给对应的 Agent……”，“已同步”那句不改。评审的原话（预览把 Revision 拦成 `UP_TO_DATE`）已被 B2 消除（文字一定入库）；仍成立的是“投递在等人” | `SyncStatusAwaitingTest`（4）、`SyncScreenTextTest`、`SyncStatusWiringTest`（2 个源码守卫） | 8 条新增 | E2；页面真的显示 `NOT_RUN` |
| M3 中：离线的自动项意图被锁死 | 并入 B2 `9e9ee03` | 意图冻结挪到“找到网关、请求马上要离开手机”那一刻（构造提交时在同一个事务里冻结）；没网、排队、等网关时标签还能改，发出去的是冻结的那个值。为此引擎在联系网关之前多问一次放行（B1 的“收回之后连网关都不碰”不退步） | `DispatchStoreContract`（内存与 SQLite 共用）的 `building_the_submission_freezes_the_intent_so_a_later_change_is_refused_and_the_frozen_value_stays` 与“同一个事务里冻结”一条、`DispatchEngineTest` 的“没网时文字排着队，标签还能改，网关收到的是改后的” | 3 条（`dispatch-sql-publication-does-not-freeze`、`dispatch-sql-set-intent-ignores-lock`、`engine-freezes-intent-before-locating-gateway`） | E2 |
| M4 中：50 个上限看陈旧缓存 | 手机一半并入 `9e9ee03`；网关一半 `5c5deda` | 手机：第一次提交最多带合同上限个目标，其余走追加，不再 `take(50)` 悄悄丢第 51 个。网关：一个 Tenant 至多 50 个有效（`revoked_at IS NULL`）Destination，第 51 个 `POST /v1/destinations` 回 `409 REQUEST_INVALID`（`retryable=false`，`required_action` 说先撤销用不到的），什么也不创建；在创建事务里数；已撤销的不占名额；网关上已有的旧数据不动。手机去掉缓存预检，创建端点的这个 409 映射成 `LimitReached` | 网关 `test_destination_limit.py`（5，红时 5/5）；`DestinationServiceTest` 三个新用例（网关已满而缓存不知道；缓存记着满 50 个而网关上已撤销了几个；别的 422 不当成上限）；真网关一个用例（另一部缓存为空的手机被网关拒绝，撤销一个后能建）；`DestinationTextTest` | 8 条新增 + 6 条 pytest 侧手工变异（13.4） | E2 |

### 13.2 假设与取舍（做决定时没有人可问；每一条都可以推翻）

1. **B2 的方案：文字照常入库，预览只管 Delivery**。取舍：用户确认之前文字已经在他自己的网关库里（Agent 没有 Delivery 就读不到）；原先“确认之前网关上什么也没有”的保证改成“确认之前没有任何 Agent 读得到”。要严格不出手机用“严格仅本地”。
2. **B3 选“取消”而不是“保留但不可领”**；暂停期间提交里点名它**不报错**（手机缓存可能还不知道凭证被撤销，整条被拒会让文字永远进不了网关）。Ticket 07 的 `test_cli_revoke_credential_blocks_only_that_credential` 原先断言“撤销前排着的文字被新凭证领到”，那正是缺陷本身，**有意翻转**为“新凭证领不到撤销前的、只领得到之后入库的”（`tests/integration/test_gateway_operations.py`，+5/−1）。
3. **B5 偏离评审的措辞**：评审要求面板关闭、退后台、到时间三个时刻都清；只做了“到时间”（60 秒），**没有**在关闭和退后台那一刻清：复制本身就会关面板，唯一的用处是粘贴到别的应用，几乎总要退出本应用，一退后台就清等于把刚复制的东西当场删掉、用户只能再轮换一次。要不要在那两个时刻也清是产品决定（改法：在退后台的回调里调一次 `clear()`）。**【已被 R6 取代：离开本应用时现在会清（读得到才清），读不到绝不盲清；见 14.2 第 1 条】**
4. **B6 在途请求在退后台或离开时作废**，已签发的凭证提示轮换（网关里它已经存在）；这推翻了旧规则“在途的凭证到达后照样显示”，对应的旧测试改成断言相反的结果（凭证仍然不显示、不保留，用户现在被告知）。
5. **H2 是“撤回”不是“重新规划”**：重新配对到另一个网关时，没有 Delivery 的目标一律撤回；仍在等、或已存到旧网关的文字在新网关上无目标入库，不会自动投给新网关的 Destination（那不是用户给过的授权）。
6. **H3 只有撤销是单调的**（`revoked` 不会被较早的快照改回）；创建与签发靠往返串行化。
7. **H4 只治本票的三个视图模型与 `AuriRuntime` 的作用域**。本票之外的 `SyncResumeService`、`ControlWiring` 各有一个自建作用域没有异常处理器，**没动**；Home、Sync、Models、Control 这些视图模型读的是内存状态流，**没有逐个审计**。
8. **M1 的等确认列表不分页、不设上限**：每一条都是一个等用户的决定，截断就是 M1 本身。
9. **M2 的标题“已同步”不改**（文字确实都在网关里）；“用户已确认、但网关上 Delivery 还没追加完”的欠账不进标题（残余）；条数用“读出再数”（`SyncStatus.watch` 上有 shortcut 注释，列表很长时换 `COUNT` 查询）。
10. **M4 用网关的 `409 REQUEST_INVALID`，不新增错误码**（增加错误码属于不兼容改动，要升版本），合同当时仍是 1.1（R7 之后是 1.2），只加一条网关规则；创建端点只有这一个原因回 409，所以手机把它映射成 `LimitReached`，别的拒绝（例如 422）照旧是普通拒绝。**旧的手机侧上限测试被替换**：它的前提是“本机缓存是裁判、请求没有发出”，评审把这个前提反过来了，换成三个反前提的用例。变异表自检的“只许改生产代码”规则扩到网关包 `services/gateway/auri_gateway/`（`tests/`、`*Test.kt`、`androidTest`、`testFixtures` 仍然禁止）。
11. **并发测试的证明力有限（M4）**：网关的创建处理器是 `async def`，直接在事件循环里调用同步的存储函数，创建请求实际上一个接一个跑，所以“12 个客户端同时建 58 个，恰好 50 个成功”这条端到端测试证明不了 `BEGIN IMMEDIATE` 本身（把它改成 `BEGIN` 也不会红，等价变异，13.4 如实记录）。
12. **没有 Codex 评审、没有第二轮独立评审**（单一 agent、禁止子 agent）。

### 13.3 要在设备上核对的（`NOT_RUN`，原因：为省内存禁用模拟器，没有连着的设备）

前提同第 4 节（调试版已装、电脑上网关在跑、已配对）；电脑上看网关库：`sqlite3 /tmp/auri-gateway-data/gateway.db '<语句>'`（表 `revisions`、`deliveries`、`destinations`、`destination_credentials`），Agent 一侧用 `real_gateway_agent.py claim|ack|probe`（第 4 节开头的三个环境变量）。

| 项 | 做什么 | 期望 |
|---|---|---|
| B1 授权与严格仅本地 | 配对后不开任何开关，录一句话；再打开“允许把文字发送到我配对的网关”；再打开“严格仅本地”并录一句；杀掉应用再进来 | `select count(*) from revisions` 依次是 0、1、1；同步页写“文字还没有发出去……”；两个开关的位置杀进程后保持 |
| B2 自动与预览并存 | 建一个“自动发送”和一个“预览确认”的目的地，录一句话 | 自动目的地的凭证 `claim` 马上领到；预览目的地的 `claim` 为空；点“确认发送”后才领到；`select destination_id, state from deliveries` 各一行 |
| B3 撤销凭证 | 电脑上 `PYTHONPATH=services/gateway .venv/bin/python -m auri_gateway revoke-credential --data-dir /tmp/auri-gateway-data --credential-id <id>`；手机“刷新”；再录一句；在手机上“轮换凭证” | 手机显示“已暂停”；暂停期间新文字没有为它建 Delivery；轮换后新凭证 `claim` 领不到撤销前的文字，只领得到之后的 |
| B4 强生物识别 | 第 4 节第 2 步“核验只认强生物识别”的 ①②③ | 只有 PIN 的手机不弹框、没有凭证；登记指纹后框里只有指纹/人脸与“取消”，取消 = 已取消、没有凭证 |
| B5 剪贴板 | 第 4 节第 2 步“剪贴板”那一条的四种做法 | 65 秒后空；别的应用复制的东西不被清；杀进程后重进补清（**第三轮 S11 取代：杀进程后认不出归属，凭证应仍在剪贴板里，见 15.4**） |
| B6 面板代数 | 第 4 节第 2 步“生成凭证的过程中”那一条 | 核验框弹着期间页面不收到 `ON_STOP`；网关回答前按 Home 再回来：面板空，随后“已丢掉，请轮换” |
| H1 唤醒 | 关掉耳机控制模式；预览确认后按 Home，再 `adb shell am kill app.auri.android`（**不要用 `force-stop`**：它会连应用自己登记的 `JobScheduler` 任务一起取消，等于没测；这一行原先写的是 `force-stop`，R12 时改正）；`adb shell dumpsys jobscheduler \| grep -B2 -A6 app.auri.android` | 欠着追加时有已登记的任务；系统叫醒后 `claim` 领到确认过的那条。自动化版本是 `ResumeSchedulingTest`（`androidTest`，要设备） |
| H2 换网关 | 另起一个网关（另一个数据目录与端口）并重新配对，再录一句 | 新网关 `select count(*) from revisions` 为 1，没有 422；`adb logcat -d -s AuriSync:I` 里没有旧网关的目的地编号 |
| H4 读失败不杀进程 | 录音进行中，`adb shell run-as app.auri.android ls databases/` 找到发送账所在的库，临时 `chmod 000`，打开“发送”页；之后 `chmod 600` 还原 | `adb shell pidof app.auri.android` 不变、录音继续；页面说读不到并在退避间隔内自己恢复；`adb logcat -d` 里只有事件名与异常类名，没有路径与正文 |
| M2 同步页 | 有预览目标在等时看“同步与配对”页 | 有“有 N 条文字在等你确认……”一句，“已同步”那句没变；确认或选择不发送后条数减少 |
| M3 离线改意图 | 关掉网关（或手机断网），录一句并在“待发送与标签”里改成“请求执行”，再开网关 | `claim` 里的 `intent` 是 `action_request` |
| M4 上限 | 在手机上建满 50 个有效目的地，再建第 51 个；撤销一个后再建 | 页面说“网关上有效的目的地已经有 50 个……没有创建新的”，`select count(*) from destinations where revoked_at is null` 是 50；撤销一个后能建 |

设备上的自动化测试（`NOT_RUN`）同第 4 节末尾：`AURI_ALSO_ASR_ACCEPTANCE=1 execution/acceptance/android-sync/run_emulator_acceptance.sh`（一次性无头模拟器，先取共享锁），或已连设备时 `cd apps/android && ./gradlew :syncplatform:connectedDebugAndroidTest`。

### 13.4 变异检查：整张表重跑

在**所有修复提交之后**（代码 = `5c5deda`），逐条真跑整张表：`destination_mutation_check.py --only <编号>`，每一条各自取共享锁、跑前后各读一次内存、条与条之间放锁；05:21:30–06:19:18，共 57 分 48 秒。结果：**132 条全部 CAUGHT，SURVIVED 0，INVALID 0**，运行期间没有残留的网关进程。

- 原 30 条：27 条原样保留，重跑全部 CAUGHT；3 条随 B2 删除——`dispatch-preview-not-held`、`dispatch-sql-preview-not-held`（它们断言“预览目标没决定就不创建同步任务”，B2 把这条语义本身去掉了）与 `real-gw-source-ignores-publication`（新语义下自动目标也可由追加建成，第一次 PUT 里带 `publication` 由 `DispatchEngineTest` 把守）——由 B2/M3 的新条目（`dispatch-awaiting-*`、`engine-additions-*`、`dispatch-sql-preview-recorded-as-auto`、`dispatch-sql-publication-does-not-freeze` 等）取代，不是为了让表通过。
- 评审修复新增 105 条（各提交实际增加的条目数）：B1 10、B2 与 M3 共 11、B3 14（B3 提交说明里写的“15 条”是笔误）、B4 3、B5 9、B6 5、H1 4、H2 6、H3 3、H4 20、M1 4、M2 8、M4 8。27 + 105 = 132。
- 评审点名的 `b6-lost-overwrites-newer-request`：CAUGHT，恰好一个测试变红（`DestinationDetailViewModelTest.旧凭证到的时候新一代正在要凭证：不打扰它（还是“进行中”），新凭证随后显示，网关里只认新的`，1/25）。
- 每个修复提交各自真跑过它自己的新条目（全部 CAUGHT）；这次是全部修复完成后的整表重跑。修复过程中真跑出过的问题与处理（都是改变异表，没有放宽任何测试）：H4 有两条因删掉一行后逗号悬空而编译失败（INVALID）、一条“等 0 毫秒”在虚拟时间里让测试自己死循环，改写后重跑 CAUGHT；M4 的 `m4-service-trusts-the-cache` 第一次 SURVIVED——预期的测试名我列多了：网关已满而缓存为空的那条用例本来就看不见缓存预检（它守的是另一个破坏），真正会红的是“缓存记着满 50 个而网关上已撤销了几个”那条，更正预期后 CAUGHT；变异表的自检规则为 M4 放宽到网关包（13.2 第 10 条）。
- 表自己的离线检查 `test_destination_mutation_table.py` 在最终套件里再跑一遍（13.5）。
- **网关上限规则的 pytest 侧手工变异**：Kotlin 的变异机器只跑 Gradle，所以这几条是在仓库外的副本里改 `services/gateway/auri_gateway/store.py`、跑 `tests/integration/test_destination_limit.py`，工作树一个字节没动（脚本是临时的，已删）。

| 破坏 | 结果 | 变红的测试 |
|---|---|---|
| 去掉上限判断（`if False:`） | CAUGHT | 全部 5 个 |
| `>=` 改成 `>`（放过第 51 个） | CAUGHT | 全部 5 个 |
| 数名额时把已撤销的也算 | CAUGHT | `test_a_revoked_destination_frees_exactly_one_slot` |
| 数名额不分 Tenant | CAUGHT | `test_the_limit_counts_each_tenant_separately` |
| 回 422 而不是 409 | CAUGHT | 全部 5 个 |
| 去掉 `required_action` | CAUGHT | `test_the_51st_active_destination_is_refused_visibly_and_creates_nothing` |
| `BEGIN IMMEDIATE` 改成 `BEGIN` | **存活（等价变异）** | 创建处理器是 `async def`，在事件循环里一个接一个跑，现在没有数据库层的并发可供它暴露；`BEGIN IMMEDIATE` 是防御性的 |

### 13.5 最终验证（最新结果）

方法与 12.1 相同：本机 macOS，交换区一直不空（本轮读数约 2.6 GB，修复之前那轮接近 4 GB），所以每个 Gradle / pytest 都**单独**取共享锁 `~/.cache/auri/gradle.lock`、一次只跑一个、跑完立刻放锁；Gradle 参数 `--no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2`，测试任务加 `--rerun`（表里的数字是该次运行写出的 JUnit XML 的合计，不是缓存）；每次运行前后各读 `memory_pressure` 的空闲百分比与 `sysctl vm.swapusage`（空闲低于 25% 或 60 秒内交换区涨超 500 MB 就等，本轮没有一次触发等待）；pytest 带 `NO_PROXY=localhost,127.0.0.1,::1 no_proxy=localhost,127.0.0.1,::1`，前后查 `pgrep -fl auri_gateway`，每一次残留都是 0；没有执行过 `./gradlew --stop`；**没有**启动模拟器、AVD、Android Studio、Xcode、DevEco 或任何仿真器，没有运行仪器测试（只编译打包）。先 `git fetch origin` 并核对 `origin/integration/auri-v2`（`10be78c`）：本分支落后 0 个提交，没有东西可合并（第二轮结束后再核对一次，仍是 0）。

**分两轮**：第一轮在 `5c5deda`（全部 14 条发现的修复完成之后）跑**全部**套件，有两个红（`android-destinations`、`android-sync`，原因与处理见 13.7，都是旁路检查没跟上 B2，不是产品代码）；修复提交 `3376705` 只改了 `:sync` 的一个测试文件、本票的 Python 校验工具与 Ticket 21 的变异表，所以第二轮只重跑被它碰到的三个套件（`sync`、`android-destinations`、`android-sync`），其余套件的第一轮结果对最终代码仍然成立（产品代码自 `5c5deda` 起没有再变）。表里两轮的行都列出，红的行也留着。

#### Gradle（`apps/android`）

| 轮次（HEAD） | 套件 | 命令 | 结果 | 起止（本机时间） | 内存空闲（前 → 后）与交换区已用 |
|---|---|---|---|---|---|
| 1（`5c5deda`） | `assemble-debug` | `./gradlew :app:assembleDebug` | exit 0：构建成功 | 06:20:39–06:20:50 | 66% → 65%；交换区 2601 → 2601 MB |
| 1（`5c5deda`） | `capture` | `./gradlew :capture:test --rerun` | exit 0：369 个，失败 0、出错 0、跳过 0 | 06:20:50–06:20:59 | 65% → 65%；交换区 2601 → 2601 MB |
| 1（`5c5deda`） | `modelmgmt` | `./gradlew :modelmgmt:testDebugUnitTest --rerun` | exit 0：84 个，失败 0、出错 0、跳过 0 | 06:20:59–06:21:36 | 65% → 65%；交换区 2601 → 2601 MB |
| 1（`5c5deda`） | `platform` | `./gradlew :platform:testDebugUnitTest --rerun` | exit 0：257 个，失败 0、出错 0、跳过 0 | 06:21:36–06:21:49 | 65% → 65%；交换区 2601 → 2601 MB |
| 1（`5c5deda`） | `app` | `./gradlew :app:testDebugUnitTest --rerun` | exit 0：297 个，失败 0、出错 0、跳过 0 | 06:21:49–06:22:01 | 65% → 63%；交换区 2601 → 2601 MB |
| 1（`5c5deda`） | `asr` | `./gradlew :asr:testDebugUnitTest --rerun` | exit 0：112 个，失败 0、出错 0、跳过 0 | 06:22:01–06:22:08 | 63% → 64%；交换区 2601 → 2601 MB |
| 1（`5c5deda`） | `sync` | `./gradlew :sync:test --rerun` | exit 0：440 个，失败 0、出错 0、跳过 0 | 06:22:08–06:22:22 | 64% → 65%；交换区 2601 → 2601 MB |
| 2（`3376705`） | `sync` | `./gradlew :sync:test --rerun` | exit 0：440 个，失败 0、出错 0、跳过 0 | 06:47:08–06:47:22 | 61% → 62%；交换区 2593 → 2593 MB |
| 1（`5c5deda`） | `syncplatform` | `./gradlew :syncplatform:testDebugUnitTest --rerun` | exit 0：105 个，失败 0、出错 0、跳过 0 | 06:22:22–06:22:33 | 65% → 64%；交换区 2601 → 2601 MB |
| 1（`5c5deda`） | `realgateway` | `./gradlew :sync:realGatewayTest --rerun` | exit 0：25 个，失败 0、出错 0、跳过 0 | 06:22:33–06:23:15 | 64% → 63%；交换区 2601 → 2601 MB |
| 1（`5c5deda`） | `androidtest-apks` | `./gradlew :app:assembleDebugAndroidTest :syncplatform:assembleDebugAndroidTest :platform:assembleDebugAndroidTest` | exit 0：构建成功 | 06:23:15–06:23:30 | 63% → 63%；交换区 2601 → 2601 MB |

#### Python 验收与仓库套件（每个都是单独一次 pytest）

| 轮次（HEAD） | 套件 | 命令 | 结果 | 起止（本机时间） | 内存空闲（前 → 后）与交换区已用 |
|---|---|---|---|---|---|
| 1（`5c5deda`） | `android-capture` | `pytest execution/acceptance/android-capture` | exit 0：59 passed in 50.70s | 06:23:30–06:24:21 | 63% → 64%；交换区 2601 → 2601 MB；残留网关进程 0 |
| 1（`5c5deda`） | `android-control` | `pytest execution/acceptance/android-control` | exit 0：106 passed in 54.23s | 06:24:21–06:25:16 | 64% → 64%；交换区 2601 → 2601 MB；残留网关进程 0 |
| 1（`5c5deda`） | `android-silence` | `pytest execution/acceptance/android-silence` | exit 0：3 passed in 0.03s | 06:25:16–06:25:16 | 64% → 64%；交换区 2601 → 2601 MB；残留网关进程 0 |
| 1（`5c5deda`） | `android-models` | `pytest execution/acceptance/android-models` | exit 0：2 passed in 0.02s | 06:25:16–06:25:16 | 64% → 64%；交换区 2601 → 2601 MB；残留网关进程 0 |
| 1（`5c5deda`） | `android-sync` | `pytest execution/acceptance/android-sync` | **exit 1：10 failed, 804 passed in 183.49s (0:03:03)** | 06:25:16–06:28:20 | 64% → 66%；交换区 2601 → 2601 MB；残留网关进程 0 |
| 2（`3376705`） | `android-sync` | `pytest execution/acceptance/android-sync` | exit 0：814 passed in 178.39s (0:02:58) | 06:47:44–06:50:43 | 63% → 64%；交换区 2593 → 2593 MB；残留网关进程 0 |
| 1（`5c5deda`） | `android-destinations` | `pytest execution/acceptance/android-destinations` | **exit 1：2 failed, 431 passed in 21.77s** | 06:28:20–06:28:42 | 66% → 65%；交换区 2601 → 2601 MB；残留网关进程 0 |
| 2（`3376705`） | `android-destinations` | `pytest execution/acceptance/android-destinations` | exit 0：457 passed in 21.53s | 06:47:22–06:47:44 | 62% → 63%；交换区 2593 → 2593 MB；残留网关进程 0 |
| 1（`5c5deda`） | `android-interruptions` | `pytest execution/acceptance/android-interruptions` | **exit 5：no tests ran in 0.00s** | 06:28:42–06:28:42 | 65% → 65%；交换区 2601 → 2601 MB；残留网关进程 0 |
| 1（`5c5deda`） | `asr-android` | `pytest execution/acceptance/asr-android` | exit 0：131 passed in 46.80s | 06:28:42–06:29:29 | 65% → 64%；交换区 2601 → 2601 MB；残留网关进程 0 |
| 1（`5c5deda`） | `repo` | `pytest tests/contract tests/integration tests/security` | exit 0：529 passed in 230.66s (0:03:50) | 06:29:29–06:33:20 | 64% → 66%；交换区 2601 → 2601 MB；残留网关进程 0 |

- `android-interruptions` 是 exit 5：该目录只有 README、没有 Python 测试（Ticket 19 的区域，没动），所以 pytest 一个测试也没收集到；这是它一直的样子，不是跳过。
- `android-silence`（3 个）、`android-models`（2 个）是纯静态检查，不调用 Gradle，所以只要零点几秒。
- 变异表整表重跑（13.4）：132 条全部 CAUGHT，约 57 分 48 秒；Ticket 21 表里目标文件被评审修复轮改过的 119 条（含被 B2 改了锚点的 5 条）重新真跑，119 条全部 CAUGHT，其余 134 条没有重跑（13.7）。
- 测得的数字（E2）：网关与合同套件 529 个 / 约 3 分 50 秒（租约到期测试要真实等待）；真网关 25 个；`:sync:test` 440 个；本票的 Python 验收见上表。没有任何性能或设备数字，因为没有设备。
- 没有做、也没法做的：所有设备或模拟器才能证明的（13.3 列了命令与期望），状态都是 `NOT_RUN`，原因：为省内存禁用模拟器、没有连着的设备。

### 13.6 评审修复轮对共享文件的改动

| 文件 | 改动 | 为什么 |
|---|---|---|
| `contracts/schemas/revision.schema.json` | +45（B2） | 追加 Delivery 的请求 `delivery_additions` 与响应 `deliveries_added` 两个消息；既有字段、必填项、枚举、状态机一个没动 |
| `contracts/schemas/destination.schema.json` | +4（B3） | Destination 资源多一个可选字段 `credential_active` |
| `contracts/fixtures/messages/`（8 个新样本） | 新增（B2、B3） | 有效：`revision.delivery_additions.confirmed-preview`、`revision.deliveries_added.created-and-existing`、`destination.resource.credential-revoked-suspended`；无效：`delivery_additions` 的 `execute-intent` / `no-destinations` / `tenant-id-in-body`、`deliveries_added.acked-spelling`、`destination.resource.credential-active-not-boolean`。**既有样本一个没改、没删** |
| `contracts/README.md` | +4/−1（B2、B3、M4） | “另有几条规则依赖网关的状态”下的三条语义规则：追加 Delivery 的端点、凭证被撤销 = Destination 暂停、一个 Tenant 至多 50 个有效 Destination；三条当时都留在 1.1（只加可选端点/可选字段/网关规则，没有新错误码、必填项或状态机改动；R7 之后整个合同升到 1.2） |
| `services/gateway/auri_gateway/store.py`、`app.py` | +276/−75 与 +35/−19（B2、B3、M4） | 追加端点、凭证暂停（撤销取消排着的投递、暂停期间不建、签发才恢复）、创建时的 50 个上限 |
| `services/gateway/OPERATIONS.md` | +15（B3、M4） | “撤销凭证的后果”与“Destination 数量上限”两段运维说明 |
| `tests/integration/`（`test_delivery_additions.py` 11、`test_credential_suspension.py` 7、`test_destination_limit.py` 5） | 新增 | 网关侧的红→绿测试；`test_gateway_operations.py` 一处断言有意翻转（13.2 第 2 条） |
| `tests/contract/test_messages.py` | +2 | 登记新消息的 schema 名，让新样本被已有的有效/无效样本测试覆盖（没有改任何既有预期） |
| `execution/acceptance/android-destinations/test_destination_mutation_table.py` | 一处放宽 | “变异只能碰生产代码”的范围扩到网关包（见 13.2 第 10 条） |
| `apps/android/sync/src/test/.../contract/DestinationContractOutputTest.kt`、`execution/acceptance/android-destinations/destination_contract_check.py`、`test_destination_contract_check.py`、`test_destination_contract_output.py` | 最终验证时发现并改（13.7 第 1 条，`3376705`） | 合同输出的场景与校验器原先还按 B2 之前“预览确认的文字确认前不入库”的语义；改成 B2 的语义，并多校验追加请求的 schema |
| `execution/acceptance/android-sync/mutation_check.py`（Ticket 21 的变异表） | 4 条条目的锚点（13.7 第 2 条，`3376705`） | B2 重构 `SyncEngine.kt` / `SyncLoop.kt` 后这 4 条（连同派生的 `real-gw-gate-not-persisted` 共 5 条）的原文不再恰好出现一次；只换锚点，破坏的含义不变，并重新真跑 |
| `CLAUDE.md` | 只改“命令”一节：Ticket 22 那一段的说明与测试数字，以及网关全量 pytest 那一行的个数与耗时（529 个，约 4 分钟） | 说明按评审修复轮改正，数字改成 13.5 的最新值 |

### 13.7 最终验证里发现并修掉的两处遗漏（提交 `3376705`）

最终验证（13.5）第一次跑完时有两个套件是红的。两处都是 B2 改了语义之后我没有回头重跑的旁路检查，不是产品代码的问题；**没有放宽任何测试**。

1. **本票自己的合同输出校验还在检查旧语义**（`android-destinations`：2 failed, 431 passed）。B2 的提交只重跑了 Kotlin、网关与变异测试，没有重跑 Python 验收，所以 `DestinationContractOutputTest`（Kotlin 写出场景）与 `destination_contract_check.py`（校验）还停在“预览确认的文字在确认前不入库、网关上什么也没有”。失败的两个测试断言的恰是 B2 明确取消的那句话（13.2 第 1 条），不是回归。处理：校验器与 Kotlin 输出测试按 B2 的语义重写（第 9 节的表），并且**更严**：`preview-held-before-confirm` 要求文字已入库（恰好 1 次 PUT）、入库请求不点名预览目的地、0 次追加、0 个 Delivery；`preview-after-confirm` 要求仍是 1 次 PUT、确认（含重复确认）后恰好 1 次追加请求（请求体按 `delivery_additions` 的 schema 校验——这是 B2 新增的合同消息，以前没有任何输出被它校验过）、恰好 1 个 Delivery；`auto` / `action_request` 场景要求没有追加。校验器自己的测试 46 个，**对着修复之前的校验器有 22 个红**（先红），对着现在的全过。
2. **Ticket 21 的变异表有 4 条（派生的 `real-gw-gate-not-persisted` 共 5 条）的锚点失效**（`android-sync`：10 failed, 804 passed，即这 5 条各 2 个离线自检）。B2 把 `SyncEngine.kt` 里记连接问题的那段抽成 `recordConnectionProblem`、在 `networkChanged()` 与 `SyncLoop.nudge()` 里加了 `resetAdditionsBackoff()`，表里这 5 条的原文不再恰好出现一次。处理：只换锚点（`engine-blocks-on-stale-connection-verdict`、`engine-does-not-persist-connection-gate`、`engine-never-clears-offline`、`loop-nudge-does-not-rearm`），破坏的含义不变；离线自检 767 个全过，并且这 5 条重新真跑，全部 CAUGHT。既然动了 Ticket 21 的共享文件，就把 Ticket 21 表里**目标文件被评审修复轮改过的 119 条**（`SyncEngine.kt` 46、`SyncLoop.kt` 17、`SyncViewModel.kt` 16、`SyncStatus.kt` 8、`ResumePlanner.kt` 8、`SqliteSyncJobStore.kt` 8、`SyncScreen.kt` 7、`GatewayClient.kt` 4、`AuriRuntime.kt` 2、`Wire.kt`、`MainActivity.kt`、`SyncRuntime.kt` 各 1，含上面这 5 条；其中 8 条是真网关版，跑 `:sync:realGatewayTest`）逐条真跑（每条各自取锁，约 45 分钟，快照放在私有目录，跑完已删）：**119 条全部 CAUGHT，SURVIVED 0、INVALID 0**，即 B1/B2/H1/H2/M3 对这些文件的改动没有削弱 Ticket 21 表对它们的覆盖。其余 134 条（目标文件在评审修复轮没有改动）只确认了锚点仍然恰好出现一次（离线），**没有重新真跑**（整表约 2 小时）：B1 在装配处（`SyncRig`、`DispatchRig`）显式授权可能影响用到这些装配的测试，所以不能说它们没被削弱——这是遗留的未核对项。

### 13.8 收尾：运行环境读数、临时文件与过程说明

- **范围**：评审修复轮 = `59bf9e6..HEAD`，`git diff --shortstat` 为 109 个文件、+7371/−797。
- **内存读数**：本轮每次 Gradle / pytest 运行前后都读了空闲内存百分比与交换区（共 762 次读数）：空闲 45%–69%，交换区 2357–2721 MB；**没有一次触发“等内存”**；同一时刻只有一个 Gradle 或 pytest 进程，共享锁 `~/.cache/auri/gradle.lock` 每次用完即删（收尾时不存在）；所有 pytest 前后的 `auri_gateway` 残留进程都是 0；没有执行 `./gradlew --stop`，没有启动任何模拟器或 IDE。每个套件的前后读数在 13.5 的表里。
- **临时文件**：本轮建过 `/tmp/auri-t22-*` 共 24 项（脚本、455 个运行日志、117 MB 的 Ticket 21 变异快照、红阶段输出），收尾时**全部删除**；`$TMPDIR` 里落在我自己运行窗口内的 238 个测试副产物目录（`auri-tls-fixtures*`、`auri-cfg*`、`pytest-of-ethan/pytest-75…77`）也已删除，工作树里的 `.pytest_cache` 与 `__pycache__`（`.venv` 之外）同样删除。**保留**：41 个 `$TMPDIR/auri-tls-fixtures*` / `auri-cfg*`，时间在 11-20 22:27–23:56，早于本轮第一次运行（11-21 00:44），不是这一轮产生的，没有动；git 忽略的 `apps/android/**/build`、`.gradle` 与 `.venv`（增量构建与测试要用）。
- **和使用者规则对不上的地方**（没有绕过，也没有假装做了）：没有跑 codex 评审——派发说明禁止子 agent 与辅助 worker，评审只靠 13.4 / 13.5 的真跑与变异检查；“每任务 4000、每会话 30000 token”的预算对这份工作量不可能满足，靠检查点摘要续作；mcp-feedback-enhanced 工具在本环境没有加载，没有调用；GitNexus 与 AOCI 本轮没有使用（工作树没有 `.gitnexus` 索引，分支上没有 `aoci.txt`；前一轮的核对见 12.1）。

## 14. 复评修复第二轮（R1–R13）

第一轮修复（第 13 节）之后又来了一轮复评，共 13 条（R1–R13；R13 是第一轮 M3 只修了一半的遗留）。做法同第一轮：**每条一个提交、先写会红的测试再修复、提交完立刻推送**（`git push origin ticket/22-destinations-and-sending`），提交信息以 `Ticket 22 re-review R<编号>:` 开头；R2、R5、R7 各有一个“跟进”提交（原因见 14.1 的表），另有一个变异表提交 `c93f3ba` 和一个改 `OPERATIONS.md` 的文档提交 `43f5f86`（14.7）。网关一侧的条目（R1、R2、R7）按任务授权改了合同与网关，其余只动 Android 与本票的验收区。证据等级与第 13 节一致：JVM 单测、真实 Python 网关进程的测试（`:sync:realGatewayTest`、`tests/integration`）、Python 校验工具都是 E2；没有设备或模拟器（为省内存禁用），凡是只有设备才能证明的一律 `NOT_RUN`，步骤在 14.4。

### 14.1 发现与修复

| 编号 | 提交 | 做了什么 | 先红后绿的测试（主要的） | 变异 | 证据 |
|---|---|---|---|---|---|
| R1 撤销之后的替换凭证读得到撤销之前的文字 | `0563d2d` | 撤销最后一条有效凭证、再签发替换凭证的那一刻，网关给这个 Destination 记一个“撤销截止点”（不低于库里已有文字的最晚入库时间，所以网关时钟曾经跑快也不会漏）；入库时间不晚于截止点的文字，替换凭证在**每一条** Agent 路径上都看不到：`record_list` 不列、`record_search` 搜不到、`record_get` 回 404、`delivery_claim` 领不到、续租/确认/拒收认不出旧的投递编号与租约；只轮换（不暂停）不设截止点；迁移 `0005` 按凭证历史给升级前已经结束的暂停补上截止点 | `tests/integration/test_revocation_cutoff.py`（11 个，R1 与 R2 共用；真网关进程 + 官方 MCP 客户端，带的是替换凭证）：先写的是“每一条路径都读不到撤销之前的文字”，对着旧网关红 | 9（`r1-gw-*`，用 pytest 跑网关，见 14.5） | E2 |
| R2 延迟到达的追加仍为截止点之前的文字建 Delivery | `b9da821`，跟进 `dfb9591` | 用户在撤销并签发替换凭证**之后**才点“确认发送”，追加请求为一条撤销之前入库的文字建出一条 `PENDING`，替换凭证随后就领得到；首次入库的 `publication` 同理。现在这两条路径都按暂停处理：对入库时间不晚于截止点的文字不建 Delivery（`200`、回答里没有它、同一请求里别的目标不受影响）；升级测试改成“用当前网关造库、再退回迁移 0005 之前”。**跟进**：首次入库的检查无条件读 `destinations.revocation_cutoff`，冻结在迁移 0005 之前的旧库（`test_gateway_operations` 的升级用例就是这样造库的）会 500；R2 提交时我没有重跑整个仓库套件，所以到这时才发现，跟进提交补上容错（真实部署里迁移先于服务，列一定在） | `test_revocation_cutoff.py` 里的 4 个 R2 用例（撤销之前 / 暂停期间 / 替换凭证之后的追加；首次入库）；跟进的红测试是 `test_gateway_operations` 的升级用例（在 `b9da821` 上 500） | 3（`r2-gw-*`） | E2 |
| R3 “文字可以进网关”的授权是全局布尔 | `bc4b5a9` | 授权不再是一个全局布尔，而属于**一个网关身份**（网关编号 + 固定公钥指纹）：对 A 授权、重新配对到 B，积压的文字（和之后录的）原本会不经同意发给 B。现在引擎、状态汇总、唤醒规划、界面都问“相对当前要联系的这个网关”的视图，所以对 A 还在跑的一轮骑不上 B 的授权；配对时先清掉所有不属于新网关的授权再换配对记录（失败只记日志，不挡一次性令牌），启动时同样对账，读取时再绑一次作兜底；行格式 v2（`granted v2 <网关编号> <指纹> <时间>`），旧的 v1（全局）读成没授权；开关只对当前配对的网关生效，没配对时禁用 | `GatewayBoundConsentTest`、`DataFlowPolicySqlTest`、`PairingServiceTest`（3）、接线与提示文字测试；真网关套件在配对之后显式授权 | 6 新增，8 条重新锚定 | E2 |
| R4 询问放行之后网络回答才到 | `3193f02` | 引擎原来只在联系网关之前问几次，状态查询的回答在路上时授权被撤销（或开了“严格仅本地”），PUT 照样发出，合同探测也排在定位回答之后。现在每次网络回答之后、真正发出 PUT / 每次追加请求之前都再问一次（定位回答之后、合同探测之后、PUT 紧前，中间只有本地计算；追加在定位之后与每个请求之前） | `PolicyAfterResponseTest`（5，经新的 `FakeGateway.onRequest` 钩子做确定的交错；“状态回答在路上”“定位回答在路上”两个在修复前是红的）、`DataFlowGateTest` 的最后一道门用例改成瞄准紧挨 PUT 的那次询问 | 4 新增，1 条重新锚定 | E2 |
| R5 过期的一轮写了新配对的账（高） | `17e6e28`，跟进 `611958a` | `PairingStore.whileCurrent(gateway, block)`：“当前配对仍是这一份（deviceId + pairedAt + 公钥相同）”的判断与 `block` 在存储的同一把锁里执行；引擎里所有改缓存/目标的调用（定位、`useGateway`、`gatewayHas`、追加的三处）与 `DestinationService` 两处写缓存都在围栏里，不是同一份就什么也不碰（`attempt` 里 `stale(key)` 重新排队，追加整遍放弃）。**跟进**：整表重跑发现围栏让两个旧测试变成冗余、而引擎里两行“配对还是原来那份吗”的检查另有职责（旧配对的永久拒绝、“已删除”、“没有这一版”之后仍拿旧令牌发 PUT）没有测试守着，补了 3 个（14.5、14.2 第 12 条） | `StalePassFenceTest`（2：定位回答在路上 / 入库回答在路上时改配到 B）、`PairingFenceAtomicityTest`（4：写账停在半路时重新配对必须排在后面，证明围栏是同一把锁里的“判断 + 写账”，不是先看一眼再写）、`FilePairingStoreTest` / `ObservablePairingStoreTest` 的 `whileCurrent` 原语测试、`PairingFenceWiringTest`（`:app`，2：源码里每一处改账调用都在围栏里）；跟进的 3 个在 `SyncEngineTest`（旧网关对 PUT 的永久拒绝 / 状态回答“已删除” / 状态回答“没有这一版”且已改用新令牌） | 10 新增，3 条重新锚定；跟进：2 条（Ticket 21 表里 `engine-accepts-stale-*`）改预期 | E2 |
| R6 剪贴板：认不出归属也照清（高） | `9ee0908` | `SecretClipboardGuard.expire()` 读不到（应用在后台，Android 10+）不再盲清，只记 `clipboard.unverifiable`——那份可能已是别人的；新增 `onLeaving()`（读得到且确是自己放的才清并作废定时器），挂在 `MainActivity.onPause()`（失去焦点前最后一次还读得到）与详情页 `DisposableEffect.onDispose`；复制之后 Toast 一句话说明条件与平台限制（`describeClipboardNotice()`）；`EXTRA_IS_SENSITIVE` 早就标了，现在有接线测试钉住 | `SecretClipboardGuardTest`（假剪贴板：读得到 / 读不到 / 别人的）、`DestinationTextTest`、接线测试。**唯一被我改了预期的既有测试**：`SecretClipboardGuardTest` 里“应用不在前台……认不出归属也到点清”，它编码的正是评审指出的缺陷，改成“读不了就不清”，并新增“读不了时剪贴板已被别人换成别的东西”这个真实后果的用例 | 8 新增；`b5-unreadable-keeps-credential` 退役（它守的规则被 R6 反过来了，它的对偶是 `r6-timeout-clears-unreadable`），`b5-notice-states-wrong-seconds` 重新锚定 | E2；剪贴板适配器 `NOT_RUN` |
| R7 网关已经说的不是 1.1 了，还自称 1.1 | `f4f8be6`，跟进 `de58d8d` | 网关已经在返回 `destination.credential_active`、追加端点、暂停/截止点与 50 个上限，却仍自称 1.1，而 1.1 的资源 schema 禁止多余字段；没有追加端点的网关，1.1 手机把每条文字当成永久拒绝。按 `contracts/README.md` 的“修改规则”升到 **1.2**：每个 schema、`$id`、样本只改 `schema_version`（没有改任何期望），网关通告只有 1.2、1.1 的请求回 `422`，版本记录、spec、PRD 版本表、`OPERATIONS.md` 同步。手机：`ContractGuard` 在联系网关传任何东西（入库、查状态、追加 Delivery、目的地管理）之前先对这份配对问一次 `GET /v1/capabilities`；网关更旧：什么都不发、配对记录标 `GATEWAY_TOO_OLD`、任务回到等待（绝不是永久拒绝）、同步页与目的地页说“请先更新网关”，网关更新之后自己恢复、不用重新配对；旧网关生成的配对二维码在配对前就提示；问不到 / 读不懂 / 通告了无法判断的版本：什么都不发，但不冤枉成“太旧”。**跟进**：两个真网关用例按精确顺序断言请求序列，仍是 R7 之前的顺序，到 R3 之后重跑真网关套件才发现；断言没有放宽——探测显式列出，状态先于 PUT 的顺序照旧检查 | `ContractVerdictTest`、`SyncEngineContractTest`、`DestinationContractTest`（新旧两个版本、两侧）、网关 `tests/integration/test_contract_version.py`；该提交上网关 543 个全过 | 10（`r7-*`；原计划 9 条，真跑后补 1 条，见 14.5） | E2；真旧网关 + 真手机 `NOT_RUN` |
| R8 授权行的时间没人解析（中） | `fd6a06a` | R3 之后授权行是五段，编号与指纹有严格正则，第五段（时间）从没解析——`granted v2 <编号> <指纹> nonsense` 仍被当成授权。现在第五段必须能被 `Instant.parse` 读懂且 `toString()` 逐字往返（拒绝偏移量、小写、多余的零、闰秒、纯数字、结尾换行等一切宽松解析器认下的写法）；读不懂 = 这一行被改坏 = 不是授权（`forgetGrantExcept` 对读不懂的行照旧不动） | 无效时间夹具（18 种时间 + 4 行评审式整行）；反向守卫：`grantTo` 自己写出的 9 种时刻（整秒、毫秒、纳秒、纪元、公元 1 年、9999 年、+10000 年…）读回来仍是授权，严格不能误伤成“授权悄悄失效” | 4 | E2 |
| R9 授权存档读一次失败就永远停在“不许发”（中） | `b370cd2` | `SqliteDataFlowPolicy.storedStates` 只在“设置变了”时重读：一次读撞上暂时故障 → 回答 Closed（对），数据库好了之后没有任何东西再触发读，状态汇总与唤醒规划永远停在“不许发”，用户的授权其实好好存着。新算子 `Flow<*>.recoveringStoredStates(read)`（`:sync` 的 `policy/StoredStatesRecovery.kt`）：读不出来立刻发 Closed（只发一次），按 2 秒起翻倍、30 秒封顶重试（与 `:app` 的 `ContainedReads` 同一节奏），读到了发出真实值；新的变动打断旧的重试，收集者取消重试跟着停；`readOrNull()` 把读失败变成算子看得见的 null（取消照常外抛）；`stored()` 不变（引擎每次发请求前直接问，仍是“读不出来 = Closed”） | `StoredStatesRecoveryTest`（5，虚拟时钟，退避每一步精确断言：2、4、8、16、30、30 秒；故障当下就是 Closed；先读到过真值、之后某次变动读失败、没有新变动也要回到真值；新变动取代旧重试；取消停重试）、`StoredStatesWiringTest`（`:app`，读源码，钉住 SQLite 那一份真的走算子） | 10（3 接线 + 7 算子） | E2；SQLite 实现在设备上 `NOT_RUN` |
| R10 暂停期间被拒收的预览目标，续签后还摆着“确认 / 不发送”（中） | `6beaa12` | 账本（`DispatchTarget.awaitingUser`）早把 REFUSED 算成“没什么可决定”，`ReviewTarget` 却只带 decision + eligible：续签后目的地恢复、`eligible=true`，界面以为在等，两个按钮背后都是 `DecideResult.NotEligible`。现在 `ReviewTarget` 带上 delivery 与 refusal，`awaitingUser` 与账本是同一个判断；`describeDecision` 对没建投递的目标说“没有送给它”并分开说原因（暂停期间错过、恢复后不补发 / 网关没有为它建投递）；用户自己选了不发送的保持原话；`SendReviewScreen` 的按钮只看 `awaitingUser` | `SendReviewViewModelTest`（端到端：暂停 → 续签 → 界面状态；账本与界面对 decision × eligible × delivery × refusal 全部组合一致）、`DestinationTextTest`、`ReviewTargetWiringTest` | 7 | E2；页面显示 `NOT_RUN` |
| R11 详情页刷新收集器遇到 `SyncStatus.watch` 暂时故障就死了（中） | `2038fc8` | `refreshOn.containReadFailures("destination.detail.refresh", log).collect { … }`：与其它读流同一套重试围栏，故障只记事件名，下一次发值就重读 | `ReadFailureContainmentTest` 里的 R11 用例：流先抛一次再恢复，错误不逃出作用域、收集者不死、恢复后下一次发值就重读 | 1 | E2 |
| R12 确认之后进程被杀，没人叫醒（中） | `0b59301` | 确认预览之后账上立刻欠“向网关追加 Delivery”，可系统唤醒要等 `ResumePlanner` 异步反应才排定；提交与排定之间进程被杀，就没有东西再把应用叫起来。新增 `WakeGate`：一把 `Mutex` + 告诉过系统什么；`reconcile`（规划器：在锁里现算、与上次不同才登记/取消）与 `ahead`（用户确认：在同一把锁里**先**幂等地登记叫醒、**再**提交）共用它；预先登记被系统拒绝只记 `resume.prewake-failed <类名>`、照常提交；`WakeAheadDispatchStore` 只在 CONFIRM 之前预先登记（“不发送”不欠追加）；`SyncRuntime` 让规划器与被装饰的发送账共用同一个门 | `ResumeRecoveryTest` 的 7 个新用例（提交那一刻系统里已登记；确认提交之后规划器根本没跑、进程就被杀，用会“持久”的假系统调度器模拟 OS 保存的叫醒；规划器正在算“没活了”的当口用户确认；预先登记被拒用户的确认照样提交；“不发送”不登记；预先登记之后“告诉过系统什么”被忘掉）、`ResumeWiringGuardTest`（`:app`） | 13（`r12-*`） | E2；真 `JobScheduler` `NOT_RUN` |
| R13 意图在被拦的尝试里就冻结了（第一轮 M3 的遗留） | `cb7d0e7` | 引擎在最后一道“允许文字进网关”检查之前就构造提交——构造提交会在发送账事务里冻结意图标签；这道检查没通过（授权刚收回、严格仅本地、刚改配了别的网关）时任务停下等用户，标签却已被锁，用户再也改不了。现在 `SubmissionSource` 有不冻结的 `textSha256For(key)`（默认实现经 `submissionFor`；`RecordLibrarySubmissionSource` 覆盖，只构造 StoreOnly 的提交取哈希、不碰发送账）；引擎的顺序：策略检查 → 合同探测 → 策略 → 围栏内 `useGateway` → `textSha256For`（只看；本机没有这条文字 = `LOCAL_DATA_MISSING` 停下）→（结果不确定：用哈希问网关状态）→ 最后一道 `mayTransmit` → **此时才** `submissionFor`（封存 + 冻结；拿不到同样 `LOCAL_DATA_MISSING`）→ PUT | `DispatchEngineTest` 3 个（最后一道放行没通过：什么都没发、标签仍可改，发出去之后才冻结；结果不确定且从没真正发出去的任务先问网关、不冻结；问网关的路上改配了另一个网关：这一轮作废、标签仍可改）、`SubmissionBuilderTest`（只看哈希不问发送账）、`SyncEngineTest`（看过哈希之后、封存之前本机把文字删了：BLOCKED / `LOCAL_DATA_MISSING`、一个 PUT 都不发）；修复前 4 个红 | 5（`r13-*`），7 条 Ticket 21 条目重新锚定 | E2 |

### 14.2 假设与取舍（做决定时没有人可问；每一条都可以推翻）

1. **R6 与第一轮的假设冲突，以 R6 为准**（规则 7：不把两种做法掺在一起）。13.2 第 3 条、第 6 节第 3 条、第 4 节剪贴板那一行写的是“认不出归属就照清、离开应用时不清”；R6 反过来：**认不出归属绝不清，离开本应用（读得到的最后一刻）清**。代价是产品取舍，需要产品拍板：复制凭证之后切到别的应用再粘贴行不通（只有分屏 / 悬浮窗里能粘，或照屏幕上抄，或再轮换一次）。这是复评条款的直接结果，我没有自作主张放宽。上面三处旧句子已加了“被 R6 取代”的指针，没有改写历史。
2. **R7 升到 1.2 是按合同的“修改规则”做的**，不是我的偏好：响应里多了字段、多了端点，严格的旧客户端读不了。后果：网关不再接受 `schema_version: "1.1"` 的请求，**旧版手机连新网关会把每条文字永久拒绝**，所以升级顺序是“先升手机应用、再升网关（或一起）”。我在写这一节时发现 `services/gateway/OPERATIONS.md` 把顺序写反了（同一节里一句说先升网关、一句说先升手机），已改正（见 14.7）。
3. **R1/R2 的“替换凭证只看得到撤销之后的文字”是有意的保守读法**：截止点取“当前时间”与“库里最晚入库时间”的较大者，宁可多藏、不漏；撤销之前的历史对替换凭证永远不可见（哪怕用户其实想让新凭证读到，要读就用旧凭证之外的途径，比如再录一次）。
4. **R1 的领取这一层过滤是纵深防御**：正常路径里撤销已经把排着的投递取消了，只有旧库里升级前留下的排队投递走得到这里，所以它由升级用例而不是“全路径”用例守着（变异 `r1-gw-claim-ignores-cutoff`，见 14.5）。
5. **R3 的授权跟着网关身份（编号 + 公钥指纹）走**：重新配对到同一个网关（编号与指纹都不变）保留授权；配对到别的网关一律清掉；升级前的 v1 行（全局）读成没授权——同意不能从旧数据里推出来，升级的用户要重新打开一次开关。
6. **R8 的“规范写法”选 `Instant.parse` + `toString()` 往返**：任何别的写法都当没授权（闭着失败），手工改过授权文件的人会失去授权，可以接受。
7. **R9 的退避（2 秒起翻倍、30 秒封顶）与 `ContainedReads` 一致**，没有另起一套节奏。
8. **R12 预先登记失败的处理**：只记事件名与异常类名、吞掉、照常提交用户的确认，叫醒由规划器随后补（退化成原来的“提交之后异步登记”，不比以前差）；不让系统的拒绝挡住用户的决定。
9. **R13 的 `textSha256For` 默认实现经 `submissionFor`**：别的 `SubmissionSource` 实现行为不变；只有 `RecordLibrarySubmissionSource` 覆盖成不冻结的版本。代价是每次尝试多构造一次提交（读一次库）；Revision 不可变，两次取的是同一份字节，中间被删就是 `LOCAL_DATA_MISSING`。测试里“最后一道放行”用“账本的 `useGateway` 之后的第一次策略读取”来定位，而不是数读取次数：`DispatchRig` 里合同守卫与服务共用，读取次数从一开始就不为零。
10. **变异表里我写错过的预期，按真跑的结果改，没有放宽任何测试**：`r10-vm-drops-delivery`（行为测试才是拦它的，组合测试不经 `toReview()`）、`r1-gw-claim-ignores-cutoff`（升级用例拦它）、`r7-guard-remembers-too-old`（它的后果是旧网关被放行，拦它的是“一个请求都不发”那几个；“更新后自己恢复”由新条目 `r7-guard-caches-too-old-verdict` 守）。**一次是真的测试缺口**：`r1-gw-migration-backfills-rotations` 真跑存活——升级用例把“只轮换过”的文字用真实时钟入库，晚于迁移可能误补的任何截止点，所以“轮换不补截止点”测不出来；已让那条文字入库在轮换之前（和真实的故事一致），变异随即被抓住，没改任何断言。
11. **Ticket 21 的变异表本轮改了 10 条**：6 条的锚点被本轮的源码改动弄失效，只换锚点或把目标挪到新文件、破坏的含义不变（`PairingService` 的围栏 R5、`SyncEngine` 取哈希那一行 R13、三条唤醒去重条目的目标从 `ResumePlanner` 挪到 `WakeGate.kt` R12、`SyncViewModel` 的一处）；2 条随合同 1.2 改了说明与预期的测试名文字（`1_1` → `1_2`）；2 条是整表重跑里存活之后改的预期（下一条）。表自检随后全过。
12. **整表重跑抓出了第二处真的测试缺口，是我自己的 R5 造成的**：R5 给引擎里改账的调用都加了围栏，原来守着“旧配对的结果必须作废”的两个测试（“已入库”的回答）因此变成冗余——变异 `engine-accepts-stale-put-result`、`engine-accepts-stale-status-answer` 把对应的 `stillPaired` 检查删掉后，全部测试照样通过。可这两行还在拦围栏不拦的东西（旧配对的永久拒绝、“已删除”、“没有这一版”之后仍拿旧令牌发 PUT），那才是它们现在存在的理由，却没有测试。做法：补 3 个测试而不是删检查（删掉等于放开了真实的行为），表里这两条的预期改成点名新测试（旧预期点名的测试在变异下已不再红，留着会让条目变成 SURVIVED）。这类缺口只有整表重跑才看得到：每条修复自己的新变异都过了，但它改变了旁边旧条目的覆盖（第 10 条那个缺口不同，是新条目第一次真跑就暴露的）。**教训**：给一类调用加围栏、门或兜底之后，要把依赖原来那条路径的全部旧变异条目重新真跑，而不只是新条目与点名重新锚定的几条；我在 R5 时只重跑了 13 条，其余是靠最后的整表重跑才补上。
13. **没有 Codex 评审、没有第二轮独立评审**：派发说明禁止子 agent 与辅助 worker，评审只靠红→绿测试与变异检查。mcp-feedback-enhanced 工具在本环境没有加载，没有调用；AOCI 在本仓库未启用（没有 `aoci*.txt`，不擅自启用）；GitNexus 索引只覆盖另一个检出（`/Users/ethan/Desktop/CODE/auri`，过期、没有 Ticket 22 的代码），所以调查靠 `rg` 与读源码。

### 14.3 残余风险（如实）

- **R6 的平台限制**：应用在后台时系统不让读剪贴板（API 29+），此时凭证若还在剪贴板里，应用既无法确认归属、也不该盲清——它会留着，直到被覆盖或系统自己清（Android 13 起约一小时，真机 `NOT_RUN`）。离开应用的那一刻（`onPause`）窗口是否仍有焦点、`peek()` 是否读得到，也只有真机能证明。
- **最后一道策略检查到 PUT 之间的窗口**（R4、R13）：中间只剩本地计算，但仍是抢占式线程上的一个窗口；R13 之后封存（发送账事务）也落在这个窗口里。窗口内的撤销最坏是一个已经在路上的请求，与第一轮记录的同类，没有扩大。
- **R5 的围栏是进程内的锁**：配对身份在配对文件里、账在 SQLite 里，两边没有共同的事务；单进程部署下够用，多个进程同时写账不在 V1 范围。
- **R12 只让“确认”先登记叫醒**：发布 Revision 之后、规划器反应之前进程被杀有同样形状的窗口（提交 → 规划器 → 系统），本轮没有对它动手；窗口之后用户再打开应用，启动时的评估读磁盘上的账会补上。预先登记成功而提交没成时系统会白叫醒一次（醒来发现没活就收走，无害）。真实的 `JobScheduler` 在应用被杀后是否叫醒，`NOT_RUN`。
- **R9 的 SQLite 实现没在设备上跑**：JVM 里没有 Android 的 `SQLiteDatabase`，故障注入用的是脚本化的读。
- **R10、R11 的页面显示**只有设备能确认；R3/R4/R5/R8/R13 是引擎与存储层的逻辑，没有设备相关的部分。
- **旧版手机连新网关**（R7）：见 14.2 第 2 条，升级顺序写在 `OPERATIONS.md`；真旧网关 + 真手机的联调 `NOT_RUN`。

### 14.4 要在设备上核对的（`NOT_RUN`，原因：为省内存禁用模拟器，没有连着的设备）

前提与变量同第 4 节与 13.3（调试版已装、电脑上网关在跑并已配对、`AURI_GATEWAY_URL` / `AURI_GATEWAY_SPKI_SHA256` / `AURI_DESTINATION_CREDENTIAL` 三个环境变量）。

| 项 | 做什么 | 期望 |
|---|---|---|
| R1 / R2 / R10 替换凭证与预览目标 | 建一个“预览确认”的目的地 D 并签发凭证；录一句 X（“待发送与标签”里标着等你确认，先不点）；电脑上 `sqlite3 /tmp/auri-gateway-data/gateway.db "select credential_id from destination_credentials where destination_id='<D>' and revoked_at is null"` 取编号，再 `PYTHONPATH=services/gateway .venv/bin/python -m auri_gateway revoke-credential --data-dir /tmp/auri-gateway-data --credential-id <编号>`；手机“刷新”（D 显示已暂停）→“轮换凭证”（核验）；用**新凭证**设 `AURI_DESTINATION_CREDENTIAL` 后 `.venv/bin/python execution/acceptance/android-destinations/real_gateway_agent.py claim`；最后在手机上对 X 点“确认发送” | `claim` 返回空的 `deliveries`；点“确认发送”之后 `sqlite3 … "select count(*) from deliveries where destination_id='<D>'"` 没有增加（X 没有 Delivery），`claim` 仍然空；手机上 X 对 D 显示“没有送给它”，且不再有“确认 / 不发送”两个按钮（R10）；再录一句 Y，新凭证的 `claim` 只领到 Y |
| R3 授权跟着网关 | 配对网关 A 并打开“允许把文字发送到我配对的网关”，录一句（A 的 `select count(*) from revisions` 为 1）；另起网关 B：`PYTHONPATH=services/gateway .venv/bin/python -m auri_gateway serve --data-dir /tmp/auri-gateway-b --port 8444`，`… pair --data-dir /tmp/auri-gateway-b`，手机重新配对到 B；再录一句 | 重新配对后“允许把文字发送到我配对的网关”是关的（未配对时灰着）；B 的 `select count(*) from revisions` 仍是 0，同步页说文字还没有发出去；打开开关之后才变成 1 |
| R6 剪贴板 | 生成凭证 →“复制并关闭”，看 Toast；① 立刻切到别的应用粘贴；② 回到本应用详情页再复制，按 Home 后回来再粘贴；③ 复制后留在本应用前台待到 60 秒；④ 复制后在别的应用里复制一句别的话，再回本应用；⑤ 下拉通知栏 / Android 13+ 的剪贴板预览 | ① 粘不出凭证（离开本应用时已清；`adb logcat -d -s AuriSync:I \| grep clipboard` 有 `clipboard.copied`、`clipboard.cleared`）；② 同样是空的；③ 60 秒后清掉；④ 别的话还在（`clipboard.kept-other`），读不到时只有 `clipboard.unverifiable` 而没有清；⑤ 预览里内容被遮住（`EXTRA_IS_SENSITIVE`） |
| R7 旧网关 | 在另一个目录检出上一版网关：`git worktree add /tmp/auri-old-gateway origin/integration/auri-v2`，用本仓库的解释器起它：`PYTHONPATH=/tmp/auri-old-gateway/services/gateway .venv/bin/python -m auri_gateway serve --data-dir /tmp/auri-old-gw-data --port 8445`，`… pair --data-dir /tmp/auri-old-gw-data`，扫它的二维码；再录一句；之后停掉它、起回当前网关（原数据目录）等下一轮同步；收尾 `git worktree remove /tmp/auri-old-gateway` | 扫码就提示“网关版本太旧，请先更新网关”（载荷里 `schema_version` 是 1.1）；若已配对：同步页与目的地页都说“请先更新网关”，旧网关上 `revisions` 为 0，文字在手机上排着（不是“已拒收”）；换成当前网关后下一轮同步自己清掉提示、文字送到，不需要重新配对 |
| R9 读失败自己恢复（SQLite 实现） | 无法在真机上注入读失败；只能确认正常路径：设备端授权存档合同 `cd apps/android && ./gradlew :syncplatform:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=app.auri.syncplatform.policy` | 通过即说明 SQLite 适配器的读写与 JVM 一致；“读失败 → 重试 → 恢复”本身只有 JVM 的虚拟时钟证据 |
| R12 确认之后进程被杀 | 关掉耳机控制模式（前台服务会让进程不“可杀”）；预览目的地上有一条等确认的文字，且文字已入库；点“确认发送”后立刻按 Home，再 `adb shell am kill app.auri.android`（注意**不要用 `force-stop`**：它会连应用登记过的系统任务一起取消，等于没测）；`adb shell dumpsys jobscheduler \| grep -B2 -A6 app.auri.android` | 有已登记的任务；系统把应用叫起后，`real_gateway_agent.py claim`（预览目的地的凭证）领到刚确认的那一条。13.3 的 H1 一行原先也写成了 `force-stop`，已一并改正 |
| R13 被拦的尝试标签仍可改 | 在“同步与配对”页关掉“允许把文字发送到我配对的网关”，录一句；在“待发送与标签”里把它标成“请求执行”；再打开开关 | 关着开关时标签可以改；打开后送到网关的 `intent` 是 `action_request`（`claim` 或 `select intent from revisions` 可见） |
| R4 / R5 / R8 / R11 | 纯逻辑，没有设备相关的部分；R11 的页面刷新只能在真机上看，且需要往 `SyncStatus.watch` 里注入故障，没有现成手段 | JVM 测试为准 |

设备上的自动化测试（`NOT_RUN`）同第 4 节末尾：`AURI_ALSO_ASR_ACCEPTANCE=1 execution/acceptance/android-sync/run_emulator_acceptance.sh`（一次性无头模拟器，先取共享锁），或已连设备时 `cd apps/android && ./gradlew :syncplatform:connectedDebugAndroidTest`。

### 14.5 变异检查

- **表的规模**：第一轮结束时 132 条；本轮新增 90 条（R1 9、R2 3、R3 6、R4 4、R5 10、R6 8、R7 10、R8 4、R9 10、R10 7、R11 1、R12 13、R13 5），退役 1 条（`b5-unreadable-keeps-credential`，R6 把它守的规则反过来了），共 **221 条**；其中 12 条（R1、R2）是**网关一侧**的，由新的 pytest 运行器真跑。
- **网关一侧的运行器**：Kotlin 的变异机器只会跑 Gradle，R1/R2 是 Agent 经 MCP 才看得到的网关行为。`destination_mutation_check.py` 现在有第二种条目（`gradle` 字段以 `"pytest"` 开头）：在私有快照里应用编辑、拒绝不是合法 Python 的变异（记 `INVALID`）、用仓库的解释器对变异后的网关跑 `tests/integration/test_revocation_cutoff.py`、读 JUnit XML、还原。表的离线自检同样覆盖这种条目（pytest 文件与测试名存在、变异后的 `.py` 能编译、变异后的迁移 SQL 能在真实的 0001–0004 之上执行）。
- **新增条目的真跑（每批的结果）**：R13 5 条 CAUGHT；R1/R2 12 条与 R7 10 条第一次真跑时有 3 条存活，都不是削弱测试能解释的：`r1-gw-claim-ignores-cutoff` 与 `r7-guard-remembers-too-old` 是我对“哪个测试会红”预判错了（实际被别的测试抓住，已按真跑改预期；后者还补了一条新条目守“更新后自己恢复”），`r1-gw-migration-backfills-rotations` 是真的测试缺口（14.2 第 10 条），修好测试后 CAUGHT。三条重跑后全部 CAUGHT。
- **整表重跑（Ticket 22，221 条）**：在 R1–R13 与变异表提交之后（代码 = `c93f3ba`；之后到 `611958a` 之间产品代码一个字节没动，只加了 Ticket 21 的三个测试，见下条）分 15 批真跑：每批 `destination_mutation_check.py --only <至多 15 个编号>`，每批各自过内存关卡并取共享锁、批与批之间放锁；11-21 16:25:23–18:47:22，共 2 小时 22 分。结果：**221 条全部 CAUGHT，SURVIVED 0，INVALID 0**（其中 12 条网关一侧的由 pytest 运行器真跑，其余走 Gradle）。各批开始与结束时的读数：空闲 39%–59%，交换区 3113 → 4922 MB（读数最高 4999 MB；这段时间本机交换区一直在涨，来源没有追查——每批结束我的进程就退出，没有常驻的 Gradle 守护进程），每批开始前的关卡一次也没有触发等待；每批前后 `auri_gateway` 残留进程都是 0。
- **整表重跑（Ticket 21，253 条）**：`android-sync/mutation_check.py --only <至多 15 个编号>`，分 17 批，11-21 19:20–21:35，共 2 小时 15 分，方法同上（每批各自过关卡与取锁）。结果：**251 条一次 CAUGHT，2 条 SURVIVED，0 条 INVALID**。这 2 条是**真的测试缺口**，不是预期写错：`engine-accepts-stale-put-result`、`engine-accepts-stale-status-answer`（都是引擎里“网络回答回来之后，配对还是原来那份吗？”的检查，F6）。R5 的围栏让原来拦它们的两个测试（“已入库”的结果）变得多余——旧网关的“已入库”现在在记账那一步被围栏挡下——但这两行 `stillPaired` 仍在做围栏不做的事：**旧配对得到的非成功结论**（永久拒绝 `409 RECORD_VERSION_CONFLICT`、状态回答“已删除”）到得太晚时，不能把新配对的任务停在“需要用户处理”；状态回答“没有这一版”到得太晚时，不能让引擎拿旧令牌再发一个 PUT。没有测试守着这些。修复提交 `611958a` 补了 3 个 `SyncEngineTest`（红对着变异、绿对着真代码），两条的预期改成点名这些新测试（旧预期点名的测试在变异下已不会红）；重跑这两条：CAUGHT（1/51 与 2/51 个测试变红，恰好是新测试）。**所以 253 条最终全部 CAUGHT。** 各批开始与结束时的读数：空闲 46%–58%，交换区 4834 → 3431 MB（先落后稳），每批前后 `auri_gateway` 残留进程 0，没有等待。
- **表的离线自检**：`test_destination_mutation_table.py` 686 个（`--collect-only` 数得），`android-sync/test_mutation_table.py` 767 个（在 `611958a` 的内容上、提交之前单独跑过：767 passed in 31.57s）；两者都在 14.6 的整个验收套件里再跑一遍（`android-destinations` 737 个、`android-sync` 814 个，全过）。

### 14.6 最终验证（最新结果）

方法与 13.5 相同：本机 macOS，每个 Gradle / pytest 都**单独**取共享锁 `~/.cache/auri/gradle.lock`（会调 Gradle 的验收 pytest 也算一个 Gradle 进程，整次持锁；不调 Gradle 的 `repo` 套件不取锁）、一次只跑一个、跑完立刻放锁；Gradle 参数 `--no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2`，测试任务加 `--rerun`（表里的数字是该次运行写出的 JUnit XML 的合计，不是缓存）；每次运行前后各读 `memory_pressure` 的空闲百分比与 `sysctl vm.swapusage`（空闲低于 25% 或 60 秒内交换区涨超 500 MB 就等；整轮 139 次运行里**一次也没有触发等待**）；pytest 带 `NO_PROXY=localhost,127.0.0.1,::1 no_proxy=localhost,127.0.0.1,::1`，前后查 `auri_gateway` 进程，每一次残留都是 0；没有执行过 `./gradlew --stop`；**没有**启动模拟器、AVD、Android Studio、Xcode、DevEco 或任何仿真器，没有运行仪器测试（只编译打包）。先 `git fetch origin` 并核对 `origin/integration/auri-v2`（`10be78c`）：它是本分支的祖先，落后 0 个提交，没有东西可合并（交付前再核对一次，仍是 0）。

**一轮跑完**：全部套件在 `611958a`（最后一个改了代码的提交）上依次跑完，没有红的（`android-interruptions` 的 exit 5 是它一直的状态，见下）。之后的提交只改文档（本 README 与 `CLAUDE.md`），产品代码与测试一个字节没动。本轮代码范围 `7b9cf5c..611958a`（`7b9cf5c` 是第一轮的最后一个提交），18 个提交，244 个文件、+4825/−495。

#### Gradle（`apps/android`）

| 套件 | 命令 | 结果 | 起止（本机时间） | 内存空闲（前 → 后）与交换区已用 |
|---|---|---|---|---|
| `assemble-debug` | `./gradlew :app:assembleDebug` | exit 0：构建成功 | 21:39:06–21:39:18 | 56% → 52%；交换区 3431 → 3431 MB |
| `capture` | `./gradlew :capture:test --rerun` | exit 0：369 个，失败 0、出错 0、跳过 0 | 21:39:18–21:39:28 | 52% → 53%；交换区 3431 → 3431 MB |
| `modelmgmt` | `./gradlew :modelmgmt:testDebugUnitTest --rerun` | exit 0：84 个，失败 0、出错 0、跳过 0 | 21:39:28–21:40:14 | 53% → 52%；交换区 3431 → 3431 MB |
| `platform` | `./gradlew :platform:testDebugUnitTest --rerun` | exit 0：257 个，失败 0、出错 0、跳过 0 | 21:40:14–21:40:30 | 52% → 51%；交换区 3431 → 3431 MB |
| `app` | `./gradlew :app:testDebugUnitTest --rerun` | exit 0：320 个，失败 0、出错 0、跳过 0 | 21:40:30–21:40:42 | 51% → 50%；交换区 3431 → 3431 MB |
| `asr` | `./gradlew :asr:testDebugUnitTest --rerun` | exit 0：112 个，失败 0、出错 0、跳过 0 | 21:40:42–21:40:49 | 50% → 52%；交换区 3431 → 3431 MB |
| `sync` | `./gradlew :sync:test --rerun` | exit 0：511 个，失败 0、出错 0、跳过 0 | 21:40:49–21:41:04 | 52% → 52%；交换区 3431 → 3431 MB |
| `syncplatform` | `./gradlew :syncplatform:testDebugUnitTest --rerun` | exit 0：111 个，失败 0、出错 0、跳过 0 | 21:41:04–21:41:12 | 52% → 53%；交换区 3431 → 3431 MB |
| `realgateway` | `./gradlew :sync:realGatewayTest --rerun` | exit 0：25 个，失败 0、出错 0、跳过 0 | 21:41:12–21:41:59 | 53% → 51%；交换区 3431 → 3431 MB |
| `androidtest-apks` | `./gradlew :app:assembleDebugAndroidTest :syncplatform:assembleDebugAndroidTest :platform:assembleDebugAndroidTest` | exit 0：构建成功（只编译，不运行） | 21:41:59–21:42:21 | 52% → 49%；交换区 3431 → 3431 MB |

#### Python 验收与仓库套件（每个都是单独一次 pytest）

| 套件 | 命令 | 结果 | 起止（本机时间） | 内存空闲（前 → 后）与交换区已用 |
|---|---|---|---|---|
| `android-capture` | `pytest execution/acceptance/android-capture` | exit 0：59 passed in 54.28s | 21:42:21–21:43:15 | 49% → 55%；交换区 3431 → 3431 MB；残留网关进程 0 |
| `android-control` | `pytest execution/acceptance/android-control` | exit 0：106 passed in 56.70s | 21:43:15–21:44:12 | 55% → 55%；交换区 3431 → 3431 MB；残留网关进程 0 |
| `android-silence` | `pytest execution/acceptance/android-silence` | exit 0：3 passed in 0.04s | 21:44:12–21:44:13 | 55% → 55%；交换区 3431 → 3431 MB；残留网关进程 0 |
| `android-models` | `pytest execution/acceptance/android-models` | exit 0：2 passed in 0.04s | 21:44:13–21:44:13 | 55% → 55%；交换区 3431 → 3431 MB；残留网关进程 0 |
| `android-sync` | `pytest execution/acceptance/android-sync` | exit 0：814 passed in 184.13s (0:03:04) | 21:44:13–21:47:17 | 55% → 56%；交换区 3431 → 3431 MB；残留网关进程 0 |
| `android-destinations` | `pytest execution/acceptance/android-destinations` | exit 0：737 passed in 34.83s | 21:47:17–21:47:52 | 56% → 55%；交换区 3431 → 3431 MB；残留网关进程 0 |
| `android-interruptions` | `pytest execution/acceptance/android-interruptions` | **exit 5：no tests ran in 0.00s** | 21:47:52–21:47:53 | 55% → 55%；交换区 3431 → 3431 MB；残留网关进程 0 |
| `asr-android` | `pytest execution/acceptance/asr-android` | exit 0：131 passed in 51.92s | 21:47:53–21:48:45 | 55% → 53%；交换区 3431 → 3431 MB；残留网关进程 0 |
| `repo` | `pytest tests/contract tests/integration tests/security` | exit 0：543 passed in 258.68s (0:04:18) | 21:48:45–21:53:04 | 53% → 56%；交换区 3431 → 3423 MB；残留网关进程 0 |

- `android-interruptions` 是 exit 5：该目录只有 README、没有 Python 测试（Ticket 19 的区域，没动），pytest 一个测试也没收集到；这是它一直的样子，不是跳过。`android-silence`（3 个）、`android-models`（2 个）是纯静态检查，不调用 Gradle，所以只要零点几秒。
- 测得的数字（E2，本机 macOS 上量的，没有设备）：网关与合同套件 543 个 / 4 分 18 秒（第一轮是 529 个 / 3 分 50 秒；多出的 14 个是 `test_revocation_cutoff.py` 的 11 个与 `test_contract_version.py` 的 3 个，租约到期测试仍要真实等待）；真网关 25 个（`:sync:realGatewayTest`）；`:sync:test` 511 个（第一轮 440）、`:syncplatform` 111（105）、`:app` 320（297）、`:capture` 369、`:platform` 257、`:modelmgmt` 84、`:asr` 112（这四个与第一轮相同）。没有任何性能或设备数字，因为没有设备。
- 变异表（14.5）：Ticket 22 的 221 条一次全部 CAUGHT；Ticket 21 的 253 条里 2 条存活、补测试后 CAUGHT，最终 253 条全部 CAUGHT。
- 没有做、也没法做的：所有设备或模拟器才能证明的（14.4 列了命令与期望），状态都是 `NOT_RUN`，原因：为省内存禁用模拟器、没有连着的设备。

### 14.7 对共享文件的改动

| 文件 | 改动 | 为什么 |
|---|---|---|
| `contracts/schemas/*.json`、`contracts/fixtures/**`、`tests/contract/conftest.py` | R7：`schema_version` 与 `$id` 1.1 → 1.2（11 个 schema、110 个样本文件，各只改 `schema_version` 与 `$id`） | 合同升到 1.2；**没有改任何样本的期望、没有删任何无效样本** |
| `apps/android/{asr,capture,platform}` 里写死的合同版本（`AsrContract.kt`、`HeadsetProfile.kt`、`AdapterTypes.kt`、`Enums.kt` 的 `SCHEMA_VERSION` 与注释）及读它们的测试，`execution/acceptance/{asr-android,android-capture,android-control}` 的 Python 校验工具（`BASE_URI`、说明文字） | R7：`"1.1"` → `"1.2"`，只改版本字符串与注释，没有动行为 | 这些模块（Ticket 14/16/17）把合同版本写死在代码里；合同升版不改，就会写出 1.1 的 JSON、被 1.2 的 schema 拒收 |
| `contracts/README.md`、`docs/spec/auri-v1-spec.md`、`VoiceBridge_PRD_Agent_Ready_v1.0.md` | R1/R2/R7：撤销截止点规则、1.2 版本记录、PRD 版本表 | 按 `contracts/README.md` 的“修改规则” |
| `services/gateway/auri_gateway/store.py`、`deliveries.py`、`queries.py`、`migrations/0005_destination_revocation_cutoff.sql`、`app.py`、`cli.py`、`contracts.py`、`error_codes.py`、`mcp_endpoint.py`、`mdns.py` | R1/R2：截止点的记录、读取路径与追加路径的过滤、迁移；R7：版本常量 | 网关一侧的修复 |
| `services/gateway/OPERATIONS.md` | R1/R2/R7 各一段；**本节新增一处改正**：“合同版本与升级顺序”里原写“先升级网关，再升级手机应用”，与同一节最后一条（旧版手机连新网关会被 `422` 永久拒绝）自相矛盾，改成“先升级手机应用，再升级网关（或两者一起做）” | 避免用户按错误的顺序升级而丢文字 |
| `apps/android/sync/src/test/.../engine/SyncEngineTest.kt`（Ticket 21 的引擎测试） | 净增 +89/−3：加 `repairToB()` 装配函数（R3）、R13 的 1 个测试（看过哈希之后、封存之前本机把文字删了）、R5 跟进的 3 个测试（14.2 第 12 条）；删的 3 行是 3 处 `rig.pairing.save(pairedToB())`，换成 `rig.repairToB()`（R3：授权是给某个网关的，改配到 B 也要对 B 另行同意，否则新配对读成没同意）。**没有改任何断言** | 本票的引擎行为变了，同一个测试文件里的旧场景要跟着装配 |
| `tests/integration/test_revocation_cutoff.py`（新）、`test_contract_version.py`（新）、`harness.py` 等 | R1/R2/R7 的网关测试与常量；`test_revocation_cutoff.py` 的升级用例在变异检查之后加强了一处（14.2 第 10 条） | 红→绿测试 |
| `execution/acceptance/android-sync/mutation_check.py`（Ticket 21 的变异表） | 10 条：6 条重新锚定（含把去重逻辑的目标挪到 `WakeGate.kt`）、2 条随合同 1.2 改文字、2 条（`engine-accepts-stale-put-result`、`engine-accepts-stale-status-answer`）改预期的测试名 | 前 8 条：本轮改了它们的目标源码或合同版本，只换锚点与文字，破坏的含义不变；后 2 条：整表重跑时存活，补了 3 个测试，预期改成点名新测试（14.2 第 12 条） |
| `execution/acceptance/android-destinations/destination_mutation_check.py`、`test_destination_mutation_table.py` | 90 条新条目、pytest 运行器及其离线自检 | 14.5 |
| `CLAUDE.md` | 只改“命令”一节里 Ticket 22 那一段的说明与数字 | 数字改成 14.6 的最新值 |

### 14.8 收尾：运行环境读数、临时文件与过程说明

- **内存读数**：整个第二轮（11-21 09:54–21:53）每次 Gradle / pytest 运行前后都读了空闲内存百分比与交换区，共 139 次运行、278 次读数（读数日志在仓库外，已随收尾删除）：空闲 32%–59%（最低的 32% 出现在 R7 的网关红阶段 10:15，高于 25% 的等待线），交换区 2431–4999 MB；**没有一次触发“等内存”**；同一时刻只有一个 Gradle 或 pytest 进程；所有 pytest 前后的 `auri_gateway` 残留进程都是 0；没有执行 `./gradlew --stop`，没有启动任何模拟器或 IDE。139 次运行里 39 次退出码非 0：先写会红的测试的红阶段、开发中途当场修掉的失败、带存活变异的批次（Ticket 21 表第 3 批的 2 条，14.5）；最终验证的 19 次里只有 `android-interruptions` 的 exit 5（它没有测试，一直如此）。
- **一次过程失误**：启动 Ticket 21 整表时，我把驱动脚本放进“子 shell + nohup”启动，工具会话一结束就把它连同进程组杀了，只留下**我自己的一把共享锁**（`~/.cache/auri/gradle.lock`，所有者是我的分支）和一个空日志，没有任何变异被应用、工作树没动。我没有当场确认它真在跑，白等了约半小时才发现；确认没有任何 Gradle 进程之后清掉这把失效的锁，改用受监控的后台任务重新启动，之后每次启动长任务都先确认它真的在跑。**这把失效的锁大约占了 33 分钟**（18:47–19:20），期间别的工作树若要跑 Gradle 会被它挡住；我没有看到别人排队的迹象，但没有办法确认。
- **临时文件**：本轮建过 `/tmp/auri-t22r2`（109 项、1.6 MB：脚本、运行日志、读数日志、各批变异日志）、仓库外的两个变异快照 `$TMPDIR/auri-ticket21-mutation-snapshot`（125 MB）与 `auri-ticket22-mutation-snapshot`（127 MB）、`$TMPDIR` 里落在我运行窗口内（11-21 09:50 之后）的测试副产物 323 个目录（`auri-tls-fixtures*` 309、`auri-cfg*` 14）与 `pytest-of-ethan`，以及工作树里 `.venv` 之外的 14 处 `.pytest_cache` / `__pycache__`；收尾时**全部删除**（交付报告里有删除前后的核对）。**保留**：41 个更早的 `$TMPDIR/auri-tls-fixtures*`（27）与 `auri-cfg*`（14），目录修改时间在 11-20 22:27 至 11-21 00:09，早于本轮第一次运行（09:54），不是这一轮产生的，没有动（第一轮 13.8 记的保留项就是这一批）；git 忽略的 `apps/android/**/build`、`.gradle` 与 `.venv`（增量构建与测试要用）；未跟踪的 `.serena/`（开工前就在，不是我建的，没有提交）。
- **和使用者规则对不上的地方**（没有绕过，也没有假装做了）：没有 Codex 评审、没有调用 superpowers 技能——派发说明要求单一 agent、禁止子 agent 与辅助 worker，TDD 按“先写会红的测试、看它真的红、再修、再看它绿”手工执行，评审只靠红→绿测试与变异检查（14.2 第 13 条）；“每任务 4000、每会话 30000 token”的预算对这份工作量不可能满足，靠检查点摘要续作；mcp-feedback-enhanced 工具在本环境没有加载，没有调用；AOCI 在本仓库未启用（没有 `aoci*.txt`，不擅自启用，也没有写任何 MCP 配置）；GitNexus 索引只覆盖另一个检出（`/Users/ethan/Desktop/CODE/auri`，过期、没有 Ticket 22 的代码），调查与改动影响核对靠 `rg`、读源码与真跑的测试。

## 15. 复评修复第三轮（S1–S12）

第二轮（第 14 节）之后又来了第三轮再评：S1–S11 共 11 条。上一个 worker（codex）先做了 S1–S11、两张变异表的重新锚定和授权交接的收口（`bd93376`），随后因它所用供应商的内容过滤中止；本轮由我接手，没有重做已提交的部分，只做三件事：**补完**（它留下的一个未提交的红测试，查明是真缺陷，即 S12）、**批判地复查**（交接补丁的锁、测试会不会空洞通过、变异表里“等价变异”的说法是否站得住）、**在最终代码上整表重跑两张变异表并跑完全部验证**。做法同前：每条修复一个提交、先写会红的测试、提交完立刻推送（`git push origin ticket/22-destinations-and-sending`），提交信息以 `Ticket 22 round 3 S<编号>:` 开头；之后是几个变异表与续作提交（15.1 末）。证据等级同 14：JVM 单测、真实 Python 网关进程的测试（`:sync:realGatewayTest`、`tests/integration`）、Python 校验工具都是 E2；没有设备或模拟器（派发禁止启动），凡是只有设备才能证明的一律 `NOT_RUN`，步骤在 15.4。

### 15.1 发现与修复

| 编号 | 提交 | 做了什么 | 先红后绿的测试（主要的） | 变异 | 证据 |
|---|---|---|---|---|---|
| S1 网关升级时撤销截止点的回填信墙钟的先后 | `445441a` | 迁移 `0005` 原来用替换凭证的签发时间当截止点；网关时钟回拨之后它可以早于撤销之前文字的入库时间，甚至早于被它替换的那条凭证的撤销时间（已结束的暂停根本认不出来），替换凭证就读得到撤销之前的文字。现在“已结束的暂停”用旧的墙钟特征**或**不依赖时钟的特征认（不是该 Destination 的第一条凭证，且没有哪条凭证在签发的同一时刻被撤销：轮换才是同一时刻）；截止点取该 Destination 全部凭证的签发/撤销时间与其 Tenant 下每个 Revision 入库时间中最晚的；只轮换过的仍不设。**有意翻转了一条旧升级用例的预期**：撤销之后、升级之前入库的文字，与“墙钟跑快、暂停之前入库的文字”无法区分，所以升级前入库的一律藏起来，升级之后入库的可见 | `tests/integration/test_revocation_cutoff.py`：3 个升级用例对着旧迁移红（时钟跑快；时钟拨回到替换之前；原升级用例翻转后的预期）+ 1 个守卫（回拨之后的轮换不能被当成暂停） | 3（`s1-gw-*`，网关一侧，pytest 运行器） | E2 |
| S2 网关结果的判断与落账不在同一把锁里 | `2b2ac84` | 引擎里每个由请求结果驱动的任务转移（已入库、被拒绝、公钥不符、令牌被拒、等网关、重试）、发送账改动和冻结意图标签的 `submissionFor`，改成 `pairing.whileCurrent` 内的单一原子步骤（`settle`）：不是发请求时的那份配对就整个丢弃，任务回到排队、对新配对重新来过（仍记“结果不确定”）。以前是先 `stillPaired` 看一眼再写，刚看完用户改配，A 网关的结果就会记到 B 的任务上 | `ResultFenceAtomicityTest`（9 个，写钩子在每个写入点确定性地插入改配；8 个在修复前红）、`PairingFenceWiringTest`（结果驱动的转移只许出现在 `settle` 里、`submissionFor` 只在围栏里） | 2 | E2 |
| S3 放行与请求交出去之间有缝 | `4a87b45`，收口 `bd93376`，续作 `ce9671c` | 询问放行（读存档）到请求交给 HTTP 客户端之间还隔着构造提交（冻结意图）、换账本、记地址，授权随时可能在这中间被收回。授权设置有世代（`PolicyGeneration`：改设置前与落定后各加一）；引擎每次问放行得到一张 `Permit`（记着问之前的世代），定位、合同版本、状态查询、PUT、追加的每个请求交出去的前一刻再比一次，变过就不发、退回等待；放行拦下的第一次尝试放开冻结的标签（`unfreezeUnsent`；上一次可能已送出的不放开）。`bd93376`：比较与“交给客户端”收进同一把进程级锁（`PolicyGeneration.handoff`，与 `changing()` / `changed()` 共用），原来比较之后、交出之前还有一道缝。**续作 `ce9671c`（1b/1c）**：复查这把锁里到底跑什么——读真实的 `PinnedHttpsClient`（DNS 在独立线程池，连接与握手在 IO 线程，第一个挂起点是 `work.await()`）、`ContractGuard`（自己的锁、只读内存）、SQLite 写授权设置、mDNS 查询：锁内没有阻塞工作，也没有第二把锁（无锁序颠倒）；取消与异常原样外抛，异常之后锁是空的。**没有发现产品缺陷，所以没有改产品代码**，只加测试与变异 | `PolicyGateRaceTest`（5 个确定的交错，修复前全红；续作加 1 个：换账本之后授权被收回，请求之前的最后一问就拦下它，提交一次也没构造、标签从头到尾没冻结过）、`PolicyGenerationTest`、`DispatchStoreContract` 的 `unfreezeUnsent`（内存与 SQLite 两份）、`PolicyGateWiringTest`、`PolicyHandoffRaceTest`（撤销落在“比较之后、交出之前”）、续作 `PolicyHandoffRealClientTest`（6 个，用真实客户端：停住的解析器、握手永不回答的服务器——策略变更不被拖住；取消穿过交接、请求或检查抛的异常原样外抛且锁随后可用）；续作还让 `revokedBetweenGenerationReadAndClientHandoffMustNotSendText` 不再空洞通过（15.2 第 5 条） | 17（9 条原有 + 续作 8 条） | E2 |
| S4 Destination 缓存与排着的目标只按网关编号认 | `a4bea23` | 同一个网关编号、另一把公钥是另一台网关：旧缓存与按它规划的、网关上还没有 Delivery 的目标都要作废，否则之后的文字带着 A 的 Destination 编号入库，新网关以未知 Destination 整个拒收，任务被永久拒绝。`GatewayIdentity.ledgerKey` = 编号 + 公钥指纹，`DeliveryAdditions.useGateway` / `DispatchStore.applyDestinations` 改收 `GatewayIdentity`，发送账按它对账（旧格式只有编号，升级后第一次对账整个作废一次，保守） | `SameIdOtherKeyTest`（2，引擎层：永久 BLOCKED / 旧目标不撤回）+ 契约 3 个（内存与真 SQLite 共用） | 3 | E2 |
| S5 清理失败时旧同意在配回原网关时复活 | `6777eb4` | 换配对时的授权清理失败被吞掉（令牌只出现一次，不能拦配对）；A→B→A 之后 A 的旧授权记录还在，配回 A 时“授权给的就是它”又成立，同意在用户没有动作的情况下复活。`GatewayIdentity` 多一项 `pairingEpoch`（这一段配对的世代，默认 legacy），随配对记录一次原子写入落盘；`PairingService.remember`：现在配对着的恰好是同一台网关（编号与公钥都同）才沿用世代，其余（换网关、没配对、取消过、记录读不出来）一律新世代——拿不准就当换了；授权行升到 v3：`granted v3 <编号> <指纹> <世代> <时间>`，v2 读成没授权 | 配到 B 再配回 A（两次清理都失败）与取消配对后重配，旧同意不复活；同一台网关重新配对仍保留同意（回归守卫）：`DataFlowPolicySqlTest`、`FilePairingStoreTest`、`PairingServiceTest` | 6 | E2 |
| S6 兼容结论永久有效 | `238f7d1` | `ContractGuard.invalidate()`（世代保证作废之前发出的询问不会把记忆写回去）；同步引擎里定位连不上、请求传输失败、`networkLost` / `networkChanged` 都作废；“请求不合规”的拒绝在判成永久之前先重新问一次版本（太旧 = 可重试并打“网关太旧”标记，没问成 = 只是等，网关刚确认还说 1.2 才是永久拒绝），追加 Delivery 同样；目的地管理页共用同一个 `ContractGuard`。降级后的网关让文字等待，而不是永久拒绝 | `ContractRevalidationTest`（9：引擎 6 + 在途询问 1 + 管理页 2）；`PolicyGateWiringTest` 的计数随新增的版本重问 10 → 11 | 8 | E2 |
| S7 “网关太旧”取消了系统叫醒 | `ca3a02b` | `needsResume`：`GATEWAY_TOO_OLD` 与“没有停下”一样算要叫醒（用户不用重新配对，只要更新网关）；其余需要用户处理的状态（公钥不符、令牌被拒）仍然取消。重新探测的频率有界：叫醒是 `JobScheduler` 的持久任务，每次被叫醒后还有活就请系统稍后再叫（初始 30 秒、指数增长、系统上限 5 小时），没活才取消；这个节奏由平台保证，JVM 断言不了（15.4） | `ResumePlannerTest` +2 与决策表补两行；`ResumeRecoveryTest` +1 端到端（网关太旧 → 进程被杀 → 重启后叫醒仍登记 → 升级网关后被叫醒的一轮自己恢复并发完） | 2 | E2；真 `JobScheduler` `NOT_RUN` |
| S8 收尾把叫醒带走 / 登记不上叫醒仍落账确认 | `88071b1` | `WakeGate.finishing`：被叫醒的那一轮收尾时在门的锁里现算还有没有活，没活就同步取消系统里的叫醒（`JobScheduler.cancel` 立刻生效，`jobFinished` 是异步的），确认的预先登记因此不会在“正在收尾的任务还看得见”的缝里什么也不做；`SyncRuntime` 里运行器的 `stillNeeded` 经过 `wakes.finishing`。`WakeGate.ahead`：登记不上（异常）就不提交、返回 null，`WakeAheadDispatchStore` 映射成新的 `DecideResult.WakeUnavailable`；“不发送”不登记、不受影响；待发送页对 `WakeUnavailable` 说“没有记下、什么都没有改变、请稍后再点一次”，用户再点就作废这句话。**有意反转了一条旧测试**：`ResumeRecoveryTest` 里“预先登记被系统拒绝：用户的确认照样提交”——规划器补登记要的是同一个系统调用，系统多半仍然拒绝，用户却看到已确认；现在不落账 | `ResumeRecoveryTest` +7（含更像 `JobScheduler` 的假系统 `JobOs`：已有就不登记、`cancel` 同步、`jobFinished` 异步且对被摘掉的执行无效；旧接线对照证明假系统与测试抓得住这条缝）、`:app` 3 个（`SendReviewViewModelTest`、`ResumeWiringGuardTest`） | 8 | E2；真 `JobScheduler` `NOT_RUN` |
| S9 第一次发送连接/握手就失败时意图标签仍被锁住 | `7091323` | `PinnedHttpsClient` 把“连接或握手没完成”分成 `Unreachable`、公钥对不上分成 `KeyMismatch`：这两种都证明请求没有发出去。引擎只在“第一次尝试”（此前没有不确定的发送）遇到这两种失败时放开意图冻结（`unfreezeUnsent`，与 S3 同一个入口、同一个配对围栏事务）；此前就不确定的作业与 `Interrupted`（请求可能已经出去）一律保持冻结，因为网关上也许已经存了带着旧意图的 Revision | `UnsentIntentReleaseTest`（4） | 4 | E2 |
| S10 失去焦点（分屏点了别的窗口、系统对话框、下拉通知栏）时凭证仍留在剪贴板 | `b47badf` | `MainActivity.onWindowFocusChanged(false)` 现在叫守卫的 `onLeaving()`，与 `onPause` 同一个核实归属的入口：刚失焦的这一刻通常还读得到，认得出是自己放的就当场清；读不到（保留“读不了就不盲清”的回退）或是别人的，不动；`onPause` 随后再叫一次无害 | `ClipboardWiringTest`（`hasFocus` 为 false 时叫 `onLeaving`）、`SecretClipboardGuardTest`（失焦清一次、随后 `onPause` 不重复清也不碰别人复制的） | 1 | E2；剪贴板适配器 `NOT_RUN` |
| S11 剪贴板归属凭描述里的标记，别的应用抄得走、伪造得出 | `d68b4d8` | 旧做法在剪贴板描述里放 `COPIED_AT` 额外字段当归属标记：别的应用复制了别的东西、再把这个字段抄上去，我们就会当成自己的清掉。新增 `ClipOwnership`：放进去时只在内存里记下这段文字的 SHA-256 与时刻（不记文字本身，不落盘），之后当前剪贴板文字的摘要相同才算我们的；描述里任何字段都不看，`COPIED_AT` 整个删除；手里没有东西要核实（没放过、清了、已发现被顶掉）时一次也不读剪贴板；读不了时摘要保留。进程重启后摘要没了：认不出归属，不清（残余风险，15.3）。**有意改了三条旧测试的名字与注释**（断言一字未动）：它们的前提“上一个进程放的、带我们标记”（标记跨进程证明归属）被本条撤回 | `ClipOwnershipTest`（11，含伪造额外字段的外来剪贴板在离开 / 到点 / 回前台三条路上都不被清）、`ClipboardWiringTest`（读源码钉住端口接线） | 7 | E2；剪贴板适配器 `NOT_RUN` |
| S12 找网关是一串请求，只过一次门（续作 1a） | `78c1e96` | 上一个 worker 留下一个**未提交**的测试 `revocationDuringTheFirstProbeStopsTheNextProbe`：我跑了它，确实是红的（期望 1 个请求，实际 2 个；mDNS 查询同样）。找网关要对每个已存的地址各探测一次健康、再问 mDNS、再对每个发现的地址各探测一次，引擎却用一个 `permit.gated { … }` 包住整个查找，授权世代只在**第一个**探测之前比较，第一个探测在路上时撤销，后面的探测与 mDNS 查询照样发出去。修复：`RequestGate`（`Permit` 实现它）交给 `GatewayLocator`，每个探测与 mDNS 查询各过一次门；被拦下就停（`Walk.Withheld`、`locate()` 返回 null、不缓存、不当成“连不上”报给用户）；配对与目的地管理不带门（`RequestGate.Open`），行为不变。`PolicyGateWiringTest` 的守卫：被门包住的请求块 11 → 7（4 处查找移进了定位器），并要求 4 处查找都把 `permit` 交给定位器、`GatewayDiscovery.kt` 里恰好有 2 处 `gate.gated {` | `PolicyHandoffRaceTest`（探测与 mDNS 各一个）、`GatewayLocatorTest`（门的语义）、`PolicyGateWiringTest` | 11 | E2 |
| S6 的真网关跟进 | `1f51aed` | 最终验证里 `:sync:realGatewayTest` 红了 2 个（共 25 个）：`RealGatewayTest` 里两个用例按精确顺序断言“PUT 传输失败之后”的请求序列，仍是 S6 之前的顺序——S6 让每次传输失败都作废“兼容”的记忆，所以状态查询之前多一次 `GET /v1/capabilities`（期望 `[caps, PUT, status]`，实际 `[caps, PUT, caps, status]`；第二个用例同理，末尾多一个重新提交的 PUT）。S6 提交时只跑了 JVM 套件，没有人重跑真网关套件，和第 14 节 R7 的跟进是同一种遗漏。**改的是预期，不是产品代码**：重新探测是 S6 的设计（提交信息写明传输失败要作废），断言仍是整个序列精确相等、状态查询仍要排在第二个 PUT 之前——只是把这次重新探测显式列出，没有放宽任何东西 | 这两个用例本身（红在 `ce9671c`，绿在 `1f51aed`）；`:sync:realGatewayTest` 25 个全过 | 无新增；影响见 15.5：两条真网关变异在 `ce9671c` 上是**空洞地** CAUGHT | E2 |

其余提交：`bb73b69`（两张变异表重新锚定，并为每条修复写变异）、`420075f`（变异表跟进）、`bd93376`（S3 的收口，见上）、`78c1e96`（S12）、`ce9671c`（S3 续作 1b/1c/1d，见 15.2 与 15.5）、`1f51aed`（S6 的真网关跟进，见上）、README 与 `CLAUDE.md` 的文档提交（15.7）。

### 15.2 假设与取舍（做决定时没有人可问；每一条都可以推翻）

1. **接手的范围**：不重做 S1–S11 与 `bd93376`；上一个 worker 留下的变异证据早于 `bd93376`，**不算数**，两张变异表在最终代码上整表重跑（15.5）。15.1 里 S1–S11 的“先红后绿”照上一个 worker 的提交信息转述，**我没有在修复之前的代码上逐条重放**；我自己做的独立证据是整表变异重跑——每一处修复都有变异被测试抓住，也就是撤掉修复测试会红（S12 是我先看到那个测试红、再修的；续作 1b/1c/1d 新写的测试都对着变异证明过会红）。前任留在 `/tmp` 的临时目录与本工作树里的 GitNexus 索引按派发删除，只动这两处。
2. **TDD 的“接缝”**：规范要求先与用户确认接缝，这里没有人可问，我用已有的公共接缝代替：`PolicyGeneration.handoff` / `Permit.gated` / `RequestGate` / 引擎的公开行为 / `GatewayLocator.locate` / 网关的请求与响应；没有为测试新开私有口子。
3. **S12 逐步过门，而不是给整个查找过一次门**：撤销之后一个请求都不该出去，所以每个探测、每次 mDNS 查询各过一次门。代价是撤销时找网关的一轮停在半路（`locate()` 返回 null，什么也不缓存），下一轮重新问放行；配对与目的地管理保持原样（不带门）。
4. **1b 的结论是“没有缺陷”，不是“没有查”**：依据是读源码与真实客户端的测试，不是一句断言；证明不到的是**将来**的客户端实现，所以有变异守着（`s3-real-client-runs-blocking-work-in-the-callers-prefix`、`s3-handoff-holds-the-lock-for-the-whole-request`、`s3-handoff-shields-the-request-from-cancellation`、`s3-handoff-reports-a-failed-request-as-withheld`）。
5. **1c：`revokedBetweenGenerationReadAndClientHandoffMustNotSendText` 原来可以空洞通过**：它只等撤销最多 1 秒（`revocationDone.await(1, SECONDS)`），撤销先完成才断言“PUT 数为 0”；没有先完成（撤销排在锁后面）就什么也不断言，所以“PUT 用一张过期的章交出”这类缺陷照样绿。现在没有固定等待：观察撤销线程处于 `BLOCKED`（卡在监视器上）或已经完成，二选一各有断言——撤销不得不等交接完成时，PUT 恰好发出一次；撤销先完成时，PUT 不能发出。旧版本对着新变异 `s3-put-handed-over-with-a-stale-permit`（PUT 用一张过期的 `Permit(-1)` 交出）**存活**，新版本抓住它（旧版是在已提交的版本上真跑出来的）。
6. **1d：对“等价变异”的说法逐条核实**，规则是：只删一行后整个 `:sync:test`（581 个）仍全绿，才算等价；否则恢复成严格形式、作为独立条目。结果：
   - **假的**：紧挨着请求的最后一问“只删这一行、沿用旧章”（`policy-second-gate-missing` 的严格形式）——最后一问还管着“要不要构造提交（冻结意图标签）”，少了它被拦下的尝试也先构造了提交再放开，`DataFlowGateTest` 数最后一问的次数、新增的 `PolicyGateRaceTest` 用例数构造提交的次数都会红；合同探测之后那一问“只删这一行”（`r4-no-gate-after-contract-probe` 的严格形式）——`SyncEngineTest` 的 R13 用例与 `DataFlowGateTest` 的“最后一道”用例会红。两条严格形式恢复为独立条目（`s3-last-ask-deleted-keeps-the-old-permit`、`s3-contract-probe-ask-deleted-keeps-the-old-permit`），并新增 `PolicyGateRaceTest` 的一个测试（换账本之后授权被收回，请求之前的最后一问就拦下它：提交一次也没构造、标签从头到尾没冻结过）。
   - **真的**：发送路径定位之后的那一问（`r4-no-gate-after-locating`）、追加路径定位之后的那一问（`r4-additions-no-gate-after-locating`）、追加每个请求之前的那一问（`r4-additions-no-gate-before-each-request`）、合同自愈里定位之后的第二问（`s3-heal-contract-skips-the-second-ask`）——只删这一行，整个 `:sync:test`（581 个）都绿，对规则等价（撤销之后什么都发不出去：请求被每个请求前的世代比较拦下），差别只在收回后多记一次本地地址、以及“设置变了但没撤销授权”时这一轮多等一轮；这几条保留“换成新章”的变异（引擎不再问策略），描述已改成精确的说法。
   - `s3-permit-never-compares-the-generation`：它丢掉的那个自愈用例，盯的是第二次询问而不是比较，所以本来就不该红（真跑 6 个里红 4 个、自愈用例仍绿）——说法成立。
   - `engine-blocks-on-stale-connection-verdict`（Ticket 21 表）：只删第一层 `connectionBlocked` 里的 `recordConnectionProblem` 检查，581 个全绿——`settle` 的围栏用同一个判断（`isSamePairing` = deviceId && pairedAt && 公钥，一次配对的身份唯一）再查一遍，真的等价；只删围栏那一层则被围栏测试（`ResultFenceAtomicityTest`、`PairingFenceAtomicityTest`）抓住。两处同时删的两编辑形式保留。
   - `engine-accepts-stale-status-answer`（Ticket 21 表）：它的两处编辑各自被各自的测试抓住（只删 `settle` 里的检查：5/51 红；只删构造之前的围栏：1/51 红），所以另加一条单编辑条目 `engine-accepts-stale-status-answer-resend`。
7. **变异条目的编号**：新条目一律以 `s<数字>-` 开头，`test_every_round3_mutation_in_the_table_is_attributed_to_a_fix` 反向要求它们都归属于某项修复，所以 1d 恢复的严格条目用 `s3-` 前缀，不用 `policy-` / `r4-`。
8. **工具方面**：没有 Codex 评审、没有调用 superpowers 技能（派发要求单一 agent、禁止子 agent 与辅助 worker；TDD 按“先写会红的测试、看它真的红、再修、再看它绿”手工执行）；“每任务 4000、每会话 30000 token”的预算对这份工作量不可能满足，已超出，靠检查点摘要续作；mcp-feedback-enhanced 工具在本环境没有加载，没有调用；AOCI 在本仓库未启用（没有 `aoci*.txt`，不擅自启用）；GitNexus 索引只覆盖另一个检出（`/Users/ethan/Desktop/CODE/auri`，没有 Ticket 22 的代码），调查靠 `rg` 与读源码。
9. **`tests/security/**` 的范围**：派发没有把它列进可改范围，也没有列进禁止范围；我按保守的一边处理——不改。所以 `repo` 套件里那个由本机 TUN 代理引起的失败（15.6），我没有碰那个测试，只记录了证据。

### 15.3 残余风险（如实）

- **交接之后收不回**：授权比较与“把请求交给客户端”在同一把锁里，但请求一旦交出就收不回；撤销那一刻已经在路上的请求会继续（至多一个，与第 14 节同类，没有扩大）。
- **锁是进程内的**：`handoffLock` 是进程级监视器；V1 单进程部署下够用（口径同 14.3 的 R5）。锁内目前只有“比较 + 请求到第一个挂起点之前的前缀”，**没有阻塞工作**，这是对**现在的**`PinnedHttpsClient` 等实现读源码并用真实客户端测试证明的；将来换一个在前缀里阻塞的客户端实现，会拖住所有策略变更——`PolicyHandoffRealClientTest` 与对应变异会红，但前提是有人把新实现也接进这组测试。
- **S11**：进程重启之后内存里的摘要没了，认不出归属，不清——凭证可能留在剪贴板里，直到被覆盖或系统自己清（Android 13 起约一小时，真机 `NOT_RUN`）。这是为“绝不误清别人的东西”付的代价。
- **S7**：重新探测的节奏由 `JobScheduler` 的退避（初始 30 秒、指数增长、系统上限 5 小时）决定，JVM 无法断言；升级网关之后最长可能等几个小时才被叫醒（用户打开应用会立刻重新评估）。
- **S1**：升级之前入库的文字对替换凭证一律不可见（宁可多藏；用户想让新凭证读到，只能再录一次）。**S5**：升级前的 v2 授权行读成没授权，用户要重新同意一次。**S4**：升级后第一次对账会把旧格式（只有编号）的缓存整个作废一次（保守）。
- **变异表**：每条 CAUGHT 只证明那一处改动有测试盯着，不证明所有行为都被测到；15.2 第 6 条列出的等价变异按规则等价，不是漏测。没有设备，所有设备相关的结论 `NOT_RUN`。
- **变异检查不对照未变异的基线**：它只问“变异后有没有红的、名字对不对”，所以“必红”的那个测试若本来就是红的，CAUGHT 是空洞的（15.5 的 `real-gw-*` 两条就是这样漏过整表真跑的，靠最终验证才发现）。本轮用一次性的只读审计补了这道核对，**没有改检查本身**（不在派发范围内，也会让每个结果目录多跑一遍基线）；建议负责人把基线对照做进 `destination_mutation_check.py` 与 `android-sync/mutation_check.py`，否则以后每次“整表全 CAUGHT”都要另外做这一遍。
- **附带观察（不属于本票，没有处理）**：网关的 `network.py` 把 `fc00::/7`（IPv6 唯一本地地址）都当作局域网，所以默认的 `serve` 也监听本机 Tailscale 与 TUN 代理接口上的 ULA 地址（`lsof` 里看到两个）。这与 ADR-0018“只在局域网、不经 VPN / 隧道”的意图是否冲突，由网关与产品负责人判断；TLS 指纹绑定与令牌仍然要过，但“网关只在局域网可达”在装了 Tailscale 的机器上不严格成立。`services/gateway/**` 不在本轮允许改的范围，只记录。

### 15.4 要在设备上核对的（`NOT_RUN`，原因：派发禁止启动模拟器与仪器测试，没有连着的设备）

前提与变量同第 4 节、13.3、14.4（调试版已装、电脑上网关在跑并已配对、`AURI_GATEWAY_URL` / `AURI_GATEWAY_SPKI_SHA256` / `AURI_DESTINATION_CREDENTIAL` 三个环境变量）。

| 项 | 做什么 | 期望 |
|---|---|---|
| S6 / S7 网关太旧，升级后自己恢复，进程被杀也有人叫醒 | 同 14.4 的 R7：`git worktree add /tmp/auri-old-gateway origin/integration/auri-v2`，用本仓库的解释器起上一版网关：`PYTHONPATH=/tmp/auri-old-gateway/services/gateway .venv/bin/python -m auri_gateway serve --data-dir /tmp/auri-old-gw-data --port 8445`，`… pair --data-dir /tmp/auri-old-gw-data`，扫码并录一句；同步页出现“请先更新网关”后：`adb shell dumpsys jobscheduler \| grep -B2 -A8 app.auri.android`（作业号 2101），`adb shell am kill app.auri.android`（**不要用 `force-stop`**）；停掉上一版网关，对**同一个数据目录**执行 `PYTHONPATH=services/gateway .venv/bin/python -m auri_gateway migrate --data-dir /tmp/auri-old-gw-data`，再用当前网关在同一端口起它；等叫醒（或强制：`adb shell cmd jobscheduler run -f app.auri.android 2101`）；收尾 `git worktree remove /tmp/auri-old-gateway`。**反过来（S6，降级）没有验证过**：当前网关上发过一句之后换回上一版网关，要求文字在手机上排着、不是“已拒收”；上一版网关未必肯打开已迁移的数据目录，不行就只能靠 JVM 的 `ContractRevalidationTest` | 叫起之后文字送到、同步页的提示自己消失，不需要重新配对；叫醒在进程被杀之后仍在 `dumpsys` 里 |
| S8 登记不上叫醒就不落账确认 | 设备上无法注入“系统拒绝登记”；只确认正常路径：在预览目的地点“确认发送”之后 `adb shell dumpsys jobscheduler \| grep -A6 app.auri.android` 有作业 2101；追加发完（没活）之后作业消失 | 正常路径与 JVM 一致；“登记不上就不落账、提示再点一次”只有 JVM 证据（`ResumeRecoveryTest`、`SendReviewViewModelTest`） |
| S10 失去焦点也清凭证 | 生成凭证 →“复制并关闭”；① 进入分屏并点另一个应用的窗口；② 下拉通知栏；③ 弹出系统对话框；然后在别的应用里粘贴，`adb logcat -d -s AuriSync:I \| grep clipboard` | 粘不出凭证（失焦的那一刻已清；日志有 `clipboard.copied`、`clipboard.cleared`）；读不到时只有 `clipboard.unverifiable`、没有清 |
| S11 归属按内容证明 | 复制凭证后在另一个应用里复制一句**别的话**，回到本应用；再复制凭证并 `adb shell am kill app.auri.android`，重新打开应用 | 前者：别人的话还在（`clipboard.kept-other`）；后者：凭证仍在剪贴板里、没有 `clipboard.cleared`（进程重启之后认不出归属，15.3 第 3 条） |
| S1 / S2 / S3 / S4 / S5 / S9 / S12 | 纯逻辑（S1 是网关一侧，由真网关进程的测试覆盖），没有设备相关的部分；S3 / S12 的窗口是微秒级，设备上无法确定性复现 | JVM 与真网关测试为准 |

设备上的自动化测试（`NOT_RUN`）同第 4 节末尾与 14.4：`AURI_ALSO_ASR_ACCEPTANCE=1 execution/acceptance/android-sync/run_emulator_acceptance.sh`（一次性无头模拟器，先取共享锁），或已连设备时 `cd apps/android && ./gradlew :syncplatform:connectedDebugAndroidTest`。

### 15.5 变异检查

- **表的规模**：Ticket 22 的表 221 → **293 条**：第三轮新增 72 条（S1 3、S2 2、S3 17、S4 3、S5 6、S6 8、S7 2、S8 8、S9 4、S10 1、S11 7、S12 11，逐条见下表）；`test_destination_mutation_table.py` 的 `ROUND3` 把每个 `s<数字>-` 条目归到某项修复，并反向要求表里没有无主条目。其中 15 条在网关一侧（R1/R2 的 12 条加 S1 的 3 条，由 pytest 运行器真跑）。`bb73b69` / `420075f` 还重新锚定了既有条目（被改的源码挪了位置或换了写法，只换锚点，破坏的含义不变）。Ticket 21 的表 253 → **254 条**（新增 `engine-accepts-stale-status-answer-resend`，见 15.2 第 6 条）。

| 修复 | 条数 | 条目（`destination_mutation_check.py --only <id>`） |
|---|---|---|
| S1 升级补撤销截止点不信网关时钟的先后 | 3 | `s1-gw-migration-ignores-revision-times`、`s1-gw-migration-trusts-time-order`、`s1-gw-migration-treats-every-later-credential-as-suspension` |
| S2 网关结果的判断与落账在配对存储的同一把锁里 | 2 | `s2-settle-without-the-pairing-lock`、`s2-freeze-outside-the-pairing-lock` |
| S3 放行与请求交出去之间不留缝（授权世代） | 17 | `s3-permit-never-compares-the-generation`、`s3-handoff-without-the-policy-lock`、`s3-heal-contract-skips-the-second-ask`、`s3-generation-not-moved-before-the-write`、`s3-generation-not-moved-after-settling`、`s3-sqlite-policy-write-skips-the-generation`、`s3-first-attempt-keeps-the-freeze-when-gated`、`s3-uncertain-attempt-releases-the-freeze`、`s3-sql-unfreeze-does-nothing`、`s3-real-client-runs-blocking-work-in-the-callers-prefix`、`s3-handoff-holds-the-lock-for-the-whole-request`、`s3-handoff-shields-the-request-from-cancellation`、`s3-handoff-reports-a-failed-request-as-withheld`、`s3-handoff-checks-then-never-sends`、`s3-put-handed-over-with-a-stale-permit`、`s3-last-ask-deleted-keeps-the-old-permit`、`s3-contract-probe-ask-deleted-keeps-the-old-permit` |
| S4 缓存与排着的目标按完整网关身份认 | 3 | `s4-ledger-key-is-the-gateway-id-only`、`s4-ledger-key-is-the-gateway-id-only-on-sqlite`、`s4-sql-use-gateway-compares-the-id-only` |
| S5 授权带配对世代，清理失败不让旧同意复活 | 6 | `s5-epoch-always-inherited`、`s5-epoch-always-fresh`、`s5-consent-ignores-the-pairing-epoch`、`s5-consent-ignores-the-pairing-epoch-on-sqlite`、`s5-grant-epoch-not-validated`、`s5-pairing-store-drops-the-epoch` |
| S6 兼容结论作废并重新问版本 | 8 | `s6-422-permanent-without-rechecking`、`s6-platform-network-events-keep-the-verdict`、`s6-unreachable-location-keeps-the-verdict`、`s6-guard-invalidate-keeps-the-memory`、`s6-guard-remembers-an-answer-asked-before-an-invalidation`、`s6-service-keeps-the-verdict-after-a-failed-call`、`s6-service-unreachable-keeps-the-verdict`、`s6-additions-permanent-rejection-without-rechecking` |
| S7 网关太旧不取消系统叫醒 | 2 | `s7-too-old-cancels-the-wake`、`s7-any-connection-problem-keeps-the-wake` |
| S8 收尾不带走叫醒；登记不上叫醒就不落账确认 | 8 | `s8-finishing-outside-the-lock`、`s8-finishing-never-cancels-the-wake`、`s8-finishing-trusts-what-the-gate-told`、`s8-prewake-failure-still-commits`、`s8-wake-unavailable-commits-anyway`、`s8-runtime-finishing-skips-the-gate`、`s8-review-ignores-wake-unavailable`、`s8-review-screen-never-shows-the-retry-notice` |
| S9 第一次发送连接就失败时放开意图标签 | 4 | `s9-unreachable-first-attempt-keeps-the-freeze`、`s9-key-mismatch-first-attempt-keeps-the-freeze`、`s9-interrupted-request-releases-the-freeze`、`s9-uncertain-attempt-releases-the-freeze` |
| S10 失去焦点也按离开处理 | 1 | `s10-focus-loss-does-not-clear-the-clipboard` |
| S11 剪贴板归属按内容证明 | 7 | `s11-ownership-trusts-any-content`、`s11-ownership-compares-loosely`、`s11-ownership-keeps-the-secret-text`、`s11-ownership-keeps-reading-after-being-replaced`、`s11-ownership-forget-keeps-the-digest`、`s11-port-does-not-remember-what-it-copied`、`s11-port-clear-keeps-the-digest` |
| S12 找网关是一串请求：逐个探测、问 mDNS 各过一次门（续作 1a） | 11 | `s12-walk-skips-the-gate-for-probes`、`s12-walk-skips-the-gate-for-mdns`、`s12-walk-keeps-asking-after-the-gate-withholds`、`s12-withheld-walk-reported-as-unreachable`、`s12-locator-hands-an-open-gate-to-the-walk`、`s12-engine-locates-without-the-permit`、`s12-open-gate-withholds`、`s12-attempt-locates-with-a-fresh-permit`、`s12-probe-locates-with-a-fresh-permit`、`s12-heal-contract-locates-with-a-fresh-permit`、`s12-additions-locate-with-a-fresh-permit` |
| 合计 | 72 | |

- **整表从零重跑**：上一个 worker 留下的变异结果早于 `bd93376`，不算数，所以两张表都在最终代码上从头真跑。代码 = `ce9671c`（S12 与 1b/1c/1d 之后；到最终提交 `1f51aed` 只改了 `RealGatewayTest.kt` 里两个用例的预期，见下一条）。用仓库外的驱动脚本分片：每片 `--only <至多 10 个编号>`，每片各自过内存关卡并取共享锁、片与片之间放锁；每片之后核对工作树只剩 `?? .serena/`、没有残留网关进程，遇到第一片出现非 CAUGHT 就停（这次没有停过）。
  - **Ticket 22，293 条**：30 片，11-23 00:53–03:35，共 2 小时 42 分。结果：**293 条全部 CAUGHT，SURVIVED 0，INVALID 0**（其中 15 条网关一侧的由 pytest 运行器真跑，其余走 Gradle）。读数：每片开始前空闲最低 48%，片内最低 27%（高于 25% 的等待线），交换区 2526 → 3406 MB（片内最高 3478 MB；这段时间 macOS 自己把交换区总量从 3072 MB 扩到了 4096 MB，没有追查原因），关卡一次也没有触发等待；每片前后 `auri_gateway` 残留进程 0。
  - **Ticket 21，254 条**：26 片，03:35–05:43，共 2 小时 8 分。结果：**254 条全部 CAUGHT，SURVIVED 0，INVALID 0**。片内空闲最低 41%，交换区 3406 → 3278 MB，没有等待，没有残留进程。
  - 没有存活者，所以派发里“存活的补测试”这一步没有发生；但下一条说明，“全部 CAUGHT”还要加一道基线核对才算数。
- **整表之后发现的一处空洞（`real-gw-resends-without-asking`、`real-gw-send-started-not-recorded`）**：变异检查只核对“变异后有没有红的、红的测试名里有没有每个必红的名字”，**不拿未变异的基线对照**。最终验证时 `:sync:realGatewayTest` 红了 2 个（15.1 末行），这两个用例恰好是上面两条 Ticket 21 变异唯一的“必红”测试（“响应在路上丢了”与“请求没送到网关”）。也就是说它们在 `ce9671c` 上的 CAUGHT 是**空洞的**：测试没有变异也是红的，变不变异都一样。预期修好之后（`1f51aed`），我在 `1f51aed` 上重跑了这两条（各 2/25 个测试红，恰好是这两个用例，这回红是变异造成的），以及两张表里**全部**的 `real-gw-*` 条目（Ticket 21 的 11 条、Ticket 22 的 10 条；只有它们会跑这份被改动的测试）：21 条全部 CAUGHT。
- **基线审计（防空洞，一次性）**：只读脚本（在仓库外，没有提交）读两张表，对每个 Gradle 条目的每个“必红”测试名，在**未变异的基线**的 JUnit XML 里找至少一个同名且通过的测试；pytest 条目同理（15 条，对着 `test_revocation_cutoff.py` 在基线上全过的 14 个）。基线 XML 在 `1f51aed` 上用 `--rerun` 新跑出来——Python 验收套件会带过滤器重跑 Gradle、覆盖结果目录，所以审计前必须重新生成，沿用旧 XML 会得出一堆假问题（我第一次就是这样得到 398 个“问题”的）。结果：Gradle 条目的 713 个（条目, 必红测试名）对与 15 条 pytest 条目，**0 个问题**。
- **表的离线自检**：`test_destination_mutation_table.py` 918 个、`android-sync/test_mutation_table.py` 770 个（`--collect-only` 数得）；两者都在 15.6 的整个验收套件里再跑一遍（`android-destinations` 969 个、`android-sync` 817 个，全过）。离线自检只能证明原文可替换、测试名存在，变异能否编译、预期红的测试名对不对，要真跑才知道——上面的整表真跑就是这一步。

### 15.6 最终验证（最新结果）

在 `1f51aed`（本轮最后一个代码提交；之后只有文档）上，**一次一个套件**，每个套件前后都读内存与交换区、查 `auri_gateway` 残留进程。Gradle 命令都带 `--no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2`（下表省略），先 `source ~/.config/auri/android-env.sh`、取共享锁；测试任务带 `--rerun`，避免被判 UP-TO-DATE 而没有真跑；pytest 用仓库 `.venv`，带 `NO_PROXY=localhost,127.0.0.1,::1`，涉及 Gradle 的另带 `AURI_GRADLE_ARGS`（同上参数）。

#### Gradle（`apps/android`）

| 套件 | 命令 | 结果 | 起止（本机时间） | 内存空闲（前 → 后）与交换区已用 |
|---|---|---|---|---|
| `assemble-debug` | `./gradlew :app:assembleDebug` | exit 0：构建成功 | 06:21:35–06:21:43 | 66% → 67%；交换区 3254 → 3254 MB |
| `capture` | `./gradlew :capture:test --rerun` | exit 0：369 个，失败 0、出错 0、跳过 0 | 06:21:43–06:21:55 | 67% → 66%；交换区 3254 → 3254 MB |
| `modelmgmt` | `./gradlew :modelmgmt:testDebugUnitTest --rerun` | exit 0：84 个，失败 0、出错 0、跳过 0 | 06:21:56–06:22:38 | 66% → 66%；交换区 3254 → 3254 MB |
| `platform` | `./gradlew :platform:testDebugUnitTest --rerun` | exit 0：257 个，失败 0、出错 0、跳过 0 | 06:22:38–06:22:52 | 66% → 65%；交换区 3254 → 3254 MB |
| `app` | `./gradlew :app:testDebugUnitTest --rerun` | exit 0：342 个，失败 0、出错 0、跳过 0 | 06:22:52–06:23:04 | 65% → 65%；交换区 3254 → 3254 MB |
| `asr` | `./gradlew :asr:testDebugUnitTest --rerun` | exit 0：112 个，失败 0、出错 0、跳过 0 | 06:23:04–06:23:12 | 65% → 65%；交换区 3254 → 3254 MB |
| `sync` | `./gradlew :sync:test --rerun` | exit 0：581 个，失败 0、出错 0、跳过 0 | 06:23:12–06:23:28 | 65% → 64%；交换区 3254 → 3254 MB |
| `syncplatform` | `./gradlew :syncplatform:testDebugUnitTest --rerun` | exit 0：119 个，失败 0、出错 0、跳过 0 | 06:23:28–06:23:38 | 64% → 65%；交换区 3254 → 3254 MB |
| `realgateway` | `./gradlew :sync:realGatewayTest --rerun` | exit 0：25 个，失败 0、出错 0、跳过 0 | 06:23:39–06:24:31 | 65% → 53%；交换区 3254 → 3254 MB |
| `androidtest-apks` | `./gradlew :app:assembleDebugAndroidTest :syncplatform:assembleDebugAndroidTest :platform:assembleDebugAndroidTest` | exit 0：构建成功 | 06:24:31–06:24:53 | 53% → 59%；交换区 3254 → 3254 MB |

#### Python 验收与仓库套件（每个都是单独一次 pytest）

| 套件 | 命令 | 结果 | 起止（本机时间） | 内存空闲（前 → 后）与交换区已用 |
|---|---|---|---|---|
| `android-capture` | `pytest execution/acceptance/android-capture` | exit 0：59 passed in 52.19s | 06:24:53–06:25:47 | 59% → 66%；交换区 3254 → 3254 MB；残留网关进程 0 |
| `android-control` | `pytest execution/acceptance/android-control` | exit 0：106 passed in 54.25s | 06:25:47–06:26:44 | 66% → 66%；交换区 3254 → 3254 MB；残留网关进程 0 |
| `android-silence` | `pytest execution/acceptance/android-silence` | exit 0：3 passed in 0.06s | 06:26:44–06:26:46 | 66% → 66%；交换区 3254 → 3254 MB；残留网关进程 0 |
| `android-models` | `pytest execution/acceptance/android-models` | exit 0：2 passed in 0.02s | 06:26:46–06:26:48 | 65% → 66%；交换区 3254 → 3254 MB；残留网关进程 0 |
| `android-sync` | `pytest execution/acceptance/android-sync` | exit 0：817 passed in 182.39s (0:03:02) | 06:26:48–06:29:52 | 66% → 68%；交换区 3254 → 3254 MB；残留网关进程 0 |
| `android-destinations` | `pytest execution/acceptance/android-destinations` | exit 0：969 passed in 44.14s | 06:29:52–06:30:39 | 68% → 68%；交换区 3254 → 3254 MB；残留网关进程 0 |
| `android-interruptions` | `pytest execution/acceptance/android-interruptions` | exit 5：no tests ran in 0.01s | 06:30:39–06:30:41 | 68% → 69%；交换区 3254 → 3254 MB；残留网关进程 0 |
| `asr-android` | `pytest execution/acceptance/asr-android` | exit 0：131 passed in 55.62s | 06:30:41–06:31:37 | 69% → 66%；交换区 3254 → 3254 MB；残留网关进程 0 |
| `repo` | `pytest tests/contract tests/integration tests/security` | exit 1：1 failed, 545 passed in 293.96s (0:04:53) | 06:31:37–06:36:32 | 66% → 65%；交换区 3254 → 3198 MB；残留网关进程 0 |

- 运行 20 次（表里 19 次，第 20 次是 `repo` 里那个失败测试的单独复跑，见下），关卡等待 0 次，运行中空闲最低 44%，交换区最高 3254 MB；每次运行之后工作树都只剩 `?? .serena/`，残留网关进程 0。除 `repo`（含它的复跑）与 `android-interruptions`（exit 5：它没有测试，一直如此）之外全部 exit 0，没有任何失败、出错或跳过。
- **`repo` 套件有 1 个失败，是本机环境造成的，不是代码问题；但按派发的规则（任何套件红 = 任务没做完），我没有把这一轮报成全绿。** 失败的是 `tests/security/test_lan_transport.py::test_default_serve_binds_loopback_and_lan_only_never_vpn_or_public`（`DID NOT RAISE OSError`，第 143 行）。这个测试起一个默认的网关，再对本机每一个“既不是回环、也不是局域网”的地址发起连接，要求都被拒绝（网关不得绑 VPN / 公网地址，ADR-0018）。本机现在有一个 mihomo 的 TUN 代理接口（`utun12`，`198.18.0.1`，默认路由都接走）：**连 `198.18.0.1` 上没有任何进程监听的端口，TCP 连接也会成功**（对照实验：随机挑一个没人监听的端口，同样连得上），所以测试里“被拒绝”的前提在这台机器上不成立，与网关无关。证据：① 同一个测试单独重跑（06:38，2 秒）同样红，是确定性的；② 网关进程实际监听的套接字（`lsof`）只有 `127.0.0.1`、本机局域网地址和两个 IPv6 唯一本地地址（见 15.3 附带观察），没有 `198.18.0.1`；③ 第二轮（11-21 21:48）同一台机器上这个套件 543 个全绿；那时这个 TUN 是否在运行我不知道。**我没有改这个测试**：`tests/security/**` 不在派发允许改的范围里（15.2 第 9 条），改它等于动一条安全负例；正确的改法是让测试先用“没有监听的端口”做对照，对照也连得上的地址就不参与断言，由测试的归属人决定。我也没有去动本机的代理接口。关掉那个 TUN 之后重跑这一个套件（`NO_PROXY=localhost,127.0.0.1,::1 no_proxy=localhost,127.0.0.1,::1 .venv/bin/pytest tests/contract tests/integration tests/security`，约 5 分钟）应当是 546 个全过——这是推断，我没有关它，所以**没有验证过**。
- 测得的数字（E2，本机 macOS 上量的，没有设备）：网关与合同套件 546 个（545 过、1 个环境失败）/ 4 分 53 秒（第二轮 543 个 / 4 分 18 秒；多出的 3 个是 `test_revocation_cutoff.py` 里 S1 新增的用例）；真网关 25 个（`:sync:realGatewayTest`）；`:sync:test` 581 个（第二轮 511）、`:syncplatform` 119（111）、`:app` 342（320）、`:capture` 369、`:platform` 257、`:modelmgmt` 84、`:asr` 112（这四个与第二轮相同）；`android-destinations` 969（737）、`android-sync` 817（814）。没有任何性能或设备数字，因为没有设备。
- 变异表（15.5）：Ticket 22 的 293 条、Ticket 21 的 254 条，整表真跑全部 CAUGHT；最终代码上另对 21 条真网关条目重跑，全部 CAUGHT。
- 没有做、也没法做的：所有设备或模拟器才能证明的（15.4 列了命令与期望），状态都是 `NOT_RUN`，原因：派发禁止启动模拟器与仪器测试、没有连着的设备。

### 15.7 对共享文件的改动

第三轮共 17 个提交（`ac39320..1f51aed`），68 个文件，+4128/−410，其中 `apps/android` 62 个文件（+3243/−265）。

| 文件 | 改动 | 为什么 |
|---|---|---|
| `services/gateway/auri_gateway/migrations/0005_destination_revocation_cutoff.sql`、`tests/integration/test_revocation_cutoff.py`、`services/gateway/OPERATIONS.md`（1 行） | S1（`445441a`，上一个 worker 的提交；本轮没有再改网关一侧的任何文件）：迁移的回填规则、3 个升级用例与 1 个守卫、`OPERATIONS.md` 里撤销截止点那一段的说法 | 网关一侧的修复 |
| `apps/android/{sync,syncplatform,app}` 的产品代码与测试 | S2–S12 的修复，每处都带新测试与变异（先红后绿的情况见 15.2 第 1 条）；1b / 1c / 1d 没有改产品代码，只加了测试 | 15.1 |
| `apps/android/sync/src/test/kotlin/app/auri/sync/realgateway/RealGatewayTest.kt`（Ticket 21 的真网关测试） | `1f51aed`：两个用例的预期里显式列出 S6 的重新探测，断言仍是整个请求序列精确相等，没有放宽 | S6 的设计（15.1 末行） |
| `execution/acceptance/android-sync/mutation_check.py`（Ticket 21 的变异表） | 54 行：重新锚定若干条（本轮改了它们的目标源码）、新增 1 条 `engine-accepts-stale-status-answer-resend` | 15.5 |
| `execution/acceptance/android-destinations/destination_mutation_check.py`、`test_destination_mutation_table.py` | 72 条新条目、`ROUND3` 归属表与反向守卫；既有条目重新锚定；1d 改了几条的说明、恢复了两条严格形式 | 15.5 |
| `CLAUDE.md` | 只改“命令”一节里 Ticket 21 与 Ticket 22 两段的数字、Ticket 22 那段加一句第三轮的行为（授权世代与交接锁、逐步过门、兼容结论作废、剪贴板归属按内容摘要），以及仓库套件个数（546） | 数字改成 15.6 的最新值 |
| 本 README | 新增第 15 节；第 4、6、10、13 节里被 S11 取代的几处说法（剪贴板归属标记、杀进程后补清）就地加了指针，没有改写历史 | 避免旧步骤误导设备验收 |

没有动：`contracts/`、`tests/contract/`、`.github/`、`tests/security/`，以及 `services/gateway/**`（S1 的改动是上一个 worker 提交的）。

### 15.8 收尾：运行环境读数、临时文件与过程说明

- **内存读数**：我的运行器每次 Gradle / pytest 运行前后与运行中都读了空闲内存百分比与交换区，共 118 次运行、1840 次读数（11-22 23:47–11-23 06:42，读数日志在仓库外，已随收尾删除）：空闲 27%–70%（最低的 27% 出现在 Ticket 22 整表真跑的某一片里，高于 25% 的等待线），交换区 2497–3478 MB；**没有一次触发“等内存”**；同一时刻只有一个 Gradle 或 pytest 进程；每次运行前后的 `auri_gateway` 残留进程都是 0；没有执行 `./gradlew --stop`，没有启动任何模拟器、AVD、IDE 或仪器测试。118 次运行里 13 次退出码非 0：先写会红的测试的红阶段（2 次，S12 的两个测试）、边写变异条目边跑离线自检时当场修掉的失败（2 次）、1c / 1d 里的对照与变异实验（5 次，红是预期结果之一）、最终验证里真网关套件红的那一次（15.1 末行）、`android-interruptions` 的 exit 5（没有测试，一直如此）、`repo` 套件的 1 次环境失败与它的单测复跑（15.6）。
- **过程失误（三处，都没有影响结果）**：① 启动 Ticket 22 整表驱动脚本时，我又用了“子 shell + nohup”（14.8 记过同样的失误），工具会话一结束进程就被杀；我一分钟内查进程发现它没在跑（输出只有一行），没有任何变异被应用、没有占着共享锁，改成受监控的后台任务重新启动，之后每次启动长任务都先确认它真在跑。② 做 1b / 1c 的变异实验时，我用 `pkill -f` 按路径匹配想结束卡住的测试 JVM，**匹配到了我自己的运行器**（它的命令行里有那个路径），把它连同子进程一起结束，在 `~/.cache/auri/gradle.lock` 留下一个空的锁目录；确认没有任何 Gradle 进程之后 `rmdir` 掉它。它短暂地挡过别的工作树（我没有看到别人排队的迹象，但无法确认）；工作树没受影响，因为变异只在仓库外的快照里。③ 一次基线审计里，我在 zsh 里把命令存进未加引号的变量再展开，zsh 不拆词，命令根本没跑，输出又被重定向吞了；读审计结果时看到缺口才发现，改成函数后重跑。审计从没用过那次空跑的数据。
- **临时文件**：本轮建过：`/tmp/auri-t22r3b.Z23GhTB9`（运行器、各次运行的日志与读数、各片变异日志、基线审计脚本，以及两个变异快照 `auri-ticket21-mutation-snapshot` 与 `auri-ticket22-mutation-snapshot`，共约 262 MB）、`/tmp/auri-t22r3b-*.out`（5 个）、`/tmp/.auri-t22r3b-current`；以及系统临时目录里我的运行留下的测试副产物：按目录创建时间对照 118 次运行的起止时间（前后各放宽 3 秒）归到我名下的 340 个目录（`auri-tls-fixtures*` 326、`auri-cfg*` 14）与 `pytest-of-ethan`（3 份基目录，创建时间与我最后三次 pytest 运行吻合）。收尾时**全部删除**。另按派发删除了上一个 worker 留下的 `/tmp/auri-t22r3.Z3elJTT7`（135 MB）与本工作树的 `.gitnexus/` 及它在 GitNexus 注册表里的条目（主检出的索引没有动）。**保留**：34 个**不在我任何一次运行区间内**的 `auri-tls-fixtures*`——31 个创建于 11-22 23:12–23:35，早于我第一次记录的运行（23:47），应是上一个 worker 的；3 个创建于 11-23 00:16–00:17，落在两次记录的运行之间，无法确认是不是我的，都没有动；git 忽略的 `apps/android/**/build`、`.gradle` 与 `.venv`（增量构建与测试要用）；未跟踪的 `.serena/`（开工前就在，不是我建的，没有提交）。
- **和使用者规则对不上的地方**（没有绕过，也没有假装做了）：见 15.2 第 8 条（没有 Codex 评审、没有调用 superpowers 技能、token 预算超出、mcp-feedback-enhanced 未加载、AOCI 未启用、GitNexus 索引覆盖另一个检出）。

## 16. 复评修复第四轮（F1–F10）

第三轮（第 15 节）之后又来了第三次再评：10 条发现（F1–F10，编号照派发里的顺序，与第三轮的 S 编号无关），外加对两个变异检查工具的要求（16.5）。做法同前：单一 agent，没有人可问；每条修复一个提交——先写会红的测试、看它真的红（红得有理由）、再修、再看它绿——至少一条变异被新测试抓住，提交完立刻推送（`git push origin ticket/22-destinations-and-sending`），提交信息以 `Ticket 22 round 4 F<编号>:` 开头。起点 `5cb9d08`（第三轮的最后一个文档提交，GitHub CI 在它上面是绿的）。证据等级同 15：JVM 单测、真网关进程测试、Python 校验工具都是 E2；没有设备或模拟器（派发禁止启动），设备才能证明的一律 `NOT_RUN`，步骤在 16.4。

### 16.1 发现与修复

| 编号 | 提交 | 做了什么 | 先红后绿的测试（主要的） | 变异 | 结论 |
|---|---|---|---|---|---|
| F1（高，S3 的缺口）授权设置的写窗口 | `a9053ac` | 改授权（收回同意、开严格仅本地）的写在数据库线程上排队、提交，原来世代只在写前后各加一：写已经排上队（甚至已提交）而还没落定的这一小段里，带着旧章的请求仍能交接出去，“收回已经提交，请求还是出去了”。现在 `PolicyGeneration` 在进程级交接锁里记着“在途的写有几个”；窗口开着时读世代返回**每次都不同的负数章**，任何带着旧章的交接都对不上；每个写一张一次性凭据（NEW → OPEN → SETTLED：落定只算一次、落定后不能再开窗口）；`SqliteDataFlowPolicy.write` 在取数据库连接之前开窗口、在数据库线程上紧接着写落定、外层 `finally` 再落定一次——被取消、拿不到连接的写也不会让窗口永远开着 | `PolicyWriteWindowTest`（7）、`PolicyWriteProtocolTest`（7）、`PolicyWriteWiringTest`（2，读源码钉住 SQLite 写的形状） | 9（`r4f1-*`）+ 重新锚定 3 条 `s3-*` | FIXED |
| F2（高，S1 迁移）凭证历史含糊时要补撤销截止点 | `abdf525` | 迁移 `0005` 原来把“某条凭证在下一条签发的同一时刻被撤销”一律当原子轮换、不补截止点；可撤销与重新签发恰好同一时间戳的老暂停（旧网关里暂停不取消排着的投递）也长这样，替换凭证就读得到暂停之前的文字。现在**含糊就补**：(a) 旧的墙钟特征；(b) 某条非第一条凭证紧挨着的上一条（同 Destination、按 rowid）不是在这一刻被撤销（NULL 不算相等）；(c) 同一 Destination 两条凭证签发在同一刻（先后不明）；(d) 该 Destination 有被取消的投递，取消时刻等于某条“后来还签发过凭证”的凭证的撤销时刻（新版网关暂停的痕迹，轮换不取消任何东西）。截止点 = 该 Destination 全部凭证的签发/撤销时间与本 Tenant 全部 Revision 入库时间中最晚的。只有数据能证明的原子轮换（紧挨着的上一条恰好同刻撤销、没有取消痕迹）才不补——这是旧数据能证明的极限，见 16.2 第 1 条 | `tests/integration/test_revocation_cutoff_backfill.py`（14 种凭证历史，在**真的** 0001–0004 迁移建出的库上跑真迁移 SQL；加截止点取值、交错历史、别的 Destination 的取消、真网关进程的升级端到端） | 8（`r4f2-gw-*`，pytest 运行器）+ 重新锚定 4 条旧迁移条目 | FIXED |
| F3（高，S10 的缺口）失焦后读不了剪贴板，守卫永远不清 + F10（低，S11）归属只比第 0 项 | `ac2fdb5` | **F3**：Android 10 起窗口一失焦读剪贴板就是 null，守卫“读不了就不清”于是在分屏点别的窗口、下拉通知栏时永远清不了。产品决定：**没有证明是我们的就绝不清**。多一种不需要读的证明：放进去**之后**登记 `OnPrimaryClipChangedListener`；从复制到失焦那一刻没收到过任何变化，而我们这段时间拿着焦点（系统只把变化回调发给有焦点的应用，别人动剪贴板我们一定收得到），那里就还是我们放的（`ClipboardPeek.Untouched`），`onLeaving` 当场清；收到过任何一次变化就不是了：忘掉、绝不清；监听在每条退出路径（清了、被顶掉、认不出、再复制、忘掉）都摘掉，任何时候至多一个；平台拒绝登记（抛异常）就是没有这条证明。**这条证明只在失焦那一刻有效**：到点的定时器多半跑在后台，没有焦点时系统既不让读也不发回调，沉默什么也证明不了，所以 `expire` 对 `Untouched` 仍不清（记 `clipboard.unverifiable`）。顺序同样要紧：放进去之前先摘旧监听、放进去之后才登记新的，否则自己放的那一下会回调回来，被当成别人动过。**F10**：原来只比第 0 项文字的摘要，别的应用复制的多项内容（第 0 项恰好与凭证相同）会被当成自己的清掉，连带抹掉后面那些别人的东西。现在整份剪贴板：恰好一项、类型只有 `text/plain`、这一项有文字且没有 HTML / URI / Intent、整段文字的 SHA-256 相同才是我们的；真机端口读整份（`ClipData.snapshot()`） | `ClipChangeProofTest`（14；带 Android 10+ 焦点规则与“系统排队、稍后才回调”的假剪贴板，登记顺序错了会被真实地测出来）、`ClipShapeTest`（7）、`ClipChangeWiringTest`（5，读源码钉住：放的顺序、监听适配器的登记与摘除、整份快照、清）；红阶段 21 个里 11 个红 | 19（`r4f3-*` 11、`r4f10-*` 8）+ 重新锚定 8 条（`r6-*`、`s11-*`）= 27 条，全部 CAUGHT | FIXED（真机行为 `NOT_RUN`，16.4） |
| F4（高，S8 唤醒）规划器拿过时快照取消刚登记的叫醒 | F4 提交（见下） | `ResumePlanner` 原来是：流发出一份快照（配对、任务表、授权）→ 带着它去等 `WakeGate` 的锁 → 拿到锁后把这份快照与**刚读的**“欠不欠追加”拼成结论。用户的确认（`WakeGate.ahead`，锁里先登记叫醒再提交）握着锁时规划器停在门口，这段时间账变了，锁一放开，旧快照得出的“没活了”把确认刚登记的叫醒取消；此刻进程被杀，确认过的文字就没人再来发（下一拍新快照会补登记，但被杀发生在这两拍之间）。现在 `ResumePlanner` 多一个 `currentNeed`：结论在门的锁里按磁盘上的账整个现算，三个输入流只当“有东西变了，再算一次”的提示；`SyncRuntime` 把配对、任务表、授权、发送账各读各的存储的 `resumeNeededNow()` 交给规划器，被叫醒那一轮的收尾判断（`wakes.finishing`）用的是同一个函数 | `ResumeStaleSnapshotTest`（6：假账是权威、三个输入流故意慢一拍，门被“确认”握着时账变了——任务表 / 授权 / 配对各一种，每次登记取消之后查“账上有活系统里必须有叫醒”；另有先拿锁再读、账说没活就不登记、不传 `currentNeed` 行为不变）、`ResumeCurrentNeedWiringTest`（3，读 `SyncRuntime` 源码）；红阶段 6 个里 5 个红，红的正是“取消之后：账上有活，系统里却没有叫醒” | 9（`r4f4-*`）+ 重新锚定 `s8-runtime-finishing-skips-the-gate` | FIXED（真 `JobScheduler` `NOT_RUN`，16.4） |
