package app.auri.sync.loop

import app.auri.capture.coordinator.Clock
import app.auri.sync.SyncLog
import app.auri.sync.engine.JobKey
import app.auri.sync.engine.RetryPolicy
import app.auri.sync.engine.SyncEngine
import app.auri.sync.engine.SyncJob
import app.auri.sync.engine.SyncJobStore
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** 平台的“有没有网”信号（Android 上由 ConnectivityManager 的回调提供）。 */
interface NetworkSignal {
    val available: Flow<Boolean>
}

/**
 * 同步循环：决定**什么时候**跑一轮同步。引擎保证一轮里做对（先落盘后发送、不确定先问、永久拒绝不重试），
 * 循环保证该跑的时候一定会跑，不该跑的时候不跑：
 * - 启动时跑一轮（接回上次进程被杀时留下的任务）；运行中出现新任务就跑；网络恢复就跑；[nudge]（刚配对、回到前台）就跑。
 * - 任务在等重试：到了重试时间跑一轮。重试时间由引擎按有界指数退避排好（5 秒起、翻倍、封顶 15 分钟）。
 * - 网关一直连不上（网络是好的）：同样按退避去探，封顶 15 分钟一次，不会密集，也不会放弃（网关可能晚点开机）。
 * - 网络断开：没有请求在途的任务改为等待网关，断网期间一律不探测。
 * - 被永久拒绝（停下等用户处理）的任务不产生任何计时与唤醒：没有“再试几次”的路。
 *
 * 几处设计要点：
 * - 所有触发只往一个“合并”的信号里投一票；跑一轮的是唯一的一个协程，一轮永远不会被别的触发或任务表的变化取消或重叠
 *   （取消一个发到一半的请求会留下“结果不确定”的任务）。一轮在跑时来的触发合并成之后的一轮。
 * - 计时器跟着任务表走：任务表一变就按最新的状态重新计时，所以不会按过期的状态响。
 * - 只在进程活着时工作：进程被杀之后什么也不会发生，下次启动的第一轮把任务接回来（没有用 WorkManager，见 README）。
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
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val rearm = MutableStateFlow(0L)

    @Volatile
    private var online = true

    @Volatile
    private var probeAttempt = 0

    fun start(scope: CoroutineScope): Job = scope.launch {
        wake.trySend(Unit)
        producer("network") { watchNetwork() }
        producer("jobs") { watchJobs() }
        runPasses()
    }

    /** 立刻跑一轮，并让探测的退避从头开始（刚配对完、回到前台、用户点了“现在同步”）。 */
    fun nudge() {
        probeAttempt = 0
        rearm.value += 1
        wake.trySend(Unit)
    }

    private suspend fun runPasses() {
        for (signal in wake) {
            log.event("loop.pass")
            engine.runOnce()
        }
    }

    /** 一个触发来源出了故障只记日志，不拖垮别的来源（比如系统的网络服务不可用时，任务表与手动触发照常工作）。 */
    private fun CoroutineScope.producer(name: String, body: suspend () -> Unit) = launch {
        try {
            body()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.event("loop.$name.failed ${failure::class.simpleName}")
        }
    }

    private suspend fun watchNetwork() {
        var first = true
        network.available.distinctUntilChanged().collect { available ->
            val initial = first
            first = false
            online = available
            probeAttempt = 0
            rearm.value += 1
            when {
                !available -> try {
                    engine.networkLost()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    log.event("loop.network.failed ${failure::class.simpleName}")
                }
                // 启动时的第一轮已经由 start 安排了
                !initial -> wake.trySend(Unit)
            }
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
