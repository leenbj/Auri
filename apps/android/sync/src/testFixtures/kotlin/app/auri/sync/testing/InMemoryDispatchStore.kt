package app.auri.sync.testing

import app.auri.capture.contract.RevisionSource
import app.auri.sync.destinations.Choice
import app.auri.sync.destinations.DecideResult
import app.auri.sync.destinations.DispatchItem
import app.auri.sync.destinations.DispatchStore
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
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 内存里的“磁盘”：重启（[InMemoryDispatchFixture.reopen]）后新实例读到的还是它。 */
class InMemoryDispatchDisk(val jobs: InMemorySyncJobStore = InMemorySyncJobStore()) {
    class Row(val sendMode: SendMode, var decision: TargetDecision, var delivery: GatewayDelivery = GatewayDelivery.NOT_YET, var refusal: Refusal? = null)

    class Item(var intent: Intent, val createdAt: Instant, val order: Long, var intentLocked: Boolean = false) {
        val rows = LinkedHashMap<String, Row>()
    }

    val lock = Mutex()

    /** 缓存属于哪台网关：[GatewayIdentity.ledgerKey]（编号加公钥指纹，复评三 S4）。 */
    var gatewayKey: String? = null
    val cache = LinkedHashMap<String, Destination>()
    val items = LinkedHashMap<JobKey, Item>()
    var defaultIntent: Intent = Intent.CONTEXT
    var nextOrder = 0L
    val signal = MutableStateFlow(0L)
}

/**
 * [DispatchStore] 的内存实现（引擎与界面测试用）。与 :syncplatform 的 SQLite 实现共用 [DispatchStoreContract]，
 * 所以这里的“文字入库不等决定、决定幂等、意图在构造提交时冻结、欠的 Delivery 怎么记”就是真实实现的语义。
 */
class InMemoryDispatchStore(private val disk: InMemoryDispatchDisk) : DispatchStore {
    private val jobs get() = disk.jobs

    override suspend fun destinations(): List<Destination> = disk.lock.withLock { sortedCache() }

    override suspend fun applyDestinations(gateway: GatewayIdentity, destinations: List<Destination>, authoritative: Boolean, now: Instant) = disk.lock.withLock {
        if (disk.gatewayKey != gateway.ledgerKey) switchGateway(gateway)
        if (authoritative) disk.cache.keys.retainAll(destinations.map { it.destinationId }.toSet())
        // 撤销只进不退（评审 H3）：缓存里已经是撤销的，较早算好的回答再说它有效也不算
        destinations.forEach { incoming -> disk.cache[incoming.destinationId] = incoming.copy(revokedAt = incoming.revokedAt ?: disk.cache[incoming.destinationId]?.revokedAt) }
        // 暂停的（凭证被撤销）：还没建 Delivery 的目标记成没送达，之后恢复也不补发（评审 B3）；用户已经跳过的不动
        destinations.filter { it.suspended }.forEach { suspended ->
            disk.items.values.forEach { item ->
                item.rows[suspended.destinationId]?.let { row ->
                    if (row.delivery == GatewayDelivery.NOT_YET && row.decision != TargetDecision.SKIPPED) {
                        row.delivery = GatewayDelivery.REFUSED
                        row.refusal = Refusal.SUSPENDED
                    }
                }
            }
        }
        bump()
    }

    override suspend fun forgetDestinations(now: Instant) = disk.lock.withLock {
        switchGateway(null)
        bump()
    }

    override suspend fun useGateway(gateway: GatewayIdentity) = disk.lock.withLock {
        if (disk.gatewayKey != gateway.ledgerKey) {
            switchGateway(gateway)
            bump()
        }
    }

    override suspend fun item(key: JobKey): DispatchItem? = disk.lock.withLock { disk.items[key]?.let { view(key, it) } }

    override suspend fun items(limit: Int): List<DispatchItem> = disk.lock.withLock {
        disk.items.entries.sortedByDescending { it.value.order }.take(limit).map { (key, item) -> view(key, item) }
    }

    override suspend fun awaiting(): List<DispatchItem> = disk.lock.withLock {
        disk.items.entries.sortedByDescending { it.value.order }.map { (key, item) -> view(key, item) }.filter { it.awaitingUser }
    }

    override suspend fun itemsFor(destinationId: String, limit: Int): List<DispatchItem> = disk.lock.withLock {
        disk.items.entries.filter { destinationId in it.value.rows }.sortedByDescending { it.value.order }.take(limit).map { (key, item) -> view(key, item) }
    }

    override suspend fun decide(key: JobKey, destinationId: String, choice: Choice, now: Instant): DecideResult = disk.lock.withLock {
        val item = disk.items[key] ?: return@withLock DecideResult.NotFound
        val row = item.rows[destinationId] ?: return@withLock DecideResult.NotFound
        if (row.decision != TargetDecision.AWAITING) return@withLock DecideResult.AlreadyDecided(row.decision)
        if (!eligible(item, destinationId) || row.delivery == GatewayDelivery.REFUSED) return@withLock DecideResult.NotEligible
        row.decision = if (choice == Choice.CONFIRM) TargetDecision.CONFIRMED else TargetDecision.SKIPPED
        bump()
        DecideResult.Decided
    }

    override suspend fun requestResend(key: JobKey, destinationId: String, intent: Intent, now: Instant): ResendResult = disk.lock.withLock {
        if (jobs.get(key) == null) return@withLock ResendResult.NotEligible
        val existingItem = disk.items[key]
        val effectiveIntent = existingItem?.intent ?: intent
        val destination = disk.cache[destinationId] ?: return@withLock ResendResult.NotEligible
        if (!destination.receivesNewText || !destination.allows(effectiveIntent)) return@withLock ResendResult.NotEligible
        val existingTarget = existingItem?.rows?.get(destinationId)
        if (existingTarget != null) {
            if (existingTarget.delivery == GatewayDelivery.REFUSED) return@withLock ResendResult.NotEligible
            if (existingTarget.decision == TargetDecision.AUTO || existingTarget.decision == TargetDecision.CONFIRMED) {
                return@withLock ResendResult.AlreadyRequested
            }
            existingTarget.decision = TargetDecision.CONFIRMED
        } else {
            val item = existingItem ?: InMemoryDispatchDisk.Item(intent = effectiveIntent, createdAt = now, order = ++disk.nextOrder, intentLocked = false)
                .also { disk.items[key] = it }
            item.rows[destinationId] = InMemoryDispatchDisk.Row(destination.sendMode, TargetDecision.CONFIRMED)
        }
        bump()
        ResendResult.Requested
    }

    override suspend fun setIntent(key: JobKey, intent: Intent, now: Instant): IntentResult = disk.lock.withLock {
        val item = disk.items[key] ?: return@withLock IntentResult.NotFound
        if (item.intentLocked) return@withLock IntentResult.Locked
        if (item.intent == intent) return@withLock IntentResult.Unchanged
        item.intent = intent
        bump()
        IntentResult.Changed
    }

    override suspend fun publicationFor(key: JobKey): Publication = disk.lock.withLock {
        val item = disk.items[key] ?: return@withLock Publication.StoreOnly
        if (!item.intentLocked) {
            item.intentLocked = true
            bump()
        }
        val ids = item.rows.entries
            .filter { (id, row) -> eligible(item, id) && row.delivery != GatewayDelivery.REFUSED && (row.decision == TargetDecision.AUTO || row.decision == TargetDecision.CONFIRMED) }
            .map { it.key }
            .take(Publication.MAX_DESTINATIONS)
        Publication(item.intent, ids)
    }

    override suspend fun unfreezeUnsent(key: JobKey) = disk.lock.withLock {
        val item = disk.items[key]
        if (item != null && item.intentLocked) {
            item.intentLocked = false
            bump()
        }
    }

    override suspend fun due(): List<DeliveryAddition> = disk.lock.withLock {
        disk.items.entries.sortedBy { it.value.order }.flatMap { (key, item) ->
            if (jobs.get(key)?.state != SyncState.STORED) return@flatMap emptyList()
            item.rows.entries
                .filter { (id, row) ->
                    row.delivery == GatewayDelivery.NOT_YET &&
                        (row.decision == TargetDecision.AUTO || row.decision == TargetDecision.CONFIRMED) && eligible(item, id)
                }
                .map { (id, _) -> DeliveryAddition(key, item.intent, id) }
        }
    }

    override suspend fun gatewayHas(key: JobKey, destinationIds: Collection<String>) = disk.lock.withLock {
        destinationIds.forEach { id ->
            disk.items[key]?.rows?.get(id)?.let { row ->
                row.delivery = GatewayDelivery.CREATED
                row.refusal = null
            }
        }
        bump()
    }

    override suspend fun refused(key: JobKey, destinationIds: Collection<String>, reason: Refusal) = disk.lock.withLock {
        destinationIds.forEach { id ->
            disk.items[key]?.rows?.get(id)?.let { row ->
                if (row.delivery == GatewayDelivery.NOT_YET) {
                    row.delivery = GatewayDelivery.REFUSED
                    row.refusal = reason
                }
            }
        }
        bump()
    }

    override suspend fun defaultIntent(): Intent = disk.lock.withLock { disk.defaultIntent }

    override suspend fun setDefaultIntent(intent: Intent) = disk.lock.withLock {
        disk.defaultIntent = intent
        bump()
    }

    override fun changes(): Flow<Long> = disk.signal

    /** 发布一条 Revision 的那个事务里发生的事（Android：库的 publicationStep）。测试专用入口：文字一定登记同步任务；ASR 初稿另外规划目标。 */
    suspend fun publish(key: JobKey, source: RevisionSource, now: Instant) = disk.lock.withLock {
        jobs.enqueue(key, now)
        if (source == RevisionSource.ASR && key.revision == 1) { // 自动发送/预览确认只管 ASR 初稿（ADR-0009）；重新转录出的新版本只入库
            val item = InMemoryDispatchDisk.Item(disk.defaultIntent, now, disk.nextOrder++)
            sortedCache().filter { it.receivesNewText }.forEach { destination ->
                item.rows[destination.destinationId] = InMemoryDispatchDisk.Row(
                    destination.sendMode,
                    if (destination.sendMode == SendMode.AUTO_SEND) TargetDecision.AUTO else TargetDecision.AWAITING,
                )
            }
            disk.items[key] = item
        }
        bump()
    }

    // ---- 内部（都在锁里）----

    /** 缓存与按它规划的、网关上还没有 Delivery 的目标一起作废（评审 H2）：编号只在那个网关上有意义。null = 忘掉缓存、不属于任何网关。 */
    private fun switchGateway(gateway: GatewayIdentity?) {
        disk.cache.clear()
        disk.gatewayKey = gateway?.ledgerKey
        disk.items.values.forEach { item -> item.rows.values.removeAll { it.delivery == GatewayDelivery.NOT_YET } }
    }

    private fun bump() {
        disk.signal.value += 1
    }

    private fun sortedCache(): List<Destination> = disk.cache.values.sortedWith(compareBy({ it.createdAt }, { it.destinationId }))

    private fun eligible(item: InMemoryDispatchDisk.Item, destinationId: String): Boolean {
        val destination = disk.cache[destinationId] ?: return false
        return destination.receivesNewText && destination.allows(item.intent)
    }

    private suspend fun view(key: JobKey, item: InMemoryDispatchDisk.Item): DispatchItem = DispatchItem(
        key = key,
        intent = item.intent,
        createdAt = item.createdAt,
        intentLocked = item.intentLocked,
        targets = item.rows.map { (id, row) ->
            DispatchTarget(id, disk.cache[id]?.displayName, row.sendMode, row.decision, eligible(item, id), row.delivery, row.refusal)
        },
    )
}

/** 内存版的发送账加它所在的磁盘，给 [DispatchStoreContract] 用。 */
class InMemoryDispatchFixture(private val disk: InMemoryDispatchDisk = InMemoryDispatchDisk()) : DispatchFixture {
    private val memory = InMemoryDispatchStore(disk)
    override val store: DispatchStore get() = memory
    override val jobs get() = disk.jobs

    override suspend fun publish(key: JobKey, source: RevisionSource, now: Instant) = memory.publish(key, source, now)

    override fun reopen(): DispatchFixture = InMemoryDispatchFixture(disk)
}
