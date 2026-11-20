package app.auri.platform.library

import app.auri.asr.audio.PcmAudio
import app.auri.asr.contract.ReviewState
import app.auri.asr.contract.RevisionContent
import app.auri.asr.contract.RevisionSource
import app.auri.asr.manifest.ModelManifest
import app.auri.capture.adapter.PcmFormat
import app.auri.capture.contract.AsrCapabilities
import app.auri.capture.contract.AsrFormat
import app.auri.capture.contract.AsrLimits
import app.auri.capture.contract.AudioEncoding
import app.auri.capture.contract.CancelSupport
import app.auri.capture.contract.CaptureHandle
import app.auri.capture.contract.ExecutionLocation
import app.auri.capture.contract.GateState
import app.auri.capture.contract.TimestampGranularity
import app.auri.capture.library.RecordEntry
import app.auri.capture.library.TranscriptionStatus
import app.auri.capture.scheduler.InMemoryJobStore
import app.auri.capture.scheduler.ResourcesAlwaysOk
import app.auri.capture.testing.InMemoryRecordLibrary
import app.auri.capture.testing.Samples
import app.auri.modelmgmt.ActiveModel
import app.auri.modelmgmt.ActiveModelStatus
import app.auri.platform.asr.LoadedEngine
import app.auri.platform.asr.LocalSpeechEngine
import app.auri.platform.asr.LocalTranscriptionStack
import app.auri.platform.storage.NoBackupStorage
import app.auri.platform.storage.WavRecordingStore
import app.auri.platform.system.SystemClock
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Ticket 19 评审 H1：升级时录音目录从 filesDir 整个改名挪到 noBackupFilesDir，库里已登记的 Record 却还存着旧的绝对路径，
 * 等转录的、重新转录的 Record 打开的是不存在的文件。
 *
 * 每个测试都从“老版本留下的状态”出发：录音是真文件、在 filesDir/recordings（真的 WavRecordingStore 写的），
 * 库里（真 SQLite 引擎，建表语句就是产品的）存着那里的绝对路径。“新版本启动一次”做的就是 AuriRuntime 做的两步：
 * 真的 [NoBackupStorage.place]（改名挪目录）与 [relocateRecordedAudio]（真的 SQL 改库里的位置）。
 * 转录走真的转录栈（引擎是假的，但音频由真适配器从库里登记的位置读文件）：转得出文字，说明那个位置指着真文件。
 */
class RecordingsMoveUpgradeTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val filesDir get() = File(folder.root, "files")
    private val noBackupDir get() = File(folder.root, "no_backup")
    private val legacyRecordings get() = File(filesDir, "recordings")
    private val currentRecordings get() = File(noBackupDir, "recordings")
    private val handle = CaptureHandle("0b0b0b0b-0000-4000-8000-000000000001", 1, 1, GateState.OPEN)
    private val manifest: ModelManifest = ModelManifest.parse(
        File(System.getProperty("auri.manifests.dir"), "sensevoice-small-int8-2024-07-17.manifest.json").readText(),
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rows = RecordRows()

    /** 老版本留下的 Record（位置是老路径）；库里的位置以 [rows] 为准。 */
    private val written = mutableListOf<RecordEntry>()

    /** 每个录音文件在老位置写好时整个文件的 SHA-256（挪家之后文件内容必须一字不差）。 */
    private val fileDigests = mutableMapOf<String, String>()
    private var library = InMemoryRecordLibrary()
    private var stack: LocalTranscriptionStack? = null

    @After
    fun tearDown() {
        stack?.close()
        scope.cancel()
        rows.close()
    }

    /** 老版本登记的 Record：音频在老目录（真文件），库里存的是老目录下的绝对路径。 */
    private fun legacyRecord(n: Int): RecordEntry = runBlocking {
        val writer = WavRecordingStore(legacyRecordings).begin(Samples.uuid(n), handle, PcmFormat(16_000), Samples.recordingMeta(sessionId = handle.captureSessionId))
        writer.append(handle, 0, ShortArray(1600) { (it % 50).toShort() })
        val audio = writer.seal()
        Samples.entry(n, audio = audio).copy(transcription = TranscriptionStatus.PENDING).also {
            rows.insert(it)
            written += it
            fileDigests[it.recordId] = sha256(File(audio.location))
        }
    }

    /** 新版本启动一次：AuriRuntime 里的两步——先 place 挪目录，再让库里登记的位置跟上。返回库里改了几行。 */
    private fun startNewVersion(): Int = runBlocking {
        val recordingsDir = NoBackupStorage.place(noBackupDir, filesDir, "recordings")
        relocateRecordedAudio(File(filesDir, recordingsDir.name), recordingsDir) { from, to -> rows.relocate(from, to) }
    }

    /** 库此刻的样子：Record 的音频位置取自 [rows]（产品里 SqliteRecordLibrary 就是从那一列读的）；[done] 里的已经有一条 Revision。 */
    private fun openLibrary(vararg done: String) {
        library = InMemoryRecordLibrary().also { lib ->
            runBlocking {
                written.forEach { entry ->
                    lib.addRecord(entry.copy(audio = entry.audio!!.copy(location = rows.locationOf(entry.recordId)!!)))
                    if (entry.recordId in done) lib.publishRevision(Samples.draft(entry.recordId))
                }
            }
        }
    }

    /** 记下被叫到几次、听到多少采样的假引擎：转得出文字，就说明适配器真的打开了库里登记的那份文件。 */
    private inner class FakeEngine : LocalSpeechEngine, AutoCloseable {
        val heard = CopyOnWriteArrayList<Int>()
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

        override fun transcribe(audio: PcmAudio): app.auri.asr.TranscriptionResult {
            heard += audio.samples.size
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

    private fun startStack(engine: FakeEngine): LocalTranscriptionStack = LocalTranscriptionStack(
        library = library,
        active = MutableStateFlow(ActiveModelStatus.Ready(ActiveModel(manifest, File(folder.root, "model"), 1))),
        fallbackCapabilities = AsrCapabilities(
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
        ),
        onIntegrityFailure = {},
        loadEngine = { LoadedEngine(engine, engine) },
        clock = SystemClock,
        scope = scope,
        store = InMemoryJobStore(),
        resources = ResourcesAlwaysOk,
    ).also { stack = it }

    private fun entryOf(recordId: String): RecordEntry = runBlocking { library.get(recordId)!! }

    private fun awaitUntil(what: () -> String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("等不到：${what()}")
            Thread.sleep(10)
        }
    }

    private fun awaitStatus(recordId: String, vararg wanted: TranscriptionStatus) =
        awaitUntil({ "$recordId 的转录状态变成 ${wanted.toList()}（现在是 ${entryOf(recordId).transcription}）" }) { entryOf(recordId).transcription in wanted }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    /** 库里登记的位置指着一个真文件，内容逐字节就是当初封存的那份。 */
    private fun assertStoredLocationIsTheRecording(entry: RecordEntry) {
        val location = rows.locationOf(entry.recordId)!!
        assertTrue("登记的位置必须是真文件：$location", File(location).isFile)
        assertEquals("文件内容必须就是老位置写好时的那份", fileDigests[entry.recordId], sha256(File(location)))
    }

    @Test
    fun `对照：只挪目录、库里的位置不跟着改，等转录的老 Record 打开的是不存在的文件而失败`() {
        val old = legacyRecord(1)
        NoBackupStorage.place(noBackupDir, filesDir, "recordings")
        assertFalse("老位置的文件已经搬走了", File(rows.locationOf(old.recordId)!!).exists())
        openLibrary()
        val engine = FakeEngine()

        startStack(engine).scheduler.submit(old.recordId)
        awaitStatus(old.recordId, TranscriptionStatus.DONE, TranscriptionStatus.FAILED)

        assertEquals("打开的是不存在的文件：转录失败，引擎一次也没被叫到", TranscriptionStatus.FAILED, entryOf(old.recordId).transcription)
        assertTrue(engine.heard.isEmpty())
    }

    @Test
    fun `升级：老版本登记的、等转录的 Record 转得出文字，引擎听到的是整段音频`() {
        val old = legacyRecord(1)

        assertEquals("库里一行位置被改到新目录", 1, startNewVersion())
        openLibrary()
        val engine = FakeEngine()
        startStack(engine).scheduler.submit(old.recordId)
        awaitStatus(old.recordId, TranscriptionStatus.DONE, TranscriptionStatus.FAILED)

        assertEquals(TranscriptionStatus.DONE, entryOf(old.recordId).transcription)
        assertEquals("引擎听到整段 1600 个采样", listOf(1600), engine.heard.toList())
        assertEquals("新位置在新目录里", File(currentRecordings, "${old.recordId}.wav").path, rows.locationOf(old.recordId))
    }

    @Test
    fun `升级：重新转录老版本留下的、已有文字的 Record，也打开得了挪走后的文件`() {
        val old = legacyRecord(1)
        startNewVersion()
        openLibrary(old.recordId)
        val engine = FakeEngine()

        startStack(engine).scheduler.retranscribe(old.recordId)
        awaitUntil({ "重新转录出第二条 Revision（引擎被叫到 ${engine.heard.size} 次）" }) { entryOf(old.recordId).revisions.size == 2 }

        assertEquals("旧 Revision 不动，新增一条", listOf(1, 2), entryOf(old.recordId).revisions.map { it.number })
        assertEquals(listOf(1600), engine.heard.toList())
    }

    @Test
    fun `升级被杀在“目录挪完、库还没改”之后：下次启动补改库，位置指向真文件`() {
        val a = legacyRecord(1)
        val b = legacyRecord(2)
        NoBackupStorage.place(noBackupDir, filesDir, "recordings") // 第一次启动：挪完目录，进程在改库之前被杀
        assertFalse("被杀时库里还是老位置，指向不存在的文件", File(rows.locationOf(a.recordId)!!).exists())

        assertEquals("第二次启动补上：两行都改到新目录", 2, startNewVersion())

        assertStoredLocationIsTheRecording(a)
        assertStoredLocationIsTheRecording(b)
        assertFalse("老目录不会再冒出来", legacyRecordings.exists())
    }

    @Test
    fun `升级被杀在“库已改、目录还没挪”之后（另一种顺序）：下次启动把目录挪过去，位置仍然对得上`() {
        val a = legacyRecord(1)
        val b = legacyRecord(2)
        // 假设有一个版本先改库后挪目录，被杀在两步之间：库里已经是新位置，文件还在老目录
        rows.relocate(audioDirPrefix(legacyRecordings), audioDirPrefix(currentRecordings))
        assertFalse(File(rows.locationOf(a.recordId)!!).exists())

        assertEquals("库里已经是新位置，没有行再需要改", 0, startNewVersion())

        assertStoredLocationIsTheRecording(a)
        assertStoredLocationIsTheRecording(b)
        assertFalse(legacyRecordings.exists())
        openLibrary()
        val engine = FakeEngine()
        startStack(engine).scheduler.submit(a.recordId)
        awaitStatus(a.recordId, TranscriptionStatus.DONE, TranscriptionStatus.FAILED)
        assertEquals(TranscriptionStatus.DONE, entryOf(a.recordId).transcription)
    }

    @Test
    fun `目录挪不动（改名失败）：不改库里的位置，文件还在老位置，照样转录`() {
        val old = legacyRecord(1)
        noBackupDir.writeText("不是目录：mkdirs 与改名都会失败") // 几乎不会发生；这里用一个同名的普通文件让改名确定失败
        val before = rows.locationOf(old.recordId)

        assertEquals("没挪成就一行也不改，否则位置会指向没有文件的地方", 0, startNewVersion())

        assertTrue("目录确实还在老位置", legacyRecordings.isDirectory)
        assertEquals(before, rows.locationOf(old.recordId))
        assertStoredLocationIsTheRecording(old)
        openLibrary()
        val engine = FakeEngine()
        startStack(engine).scheduler.submit(old.recordId)
        awaitStatus(old.recordId, TranscriptionStatus.DONE, TranscriptionStatus.FAILED)
        assertEquals(TranscriptionStatus.DONE, entryOf(old.recordId).transcription)
    }

    @Test
    fun `新老两处都有目录：place 不覆盖也不动老目录，库里的位置同样原样保留`() {
        val old = legacyRecord(1)
        currentRecordings.mkdirs()
        File(currentRecordings, "stray.wav").writeBytes(byteArrayOf(1))
        val before = rows.locationOf(old.recordId)

        assertEquals(0, startNewVersion())

        assertEquals(before, rows.locationOf(old.recordId))
        assertStoredLocationIsTheRecording(old)
        assertTrue("新目录里原有的文件没被动", File(currentRecordings, "stray.wav").isFile)
    }

    @Test
    fun `每次启动都做：第二次启动什么也不再改，全新安装（什么都没有）也不出错`() {
        assertEquals("全新安装：没有行、没有目录", 0, startNewVersion())
        val old = legacyRecord(1)

        assertEquals(1, startNewVersion())
        val after = rows.locationOf(old.recordId)
        assertEquals(0, startNewVersion())

        assertEquals(after, rows.locationOf(old.recordId))
        assertStoredLocationIsTheRecording(old)
    }
}
