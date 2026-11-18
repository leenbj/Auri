package app.auri.platform.asr

import app.auri.capture.contract.AsrCapabilities
import app.auri.capture.coordinator.Clock
import app.auri.capture.library.RecordLibrary
import app.auri.capture.scheduler.JobStore
import app.auri.capture.scheduler.ResourceMonitor
import app.auri.capture.scheduler.SchedulerConfig
import app.auri.capture.scheduler.TranscriptionRuntime
import app.auri.capture.scheduler.TranscriptionScheduler
import app.auri.capture.scheduler.lowPriorityTranscriptionDispatcher
import app.auri.modelmgmt.ActiveModel
import app.auri.modelmgmt.ActiveModelStatus
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow

/**
 * 线上转录这一整套的接法（Ticket 20）：调度器 → 跟着模型管理器走的适配器 → 引擎。
 * 三者共用**同一条**最低优先级的专用线程：适配器内部也要切线程，若它用自己的（默认优先级的）线程，
 * 引擎调用就被切出了低优先级线程，录音路径与转录的 CPU 先后就没有保证了。AuriRuntime 与测试都从这里进，
 * 所以测试证明的是线上的接法，而不是另一套手工拼的。
 *
 * 模型没装、或判为损坏：调度器让任务等模型（带原因），适配器不创建引擎；模型装好、修好之后调度器自动放行。
 * 用完 [close]：取消自己的监听，再在正在跑的引擎结束后关闭线程池。
 */
class LocalTranscriptionStack(
    library: RecordLibrary,
    active: StateFlow<ActiveModelStatus>,
    fallbackCapabilities: AsrCapabilities,
    onIntegrityFailure: (ActiveModel) -> Unit,
    loadEngine: (ActiveModel) -> LoadedEngine,
    clock: Clock,
    scope: CoroutineScope,
    store: JobStore,
    resources: ResourceMonitor,
    config: SchedulerConfig = SchedulerConfig(),
) : AutoCloseable {
    private val dispatcher = lowPriorityTranscriptionDispatcher()
    private val lifetime = SupervisorJob(scope.coroutineContext[Job])
    private val schedulerScope = CoroutineScope(scope.coroutineContext + lifetime)
    private val closed = AtomicBoolean()
    private val adapter = ActiveModelTranscriptionAdapter(active, fallbackCapabilities, onIntegrityFailure, dispatcher, loadEngine)
    private val runtime = TranscriptionRuntime(
        library = library,
        adapter = adapter,
        clock = clock,
        scope = schedulerScope,
        store = store,
        models = ModelManagerAvailability(active),
        resources = resources,
        config = config,
        dispatcher = dispatcher,
    )

    val scheduler: TranscriptionScheduler get() = runtime.scheduler

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        schedulerScope.cancel()
        adapter.close()
        dispatcher.dispatch(EmptyCoroutineContext, Runnable { runtime.close() })
    }
}
