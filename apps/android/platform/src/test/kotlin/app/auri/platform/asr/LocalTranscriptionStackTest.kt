package app.auri.platform.asr

import app.auri.asr.audio.PcmAudio
import app.auri.asr.contract.ReviewState
import app.auri.asr.contract.RevisionContent
import app.auri.asr.contract.RevisionSource
import app.auri.asr.manifest.ModelManifest
import app.auri.capture.adapter.PcmFormat
import app.auri.capture.adapter.SealedAudio
import app.auri.capture.contract.AsrCapabilities
import app.auri.capture.contract.AsrFormat
import app.auri.capture.contract.AsrLimits
import app.auri.capture.contract.AudioEncoding
import app.auri.capture.contract.CancelSupport
import app.auri.capture.contract.CaptureHandle
import app.auri.capture.contract.ErrorCode
import app.auri.capture.contract.ExecutionLocation
import app.auri.capture.contract.GateState
import app.auri.capture.contract.TimestampGranularity
import app.auri.capture.library.TranscriptionStatus
import app.auri.capture.machine.TranscriptionState
import app.auri.capture.scheduler.InMemoryJobStore
import app.auri.capture.scheduler.ResourcesAlwaysOk
import app.auri.capture.scheduler.WaitReason
import app.auri.capture.testing.InMemoryRecordLibrary
import app.auri.capture.testing.Samples
import app.auri.modelmgmt.ActiveModel
import app.auri.modelmgmt.ActiveModelStatus
import app.auri.platform.storage.WavRecordingStore
import app.auri.platform.system.SystemClock
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Ticket 20：线上转录的整条接法——调度器 → 跟着模型管理器走的适配器 → 引擎——放在一起、用真线程跑。
 * 单独测各一层证明不了“接起来也对”：适配器若自己切回默认优先级的线程，最低优先级就白设了；
 * 模型状态若没有传到调度器，等模型的任务就永远等下去。这里钉的是接法本身（引擎用假的）。
 */
class LocalTranscriptionStackTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val manifest: ModelManifest = ModelManifest.parse(
        File(System.getProperty("auri.manifests.dir"), "sensevoice-small-int8-2024-07-17.manifest.json").readText(),
    )
    private val handle = CaptureHandle("0b0b0b0b-0000-4000-8000-000000000001", 1, 1, GateState.OPEN)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val library = InMemoryRecordLibrary()
    private var stack: LocalTranscriptionStack? = null

    @After
    fun tearDown() {
        stack?.close()
        scope.cancel()
    }

    private fun capabilities() = AsrCapabilities(
        executionLocation = ExecutionLocation.ON_DEVICE,
        engine = "sherpa-onnx",
        modelId = manifest.modelId,
        modelVersion = manifest.modelVersion,
        languages = listOf("zh"),
        acceptsRecordedFile = true,
        formats = listOf(AsrFormat(AudioEncoding.PCM_S16LE, 16_000, 1)),
        streaming = false,
        timestampGranularity = TimestampGranularity.NONE,
        limits = AsrLimits(null, 1),
        cancelSupport = CancelSupport.NONE,
    )

    /** 记下自己是在哪条线程上被叫的假引擎。 */
    private inner class ThreadRecordingEngine : LocalSpeechEngine, AutoCloseable {
        override val capabilities = app.auri.asr.contract.AsrCapabilities(
            executionLocation = app.auri.asr.contract.ExecutionLocation.ON_DEVICE,
            engine = "sherpa-onnx",
            modelId = manifest.modelId,
            modelVersion = manifest.modelVersion,
            languages = listOf("zh"),
            acceptsRecordedFile = true,
            formats = listOf(app.auri.asr.contract.AsrFormat(app.auri.asr.contract.AudioEncoding.PCM_S16LE, 16_000, 1)),
            streaming = false,
            timestampGranularity = app.auri.asr.contract.TimestampGranularity.NONE,
            limits = app.auri.asr.contract.AsrLimits(null, 1),
            cancelSupport = app.auri.asr.contract.CancelSupport.NONE,
        )
        val threads = CopyOnWriteArrayList<Thread>()

        override fun transcribe(audio: PcmAudio): app.auri.asr.TranscriptionResult {
            threads += Thread.currentThread()
            val content = RevisionContent(
                text = "你好",
                textSha256 = "a".repeat(64),
                source = RevisionSource.ASR,
                language = "zh",
                reviewState = ReviewState.NOT_REVIEWED,
                executionLocation = app.auri.asr.contract.ExecutionLocation.ON_DEVICE,
                engine = "sherpa-onnx",
                modelId = manifest.modelId,
                modelVersion = manifest.modelVersion,
                confidence = null,
                segments = emptyList(),
            )
            val job = app.auri.asr.contract.AsrTaskHandle("3c3c3c3c-0000-4000-8000-000000000001", "local-sherpa-onnx", null, 1)
            return app.auri.asr.TranscriptionResult(
                job,
                app.auri.asr.TranscriptionOutcome.Succeeded(content),
                emptyList(),
                app.auri.asr.TranscriptionMetrics(audio.durationMs, 0, 0, 0, 0, null),
            )
        }

        override fun close() {}
    }

    private class Rig(val active: MutableStateFlow<ActiveModelStatus>, val engine: ThreadRecordingEngine) {
        val loadedModels = CopyOnWriteArrayList<ActiveModel>()
        val integrityReports = CopyOnWriteArrayList<ActiveModel>()
    }

    private fun rig(initial: ActiveModelStatus, engine: ThreadRecordingEngine): Pair<Rig, LocalTranscriptionStack> {
        val rig = Rig(MutableStateFlow(initial), engine)
        val stack = LocalTranscriptionStack(
            library = library,
            active = rig.active,
            fallbackCapabilities = capabilities(),
            onIntegrityFailure = { rig.integrityReports += it },
            loadEngine = { model ->
                rig.loadedModels += model
                LoadedEngine(engine, engine)
            },
            clock = SystemClock,
            scope = scope,
            store = InMemoryJobStore(),
            resources = ResourcesAlwaysOk,
        )
        this.stack = stack
        return rig to stack
    }

    private fun model(generation: Long) = ActiveModel(manifest, File(folder.root, "model-$generation"), generation)

    /** 经真的 WavRecordingStore 封存一段音频并登记成 Record，和产品里的路径一致。 */
    private fun addRecord(n: Int): String = runBlocking {
        val recordId = Samples.uuid(n)
        val writer = WavRecordingStore(File(folder.root, "records")).begin(recordId, handle, PcmFormat(16_000))
        writer.append(handle, ShortArray(1600) { (it % 50).toShort() })
        val audio: SealedAudio = writer.seal()
        library.addRecord(Samples.entry(n, audio = audio))
        recordId
    }

    private fun statusOf(recordId: String): TranscriptionStatus = runBlocking { library.get(recordId)!!.transcription }

    private fun awaitUntil(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("等不到：$what")
            Thread.sleep(10)
        }
    }

    @Test
    fun `模型切换时运行中的旧引擎不阻塞邮箱取消任务`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val replacementCreated = CountDownLatch(1)
        val oldClosed = CountDownLatch(1)
        val closeThreads = CopyOnWriteArrayList<Thread>()
        val base = ThreadRecordingEngine()
        val blocking = object : LocalSpeechEngine {
            override val capabilities = base.capabilities
            override fun transcribe(audio: PcmAudio): app.auri.asr.TranscriptionResult {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return base.transcribe(audio)
            }
        }
        val active = MutableStateFlow<ActiveModelStatus>(ActiveModelStatus.Ready(model(1)))
        val stack = LocalTranscriptionStack(
            library, active, capabilities(), {}, { selected ->
                if (selected.generation == 1L) {
                    LoadedEngine(blocking, AutoCloseable {
                        release.await(10, TimeUnit.SECONDS)
                        closeThreads += Thread.currentThread()
                        oldClosed.countDown()
                    })
                } else {
                    replacementCreated.countDown()
                    LoadedEngine(base, base)
                }
            }, SystemClock, scope, InMemoryJobStore(), ResourcesAlwaysOk,
        )
        this.stack = stack
        try {
            val id = addRecord(1)
            stack.scheduler.submit(id)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            active.value = ActiveModelStatus.Ready(model(2))
            assertTrue(replacementCreated.await(5, TimeUnit.SECONDS))

            stack.scheduler.cancel(stack.scheduler.jobs.value.single().jobId)
            awaitUntil("模型切换期间取消生效", timeoutMs = 1_000) {
                stack.scheduler.jobs.value.single().state == TranscriptionState.CANCELLED
            }
            assertEquals("旧引擎仍在运行，不能关闭", 1L, oldClosed.count)

            release.countDown()
            assertTrue(oldClosed.await(5, TimeUnit.SECONDS))
            assertEquals("auri-transcribe", closeThreads.single().name)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `引擎调用在最低优先级的专用线程上：整条线上接法，不只是调度器那一层`() {
        val (rig, stack) = rig(ActiveModelStatus.Ready(model(1)), ThreadRecordingEngine())
        val recordId = addRecord(1)

        stack.scheduler.submit(recordId)
        awaitUntil("转完") { statusOf(recordId) == TranscriptionStatus.DONE }

        val thread = rig.engine.threads.single()
        assertEquals("引擎调用在最低优先级的线程上（适配器没有把它切回默认优先级的线程）", Thread.MIN_PRIORITY, thread.priority)
        assertEquals("auri-transcribe", thread.name)
        assertTrue("守护线程，不拖着进程不退出", thread.isDaemon)
        assertEquals("转出的文字发布成第 1 条 Revision，写明模型", manifest.modelId, runBlocking { library.get(recordId)!!.revisions.single().modelId })
    }

    @Test
    fun `没装模型：任务等模型（带原因），一个引擎也没加载；装好之后自动转，不需要再提交`() {
        val (rig, stack) = rig(ActiveModelStatus.NotInstalled, ThreadRecordingEngine())
        val recordId = addRecord(1)

        stack.scheduler.submit(recordId)
        awaitUntil("任务在等模型") { stack.scheduler.jobs.value.singleOrNull()?.state == TranscriptionState.WAITING_MODEL }
        assertEquals(WaitReason.Model(ErrorCode.MODEL_NOT_INSTALLED), stack.scheduler.jobs.value.single().wait)
        assertTrue("没有可信的模型目录就不创建引擎", rig.loadedModels.isEmpty())
        assertEquals("音频与 Record 保留，状态是等模型", TranscriptionStatus.WAITING_MODEL, statusOf(recordId))

        rig.active.value = ActiveModelStatus.Ready(model(1))

        awaitUntil("装好后自动转完") { statusOf(recordId) == TranscriptionStatus.DONE }
        assertEquals(1, rig.engine.threads.size)
        assertEquals(listOf(1L), rig.loadedModels.map { it.generation })
    }

    @Test
    fun `模型被判为损坏：任务等模型（完整性失败），损坏的那份不会被加载；修好之后继续`() {
        val (rig, stack) = rig(ActiveModelStatus.Damaged(manifest.modelId), ThreadRecordingEngine())
        val recordId = addRecord(1)

        stack.scheduler.submit(recordId)
        awaitUntil("任务在等模型") { stack.scheduler.jobs.value.singleOrNull()?.state == TranscriptionState.WAITING_MODEL }
        assertEquals(WaitReason.Model(ErrorCode.MODEL_INTEGRITY_FAILED), stack.scheduler.jobs.value.single().wait)
        assertTrue("损坏的模型永远不会被加载", rig.loadedModels.isEmpty())

        rig.active.value = ActiveModelStatus.Ready(model(2))

        awaitUntil("修好后自动转完") { statusOf(recordId) == TranscriptionStatus.DONE }
        assertEquals(listOf(2L), rig.loadedModels.map { it.generation })
    }
}
