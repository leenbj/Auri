package app.auri.syncplatform.dispatch

import app.auri.capture.contract.RevisionSource
import app.auri.sync.destinations.Choice
import app.auri.sync.destinations.DecideResult
import app.auri.sync.destinations.DispatchItem
import app.auri.sync.destinations.DispatchTarget
import app.auri.sync.destinations.IntentResult
import app.auri.sync.destinations.TargetDecision
import app.auri.sync.engine.JobKey
import app.auri.sync.gateway.Destination
import app.auri.sync.gateway.Intent
import app.auri.sync.gateway.Publication
import app.auri.sync.gateway.SendMode
import app.auri.syncplatform.jobs.SqliteSyncJobStore
import java.time.Instant

/**
 * 发送账（[app.auri.sync.destinations.DispatchStore]）的全部逻辑，写在 [SqlDb] 上：Android 上跑在 Record 库同一个数据库文件的同一条连接上，
 * JVM 测试里跑在真正的 SQLite 引擎上（同一份 DispatchStoreContract）。方法都是同步的，**调用方负责放到数据库线程上**；
 * 修改类的方法自己开事务（可重入：发布 Revision 的事务里调用时并入那个事务，成败一起）。
 *
 * 为什么预览确认的文字不会提前出现在网关上：发布 Revision 的那个事务里，只要还有“等着用户决定”的目标，就**不创建同步任务**（[publish]）；
 * 最后一个决定落下的同一个事务里才创建（[releaseIfReady]）。任务不存在，同步引擎就看不见它。
 * 打开数据库时的回填（SqliteSyncJobStore 的 SCHEMA）会给“有 Revision 却没有任务”的文字补任务，所以它必须跳过还拦着的文字——
 * 见 [SCHEMA]：`dispatch_items.released = 0` 的不补。
 *
 * 不记日志、不碰正文：这里只有 Destination 的名字（用户自己起的）、意图、决定。
 */
class DispatchSql(private val sql: SqlDb) {
    // ---- 目的地缓存 ----

    fun destinations(): List<Destination> = sql.query(
        """
        SELECT destination_id, display_name, send_mode, allowed_intents, created_at_s, created_at_nano,
               created_by_device_id, credential_issued, revoked_at_s, revoked_at_nano
        FROM dispatch_destinations
        ORDER BY created_at_s, created_at_nano, destination_id
        """.trimIndent(),
    ) { it.toDestination() }

    fun applyDestinations(gatewayId: String, destinations: List<Destination>, authoritative: Boolean, now: Instant) = sql.transaction {
        if (meta(GATEWAY_ID) != gatewayId) {
            sql.update("DELETE FROM dispatch_destinations")
            setMeta(GATEWAY_ID, gatewayId)
        }
        if (authoritative) {
            val keep = destinations.map { it.destinationId }.toSet()
            sql.query("SELECT destination_id FROM dispatch_destinations") { it.string(0) }
                .filter { it !in keep }
                .forEach { sql.update("DELETE FROM dispatch_destinations WHERE destination_id = ?", it) }
        }
        destinations.forEach(::upsert)
        releaseReady(now)
    }

    fun forgetDestinations(now: Instant) = sql.transaction {
        sql.update("DELETE FROM dispatch_destinations")
        sql.update("DELETE FROM dispatch_meta WHERE name = ?", GATEWAY_ID)
        // 旧网关的目的地不会再有了：还在等它们的文字不能永远拦着，照常入库
        releaseReady(now)
    }

    // ---- 读 ----

    fun item(key: JobKey): DispatchItem? = loadItem(key, destinationsById())

    fun items(limit: Int): List<DispatchItem> {
        val known = destinationsById()
        // 上限直接写进语句：它是 Int，不是用户输入
        return sql.query("SELECT record_id, revision FROM dispatch_items ORDER BY rowid DESC LIMIT ${limit.coerceIn(0, MAX_LIMIT)}") { it.toKey() }
            .mapNotNull { loadItem(it, known) }
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

    fun publicationFor(key: JobKey): Publication {
        val item = item(key) ?: return Publication.StoreOnly
        return Publication(item.intent, item.targets.filter { it.willReceive }.map { it.destinationId })
    }

    fun defaultIntent(): Intent = meta(DEFAULT_INTENT)?.let(Intent::fromWire) ?: Intent.CONTEXT

    // ---- 写 ----

    fun setDefaultIntent(intent: Intent) = sql.transaction { setMeta(DEFAULT_INTENT, intent.wire) }

    fun decide(key: JobKey, destinationId: String, choice: Choice, now: Instant): DecideResult = sql.transaction {
        val item = item(key) ?: return@transaction DecideResult.NotFound
        val target = item.targets.firstOrNull { it.destinationId == destinationId } ?: return@transaction DecideResult.NotFound
        if (target.decision != TargetDecision.AWAITING) return@transaction DecideResult.AlreadyDecided(target.decision)
        if (!target.eligible) return@transaction DecideResult.NotEligible
        val decision = if (choice == Choice.CONFIRM) TargetDecision.CONFIRMED else TargetDecision.SKIPPED
        sql.update(
            "UPDATE dispatch_targets SET decision = ? WHERE record_id = ? AND revision = ? AND destination_id = ? AND decision = ?",
            decision.name,
            key.recordId,
            key.revision,
            destinationId,
            TargetDecision.AWAITING.name,
        )
        DecideResult.Decided(releaseIfReady(key, now))
    }

    fun setIntent(key: JobKey, intent: Intent, now: Instant): IntentResult = sql.transaction {
        val item = item(key) ?: return@transaction IntentResult.NotFound
        if (item.sent) return@transaction IntentResult.Locked
        if (item.intent == intent) return@transaction IntentResult.Unchanged
        sql.update("UPDATE dispatch_items SET intent = ? WHERE record_id = ? AND revision = ?", intent.wire, key.recordId, key.revision)
        IntentResult.Changed(releaseIfReady(key, now))
    }

    /**
     * 发布一条 Revision 的那个事务里发生的事（Android：SqliteRecordLibrary 的 publicationStep）。
     * ASR 初稿（第一个 Revision）：按这一刻的目的地缓存定下目标快照，没有要等的决定就创建同步任务，有就先不创建。
     * 其余（用户修订、重新转录出的新版本）：不规划、不等待，照常入库——自动发送只管初稿（ADR-0009）。
     */
    fun publish(key: JobKey, source: RevisionSource, now: Instant) = sql.transaction<Unit> {
        if (source != RevisionSource.ASR || key.revision != 1) {
            SqliteSyncJobStore.enqueueIn(sql, key, now)
            return@transaction
        }
        val planned = sql.update(
            "INSERT OR IGNORE INTO dispatch_items (record_id, revision, intent, created_at_s, created_at_nano, released) VALUES (?, ?, ?, ?, ?, 0)",
            key.recordId,
            key.revision,
            defaultIntent().wire,
            now.epochSecond,
            now.nano,
        )
        if (planned > 0) {
            destinations().filter { it.active }.take(Publication.MAX_DESTINATIONS).forEach { destination ->
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
        releaseIfReady(key, now)
    }

    // ---- 内部 ----

    private fun upsert(destination: Destination) {
        sql.update(
            """
            INSERT OR REPLACE INTO dispatch_destinations (
                destination_id, display_name, send_mode, allowed_intents, created_at_s, created_at_nano,
                created_by_device_id, credential_issued, revoked_at_s, revoked_at_nano
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            destination.destinationId,
            destination.displayName,
            destination.sendMode.wire,
            destination.allowedIntents.map { it.wire }.sorted().joinToString(","),
            destination.createdAt.epochSecond,
            destination.createdAt.nano,
            destination.createdByDeviceId,
            if (destination.credentialIssued) 1 else 0,
            destination.revokedAt?.epochSecond,
            destination.revokedAt?.nano,
        )
    }

    private fun destinationsById(): Map<String, Destination> = destinations().associateBy { it.destinationId }

    private fun loadItem(key: JobKey, known: Map<String, Destination>): DispatchItem? {
        val head = sql.query(
            "SELECT intent, created_at_s, created_at_nano FROM dispatch_items WHERE record_id = ? AND revision = ?",
            key.recordId,
            key.revision,
        ) { Triple(it.string(0), it.long(1), it.long(2)) }.firstOrNull() ?: return null
        val intent = Intent.fromWire(head.first) ?: error("发送账里有不认识的意图")
        val targets = sql.query(
            "SELECT destination_id, send_mode, decision FROM dispatch_targets WHERE record_id = ? AND revision = ? ORDER BY rowid",
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
                eligible = destination != null && destination.active && destination.allows(intent),
            )
        }
        val sent = sql.query("SELECT 1 FROM sync_jobs WHERE record_id = ? AND revision = ?", key.recordId, key.revision) { it.long(0) }.isNotEmpty()
        return DispatchItem(key, intent, Instant.ofEpochSecond(head.second, head.third), sent, targets)
    }

    /** 没有还要等的决定就创建同步任务（已有则什么都不做）；返回这一次是不是真的创建了。 */
    private fun releaseIfReady(key: JobKey, now: Instant): Boolean {
        val item = item(key) ?: return false
        if (item.awaitingUser) return false
        val created = SqliteSyncJobStore.enqueueIn(sql, key, now)
        sql.update("UPDATE dispatch_items SET released = 1 WHERE record_id = ? AND revision = ?", key.recordId, key.revision)
        return created
    }

    private fun releaseReady(now: Instant) {
        sql.query("SELECT record_id, revision FROM dispatch_items WHERE released = 0 ORDER BY rowid") { it.toKey() }
            .forEach { releaseIfReady(it, now) }
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
    )

    companion object {
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
                revoked_at_nano INTEGER
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS dispatch_items (
                record_id TEXT NOT NULL,
                revision INTEGER NOT NULL CHECK (revision >= 1),
                intent TEXT NOT NULL CHECK (intent IN ('context', 'action_request')),
                created_at_s INTEGER NOT NULL,
                created_at_nano INTEGER NOT NULL,
                released INTEGER NOT NULL CHECK (released IN (0, 1)),
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
                PRIMARY KEY (record_id, revision, destination_id)
            )
            """.trimIndent(),
        )

        /**
         * 交给 SqliteRecordLibrary 的 extraSchema（打开数据库时在一个事务里按顺序幂等执行）：先建发送账的表，再建任务表，最后回填——
         * 回填排除还拦着的文字（`released = 0`）：它们没有任务是故意的，补上就等于绕过了“用户确认之前不发送”。
         */
        val SCHEMA: List<String> = TABLES + listOf(
            SqliteSyncJobStore.CREATE_TABLE,
            SqliteSyncJobStore.backfill(skippingHeldItems = true),
        )
    }
}
