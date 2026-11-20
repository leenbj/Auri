# Ticket 22 验收：Android 的 Destination 管理与发送方式

范围：在手机上创建 / 查看 / 撤销 Destination（网关上的“收件人”，一个 Agent 一个），创建时**明确选**“自动发送”或“预览确认”；
生成 / 轮换凭证前必须过本机核验（`BiometricPrompt`：强生物识别或锁屏凭据），凭证只显示一次；自动发送的初稿标未审核；
预览确认的 Destination 在用户确认之前不产生 Delivery；每条内容可标“备忘 / 请求执行”；详情页如实显示每条内容在这个 Destination 上
是已入库、已领取还是已确认（**绝不说“已处理”**）。全部走 Ticket 21 的固定公钥 HTTPS 客户端，没有新增网络栈、依赖或网络类。

证据等级：JVM 单测、对着**真实 Python 网关进程**的测试、Python 合同校验都是 E2；没有设备或模拟器（本机为省内存禁用），
所有要设备才能证明的都是 `NOT_RUN`，步骤与命令在第 4、12.4 节。

## 1. 这里有什么

| 文件 | 作用 |
|---|---|
| `destination_contract_check.py`、`destination_support.py` | 校验 Kotlin 测试写出的**手机实际发给网关的请求**（创建请求、入库请求含 `publication`、网关侧观察）满足真实的 `contracts/schemas/` 与语义规则；做法同 Ticket 16/21 |
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
| 4 | 预览确认的 Destination 在用户确认前不产生 Delivery | `DispatchEngineTest`、`SendReviewViewModelTest`（确认前同步跑多少轮网关上都没有这条文字；重复确认恰好一份；确认后进程被杀仍一份）；`DispatchStoreContract`（内存版与 **SQLite 版**共用，JVM 上跑真 SQLite 引擎；重启后仍拦着）；合同输出场景 `preview-held-before-confirm`；真网关 `预览确认：用户确认之前…`（0 条文字、0 个 Delivery、0 个 PUT、Agent 领不到；三次确认之后恰好一份） | E2；Android 自带 SQLite 上的同一套合同 `SqliteDispatchStoreOnDeviceTest` = `NOT_RUN` |
| 5 | 每条内容可标备忘 / 请求执行 | `SendReviewViewModelTest`（发出去之前可改标签；默认是备忘；请求里带的就是最后选的）；`DispatchEngineTest`；`DestinationTextTest`（说明里写明“只是数据上的标记，不会被执行，不授予额外权限”）；真网关 `请求执行只是数据上的标签…`（Agent 领到 `intent=action_request` 与 `content_trust` 标记；只收备忘的 Destination 一条也领不到、整条也没被拒收） | E2 |
| 6 | 详情页每条内容的状态：已入库 / 已领取 / 已确认，绝不说“已处理” | `DeliveryViewTest`（合同的每个投递状态都有对应阶段；不认识的状态是未知）；`DestinationServiceTest`（排队 → 已入库 → 已领取 → 已确认；问不到网关是未知；缺记录是未知）；`DestinationTextTest`（没有一句话说“已处理”）；`DestinationDetailViewModelTest`；真网关 `自动发送：…领取、确认之后手机上依次显示已领取、已确认` | E2 |
| 7 | 真机：创建 Destination，Agent 领到自动发送的文字 | 第 4 节的步骤；电脑上的等价证据是第 3 项的真网关测试（真实 MCP 客户端、真实网关，但不是真机、不是真局域网） | `NOT_RUN`（原因：没有设备；本机为省内存禁用模拟器） |

## 3. 在本机怎么跑

先 `source ~/.config/auri/android-env.sh`；多个工作树共用机器时先取共享锁 `~/.cache/auri/gradle.lock`，一次只跑一个 Gradle / pytest，Gradle 加 `--no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2`；**不要用 `./gradlew --stop`**。

```bash
cd apps/android
./gradlew :sync:test :syncplatform:testDebugUnitTest :app:testDebugUnitTest   # JVM 单测（数字见第 12.2 节）；输入没变会 UP-TO-DATE，要新鲜结果加 --rerun
./gradlew :sync:realGatewayTest      # 对着真实的 Python 网关与官方 MCP 客户端（含 Ticket 21 的 12 个和本票的 9 个）；找不到 .venv 就失败，不跳过
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
2. **凭证（清单 2）**：点“查看”→“生成凭证”。期望：弹系统的指纹 / 人脸 / 锁屏核验；**取消**或核验失败 → 页面说明没有生成凭证，网关上没有凭证；没有登记任何生物识别也没有锁屏的设备 → 明说没法核验。通过后页面只显示一次凭证；**截图应当被系统挡住（`FLAG_SECURE`）**：`adb exec-out screencap -p > /tmp/auri-shot.png` 得到黑屏或失败。点“复制并关闭”后凭证面板消失、再也找不回；把凭证放进电脑：`export AURI_DESTINATION_CREDENTIAL='<粘贴>'`。
   - 显示凭证时按 Home（或锁屏）再回来：面板必须是空的（退到后台就抹掉）。显示凭证时杀掉进程（`adb shell am force-stop app.auri.android`）再进来：同样是空的。
   - 凭证不在设备上：`adb shell run-as app.auri.android sh -c "grep -rl '<凭证前 12 个字符>' . ; echo done"` 只应打印 `done`；`adb logcat -d | grep -c '<凭证前 12 个字符>'` 应为 0（连同别的应用的日志一起查）。
   - 再点“轮换凭证”：先说清旧凭证立刻失效、要你确认，确认后**再次**核验；轮换后 `real_gateway_agent.py probe`：旧凭证 `{"status": 401, "code": "GATEWAY_AUTH_REVOKED"}`，新凭证 `{"status": 200, "code": null}`。
3. **自动发送与 Agent 领取（清单 3、7）**：用耳机录一句话，等转录完成（Ticket 14/16/20 的流程）。期望：不用点任何东西；电脑上
   ```bash
   .venv/bin/python execution/acceptance/android-destinations/real_gateway_agent.py claim
   ```
   输出的 `payload.deliveries` 里有这句话，`review_state` 是 `not_reviewed`，带 `delivery_id` 与 `lease_token`；手机详情页该条显示“Agent 已领取，还没回确认”。再
   ```bash
   .venv/bin/python execution/acceptance/android-destinations/real_gateway_agent.py ack <delivery_id> <lease_token>
   ```
   刷新后手机显示“Agent 已确认（网关只知道它回了确认）”；全程没有任何一处写“已处理”。
4. **预览确认（清单 4）**：用“预览确认”的 Destination 的凭证再录一句。期望：“待发送与标签”里这条标着“等你确认”；电脑上 `claim` 返回空的 `deliveries`；点“确认发送”（连点几下）后 `claim` 恰好领到一条。点“不发送”的那条永远领不到。
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
- **凭证只存在一个地方**：网关响应 → 一个 `toString` 永远脱敏的包装类型 → 一个只在内存里的“正在显示”状态。复制、关闭、离开页面、退到后台都抹掉它；等待期间页面离开了，之后才到的凭证不显示。不进日志、不进保存的实例状态（界面里唯一保存的是“现在在哪一页”）、不进磁盘、不进备份；网关库里也只有哈希。
- **预览确认靠本地账拦住整条文字**：网关只在一个 (record, revision) **第一次入库**时创建 Delivery（缺口 G13），所以不能“先发给自动的、确认后再补预览的”。手机在发布 Revision 的**同一个事务**里按当时的目的地缓存定下目标快照，只要还有等用户决定的预览目标，就不创建同步任务；最后一个决定做完才放行，一次请求带齐所有目标。决定是终局，重复确认认出“已经决定过”。
- **`publication` 在发送那一刻现问发送账**（不是发布时）：被撤销或不接收这个意图的目的地不进去，否则网关会把整条 422 永久拒绝。
- **状态只说网关证明得了的**：已入库（`PENDING`）、已领取（`LEASED`）、已确认（`ACKNOWLEDGED`），外加已拒收、已取消；不认识的状态值、问不到网关、网关答非所问、缺记录各自明说“未知”，不当成功。
- **意图标签只是数据**（SEC-004）：`action_request` 不授予任何额外权限，网关交给 Agent 时带着 `content_trust` 标记；界面文案里写明。
- **日志只有事件名与异常类名**：没有凭证、令牌、正文、目的地名字与编号、地址。

## 6. 假设（做决定时我没有问人，下面每一条都可以推翻）

1. **预览确认放行的仍是未审核的初稿**：用户点“确认发送”不等于他审阅并改过正文，请求里 `review_state` 仍是 `not_reviewed`（合同也没有办法在“确认发送”时标成已审核，见 G14）。
2. **退到后台（`ON_STOP`）就抹掉正在显示的凭证**，比“离开页面”更严：回来时看到的与进程被杀后一样是空的。凭证还在路上时不抹（网关已签发，轮换时旧的已失效，抹了就找不回来）。
3. **剪贴板里的凭证不自动清除**：复制时标了“敏感内容”（系统不在预览里显示），但没有定时清空；用户自己粘贴后清掉。这是已知限制（第 10 节），要不要定时清空是产品决定。
4. **界面上“未审核”是常量**：V1 里没有任何代码能产生 `review_state=reviewed` 的初稿（只有枚举值），所以发送账里的每一条都是未审核初稿，常量是如实的。将来有审阅功能时，必须改成从 Revision 的 `review_state` 派生。
5. **核验允许“强生物识别或锁屏凭据”**（`BIOMETRIC_STRONG or DEVICE_CREDENTIAL`），用框架自带的 `android.hardware.biometrics.BiometricPrompt`，没有新增依赖（minSdk 够用）。设备上什么都没设置 → 不生成凭证并明说。
6. **只有 ASR 初稿（第 1 版）走自动发送 / 预览确认**：用户修订、重新转录出的新版本只入库（ADR-009 授权的是“转录出来的文字发给这个 Agent”；修订要不要自动重发是单独的决定）。
7. **意图标签发出去之后不能改**（网关上一个版本的意图不可更改）；发出去之前随时可改；“新录的文字默认标成什么”由用户设，只影响之后新录的。
8. **创建没有幂等键，所以绝不自动重试**：结果不确定（响应丢了、读不懂）时让用户先“刷新”看列表，不叫人直接重试（会多出同名目的地）。有效目的地到 50 个（合同上限）就不让再建。
9. **本机缓存可能比网关旧**（别的设备撤销了某个目的地）：见第 7 节 G16 与第 10 节。
10. **TDD 的接缝没能先问人**：被派发的 worker 没有人可问，接缝定在“`DestinationController` / `DispatchStore` / `LocalAuthenticator` 接口、三个视图模型的 `state`、网关请求 / 响应”，测试都写在这些接口上。
11. **没有经过独立评审**：这次实现由单个 worker 完成，没有另一个评审者（或评审工具）看过。替代证据只有第 8 节的变异验证与真网关测试；合入前仍应有人评审，尤其是第 5 节的发送账事务与凭证只显示一次的几处。
12. **前四个提交不是这一轮写的**：`6971bee`、`34a3a9b`、`6fc8331` 与不能编译的在制品 `62c0f8c` 是被 Orca 重启打断的前几轮留下的。我接手后让它们编译、补齐测试，没法证明那几处当初是先红后绿写成的，所以用第 8 节的 30 条变异证明这些测试在规则被改坏时真的会变红；这一轮新写的部分按检查项先写测试再写实现。

## 7. 合同与网关的缺口及建议（没有改 `contracts/` 与 `services/gateway/`，只记录）

编号接着 Ticket 21 的 G7–G12。

| # | 缺口 | 位置 | 建议 |
|---|---|---|---|
| G13 | 网关只在一个 (record, revision) **第一次入库**时按 `publication.destination_ids` 创建 Delivery；同一版再 PUT 只回 `duplicate: true` 与**已有**的 `deliveries`，补不上新目标（`services/gateway/auri_gateway/store.py` 的 `existing` 分支）。后果：预览确认不能“先发给自动的、确认后补预览的”，只能整条等到最后一个决定，一次请求带齐；也没办法把已入库的文字补发给之后才创建的 Destination | `revision.schema.json#/$defs/submission/properties/publication`；网关 `PUT /v1/records/{id}/revisions/{n}` | 增加“给已有版本补投递”的操作（例如 `POST /v1/records/{id}/revisions/{n}/deliveries`，只增不减、幂等），或让重复 PUT 带来的 publication 作为增量处理。现在不需要改，手机端的“整条等”是不改网关的唯一做法 |
| G14 | 合同里没有任何字段能表达“这段文字发出之前有人看过 / 用户点过确认”。预览确认放行的文字，Agent 看到的 `review_state` 仍是 `not_reviewed`，和自动发送的没有区别 | `revision.schema.json` 的 `review_state` 与 `publication` | 若产品希望 Agent 区分“用户预览确认过”与“自动发送”，需要新字段（例如 `publication.confirmed_by_user`）或把确认当作一次 `reviewed` 的新 Revision；否则这就是 V1 的约定 |
| G15 | 创建 Destination 没有客户端生成的稳定操作编号（`POST /v1/destinations`）。响应丢了之后重发会多出同名 Destination | `destination.schema.json#/$defs/create_request` | 合同 1.2 可选加 `client_request_id`，网关在窗口内幂等。现在手机端靠“不自动重试 + 让用户先刷新”绕开 |
| G16 | 提交里有一个已被撤销（或已不存在、或不接收该意图）的目标，网关把**整条**以 `422 REQUEST_INVALID` 拒收，错误消息不指明是哪一个。手机缓存可能比网关旧（别的设备刚撤销了某个 Destination），整条会停在“需要用户处理” | `store.py` 的 `_validate_publication_destinations` | 在错误体的 `details` 里带上有问题的 `destination_ids`，手机据此刷新缓存后重新规划再发；或网关对已撤销的目标跳过并在响应里说明 |
| G17 | 状态查询只给每个 Delivery 的 `state`：没有“哪个 Agent 领的、租约何时到期、确认备注”，详情页只能说“Agent 已领取，还没回确认”，没法说“租约还剩多久 / 过期会重新投递” | `GET /v1/records/{id}/status` 的 `destinations[]` | 可选：补 `lease_expires_at`（不含 token）。不是缺陷 |

另：没有发现合同与网关行为不一致的地方——真网关测试里创建（两种模式）、凭证（签发 / 轮换，旧凭证 `GATEWAY_AUTH_REVOKED`）、入库（含 `publication`）、MCP 领取与确认、状态查询、撤销（待领取的投递变 `CANCELLED`）都按合同工作。

## 8. 变异检查：故意写错，确认测试会红

只写“通过的测试”不能证明测试拦得住错误。`destination_mutation_check.py` 把“我破坏了什么、哪些测试必须因此失败”写成一张表，机器与 Ticket 21 的 `android-sync/mutation_check.py` 是同一套：**只在仓库外的快照里改源码，真实工作树一个字节都不动**（`/tmp/auri-ticket22-mutation-snapshot`，跑完删），应用 → 跑 Gradle → 还原；每条改动的原文必须恰好出现一次，找不到就是表无效（退出码 2），预期会红的那些测试名**每一个**都必须出现在失败的用例里（否则判“没被拦住”，退出码 1）。表自己的离线检查（`test_destination_mutation_table.py`，不跑 Gradle）确认每条原文真实存在、每个预期的测试名真实存在，并把清单 1–6 项逐项映射到至少一条变异。

共 30 条变异，**全部被预期的测试拦住**（2026-11-20 在本机逐条真跑；“变红数”是该次运行里红的测试数 / 该次运行选中的测试数；名称片段对应的测试名见第 2 节与源码）：

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
2. `dispatch-preview-not-held`、`dispatch-ignores-eligibility` 一开始改在 `:sync` 的单测上，要么全绿、要么没拦住预期的测试：真正拦住文字、决定谁进 `publication` 的生产代码是 SQLite 发送账（`DispatchSql.releaseIfReady` / `publicationFor`），`:sync` 的单测与真网关测试用的是内存实现（价值在“网关一侧看到什么”）。已改成跑 `:syncplatform` 里的 SQLite 合同测试（JVM 里跑真 SQLite 引擎）。改完之后 `dispatch-ignores-eligibility` 仍有一个预期的测试没红，查出来是**测试缺口**：合同里没有“授权了自动发送、却不接收这个标签的目的地不能进 `publication`”这一条（原来的“不接收标签”用例用的是预览确认的目标，它本来就收不到，变异碰不到它）。放进去网关会把整条 422 永久拒收，文字就丢在手机上了，所以补了合同用例 `an_auto_send_destination_that_does_not_accept_the_intent_is_not_in_the_publication_and_does_not_block_the_others`（内存版、SQLite 版、设备端版共用同一份），内存版与 SQLite 版都绿，重跑变异后它红了。原来的两条“真网关版”（`real-gw-preview-not-held`、`real-gw-publication-ignores-eligibility`）因此**删掉**：那一层根本没有能被变异的生产拦截逻辑，留着只会是一条永远活着的假变异。真网关层对预览确认与标签的保障是：确认之后恰好一份、网关一侧确认前什么也没有（见第 2 节），再加 `real-gw-source-ignores-publication`。
3. `status-unreachable-shown-as-stored` 一开始改的是 `remote == null` 那条防御分支，它在正常流程里到不了（等价变异）。已改到真正会走的 `Remote.Unusable` 分支。

## 9. 合同输出校验的场景

`:sync` 的 `DestinationContractOutputTest` 用真实的视图模型、服务与同步引擎，把手机实际发给网关的请求按场景写进目录（Gradle 属性 `-PauriContractOut=<目录>`，默认在 `sync/build/contract-out/jvm`），`destination_contract_check.py --preset jvm` 严格检查：这六个场景一个不能少、不能多。

| 场景 | 写出的东西 | 检查 |
|---|---|---|
| `create-auto-send` / `create-preview-confirm` | `create-request.json` | 按 `destination.schema.json#/$defs/create_request` 校验；`send_mode` 恰好是场景名对应的取值 |
| `auto-send-unreviewed` | `submission.json` | 按 `revision.schema.json#/$defs/submission` 校验；`content.review_state=not_reviewed`；`publication.destination_ids` 就是那个目的地；SR-01/05/06 |
| `preview-held-before-confirm` | 只有 `scenario.json` | **没有** `submission.json`；网关侧观察到 0 个 PUT、0 个 Delivery |
| `preview-after-confirm` | `submission.json` | 用户确认之后才有；含确认的那个目的地；仍是未审核初稿 |
| `action-request-tag` | `submission.json` | `publication.intent=action_request`（只是数据上的标签） |

`test_destination_contract_check.py` 证明校验器自己拦得住坏样本（缺 `send_mode`、取值不在合同里、该有 `publication` 的没有、`held` 场景里却有请求、报错不回显输出文件里的值……）。

## 10. 已知限制（没做、做不到或需要产品决定的）

- **真机行为全是 `NOT_RUN`**：真实 `BiometricPrompt` 的弹出与各种结果、`FLAG_SECURE` 对截图与最近任务的效果、剪贴板敏感标记、`ON_STOP` 在真实 Activity 生命周期里的触发、Android 自带 SQLite 上的发送账合同（`SqliteDispatchStoreOnDeviceTest`）、进程被系统杀掉后的发送接力、整条链在局域网真机上的样子。JVM 测试证明了逻辑，不证明这些平台行为。
- **剪贴板里的凭证清不掉**：复制后留在系统剪贴板，直到被别的复制覆盖（Android 10 起后台应用读不到也清不了剪贴板）。已标“敏感内容”（该标记是 API 33 才有的常量，旧系统会忽略它；剪贴板预览是否显示、各系统版本的实际表现都没在真机上看过，`NOT_RUN`）。界面上凭证复制后立刻抹掉，并提示马上粘贴给 Agent。
- **“核验”不是“机主确认”的全部**：通过的是系统的强生物识别或锁屏凭据；手机若被人解锁着递给别人，核验拦不住。这是系统核验的边界，不是本票能解决的。
- **本机缓存可能比网关旧**（缺口 G16）：别的设备撤销了某个 Destination 后，本机在下次“刷新”或同步前不知道；手机在发送那一刻只排除**本机已知**已撤销的。网关会把含已撤销目标的整条 422 拒收，那条文字会停在“需要用户处理”，刷新缓存后重新规划才能发。
- **预览确认的粒度是整条文字**（缺口 G13）：同一条文字上有“自动发送”和“预览确认”两类目标时，自动发送的也要等预览的决定做完才一起发出。没有办法把已入库的文字事后补发给新建的 Destination。
- **创建 Destination 没有幂等键**（缺口 G15）：结果不确定时只能让用户先刷新再决定，不自动重试。
- **详情页只列最近 20 条**，每条已入库的文字向网关问一次状态（一次刷新最多 20 个请求，刷新有最小间隔）；更早的条目不显示状态。
- **撤销 Destination 不可逆**：撤销后凭证立刻失效；按合同状态机，还没确认的投递（待领取的、已领取的）都变成已取消，已确认的不变。本票的真网关测试只覆盖“待领取的被取消”这一种；“已领取后被撤销”靠合同状态机的转移表，没有单独的端到端用例。
- **V1 没有“用户审阅并修改正文”的功能**，所以没有 `reviewed` 的初稿，也就没有“审阅后再发”的路径；见假设 4。
- **没有通知**：Agent 领走了或确认了，手机不会提醒，要打开详情页（或下拉刷新）才看到。

## 11. 依赖与权限

- **没有新增任何第三方依赖**：`gradle/libs.versions.toml` 没改；`:sync` 的测试固件被 `:app` 的单测引用（`testImplementation(testFixtures(project(":sync")))`）、`:sync` 的 `build.gradle.kts` 多了一个把 Gradle 属性 `auriContractOut` 转给测试进程（系统属性 `auri.contract.out`，与别的合同输出测试同名）的设置。生物识别用的是系统框架类 `android.hardware.biometrics.BiometricPrompt`（minSdk 31），没有引 `androidx.biometric`。
- **没有新增网络类、没有新增 `INTERNET` 声明**：所有联网都走 Ticket 21 的 `PinnedHttpsClient`（`NoNetworkCodeGuardTest` 的白名单没改，照样绿）。
- **新增一个权限 `USE_BIOMETRIC`**（`:syncplatform` 清单；普通权限，安装时自动授予，不读取任何生物特征数据）。

## 12. 本次运行结果

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

### 12.5 共享文件与既有文件的改动（都是追加式的；不碰 `contracts/`、`tests/contract/`、`services/gateway/`、`.github/`）

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

