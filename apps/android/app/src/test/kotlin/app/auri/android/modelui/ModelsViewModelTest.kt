package app.auri.android.modelui

import app.auri.asr.manifest.Redistribution
import app.auri.capture.contract.ErrorCode
import app.auri.capture.machine.TranscriptionState
import app.auri.capture.scheduler.JobKind
import app.auri.capture.scheduler.ResourceReason
import app.auri.capture.scheduler.TranscriptionJob
import app.auri.capture.scheduler.WaitReason
import app.auri.modelmgmt.ActiveModelStatus
import app.auri.modelmgmt.ModelCatalog
import app.auri.modelmgmt.ModelEntryState
import app.auri.modelmgmt.ModelError
import app.auri.modelmgmt.ModelErrorKind
import app.auri.modelmgmt.ModelInfo
import app.auri.modelmgmt.ModelsState
import app.auri.modelmgmt.SpaceRule
import app.auri.modelmgmt.Transfer
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelsViewModelTest {
    private class FakeModelControls(initial: ModelsState, active: ActiveModelStatus = ActiveModelStatus.NotInstalled) : ModelControls {
        override val models = MutableStateFlow(initial)
        override val active = MutableStateFlow(active)
        val calls = mutableListOf<String>()

        override fun start(modelId: String) { calls += "start:$modelId" }

        override fun pause(modelId: String) { calls += "pause:$modelId" }

        override fun cancel(modelId: String) { calls += "cancel:$modelId" }
    }

    private class FakeTranscriptionControls : TranscriptionControls {
        override val jobs = MutableStateFlow<List<TranscriptionJob>>(emptyList())
        val calls = mutableListOf<String>()

        override fun cancel(jobId: String) { calls += "cancel:$jobId" }

        override fun retranscribe(recordId: String) { calls += "retranscribe:$recordId" }

        override fun recheckResources() { calls += "recheck" }
    }

    private val manifestFile = File("../manifests/sensevoice-small-int8-2024-07-17.manifest.json")
    private val manifestJson get() = manifestFile.readText()
    private val info: ModelInfo get() = ModelCatalog.fromJson(listOf(manifestJson)).default.info

    private fun entry(
        installedVersion: String? = null,
        damaged: Boolean = false,
        transfer: Transfer = Transfer.Idle,
        version: String = info.modelVersion,
    ) = ModelEntryState(info.copy(modelVersion = version), isDefault = true, installedVersion = installedVersion, damaged = damaged, transfer = transfer)

    private fun rowOf(entry: ModelEntryState): ModelRow = rowFor(entry)

    private fun job(
        n: Int,
        record: String = "record-$n",
        state: TranscriptionState,
        kind: JobKind = JobKind.INITIAL,
        wait: WaitReason? = null,
        needsUser: Boolean = false,
        failure: ErrorCode? = null,
        revision: Int? = null,
        attempts: Int = 0,
    ) = TranscriptionJob(
        jobId = "job-$n", recordId = record, kind = kind, seq = n.toLong(), state = state,
        createdAt = Instant.ofEpochSecond(n.toLong()), updatedAt = Instant.ofEpochSecond(n.toLong()),
        attempts = attempts, wait = wait, needsUser = needsUser, failure = failure,
        resultRevision = revision, modelId = revision?.let { "sensevoice-small-int8" }, modelVersion = revision?.let { "2024-07-17+7d1efa2138a6" },
        retryAt = if (state == TranscriptionState.RETRY_SCHEDULED) Instant.ofEpochSecond(1000) else null,
    )

    // ---------------------------------------------------------------- 清单第 1 项：列表显示大小、所需空间、语言和许可证入口

    @Test
    fun `列表一行的大小、所需空间、语言、许可证与固定哈希，逐项等于清单文件里写的（读真实的清单，不是抄来的数）`() {
        val manifest = Json.parseToJsonElement(manifestJson).jsonObject
        val archive = manifest.getValue("archive").jsonObject
        val files = manifest.getValue("files").jsonArray.map { it.jsonObject }
        val license = manifest.getValue("license").jsonObject

        val row = rowOf(entry())

        assertEquals("要下载的大小", archive.getValue("size_bytes").jsonPrimitive.long, row.downloadBytes)
        assertEquals("解包后的大小是各文件之和", files.sumOf { it.getValue("size_bytes").jsonPrimitive.long }, row.unpackedBytes)
        assertEquals(
            "所需空间 = 下载 + 解包 + 余量（SpaceRule），而且一定大于两者之和",
            SpaceRule.needed(row.downloadBytes, row.unpackedBytes),
            row.spaceNeededBytes,
        )
        assertTrue(row.spaceNeededBytes > row.downloadBytes + row.unpackedBytes)
        assertEquals(manifest.getValue("languages").jsonArray.map { it.jsonPrimitive.content }, row.languages)
        assertEquals(license.getValue("name").jsonPrimitive.content, row.license.name)
        assertEquals("能打开的许可地址", license.getValue("source_url").jsonPrimitive.content, row.license.url)
        assertTrue(row.license.url.startsWith("https://"))
        assertEquals("许可的再分发结论如实给出，不替用户说成“已同意”", Redistribution.UNCONFIRMED, row.license.redistribution)
        assertEquals(license.getValue("note").jsonPrimitive.content, row.license.note)
        assertEquals("固定的哈希", archive.getValue("sha256").jsonPrimitive.content, row.archiveSha256)
        assertEquals(manifest.getValue("model_id").jsonPrimitive.content, row.modelId)
        assertEquals(manifest.getValue("model_version").jsonPrimitive.content, row.version)
    }

    // ---------------------------------------------------------------- 下载状态与能做的操作

    @Test
    fun `能开始的操作随状态变：没装是下载，有残片是继续，失败是重试，有新版本是更新，损坏是重新下载，已是最新就没有`() {
        assertEquals(StartAction.DOWNLOAD, rowOf(entry()).start)
        assertEquals(StartAction.RESUME, rowOf(entry(transfer = Transfer.Paused(10, 100))).start)
        assertEquals(StartAction.RETRY, rowOf(entry(transfer = Transfer.Failed(ModelError(ModelErrorKind.NO_NETWORK), 10))).start)
        assertEquals(StartAction.UPDATE, rowOf(entry(installedVersion = "old", version = "new")).start)
        assertEquals(StartAction.REPAIR, rowOf(entry(installedVersion = info.modelVersion, damaged = true)).start)
        assertNull("已经是最新而且完好", rowOf(entry(installedVersion = info.modelVersion)).start)
        assertNull("正在下载", rowOf(entry(transfer = Transfer.Downloading(1, 100))).start)
        assertNull("正在安装", rowOf(entry(transfer = Transfer.Installing)).start)
    }

    @Test
    fun `暂停只在下载中能按，放弃在下载中、暂停、失败时能按，安装中两个都不能按`() {
        fun flags(transfer: Transfer) = rowOf(entry(transfer = transfer)).let { it.canPause to it.canCancel }
        assertEquals(true to true, flags(Transfer.Downloading(1, 100)))
        assertEquals(false to true, flags(Transfer.Paused(1, 100)))
        assertEquals(false to true, flags(Transfer.Failed(ModelError(ModelErrorKind.NO_NETWORK), 1)))
        assertEquals(false to false, flags(Transfer.Installing))
        assertEquals(false to false, flags(Transfer.Idle))
    }

    @Test
    fun `失败带着原因的类别与数字：空间不够能看到差多少，哈希不符、没有网络各是各的`() = runTest {
        val failed = Transfer.Failed(ModelError(ModelErrorKind.NOT_ENOUGH_SPACE, neededBytes = 400, availableBytes = 100), bytesDone = 0)
        val vm = viewModel(this, FakeModelControls(ModelsState(listOf(entry(transfer = failed)))))

        val transfer = vm.state.value.models.single().transfer as Transfer.Failed
        assertEquals(ModelErrorKind.NOT_ENOUGH_SPACE, transfer.error.kind)
        assertEquals(400L, transfer.error.neededBytes)
        assertEquals(100L, transfer.error.availableBytes)
    }

    @Test
    fun `已装的旧模型在更新失败时仍然显示为可用的：失败只是传输的状态，不是模型的状态`() = runTest {
        val failedUpdate = entry(installedVersion = "old", version = "new", transfer = Transfer.Failed(ModelError(ModelErrorKind.HASH_MISMATCH), 0))
        val vm = viewModel(this, FakeModelControls(ModelsState(listOf(failedUpdate)), ActiveModelStatus.NotInstalled))

        val row = vm.state.value.models.single()
        assertEquals("old", row.installedVersion)
        assertFalse(row.damaged)
        assertTrue(row.updateAvailable)
        assertEquals(ModelErrorKind.HASH_MISMATCH, (row.transfer as Transfer.Failed).error.kind)
    }

    @Test
    fun `转录现在用的模型：没装、损坏、可用，各是各的`() = runTest {
        val controls = FakeModelControls(ModelsState(listOf(entry())))
        val vm = viewModel(this, controls)
        assertEquals(ActiveModelFact.NotInstalled, vm.state.value.activeModel)

        controls.active.value = ActiveModelStatus.Damaged("sensevoice-small-int8")
        assertEquals(ActiveModelFact.Damaged("sensevoice-small-int8"), vm.state.value.activeModel)

        controls.active.value = ActiveModelStatus.Ready(
            app.auri.modelmgmt.ActiveModel(app.auri.asr.manifest.ModelManifest.parse(manifestJson), File("."), generation = 3),
        )
        assertEquals(ActiveModelFact.Ready("sensevoice-small-int8", "2024-07-17+7d1efa2138a6"), vm.state.value.activeModel)
    }

    @Test
    fun `模型管理器的状态一变，界面状态就跟着变（唯一的可观察入口）`() = runTest {
        val controls = FakeModelControls(ModelsState(listOf(entry())))
        val vm = viewModel(this, controls)
        assertEquals(Transfer.Idle, vm.state.value.models.single().transfer)

        controls.models.value = ModelsState(listOf(entry(transfer = Transfer.Downloading(50, 100))))

        assertEquals(Transfer.Downloading(50, 100), vm.state.value.models.single().transfer)
    }

    // ---------------------------------------------------------------- 转录状态：等待并显示原因、没有语音、重新转录

    @Test
    fun `没有模型：任务显示在等模型，带原因代码；资源不够：显示在等什么资源，重查用完了标成需要用户处理`() = runTest {
        val jobs = FakeTranscriptionControls()
        val vm = viewModel(this, FakeModelControls(ModelsState(listOf(entry()))), jobs)

        jobs.jobs.value = listOf(
            job(3, state = TranscriptionState.WAITING_RESOURCES, wait = WaitReason.Resources(ResourceReason.LOW_STORAGE), needsUser = true),
            job(2, state = TranscriptionState.WAITING_RESOURCES, wait = WaitReason.Resources(ResourceReason.BATTERY_SAVER)),
            job(1, state = TranscriptionState.WAITING_MODEL, wait = WaitReason.Model(ErrorCode.MODEL_NOT_INSTALLED)),
        )

        val (needsUser, saver, model) = vm.state.value.jobs
        assertEquals(JobStatus.WaitingResources(ResourceReason.LOW_STORAGE, needsUser = true), needsUser.status)
        assertEquals(JobStatus.WaitingResources(ResourceReason.BATTERY_SAVER, needsUser = false), saver.status)
        assertEquals(JobStatus.WaitingModel(ErrorCode.MODEL_NOT_INSTALLED), model.status)
        assertTrue("等资源的任务能让用户点“重新检查”", needsUser.canRecheck && saver.canRecheck)
        assertFalse("等模型的任务不需要重新检查资源", model.canRecheck)
        assertTrue("等待中的任务都能取消", listOf(needsUser, saver, model).all { it.canCancel })
    }

    @Test
    fun `没有识别出语音：如实标成没有语音，没有文字也没有 Revision 编号`() = runTest {
        val jobs = FakeTranscriptionControls()
        val vm = viewModel(this, FakeModelControls(ModelsState(listOf(entry()))), jobs)

        jobs.jobs.value = listOf(job(1, state = TranscriptionState.NO_SPEECH))

        val row = vm.state.value.jobs.single()
        assertEquals(JobStatus.NoSpeech, row.status)
        assertTrue("界面上写明“没有识别出语音”，不是留白也不是编一句话", describeJob(row.status).contains("没有识别出语音"))
        assertTrue(describeJob(row.status).contains("没有生成文字"))
    }

    @Test
    fun `成功的任务写明用的是哪个模型与第几条 Revision；失败给原因；重试排期带次数`() = runTest {
        val jobs = FakeTranscriptionControls()
        val vm = viewModel(this, FakeModelControls(ModelsState(listOf(entry()))), jobs)

        jobs.jobs.value = listOf(
            job(4, state = TranscriptionState.RETRY_SCHEDULED, failure = ErrorCode.ASR_RESOURCE_LIMIT, attempts = 1),
            job(3, state = TranscriptionState.FAILED, failure = ErrorCode.ASR_RESOURCE_LIMIT),
            job(2, state = TranscriptionState.SUCCEEDED, revision = 2, kind = JobKind.RETRANSCRIBE),
            job(1, state = TranscriptionState.CANCELLED),
        )

        val (retry, failed, done, cancelled) = vm.state.value.jobs.map { it.status }
        assertEquals(JobStatus.RetryScheduled(Instant.ofEpochSecond(1000), attempts = 1), retry)
        assertEquals(JobStatus.Failed(ErrorCode.ASR_RESOURCE_LIMIT), failed)
        assertEquals(JobStatus.Succeeded(revision = 2, modelId = "sensevoice-small-int8", modelVersion = "2024-07-17+7d1efa2138a6"), done)
        assertEquals(JobStatus.Cancelled, cancelled)
        assertTrue(describeJob(done).contains("sensevoice-small-int8"))
    }

    @Test
    fun `重新转录按钮：结束了的任务能重转，这个 Record 已经有没结束的任务时不能（新 Revision 由调度器与管线的测试保证）`() = runTest {
        val jobs = FakeTranscriptionControls()
        val vm = viewModel(this, FakeModelControls(ModelsState(listOf(entry()))), jobs)

        jobs.jobs.value = listOf(
            job(3, record = "busy", state = TranscriptionState.RUNNING),
            job(2, record = "busy", state = TranscriptionState.SUCCEEDED, revision = 1),
            job(1, record = "idle", state = TranscriptionState.SUCCEEDED, revision = 1),
        )

        val rows = vm.state.value.jobs.associateBy { it.jobId }
        assertFalse("没结束的任务不能重转", rows.getValue("job-3").canRetranscribe)
        assertFalse("这个 Record 正在转，旧的成功任务也不给重转（调度器也会忽略）", rows.getValue("job-2").canRetranscribe)
        assertTrue(rows.getValue("job-1").canRetranscribe)
        assertTrue("没结束的任务能取消", rows.getValue("job-3").canCancel)
        assertFalse("结束了的任务没有什么可取消", rows.getValue("job-1").canCancel)
    }

    // ---------------------------------------------------------------- 操作原样交给下层

    @Test
    fun `操作原样交给模型管理器与调度器，界面层不自己加规则`() = runTest {
        val models = FakeModelControls(ModelsState(listOf(entry())))
        val jobs = FakeTranscriptionControls()
        val vm = viewModel(this, models, jobs)

        vm.start("m1")
        vm.pause("m1")
        vm.cancelDownload("m1")
        vm.cancelJob("j1")
        vm.retranscribe("r1")
        vm.recheckResources()

        assertEquals(listOf("start:m1", "pause:m1", "cancel:m1"), models.calls)
        assertEquals(listOf("cancel:j1", "retranscribe:r1", "recheck"), jobs.calls)
    }

    private fun viewModel(
        scope: kotlinx.coroutines.test.TestScope,
        models: ModelControls,
        jobs: TranscriptionControls = FakeTranscriptionControls(),
    ) = ModelsViewModel(models, jobs, CoroutineScope(UnconfinedTestDispatcher(scope.testScheduler)))
}
