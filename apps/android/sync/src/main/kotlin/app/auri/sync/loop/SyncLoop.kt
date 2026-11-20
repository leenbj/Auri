package app.auri.sync.loop

import app.auri.capture.coordinator.Clock
import app.auri.sync.SyncLog
import app.auri.sync.engine.DeliveryAdditions
import app.auri.sync.engine.JobKey
import app.auri.sync.engine.RetryPolicy
import app.auri.sync.engine.SyncEngine
import app.auri.sync.engine.SyncJob
import app.auri.sync.engine.SyncJobStore
import app.auri.sync.engine.dueUpdates
import app.auri.sync.machine.SyncState
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * 平台的“有没有网”信号（Android 上由 ConnectivityManager 的回调提供）。
 * 每次默认网络变化发一个值，**不去重**：没有网络是 false；有网络是 true，默认网络换成另一个可用网络时再发一个 true。
 */
interface NetworkSignal {
    val available: Flow<Boolean>
}

/**
 * 同步循环：决定**什么时候**跑一轮同步。引擎保证一轮里做对（先落盘后发送、不确定先问、永久拒绝不重试），
 * 循环保证该跑的时候一定会跑，不该跑的时候不跑：
 * - 启动时跑一轮（接回上次进程被杀时留下的任务）；运行中出现新任务就跑；网络恢复或默认网络换了一个就跑；[nudge]（刚配对、回到前台）就跑。
 * - 任务在等重试：到了重试时间跑一轮。重试时间由引擎按有界指数退避排好（5 秒起、翻倍、封顶 15 分钟）。
 * - 网关一直连不上（网络是好的）：同样按退避去探，封顶 15 分钟一次，不会密集，也不会放弃（网关可能晚点开机）。
 * - 网络断开：没有请求在途的任务改为等待网关，断网期间一律不探测；在途的请求若随之失败，结果出来时也按等待网关处理。
 * - 网络回来或默认网络换了一个：等待重试的任务立刻回到排队、退避从头算（旧的失败发生在另一个网络上）。
 * - 被永久拒绝（停下等用户处理）的任务不产生任何计时与唤醒：没有“再试几次”的路。
 * - 触发来源（任务表的变化、网络信号）暂时出故障：记一条不含内容的日志，按有界退避重新订阅，不会永远失聪。
 * - 要给已入库的 Revision 追加 Delivery（预览确认之后；评审 B2）：账上欠的集合一变就跑一轮；追加失败了，引擎排好重试时间
 *   （[SyncEngine.additionsRetryAt]，有界退避），到点再跑。追加不动任务表，所以任务表的变化叫不醒它，要靠这一路。
 *
 * 几处设计要点：
 * - 所有触发只往一个“合并”的信号里投一票；跑一轮的是唯一的一个协程，一轮永远不会被别的触发或任务表的变化取消或重叠
 *   （取消一个发到一半的请求会留下“结果不确定”的任务）。一轮在跑时来的触发合并成之后的一轮。
 * - 计时器跟着任务表走：任务表一变就按最新的状态重新计时，所以不会按过期的状态响。
 * - 只在进程活着时工作：进程被杀之后由 [ResumePlanner][app.auri.sync.resume.ResumePlanner] 登记在系统里的叫醒（Android 上是 JobScheduler）
 *   把应用拉起来，或下次启动的第一轮，把任务接回来。
 *
 * [start] 只调用一次；返回的 Job 取消之后，循环里的一切都停下。
 */
class SyncLoop(
    private val engine: SyncEngine,
    private val jobs: SyncJobStore,
    private val network: NetworkSignal,
    private val clock: Clock,
    private val retry: RetryPolicy = RetryPolicy(),
    private val log: SyncLog = SyncLog.None,
    private val additions: DeliveryAdditions = DeliveryAdditions.None,
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val rearm = MutableStateFlow(0L)

    @Volatile
    private var online = true

    @Volatile
    private var networkSeen = false

    @Volatile
    private var probeAttempt = 0

    fun start(scope: CoroutineScope): Job = scope.launch {
        wake.trySend(Unit)
        producer("network") { watchNetwork() }
        producer("jobs") { watchJobs() }
        producer("additions") { watchAdditions() }
        runPasses()
    }

    /** 立刻跑一轮，并让探测的退避从头开始（刚配对完、回到前台、用户点了“现在同步”）。 */
    fun nudge() {
        probeAttempt = 0
        engine.resetAdditionsBackoff()
        rearm.value += 1
        wake.trySend(Unit)
    }

    private suspend fun runPasses() {
        for (signal in wake) {
            log.event("loop.pass")
            engine.runOnce()
        }
    }

    /** 触发来源出故障的处置（只记日志、有界退避重新订阅）见 [restartingProducer]。 */
    private fun CoroutineScope.producer(name: String, body: suspend () -> Unit) = restartingProducer(name, log, clock, retry, body)

    private suspend fun watchNetwork() {
        // 不去重：手机常开着移动数据，Wi-Fi 回来时系统只是把默认网络从蜂窝换回 Wi-Fi，信号一直是“可用”；
        // 去重会把这一刻吞掉，补传就要干等探测的退避（最长 15 分钟）
        network.available.collect { available ->
            // 重新订阅之后的第一个值不算“启动时的第一个”：订阅中断期间可能错过了网络变化，当作变化处理更稳妥
            val initial = !networkSeen
            networkSeen = true
            online = available
            probeAttempt = 0
            rearm.value += 1
            when {
                !available -> containNetworkFailure { engine.networkLost() }
                // 启动时的第一轮已经由 start 安排了
                !initial -> {
                    // 网络回来了或换了一个：等待重试的任务不必再等旧的重试时间（旧的失败发生在另一个网络上）
                    containNetworkFailure { engine.networkChanged() }
                    wake.trySend(Unit)
                }
            }
        }
    }

    /** 账上欠的 Delivery 变了就跑一轮；追加在等重试时，到点再跑。 */
    private suspend fun watchAdditions() = coroutineScope {
        launch { additions.dueUpdates().collect { due -> if (due.isNotEmpty()) wake.trySend(Unit) } }
        engine.additionsRetryAt.collectLatest { at ->
            if (at != null) {
                delay(Duration.between(clock.now(), at).toMillis().coerceAtLeast(0))
                wake.trySend(Unit)
            }
        }
    }

    private suspend fun containNetworkFailure(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.event("loop.network.failed ${failure::class.simpleName}")
        }
    }

    private suspend fun watchJobs() {
        var previous: Set<JobKey>? = null
        combine(jobs.observe(), rearm) { list, _ -> list }
            .onEach { list ->
                val keys = list.mapTo(HashSet()) { it.key }
                val before = previous
                previous = keys
                // 没见过的任务（刚发布的 Revision）要马上发；第一次看到任务表时，排队中的也要发（别漏掉启动时那一刻之后才入队的）
                val fresh = if (before == null) list.any { it.state == SyncState.PENDING } else keys.any { it !in before }
                if (fresh) {
                    probeAttempt = 0
                    wake.trySend(Unit)
                }
            }
            .collectLatest { list -> sleepThenWake(list) }
    }

    /** 按任务表的现状计时。每次任务表变化都会取消并重来，所以不会按过期的状态响。 */
    private suspend fun sleepThenWake(list: List<SyncJob>) = coroutineScope {
        // 重试时间：每次任务表变化只响一次；这一轮会改变任务的状态，产生新的变化，再重新计时
        val due = list.filter { it.state == SyncState.RETRY_SCHEDULED }.mapNotNull { it.retryAt }.minOrNull()
        if (due != null) {
            launch {
                delay(Duration.between(clock.now(), due).toMillis().coerceAtLeast(0))
                wake.trySend(Unit)
            }
        }
        // 网关连不上：按退避探测，探测本身不改变任务表，所以要自己接着计下一次
        if (online && list.any { it.state == SyncState.WAITING_GATEWAY }) {
            launch {
                while (true) {
                    delay(retry.delayMs(probeAttempt + 1))
                    probeAttempt += 1
                    if (online) wake.trySend(Unit)
                }
            }
        }
    }
}
