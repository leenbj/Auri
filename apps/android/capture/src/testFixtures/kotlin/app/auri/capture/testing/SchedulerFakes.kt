package app.auri.capture.testing

import app.auri.capture.adapter.TranscriptionOutcome
import app.auri.capture.contract.ErrorCode
import app.auri.capture.library.RecordLibrary
import app.auri.capture.scheduler.InMemoryJobStore
import app.auri.capture.scheduler.JobDecision
import app.auri.capture.scheduler.JobStore
import app.auri.capture.scheduler.ModelAvailability
import app.auri.capture.scheduler.ModelReadiness
import app.auri.capture.scheduler.ResourceMonitor
import app.auri.capture.scheduler.ResourceReason
import app.auri.capture.scheduler.ResourceStatus
import app.auri.capture.scheduler.SchedulerConfig
import app.auri.capture.scheduler.TranscriptionJob
import app.auri.capture.scheduler.TranscriptionScheduler
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withContext

/** 假的“模型是否可用”：测试用 [missing] / [ready] 切换，像模型管理器在下载完成或模型损坏时那样。 */
class FakeModelAvailability(initial: ModelReadiness = ModelReadiness.Ready(0)) : ModelAvailability {
    private val state = MutableStateFlow(initial)
    private var generation = 0L
    override val readiness: StateFlow<ModelReadiness> get() = state

    fun missing(code: ErrorCode = ErrorCode.MODEL_NOT_INSTALLED) {
        state.value = ModelReadiness.Missing(code)
    }

    /** 每次都是一个新的 generation：同一个模型重新装一遍也算“变了”。 */
    fun ready() {
        generation += 1
        state.value = ModelReadiness.Ready(generation)
    }
}

/** 假的资源监视器：[status] 是 [check] 此刻的答案；[set] 同时发出“变了”的信号（像温度回调、省电模式广播）。 */
class FakeResourceMonitor : ResourceMonitor {
    @Volatile
    var status: ResourceStatus = ResourceStatus.Ok
    val checks = AtomicInteger()

    /** 非空时 [check] 抛它：模拟平台接口自己的故障。 */
    @Volatile
    var failure: RuntimeException? = null
    private val signal = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    override val changes get() = signal

    override fun check(): ResourceStatus {
        checks.incrementAndGet()
        failure?.let { throw it }
        return status
    }

    /** 状态变了，并通知调度器。 */
    fun set(newStatus: ResourceStatus) {
        status = newStatus
        signal.tryEmit(Unit)
    }

    /** 状态变了但**没有**通知（比如广播丢了）：只有定时重查才能发现。 */
    fun setSilently(newStatus: ResourceStatus) {
        status = newStatus
    }

    fun low(reason: ResourceReason) = set(ResourceStatus.Low(reason))

    fun ok() = set(ResourceStatus.Ok)
}

/**
 * 一套接好的调度器 + 假适配器，跑在 [scope]（runTest 的 TestScope）的虚拟时间里。
 * [settle] 只处理“当前虚拟时刻”已经就绪的工作；需要让时间流逝时用 [advance]（重试与重查定时器靠它）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SchedulerRig(
    private val scope: TestScope,
    val library: RecordLibrary = InMemoryRecordLibrary(),
    val config: SchedulerConfig = SchedulerConfig(),
    val store: JobStore = InMemoryJobStore(),
    val adapter: FakeTranscriptionAdapter = FakeTranscriptionAdapter(),
    val models: FakeModelAvailability = FakeModelAvailability(),
    val resources: FakeResourceMonitor = FakeResourceMonitor(),
    schedulerScope: CoroutineScope = scope.backgroundScope,
    /** 两套调度器共用一个任务库（模拟重启）时用不同的前缀，免得新任务的标识撞上旧的。 */
    idPrefix: Int = 0x5C,
    /** 调度器实际用的监视器，默认就是 [resources]；要测装饰器（比如录音优先）时传一个包着它的。 */
    monitor: ResourceMonitor = resources,
    runContext: CoroutineContext = EmptyCoroutineContext,
) {
    val clock = FakeClock(scope.testScheduler)
    val decisions = CopyOnWriteArrayList<JobDecision>()
    val scheduler = TranscriptionScheduler(
        library = library,
        adapter = adapter,
        clock = clock,
        scope = schedulerScope,
        store = store,
        models = models,
        resources = monitor,
        ids = SequentialIds(idPrefix),
        config = config,
        runContext = runContext,
    )

    init {
        scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
            scheduler.decisions.toCollection(decisions)
        }
    }

    fun settle() = scope.testScheduler.runCurrent()

    fun advance(ms: Long) {
        scope.testScheduler.advanceTimeBy(ms)
        scope.testScheduler.runCurrent()
    }

    /** 往库里登记第 [n] 个样本 Record（PENDING），返回它的标识；不提交给调度器。 */
    suspend fun addRecord(n: Int): String = Samples.uuid(n).also { library.addRecord(Samples.entry(n)) }

    val jobs: List<TranscriptionJob> get() = scheduler.jobs.value

    /** 这个 Record 唯一的任务。 */
    fun jobOf(recordId: String): TranscriptionJob = jobs.single { it.recordId == recordId }

    /** 某个任务产生过的决定，按发生顺序。 */
    fun decisionsOf(jobId: String): List<JobDecision> = decisions.filter { it.jobId == jobId }

    /**
     * 让 [recordId] 这个 Record 的转录卡住，直到测试用返回的 [CompletableDeferred] 放行并给出结果。
     * 这是“转录引擎正在算”：此刻任务处于 RUNNING，占着唯一的转录名额。
     *
     * [cancellable] 为 false（默认）时，协程被取消也拦不住这次调用，像真引擎那样（原生推理没法中途停，`cancel_support = none`）：
     * 它只会在放行之后才返回。为 true 时取消会立刻中断它（对应声明了 best_effort / immediate 的引擎）。
     */
    fun hold(recordId: String, cancellable: Boolean = false): CompletableDeferred<TranscriptionOutcome> {
        val gate = CompletableDeferred<TranscriptionOutcome>()
        val previousHook = adapter.hook
        val previousOutcome = adapter.outcome
        val results = HashMap<String, TranscriptionOutcome>()
        adapter.hook = { request ->
            if (request.recordId == recordId) {
                results[request.recordId] = if (cancellable) gate.await() else withContext(NonCancellable) { gate.await() }
            } else {
                previousHook?.invoke(request)
            }
        }
        adapter.outcome = { request -> results.remove(request.recordId) ?: previousOutcome(request) }
        return gate
    }
}
