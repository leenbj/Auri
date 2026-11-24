package app.auri.syncplatform.dispatch

import app.auri.capture.contract.RevisionSource
import app.auri.sync.destinations.Choice
import app.auri.sync.destinations.DecideResult
import app.auri.sync.destinations.DispatchItem
import app.auri.sync.destinations.DispatchTarget
import app.auri.sync.destinations.GatewayDelivery
import app.auri.sync.destinations.IntentResult
import app.auri.sync.destinations.ResendResult
import app.auri.sync.destinations.TargetDecision
import app.auri.sync.engine.DeliveryAddition
import app.auri.sync.engine.JobKey
import app.auri.sync.engine.Refusal
import app.auri.sync.gateway.Destination
import app.auri.sync.gateway.Intent
import app.auri.sync.gateway.Publication
import app.auri.sync.gateway.SendMode
import app.auri.sync.machine.SyncState
import app.auri.sync.pairing.GatewayIdentity
import app.auri.syncplatform.jobs.SqliteSyncJobStore
import java.time.Instant

/**
 * 发送账（[app.auri.sync.destinations.DispatchStore]）的全部逻辑，写在 [SqlDb] 上：Android 上跑在 Record 库同一个数据库文件的同一条连接上，
 * JVM 测试里跑在真正的 SQLite 引擎上（同一份 DispatchStoreContract）。方法都是同步的，**调用方负责放到数据库线程上**；
 * 修改类的方法自己开事务（可重入：发布 Revision 的事务里调用时并入那个事务，成败一起）。
 *
 * 文字照常入库，Delivery 各管各的（评审 B2）：发布 Revision 的那个事务里**一定**创建同步任务（[publish]），不等任何决定；
 * 自动发送的目标随入库建 Delivery；预览确认的目标在用户确认之后，由同步引擎向网关给这个已入库的 Revision 追加（[due]、[gatewayHas]、[refused]）。
 * 一个等用户的目标不拦着别的目标，也不拦着文字本身。
 *
 * 意图标签的冻结（评审 M3）：[publicationFor] 在引擎构造提交的那一刻，在**同一个事务**里把 `intent_locked` 置 1 并交出意图；
 * [setIntent] 在同一把事务锁下看这个标记——两者必有先后：要么改动先落、提交带着新标签，要么提交先冻结、改动被拒。
 *
 * 不记日志、不碰正文：这里只有 Destination 的名字（用户自己起的）、意图、决定。
 */
class DispatchSql(private val sql: SqlDb) {
    // ---- 目的地缓存 ----

    fun destinations(): List<Destination> = sql.query(
        """
        SELECT destination_id, display_name, send_mode, allowed_intents, created_at_s, created_at_nano,
               created_by_device_id, credential_issued, revoked_at_s, revoked_at_nano, credential_active
        FROM dispatch_destinations
        ORDER BY created_at_s, created_at_nano, destination_id
        """.trimIndent(),
    ) { it.toDestination() }

    fun applyDestinations(gateway: GatewayIdentity, destinations: List<Destination>, authoritative: Boolean, now: Instant) = sql.transaction<Unit> {
        if (meta(GATEWAY_ID) != gateway.ledgerKey) switchGateway(gateway)
        if (authoritative) {
            val keep = destinations.map { it.destinationId }.toSet()
            sql.query("SELECT destination_id FROM dispatch_destinations") { it.string(0) }
                .filter { it !in keep }
                .forEach { sql.update("DELETE FROM dispatch_destinations WHERE destination_id = ?", it) }
        }
        destinations.forEach(::upsert)
        // 暂停的（凭证被撤销）：还没建 Delivery 的目标记成没送达，之后恢复也不补发（评审 B3）；用户已经跳过的不动
        destinations.filter { it.suspended }.forEach { suspended ->
            sql.update(
                "UPDATE dispatch_targets SET delivery = ?, refusal = ? WHERE destination_id = ? AND delivery = ? AND decision <> ?",
                GatewayDelivery.REFUSED.name,
                Refusal.SUSPENDED.name,
                suspended.destinationId,
                GatewayDelivery.NOT_YET.name,
                TargetDecision.SKIPPED.name,
            )
        }
    }

    fun forgetDestinations(now: Instant) = sql.transaction<Unit> { switchGateway(null) }

    /**
     * 向 [gateway] 发请求之前对账（评审 H2）：缓存记着的不是这个网关，就一起作废。返回有没有换。
     * 认的是完整身份（[GatewayIdentity.ledgerKey]：编号加公钥指纹，复评三 S4）：同一个编号换了公钥是另一台网关。
     * 升级前写下的只有编号的旧记法永远对不上：升级后第一次对账整个作废一次（保守；缓存重读，排着的目标撤回，历史不动）。
     */
    fun useGateway(gateway: GatewayIdentity): Boolean = sql.transaction {
        if (meta(GATEWAY_ID) == gateway.ledgerKey) return@transaction false
        switchGateway(gateway)
        true
    }

    /**
     * 缓存与按它规划的、网关上还没有 Delivery 的目标一起作废：编号只在那个网关上有意义，拿着它们去别的网关只会被整个拒绝。
     * 已经发生的（有 Delivery、被拒绝建）是历史，不动。[gateway] 为 null = 忘掉缓存、不属于任何网关。调用方已经在事务里。
     */
    private fun switchGateway(gateway: GatewayIdentity?) {
        sql.update("DELETE FROM dispatch_destinations")
        sql.update("DELETE FROM dispatch_targets WHERE delivery = ?", GatewayDelivery.NOT_YET.name)
        if (gateway == null) sql.update("DELETE FROM dispatch_meta WHERE name = ?", GATEWAY_ID) else setMeta(GATEWAY_ID, gateway.ledgerKey)
    }

    // ---- 读 ----

    fun item(key: JobKey): DispatchItem? = loadItem(key, destinationsById())

    fun items(limit: Int): List<DispatchItem> {
        val known = destinationsById()
        // 上限直接写进语句：它是 Int，不是用户输入
        return sql.query("SELECT record_id, revision FROM dispatch_items ORDER BY rowid DESC LIMIT ${limit.coerceIn(0, MAX_LIMIT)}") { it.toKey() }
            .mapNotNull { loadItem(it, known) }
    }

    /** 有目标在等用户决定的（先按“决定还是等待”粗筛，再按目的地现状精确判断 awaitingUser），不设条数上限（评审 M1）。 */
    fun awaiting(): List<DispatchItem> {
        val known = destinationsById()
        return sql.query(
            """
            SELECT i.record_id, i.revision FROM dispatch_items i
            WHERE EXISTS (SELECT 1 FROM dispatch_targets t WHERE t.record_id = i.record_id AND t.revision = i.revision AND t.decision = '${TargetDecision.AWAITING.name}')
            ORDER BY i.rowid DESC
            """.trimIndent(),
        ) { it.toKey() }.mapNotNull { loadItem(it, known) }.filter { it.awaitingUser }
    }

    fun itemsFor(destinationId: String, limit: Int): List<DispatchItem> {
        val known = destinationsById()
        return sql.query(
            """
            SELECT i.record_id, i.revision FROM dispatch_items i
            WHERE EXISTS (SELECT 1 FROM dispatch_targets t WHERE t.record_id = i.record_id AND t.revision = i.revision AND t.destination_id = ?)
            ORDER BY i.rowid DESC LIMIT ${limit.coerceIn(0, MAX_LIMIT)}
            """.trimIndent(),
            destinationId,
        ) { it.toKey() }.mapNotNull { loadItem(it, known) }
    }

    /** 构造提交的那一刻：冻结意图并交出 publication（一次提交最多带合同上限个目标，其余的由追加补上）。没有为它规划过的文字只入库。 */
    fun publicationFor(key: JobKey): Publication = sql.transaction {
        val item = item(key) ?: return@transaction Publication.StoreOnly
        sql.update("UPDATE dispatch_items SET intent_locked = 1 WHERE record_id = ? AND revision = ? AND intent_locked = 0", key.recordId, key.revision)
        Publication(item.intent, item.targets.filter { it.willReceive }.take(Publication.MAX_DESTINATIONS).map { it.destinationId })
    }

    /** 放开 [publicationFor] 的冻结（请求没有离开手机）：只动还冻着的那一行；之后用户又能改标签，下一次构造提交带的是改后的。 */
    fun unfreezeUnsent(key: JobKey): Boolean = sql.transaction {
        sql.update("UPDATE dispatch_items SET intent_locked = 0 WHERE record_id = ? AND revision = ? AND intent_locked = 1", key.recordId, key.revision) > 0
    }

    /** 已入库的 Revision 上还欠的 Delivery：目标会收到它、账上网关还没有它、没被拒绝过；目的地不在了或不接收这个意图的不算。最早的在前。 */
    fun due(): List<DeliveryAddition> {
        val known = destinationsById()
        return sql.query(
            """
            SELECT t.record_id, t.revision, i.intent, t.destination_id
            FROM dispatch_targets t
            JOIN dispatch_items i ON i.record_id = t.record_id AND i.revision = t.revision
            JOIN sync_jobs j ON j.record_id = t.record_id AND j.revision = t.revision
            WHERE j.state = '${SyncState.STORED.name}' AND t.decision IN ('${TargetDecision.AUTO.name}', '${TargetDecision.CONFIRMED.name}')
              AND t.delivery = '${GatewayDelivery.NOT_YET.name}'
            ORDER BY i.rowid, t.rowid
            """.trimIndent(),
        ) { Triple(it.toKey(), it.string(2), it.string(3)) }.mapNotNull { (key, intentWire, destinationId) ->
            val intent = Intent.fromWire(intentWire) ?: error("发送账里有不认识的意图")
            val destination = known[destinationId]
            if (destination != null && destination.receivesNewText && destination.allows(intent)) DeliveryAddition(key, intent, destinationId) else null
        }
    }

    /** 网关上这个 Revision 已经有这些 Destination 的 Delivery：记下（网关的回答比账上先前的任何说法都算数，包括“被拒绝”）。 */
    fun gatewayHas(key: JobKey, destinationIds: Collection<String>) = sql.transaction {
        destinationIds.forEach { id ->
            sql.update(
                "UPDATE dispatch_targets SET delivery = ?, refusal = NULL WHERE record_id = ? AND revision = ? AND destination_id = ?",
                GatewayDelivery.CREATED.name,
                key.recordId,
                key.revision,
                id,
            )
        }
    }

    /** 网关不会为这些目标建 Delivery：记下、不再追加。已经记过“网关有了”的不改。 */
    fun refused(key: JobKey, destinationIds: Collection<String>, reason: Refusal) = sql.transaction {
        destinationIds.forEach { id ->
            sql.update(
                "UPDATE dispatch_targets SET delivery = ?, refusal = ? WHERE record_id = ? AND revision = ? AND destination_id = ? AND delivery = ?",
                GatewayDelivery.REFUSED.name,
                reason.name,
                key.recordId,
                key.revision,
                id,
                GatewayDelivery.NOT_YET.name,
            )
        }
    }

    fun defaultIntent(): Intent = meta(DEFAULT_INTENT)?.let(Intent::fromWire) ?: Intent.CONTEXT

    // ---- 写 ----

    fun setDefaultIntent(intent: Intent) = sql.transaction { setMeta(DEFAULT_INTENT, intent.wire) }

    fun decide(key: JobKey, destinationId: String, choice: Choice, now: Instant): DecideResult = sql.transaction {
        val item = item(key) ?: return@transaction DecideResult.NotFound
        val target = item.targets.firstOrNull { it.destinationId == destinationId } ?: return@transaction DecideResult.NotFound
        if (target.decision != TargetDecision.AWAITING) return@transaction DecideResult.AlreadyDecided(target.decision)
        if (!target.eligible || target.delivery == GatewayDelivery.REFUSED) return@transaction DecideResult.NotEligible
        val decision = if (choice == Choice.CONFIRM) TargetDecision.CONFIRMED else TargetDecision.SKIPPED
        sql.update(
            "UPDATE dispatch_targets SET decision = ? WHERE record_id = ? AND revision = ? AND destination_id = ? AND decision = ?",
            decision.name,
            key.recordId,
            key.revision,
            destinationId,
            TargetDecision.AWAITING.name,
        )
        DecideResult.Decided
    }

    fun requestResend(key: JobKey, destinationId: String, intent: Intent, now: Instant): ResendResult = sql.transaction {
        val hasJob = sql.query("SELECT 1 FROM sync_jobs WHERE record_id = ? AND revision = ?", key.recordId, key.revision) { 1 }.isNotEmpty()
        if (!hasJob) return@transaction ResendResult.NotEligible
        val existingItem = item(key)
        val effectiveIntent = existingItem?.intent ?: intent
        val destination = destinationsById()[destinationId] ?: return@transaction ResendResult.NotEligible
        if (!destination.receivesNewText || !destination.allows(effectiveIntent)) return@transaction ResendResult.NotEligible
        val existingTarget = existingItem?.targets?.firstOrNull { it.destinationId == destinationId }
        if (existingTarget != null) {
            if (existingTarget.delivery == GatewayDelivery.REFUSED) return@transaction ResendResult.NotEligible
            if (existingTarget.decision == TargetDecision.AUTO || existingTarget.decision == TargetDecision.CONFIRMED) {
                return@transaction ResendResult.AlreadyRequested
            }
            sql.update(
                "UPDATE dispatch_targets SET decision = ? WHERE record_id = ? AND revision = ? AND destination_id = ?",
                TargetDecision.CONFIRMED.name,
                key.recordId,
                key.revision,
                destinationId,
            )
        } else {
            if (existingItem == null) {
                sql.update(
                    "INSERT OR IGNORE INTO dispatch_items (record_id, revision, intent, created_at_s, created_at_nano, intent_locked) VALUES (?, ?, ?, ?, ?, 0)",
                    key.recordId,
                    key.revision,
                    effectiveIntent.wire,
                    now.epochSecond,
                    now.nano,
                )
            }
            sql.update(
                "INSERT INTO dispatch_targets (record_id, revision, destination_id, send_mode, decision) VALUES (?, ?, ?, ?, ?)",
                key.recordId,
                key.revision,
                destinationId,
                destination.sendMode.wire,
                TargetDecision.CONFIRMED.name,
            )
        }
        ResendResult.Requested
    }

    fun setIntent(key: JobKey, intent: Intent, now: Instant): IntentResult = sql.transaction {
        val item = item(key) ?: return@transaction IntentResult.NotFound
        if (item.intentLocked) return@transaction IntentResult.Locked
        if (item.intent == intent) return@transaction IntentResult.Unchanged
        sql.update("UPDATE dispatch_items SET intent = ? WHERE record_id = ? AND revision = ? AND intent_locked = 0", intent.wire, key.recordId, key.revision)
        IntentResult.Changed
    }

    /**
     * 发布一条 Revision 的那个事务里发生的事（Android：SqliteRecordLibrary 的 publicationStep）。**文字照常入库：同步任务一定创建，不等任何决定。**
     * ASR 初稿（第一个 Revision）另外按这一刻的目的地缓存定下目标快照（所有有效的 Destination，一个不丢）；
     * 其余（用户修订、重新转录出的新版本）：不规划，只入库——自动发送只管初稿（ADR-0009）。
     */
    fun publish(key: JobKey, source: RevisionSource, now: Instant) = sql.transaction<Unit> {
        SqliteSyncJobStore.enqueueIn(sql, key, now)
        if (source != RevisionSource.ASR || key.revision != 1) return@transaction
        val planned = sql.update(
            "INSERT OR IGNORE INTO dispatch_items (record_id, revision, intent, created_at_s, created_at_nano, intent_locked) VALUES (?, ?, ?, ?, ?, 0)",
            key.recordId,
            key.revision,
            defaultIntent().wire,
            now.epochSecond,
            now.nano,
        )
        if (planned > 0) {
            destinations().filter { it.receivesNewText }.forEach { destination ->
                sql.update(
                    "INSERT INTO dispatch_targets (record_id, revision, destination_id, send_mode, decision) VALUES (?, ?, ?, ?, ?)",
                    key.recordId,
                    key.revision,
                    destination.destinationId,
                    destination.sendMode.wire,
                    (if (destination.sendMode == SendMode.AUTO_SEND) TargetDecision.AUTO else TargetDecision.AWAITING).name,
                )
            }
        }
    }

    /**
     * 删除整条 Record 的同一事务里清理其待同步任务与发送账（FR-TXT-005）：
     * 删除之后不再把该 Record 的旧 Revision 推给网关，也不再追加它的 Delivery。
     */
    fun deleteRecord(recordId: String) = sql.transaction<Unit> {
        sql.update("DELETE FROM sync_jobs WHERE record_id = ?", recordId)
        sql.update("DELETE FROM dispatch_targets WHERE record_id = ?", recordId)
        sql.update("DELETE FROM dispatch_items WHERE record_id = ?", recordId)
    }

    // ---- 内部 ----

    private fun upsert(destination: Destination) {
        // 撤销只进不退（评审 H3）：缓存里已经是撤销的，较早算好的回答再说它有效也不算
        val revokedAt = sql.query(
            "SELECT revoked_at_s, revoked_at_nano FROM dispatch_destinations WHERE destination_id = ? AND revoked_at_s IS NOT NULL",
            destination.destinationId,
        ) { Instant.ofEpochSecond(it.long(0), it.long(1)) }.firstOrNull() ?: destination.revokedAt
        sql.update(
            """
            INSERT OR REPLACE INTO dispatch_destinations (
                destination_id, display_name, send_mode, allowed_intents, created_at_s, created_at_nano,
                created_by_device_id, credential_issued, revoked_at_s, revoked_at_nano, credential_active
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            destination.destinationId,
            destination.displayName,
            destination.sendMode.wire,
            destination.allowedIntents.map { it.wire }.sorted().joinToString(","),
            destination.createdAt.epochSecond,
            destination.createdAt.nano,
            destination.createdByDeviceId,
            if (destination.credentialIssued) 1 else 0,
            revokedAt?.epochSecond,
            revokedAt?.nano,
            if (destination.credentialActive) 1 else 0,
        )
    }

    private fun destinationsById(): Map<String, Destination> = destinations().associateBy { it.destinationId }

    private fun loadItem(key: JobKey, known: Map<String, Destination>): DispatchItem? {
        val head = sql.query(
            "SELECT intent, created_at_s, created_at_nano, intent_locked FROM dispatch_items WHERE record_id = ? AND revision = ?",
            key.recordId,
            key.revision,
        ) { listOf(it.string(0), it.long(1), it.long(2), it.long(3)) }.firstOrNull() ?: return null
        val intent = Intent.fromWire(head[0] as String) ?: error("发送账里有不认识的意图")
        val targets = sql.query(
            "SELECT destination_id, send_mode, decision, delivery, refusal FROM dispatch_targets WHERE record_id = ? AND revision = ? ORDER BY rowid",
            key.recordId,
            key.revision,
        ) { row ->
            val id = row.string(0)
            val destination = known[id]
            DispatchTarget(
                destinationId = id,
                destinationName = destination?.displayName,
                sendMode = SendMode.fromWire(row.string(1)) ?: error("发送账里有不认识的发送方式"),
                decision = TargetDecision.valueOf(row.string(2)),
                eligible = destination != null && destination.receivesNewText && destination.allows(intent),
                delivery = GatewayDelivery.valueOf(row.string(3)),
                refusal = row.stringOrNull(4)?.let(Refusal::valueOf),
            )
        }
        return DispatchItem(key, intent, Instant.ofEpochSecond(head[1] as Long, head[2] as Long), head[3] == 1L, targets)
    }

    private fun meta(name: String): String? = sql.query("SELECT value FROM dispatch_meta WHERE name = ?", name) { it.string(0) }.firstOrNull()

    private fun setMeta(name: String, value: String) {
        sql.update("INSERT OR REPLACE INTO dispatch_meta (name, value) VALUES (?, ?)", name, value)
    }

    private fun SqlRow.toKey() = JobKey(string(0), long(1).toInt())

    private fun SqlRow.toDestination() = Destination(
        destinationId = string(0),
        displayName = string(1),
        sendMode = SendMode.fromWire(string(2)) ?: error("发送账里有不认识的发送方式"),
        allowedIntents = string(3).split(",").filter { it.isNotEmpty() }.map { Intent.fromWire(it) ?: error("发送账里有不认识的意图") }.toSet(),
        createdAt = Instant.ofEpochSecond(long(4), long(5)),
        createdByDeviceId = string(6),
        credentialIssued = long(7) == 1L,
        revokedAt = longOrNull(8)?.let { Instant.ofEpochSecond(it, long(9)) },
        credentialActive = long(10) == 1L,
    )

    companion object {
        /** 行名沿用旧的；值是 [GatewayIdentity.ledgerKey]（编号加公钥指纹，复评三 S4），旧版只存编号，对不上就整个作废一次。 */
        private const val GATEWAY_ID = "gateway_id"
        private const val DEFAULT_INTENT = "default_intent"
        private const val MAX_LIMIT = 10_000

        /** 发送账的四张表（同 Record 库一个文件）。没有外键：和 sync_jobs 一样，库里的删除（后续 Ticket）不应被这里的账拦住。 */
        val TABLES = listOf(
            """
            CREATE TABLE IF NOT EXISTS dispatch_meta (
                name TEXT NOT NULL PRIMARY KEY,
                value TEXT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS dispatch_destinations (
                destination_id TEXT NOT NULL PRIMARY KEY,
                display_name TEXT NOT NULL,
                send_mode TEXT NOT NULL CHECK (send_mode IN ('auto_send', 'preview_confirm')),
                allowed_intents TEXT NOT NULL,
                created_at_s INTEGER NOT NULL,
                created_at_nano INTEGER NOT NULL,
                created_by_device_id TEXT NOT NULL,
                credential_issued INTEGER NOT NULL CHECK (credential_issued IN (0, 1)),
                revoked_at_s INTEGER,
                revoked_at_nano INTEGER,
                credential_active INTEGER NOT NULL DEFAULT 0 CHECK (credential_active IN (0, 1))
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS dispatch_items (
                record_id TEXT NOT NULL,
                revision INTEGER NOT NULL CHECK (revision >= 1),
                intent TEXT NOT NULL CHECK (intent IN ('context', 'action_request')),
                created_at_s INTEGER NOT NULL,
                created_at_nano INTEGER NOT NULL,
                intent_locked INTEGER NOT NULL DEFAULT 0 CHECK (intent_locked IN (0, 1)),
                PRIMARY KEY (record_id, revision)
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS dispatch_targets (
                record_id TEXT NOT NULL,
                revision INTEGER NOT NULL CHECK (revision >= 1),
                destination_id TEXT NOT NULL,
                send_mode TEXT NOT NULL CHECK (send_mode IN ('auto_send', 'preview_confirm')),
                decision TEXT NOT NULL CHECK (decision IN ('AUTO', 'AWAITING', 'CONFIRMED', 'SKIPPED')),
                delivery TEXT NOT NULL DEFAULT 'NOT_YET' CHECK (delivery IN ('NOT_YET', 'CREATED', 'REFUSED')),
                refusal TEXT,
                PRIMARY KEY (record_id, revision, destination_id)
            )
            """.trimIndent(),
        )

        /**
         * 交给 SqliteRecordLibrary 的 extraSchema（打开数据库时在一个事务里按顺序幂等执行）：先建发送账的表，再建任务表并回填。
         * 文字入库不再等任何决定（评审 B2），所以回填不必绕开任何文字：库里有 Revision 却没有任务的，一律补。
         * 表结构直接写在 CREATE TABLE 里、没有迁移：本票的代码还没有发布给任何设备（没有旧库要升级）。
         */
        val SCHEMA: List<String> = TABLES + SqliteSyncJobStore.SCHEMA
    }
}
