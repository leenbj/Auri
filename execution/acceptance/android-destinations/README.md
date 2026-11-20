# Ticket 22 验收：Android 的 Destination 管理与发送方式

范围：在手机上创建 / 查看 / 撤销 Destination（网关上的“收件人”，一个 Agent 一个），创建时**明确选**“自动发送”或“预览确认”；
生成 / 轮换凭证前必须过本机核验（`BiometricPrompt`：只认强生物识别，锁屏密码不行），凭证只显示一次；自动发送的初稿标未审核；
预览确认的 Destination 在用户确认之前不产生 Delivery；每条内容可标“备忘 / 请求执行”；详情页如实显示每条内容在这个 Destination 上
是已入库、已领取还是已确认（**绝不说“已处理”**）。全部走 Ticket 21 的固定公钥 HTTPS 客户端，没有新增网络栈、依赖或网络类。

证据等级：JVM 单测、对着**真实 Python 网关进程**的测试、Python 合同校验都是 E2；没有设备或模拟器（本机为省内存禁用），
所有要设备才能证明的都是 `NOT_RUN`，步骤与命令在第 4、12.4、13.3 节。

**评审修复轮（2026-11-21）**：独立评审提出 B1–B6、H1–H4、M1–M4 共 14 条发现，每条一个提交、先红后绿、推送一次，见**第 13 节**（发现表、变异、要在设备上核对的、最新验证结果、假设）。第 1–3、5–10 节里被它们取代的说法已就地改正（第 8 节的 30 条变异表是原始实现时的历史记录，其中 3 条已随 B2 删除，见 13.4）；第 12 节的数字是修复之前的历史记录，最新结果在 13.5。

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
   - 剪贴板（评审 B5）：点“复制并关闭”后马上退到另一个应用，在那里粘贴：应当粘得出凭证（复制那一下没有把它当场删掉）；**65 秒后**再粘贴：应当是空的，`adb logcat -d -s AuriSync:I | grep clipboard` 应有 `clipboard.copied` 与 `clipboard.cleared`。再来一次：复制后在别的应用里复制一句别的话，**回到本应用的前台**待到 60 秒——别的话应当还在，日志是 `clipboard.kept-other`。又一次：复制后 `adb shell am force-stop app.auri.android`，65 秒后重新打开应用：凭证应被清掉（回到前台补查）。
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
3. **复制出去的凭证 60 秒后从剪贴板清掉，只清自己放的那一份**（评审 B5，`SecretClipboardGuard`）。评审要求“面板关闭、退到后台、到时间”三个时刻都清；我只做了“到时间”，**没有**在后台那一刻清：凭证复制出去的唯一用处是粘贴到别处，几乎总要退出本应用（而且复制的那一下面板本来就关了），一退后台就清，等于把刚复制的东西当场删掉，用户只能再轮换一次。要不要在那两个时刻也清是产品决定（改法：在退后台的回调里调 `clear()`）。后台时 Android 不让读剪贴板，认不出归属就照清（宁可多清一次，也不让凭证留着）；读得到时按标记认，别的应用后来复制的东西不动。
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
| G13（**已关闭**，评审 B2） | 网关只在一个 (record, revision) **第一次入库**时按 `publication.destination_ids` 创建 Delivery；同一版再 PUT 只回 `duplicate: true` 与**已有**的 `deliveries`，补不上新目标（`services/gateway/auri_gateway/store.py` 的 `existing` 分支）。后果：预览确认不能“先发给自动的、确认后补预览的”，只能整条等到最后一个决定，一次请求带齐；也没办法把已入库的文字补发给之后才创建的 Destination | `revision.schema.json#/$defs/submission/properties/publication`；网关 `PUT /v1/records/{id}/revisions/{n}` | 增加“给已有版本补投递”的操作（例如 `POST /v1/records/{id}/revisions/{n}/deliveries`，只增不减、幂等），或让重复 PUT 带来的 publication 作为增量处理。**已关闭**：评审 B2 照这条建议在网关加了该端点（合同仍是 1.1），手机端不再“整条等”。 |
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
- **剪贴板里的凭证只有 60 秒，且有三处认不全**（评审 B5）：① 进程在 60 秒内被系统杀掉，定时器随之没了，下次窗口拿到焦点时才补查（标记里带着放进去的时刻），在那之前凭证还留在剪贴板里（Android 13 起系统自己约一小时后清，更早的版本只等被覆盖）；② 后台到点时 Android 不让读，认不出归属就照清，所以用户在那 60 秒里于别处复制的东西可能被一并清掉；③ 敏感标记（预览里不显示）是 API 33 才有的常量，旧系统忽略它。`clearPrimaryClip` 是 API 28 才有的，应用 minSdk 31，没有缺它的系统（`ClipboardWiringTest` 钉着 minSdk ≥ 28，谁调低它这条先红）。后台能否写与清剪贴板、描述里的标记能否读回，都没在真机上看过，`NOT_RUN`（第 4 节）。
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
| B2 阻断：预览拦住自动发送 | `9e9ee03` | 网关新增 `POST /v1/records/{id}/revisions/{n}/deliveries`（给已入库的不可变 Revision 追加 Delivery，幂等，来源设备专用）；手机：同步任务在发布 Revision 的事务里一定创建、不等任何决定，自动目标随第一次 PUT 建 Delivery，预览目标在确认后由引擎追加（每个目标一本账 `NOT_YET/CREATED/REFUSED`，失败有界退避，重启后补做）。合同仍是 1.1（只加可选端点与两个消息，既有样本一个字没动，细节见 13.6） | 网关 `test_delivery_additions.py`（11 个，红时 11/11）；真网关 2 个（自动与预览并存、预览确认后追加）红时失败；`DispatchEngineTest`、`DispatchStoreContract`、`DispatchSqlTest`、`DeliveryAdditionsLoopTest`、`ResumePlannerTest`、`SendReviewViewModelTest` 按新语义重写 | 11 条新增（含 M3 的 3 条）；原 30 条里 3 条因旧语义消失而删除，见 13.4 | E2 |
| B3 阻断：撤销凭证不停投递 | `e6bebd6` | 撤销**最后一条有效凭证** = 该 Destination “暂停”：撤销那一刻 `PENDING`/`LEASED` 的 Delivery 取消（沿用状态机的 `DESTINATION_REVOKED`，不新增状态）；暂停期间提交里点名它不报错、只是不建 Delivery；签发新凭证才恢复，且只对之后入库的文字生效，之前和暂停期间的文字永远不会补给新凭证。Destination 资源多一个可选字段 `credential_active`（合同仍是 1.1）。手机读它、不为暂停的目的地规划新文字、列表和详情页显示“已暂停” | 网关 `test_credential_suspension.py`（7）、`DestinationClientTest`、`DestinationServiceTest`（4）、`DispatchStoreContract`（6，内存与 SQLite 共用）、`DispatchSqlTest`、应用的列表/详情/文案测试、两条真网关用例（经网关命令行撤销凭证）；Ticket 07 的一条断言被**有意翻转**（见 13.2 第 2 条） | 14 条新增 | E2 |
| B4 阻断：PIN 能通过核验 | `248c882` | 只要 `BIOMETRIC_STRONG`，去掉 `DEVICE_CREDENTIAL`；没登记生物识别就不弹框、不生成凭证并明说。策略搬进 `BiometricGate`（接缝是小接口 `BiometricFramework`），`BiometricLocalAuthenticator` 只剩 Android 适配；没有设备凭据后系统要求自带“取消”按钮，已加并映射成“已取消” | `BiometricGateTest`（“只有 PIN 的手机”只在请求里带 `DEVICE_CREDENTIAL` 时才通过，所以应用请求什么决定测试结果；红于旧的掩码）、`DestinationTextTest` | 3 条新增 | E2；真实弹框与取消按钮 `NOT_RUN`（第 4 节第 2 步，13.3） |
| B5 阻断：复制的凭证不清 | `404504b` | `SecretClipboardGuard`：复制时标记为敏感并在描述里记下标记与时刻（不含凭证）；60 秒到点只清自己放的那一份，别的应用后来复制的东西不动；再复制重新计时；后台读不到时照清；进程被杀靠窗口拿到焦点时补查；面板写真实的秒数 | `SecretClipboardGuardTest`（8 个，红时 6 个；假剪贴板按 Android 规则：后台读不到、写与清仍可）、`ClipboardWiringTest`（钉住 minSdk ≥ 28） | 9 条新增 | E2；剪贴板适配器 `NOT_RUN`（第 4 节） |
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
3. **B5 偏离评审的措辞**：评审要求面板关闭、退后台、到时间三个时刻都清；只做了“到时间”（60 秒），**没有**在关闭和退后台那一刻清：复制本身就会关面板，唯一的用处是粘贴到别的应用，几乎总要退出本应用，一退后台就清等于把刚复制的东西当场删掉、用户只能再轮换一次。要不要在那两个时刻也清是产品决定（改法：在退后台的回调里调一次 `clear()`）。
4. **B6 在途请求在退后台或离开时作废**，已签发的凭证提示轮换（网关里它已经存在）；这推翻了旧规则“在途的凭证到达后照样显示”，对应的旧测试改成断言相反的结果（凭证仍然不显示、不保留，用户现在被告知）。
5. **H2 是“撤回”不是“重新规划”**：重新配对到另一个网关时，没有 Delivery 的目标一律撤回；仍在等、或已存到旧网关的文字在新网关上无目标入库，不会自动投给新网关的 Destination（那不是用户给过的授权）。
6. **H3 只有撤销是单调的**（`revoked` 不会被较早的快照改回）；创建与签发靠往返串行化。
7. **H4 只治本票的三个视图模型与 `AuriRuntime` 的作用域**。本票之外的 `SyncResumeService`、`ControlWiring` 各有一个自建作用域没有异常处理器，**没动**；Home、Sync、Models、Control 这些视图模型读的是内存状态流，**没有逐个审计**。
8. **M1 的等确认列表不分页、不设上限**：每一条都是一个等用户的决定，截断就是 M1 本身。
9. **M2 的标题“已同步”不改**（文字确实都在网关里）；“用户已确认、但网关上 Delivery 还没追加完”的欠账不进标题（残余）；条数用“读出再数”（`SyncStatus.watch` 上有 shortcut 注释，列表很长时换 `COUNT` 查询）。
10. **M4 用网关的 `409 REQUEST_INVALID`，不新增错误码**（增加错误码属于不兼容改动，要升版本），合同仍是 1.1，只加一条网关规则；创建端点只有这一个原因回 409，所以手机把它映射成 `LimitReached`，别的拒绝（例如 422）照旧是普通拒绝。**旧的手机侧上限测试被替换**：它的前提是“本机缓存是裁判、请求没有发出”，评审把这个前提反过来了，换成三个反前提的用例。变异表自检的“只许改生产代码”规则扩到网关包 `services/gateway/auri_gateway/`（`tests/`、`*Test.kt`、`androidTest`、`testFixtures` 仍然禁止）。
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
| B5 剪贴板 | 第 4 节第 2 步“剪贴板”那一条的四种做法 | 65 秒后空；别的应用复制的东西不被清；杀进程后重进补清 |
| B6 面板代数 | 第 4 节第 2 步“生成凭证的过程中”那一条 | 核验框弹着期间页面不收到 `ON_STOP`；网关回答前按 Home 再回来：面板空，随后“已丢掉，请轮换” |
| H1 唤醒 | 预览确认后立刻 `adb shell am force-stop app.auri.android`；`adb shell dumpsys jobscheduler \| grep -B2 -A6 app.auri.android` | 欠着追加时有已登记的任务；系统叫醒后 `claim` 领到确认过的那条。自动化版本是 `ResumeSchedulingTest`（`androidTest`，要设备） |
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
| `contracts/README.md` | +4/−1（B2、B3、M4） | “另有几条规则依赖网关的状态”下的三条语义规则：追加 Delivery 的端点、凭证被撤销 = Destination 暂停、一个 Tenant 至多 50 个有效 Destination；三条都留在 1.1（只加可选端点/可选字段/网关规则，没有新错误码、必填项或状态机改动） |
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
