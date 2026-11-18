package app.auri.capture.scheduler

import app.auri.capture.adapter.TranscriptionAdapter
import app.auri.capture.coordinator.Clock
import app.auri.capture.coordinator.IdGenerator
import app.auri.capture.library.RecordLibrary
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher

/**
 * 线上的调度器接法：引擎调用跑在专用低优先级线程上（[lowPriorityTranscriptionDispatcher]），其余照 [TranscriptionScheduler]。
 * AuriRuntime 与“转录时不丢帧”的真线程测试走同一个入口，所以那个测试证明的就是线上的接法，而不是另一套手工拼的。
 * 用完 [close]（只关线程池，不打断正在跑的引擎调用）。
 *
 * [dispatcher] 默认自己建一条。适配器自己也要切线程时（`ActiveModelTranscriptionAdapter`）必须传同一条进来：
 * 否则适配器会把引擎调用切回默认优先级的线程，最低优先级就白设了。传进来的这条也由 [close] 关掉。
 */
class TranscriptionRuntime(
    library: RecordLibrary,
    adapter: TranscriptionAdapter,
    clock: Clock,
    scope: CoroutineScope,
    store: JobStore = InMemoryJobStore(),
    models: ModelAvailability = AlwaysReady,
    resources: ResourceMonitor = ResourcesAlwaysOk,
    ids: IdGenerator = IdGenerator { UUID.randomUUID().toString() },
    config: SchedulerConfig = SchedulerConfig(),
    private val dispatcher: ExecutorCoroutineDispatcher = lowPriorityTranscriptionDispatcher(),
) : AutoCloseable {
    val scheduler = TranscriptionScheduler(library, adapter, clock, scope, store, models, resources, ids, config, dispatcher)

    override fun close() = dispatcher.close()
}
