package app.auri.capture.scheduler

import app.auri.capture.adapter.TranscriptionAdapter
import app.auri.capture.adapter.TranscriptionOutcome
import app.auri.capture.adapter.TranscriptionRequest
import app.auri.capture.adapter.TranscriptionResult
import app.auri.capture.contract.CancelSupport
import app.auri.capture.contract.ErrorCode
import app.auri.capture.contract.RevisionSource
import app.auri.capture.contract.ReviewState
import app.auri.capture.coordinator.Clock
import app.auri.capture.coordinator.IdGenerator
import app.auri.capture.library.RecordEntry
import app.auri.capture.library.RecordLibrary
import app.auri.capture.library.RevisionDraft
import app.auri.capture.library.TranscriptionStatus
import app.auri.capture.machine.Outcome
import app.auri.capture.machine.TranscriptionEvent
import app.auri.capture.machine.TranscriptionEvent.CANCEL
import app.auri.capture.machine.TranscriptionEvent.MODEL_MISSING
import app.auri.capture.machine.TranscriptionEvent.MODEL_READY
import app.auri.capture.machine.TranscriptionEvent.PERMANENT_ERROR
import app.auri.capture.machine.TranscriptionEvent.RESOURCES_LOW
import app.auri.capture.machine.TranscriptionEvent.RESOURCES_OK
import app.auri.capture.machine.TranscriptionEvent.RETRYABLE_ERROR
import app.auri.capture.machine.TranscriptionEvent.RETRY_DUE
import app.auri.capture.machine.TranscriptionEvent.RESULT_NO_SPEECH
import app.auri.capture.machine.TranscriptionEvent.RESULT_TEXT
import app.auri.capture.machine.TranscriptionEvent.RUN_STARTED
import app.auri.capture.machine.TranscriptionMachine
import app.auri.capture.machine.TranscriptionState
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 转录调度器（Ticket 20）：把“录音封存之后要转成文字”做成一组持久化的任务，每个任务严格按
 * `transcription.machine.json` 的转移表走，转不了就等并说明原因，条件好了再继续，绝不无限重试。
 *
 * 结构与 `CaptureCoordinator` 同一个套路：所有输入（提交、取消、模型变化、资源变化、定时器、转录结果）变成消息进一个邮箱，
 * 由**一个**协程按顺序处理，所以任务状态不需要锁，取消与结果之间不会交错。状态机是唯一的仲裁者：
 * 每个动作之前先问转移表，表说拒绝就没有任何副作用（迟到的结果不会发布 Revision）。
 * 转录引擎的调用跑在 [runContext] 里（生产上是低优先级的专用线程），永远不在邮箱协程上阻塞：
 * 录音路径（协调器、采集线程）因此不会被转录拖慢。
 *
 * 规则：
 *  - 默认一次转一个（[SchedulerConfig.maxConcurrent]，再取引擎声明的上限）；录完的第一次转录排在用户要求的重转录之前，同类按先后。
 *  - 没有模型：任务等模型（WAITING_MODEL）并记下原因，模型好了自动继续；不上云（ADR-0019）。等模型不轮询——模型管理器会通知。
 *  - 资源不够（录音中、过热、省电、内存、存储）：任务等（WAITING_RESOURCES）并记下原因；条件变好立刻继续，
 *    条件没变也按有界的间隔重查（防止通知丢了），用完次数就标“需要用户处理”，不再自己重试。
 *  - 可重试的失败按有界的退避重试，次数用完就是 FAILED；永久性失败直接 FAILED。重启后崩溃循环也有上限（开始前先把次数落盘）。
 *  - 取消：运行中的转录不能中途停（原生推理），任务立刻变 CANCELLED、名额一直占到那次调用返回，迟到的结果被状态机拒绝，不写 Revision。
 *  - 重转录是新任务，产生新的 Revision；旧 Revision 不动（不可变）。
 *  - 日志与决定里没有正文；转录出的文字是数据，原样存成 Revision，不解释。
 */
class TranscriptionScheduler(
    private val library: RecordLibrary,
    private val adapter: TranscriptionAdapter,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val store: JobStore = InMemoryJobStore(),
    private val models: ModelAvailability = AlwaysReady,
    private val resources: ResourceMonitor = ResourcesAlwaysOk,
    private val ids: IdGenerator = IdGenerator { UUID.randomUUID().toString() },
    private val config: SchedulerConfig = SchedulerConfig(),
    private val runContext: CoroutineContext = EmptyCoroutineContext,
) {
    private sealed interface Message {
        data object Load : Message

        data class Submit(val recordId: String, val kind: JobKind) : Message

        data class RunFinished(val key: RunKey, val result: RunResult) : Message

        data object ModelChanged : Message

        data object ResourcesChanged : Message

        data object ResourcesUnavailable : Message

        data object RecheckRequested : Message

        data object Resume : Message

        data class Stale(val jobId: String, val event: TranscriptionEvent) : Message

        data class Cancel(val jobId: String) : Message

        /** 定时器到期。[token] 是定时器启动时任务的计数，任务已经走到别处就对不上，作废。 */
        data class TimerDue(val jobId: String, val token: Int) : Message
    }

    private data class RunKey(val jobId: String, val runId: Long)

    private data class Refusal(val generation: Long, val code: ErrorCode)

    /** 读 Record 的三种结果：读到了、确实没有、库暂时读不了（后者不能当成“没有”，否则一次读失败就会取消用户的转录）。 */
    private sealed interface Lookup {
        data class Found(val entry: RecordEntry) : Lookup

        data object Gone : Lookup

        data object Unavailable : Lookup
    }

    private sealed interface RunResult {
        /** 这次调用被取消了（我们自己取消的），没有结果。 */
        data object Cancelled : RunResult

        data class Done(val result: TranscriptionResult, val generation: Long?) : RunResult

        /** 适配器抛出了异常：一个 bug，不是引擎的结果。 */
        data object Crashed : RunResult
    }

    private val mailbox = Channel<Message>(Channel.UNLIMITED)

    // 以下状态只在邮箱协程里读写
    private val jobsById = LinkedHashMap<String, TranscriptionJob>()

    /** 还没返回的引擎调用。名额一直占到它们返回，即使对应的任务已经取消或改为等待。 */
    private val runs = HashMap<RunKey, Job>()

    /** 每个任务当前算数的那次调用；不在这里的调用，结果一律作废。 */
    private val activeRun = HashMap<String, Long>()
    private val timers = HashMap<String, Job>()
    private var nextSeq = 1L
    private var nextRunId = 1L
    private var storeLoaded = false
    private var resourceFlowUnavailable = false

    /** 引擎在运行时拒绝过的模型 generation（见 [onModelRefused]）；不落盘，重启后由 [resume] 重新试。 */
    private var refusal: Refusal? = null

    private val jobFlow = MutableStateFlow<List<TranscriptionJob>>(emptyList())
    private val decisionFlow = MutableSharedFlow<JobDecision>(extraBufferCapacity = 256)
    private val storeFailureCount = AtomicInteger()

    /** 全部任务，新的在前。界面的“转录状态”读这里。 */
    val jobs: StateFlow<List<TranscriptionJob>> = jobFlow.asStateFlow()

    /** 每处理一个输入的决定（含被拒绝、被忽略的），供诊断与测试。 */
    val decisions: SharedFlow<JobDecision> = decisionFlow.asSharedFlow()

    /** 任务没能写进任务库的次数。内存里的状态不受影响，重启时按 Record 库对账。 */
    val storeFailures: Int get() = storeFailureCount.get()

    init {
        mailbox.trySend(Message.Load)
        scope.launch {
            for (message in mailbox) {
                try {
                    handle(message)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 一个输入的处理出了意料之外的错：放弃这个输入，调度器继续处理后面的（不会因此停摆）
                }
            }
        }
        scope.launch { models.readiness.collect { mailbox.trySend(Message.ModelChanged) } }
        scope.launch {
            var failures = 0
            while (true) {
                try {
                    resources.changes.collect {
                        failures = 0
                        mailbox.trySend(Message.ResourcesChanged)
                    }
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mailbox.trySend(Message.ResourcesUnavailable)
                    System.err.println("Resource monitor unavailable")
                    val delayMs = config.resourceRecheckMs.getOrElse(failures) { config.resourceRecheckMs.lastOrNull() ?: 15_000L }
                    failures = (failures + 1).coerceAtMost(config.resourceRecheckMs.size)
                    delay(delayMs.coerceAtMost(300_000L))
                }
            }
        }
    }

    /** 录完的一个 Record 提交转录。同一个 Record 只会有一个第一次转录任务（重复提交什么都不做）。 */
    fun submit(recordId: String) {
        mailbox.trySend(Message.Submit(recordId, JobKind.INITIAL))
    }

    /**
     * 进程启动后、界面回到前台、用户装好模型或点“重试”时调用（可以随便多调，不会打扰正常运行的任务）：
     * 认领上次被杀时正在转的任务、重新为等待中的任务计时、把 Record 库里没转完却没有任务的 Record 补上任务、
     * 忘掉“引擎拒绝过的模型”，然后让能开始的开始。
     */
    fun resume() {
        mailbox.trySend(Message.Resume)
    }

    /**
     * 取消一个任务。还没结束的都取消成 CANCELLED（运行中的也立刻取消，见类注释）；已经结束的什么都不改。
     */
    fun cancel(jobId: String) {
        mailbox.trySend(Message.Cancel(jobId))
    }

    /**
     * 重新转录一个 Record：建一个新任务，结果是一条新的 Revision（写明这次用的模型），旧 Revision 不动。
     * 这个 Record 已经有没结束的任务时什么都不做。
     */
    fun retranscribe(recordId: String) {
        mailbox.trySend(Message.Submit(recordId, JobKind.RETRANSCRIBE))
    }

    /**
     * 测试接缝：对一个任务硬塞一个事件，由状态机裁决。正常运行时没有任何代码调用它——它代表
     * “对已经结束的任务又发来开始/结果”这类 bug 或竞态，合同场景 finished-job-cannot-rerun 要靠它才能重放。
     */
    internal fun injectStaleSignal(jobId: String, event: TranscriptionEvent) {
        mailbox.trySend(Message.Stale(jobId, event))
    }

    /** 用户点“重新检查”：等资源的任务计数清零、立刻重查，条件好了就继续；还是不行就重新有界地重查。 */
    fun recheckResources() {
        mailbox.trySend(Message.RecheckRequested)
    }

    private suspend fun handle(message: Message) {
        when (message) {
            Message.Load -> load()
            is Message.Submit -> onSubmit(message)
            is Message.RunFinished -> onRunFinished(message)
            Message.ModelChanged -> wake(JobCause.MODEL_CHANGED)
            Message.ResourcesChanged -> {
                resourceFlowUnavailable = false
                wake(JobCause.RESOURCES_CHANGED)
            }
            Message.ResourcesUnavailable -> {
                resourceFlowUnavailable = true
                wake(JobCause.RESOURCES_CHANGED)
            }
            Message.RecheckRequested -> onRecheckRequested()
            Message.Resume -> onResume()
            is Message.Stale -> jobsById[message.jobId]?.let { dispatch(it, message.event, JobCause.STALE_SIGNAL) }
            is Message.Cancel -> onCancel(message.jobId)
            is Message.TimerDue -> onTimerDue(message)
        }
    }

    private suspend fun load() {
        val saved = try {
            store.all()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        saved.forEach { jobsById[it.jobId] = it }
        nextSeq = (saved.maxOfOrNull { it.seq } ?: 0L) + 1
        storeLoaded = true
        publish()
    }

    private suspend fun onSubmit(message: Message.Submit) {
        if (!storeLoaded) return
        if (createJob(message.recordId, message.kind) != null) wake(JobCause.PUMP)
    }

    /** 给一个 Record 建一个排队的任务；已经有（第一次转录：任何状态；重转录：没结束的）、或者没有音频可转就不建。 */
    private suspend fun createJob(recordId: String, kind: JobKind): TranscriptionJob? {
        if (jobsById.values.any { it.recordId == recordId && (!it.isTerminal || kind == JobKind.INITIAL) }) return null
        val entry = (lookup(recordId) as? Lookup.Found)?.entry ?: return null
        if (entry.audio == null) return null
        val now = clock.now()
        val job = TranscriptionJob(
            jobId = ids.newUuid(),
            recordId = recordId,
            kind = kind,
            seq = nextSeq++,
            state = TranscriptionMachine.initialState,
            createdAt = now,
            updatedAt = now,
        )
        jobsById[job.jobId] = job
        persist(job)
        publish()
        return job
    }

    private suspend fun onResume() {
        if (!storeLoaded) load()
        if (!storeLoaded) return
        refusal = null
        adoptOrphans()
        for (job in jobsById.values.toList()) if (job.jobId !in timers) rearm(job)
        reconcileWithLibrary()
        wake(JobCause.RESUME)
    }

    /**
     * 任务库里还写着“正在转”、可是这个进程里没有对应的引擎调用：上一个进程在转的时候被杀了。
     * Record 里有本任务发布的 Revision（发布之后、记账之前被杀）就直接认领成功；否则被杀的那次算一次尝试：
     * 次数没用完就按退避重试，用完了就是失败——崩溃循环有上限（开始之前就把次数落盘了）。
     */
    private suspend fun adoptOrphans() {
        for (job in jobsIn(TranscriptionState.RUNNING).filter { it.jobId !in activeRun }) {
            // 库暂时读不了就没法判断结果有没有发布过：这次先不动它，下次 resume 再看（宁可晚认领，也不重复转）
            val entry = when (val found = lookup(job.recordId)) {
                is Lookup.Found -> found.entry
                Lookup.Unavailable -> continue
                Lookup.Gone -> null
            }
            val base = job.baseRevisions
            val published = entry?.revisions?.firstOrNull { it.publicationKey == job.jobId }
            val ambiguousLegacy = entry?.revisions?.drop(base ?: entry.revisions.size)
                ?.any { it.publicationKey == null && it.source == RevisionSource.ASR } == true
            when {
                published != null -> dispatch(job, RESULT_TEXT, JobCause.RUN_INTERRUPTED) {
                    it.copy(resultRevision = published.number, modelId = published.modelId, modelVersion = published.modelVersion, failure = null)
                }

                ambiguousLegacy -> dispatch(job, PERMANENT_ERROR, JobCause.RUN_INTERRUPTED) { it.copy(failure = null) }
                job.attempts >= config.maxAttempts -> dispatch(job, PERMANENT_ERROR, JobCause.RUN_INTERRUPTED) { it.copy(failure = null) }
                else -> dispatch(job, RETRYABLE_ERROR, JobCause.RUN_INTERRUPTED) {
                    it.copy(failure = null, retryAt = clock.now().plusMillis(backoffFor(it.attempts)))
                }
            }
        }
    }

    /** Record 库里还在“转录中”的 Record 必须有任务：没有就补一个；任务已经结束而 Record 还没跟上的，把 Record 改过来。 */
    private suspend fun reconcileWithLibrary() {
        val incomplete = try {
            library.incomplete()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        for (entry in incomplete) {
            val initial = jobsById.values.firstOrNull { it.recordId == entry.recordId && it.kind == JobKind.INITIAL }
            if (initial == null) createJob(entry.recordId, JobKind.INITIAL) else if (initial.isTerminal) syncRecord(initial)
        }
    }

    private fun backoffFor(attempts: Int): Long = config.retryBackoffMs[(attempts - 1).coerceIn(0, config.retryBackoffMs.lastIndex)]

    // ---------------------------------------------------------------- 唤醒：先过模型这一关，再过资源这一关，最后才开始

    private suspend fun wake(cause: JobCause) {
        if (!storeLoaded) return
        reconcileModel(cause)
        if (jobsById.values.any { it.state in RESOURCE_RELEVANT }) {
            val status = resourceStatus()
            reconcileResources(status, cause)
            reconcileModel(cause) // 刚从等资源回到排队的任务，可能要接着等模型
            if (status is ResourceStatus.Ok) startQueued()
        } else {
            startQueued()
        }
    }

    private suspend fun reconcileModel(cause: JobCause) {
        when (val readiness = readiness()) {
            is ModelReadiness.Missing -> {
                val wait = WaitReason.Model(readiness.code)
                for (job in jobsIn(TranscriptionState.QUEUED)) dispatch(job, MODEL_MISSING, cause) { it.copy(wait = wait) }
                for (job in jobsIn(TranscriptionState.WAITING_MODEL).filter { it.wait != wait }) update(job.copy(wait = wait))
            }

            is ModelReadiness.Ready ->
                for (job in jobsIn(TranscriptionState.WAITING_MODEL)) dispatch(job, MODEL_READY, cause) { it.copy(wait = null) }
        }
    }

    private suspend fun reconcileResources(status: ResourceStatus, cause: JobCause) {
        when (status) {
            is ResourceStatus.Low -> {
                val wait = WaitReason.Resources(status.reason)
                for (job in jobsIn(TranscriptionState.QUEUED)) {
                    dispatch(job, RESOURCES_LOW, cause) { it.copy(wait = wait, waitChecks = 0, needsUser = false) }
                }
                for (job in jobsIn(TranscriptionState.WAITING_RESOURCES).filter { it.wait != wait }) update(job.copy(wait = wait))
                interruptRunning(wait, cause)
            }

            ResourceStatus.Ok ->
                for (job in jobsIn(TranscriptionState.WAITING_RESOURCES)) {
                    dispatch(job, RESOURCES_OK, cause) { it.copy(wait = null, waitChecks = 0, needsUser = false) }
                }
        }
    }

    /**
     * 运行中资源变差：只有能中途停的引擎才打断；已经落盘的尝试次数不能回退。真引擎（cancel_support = none）停不下来，
     * 那一次照常转完，之后排队的任务才会等。
     */
    private suspend fun interruptRunning(wait: WaitReason.Resources, cause: JobCause) {
        val running = jobsIn(TranscriptionState.RUNNING)
        if (running.isEmpty() || !engineCanStop()) return
        for (job in running) {
            val runId = activeRun.remove(job.jobId)
            runs.entries.firstOrNull { it.key.jobId == job.jobId && it.key.runId == runId }?.value?.cancel()
            dispatch(job, RESOURCES_LOW, cause) {
                it.copy(wait = wait, waitChecks = 0, needsUser = false)
            }
        }
    }

    private suspend fun engineCanStop(): Boolean = try {
        adapter.capabilities().cancelSupport != CancelSupport.NONE
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** 资源状态未知时保守等待，稍后重查。 */
    private fun resourceStatus(): ResourceStatus = try {
        if (resourceFlowUnavailable) ResourceStatus.Low(ResourceReason.UNKNOWN) else resources.check()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ResourceStatus.Low(ResourceReason.UNKNOWN)
    }

    private suspend fun onRecheckRequested() {
        for (job in jobsIn(TranscriptionState.WAITING_RESOURCES)) {
            val reset = job.copy(waitChecks = 0, needsUser = false)
            update(reset)
            rearm(reset)
        }
        wake(JobCause.RESOURCES_RECHECK)
    }

    private suspend fun onCancel(jobId: String) {
        val job = jobsById[jobId] ?: return
        val runId = activeRun.remove(jobId) // 不论接下来的调用还要算多久，它的结果从此不再算数
        val cancelled = dispatch(job, CANCEL, JobCause.USER_CANCEL)
        if (cancelled != null && runId != null) runs[RunKey(jobId, runId)]?.cancel()
        wake(JobCause.PUMP)
    }

    private suspend fun onTimerDue(message: Message.TimerDue) {
        timers.remove(message.jobId)
        val job = jobsById[message.jobId] ?: return
        when (job.state) {
            TranscriptionState.WAITING_RESOURCES -> {
                if (job.needsUser || job.waitChecks != message.token) return
                val checks = job.waitChecks + 1
                val counted = job.copy(waitChecks = checks, needsUser = checks >= config.resourceRecheckMs.size)
                update(counted)
                rearm(counted)
                wake(JobCause.RESOURCES_RECHECK) // 条件好了就转移并继续；没好，计数已经记下
            }

            TranscriptionState.RETRY_SCHEDULED -> {
                if (job.attempts != message.token) return
                dispatch(job, RETRY_DUE, JobCause.RETRY_TIMER) { it.copy(retryAt = null) }
                wake(JobCause.RETRY_TIMER)
            }

            else -> Unit
        }
    }

    // ---------------------------------------------------------------- 开始与结果

    /** 能开始的就开始：先按 FIFO 取最前面的排队任务。 */
    private suspend fun startQueued() {
        while (runs.size < capacity()) {
            val next = jobsIn(TranscriptionState.QUEUED)
                .filter { job -> runs.keys.none { it.jobId == job.jobId } } // 上一次调用还没返回的任务不重复开始
                .minWithOrNull(QUEUE_ORDER) ?: return
            if (!startRun(next)) return
        }
    }

    private suspend fun capacity(): Int {
        val declared = try {
            adapter.capabilities().limits.maxConcurrentJobs
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            1
        }
        return minOf(config.maxConcurrent, declared)
    }

    private suspend fun startRun(job: TranscriptionJob): Boolean {
        val entry = when (val found = lookup(job.recordId)) {
            is Lookup.Found -> found.entry
            // 库暂时读不了：任务留在排队里，不取消；下一次唤醒（界面回到前台会 resume）再试
            Lookup.Unavailable -> return false
            Lookup.Gone -> null
        }
        val audio = entry?.audio
        if (audio == null) {
            // Record 被删了或者没有音频：这个任务没有东西可转，取消它，别让它堵住后面的任务
            dispatch(job, CANCEL, JobCause.RECORD_GONE)
            return true
        }
        val started = dispatch(job, RUN_STARTED, JobCause.PUMP, requireDurable = true) {
            it.copy(attempts = it.attempts + 1, baseRevisions = entry.revisions.size, wait = null, retryAt = null)
        } ?: return false
        val key = RunKey(started.jobId, nextRunId++)
        val generation = (models.readiness.value as? ModelReadiness.Ready)?.generation
        activeRun[started.jobId] = key.runId
        var result: RunResult = RunResult.Cancelled
        val run = scope.launch(runContext) {
            try {
                val returned = adapter.transcribe(TranscriptionRequest(started.recordId, audio))
                result = RunResult.Done(returned, returned.modelGeneration ?: generation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                result = RunResult.Crashed
            }
        }
        runs[key] = run
        // 即使取消发生在协程体开始之前，完成处理器也只发送一次并释放名额。
        run.invokeOnCompletion { mailbox.trySend(Message.RunFinished(key, result)) }
        return true
    }

    private suspend fun onRunFinished(message: Message.RunFinished) {
        runs.remove(message.key)
        val job = jobsById[message.key.jobId]
        if (job != null) {
            if (activeRun[job.jobId] == message.key.runId) {
                activeRun.remove(job.jobId)
                when (val result = message.result) {
                    RunResult.Cancelled -> Unit
                    RunResult.Crashed -> dispatch(job, PERMANENT_ERROR, JobCause.RUN_RESULT) { it.copy(failure = null) }
                    is RunResult.Done -> applyResult(job, result.result, result.generation)
                }
            } else if (job.isTerminal && message.result is RunResult.Done) {
                // 任务已经结束（比如被取消）之后才返回的结果：交给状态机裁决，它会拒绝，决定里留一条记录，没有任何副作用
                dispatch(job, eventOf(message.result.result.outcome), JobCause.RUN_RESULT)
            }
        }
        wake(JobCause.PUMP)
    }

    private fun eventOf(outcome: TranscriptionOutcome): TranscriptionEvent = when (outcome) {
        is TranscriptionOutcome.Text -> if (outcome.text.isBlank()) RESULT_NO_SPEECH else RESULT_TEXT
        TranscriptionOutcome.NoSpeech -> RESULT_NO_SPEECH
        is TranscriptionOutcome.WaitingModel -> RETRYABLE_ERROR
        is TranscriptionOutcome.Failed -> if (outcome.retryable) RETRYABLE_ERROR else PERMANENT_ERROR
    }

    private suspend fun applyResult(job: TranscriptionJob, result: TranscriptionResult, generation: Long?) {
        when (val outcome = result.outcome) {
            is TranscriptionOutcome.Text ->
                if (outcome.text.isBlank()) dispatch(job, RESULT_NO_SPEECH, JobCause.RUN_RESULT) else finishWithText(job, result, outcome)

            TranscriptionOutcome.NoSpeech -> dispatch(job, RESULT_NO_SPEECH, JobCause.RUN_RESULT)
            is TranscriptionOutcome.WaitingModel -> onModelRefused(job, outcome.code, generation)
            is TranscriptionOutcome.Failed ->
                // 可重试的才重试，次数用完同样是永久失败：失败有上限，不会无限重试
                if (outcome.retryable && job.attempts < config.maxAttempts) {
                    dispatch(job, RETRYABLE_ERROR, JobCause.RUN_RESULT) { it.copy(failure = outcome.code, retryAt = clock.now().plusMillis(backoffFor(job.attempts))) }
                } else {
                    dispatch(job, PERMANENT_ERROR, JobCause.RUN_RESULT) { it.copy(failure = outcome.code) }
                }
        }
    }

    /**
     * 引擎说模型不可用（运行时才发现）。转移表里 RUNNING 没有直接去等模型的边，所以走 重试→立刻到期→排队→模型缺失，
     * 每一步都由状态机批准。调度器记下“这个模型 generation 被引擎拒绝了”：同一个 generation 下一律当作缺失，
     * 不会反复撞同一个坏模型；模型管理器换了新模型（generation 变了）或调用 [resume] 才再试。
     */
    private suspend fun onModelRefused(job: TranscriptionJob, code: ErrorCode, generation: Long?) {
        val current = models.readiness.value as? ModelReadiness.Ready
        if (generation != null && current?.generation == generation) refusal = Refusal(generation, code)
        val scheduled = dispatch(job, RETRYABLE_ERROR, JobCause.MODEL_REFUSED) {
            it.copy(failure = null, retryAt = clock.now())
        } ?: return
        dispatch(scheduled, RETRY_DUE, JobCause.MODEL_REFUSED) { it.copy(retryAt = null) }
    }

    private fun readiness(): ModelReadiness {
        val actual = models.readiness.value
        val refused = refusal ?: return actual
        if (actual is ModelReadiness.Ready && actual.generation == refused.generation) return ModelReadiness.Missing(refused.code)
        refusal = null // 模型已经换了
        return actual
    }

    private suspend fun finishWithText(job: TranscriptionJob, result: TranscriptionResult, outcome: TranscriptionOutcome.Text) {
        // 先问状态机再发布：任务已经不在 RUNNING（被取消等）时，迟到的文字不能变成 Revision
        if (TranscriptionMachine.resolve(job.state, RESULT_TEXT)?.outcome != Outcome.TRANSITION) {
            dispatch(job, RESULT_TEXT, JobCause.RUN_RESULT)
            return
        }
        val draft = RevisionDraft(
            recordId = job.recordId,
            text = outcome.text,
            source = RevisionSource.ASR,
            language = outcome.language,
            reviewState = ReviewState.NOT_REVIEWED,
            executionLocation = result.capabilities.executionLocation,
            engine = result.capabilities.engine,
            modelId = result.capabilities.modelId,
            modelVersion = result.capabilities.modelVersion,
            confidence = outcome.confidence,
            createdAt = clock.now(),
            publicationKey = job.jobId,
        )
        val revision = try {
            library.publishRevision(draft)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dispatch(job, PERMANENT_ERROR, JobCause.RUN_RESULT) { it.copy(failure = null) }
            return
        }
        dispatch(job, RESULT_TEXT, JobCause.RUN_RESULT) {
            it.copy(resultRevision = revision.number, modelId = result.capabilities.modelId, modelVersion = result.capabilities.modelVersion, failure = null)
        }
    }

    // ---------------------------------------------------------------- 状态的唯一出入口

    /**
     * 唯一改变任务状态的地方：先问转移表。转移 → 套用 [change] 并落盘、发布；忽略与拒绝 → 只记一条决定，什么都不改。
     * 返回转移之后的任务；没有发生转移返回 null。
     */
    private suspend fun dispatch(
        job: TranscriptionJob,
        event: TranscriptionEvent,
        cause: JobCause,
        requireDurable: Boolean = false,
        change: (TranscriptionJob) -> TranscriptionJob = { it },
    ): TranscriptionJob? {
        val current = jobsById[job.jobId] ?: return null
        val rule = TranscriptionMachine.resolve(current.state, event)
        val outcome = rule?.outcome ?: Outcome.REJECTED
        if (rule == null || rule.outcome != Outcome.TRANSITION) {
            decisionFlow.tryEmit(JobDecision(current.jobId, event, outcome, current.state, rule?.to ?: current.state, cause))
            return null
        }
        val next = change(current).copy(state = rule.to, updatedAt = clock.now())
        if (requireDurable && !persist(next)) return null
        decisionFlow.tryEmit(JobDecision(current.jobId, event, outcome, current.state, rule.to, cause))
        timers.remove(next.jobId)?.cancel()
        update(next, writeStore = !requireDurable)
        rearm(next)
        return next
    }

    /** 不是状态转移的改动（计数、等待原因）：落盘、发布、同步 Record。 */
    private suspend fun update(job: TranscriptionJob, writeStore: Boolean = true) {
        val stamped = job.copy(updatedAt = clock.now())
        jobsById[stamped.jobId] = stamped
        if (writeStore) persist(stamped)
        publish()
        syncRecord(stamped)
    }

    /** 等资源的任务按有界的间隔定时重查；等重试的任务在退避到期时再试。 */
    private fun rearm(job: TranscriptionJob) {
        timers.remove(job.jobId)?.cancel()
        val (delayMs, token) = when {
            job.state == TranscriptionState.WAITING_RESOURCES && !job.needsUser -> config.resourceRecheckMs[job.waitChecks] to job.waitChecks
            job.state == TranscriptionState.RETRY_SCHEDULED -> maxOf(0L, (job.retryAt ?: clock.now()).toEpochMilli() - clock.now().toEpochMilli()) to job.attempts
            else -> return
        }
        timers[job.jobId] = scope.launch {
            delay(delayMs)
            mailbox.trySend(Message.TimerDue(job.jobId, token))
        }
    }

    private suspend fun persist(job: TranscriptionJob): Boolean =
        try {
            store.put(job)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            storeFailureCount.incrementAndGet()
            false
        }

    private fun publish() {
        jobFlow.value = jobsById.values.sortedByDescending { it.seq }
    }

    private fun jobsIn(state: TranscriptionState): List<TranscriptionJob> =
        jobsById.values.filter { it.state == state }.sortedBy { it.seq }

    /** Record 上那个粗粒度的转录状态是任务状态的投影：写不进去不影响任务（重启对账时会补上）。 */
    private suspend fun syncRecord(job: TranscriptionJob) {
        if (job.kind == JobKind.RETRANSCRIBE) return // 重转录不改 Record 的状态：它已经有文字了，失败或取消都不能让它退回去
        val (status, code) = when (job.state) {
            TranscriptionState.QUEUED, TranscriptionState.RUNNING, TranscriptionState.RETRY_SCHEDULED, TranscriptionState.WAITING_RESOURCES ->
                TranscriptionStatus.PENDING to null

            TranscriptionState.WAITING_MODEL -> TranscriptionStatus.WAITING_MODEL to (job.wait as? WaitReason.Model)?.code
            TranscriptionState.NO_SPEECH -> TranscriptionStatus.NO_SPEECH to null
            TranscriptionState.FAILED -> TranscriptionStatus.FAILED to job.failure
            TranscriptionState.CANCELLED -> TranscriptionStatus.FAILED to null
            else -> return // SUCCEEDED：发布 Revision 的事务已经把 Record 标成 DONE
        }
        try {
            library.markTranscription(job.recordId, status, code)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 已经有 Revision 的 Record（重转录）不允许改状态，或者库暂时不可用：都不影响任务本身
        }
    }

    private suspend fun lookup(recordId: String): Lookup = try {
        library.get(recordId)?.let { Lookup.Found(it) } ?: Lookup.Gone
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Lookup.Unavailable
    }

    private companion object {
        /** 录完的第一次转录先于用户要求的重转录，同类按创建先后。 */
        val QUEUE_ORDER: Comparator<TranscriptionJob> = compareBy<TranscriptionJob>({ it.kind }, { it.seq })

        /** 这些状态的任务会受资源状况影响（排队的开始前要看、等资源的要看能否继续、运行中的可能被打断）。 */
        val RESOURCE_RELEVANT = setOf(TranscriptionState.QUEUED, TranscriptionState.WAITING_RESOURCES, TranscriptionState.RUNNING)
    }
}
