package app.auri.sync.resume

import app.auri.capture.coordinator.Clock
import app.auri.sync.SyncLog
import app.auri.sync.engine.RetryPolicy
import app.auri.sync.engine.SyncJob
import app.auri.sync.loop.restartingProducer
import app.auri.sync.machine.SyncState
import app.auri.sync.pairing.PairingState
import app.auri.sync.policy.DataFlowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * 需要系统在进程被杀之后把应用叫醒、接着发吗？文字被放行（[DataFlowState.textMayEnterGateway]）、已配对、连接没有停下等用户，
 * 并且还有任务没入库也没被永久拒绝，或者还欠着要向网关追加的 Delivery（[additionsDue]：用户确认过预览、网关上还没有它的投递）。
 * 停下等用户处理的任务，叫醒了也发不出去，所以不算；没配对、配对记录丢了、公钥不符、令牌被拒同理；
 * 没授权、开着严格仅本地、设置读不出来同理（评审 B1：醒来也不会发，一直要求叫醒只会白白打扰系统）。
 * 叫醒的计划和被叫醒后的收尾判断（[ResumeRunner]）共用这一个判断。
 */
fun needsResume(pairing: PairingState, jobs: List<SyncJob>, policy: DataFlowState, additionsDue: Boolean = false): Boolean =
    policy.textMayEnterGateway && pairing is PairingState.Paired && pairing.gateway.needsUser == null &&
        (additionsDue || jobs.any { it.state != SyncState.STORED && it.state != SyncState.BLOCKED })

/**
 * 进程活着时有 [SyncLoop][app.auri.sync.loop.SyncLoop] 负责什么时候发；进程被系统杀掉之后没人在跑了——
 * 这个计划让系统在“有活”期间始终留着一个叫醒（有网络时把应用拉起来），“没活”时取消，不白白打扰系统。
 *
 * 配对记录读出来之前不做决定（不能把上次登记的叫醒因为“还没读到”而取消）；结论不变不重复登记；
 * 系统暂时拒绝登记（异常）时记一条不含内容的日志，按有界退避重新来过，不丢掉这次请求。
 *
 * “欠不欠追加”（评审 H1）不是一路自己更新的输入，而是每个输入变一次就在这一刻现算一次：它依赖任务表（任务入库才到期），
 * 做成独立的流就会比任务表旧一拍，在“入库”的那一刻把叫醒取消、过一会儿才补登记——这中间进程被杀就没人再来做。
 * 重启后的第一次评估同样只凭磁盘上的账（任务表、发送账）算，所以提交之后、登记之前被杀也能在下一个进程里补上。
 *
 * @param additionsOwed 现在账上还欠不欠要向网关追加的 Delivery（实时读，不缓存）。
 * @param additionsChanged 发送账变了（决定、网关上有没有 Delivery、目的地缓存）：重新评估一次。任务表的变化已经在 [jobs] 里。
 */
class ResumePlanner(
    private val pairing: Flow<PairingState>,
    private val jobs: Flow<List<SyncJob>>,
    private val policy: Flow<DataFlowState>,
    private val scheduler: ResumeScheduler,
    private val clock: Clock,
    private val retry: RetryPolicy = RetryPolicy(),
    private val log: SyncLog = SyncLog.None,
    private val additionsOwed: suspend () -> Boolean = { false },
    private val additionsChanged: Flow<*> = emptyFlow<Unit>(),
) {
    fun start(scope: CoroutineScope): Job = scope.restartingProducer("resume", log, clock, retry) {
        combine(pairing, jobs, policy, additionsChanged.onStart { emit(Unit) }) { pairingState, list, dataFlow, _ -> Triple(pairingState, list, dataFlow) }
            .map { (pairingState, list, dataFlow) -> needsResume(pairingState, list, dataFlow, additionsOwed()) }
            .distinctUntilChanged()
            .collect { needed -> if (needed) scheduler.ensureScheduled() else scheduler.cancel() }
    }
}
