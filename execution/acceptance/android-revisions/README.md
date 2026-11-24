# Ticket 23 验收：Android 的修订、重发与删除

范围：在手机上修改转录文字，每次保存产生新的不可变 `Revision`（旧版本保留可回看）；新 `Revision` **默认不自动重发**，界面展示哪些 `Destination` 收到过旧版本，由用户逐个确认重发；支持删除整条 `Record`（本地与网关都种下墓碑、取消未领取投递、离线补传不复活），界面明确告知 **Agent 已读走并另存的内容无法收回**；支持单独删除本地音频而保留文字与历史版本，可设置音频保留期（默认转录成功后 30 天）；支持按时间、关键词、发送状态筛选，并由用户主动导出为 TXT 或 Markdown（文件名不含正文等敏感信息）；日志默认不含正文、音频与凭证。

证据等级：JVM 单测、对着**真实 Python 网关进程 + 官方 MCP 客户端**的测试、SQLite 迁移与触发器测试、变异检查均为 **E2**；本机未启动模拟器、无真机连接，所有依赖真机硬件/系统 UI 的行为标为 `NOT_RUN`，操作步骤见第 4 节。

## 1. 这里有什么

| 文件 | 作用 |
|---|---|
| `revision_mutation_check.py` | 15 条变异检查：只在仓库外临时快照 `/tmp/auri-ticket23-mutation-snapshot` 中改源码，逐条验证预期测试变红（全部 15/15 CAUGHT） |
| `test_revision_mutation_table.py` | 变异表离线自检（17 个测试）：确认编号唯一、覆盖清单 1–7 项、每条原文在源码中恰好出现一次 |

代码分布在 `apps/android/`：
- `:capture`：`RecordLibrary`（`deleteRecord`/`isDeleted`/`deleteAudio`/`RecordDeletedException`）、`AudioRetentionCleaner`（默认转录成功后 30 天）、`OrphanRecovery`（孤儿恢复尊重墓碑，不复活已删除 Record）
- `:platform`：`SqliteRecordLibrary`（schema v2→v3 迁移、`record_tombstones` 表、仅允许有墓碑时删除 `revisions` 的触发器）、`AudioRetentionSetting`（1–365 天原子持久化，默认 30 天）
- `:sync`：`GatewayClient.deleteRecord`（`DELETE /v1/records/{id}`）、`PendingDeletions`、`SyncEngine.syncDeletions`（受 `DataFlowPolicy` / `ContractGuard` / `PairingStore` 围栏保护）、`SyncLoop.watchDeletions`、`ResumePlanner` 墓碑唤醒、`DispatchStore.requestResend`、`WakeAheadDispatchStore.requestResend`、`RecordRevisionService`、`RecordFilter`、`RecordExporter`
- `:syncplatform`：`SqlitePendingDeletions`、`DispatchSql.requestResend` / `DispatchSql.deleteRecord`、`SqliteSyncJobStore.backfill()`（排除墓碑 Record）、`SyncRuntime` 装配
- `:app`：`RevisionDescriptions`、`RecordsViewModel`、`RecordDetailViewModel`、`RecordsPage`、`AppRoot`（新增“记录”页签）、`MainActivity`（前台恢复时执行音频保留期清理）、`RevisionLogPrivacyGuardTest`

## 2. 清单逐项对照

| # | 清单项 | 覆盖它的测试 | 证据 |
|---|---|---|---|
| 1 | 每次保存产生一个新的不可变 Revision，旧的可回看 | `RecordLibraryContract`（不可变递增编号、旧版原样保留）；`RecordTombstoneSqlTest`（`revisions_immutable_update` 拒绝任何 UPDATE，无墓碑拒绝 DELETE）；`RecordRevisionServiceTest`（保存产生 `USER_EDIT`/`REVIEWED` 新版，空文本与 >1 MiB 被拒）；`RevisionsViewModelTest`（草稿修改不产生 Revision，保存才产生）；真网关 `保存修订产生新 Revision 且不自动重发…` | E2 |
| 2 | 新 Revision 默认不自动重发；界面显示哪些 Destination 收到过旧版本，由用户逐个确认重发 | `DispatchStoreContract`（`a_user_edit_revision_is_never_planned_or_held_it_is_only_stored`、`requestResend_uses_stored_item_intent_rejects_refused_and_missing_jobs_and_survives_reopen`）；`WakeAheadResendTest`（先登记叫醒再落账）；`RecordRevisionServiceTest`（展示收到旧版本的 Destination，逐个确认重发幂等）；真网关 `保存修订产生新 Revision 且不自动重发；逐个确认重发后只给目标建 Delivery 并取消其未领取的旧版本` | E2 |
| 3 | 删除 Record 后本地和 Gateway 上都不可见，离线补传不会让它复活 | `RecordLibraryContract`（本地墓碑、幂等、`addRecord`/`publishRevision` 防复活）；`RecordTombstoneSqlTest`（v2→v3 迁移与墓碑表）；`SyncJobsBackfillTest`（墓碑 Record 不被回填复活）；`GatewayClientDeleteTest`（`DELETE /v1/records/{id}` 合同解析与 403 映射）；`SyncEngineDeletionTest`（在线同步、严格仅本地门禁、离线恢复补发）；真网关 `删除 Record 后本地与真实网关都不可见，离线补传返回 RECORD_DELETED 且绝不复活` 与 `从未上传过的 Record 在离线删除后同步：网关种下墓碑，后续离线补传无法复活` | E2 |
| 4 | 界面说明 Agent 已读走的内容无法收回 | `RevisionLogPrivacyGuardTest`（断言 `DELETE_RECORD_NOTICE` 明确说明 Agent 已读走的内容无法收回，且不含“撤回所有”“已处理”）；`RevisionsViewModelTest`（详情页状态暴露该说明） | E2 |
| 5 | 可单独删除音频而保留文字；可设置音频保留期，默认转录成功后 30 天 | `RecordLibraryContract`（`deleteAudio` 清空 audio 保留 Revision 且不产生墓碑）；`AudioRetentionCleanerTest`（默认 30 天、只清理 `DONE`、以首次 ASR 时间起算、单条失败隔离）；`AudioRetentionSettingTest`（默认 30 天、1–365 天持久化、损坏回退 30 天）；`RecordRevisionServiceTest`（文件删不掉时不清空元数据）；`RevisionsViewModelTest` | E2 |
| 6 | 可按时间、关键词、发送状态筛选；可导出为 TXT 和 Markdown | `RecordFilterAndExportTest`（时间区间、大小写不敏感关键词含历史修订、全部发送状态枚举、TXT/Markdown 格式化、安全文件名绝不含正文与耳机名）；`RevisionsViewModelTest`（用户主动触发导出与消费） | E2；系统 SAF 文件选择器写入 = `NOT_RUN` |
| 7 | 日志默认不含正文、音频和凭证 | `RecordRevisionServiceTest`（断言日志不含正文与目的地名）；`RevisionsViewModelTest`（断言日志不含正文）；`RevisionLogPrivacyGuardTest`（静态扫描本票全部新增生产源码的日志调用，禁止插值 `text`/`draft`/`audio`/`path`/`credential`/`token`/`displayName`/`destinationName` 等） | E2；真机 `adb logcat` 复核 = `NOT_RUN` |

## 3. 在本机怎么跑

先 `source ~/.config/auri/android-env.sh`，一次只跑一个套件，Gradle 加 `--no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2`（要新鲜结果加 `--rerun`）：

```bash
cd apps/android
./gradlew :capture:test --rerun --no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2
./gradlew :platform:testDebugUnitTest --rerun --no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2
./gradlew :sync:test --rerun --no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2
./gradlew :syncplatform:testDebugUnitTest --rerun --no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2
./gradlew :app:testDebugUnitTest --rerun --no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2
./gradlew :sync:realGatewayTest --rerun --no-daemon -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.workers.max=2
cd ../..
.venv/bin/pytest execution/acceptance/android-revisions
.venv/bin/python execution/acceptance/android-revisions/revision_mutation_check.py
.venv/bin/pytest tests/contract tests/integration tests/security
```

## 4. 产品负责人：真机验收步骤（`NOT_RUN`）

原因：本机无连接的 Android 真机，且为节省内存不启动模拟器。以下真机项均为 `NOT_RUN`，请产品负责人在 Mac + 真机 + 同局域网电脑上执行：

1. **装机与前提**：
   ```bash
   source ~/.config/auri/android-env.sh
   cd apps/android && ./gradlew :app:assembleDebug --no-daemon
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
   完成 Ticket 21/22 的配对与自动发送 Destination 创建（假设创建了 `代理A`、`代理B`）。
2. **修订与不自动重发（清单 1、2）**：
   - 录一句话并等转录完成（第 1 版自动发给 `代理A`、`代理B`）；在电脑上用 `real_gateway_agent.py claim` / `ack` 让 `代理A` 确认第 1 版，`代理B` 暂不领取。
   - 打开手机“记录”页 → 点进该条记录详情：修改正文并点“保存为新版本”。
   - 期望：下方“不可变修订历史”同时显示第 2 版（用户修订/已审核）与第 1 版（语音转录/未审核）；此时电脑上 `代理A` 执行 `claim` 应为空（未自动重发）。
   - 点“刷新状态”查看收到过旧版本的目的地列表，仅点“确认重发最新一版给 代理B”。
   - 期望：电脑上 `代理B` 执行 `claim` 只领到第 2 版（其未领取的第 1 版已被网关自动取消），`代理A` 仍领不到第 2 版。
3. **删除整条 Record 与提示文案（清单 3、4）**：
   - 在详情页确认页面明确写有“Agent 已读走并另存的内容无法收回”。
   - 点“删除整条记录”→“确认删除整条记录”。
   - 期望：本地列表立即消失；电脑上查询 `sqlite3 /tmp/auri-gateway-data/gateway.db "SELECT record_id, deleted_at FROM records"` 可见 `deleted_at` 非空；重启应用后该记录不复活。
   - **离线删除复测**：关闭手机 Wi-Fi → 删除另一条已上传的记录 → 重启应用确认不可见 → 打开 Wi-Fi 等待同步 → 电脑查 `records.deleted_at` 非空且 Agent 无法读取。
4. **仅删除音频与 30 天保留期（清单 5）**：
   - 在详情页点“仅删除本地音频（保留文字）”→“确认仅删除音频”。
   - 期望：提示本地音频已删除，文字与历史版本完整保留；`adb shell run-as app.auri.android ls no_backup/recordings` 中对应 `.wav` 已消失。
   - 在“记录”页确认音频保留期默认显示 `30`，修改为 `7` 并点“保存保留期”，杀掉进程重启后仍显示 `7`。
5. **筛选与导出（清单 6）**：
   - 在“记录”页输入关键词、切换时间筛选（今天/近7天/近30天）和发送状态筛选（含“仅本地”）。
   - 点“导出 TXT”和“导出 Markdown”，观察系统文件选择器默认文件名形如 `auri-export-YYYYMMDD-HHMMSS.txt` / `.md`（不含任何正文或耳机名），保存后打开确认内容完整。
6. **日志隐私抽查（清单 7）**：
   ```bash
   adb logcat -d -s AuriSync:I AuriCapture:I | grep -E '<你刚才口述的关键词>|<Destination凭证前8位>|\.wav' || echo "CLEAN"
   ```
   期望输出 `CLEAN`。

## 5. 变异检查结果（15/15 CAUGHT）

| 编号 | 清单项 | 守住的规则 | 改动文件 | 结果 |
|---|---|---|---|---|
| `rev-save-allows-empty-text` | 1 | 空白编辑必须被拒绝，不产生空正文 Revision | `RecordRevisionService.kt` | CAUGHT (1/3) |
| `rev-edit-marks-unreviewed` | 1 | 用户修订必须标为 `REVIEWED` | `RecordRevisionService.kt` | CAUGHT (1/3) |
| `rev-auto-resend-on-new-revision` | 2 | 新 Revision 默认不自动规划发送目标 | `DispatchSql.kt` | CAUGHT (2/59) |
| `rev-resend-ignores-eligibility` | 2 | 重复确认重发幂等，不重复触发同步 | `RecordRevisionService.kt` | CAUGHT (1/3) |
| `delete-record-allows-resurrection` | 3 | 墓碑存在时 `addRecord` 绝不能复活 | `InMemoryRecordLibrary.kt` | CAUGHT (1/14) |
| `delete-sql-trigger-allows-single-revision-delete` | 3 | 无墓碑时 SQLite 触发器拒绝删除单个 Revision | `SqliteRecordLibrary.kt` | CAUGHT (2/2) |
| `delete-backfill-resurrects-tombstoned-job` | 3 | `SqliteSyncJobStore` 回填必须排除墓碑 Record | `SqliteSyncJobStore.kt` | CAUGHT (1/8) |
| `delete-gateway-accepts-wrong-record-id` | 3 | DELETE 响应 `record_id` 不符判为 `Garbled` | `GatewayClient.kt` | CAUGHT (1/3) |
| `delete-sync-skips-strict-local` | 3 | 同步引擎必须执行 `syncDeletions` | `SyncEngine.kt` | CAUGHT (3/3) |
| `ui-hides-agent-cannot-recall-notice` | 4 | 界面必须明示 Agent 已读走的内容无法收回 | `RevisionDescriptions.kt` | CAUGHT (1/2) |
| `audio-retention-default-not-30-days` | 5 | 音频保留期默认必须为 30 天 | `AudioRetentionCleaner.kt` | CAUGHT (2/3) |
| `audio-retention-purges-unexpired` | 5 | 未满保留期的音频绝不能被提前清理 | `AudioRetentionCleaner.kt` | CAUGHT (1/3) |
| `export-filename-leaks-text` | 6 | 导出文件名不得泄露敏感信息 | `RecordExporter.kt` | CAUGHT (1/2) |
| `filter-ignores-keyword` | 6 | 关键词筛选必须匹配正文 | `RecordFilter.kt` | CAUGHT (1/2) |
| `log-interpolates-transcript-text` | 7 | 日志不得插值转录正文 | `RecordRevisionService.kt` | CAUGHT (2/3) |

## 6. 最新测试结果（2026-11-24，全部 `--no-daemon --rerun`）

| 套件 | 结果 | 备注 |
|---|---|---|
| `:capture:test` | **375 passed, 0 failed** | 含 `AudioRetentionCleanerTest`、`InMemoryRecordLibraryTest` |
| `:platform:testDebugUnitTest` | **261 passed, 0 failed** | 含 `RecordTombstoneSqlTest`、`AudioRetentionSettingTest` |
| `:sync:test` | **646 passed, 0 failed** | 含 `GatewayClientDeleteTest`、`SyncEngineDeletionTest`、`RecordRevisionServiceTest`、`RecordFilterAndExportTest`、`WakeAheadResendTest` |
| `:syncplatform:testDebugUnitTest` | **123 passed, 0 failed** | 含 `SyncJobsBackfillTest`、`SqliteDispatchStoreContractTest` |
| `:app:testDebugUnitTest` | **395 passed, 0 failed** | 含 `RevisionsViewModelTest`、`RevisionLogPrivacyGuardTest`、`StorageLocationGuardTest`、`RuntimeScopeGuardTest`、`PairingFenceWiringTest`、`PolicyGateWiringTest` |
| `:sync:realGatewayTest` | **28 passed, 0 failed** | 12 (T21) + 13 (T22) + 3 (T23：修订不自动重发与逐个确认重发、在线删除防复活、离线删除防复活) |
| `pytest execution/acceptance/android-revisions` | **17 passed, 0 failed** | 变异表离线自检 |
| `revision_mutation_check.py` | **15/15 CAUGHT** | 全表实跑 |
| `pytest tests/contract tests/integration tests/security` | **573 passed, 1 failed** | 唯一失败为已知的本机 mihomo TUN 代理导致的 `tests/security/test_lan_transport.py::test_default_serve_binds_loopback_and_lan_only_never_vpn_or_public`（与 Ticket 22 README 15.6 一致，原样报告、未修改） |

## 7. OCR 独立评审处置表（全部 39 条 Medium 及以上发现）

运行命令：`ocr review --from origin/integration/auri-v2 --to ticket/23-revisions-and-delete --format json --output /tmp/t23-ocr-review.json`（共 63 条，其中 High 1 条、Medium 38 条、Low 24 条）。每条 Medium 及以上发现逐行核对与处置如下：

| # | 级别 | 位置 | 摘要 | 处置 | 提交 / 代码级理由 |
|---|---|---|---|---|---|
| 1 | Medium | `AudioRetentionCleaner.kt:43` | `deleteAudio` 返回 false 未计入 `failedCount`，单条异常中断整轮清理 | **已修复** | `58011ed`：逐条 `try/catch`（保留 `CancellationException`），false/异常均计入 `failedCount` 并补测试 |
| 2 | Medium | `AudioRetentionCleaner.kt:30` | 逐条 `deleteAudio` 触发多次 `observe()` 重载 | **拒绝** | 清理运行在后台 `Dispatchers.IO`（`AuriRuntime.kt:196`、`MainActivity.kt:105`），日常仅清理 0–少数条过期记录；保持 `RecordLibrary` 最小接口，避免扩大接口面 |
| 3 | Medium | `RecordsViewModel.kt:130` | `AudioRetentionSetting.save` 在主线程做磁盘 I/O | **已修复** | `1c029a2`：移入 `scope.launch` 中异步保存并汇报错误 |
| 4 | Medium | `RecordsViewModel.kt:170` | `TODAY` 用滚动 24 小时而非自然日零点 | **已修复** | `1c029a2`：改为本地时区自然日零点 `atStartOfDay(ZoneId.systemDefault()).toInstant()` |
| 5 | Medium | `RecordDetailViewModel.kt:79` | 初始化异步完成时可能覆盖用户已输入的草稿 | **已修复** | `1c029a2`：若 `cur.draftInitialized` 为 true 则保留用户输入 |
| 6 | Medium | `RecordsPage.kt:160` | 初始 `record == null` 被误显示为“该记录已删除” | **已修复** | `1c029a2`：新增 `RecordDetailUiState.loaded`，未加载完显示“正在加载记录…” |
| 7 | Medium | `RecordsPage.kt:52` | 重组时反复调用 `detailFor(currentId)` | **已修复** | `1c029a2`：改用 `remember(currentId) { detailFor(currentId) }` |
| 8 | Medium | `RecordsPage.kt:95` | 发送状态筛选栏遗漏 `SendStatusFilter.LOCAL_ONLY` | **已修复** | `1c029a2`：改为遍历 `SendStatusFilter.entries` |
| 9 | Medium | `RecordsPage.kt:81` | 筛选按钮行窄屏溢出不可点击 | **已修复** | `1c029a2`：为时间与发送状态行添加 `Modifier.horizontalScroll(rememberScrollState())` |
| 10 | Medium | `RecordsPage.kt:215` | “仅删除本地音频”缺少二次确认 | **已修复** | `1c029a2`：增加 `confirmingDeleteAudio` 二次确认 |
| 11 | Medium | `RecordsPage.kt:239` | `CreateDocument("*/*")` 忽略 TXT/Markdown MIME 类型 | **已修复** | `1c029a2`：拆分为 `text/plain` 与 `text/markdown` 两个 launcher |
| 12 | Medium | `RecordsPage.kt:246` | 导出流写入失败被静默吞掉 | **已修复** | `1c029a2`：写入失败调用 `reportExportFailed()` 向用户提示 |
| 13 | Medium | `MainActivity.kt:50` | `ViewModelProvider` 按 `record-$id` 缓存详情 ViewModel | **拒绝** | 与 Ticket 22 已有模式 `MainActivity.kt:43-44`（`destinationDetail`）保持一致，本票不重构既有导航生命周期 |
| 14 | Medium | `RecordRevisionService.kt:160` | `GatewayDelivery.REFUSED` 未映射为 `ResendState.NOT_ELIGIBLE` | **已修复** | `b0fa887`：优先将 `REFUSED` 映射为 `NOT_ELIGIBLE` 并补测试 |
| 15 | Medium | `RecordRevisionService.kt:135` | 历史版本循环查 `dispatch.item` | **拒绝** | 单条 Record 通常仅 1–5 个 Revision，且仅在打开详情或手动刷新时执行，非热点路径，不扩大 `DispatchStore` 接口 |
| 16 | Medium | `SyncEngine.kt:718` | 非重试性 DELETE 拒绝后标记 `markSynced` | **拒绝** | 网关 `services/gateway/auri_gateway/store.py:1309-1422` 对来源设备的有效 UUID `DELETE` 永远幂等返回 200（即使记录不存在或已删），仅在 401/403 或可重试错误时拒绝，且前面已做 `revalidate` 防止旧网关 422 误判 |
| 17 | Medium | `DispatchStore.kt:171` | `DispatchStoreContract` 缺少 `requestResend` 合同测试 | **已修复** | `a0c3f2f`：在 `DispatchStoreContract.kt` 新增完整合同测试，内存与 SQLite 共用 |
| 18 | Medium | `ResumePlanner.kt:31` | `ResumePlanner` 回退路径未接入 `deletionsOwed`/`deletionsChanged` | **已修复** | `58011ed`：补齐 `deletionsOwed`/`deletionsChanged` 并在 `SyncRuntime.kt` 接线 |
| 19 | Medium | `DispatchSql.kt:229` | `REFUSED` 检查排在 `AUTO`/`CONFIRMED` 之后导致被吞 | **已修复** | `a0c3f2f`：提前判断 `delivery == REFUSED -> NotEligible` 并由 `DispatchStoreContract` 覆盖 |
| 20 | Medium | `RecordsPage.kt:243` | 导出文件写入在主线程同步执行 | **已修复** | `1c029a2`：改为 `rememberCoroutineScope()` + `withContext(Dispatchers.IO)` |
| 21 | Medium | `AppRoot.kt:32` | 顶部 5 个页签按钮窄屏溢出 | **已修复** | `1c029a2`：添加 `Modifier.horizontalScroll(rememberScrollState())` |
| 22 | Medium | `AuriRuntime.kt:196` | 音频保留期清理只在进程启动时跑一次 | **已修复** | `1c029a2`：`MainActivity.onStart()` 回到前台时同样触发清理 |
| 23 | Medium | `SqliteRecordLibrary.kt:221` | `deleteRecord` 事务与磁盘删文件非原子 | **拒绝** | Android 上 SQLite 事务无法回滚文件系统 `unlink`；录音位于 `noBackupFilesDir/recordings`（`BackupRulesTest` 整域排除备份），删除前先取路径并在本地删后清理，且 `deleteAudioOnly` 已在 #30 改为先删文件成功再清库 |
| 24 | Medium | `RecordsViewModel.kt:86` | `combine(library.observe(), ...)` 未包 `containReadFailures` | **拒绝** | 与 `HomeViewModel.kt:164-172` 采用同一观察模式，且 `AuriRuntime.scopeOf()` 带有 `escapedFailureHandler`（`RuntimeScopeGuardTest` 守卫），异常不会崩溃进程 |
| 25 | Medium | `RecordDetailViewModel.kt:24` | 缺少 `loaded` 状态字段区分加载中与已删除 | **已修复** | `1c029a2`：新增 `loaded: Boolean = false` |
| 26 | Medium | `RecordDetailViewModel.kt:96` | `saveEdit()` 缺少防重入锁导致双击生成重复 Revision | **已修复** | `1c029a2`：新增 `saving: Boolean` 门禁并在保存中禁用按钮 |
| 27 | Medium | `RevisionDescriptions.kt:51` | 所有 `UnknownReason` 折叠为“离线或未查询” | **已修复** | `1c029a2`：逐个枚举分支给出准确文案并补单测 |
| 28 | Medium | `AudioRetentionCleaner.kt:9` | `failedCount` 未在界面展示且缺少失败分支测试 | **已修复** | `58011ed` + `1c029a2`：补充失败分支测试并在 `RecordsViewModel` 提示未清理条数 |
| 29 | Medium | `RecordRevisionService.kt:138` | 本地兜底把 `REFUSED` 目标也列为“收到过旧版本” | **已修复** | `b0fa887`：改为要求 `delivery == CREATED || willReceive` |
| 30 | Medium | `RecordRevisionService.kt:189` | `deleteAudioOnly` 忽略 `deleteFile` 返回 false 仍清库 | **已修复** | `b0fa887`：`deleteFile` 失败时返回 false 且保留元数据并补测试 |
| 31 | Medium | `RecordRevisionService.kt:163` | `olderVersionRecipients` 未校验 `dest.allows(intent)` | **已修复** | `b0fa887`：校验 `dest.allows(effectiveIntent)` 并补测试 |
| 32 | Medium | `RecordRevisionService.kt:178` | `confirmResend` 用全局 `defaultIntent()` 而非该 Record 原意图 | **已修复** | `b0fa887`：新增 `existingIntentFor` 沿用该 Record 既有意图，无记录时回退默认意图 |
| 33 | Medium | `InMemoryDispatchStore.kt:113` | `requestResend` 用入参 `intent` 而非已存条目意图判断资格 | **已修复** | `a0c3f2f`：统一改用 `existingItem?.intent ?: intent` |
| 34 | Medium | `PendingDeletions.kt:19` | 墓碑未绑定网关身份 | **拒绝** | V1 为单用户自托管单网关（ADR-0013），且 `SyncEngine.syncDeletions` 的 `markSynced` 位于 `fenced(gateway)` 配对围栏内（`SyncEngine.kt:691`，`PairingFenceWiringTest` 守卫），换配对时旧请求结果自动作废 |
| 35 | Medium | `WakeGate.kt:104` | `WakeAheadDispatchStore.requestResend` 缺少单元测试 | **已修复** | `58011ed`：新增 `WakeAheadResendTest.kt` 覆盖成功登记与系统拒绝两条路径 |
| 36 | Medium | `DispatchSql.kt:213` | `requestResend` 未校验 `sync_jobs` 是否存在（防并发删除留下幽灵条目） | **已修复** | `a0c3f2f`：在事务内先查 `sync_jobs`，不存在返回 `NotEligible` 并由 `DispatchStoreContract` 覆盖 |
| 37 | Medium | `SqliteRecordLibrary.kt:271` | `markGatewayDeleted` 递增 `changes` 触发重载 | **拒绝** | `SqlitePendingDeletions.changes()` 复用 `library.changeSignal`（`SqlitePendingDeletions.kt:15`），`SyncLoop.watchDeletions` 与 `ResumePlanner` 依赖该信号在墓碑同步完成后及时取消后台唤醒；去掉会导致唤醒无法取消 |
| 38 | Medium | `RecordLibraryContract.kt:240` | `deleteRecord` 对未知 `recordId` 也种墓碑 | **拒绝** | **有意设计（FR-TXT-005）**：与网关 `store.py:1341-1366` 语义一致，允许对尚未入库/未登记的 `recordId` 种下墓碑，确保离线删除绝不被后续补传或孤儿恢复复活（`RealGatewayRevisionsAndDeleteTest` 已覆盖） |
| 39 | **High** | `DispatchSql.kt:213` | `requestResend` 资格检查用入参 `intent` 而非 `dispatch_items.intent`，可能与 `due()` 不一致 | **已修复** | `a0c3f2f`：改为优先取 `existingItem?.intent ?: intent`，并由 `DispatchStoreContract`（内存 + SQLite）验证 |
