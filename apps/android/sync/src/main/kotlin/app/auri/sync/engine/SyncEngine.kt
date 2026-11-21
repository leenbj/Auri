package app.auri.sync.engine

import app.auri.capture.contract.ErrorCode
import app.auri.capture.coordinator.Clock
import app.auri.capture.machine.Outcome
import app.auri.sync.SyncLog
import app.auri.sync.discovery.GatewayLocator
import app.auri.sync.discovery.Located
import app.auri.sync.gateway.ContractAnswer
import app.auri.sync.gateway.ContractGuard
import app.auri.sync.gateway.GatewayClient
import app.auri.sync.gateway.GatewayResult
import app.auri.sync.gateway.RecordStatus
import app.auri.sync.gateway.RevisionSubmission
import app.auri.sync.http.HttpExchangeFactory
import app.auri.sync.http.TransportFailure
import app.auri.sync.machine.SyncEvent
import app.auri.sync.machine.SyncMachine
import app.auri.sync.machine.SyncState
import app.auri.sync.pairing.GatewayAddress
import app.auri.sync.pairing.NeedsUser
import app.auri.sync.pairing.PairedGateway
import app.auri.sync.pairing.PairingState
import app.auri.sync.pairing.PairingStore
import app.auri.sync.pairing.clearGatewayTooOld
import app.auri.sync.pairing.markGatewayTooOld
import app.auri.sync.policy.DataFlowPolicy
import app.auri.sync.policy.current
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 引擎每一次咨询状态机的记录：回放测试与诊断用。 */
data class SyncDecision(val key: JobKey, val from: SyncState, val event: SyncEvent, val outcome: Outcome, val to: SyncState)

/** 一次发送尝试的结局；一轮同步靠它决定要不要继续发下一条。 */
enum class SendResult {
    STORED,

    /** 连不上网关：这一轮不必再试别的任务。 */
    WAITING_GATEWAY,
    RETRY_LATER,

    /** 这条任务被永久拒绝，停下等用户；别的任务不受影响。 */
    BLOCKED,

    /** 公钥不符或令牌被拒：整个连接要用户处理，这一轮到此为止。 */
    CONNECTION_BLOCKED,

    /** 什么也没发（状态机不允许、没配对、另一路已经在发、出了故障被收住）。 */
    SKIPPED,
}

/**
 * 同步引擎：把本地待同步任务推到网关，并保证“不丢、不重、不死循环”。状态变化只有一条路：[deliver] 把事件交给
 * [SyncMachine]（合同 sync.machine.json 的转移表），机器说了算；引擎只负责造事件、落盘、做事。
 *
 * 几条硬规矩（每条都有测试把它打破一次、看测试变红）：
 * - **先落盘后发送**：SEND_STARTED 把任务记成“发送中、结果不确定”并落盘之后，才会有第一个字节离开手机。进程在途中被杀，
 *   下次启动 [recoverInterrupted] 把它接回来，并且仍然记着不确定。
 * - **不确定就先问**：只要任务带着“可能已送到”的标记，就先向网关查这条 Revision 的结果，网关明确说已入库（Record 没被删、
 *   是本机录的、这一版的哈希相同）才确认；明确说没有（404 或没有这个版本）才重新提交；说 Record 已删除、不是本机录的、
 *   同一版本号是另一份正文，都直接停下（重发也只会被拒绝）；问不到答案就不重发，稍后再问。
 * - **永久拒绝不重试**：4xx 类的明确拒绝让任务停在 BLOCKED，只有 USER_RESOLVED 能让它离开；没有“次数用尽”的捷径。
 *   sync.machine.json 里只有 VERSION_CONFLICT 一个“永久拒绝”事件，其余永久拒绝（记录已删除、不是来源设备、请求不合规、
 *   本机数据丢失）借用它，真正的原因记在 [SyncJob.blockedReason]。
 * - **连接问题挂在配对记录上**：公钥不符、令牌被拒是对所有任务都成立的事实，记进配对记录（needsUser）；
 *   在用户重新配对之前，一个带令牌的请求都不会发出，也没有“忽略并继续”。
 * - **只经 [PairingStore.update] 改配对记录**：用户刚刚重新配对写入的新记录，不会被晚到的旧观察覆盖。
 * - **没放行就一个字节也不发**（评审 B1）：配对只说明手机认得这台网关，不说明用户同意把文字送过去。每个可能联系网关的地方——
 *   一轮同步的开头、探测、一条任务开始发送之前、请求离开手机之前——先问 [DataFlowPolicy]；没授权、开着严格仅本地、读不出来，
 *   一律什么都不做（连探测、连 mDNS 都不去问）。请求离开之前才被收回的，退回等待网关，不留“可能已送到”的标记。
 *   问放行与请求交出去之间不留缝（再评 S3）：每次问放行记下授权设置的世代，每个请求交给 HTTP 客户端的前一刻再比一次（[Permit.gated]），
 *   中间有人动过设置（构造提交挂着、换账本、记地址时用户收回了授权）就什么都不发；第一次尝试里构造提交冻结了标签的，请求没发出去就放开冻结。
 * - **文字入库与 Delivery 各管各的**（评审 B2）：同步任务只管把 Revision 送进网关，不等任何人的决定；预览确认的 Destination 在用户确认之后才有 Delivery，
 *   由每一轮同步的最后一步 [addDeliveries] 向网关给这个**已入库的** Revision 追加（网关上幂等）。追加不改任务的状态：失败只影响它自己，按有界退避重来
 *   （[additionsRetryAt] 给 SyncLoop 排计时）；永久拒绝的记进发送账、不再重试。网关回答“有了”才记下，进程在任何一步被杀，下一轮都能从账里重新算出还欠哪些。
 * - **只和说合同 1.2 的网关说话**（评审 R7）：联系网关传任何东西之前，先问它会哪些合同版本（[ContractGuard]）。太旧就一个数据请求都不发，
 *   配对记录打上“网关太旧”、任务退回等待（不是永久拒绝）；更新网关之后这一轮的开头自己问出来、清掉标记，不需要重新配对。
 *   没问成（连不上、网关出错、读不懂）同样什么都不发，也不冤枉网关“太旧”。
 * - **结果跟着配对走**：请求发出时是哪份配对，结果就只对那份有效。途中用户重新配对、改配别的网关，晚到的结果（被拒、
 *   公钥不符、甚至“已入库”）全部作废，任务回到排队、对新配对重新来过；一轮同步里缓存的网关地址也只对同一份配对有效。
 *   “是不是那份配对”与“结果落账”是同一把锁里的同一件事（[settle]，再评 S2），不是先看一眼再写。
 *
 * 引擎自己不计时、不监听网络：什么时候调用 [runOnce]、[networkLost]、[networkChanged] 由 SyncLoop 决定。
 */
class SyncEngine(
    private val jobs: SyncJobStore,
    private val pairing: PairingStore,
    private val source: SubmissionSource,
    private val http: HttpExchangeFactory,
    private val locator: GatewayLocator,
    private val clock: Clock,
    private val policy: DataFlowPolicy,
    private val additions: DeliveryAdditions = DeliveryAdditions.None,
    private val retry: RetryPolicy = RetryPolicy(),
    private val log: SyncLog = SyncLog.None,
    private val onDecision: (SyncDecision) -> Unit = {},
    private val contract: ContractGuard = ContractGuard(),
) {
    private val passLock = Mutex()
    private val jobLocks = ConcurrentHashMap<JobKey, Mutex>()
    private val inFlight: MutableSet<JobKey> = ConcurrentHashMap.newKeySet()

    /** 追加 Delivery 连续失败了几次（只在内存里：进程重启后账还在，第一轮重新来过）。 */
    @Volatile
    private var additionsAttempts = 0

    private val additionsRetry = MutableStateFlow<Instant?>(null)

    /** 追加 Delivery 在等重试时，重试的时间（SyncLoop 据此排计时）；没有要重试的为 null。 */
    val additionsRetryAt: StateFlow<Instant?> get() = additionsRetry

    /** 平台最近一次报告的网络状态（[networkLost] 置位、[networkChanged] 清除）。引擎自己不监听网络，只记下被告知的结果。 */
    @Volatile
    private var offline = false

    /** 一轮同步：接回中断的、到点的重试回到排队、等待网关的探一下、然后按创建先后发送排队中的任务。适配器出的任何异常都被收在这里。 */
    suspend fun runOnce() {
        try {
            passLock.withLock {
                recoverInterrupted()
                retryDue()
                if (!mayTransmit(pairedGateway())) { // 文字没有放行（对配对着的这个网关）：这一轮到此为止，任务原样留在手机上（上面两步只改本地状态）
                    log.event("pass.held-by-policy")
                    return
                }
                val location = Location()
                healContract(location) // 配对记录上有“网关太旧”的标记：先问一次网关现在会不会说 1.2，会了就清掉，下面照常发
                if (usableGateway() == null) return // 没配对、记录丢失或连接要用户处理：什么都不能发
                probe(location)
                for (job in jobs.all()) {
                    if (job.state != SyncState.PENDING) continue
                    // 连接要用户处理时，后面的任务在 attempt() 里会因为配对记录上的标记而不发，不必在这里另外中止
                    if (send(job.key, location) == SendResult.WAITING_GATEWAY) {
                        parkPending()
                        return
                    }
                }
                addDeliveries(location)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.event("pass.failed ${failure::class.simpleName}")
        }
    }

    /** 发送一条任务：SEND_STARTED 之后查地址、必要时先问结果、再 PUT。状态机不允许开始时只留下一条“被拒绝”的记录。 */
    suspend fun send(key: JobKey): SendResult = send(key, Location())

    /** 重试时间到了的任务回到排队（到没到点由 [retryDue] 逐条判断）。 */
    suspend fun retryDue() {
        for (job in jobs.all()) if (job.state == SyncState.RETRY_SCHEDULED) retryDue(job.key)
    }

    /** 这条任务的重试计时器响了。没到点的（计时器是旧的）不动；不在等待重试的交给状态机去拒绝。 */
    suspend fun retryDue(key: JobKey) {
        val job = jobs.get(key) ?: return
        val at = job.retryAt
        if (job.state == SyncState.RETRY_SCHEDULED && at != null && at.isAfter(clock.now())) return
        deliver(key, SyncEvent.RETRY_DUE)
    }

    /**
     * 平台报告网络没了：没有请求在途的任务改为等待网关。在途的由请求自己的结果决定——
     * 引擎记下“现在没网”，请求若是因此失败，结果出来时按“等网关”处理（见 [retryLater]），不排重试倒计时。
     */
    suspend fun networkLost() {
        offline = true // 先记下再动任务：在途的请求这时完成，也能看到“没网”
        for (job in jobs.all()) {
            if (job.state in NO_REQUEST_IN_FLIGHT) deliver(job.key, SyncEvent.GATEWAY_UNREACHABLE)
        }
    }

    /**
     * 平台报告网络回来了，或默认网络换成了另一个：等待重试的任务不必再等旧的重试时间——旧的失败发生在另一个网络上。
     * 立刻回到排队，持久化的重试次数清零（退避从头算）。停下等用户的任务不动：网络变了不会让一个被拒绝的请求变得可以接受。
     */
    suspend fun networkChanged() {
        offline = false
        resetAdditionsBackoff() // 旧的失败发生在另一个网络上
        for (job in jobs.all()) {
            if (job.state == SyncState.RETRY_SCHEDULED) deliver(job.key, SyncEvent.RETRY_DUE) { it.copy(attempts = 0) }
        }
    }

    /** 追加 Delivery 的退避从头算（网络变了、用户点了“现在同步”）。 */
    fun resetAdditionsBackoff() {
        additionsAttempts = 0
        additionsRetry.value = null
    }

    /** 探测网关；找得到（含“有人应答但公钥不符”）就让等待网关的任务回到排队。 */
    suspend fun probe() = probe(Location())

    /** 卡在“发送中”却没有任何请求在途的任务（进程被杀、收尾失败）：按“请求可能已送到、结果未知”接回，马上可重试。 */
    suspend fun recoverInterrupted() {
        for (job in jobs.all()) {
            if (job.state == SyncState.SENDING && job.key !in inFlight) {
                deliver(job.key, SyncEvent.RETRYABLE_FAILURE) { it.copy(uncertain = true, retryAt = clock.now()) }
            }
        }
    }

    /** 用户处理了这条任务（比如确认后重试）：回到排队，次数清零。 */
    suspend fun userResolved(key: JobKey) {
        deliver(key, SyncEvent.USER_RESOLVED) { it.copy(attempts = 0) }
    }

    /** 重新配对之后：因公钥不符或令牌被拒而停下的任务回到排队。其余永久拒绝要用户另行处理。连接仍然要用户处理时什么也不做。 */
    suspend fun resolveAfterRepair() {
        if (usableGateway() == null) return
        for (job in jobs.all()) {
            if (job.state == SyncState.BLOCKED && job.blockedReason in CONNECTION_REASONS) userResolved(job.key)
        }
    }

    /**
     * 唯一能改任务状态的地方：把事件交给状态机，只有“转移”才落盘。每个任务一把锁，读-判-写不会被打断；
     * 晚到的结果（任务已被别的路径挪走）由状态机拒绝，不会把任务改回去。
     */
    internal suspend fun deliver(key: JobKey, event: SyncEvent, change: (SyncJob) -> SyncJob = { it }): SyncDecision? =
        jobLock(key).withLock {
            val job = jobs.get(key) ?: return@withLock null
            val rule = SyncMachine.resolve(job.state, event)
            val outcome = rule?.outcome ?: Outcome.REJECTED
            val to = rule?.to ?: job.state
            if (outcome == Outcome.TRANSITION) {
                val next = change(job).copy(state = to, updatedAt = clock.now())
                // 重试时间只属于“等待重试”，停下的原因只属于“需要用户处理”
                jobs.update(next.copy(retryAt = next.retryAt.takeIf { to == SyncState.RETRY_SCHEDULED }, blockedReason = next.blockedReason.takeIf { to == SyncState.BLOCKED }))
            }
            SyncDecision(key, job.state, event, outcome, to).also(::announce)
        }

    // ---- 发送一条任务 ----

    private suspend fun send(key: JobKey, location: Location): SendResult {
        if (!inFlight.add(key)) return SendResult.SKIPPED // 另一路正在发这一条
        try {
            return attempt(key, location)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.event("send.contained ${failure::class.simpleName}")
            return contain(key)
        } finally {
            inFlight.remove(key)
        }
    }

    private suspend fun attempt(key: JobKey, location: Location): SendResult {
        val job = jobs.get(key) ?: return SendResult.SKIPPED
        // 状态机不让这条任务开始发送（已入库、要用户处理、正在发送……）：让它记下这次拒绝就够了。
        // 这一步放在连接检查之前：即使整个连接要用户处理，被拒绝的事件也要留下记录。
        if (SyncMachine.resolve(job.state, SyncEvent.SEND_STARTED)?.outcome != Outcome.TRANSITION) {
            deliver(key, SyncEvent.SEND_STARTED)
            return SendResult.SKIPPED
        }
        val gateway = usableGateway() ?: return SendResult.SKIPPED
        if (!mayTransmit(gateway)) return SendResult.SKIPPED // 先问放行（授权只对给出它的那个网关算数）：没放行就连“开始发送”都不落盘

        var before = job
        // 先落盘：记成“发送中、结果不确定”，之后才会有请求离开手机
        val started = deliver(key, SyncEvent.SEND_STARTED) { current ->
            before = current
            current.copy(attempts = current.attempts + 1, uncertain = true, retryAt = null, blockedReason = null)
        }
        if (started?.outcome != Outcome.TRANSITION) return SendResult.SKIPPED
        val wasUncertain = before.uncertain

        // 落盘之后、联系网关之前再问一次放行：刚才被收回的，连网关都不去碰，退回等待（“不确定”还原成发送之前的样子）。
        // 每次问放行得到一张 Permit（记着问之前的世代，再评 S3）；每个请求交出去的前一刻 gated 再比一次，问与发之间有人动过设置就不发
        var permit = permitFor(gateway) ?: return waiting(gateway, key, wasUncertain)
        val located = permit.gated { location.get(gateway) } ?: return waiting(gateway, key, wasUncertain)
        val address = when (located) {
            is Located.Reachable -> located.address
            Located.Unreachable -> return waiting(gateway, key, wasUncertain)
            Located.KeyMismatch -> return connectionBlocked(key, gateway, NeedsUser.KEY_MISMATCH, BlockedReason.KEY_MISMATCH, wasUncertain)
        }
        // 定位网关是一次网络往返，用户可能在这期间收回了授权：每个回答回来之后、下一步之前都重新问一次，收回了就不再发任何请求（复评 R4）
        permit = permitFor(gateway) ?: return waiting(gateway, key, wasUncertain)
        rememberAddress(gateway, address)

        // 请求要离开手机了：先确认这台网关说的是手机说的合同版本（评审 R7）。放在构造提交之前——旧网关不该冻结意图标签（评审 M3）
        val client = GatewayClient(http.forPin(gateway.pin))
        val verdict = permit.gated { contract.check(gateway, address, client) } ?: return waiting(gateway, key, wasUncertain)
        when (verdict) {
            ContractAnswer.COMPATIBLE -> Unit
            ContractAnswer.TOO_OLD -> return tooOld(key, gateway, wasUncertain)
            ContractAnswer.UNCONFIRMED -> return waiting(gateway, key, wasUncertain)
            ContractAnswer.AUTH_REJECTED -> return connectionBlocked(key, gateway, NeedsUser.AUTH_REJECTED, BlockedReason.AUTH_REJECTED, wasUncertain)
            ContractAnswer.KEY_MISMATCH -> return connectionBlocked(key, gateway, NeedsUser.KEY_MISMATCH, BlockedReason.KEY_MISMATCH, wasUncertain)
        }
        // 合同版本探测也是一次网络往返：回来再问一次放行（复评 R4）；下面的状态查询与 PUT 之前只有本地操作，它们挂着的时候授权也可能被收回：
        // 所以状态查询、PUT 交出去的前一刻都用这张 Permit 再比一次世代（再评 S3）
        permit = permitFor(gateway) ?: return waiting(gateway, key, wasUncertain)

        // 换了网关的话，按旧网关规划的目标在这里（构造提交之前）先作废：旧网关的 Destination 编号不许进新网关的请求（评审 H2）。
        // 改账要先确认配对着的仍是这一份（复评 R5）：这一轮在网络上等的时候用户若换了网关，它是旧配对的残余，对新配对的账一个字也不许碰
        if (!fenced(gateway) { additions.useGateway(gateway.gatewayId) }) return stale(key)
        // 请求会不会发还没定：这里只看这条文字在不在、哈希是什么。**不构造提交**——构造提交就是冻结意图标签（评审 M3），
        // 而下面任何一步（问网关、最后一道放行、配对被换）都可能让这次尝试什么也没发就结束，那时用户还想改标签（复评 R13）
        val textSha256 = source.textSha256For(key) ?: return blocked(gateway, key, BlockedReason.LOCAL_DATA_MISSING)

        if (wasUncertain) {
            // 上一次可能已经送到：先问网关。只有网关明确说“已入库”才确认，明确说“没有”才往下重发；问不到就不重发。
            // 回答不在这里单独判断“还是不是这份配对”：下面每一种结论都经 settle，判断与落账在配对存储的同一把锁里（再评 S2）；
            // 往下发 PUT 的那一路则在冻结意图的围栏里判断
            val answer = permit.gated { client.recordStatus(address, gateway.token, key.recordId) } ?: return waiting(gateway, key, wasUncertain)
            when (answer) {
                is GatewayResult.Success -> when (answer.value.standing(key.revision, textSha256, gateway.deviceId)) {
                    Standing.SAME_STORED -> return stored(gateway, key, answer.value.destinations.filter { it.revision == key.revision }.map { it.destinationId })
                    Standing.MISSING -> Unit // 网关没有这一版：往下重新提交
                    // 下面三种网关都已经说明白了，再 PUT 只会白白把正文再发一遍、换来同样的拒绝
                    Standing.CONFLICTING -> return blocked(gateway, key, BlockedReason.VERSION_CONFLICT)
                    Standing.DELETED -> return blocked(gateway, key, BlockedReason.RECORD_DELETED)
                    Standing.FOREIGN -> return blocked(gateway, key, BlockedReason.NOT_ORIGIN_DEVICE)
                }
                is GatewayResult.Rejected -> if (!(answer.status == 404 && answer.code == ErrorCode.REQUEST_INVALID)) return failed(key, gateway, answer, wasUncertain)
                else -> return failed(key, gateway, answer, wasUncertain)
            }
        }
        // 请求离开手机之前的最后一问（复评 R4）：状态查询的回答在路上时授权可能收回了。
        // 刚才被收回的，什么都没发出去，退回等待（“不确定”还原成发送之前的样子）
        permit = permitFor(gateway) ?: return waiting(gateway, key, wasUncertain)
        // 请求马上要离开手机：到这一刻才为它构造提交，构造提交就是冻结意图标签（评审 M3、复评 R13）。
        // 没网、等网关、排队、被最后一道放行拦下、问网关的路上被作废——这些尝试都没走到这里，标签都还能改；构造出来的提交带的是冻结的那个值。
        // 冻结是写账，和别的写账一样在围栏里（再评 S2）：判断“还是这一份配对”与冻结在配对存储的同一把锁里，旧配对的残余尝试不会在改配之后去冻结
        val built = pairing.whileCurrent(gateway) { Built(source.submissionFor(key)) } ?: return stale(key)
        val submission = built.submission ?: return blocked(gateway, key, BlockedReason.LOCAL_DATA_MISSING)
        // 构造提交是一次本地数据库操作，会挂起：上面那一问是在它之前，挂着的时候授权被收回的话，这里比出来世代变了——PUT 不发（再评 S3）。
        // 第一次尝试（之前没有任何一次可能送出过）：请求既然没离开手机，刚冻结的标签放开；上一次可能已经送到的，网关上也许已经有那个标签，冻结不放
        val put = permit.gated { client.putRevision(address, gateway.token, submission) }
            ?: return waiting(gateway, key, wasUncertain, releaseIntent = !wasUncertain)
        return when (put) {
            is GatewayResult.Success -> stored(gateway, key, put.value.deliveries.filter { it.revision == key.revision }.map { it.destinationId })
            else -> failed(key, gateway, put, wasUncertain)
        }
    }

    /** 出了意料之外的故障：已经开始发送的任务不能卡在“发送中”，按可重试处理；还没开始的由状态机拒绝，保持原样。 */
    private suspend fun contain(key: JobKey): SendResult {
        try {
            deliver(key, SyncEvent.RETRYABLE_FAILURE) { it.copy(retryAt = clock.now().plusMillis(retry.delayMs(it.attempts.coerceAtLeast(1)))) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.event("send.contain-failed ${failure::class.simpleName}")
        }
        return SendResult.SKIPPED
    }

    // ---- 结局 ----

    /**
     * 请求发出时是哪份配对，结果就只对那份有效（复评 R5），而且“是不是那份”与“把结果记下来”是同一件事、不是两步（再评 S2）：
     * 判断、任务的转移、发送账的改动都在配对存储的同一把锁里，用户的重新配对排在它们之后（[PairingStore.whileCurrent]）。
     * 以前是“先 stillPaired 看一眼、再转移”：刚看完、还没写，用户改配了，旧网关的“已入库/被拒绝”照样记到新配对的任务上，
     * 文字就永远到不了新网关。不是这一份了就整个丢掉（[stale]）：任务回到排队，对新配对重新来过。
     * [outcome] 在锁里执行，应当很短（本地写）；里面不许再调用配对存储。
     */
    private suspend fun settle(gateway: PairedGateway, key: JobKey, outcome: suspend () -> SendResult): SendResult =
        pairing.whileCurrent(gateway, outcome) ?: stale(key)

    /** 网关说这一版已经入库：先记账（网关上有哪些 Delivery）再确认入库，两步在同一个围栏里；进程死在两步之间，下一轮先问网关、同样记账（追加是幂等的，最坏只多一个请求）。 */
    private suspend fun stored(gateway: PairedGateway, key: JobKey, deliveredTo: Collection<String>): SendResult = settle(gateway, key) {
        additions.gatewayHas(key, deliveredTo)
        deliver(key, SyncEvent.STORED_CONFIRMED) { it.copy(uncertain = false) }
        SendResult.STORED
    }

    /** 发请求时的那份配对，现在还是当前的配对吗？用户重新配对、改配另一个网关、取消配对之后就不是了。只用来省掉对旧配对的无谓处理；写账、转移任务一律走 [settle] 或 [fenced]。 */
    private suspend fun stillPaired(gateway: PairedGateway): Boolean = (pairing.load() as? PairingState.Paired)?.gateway?.isSamePairing(gateway) == true

    /**
     * 请求在途中用户换了配对：这个结果是旧配对（旧令牌、旧公钥、也许是另一个网关）得到的，对新配对没有意义——
     * 旧令牌被拒不代表新令牌会被拒，旧网关说“已入库”不代表新网关有。丢掉它，任务回到排队、按新配对重新来过；
     * 请求可能已经送到过旧网关，所以仍记成结果不确定（先问新网关，再决定发不发）。
     */
    private suspend fun stale(key: JobKey): SendResult {
        deliver(key, SyncEvent.RETRYABLE_FAILURE) { it.copy(attempts = 0, uncertain = true, retryAt = clock.now()) }
        return SendResult.RETRY_LATER
    }

    /**
     * 请求没有离开手机（连不上、被放行拦下）：“不确定”回到发送之前的样子。
     * [releaseIntent]：这次尝试构造提交时冻结了标签、请求却没发出去——放开冻结（[SubmissionSource.unfreezeUnsent]），
     * 与任务的转移在同一个围栏里：旧配对的残余尝试不会在改配之后去动账。只有第一次尝试可以放开（之前没有任何一次可能送出过）。
     */
    private suspend fun waiting(gateway: PairedGateway, key: JobKey, wasUncertain: Boolean, releaseIntent: Boolean = false): SendResult = settle(gateway, key) {
        if (releaseIntent) source.unfreezeUnsent(key)
        deliver(key, SyncEvent.GATEWAY_UNREACHABLE) { it.copy(uncertain = wasUncertain) }
        SendResult.WAITING_GATEWAY
    }

    /**
     * 请求可能已经送到：保持“不确定”（SEND_STARTED 已经记下）。
     * 平台已经报告没网时，这次失败多半就是断网造成的：排重试倒计时没有意义（网络回来时才是该再试的时候），
     * 按“等网关”处理，仍然记着结果不确定。
     */
    private suspend fun retryLater(gateway: PairedGateway, key: JobKey): SendResult = settle(gateway, key) {
        if (offline) {
            deliver(key, SyncEvent.GATEWAY_UNREACHABLE)
            SendResult.WAITING_GATEWAY
        } else {
            deliver(key, SyncEvent.RETRYABLE_FAILURE) { it.copy(retryAt = clock.now().plusMillis(retry.delayMs(it.attempts))) }
            SendResult.RETRY_LATER
        }
    }

    /** 永久拒绝（网关已经处理并拒绝，没有“可能入库”的悬念）。事件借用 VERSION_CONFLICT，原因记在任务上。 */
    private suspend fun blocked(gateway: PairedGateway, key: JobKey, reason: BlockedReason): SendResult = settle(gateway, key) {
        deliver(key, SyncEvent.VERSION_CONFLICT) { it.copy(uncertain = false, blockedReason = reason) }
        SendResult.BLOCKED
    }

    /**
     * 公钥不符或令牌被拒。先记进配对记录（之后不再发任何带令牌的请求），再把这条任务停下。
     * 两步的顺序不能反（进程死在中间时，配对记录上有标记、任务只是回到排队，比“任务停了、记录上没标记、没人会去解除”好）；
     * 第二步仍在围栏里：两步之间用户改配了，任务不能被旧配对的结论停下（再评 S2）。
     */
    private suspend fun connectionBlocked(key: JobKey, gateway: PairedGateway, needs: NeedsUser, reason: BlockedReason, wasUncertain: Boolean): SendResult {
        // 配对记录里的这份已经不是发请求时的那份（途中重新配对了）：这是旧配对的结论，作废。写记录这一步本身就是原子的比对
        if (!recordConnectionProblem(gateway, needs)) return stale(key)
        val event = if (needs == NeedsUser.KEY_MISMATCH) SyncEvent.KEY_MISMATCH else SyncEvent.AUTH_REVOKED
        return settle(gateway, key) {
            deliver(key, event) { it.copy(uncertain = wasUncertain, blockedReason = reason) }
            SendResult.CONNECTION_BLOCKED
        }
    }

    /**
     * 网关太旧（评审 R7）：什么都没发。先记进配对记录（之后不再发带令牌的请求，直到更新之后自动恢复），再把任务退回等待网关——
     * 不是永久拒绝：用户更新网关之后，同一条文字照常发出去，没有一条会被永久拒绝。
     */
    private suspend fun tooOld(key: JobKey, gateway: PairedGateway, wasUncertain: Boolean): SendResult {
        log.event("contract.gateway-too-old")
        if (!pairing.markGatewayTooOld(gateway)) return stale(key) // 途中重新配对了：这是旧配对的结论，作废
        return waiting(gateway, key, wasUncertain)
    }

    /** 把连接问题记进配对记录（之后不再发任何带令牌的请求）。配对记录已经不是 [gateway] 那份时什么也不写，返回 false。 */
    private suspend fun recordConnectionProblem(gateway: PairedGateway, needs: NeedsUser): Boolean =
        pairing.update { current -> if (current.isSamePairing(gateway)) current.copy(needsUser = needs) else null }

    private suspend fun failed(key: JobKey, gateway: PairedGateway, result: GatewayResult<*>, wasUncertain: Boolean): SendResult = when (result) {
        is GatewayResult.Success<*> -> error("failed() 只处理失败的结果")
        is GatewayResult.Garbled -> retryLater(gateway, key) // 2xx 但读不懂：请求确实到了，网关可能已入库
        is GatewayResult.TransportFailed -> when (result.failure) {
            is TransportFailure.Unreachable -> waiting(gateway, key, wasUncertain)
            is TransportFailure.KeyMismatch -> connectionBlocked(key, gateway, NeedsUser.KEY_MISMATCH, BlockedReason.KEY_MISMATCH, wasUncertain)
            is TransportFailure.Interrupted -> retryLater(gateway, key)
        }

        is GatewayResult.Rejected -> when {
            result.status == 401 || result.code == ErrorCode.GATEWAY_AUTH_REVOKED || result.code == ErrorCode.GATEWAY_AUTH_INVALID ->
                connectionBlocked(key, gateway, NeedsUser.AUTH_REJECTED, BlockedReason.AUTH_REJECTED, wasUncertain)

            result.code == ErrorCode.RECORD_VERSION_CONFLICT -> blocked(gateway, key, BlockedReason.VERSION_CONFLICT)
            result.code == ErrorCode.RECORD_DELETED -> blocked(gateway, key, BlockedReason.RECORD_DELETED)
            result.code == ErrorCode.RECORD_NOT_ORIGIN_DEVICE -> blocked(gateway, key, BlockedReason.NOT_ORIGIN_DEVICE)
            result.retryable -> retryLater(gateway, key)
            else -> blocked(gateway, key, BlockedReason.REQUEST_REJECTED)
        }
    }

    // ---- 追加 Delivery（评审 B2）----

    /**
     * 给已入库的 Revision 追加账上欠着的 Delivery：用户确认过的预览目的地、以及入库时放不下或网关没建的。每个 Destination 一个请求。
     * 出任何问题都不碰同步任务的状态：连不上或暂时失败按有界退避重来；连接问题记进配对记录；网关明确拒绝的记账、不再重试。
     */
    private suspend fun addDeliveries(location: Location) {
        // 这一遍从头到尾对同一个网关说话：先定下它，再对账（换了网关，按旧网关规划的目标作废，评审 H2），再看欠什么
        val gateway = usableGateway()
        if (gateway != null && !fenced(gateway) { additions.useGateway(gateway.gatewayId) }) return // 读到配对之后又换了：旧配对的残余不碰新配对的账（复评 R5）
        val due = additions.due()
        if (due.isEmpty()) {
            resetAdditionsBackoff()
            return
        }
        if (offline) return // 断网期间不探测：网络回来时 SyncLoop 会再跑一轮
        var permit = permitFor(gateway) ?: return
        if (gateway == null) return
        val located = permit.gated { location.get(gateway) } ?: return
        val address = when (located) {
            is Located.Reachable -> located.address
            Located.Unreachable -> return additionsRetryLater()
            Located.KeyMismatch -> {
                recordConnectionProblem(gateway, NeedsUser.KEY_MISMATCH)
                return
            }
        }
        permit = permitFor(gateway) ?: return // 定位网关是一次网络往返：回来再问一次放行（复评 R4）
        rememberAddress(gateway, address)
        val client = GatewayClient(http.forPin(gateway.pin))
        // 追加也是联系网关：先确认版本（旧网关没有这个端点，发过去只会被当成永久拒绝，评审 R7）
        when (permit.gated { contract.check(gateway, address, client) } ?: return) {
            ContractAnswer.COMPATIBLE -> Unit
            ContractAnswer.TOO_OLD -> {
                pairing.markGatewayTooOld(gateway)
                return additionsRetryLater() // 追加没有任务表的计时器：排下一次重问，更新网关之后 runOnce 开头自己恢复
            }

            ContractAnswer.UNCONFIRMED -> return additionsRetryLater()
            ContractAnswer.AUTH_REJECTED -> {
                recordConnectionProblem(gateway, NeedsUser.AUTH_REJECTED)
                return
            }

            ContractAnswer.KEY_MISMATCH -> {
                recordConnectionProblem(gateway, NeedsUser.KEY_MISMATCH)
                return
            }
        }
        for (addition in due) {
            permit = permitFor(gateway) ?: return // 请求离开手机之前再问一次放行
            val result = permit.gated { client.addDeliveries(address, gateway.token, addition.key.recordId, addition.key.revision, addition.intent, listOf(addition.destinationId)) } ?: return
            if (!stillPaired(gateway)) return // 途中换了配对：这个结果对新配对没有意义，下一轮对新配对重新算
            val ids = listOf(addition.destinationId)
            when (result) {
                is GatewayResult.Success -> {
                    // 记账与“还是这一份配对”的判断在同一把锁里（复评 R5）：不是了就把这一遍丢掉，下一轮对新配对重新算
                    val recorded = fenced(gateway) {
                        if (result.value.deliveries.any { it.destinationId == addition.destinationId }) additions.gatewayHas(addition.key, ids)
                        else additions.refused(addition.key, ids, Refusal.NOT_CREATED)
                    }
                    if (!recorded) return
                }

                is GatewayResult.Garbled -> return additionsRetryLater() // 请求到了、回答读不懂：追加是幂等的，再问一次
                is GatewayResult.TransportFailed -> when (result.failure) {
                    is TransportFailure.KeyMismatch -> {
                        recordConnectionProblem(gateway, NeedsUser.KEY_MISMATCH)
                        return
                    }

                    else -> return additionsRetryLater()
                }

                is GatewayResult.Rejected -> when {
                    result.status == 401 || result.code == ErrorCode.GATEWAY_AUTH_REVOKED || result.code == ErrorCode.GATEWAY_AUTH_INVALID -> {
                        recordConnectionProblem(gateway, NeedsUser.AUTH_REJECTED)
                        return
                    }

                    result.retryable -> return additionsRetryLater()
                    else -> if (!fenced(gateway) { additions.refused(addition.key, ids, Refusal.REJECTED) }) return
                }
            }
        }
        resetAdditionsBackoff()
    }

    private fun additionsRetryLater() {
        additionsAttempts += 1
        additionsRetry.value = clock.now().plusMillis(retry.delayMs(additionsAttempts))
    }

    // ---- 其余 ----

    /**
     * 配对记录上有“网关太旧”的标记（评审 R7）：问一次网关现在会哪些版本。已经更新了就清掉标记（不需要重新配对），这一轮照常发；
     * 还是旧的、或这会儿问不成，保持原样，欠着追加的话给它排下一次重问（任务那边有 SyncLoop 对等待网关的探测退避）。
     */
    private suspend fun healContract(location: Location) {
        val gateway = (pairing.load() as? PairingState.Paired)?.gateway?.takeIf { it.needsUser == NeedsUser.GATEWAY_TOO_OLD } ?: return
        // 探一次也是联系网关（定位、问能力各一个请求）：和发送一样，每个请求之前问放行并在交出去的前一刻比世代（再评 S3）；
        // 没放行就不探，标记原样留着，下一轮再来
        var permit = permitFor(gateway) ?: return
        val located = permit.gated { location.get(gateway) } ?: return
        val answer = when (located) {
            is Located.Reachable -> {
                permit = permitFor(gateway) ?: return // 定位是一次网络往返：回来再问一次放行
                permit.gated { contract.check(gateway, located.address, GatewayClient(http.forPin(gateway.pin))) } ?: return
            }

            Located.Unreachable -> ContractAnswer.UNCONFIRMED
            Located.KeyMismatch -> ContractAnswer.KEY_MISMATCH
        }
        when (answer) {
            ContractAnswer.COMPATIBLE -> pairing.clearGatewayTooOld(gateway)
            ContractAnswer.TOO_OLD, ContractAnswer.UNCONFIRMED -> if (additions.due().isNotEmpty()) additionsRetryLater()
            ContractAnswer.AUTH_REJECTED -> recordConnectionProblem(gateway, NeedsUser.AUTH_REJECTED)
            ContractAnswer.KEY_MISMATCH -> recordConnectionProblem(gateway, NeedsUser.KEY_MISMATCH)
        }
    }

    private suspend fun probe(location: Location) {
        val gateway = usableGateway() ?: return
        val permit = permitFor(gateway) ?: return // 探测本身就是一次联系
        val waiting = jobs.all().filter { it.state == SyncState.WAITING_GATEWAY }
        if (waiting.isEmpty()) return
        when (permit.gated { location.get(gateway) } ?: return) {
            // 公钥不符也算“有人应答”：让下一次发送在“发送中”撞上它，按状态机报 KEY_MISMATCH（此时没有任何带令牌的请求发出）
            is Located.Reachable, Located.KeyMismatch -> waiting.forEach { deliver(it.key, SyncEvent.GATEWAY_REACHABLE) }
            Located.Unreachable -> Unit
        }
    }

    /** 这一轮找不到网关：排队中的全部改为等待网关，不对每条各探一遍。 */
    private suspend fun parkPending() {
        for (job in jobs.all()) if (job.state == SyncState.PENDING) deliver(job.key, SyncEvent.GATEWAY_UNREACHABLE)
    }

    /**
     * 文字可以进 [gateway] 吗（复评 R3：授权是给某一个网关的，这里问的是“对我要联系的这个网关”，不是“全局有没有开”）。
     * [DataFlowPolicy.stored] 承诺读不出来就回答“不许”；万一它抛了，外层的 catch 收住，同样什么都不发。没有配对的网关也就没有授权。
     */
    private suspend fun mayTransmit(gateway: PairedGateway?): Boolean = policy.current(gateway?.identity).textMayEnterGateway

    /**
     * 一张放行（再评 S3）：记着问放行**之前**的授权世代。问放行是一次挂起的读，问完到请求交给 HTTP 客户端之间还有别的本地操作
     * （构造提交、换账本、记地址），授权随时可能在这中间被收回——“问完就算”留下的就是这条缝。
     * [gated] 在请求交出去的前一刻再比一次世代：变过就一个请求也不发（返回 null），调用方退回等待，下一轮重新问。
     * 世代只管“还没交出去”的请求：已经交给客户端的（连接、握手在路上），收回授权召不回来。
     */
    private inner class Permit(private val generation: Long) {
        suspend fun <T : Any> gated(request: suspend () -> T): T? = if (policy.generation == generation) request() else null
    }

    /** 文字可以进 [gateway] 吗？可以就给一张 [Permit]，不可以是 null。世代在问之前记下：问的过程中有人改了设置，世代就与这一张不同。 */
    private suspend fun permitFor(gateway: PairedGateway?): Permit? {
        val generation = policy.generation
        return if (mayTransmit(gateway)) Permit(generation) else null
    }

    /**
     * 只在“配对着的仍是 [gateway] 这一份配对”的前提下改发送账里的缓存与目标（复评 R5）。判断与改动在配对存储的同一把锁里，
     * 所以没有“刚判断完、还没写，用户重新配对了”的缝：一轮同步在网络上等的时候用户换了网关，醒来的这一轮（旧配对的残余）一个字也写不进新配对的账。
     * 已经不是这一份就什么也不碰、返回 false，调用方把这一轮丢掉。
     */
    private suspend fun fenced(gateway: PairedGateway, change: suspend () -> Unit): Boolean = pairing.whileCurrent(gateway, change) != null

    /** 配对着的网关，不论连接有没有问题（同意是对它的身份给的，连接要用户处理时也一样）。 */
    private suspend fun pairedGateway(): PairedGateway? = (pairing.load() as? PairingState.Paired)?.gateway

    private suspend fun usableGateway(): PairedGateway? = (pairing.load() as? PairingState.Paired)?.gateway?.takeIf { it.needsUser == null }

    private suspend fun rememberAddress(gateway: PairedGateway, address: GatewayAddress) {
        if (gateway.lastGoodAddress == address) return
        try {
            pairing.update { current -> if (current.isSamePairing(gateway) && current.lastGoodAddress != address) current.copy(lastGoodAddress = address) else null }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.event("address.remember-failed ${failure::class.simpleName}")
        }
    }

    private fun jobLock(key: JobKey): Mutex = jobLocks.computeIfAbsent(key) { Mutex() }

    private fun announce(decision: SyncDecision) {
        log.event("decision from=${decision.from} event=${decision.event} outcome=${decision.outcome} to=${decision.to}")
        try {
            onDecision(decision)
        } catch (_: Exception) {
            // 观察者的故障不能影响同步
        }
    }

    /** 一轮同步里“网关在哪”只问一次——对同一份配对。途中换了配对，旧网关的地址不能拿给新网关的指纹去连。 */
    private inner class Location {
        private var forPairing: PairedGateway? = null
        private var value: Located? = null

        suspend fun get(gateway: PairedGateway): Located {
            val cached = value
            if (cached != null && forPairing?.isSamePairing(gateway) == true) return cached
            return locator.locate(gateway).also {
                value = it
                forPairing = gateway
            }
        }
    }

    /** 网关对“这一版在不在”的回答，换算成引擎的下一步。先看 Record 本身（已删除、不是本机录的），再看这一版。 */
    private enum class Standing { SAME_STORED, MISSING, CONFLICTING, DELETED, FOREIGN }

    private fun RecordStatus.standing(revision: Int, textSha256: String, deviceId: String): Standing = when {
        deleted -> Standing.DELETED
        originDeviceId != deviceId -> Standing.FOREIGN
        else -> when (revisions.firstOrNull { it.revision == revision }?.textSha256) {
            null -> Standing.MISSING
            textSha256 -> Standing.SAME_STORED
            else -> Standing.CONFLICTING
        }
    }

    /** 在围栏里构造出的提交（null = 本机已经读不到这一版）：外层的 null 留给“已经不是这份配对”。 */
    private class Built(val submission: RevisionSubmission?)

    private companion object {
        val NO_REQUEST_IN_FLIGHT = setOf(SyncState.PENDING, SyncState.RETRY_SCHEDULED, SyncState.WAITING_GATEWAY)
        val CONNECTION_REASONS = setOf(BlockedReason.KEY_MISMATCH, BlockedReason.AUTH_REJECTED)
    }
}
