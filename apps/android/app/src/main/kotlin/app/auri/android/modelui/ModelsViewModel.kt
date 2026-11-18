package app.auri.android.modelui

import androidx.lifecycle.ViewModel
import app.auri.asr.manifest.Redistribution
import app.auri.capture.contract.ErrorCode
import app.auri.capture.machine.TranscriptionState
import app.auri.capture.scheduler.JobKind
import app.auri.capture.scheduler.ResourceReason
import app.auri.capture.scheduler.TranscriptionJob
import app.auri.capture.scheduler.TranscriptionScheduler
import app.auri.capture.scheduler.WaitReason
import app.auri.modelmgmt.ActiveModelStatus
import app.auri.modelmgmt.ModelEntryState
import app.auri.modelmgmt.ModelManager
import app.auri.modelmgmt.ModelsState
import app.auri.modelmgmt.Transfer
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/*
 * ===================================================================================================================
 *  “模型与转录”页（ModelsScreen）的数据与操作：另一个人会在这上面做真正的界面，界面只需要读 ModelsUiState、调 ModelsViewModel 的方法。
 * ===================================================================================================================
 *
 * 读：`ModelsViewModel.state: StateFlow<ModelsUiState>`（唯一的可观察入口）。
 *
 *   ModelsUiState
 *   ├─ activeModel: ActiveModelFact    转录现在用的模型：NotInstalled（新录音的文字会等模型，装好自动转）/ Damaged（装了但没通过
 *   │                                  完整性检查，需要重新下载）/ Ready（模型与版本）
 *   ├─ models: List<ModelRow>          模型列表，每行（全部来自清单，没有一项是临时编的）：
 *   │    ├─ modelId / version / isDefault / languages
 *   │    ├─ downloadBytes / unpackedBytes / spaceNeededBytes   要下载多大；解开多大；从零开始装需要的可用空间（下载 + 解包 + 余量）
 *   │    ├─ license: LicenseEntry      许可名称、能打开的 https 地址（url）、能否再分发的结论（redistribution，如实给出，可能是“未确认”）和说明
 *   │    ├─ archiveSha256              固定的哈希：下载的字节必须与它一致才会被解开
 *   │    ├─ installedVersion / damaged / updateAvailable   磁盘上启用着哪个版本；已装的那份没通过完整性检查；清单里有更新的版本
 *   │    ├─ transfer: Transfer         Idle / Downloading / Paused / Installing / Failed(error)。失败只是传输的状态：更新失败时旧模型
 *   │    │                             仍然是 installedVersion、仍然可用
 *   │    └─ start: StartAction? / canPause / canCancel   按钮此刻能不能按（由状态决定，界面不要自己再判断）；start 为 null 表示没有可开始的
 *   └─ jobs: List<JobRow>              转录任务，新的在前。每行：状态（JobStatus：排队 / 转录中 / 等模型（带原因代码）/ 等资源（带原因与
 *                                      是否需要用户处理）/ 等待重试 / 已转出文字（第几条 Revision、哪个模型）/ 没有识别出语音 / 失败（原因）/ 已取消）
 *                                      与 canCancel / canRetranscribe / canRecheck。
 *
 * 写（都不阻塞，可在主线程调）：
 *   - start(modelId)          下载、继续、更新、重新下载、失败后重试：都是它（行里的 start 说明此刻是哪一种）
 *   - pause(modelId)          暂停下载：已下载的字节保留，之后 start 从那里继续
 *   - cancelDownload(modelId) 放弃这次下载（残片删掉）；已装好的模型不受影响
 *   - cancelJob(jobId)        取消一个没结束的转录任务
 *   - retranscribe(recordId)  重新转录：产生一条**新的** Revision（写明这次用的模型），旧的 Revision 不动
 *   - recheckResources()      等资源的任务立刻重新检查（“需要用户处理”的任务也靠它）
 *
 * 规则：转录文字是数据，这里根本不碰文字；没有识别出语音就是 JobStatus.NoSpeech，没有文字也没有 Revision。
 * 联网只发生在用户点了 start 之后（下载），这里没有任何自动联网。
 * ===================================================================================================================
 */

data class ModelsUiState(
    val activeModel: ActiveModelFact,
    val models: List<ModelRow>,
    val jobs: List<JobRow>,
)

sealed interface ActiveModelFact {
    data object NotInstalled : ActiveModelFact

    data class Damaged(val modelId: String) : ActiveModelFact

    data class Ready(val modelId: String, val modelVersion: String) : ActiveModelFact
}

/** 许可是一个条目（名称 + 能打开的地址 + 能否再分发的结论），不是一句“已同意”。 */
data class LicenseEntry(val name: String, val url: String, val redistribution: Redistribution, val note: String)

/** 此刻“开始”按钮是哪一种。 */
enum class StartAction { DOWNLOAD, RESUME, UPDATE, REPAIR, RETRY }

data class ModelRow(
    val modelId: String,
    val version: String,
    val isDefault: Boolean,
    val languages: List<String>,
    val downloadBytes: Long,
    val unpackedBytes: Long,
    val spaceNeededBytes: Long,
    val license: LicenseEntry,
    val archiveSha256: String,
    val installedVersion: String?,
    val damaged: Boolean,
    val updateAvailable: Boolean,
    val transfer: Transfer,
    val start: StartAction?,
    val canPause: Boolean,
    val canCancel: Boolean,
)

data class JobRow(
    val jobId: String,
    val recordId: String,
    val kind: JobKind,
    val status: JobStatus,
    val canCancel: Boolean,
    val canRetranscribe: Boolean,
    val canRecheck: Boolean,
)

sealed interface JobStatus {
    data object Queued : JobStatus

    data object Running : JobStatus

    /** 等模型：[code] 是 MODEL_NOT_INSTALLED（没装）或 MODEL_INTEGRITY_FAILED（装了但校验没过）。 */
    data class WaitingModel(val code: ErrorCode?) : JobStatus

    /** 等资源：[reason] 说明为什么；[needsUser] 为 true 表示重查用完了仍然不行，不会再自己重试，需要用户处理或点“重新检查”。 */
    data class WaitingResources(val reason: ResourceReason?, val needsUser: Boolean) : JobStatus

    data class RetryScheduled(val retryAt: Instant, val attempts: Int) : JobStatus

    data class Succeeded(val revision: Int?, val modelId: String?, val modelVersion: String?) : JobStatus

    /** 没有识别出语音：没有文字，也没有 Revision。 */
    data object NoSpeech : JobStatus

    data class Failed(val code: ErrorCode?) : JobStatus

    data object Cancelled : JobStatus

    /** 合同为云端转录保留的状态；V1 只做本地转录（ADR-0019），调度器永远不会产生它们。 */
    data class Unsupported(val state: TranscriptionState) : JobStatus
}

/** 界面对模型管理的全部操作与可观察状态。生产实现是 [ManagerModelControls]，测试里用假的。 */
interface ModelControls {
    val models: StateFlow<ModelsState>
    val active: StateFlow<ActiveModelStatus>

    fun start(modelId: String)

    fun pause(modelId: String)

    fun cancel(modelId: String)
}

/** 界面对转录调度的全部操作与可观察状态。生产实现是 [SchedulerControls]，测试里用假的。 */
interface TranscriptionControls {
    val jobs: StateFlow<List<TranscriptionJob>>

    fun cancel(jobId: String)

    fun retranscribe(recordId: String)

    fun recheckResources()
}

class ManagerModelControls(private val manager: ModelManager) : ModelControls {
    override val models: StateFlow<ModelsState> get() = manager.state
    override val active: StateFlow<ActiveModelStatus> get() = manager.active

    override fun start(modelId: String) = manager.start(modelId)

    override fun pause(modelId: String) = manager.pause(modelId)

    override fun cancel(modelId: String) = manager.cancel(modelId)
}

class SchedulerControls(private val scheduler: TranscriptionScheduler) : TranscriptionControls {
    override val jobs: StateFlow<List<TranscriptionJob>> get() = scheduler.jobs

    override fun cancel(jobId: String) = scheduler.cancel(jobId)

    override fun retranscribe(recordId: String) = scheduler.retranscribe(recordId)

    override fun recheckResources() = scheduler.recheckResources()
}

class ModelsViewModel(
    private val models: ModelControls,
    private val transcription: TranscriptionControls,
    private val scope: CoroutineScope,
) : ViewModel() {
    val state: StateFlow<ModelsUiState> = combine(models.models, models.active, transcription.jobs, ::buildState)
        .stateIn(scope, SharingStarted.Eagerly, buildState(models.models.value, models.active.value, transcription.jobs.value))

    fun start(modelId: String) = models.start(modelId)

    fun pause(modelId: String) = models.pause(modelId)

    fun cancelDownload(modelId: String) = models.cancel(modelId)

    fun cancelJob(jobId: String) = transcription.cancel(jobId)

    fun retranscribe(recordId: String) = transcription.retranscribe(recordId)

    fun recheckResources() = transcription.recheckResources()

    override fun onCleared() {
        scope.cancel()
    }
}

private fun buildState(models: ModelsState, active: ActiveModelStatus, jobs: List<TranscriptionJob>) = ModelsUiState(
    activeModel = when (active) {
        ActiveModelStatus.NotInstalled -> ActiveModelFact.NotInstalled
        is ActiveModelStatus.Damaged -> ActiveModelFact.Damaged(active.modelId)
        is ActiveModelStatus.Ready -> ActiveModelFact.Ready(active.model.manifest.modelId, active.model.manifest.modelVersion)
    },
    models = models.models.map(::rowFor),
    jobs = jobs.map { rowFor(it, jobs) },
)

internal fun rowFor(entry: ModelEntryState): ModelRow {
    val info = entry.info
    val transfer = entry.transfer
    return ModelRow(
        modelId = info.modelId,
        version = info.modelVersion,
        isDefault = entry.isDefault,
        languages = info.languages,
        downloadBytes = info.archiveSizeBytes,
        unpackedBytes = info.unpackedSizeBytes,
        spaceNeededBytes = info.spaceNeededBytes,
        license = LicenseEntry(info.licenseName, info.licenseUrl, info.redistribution, info.licenseNote),
        archiveSha256 = info.archiveSha256,
        installedVersion = entry.installedVersion,
        damaged = entry.damaged,
        updateAvailable = entry.updateAvailable,
        transfer = transfer,
        start = startActionOf(entry),
        canPause = transfer is Transfer.Downloading,
        canCancel = transfer is Transfer.Downloading || transfer is Transfer.Paused || transfer is Transfer.Failed,
    )
}

/** 与 [ModelManager.start] 一致：正在传、或已经是最新且完好，按了也什么都不做，所以这时没有可开始的操作。 */
private fun startActionOf(entry: ModelEntryState): StartAction? = when (entry.transfer) {
    is Transfer.Downloading, Transfer.Installing -> null
    is Transfer.Paused -> StartAction.RESUME
    is Transfer.Failed -> StartAction.RETRY
    Transfer.Idle -> when {
        entry.installedVersion == null -> StartAction.DOWNLOAD
        entry.damaged -> StartAction.REPAIR
        entry.updateAvailable -> StartAction.UPDATE
        else -> null
    }
}

private fun rowFor(job: TranscriptionJob, all: List<TranscriptionJob>) = JobRow(
    jobId = job.jobId,
    recordId = job.recordId,
    kind = job.kind,
    status = statusOf(job),
    canCancel = !job.isTerminal,
    // 这个 Record 已经有没结束的任务时不给重转（调度器也会忽略）：一次只有一个任务在为同一个 Record 工作
    canRetranscribe = job.isTerminal && all.none { it.recordId == job.recordId && !it.isTerminal },
    canRecheck = job.state == TranscriptionState.WAITING_RESOURCES,
)

private fun statusOf(job: TranscriptionJob): JobStatus = when (job.state) {
    TranscriptionState.QUEUED -> JobStatus.Queued
    TranscriptionState.RUNNING -> JobStatus.Running
    TranscriptionState.WAITING_MODEL -> JobStatus.WaitingModel((job.wait as? WaitReason.Model)?.code)
    TranscriptionState.WAITING_RESOURCES -> JobStatus.WaitingResources((job.wait as? WaitReason.Resources)?.reason, job.needsUser)
    TranscriptionState.RETRY_SCHEDULED -> JobStatus.RetryScheduled(job.retryAt ?: job.updatedAt, job.attempts)
    TranscriptionState.SUCCEEDED -> JobStatus.Succeeded(job.resultRevision, job.modelId, job.modelVersion)
    TranscriptionState.NO_SPEECH -> JobStatus.NoSpeech
    TranscriptionState.FAILED -> JobStatus.Failed(job.failure)
    TranscriptionState.CANCELLED -> JobStatus.Cancelled
    TranscriptionState.WAITING_NETWORK, TranscriptionState.WAITING_CONSENT, TranscriptionState.SUBMITTED_REMOTE -> JobStatus.Unsupported(job.state)
}
