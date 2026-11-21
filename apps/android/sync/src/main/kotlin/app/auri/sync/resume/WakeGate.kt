package app.auri.sync.resume

import app.auri.sync.SyncLog
import app.auri.sync.destinations.Choice
import app.auri.sync.destinations.DecideResult
import app.auri.sync.destinations.DispatchStore
import app.auri.sync.engine.JobKey
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 系统里那一个“叫醒”的唯一出入口（复评 R12）：规划器的登记、取消，和用户确认预览的那条路，都经过同一把锁。
 *
 * 要防的是这样一个时间窗：文字早已全部入库、叫醒已被取消，用户这时确认了一个预览目标——账上立刻欠下一笔“向网关追加 Delivery”，
 * 可叫醒要等规划器异步反应过来才登记；提交与登记之间进程被杀，就没有任何东西再把应用叫起来（下一个进程只有被叫起来才有机会凭磁盘上的账补登记）。
 * 所以顺序是反的：**先**在系统里登记叫醒（[ahead]；幂等，已经有就什么也不做），**再**提交决定。登记好了又没提交成，最坏是系统白叫醒一次、一看没活就收走。
 *
 * 并发：规划器先读账再登记 / 取消，读账与登记之间用户若确认了，规划器手里就是过时的“没活了”，随后的取消会把刚登记的叫醒抹掉。
 * 所以规划器“现算 + 动作”（[reconcile]）与“登记 + 提交”（[ahead]）在同一把锁里，谁都插不进谁的中间；
 * 先进的是规划器，用户的确认排在它后面——它的取消在确认之前发生，确认再登记；先进的是确认，规划器随后现算时账上已经欠着。
 *
 * 告诉过系统什么（[told]）只在锁里读写：结论与上一次告诉系统的一样就不再打扰系统；登记 / 取消抛异常时不记（重试时照旧会做）；
 * 预先登记之后记成“不知道”——提交可能什么也没改（早就决定过了），没有任何信号会叫规划器再算，而系统可能已把那个叫醒跑完收走，
 * 这时若还认为“系统里有”，之后真有新的活时规划器会因为“结论没变”而不登记。
 */
class WakeGate(private val scheduler: ResumeScheduler, private val log: SyncLog = SyncLog.None) {
    private val lock = Mutex()
    private var told: Boolean? = null

    /** 规划器用：在锁里现算要不要叫醒（[needed] 读的是磁盘上的账，不能缓存），与上次告诉系统的不同才登记或取消。系统拒绝登记的异常照常往外抛，由规划器记日志、退避重试。 */
    suspend fun reconcile(needed: suspend () -> Boolean) {
        lock.withLock {
            val now = needed()
            if (now != told) {
                if (now) scheduler.ensureScheduled() else scheduler.cancel()
                told = now
            }
        }
    }

    /**
     * 提交一个会让账上欠下活的决定之前用：先登记叫醒，再在同一把锁里 [commit]。
     * 系统拒绝登记（异常）不拦用户的决定：记一条只有事件名与异常类名的日志、照常提交，叫醒由规划器随后（发送账变了就会现算）补上，
     * 退化成本来的“提交之后异步登记”，不比以前差；决定本身在进程活着时由同步循环照常送出。
     */
    suspend fun <T> ahead(commit: suspend () -> T): T = lock.withLock {
        try {
            scheduler.ensureScheduled()
            told = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.event("resume.prewake-failed ${failure::class.simpleName}")
        }
        commit()
    }
}

/**
 * 发送账的壳：用户“确认发送”落账之前，先让 [WakeGate] 登记叫醒。其余一切原样交给 [inner]。
 * “不发送”不欠任何追加，不登记。
 */
class WakeAheadDispatchStore(private val inner: DispatchStore, private val wakes: WakeGate) : DispatchStore by inner {
    override suspend fun decide(key: JobKey, destinationId: String, choice: Choice, now: Instant): DecideResult =
        if (choice == Choice.CONFIRM) wakes.ahead { inner.decide(key, destinationId, choice, now) } else inner.decide(key, destinationId, choice, now)
}
