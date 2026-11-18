package app.auri.modelmgmt

import app.auri.asr.manifest.ManifestArchive
import app.auri.asr.manifest.ModelManifest
import app.auri.asr.manifest.ModelRefusal
import app.auri.asr.manifest.ModelRefusedException
import app.auri.asr.manifest.ModelVerifier
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 本机可用空间。生产里是 `filesDir.usableSpace`；单测换成可控的数。 */
fun interface SpaceProbe {
    fun usableBytes(): Long
}

/** 安装流程里按顺序经过的几步；[InstallHooks] 在每一步通过之后被调用一次，单测用它在“进程被杀的那一刻”给磁盘拍照。 */
enum class InstallStep { ARCHIVE_VERIFIED, EXTRACTED, FILES_VERIFIED, DIRECTORY_RENAMED, POINTER_WRITTEN }

fun interface InstallHooks {
    fun at(step: InstallStep)

    companion object {
        val NONE = InstallHooks { }
    }
}

/** 一个模型的下载/安装进度。每个状态都能让用户知道下一步能做什么。 */
sealed interface Transfer {
    data object Idle : Transfer

    data class Downloading(val bytesDone: Long, val bytesTotal: Long) : Transfer

    /** 暂停了，或进程重启后发现磁盘上有残片：不会自己继续，用户点“继续”才联网。 */
    data class Paused(val bytesDone: Long, val bytesTotal: Long) : Transfer

    /** 压缩包已下完，正在校验、解包、再校验、启用。 */
    data object Installing : Transfer

    data class Failed(val error: ModelError, val bytesDone: Long) : Transfer
}

data class ModelEntryState(
    val info: ModelInfo,
    val isDefault: Boolean,
    /** 磁盘上启用着的版本；null 是没装。可能与 [info] 的版本不同（有更新可用）。 */
    val installedVersion: String?,
    /** 已装的这份没通过完整性检查：不会被加载，需要重新下载。 */
    val damaged: Boolean,
    val transfer: Transfer,
) {
    /** 已经是清单要求的那个版本，而且完好。 */
    val isInstalled: Boolean get() = installedVersion == info.modelVersion && !damaged
    val updateAvailable: Boolean get() = installedVersion != null && installedVersion != info.modelVersion
}

data class ModelsState(val models: List<ModelEntryState>) {
    fun entry(modelId: String): ModelEntryState = models.first { it.info.modelId == modelId }
}

/** 一个可以加载的模型：清单是装它时校验用的那份，[generation] 每次换模型或修好之后加一（调度器据此放行等模型的任务）。 */
data class ActiveModel(val manifest: ModelManifest, val dir: File, val generation: Long)

/** 默认模型当前能不能用于转录。只有 [Ready] 才可以去加载模型。 */
sealed interface ActiveModelStatus {
    data object NotInstalled : ActiveModelStatus

    /** 装过，但没通过完整性检查（缺文件、大小不对，或加载时被校验器拒绝后上报）。 */
    data class Damaged(val modelId: String) : ActiveModelStatus

    data class Ready(val model: ActiveModel) : ActiveModelStatus
}

/**
 * 本地转录模型的下载、安装与启用（Ticket 20）。磁盘布局见 [ModelStore]。规则：
 *  - 开始之前先查可用空间（下载 + 解包 + 余量，已下载的部分扣掉），不够就不联网、不建文件；
 *  - 下载可暂停可续传（[ResumableDownloader]），状态在磁盘上，进程重启后仍在；
 *  - 启用之前两道校验：压缩包的 SHA-256 与大小；解出的每个文件的大小与 SHA-256（[ModelVerifier]，与引擎加载前用的是同一个）；
 *  - 启用 = 校验过的暂存目录一次原子改名成全新的目录，再原子换掉指针文件。半截的、失败的、损坏的文件永远进不了启用目录；
 *  - 任何一步失败或被杀，旧模型和指针原样不动，仍然可用；重启后清掉暂存目录与没被指针引用的目录；
 *  - 只有默认模型（[ModelCatalog.defaultModelId]）决定 [active]；目录里别的模型可以安装，但不参与转录。
 *
 * 线程：所有可变状态在 [lock] 里改；下载与安装在 [scope] 的 IO 协程里跑；状态都经 StateFlow 发布。
 * 日志：本类不打日志；错误只有类别与数字。
 */
class ModelManager(
    private val catalog: ModelCatalog,
    root: File,
    transport: HttpTransport,
    private val scope: CoroutineScope,
    private val space: SpaceProbe = SpaceProbe { root.usableSpace },
    private val hooks: InstallHooks = InstallHooks.NONE,
    openSink: (File) -> OutputStream = { FileOutputStream(it, /* append = */ true) },
    private val verifier: ModelVerifier = ModelVerifier(),
    private val newNonce: () -> String = { UUID.randomUUID().toString().take(8) },
) {
    private val store = ModelStore(root)
    private val downloader = ResumableDownloader(transport, openSink)

    private val lock = Any()
    private val jobs = HashMap<String, Job>()
    private val stops = HashMap<String, Stop>()
    private val installed = HashMap<String, Installed>()
    private var generation = 0L

    private class Installed(val manifest: ModelManifest, val dir: File)

    private enum class Stop { PAUSE, CANCEL }

    private val mutableState = MutableStateFlow(
        ModelsState(catalog.entries.map { ModelEntryState(it.info, it.modelId == catalog.defaultModelId, null, false, Transfer.Idle) }),
    )
    val state: StateFlow<ModelsState> = mutableState.asStateFlow()

    private val mutableActive = MutableStateFlow<ActiveModelStatus>(ActiveModelStatus.NotInstalled)
    val active: StateFlow<ActiveModelStatus> = mutableActive.asStateFlow()

    /**
     * 进程启动时调用一次（阻塞，很快：只读几个小文件、看文件大小，不算哈希，不联网）：
     * 清掉暂存目录与没被引用的目录，读出已装的模型，发现可续传的残片。之前不要调用 [start]。
     */
    fun restore() = synchronized(lock) {
        store.stagingRoot.deleteRecursively()
        installed.clear()
        val entries = catalog.entries.map { entry ->
            val id = entry.modelId
            val found = store.readPointer(id)
            val pointer = found?.first
            val manifest = found?.second
            val dir = pointer?.let { store.activeDir(id, it) }
            val damaged = manifest != null && dir != null && !looksComplete(manifest, dir)
            if (manifest != null && dir != null) installed[id] = Installed(manifest, dir)
            store.collectGarbage(id, keep = pointer?.directory)

            val current = manifest?.modelVersion == entry.manifest.modelVersion && !damaged
            val workDir = store.downloadDir(id, entry.manifest.modelVersion)
            store.cleanDownloads(id, keepVersionDir = if (current) null else workDir)
            val partial = if (current) 0L else downloader.partialBytes(entry.manifest.archive, workDir)
            ModelEntryState(
                info = entry.info,
                isDefault = id == catalog.defaultModelId,
                installedVersion = manifest?.modelVersion,
                damaged = damaged,
                transfer = if (partial > 0) Transfer.Paused(partial, entry.manifest.archive.sizeBytes) else Transfer.Idle,
            )
        }
        mutableState.value = ModelsState(entries)
        publishActive(bumpGeneration = true)
    }

    /** 开始或继续下载并安装 [modelId]。已经在传、或已经是最新且完好，什么也不做。 */
    fun start(modelId: String) {
        val entry = catalog.entry(modelId) ?: return
        synchronized(lock) {
            val previous = jobs[modelId]
            if (previous?.isActive == true) return
            val current = mutableState.value.entry(modelId)
            if (current.isInstalled && current.transfer == Transfer.Idle) return
            // 上一个传输刚被暂停或取消、还在收尾（残片文件还没放开）：它停下的原因留给它自己去读，新的传输等它收完才开始，
            // 绝不与它同时碰同一个残片。收尾已经结束（或根本没跑起来）时，没人会读的旧原因在这里清掉
            if (previous == null || previous.isCompleted) stops.remove(modelId)
            jobs[modelId] = scope.launch(Dispatchers.IO) { runTransfer(entry, after = previous) }
        }
    }

    /** 暂停：连接立刻断开，已下载的字节保留，之后 [start] 从那里继续。 */
    fun pause(modelId: String) {
        synchronized(lock) {
            val job = jobs[modelId]?.takeIf { it.isActive } ?: return
            stops[modelId] = Stop.PAUSE
            job.cancel()
        }
    }

    /** 放弃这次下载：残片删掉，回到空闲。已经装好的模型不受影响。 */
    fun cancel(modelId: String) {
        val entry = catalog.entry(modelId) ?: return
        synchronized(lock) {
            val job = jobs[modelId]?.takeIf { it.isActive }
            if (job != null) {
                stops[modelId] = Stop.CANCEL
                job.cancel()
                return
            }
            val workDir = store.downloadDir(modelId, entry.manifest.modelVersion)
            downloader.discard(workDir)
            workDir.deleteRecursively()
            updateEntry(modelId) { it.copy(transfer = Transfer.Idle) }
        }
    }

    /**
     * 引擎加载前的校验器拒绝了已装的模型（比如文件被改了、大小没变，重启时的快速检查查不出来）：判为损坏，
     * [active] 不再给出它，界面提示重新下载。重新安装成功后会换新的代。
     * [model] 是出事的那个引擎用的那一份：如果它已经被换掉（引擎加载时恰好装了新版本），这条上报对现在的模型没有证据，忽略。
     */
    fun reportIntegrityFailure(model: ActiveModel) {
        synchronized(lock) {
            val id = model.manifest.modelId
            if (model.generation != generation || installed[id] == null) return
            updateEntry(id) { it.copy(damaged = true) }
            publishActive(bumpGeneration = false)
        }
    }

    private suspend fun runTransfer(entry: CatalogEntry, after: Job?) {
        val id = entry.modelId
        val manifest = entry.manifest
        val workDir = store.downloadDir(id, manifest.modelVersion)
        val self = currentCoroutineContext()[Job]
        var outcome: Outcome = Outcome.Unchanged
        try {
            // 在 try 里等：等的时候被暂停或取消，走的是下面同一套善后
            after?.join()
            val have = downloader.partialBytes(manifest.archive, workDir)
            val needed = SpaceRule.needed(manifest.archive.sizeBytes, manifest.files.sumOf { it.sizeBytes }, have)
            val available = space.usableBytes()
            if (available < needed) {
                throw ModelException(ModelError(ModelErrorKind.NOT_ENOUGH_SPACE, neededBytes = needed, availableBytes = available))
            }
            setTransfer(id, Transfer.Downloading(have, manifest.archive.sizeBytes))
            val archiveFile = downloadWithProgress(id, manifest.archive, workDir, have)
            setTransfer(id, Transfer.Installing)
            install(entry, workDir, archiveFile)
            outcome = Outcome.Installed
        } catch (e: CancellationException) {
            outcome = when (synchronized(lock) { stops.remove(id) }) {
                Stop.PAUSE -> Outcome.Moved(Transfer.Paused(downloader.partialBytes(manifest.archive, workDir), manifest.archive.sizeBytes))
                Stop.CANCEL -> {
                    downloader.discard(workDir)
                    workDir.deleteRecursively()
                    Outcome.Moved(Transfer.Idle)
                }
                null -> Outcome.Unchanged // 作用域被关了（应用退出）：磁盘状态已经足够下次续传
            }
            throw e
        } catch (e: ModelException) {
            outcome = Outcome.Moved(Transfer.Failed(e.error, downloader.partialBytes(manifest.archive, workDir)))
        } catch (e: Exception) {
            // 不认识的失败也要让界面有个交代，而不是永远停在“下载中”
            outcome = Outcome.Moved(Transfer.Failed(ModelError(ModelErrorKind.STORAGE_ERROR), downloader.partialBytes(manifest.archive, workDir)))
        } finally {
            finish(id, self, outcome)
        }
    }

    private sealed interface Outcome {
        data object Unchanged : Outcome

        data object Installed : Outcome

        data class Moved(val transfer: Transfer) : Outcome
    }

    /**
     * 传输结束的唯一出口：同一个临界区里摘掉任务并发布最终状态，之后马上又能 [start]。
     * 只摘自己（[self]）：收尾期间 [start] 已经登记了接班的新传输时，不能把它摘掉，否则它会脱离暂停与取消的控制。
     */
    private fun finish(modelId: String, self: Job?, outcome: Outcome) {
        synchronized(lock) {
            if (jobs[modelId] === self) jobs.remove(modelId)
            when (outcome) {
                Outcome.Unchanged -> Unit
                Outcome.Installed -> updateEntry(modelId) { it.copy(transfer = Transfer.Idle) }
                is Outcome.Moved -> updateEntry(modelId) { it.copy(transfer = outcome.transfer) }
            }
        }
    }

    private suspend fun install(entry: CatalogEntry, workDir: File, archiveFile: File) {
        val manifest = entry.manifest
        val id = entry.modelId
        val job = currentCoroutineContext()[Job]
        val checkpoint = { job?.ensureActive() ?: Unit }

        // 第一道：压缩包的大小与 SHA-256 与清单固定的一致，否则不解包
        val sizeOk = archiveFile.length() == manifest.archive.sizeBytes
        val hashOk = sizeOk && try {
            sha256Hex(archiveFile, checkpoint) == manifest.archive.sha256
        } catch (e: IOException) {
            throw ModelException(ModelError(ModelErrorKind.STORAGE_ERROR))
        }
        if (!hashOk) {
            downloader.discard(workDir)
            throw ModelException(ModelError(ModelErrorKind.HASH_MISMATCH))
        }
        hooks.at(InstallStep.ARCHIVE_VERIFIED)

        val staging = store.stagingDir(id, newNonce())
        var renamed = false
        try {
            staging.mkdirs()
            ArchiveInstaller.extract(archiveFile, manifest, staging, checkpoint)
            hooks.at(InstallStep.EXTRACTED)

            // 第二道：解出的每个文件按清单核对大小与 SHA-256（引擎加载前用的是同一个校验器）
            try {
                verifier.verify(manifest, staging)
            } catch (e: ModelRefusedException) {
                if (e.refusal == ModelRefusal.HASH_MISMATCH || e.refusal == ModelRefusal.SIZE_MISMATCH) downloader.discard(workDir)
                throw ModelException(ModelError(errorKindOf(e.refusal)))
            }
            hooks.at(InstallStep.FILES_VERIFIED)

            // 启用：校验过的暂存目录一次原子改名成全新的目录，再原子换掉指针。两步之间被杀，旧指针原样有效
            val directory = "${ModelStore.safeName(manifest.modelVersion)}-${newNonce()}"
            store.storeDir(id).mkdirs()
            val target = File(store.storeDir(id), directory)
            try {
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                throw ModelException(ResumableDownloader.storageError(e))
            }
            renamed = true
            hooks.at(InstallStep.DIRECTORY_RENAMED)
            try {
                store.writePointer(id, ActivePointer(directory, entry.manifestJson))
            } catch (e: IOException) {
                throw ModelException(ResumableDownloader.storageError(e))
            }
            hooks.at(InstallStep.POINTER_WRITTEN)
            activated(entry, target)
        } finally {
            if (!renamed) staging.deleteRecursively()
        }
        // 新模型已经在用：现在才清掉旧版本、没引用的目录与下载区
        store.collectGarbage(id, keep = store.readPointer(id)?.first?.directory)
        downloader.discard(workDir)
        workDir.deleteRecursively()
    }

    private fun errorKindOf(refusal: ModelRefusal) = when (refusal) {
        ModelRefusal.HASH_MISMATCH, ModelRefusal.SIZE_MISMATCH -> ModelErrorKind.HASH_MISMATCH
        ModelRefusal.UNREADABLE -> ModelErrorKind.STORAGE_ERROR
        else -> ModelErrorKind.ARCHIVE_INVALID
    }

    private fun activated(entry: CatalogEntry, dir: File) {
        synchronized(lock) {
            installed[entry.modelId] = Installed(entry.manifest, dir)
            updateEntry(entry.modelId) { it.copy(installedVersion = entry.manifest.modelVersion, damaged = false) }
            if (entry.modelId == catalog.defaultModelId) publishActive(bumpGeneration = true)
        }
    }

    /** 默认模型的状态发布到 [active]。[bumpGeneration]：换了模型或首次读出时是新的一代。 */
    private fun publishActive(bumpGeneration: Boolean) {
        val id = catalog.defaultModelId
        val entry = mutableState.value.entry(id)
        val model = installed[id]
        mutableActive.value = when {
            model == null -> ActiveModelStatus.NotInstalled
            entry.damaged -> ActiveModelStatus.Damaged(id)
            else -> {
                if (bumpGeneration) generation += 1
                ActiveModelStatus.Ready(ActiveModel(model.manifest, model.dir, generation))
            }
        }
    }

    private fun looksComplete(manifest: ModelManifest, dir: File) =
        manifest.files.all { file -> File(dir, file.path).let { it.isFile && it.length() == file.sizeBytes } }

    /**
     * 下载并定时发布进度：回调只记下最新的字节数，由一个定时器每 [PROGRESS_INTERVAL_MS] 毫秒把它发布到界面状态。
     * 不在回调里按时间节流：节流会把“停顿之前的最后一次更新”吞掉，网速掉到零时界面就一直显示一个过时的数。
     */
    private suspend fun downloadWithProgress(id: String, archive: ManifestArchive, workDir: File, start: Long): File = coroutineScope {
        val latest = AtomicLong(start)
        val ticker = launch {
            var shown = start
            while (true) {
                delay(PROGRESS_INTERVAL_MS)
                val now = latest.get()
                if (now != shown) {
                    shown = now
                    setTransfer(id, Transfer.Downloading(now, archive.sizeBytes))
                }
            }
        }
        try {
            downloader.download(archive, workDir) { latest.set(it) }
        } finally {
            ticker.cancel()
        }
    }

    private fun setTransfer(modelId: String, transfer: Transfer) = updateEntry(modelId) { it.copy(transfer = transfer) }

    private fun updateEntry(modelId: String, change: (ModelEntryState) -> ModelEntryState) {
        mutableState.update { state -> ModelsState(state.models.map { if (it.info.modelId == modelId) change(it) else it }) }
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 200L
    }
}
