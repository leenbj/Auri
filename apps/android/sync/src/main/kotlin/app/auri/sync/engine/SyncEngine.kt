package app.auri.sync.engine

import app.auri.capture.contract.ErrorCode
import app.auri.capture.coordinator.Clock
import app.auri.capture.machine.Outcome
import app.auri.sync.SyncLog
import app.auri.sync.discovery.GatewayLocator
import app.auri.sync.discovery.Located
import app.auri.sync.gateway.GatewayClient
import app.auri.sync.gateway.GatewayResult
import app.auri.sync.gateway.RecordStatus
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
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
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
 * - **结果跟着配对走**：请求发出时是哪份配对，结果就只对那份有效。途中用户重新配对、改配别的网关，晚到的结果（被拒、
 *   公钥不符、甚至“已入库”）全部作废，任务回到排队、对新配对重新来过；一轮同步里缓存的网关地址也只对同一份配对有效。
 *
 * 引擎自己不计时、不监听网络：什么时候调用 [runOnce]、[networkLost] 由 SyncLoop 决定。
 */
class SyncEngine(
    private val jobs: SyncJobStore,
    private val pairing: PairingStore,
    private val source: SubmissionSource,
    private val http: HttpExchangeFactory,
    private val locator: GatewayLocator,
    private val clock: Clock,
    private val retry: RetryPolicy = RetryPolicy(),
    private val log: SyncLog = SyncLog.None,
    private val onDecision: (SyncDecision) -> Unit = {},
) {
    private val passLock = Mutex()
    private val jobLocks = ConcurrentHashMap<JobKey, Mutex>()
    private val inFlight: MutableSet<JobKey> = ConcurrentHashMap.newKeySet()

    /** 一轮同步：接回中断的、到点的重试回到排队、等待网关的探一下、然后按创建先后发送排队中的任务。适配器出的任何异常都被收在这里。 */
    suspend fun runOnce() {
        try {
            passLock.withLock {
                recoverInterrupted()
                retryDue()
                if (usableGateway() == null) return // 没配对、记录丢失或连接要用户处理：什么都不能发
                val location = Location()
                probe(location)
                for (job in jobs.all()) {
                    if (job.state != SyncState.PENDING) continue
                    // 连接要用户处理时，后面的任务在 attempt() 里会因为配对记录上的标记而不发，不必在这里另外中止
                    if (send(job.key, location) == SendResult.WAITING_GATEWAY) {
                        parkPending()
                        return
                    }
                }
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

    /** 平台报告网络没了：没有请求在途的任务改为等待网关。在途的由请求自己的结果决定。 */
    suspend fun networkLost() {
        for (job in jobs.all()) {
            if (job.state in NO_REQUEST_IN_FLIGHT) deliver(job.key, SyncEvent.GATEWAY_UNREACHABLE)
        }
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

        var before = job
        // 先落盘：记成“发送中、结果不确定”，之后才会有请求离开手机
        val started = deliver(key, SyncEvent.SEND_STARTED) { current ->
            before = current
            current.copy(attempts = current.attempts + 1, uncertain = true, retryAt = null, blockedReason = null)
        }
        if (started?.outcome != Outcome.TRANSITION) return SendResult.SKIPPED
        val wasUncertain = before.uncertain

        val submission = source.submissionFor(key) ?: return blocked(key, BlockedReason.LOCAL_DATA_MISSING)
        val address = when (val located = location.get(gateway)) {
            is Located.Reachable -> located.address
            Located.Unreachable -> return waiting(key, wasUncertain)
            Located.KeyMismatch -> return connectionBlocked(key, gateway, NeedsUser.KEY_MISMATCH, BlockedReason.KEY_MISMATCH, wasUncertain)
        }
        rememberAddress(gateway, address)

        val client = GatewayClient(http.forPin(gateway.pin))
        if (wasUncertain) {
            // 上一次可能已经送到：先问网关。只有网关明确说“已入库”才确认，明确说“没有”才往下重发；问不到就不重发。
            val answer = client.recordStatus(address, gateway.token, key.recordId)
            if (!stillPaired(gateway)) return stale(key)
            when (answer) {
                is GatewayResult.Success -> when (answer.value.standing(key.revision, submission.textSha256, gateway.deviceId)) {
                    Standing.SAME_STORED -> return stored(key)
                    Standing.MISSING -> Unit // 网关没有这一版：往下重新提交
                    // 下面三种网关都已经说明白了，再 PUT 只会白白把正文再发一遍、换来同样的拒绝
                    Standing.CONFLICTING -> return blocked(key, BlockedReason.VERSION_CONFLICT)
                    Standing.DELETED -> return blocked(key, BlockedReason.RECORD_DELETED)
                    Standing.FOREIGN -> return blocked(key, BlockedReason.NOT_ORIGIN_DEVICE)
                }
                is GatewayResult.Rejected -> if (!(answer.status == 404 && answer.code == ErrorCode.REQUEST_INVALID)) return failed(key, gateway, answer, wasUncertain)
                else -> return failed(key, gateway, answer, wasUncertain)
            }
        }
        val put = client.putRevision(address, gateway.token, submission)
        if (!stillPaired(gateway)) return stale(key)
        return when (put) {
            is GatewayResult.Success -> stored(key)
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

    private suspend fun stored(key: JobKey): SendResult {
        deliver(key, SyncEvent.STORED_CONFIRMED) { it.copy(uncertain = false) }
        return SendResult.STORED
    }

    /** 发请求时的那份配对，现在还是当前的配对吗？用户重新配对、改配另一个网关、取消配对之后就不是了。 */
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

    /** 请求没有离开手机（连不上）：“不确定”回到发送之前的样子。 */
    private suspend fun waiting(key: JobKey, wasUncertain: Boolean): SendResult {
        deliver(key, SyncEvent.GATEWAY_UNREACHABLE) { it.copy(uncertain = wasUncertain) }
        return SendResult.WAITING_GATEWAY
    }

    /** 请求可能已经送到：保持“不确定”（SEND_STARTED 已经记下）。 */
    private suspend fun retryLater(key: JobKey): SendResult {
        deliver(key, SyncEvent.RETRYABLE_FAILURE) { it.copy(retryAt = clock.now().plusMillis(retry.delayMs(it.attempts))) }
        return SendResult.RETRY_LATER
    }

    /** 永久拒绝（网关已经处理并拒绝，没有“可能入库”的悬念）。事件借用 VERSION_CONFLICT，原因记在任务上。 */
    private suspend fun blocked(key: JobKey, reason: BlockedReason): SendResult {
        deliver(key, SyncEvent.VERSION_CONFLICT) { it.copy(uncertain = false, blockedReason = reason) }
        return SendResult.BLOCKED
    }

    /** 公钥不符或令牌被拒。先记进配对记录（之后不再发任何带令牌的请求），再把这条任务停下。 */
    private suspend fun connectionBlocked(key: JobKey, gateway: PairedGateway, needs: NeedsUser, reason: BlockedReason, wasUncertain: Boolean): SendResult {
        // 配对记录里的这份已经不是发请求时的那份（途中重新配对了）：这是旧配对的结论，作废。写记录这一步本身就是原子的比对
        if (!pairing.update { current -> if (current.isSamePairing(gateway)) current.copy(needsUser = needs) else null }) return stale(key)
        val event = if (needs == NeedsUser.KEY_MISMATCH) SyncEvent.KEY_MISMATCH else SyncEvent.AUTH_REVOKED
        deliver(key, event) { it.copy(uncertain = wasUncertain, blockedReason = reason) }
        return SendResult.CONNECTION_BLOCKED
    }

    private suspend fun failed(key: JobKey, gateway: PairedGateway, result: GatewayResult<*>, wasUncertain: Boolean): SendResult = when (result) {
        is GatewayResult.Success<*> -> error("failed() 只处理失败的结果")
        is GatewayResult.Garbled -> retryLater(key) // 2xx 但读不懂：请求确实到了，网关可能已入库
        is GatewayResult.TransportFailed -> when (result.failure) {
            is TransportFailure.Unreachable -> waiting(key, wasUncertain)
            is TransportFailure.KeyMismatch -> connectionBlocked(key, gateway, NeedsUser.KEY_MISMATCH, BlockedReason.KEY_MISMATCH, wasUncertain)
            is TransportFailure.Interrupted -> retryLater(key)
        }

        is GatewayResult.Rejected -> when {
            result.status == 401 || result.code == ErrorCode.GATEWAY_AUTH_REVOKED || result.code == ErrorCode.GATEWAY_AUTH_INVALID ->
                connectionBlocked(key, gateway, NeedsUser.AUTH_REJECTED, BlockedReason.AUTH_REJECTED, wasUncertain)

            result.code == ErrorCode.RECORD_VERSION_CONFLICT -> blocked(key, BlockedReason.VERSION_CONFLICT)
            result.code == ErrorCode.RECORD_DELETED -> blocked(key, BlockedReason.RECORD_DELETED)
            result.code == ErrorCode.RECORD_NOT_ORIGIN_DEVICE -> blocked(key, BlockedReason.NOT_ORIGIN_DEVICE)
            result.retryable -> retryLater(key)
            else -> blocked(key, BlockedReason.REQUEST_REJECTED)
        }
    }

    // ---- 其余 ----

    private suspend fun probe(location: Location) {
        val waiting = jobs.all().filter { it.state == SyncState.WAITING_GATEWAY }
        if (waiting.isEmpty()) return
        val gateway = usableGateway() ?: return
        when (location.get(gateway)) {
            // 公钥不符也算“有人应答”：让下一次发送在“发送中”撞上它，按状态机报 KEY_MISMATCH（此时没有任何带令牌的请求发出）
            is Located.Reachable, Located.KeyMismatch -> waiting.forEach { deliver(it.key, SyncEvent.GATEWAY_REACHABLE) }
            Located.Unreachable -> Unit
        }
    }

    /** 这一轮找不到网关：排队中的全部改为等待网关，不对每条各探一遍。 */
    private suspend fun parkPending() {
        for (job in jobs.all()) if (job.state == SyncState.PENDING) deliver(job.key, SyncEvent.GATEWAY_UNREACHABLE)
    }

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

    private fun PairedGateway.isSamePairing(other: PairedGateway) = deviceId == other.deviceId && pairedAt == other.pairedAt && pin == other.pin

    private companion object {
        val NO_REQUEST_IN_FLIGHT = setOf(SyncState.PENDING, SyncState.RETRY_SCHEDULED, SyncState.WAITING_GATEWAY)
        val CONNECTION_REASONS = setOf(BlockedReason.KEY_MISMATCH, BlockedReason.AUTH_REJECTED)
    }
}
