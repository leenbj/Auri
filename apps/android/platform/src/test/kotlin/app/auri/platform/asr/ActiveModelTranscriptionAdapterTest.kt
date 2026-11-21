package app.auri.platform.asr

import app.auri.asr.audio.PcmAudio
import app.auri.asr.contract.AsrError
import app.auri.asr.contract.ReviewState
import app.auri.asr.contract.RevisionContent
import app.auri.asr.contract.RevisionSource
import app.auri.asr.manifest.ModelManifest
import app.auri.capture.adapter.PcmFormat
import app.auri.capture.adapter.TranscriptionOutcome
import app.auri.capture.adapter.TranscriptionRequest
import app.auri.capture.contract.CaptureHandle
import app.auri.capture.contract.ErrorCode
import app.auri.capture.contract.GateState
import app.auri.capture.testing.Samples
import app.auri.modelmgmt.ActiveModel
import app.auri.modelmgmt.ActiveModelStatus
import app.auri.platform.storage.WavRecordingStore
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Ticket 20：转录适配器跟着“当前启用的模型”走。要证明的是它**什么时候**会去碰引擎：
 *  - 没装、损坏：根本不创建引擎，直接回“等模型”（不会拿一个不可信的目录去加载）；
 *  - 装好了：按模型的“代”创建引擎并复用；换了代就换引擎、关掉旧的；
 *  - 引擎加载前的校验器拒绝了这个模型：上报给模型管理器（它会把模型判为损坏），而且上报带着出事的那一代。
 * 引擎用假的；真引擎在设备上的行为是 Ticket 14 的验收。
 */
class ActiveModelTranscriptionAdapterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val manifest: ModelManifest = ModelManifest.parse(
        File(System.getProperty("auri.manifests.dir"), "sensevoice-small-int8-2024-07-17.manifest.json").readText(),
    )
    private val fallbackCapabilities = app.auri.capture.contract.AsrCapabilities(
        executionLocation = app.auri.capture.contract.ExecutionLocation.ON_DEVICE,
        engine = "fallback-engine",
        modelId = "fallback-model",
        modelVersion = "fallback-version",
        languages = listOf("zh"),
        acceptsRecordedFile = true,
        formats = listOf(app.auri.capture.contract.AsrFormat(app.auri.capture.contract.AudioEncoding.PCM_S16LE, 16_000, 1)),
        streaming = false,
        timestampGranularity = app.auri.capture.contract.TimestampGranularity.NONE,
        limits = app.auri.capture.contract.AsrLimits(null, 1),
        cancelSupport = app.auri.capture.contract.CancelSupport.NONE,
    )

    private fun model(generation: Long) = ActiveModel(manifest, File(folder.root, "model-$generation"), generation)

    private fun asrCapabilities(version: String) = app.auri.asr.contract.AsrCapabilities(
        executionLocation = app.auri.asr.contract.ExecutionLocation.ON_DEVICE,
        engine = "sherpa-onnx",
        modelId = manifest.modelId,
        modelVersion = version,
        languages = listOf("zh"),
        acceptsRecordedFile = true,
        formats = listOf(app.auri.asr.contract.AsrFormat(app.auri.asr.contract.AudioEncoding.PCM_S16LE, 16_000, 1)),
        streaming = false,
        timestampGranularity = app.auri.asr.contract.TimestampGranularity.NONE,
        limits = app.auri.asr.contract.AsrLimits(null, 1),
        cancelSupport = app.auri.asr.contract.CancelSupport.NONE,
    )

    private fun asrError(code: app.auri.asr.contract.ErrorCode) =
        AsrError("1.2", code, "测试", retryable = false, requiredAction = null, operationId = null)

    private fun content(text: String) = RevisionContent(
        text = text,
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

    /** 记录自己被叫了几次、被关了没有的假引擎；它的“模型版本”写着是哪一代创建的。 */
    private inner class FakeEngine(val generation: Long, var reply: () -> app.auri.asr.TranscriptionOutcome) : LocalSpeechEngine, AutoCloseable {
        override val capabilities = asrCapabilities("gen-$generation")
        var calls = 0
        @Volatile var closed = false

        override fun transcribe(audio: PcmAudio): app.auri.asr.TranscriptionResult {
            calls += 1
            val job = app.auri.asr.contract.AsrTaskHandle("3c3c3c3c-0000-4000-8000-000000000001", "local-sherpa-onnx", null, 1)
            return app.auri.asr.TranscriptionResult(job, reply(), emptyList(), app.auri.asr.TranscriptionMetrics(audio.durationMs, 0, 0, 0, 0, null))
        }

        override fun close() {
            closed = true
        }
    }

    private inner class Rig(initial: ActiveModelStatus, val replyFor: (Long) -> (() -> app.auri.asr.TranscriptionOutcome) = { { app.auri.asr.TranscriptionOutcome.Succeeded(content("你好")) } }) {
        val active = MutableStateFlow(initial)
        val loaded = CopyOnWriteArrayList<ActiveModel>()
        val engines = CopyOnWriteArrayList<FakeEngine>()
        val integrityReports = CopyOnWriteArrayList<ActiveModel>()
        var loadFailure: Exception? = null
        var reportFailure: RuntimeException? = null

        val adapter = ActiveModelTranscriptionAdapter(
            active = active,
            fallbackCapabilities = fallbackCapabilities,
            onIntegrityFailure = { model ->
                integrityReports += model
                reportFailure?.let { throw it }
            },
        ) { model ->
            loaded += model
            loadFailure?.let { throw it }
            val engine = FakeEngine(model.generation, replyFor(model.generation))
            engines += engine
            LoadedEngine(engine, engine)
        }

        fun ready(generation: Long): ActiveModel = model(generation).also { active.value = ActiveModelStatus.Ready(it) }
    }

    private val handle = CaptureHandle("0b0b0b0b-0000-4000-8000-000000000001", 1, 1, GateState.OPEN)
    private var recordCounter = 0

    private suspend fun awaitClosed(engine: FakeEngine) = withTimeout(5_000) {
        while (!engine.closed) delay(1)
    }

    @Test
    fun `转录校验与推理期间持有模型目录租约，用完释放`() = runBlocking {
        val leases = AtomicInteger()
        val model = ActiveModel(manifest, File(folder.root, "leased"), 1) {
            leases.incrementAndGet()
            AutoCloseable { leases.decrementAndGet() }
        }
        val rig = Rig(ActiveModelStatus.Ready(model)) {
            {
                assertTrue("推理时目录必须仍被保护", leases.get() > 0)
                app.auri.asr.TranscriptionOutcome.Succeeded(content("你好"))
            }
        }

        rig.adapter.transcribe(request())

        assertEquals(0, leases.get())
        rig.adapter.close()
    }

    /** 每次都封存一个新的 Record 音频（同一个 Record 标识不能封存两次）。 */
    private fun request(): TranscriptionRequest = runBlocking {
        val recordId = "0a0a0a0a-0000-4000-8000-%012d".format(++recordCounter)
        val writer = WavRecordingStore(File(folder.root, "records")).begin(recordId, handle, PcmFormat(16_000), Samples.recordingMeta(sessionId = handle.captureSessionId))
        writer.append(handle, 0, ShortArray(1600) { (it % 50).toShort() })
        TranscriptionRequest(recordId, writer.seal())
    }

    @Test
    fun `没装模型：回等模型（未安装），一个引擎也没创建`() = runBlocking {
        val rig = Rig(ActiveModelStatus.NotInstalled)

        val outcome = rig.adapter.transcribe(request()).outcome

        assertEquals(ErrorCode.MODEL_NOT_INSTALLED, (outcome as TranscriptionOutcome.WaitingModel).code)
        assertTrue("没有可信的模型目录，就不创建引擎", rig.loaded.isEmpty())
        assertTrue(rig.integrityReports.isEmpty())
    }

    @Test
    fun `模型已判为损坏：回等模型（完整性失败），不创建引擎，也不重复上报`() = runBlocking {
        val rig = Rig(ActiveModelStatus.Damaged(manifest.modelId))

        val outcome = rig.adapter.transcribe(request()).outcome

        assertEquals(ErrorCode.MODEL_INTEGRITY_FAILED, (outcome as TranscriptionOutcome.WaitingModel).code)
        assertTrue(rig.loaded.isEmpty())
        assertTrue("已经是损坏状态，再上报没有意义", rig.integrityReports.isEmpty())
    }

    @Test
    fun `装好了：创建一次引擎并复用，结果带出引擎声明的模型版本`() = runBlocking {
        val rig = Rig(ActiveModelStatus.NotInstalled)
        rig.ready(1)

        val first = rig.adapter.transcribe(request())
        val second = rig.adapter.transcribe(request())

        assertEquals("你好", (first.outcome as TranscriptionOutcome.Text).text)
        assertEquals("gen-1", first.capabilities.modelVersion)
        assertEquals("同一代只创建一次引擎", 1, rig.loaded.size)
        assertEquals(2, rig.engines.single().calls)
        assertTrue(second.outcome is TranscriptionOutcome.Text)
    }

    @Test
    fun `换了新的一代：旧引擎关掉，新引擎接手，结果是新模型的`() = runBlocking {
        val rig = Rig(ActiveModelStatus.NotInstalled)
        rig.ready(1)
        rig.adapter.transcribe(request())

        rig.ready(2)
        val result = rig.adapter.transcribe(request())

        assertEquals(listOf(1L, 2L), rig.loaded.map { it.generation })
        awaitClosed(rig.engines[0])
        assertTrue("旧一代的引擎已关闭，不再占内存", rig.engines[0].closed)
        assertEquals("gen-2", result.capabilities.modelVersion)
        assertEquals(1, rig.engines[1].calls)
    }

    @Test
    fun `引擎加载前的校验器拒绝了模型：回等模型（完整性失败），并把出事的那一代上报`() = runBlocking {
        val rig = Rig(ActiveModelStatus.NotInstalled) {
            { app.auri.asr.TranscriptionOutcome.WaitingModel(asrError(app.auri.asr.contract.ErrorCode.MODEL_INTEGRITY_FAILED)) }
        }
        val model = rig.ready(7)

        val outcome = rig.adapter.transcribe(request()).outcome

        assertEquals(ErrorCode.MODEL_INTEGRITY_FAILED, (outcome as TranscriptionOutcome.WaitingModel).code)
        assertSame("上报的是出事的那一份模型，模型管理器靠它的代判断是不是已经被换掉", model, rig.integrityReports.single())
    }

    @Test
    fun `文件不见了（未安装）不是完整性问题：不上报，不把模型判为损坏`() = runBlocking {
        val rig = Rig(ActiveModelStatus.NotInstalled) {
            { app.auri.asr.TranscriptionOutcome.WaitingModel(asrError(app.auri.asr.contract.ErrorCode.MODEL_NOT_INSTALLED)) }
        }
        rig.ready(1)

        val outcome = rig.adapter.transcribe(request()).outcome

        assertEquals(ErrorCode.MODEL_NOT_INSTALLED, (outcome as TranscriptionOutcome.WaitingModel).code)
        assertTrue(rig.integrityReports.isEmpty())
    }

    @Test
    fun `上报时出了异常也不影响结果：结果照样回给调度器`() = runBlocking {
        val rig = Rig(ActiveModelStatus.NotInstalled) {
            { app.auri.asr.TranscriptionOutcome.WaitingModel(asrError(app.auri.asr.contract.ErrorCode.MODEL_INTEGRITY_FAILED)) }
        }
        rig.ready(1)
        rig.reportFailure = IllegalStateException("上报方出错")

        val result = rig.adapter.transcribe(request())

        assertTrue(result.outcome is TranscriptionOutcome.WaitingModel)
        assertEquals(1L, result.modelGeneration)
        assertEquals(1, rig.integrityReports.size)
    }

    @Test
    fun `创建引擎时抛异常：是失败（没有错误码、不可重试），不是崩溃，也没有留下半个引擎`() = runBlocking {
        val rig = Rig(ActiveModelStatus.NotInstalled)
        rig.ready(1)
        rig.loadFailure = IOException("坏了")

        val failed = rig.adapter.transcribe(request()).outcome as TranscriptionOutcome.Failed

        assertEquals(null, failed.code)
        assertEquals(false, failed.retryable)
        rig.loadFailure = null
        assertTrue("下一次可以重新创建", rig.adapter.transcribe(request()).outcome is TranscriptionOutcome.Text)
    }

    @Test
    fun `能力：装好了就是引擎声明的，没装就是后备声明；两种情况都不抛`() = runBlocking {
        val rig = Rig(ActiveModelStatus.NotInstalled)
        assertSame(fallbackCapabilities, rig.adapter.capabilities())

        rig.ready(3)
        assertEquals("gen-3", rig.adapter.capabilities().modelVersion)

        rig.active.value = ActiveModelStatus.Damaged(manifest.modelId)
        assertSame(fallbackCapabilities, rig.adapter.capabilities())
    }

    @Test
    fun `关闭适配器会关掉已创建的引擎，关闭之后不再创建新的`() = runBlocking {
        val rig = Rig(ActiveModelStatus.NotInstalled)
        rig.ready(1)
        rig.adapter.transcribe(request())

        rig.adapter.close()

        awaitClosed(rig.engines.single())
        assertTrue(rig.engines.single().closed)
        val failed = rig.adapter.transcribe(request()).outcome
        assertTrue("已关闭：不再加载任何东西", failed is TranscriptionOutcome.Failed)
        assertEquals(1, rig.loaded.size)
    }

    @Test
    fun `转录期间模型被换了：这次仍用开始时的那一代，出事上报的是旧的那一代，下一次才用新引擎`() = runBlocking {
        lateinit var rig: Rig
        rig = Rig(ActiveModelStatus.NotInstalled) { generation ->
            {
                if (generation == 1L) {
                    rig.ready(2) // 旧引擎正在转的时候，模型管理器装好了新的一代
                    app.auri.asr.TranscriptionOutcome.WaitingModel(asrError(app.auri.asr.contract.ErrorCode.MODEL_INTEGRITY_FAILED))
                } else {
                    app.auri.asr.TranscriptionOutcome.Succeeded(content("新模型的字"))
                }
            }
        }
        val old = rig.ready(1)

        val result = rig.adapter.transcribe(request())

        assertTrue(result.outcome is TranscriptionOutcome.WaitingModel)
        assertEquals(1L, result.modelGeneration)
        assertSame("旧引擎出的事，记在旧的那一代名下；模型管理器会忽略已经被换掉的一代", old, rig.integrityReports.single())
        assertEquals("新模型的字", ((rig.adapter.transcribe(request())).outcome as TranscriptionOutcome.Text).text)
        assertEquals(listOf(1L, 2L), rig.loaded.map { it.generation })
        awaitClosed(rig.engines[0])
        assertTrue(rig.engines[0].closed)
    }

    @Test
    fun `清单声明的能力：只读、不碰磁盘；一次只转一个，不能中途停`() {
        val capabilities = manifest.declaredCapabilities()

        assertEquals(manifest.modelId, capabilities.modelId)
        assertEquals(manifest.modelVersion, capabilities.modelVersion)
        assertEquals(1, capabilities.limits.maxConcurrentJobs)
        assertEquals(app.auri.capture.contract.CancelSupport.NONE, capabilities.cancelSupport)
        assertEquals(app.auri.capture.contract.ExecutionLocation.ON_DEVICE, capabilities.executionLocation)
    }

    @Test
    fun `按模型创建引擎只造转录器，不碰磁盘也不造原生引擎；目录不存在时转录回“未安装”，工厂一次也没被叫到`() = runBlocking {
        var factoryCalls = 0
        val loaded = loadLocalEngine(model(1)) {
            factoryCalls += 1
            error("不该被叫到")
        }
        assertEquals(0, factoryCalls)

        val outcome = LocalTranscriptionAdapter(loaded.engine).transcribe(request()).outcome
        loaded.closer.close()

        assertEquals(ErrorCode.MODEL_NOT_INSTALLED, (outcome as TranscriptionOutcome.WaitingModel).code)
        assertEquals(0, factoryCalls)
    }
}
