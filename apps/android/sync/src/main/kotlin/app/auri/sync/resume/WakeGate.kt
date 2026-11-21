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
 *
 * 第三个进门的是被叫醒的那一轮的收尾（再评 S8，[finishing]）：它判断“没活了”到 jobFinished 生效之间有个异步的缝，确认的预先登记会在这个缝里看到
 * “已经有一个”而什么也不做，叫醒随即被这一轮自己带走。所以收尾的判断与取消也在这把锁里。
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
     * 被叫醒的那一轮收尾时用（再评 S8）：在同一把锁里现算还有没有活 [needed]（读的是磁盘上的账，不能缓存），返回的就是 jobFinished 的“请稍后再叫醒我”。
     *
     * 要防的是：这一轮判断“没活了”之后、jobFinished 生效之前，用户确认了一个预览目标。确认的预先登记（[ahead]）问系统“有没有叫醒”——
     * getPendingJob 在 jobFinished 异步生效之前还看得见这个正在收尾的任务，于是它什么也不做，随后这个任务被收走：叫醒被这一轮自己的收尾带走了，
     * 账上却欠着追加。所以收尾判断与确认的“登记 + 提交”在同一把锁里，顺序只有两种：
     * - 确认在前：这里现算时账上已经欠着，返回 true，请系统稍后再叫；
     * - 确认在后：这里已经**同步**取消了系统里的叫醒（JobScheduler.cancel 立刻生效，jobFinished 不是），确认再登记时系统里没有任务，登记的是新的；
     *   这一轮随后迟到的 jobFinished 落在已被摘掉的执行上，系统忽略。
     * 没活时不看 [told]：它记的是“告诉过系统什么”，不是系统里此刻有什么——这一轮本身可能就是个门不知道的任务（持久任务重启后到点、系统自己重排）。
     * 有活时不动系统（调用方随后以 reschedule = true 收尾，系统会再叫），门里记成“不知道”：规划器的下一次评估会重新确认系统里有叫醒。
     * 取消被系统拒绝（异常）原样往外抛，[ResumeRunner] 把它当“没得出结论”、请系统稍后再叫醒，不丢叫醒。
     */
    suspend fun finishing(needed: suspend () -> Boolean): Boolean = lock.withLock {
        val now = needed()
        if (now) {
            told = null
        } else {
            scheduler.cancel()
            told = false
        }
        now
    }

    /**
     * 提交一个会让账上欠下活的决定之前用：先登记叫醒，再在同一把锁里 [commit]，返回 [commit] 的结果。
     * 系统拒绝登记（异常）就**不提交**、返回 null（再评 S8）：记一条只有事件名与异常类名的日志，账上什么也不改。
     * 以前是照常提交、让规划器随后补登记——可那要的是同一个系统调用，系统这时多半仍然拒绝，于是账上欠着追加、应用一被杀就再没有任何东西把它送出去，
     * 用户看到的却是“已确认”。现在宁可让用户知道没记下、再点一次。
     */
    suspend fun <T> ahead(commit: suspend () -> T): T? = lock.withLock {
        try {
            scheduler.ensureScheduled()
            told = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.event("resume.prewake-failed ${failure::class.simpleName}")
            return@withLock null
        }
        commit()
    }
}

/**
 * 发送账的壳：用户“确认发送”落账之前，先让 [WakeGate] 登记叫醒；登记不上就不落账，回 [DecideResult.WakeUnavailable]（再评 S8）。
 * 其余一切原样交给 [inner]。“不发送”不欠任何追加，不登记，也就不会因为系统拒绝登记而落不下账。
 */
class WakeAheadDispatchStore(private val inner: DispatchStore, private val wakes: WakeGate) : DispatchStore by inner {
    override suspend fun decide(key: JobKey, destinationId: String, choice: Choice, now: Instant): DecideResult =
        if (choice == Choice.CONFIRM) {
            wakes.ahead { inner.decide(key, destinationId, choice, now) } ?: DecideResult.WakeUnavailable
        } else {
            inner.decide(key, destinationId, choice, now)
        }
}
