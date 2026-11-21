package app.auri.sync.testing

import app.auri.sync.engine.JobKey
import app.auri.sync.engine.NoSuchJobException
import app.auri.sync.engine.SyncJob
import app.auri.sync.engine.SyncJobStore
import app.auri.sync.machine.SyncState
import app.auri.sync.pairing.PairedGateway
import app.auri.sync.pairing.PairingState
import app.auri.sync.pairing.PairingStore
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 内存里的同步任务表：引擎测试用；与 SQLite 实现共用 [SyncJobStoreContract]。 */
class InMemorySyncJobStore : SyncJobStore {
    private val lock = Any()
    private val jobs = LinkedHashMap<JobKey, SyncJob>()
    private val changes = MutableStateFlow<List<SyncJob>>(emptyList())

    override suspend fun enqueue(key: JobKey, now: Instant): Boolean = synchronized(lock) {
        if (key in jobs) return@synchronized false
        jobs[key] = SyncJob(key, SyncState.PENDING, attempts = 0, uncertain = false, retryAt = null, blockedReason = null, createdAt = now, updatedAt = now)
        changes.value = jobs.values.toList()
        true
    }

    override suspend fun get(key: JobKey): SyncJob? = synchronized(lock) { jobs[key] }

    override suspend fun all(): List<SyncJob> = synchronized(lock) { jobs.values.sortedBy { it.createdAt } }

    override suspend fun update(job: SyncJob) = synchronized(lock) {
        if (job.key !in jobs) throw NoSuchJobException(job.key)
        jobs[job.key] = job
        changes.value = jobs.values.toList()
    }

    override fun observe(): Flow<List<SyncJob>> = changes
}

/** 内存里的配对记录（引擎测试用；真实的加密落盘实现是 FilePairingStore）。 */
class InMemoryPairingStore(initial: PairedGateway? = null) : PairingStore {
    @Volatile
    var state: PairingState = initial?.let { PairingState.Paired(it) } ?: PairingState.NotPaired
    val saves = AtomicInteger()

    val gateway: PairedGateway? get() = (state as? PairingState.Paired)?.gateway

    override suspend fun load(): PairingState = state

    // 写、读改写与 whileCurrent 同一把锁（真实的存储也是）：whileCurrent 的 block 挂起时，别的写要等着
    private val lock = Mutex()

    override suspend fun save(gateway: PairedGateway) = lock.withLock {
        saves.incrementAndGet()
        state = PairingState.Paired(gateway)
    }

    override suspend fun update(transform: (PairedGateway) -> PairedGateway?): Boolean = lock.withLock {
        val current = gateway ?: return@withLock false
        val updated = transform(current) ?: return@withLock false
        saves.incrementAndGet()
        state = PairingState.Paired(updated)
        true
    }

    override suspend fun <T : Any> whileCurrent(gateway: PairedGateway, block: suspend () -> T): T? = lock.withLock {
        if (this.gateway?.isSamePairing(gateway) == true) block() else null
    }

    override suspend fun clear() = lock.withLock {
        state = PairingState.NotPaired
    }
}
