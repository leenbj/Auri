package app.auri.capture.scheduler

import app.auri.capture.adapter.TranscriptionAdapter
import app.auri.capture.coordinator.Clock
import app.auri.capture.coordinator.IdGenerator
import app.auri.capture.library.RecordLibrary
import java.util.UUID
import kotlinx.coroutines.CoroutineScope

/**
 * 线上的调度器接法：引擎调用跑在专用低优先级线程上（[lowPriorityTranscriptionDispatcher]），其余照 [TranscriptionScheduler]。
 * AuriRuntime 与“转录时不丢帧”的真线程测试走同一个入口，所以那个测试证明的就是线上的接法，而不是另一套手工拼的。
 * 用完 [close]（只关线程池，不打断正在跑的引擎调用）。
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
) : AutoCloseable {
    private val dispatcher = lowPriorityTranscriptionDispatcher()

    val scheduler = TranscriptionScheduler(library, adapter, clock, scope, store, models, resources, ids, config, dispatcher)

    override fun close() = dispatcher.close()
}
