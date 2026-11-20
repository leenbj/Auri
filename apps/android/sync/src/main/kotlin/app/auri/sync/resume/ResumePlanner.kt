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
import kotlinx.coroutines.flow.flowOf

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
 */
class ResumePlanner(
    private val pairing: Flow<PairingState>,
    private val jobs: Flow<List<SyncJob>>,
    private val policy: Flow<DataFlowState>,
    private val scheduler: ResumeScheduler,
    private val clock: Clock,
    private val retry: RetryPolicy = RetryPolicy(),
    private val log: SyncLog = SyncLog.None,
    private val additionsDue: Flow<Boolean> = flowOf(false),
) {
    fun start(scope: CoroutineScope): Job = scope.restartingProducer("resume", log, clock, retry) {
        combine(pairing, jobs, policy, additionsDue, ::needsResume).distinctUntilChanged().collect { needed ->
            if (needed) scheduler.ensureScheduled() else scheduler.cancel()
        }
    }
}
