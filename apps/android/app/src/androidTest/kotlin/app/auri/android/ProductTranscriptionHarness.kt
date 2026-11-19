package app.auri.android

import android.app.Application
import app.auri.asr.audio.WavReader
import app.auri.capture.adapter.PcmFormat
import app.auri.capture.adapter.SealedAudio
import app.auri.capture.contract.AudioChunk
import app.auri.capture.contract.CaptureStatus
import app.auri.capture.contract.EndReason
import app.auri.capture.contract.ObservedInput
import app.auri.capture.contract.ObservedInputRouteType
import app.auri.capture.contract.RecordingResult
import app.auri.capture.contract.RouteChangeEvent
import app.auri.capture.contract.TimeRange
import app.auri.capture.contract.VerificationState
import app.auri.capture.coordinator.SealedRecording
import app.auri.capture.library.RecordEntry
import app.auri.capture.library.TranscriptionStatus
import app.auri.modelmgmt.HttpTransport
import app.auri.modelmgmt.TransportResponse
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * 产品里真正走的转录路径（评审 F1(c)）：真实的 [AuriRuntime]——真引擎、产品的模型存储布局、产品的调度器与管线——
 * 只有一处替换：模型下载的传输换成“被调用就记账并失败”的实现（这些测试里不该有任何下载）。
 * `FileTranscription` 是验收用的调试入口，不是产品走的路；零字节只测了它，就没有测到产品的转录路径。
 *
 * 四个入口，一个都不能漏：
 *  1. 协调器封存好录音之后交给管线（[app.auri.capture.pipeline.RecordPipeline.recordingSealed]）——采集到转录的那条路；
 *  2. 调度器提交（[app.auri.capture.scheduler.TranscriptionScheduler.submit]）——Record 已经在库里；
 *  3. 重新转录（[app.auri.capture.scheduler.TranscriptionScheduler.retranscribe]）；
 *  4. 进程重启后的恢复（[app.auri.capture.pipeline.RecordPipeline.resume]）——库里有没转完、却没有任务的 Record。
 * 封存结果是测试构造的（音频就是示例 WAV，路由证据是手写的），不是真实采集，也不证明任何蓝牙路由；这里只测“转录这一段”有没有碰网络。
 * 只能在设备或模拟器上跑：模型与示例音频由 install_on_device.sh 放进应用私有目录；这个环境没有设备，所以只编译过（NOT_RUN）。
 */
class ProductTranscriptionHarness : AutoCloseable {
    /** 模型下载的传输：被调用一次就记一次并失败。 */
    class RefusingTransport : HttpTransport {
        val calls = AtomicInteger()

        override fun get(url: String, headers: Map<String, String>): TransportResponse {
            calls.incrementAndGet()
            throw IOException("这个测试里不该有任何下载")
        }
    }

    /** 四个入口跑完之后的证据。没有任何文字正文。 */
    data class Evidence(
        val entryPoints: List<String>,
        val networkBytes: Long,
        val downloadAttempts: Int,
        val revisionsByEntryPoint: Map<String, Int>,
    )

    private class Fixture(val entry: RecordEntry, val sealed: SealedRecording)

    val transport = RefusingTransport()
    private val app = DeviceEnv.target.applicationContext as Application
    private val root = File(app.cacheDir, "product-offline-${UUID.randomUUID()}")
    private val databaseName = "product-offline-${UUID.randomUUID()}.db"
    private val runtime: AuriRuntime

    init {
        // 产品的模型存储布局（见 ModelStore）：model-store/<id>/active.json 指向目录 <id>/<目录名>；清单随模型一起存。
        val manifest = DeviceEnv.manifest
        val storeDir = File(root, "files/model-store/${manifest.modelId}")
        manifest.files.forEach { file ->
            File(DeviceEnv.modelDir, file.path).copyTo(File(storeDir, "$MODEL_DIRECTORY/${file.path}").also { it.parentFile?.mkdirs() })
        }
        File(storeDir, "active.json").writeText(
            buildJsonObject {
                put("v", 1)
                put("directory", MODEL_DIRECTORY)
                put("manifest_json", DeviceEnv.manifestJson)
            }.toString(),
        )
        runtime = AuriRuntime(
            application = app,
            databaseName = databaseName,
            recordingsDir = File(root, "recordings"),
            modelStoreRoot = File(root, "files"),
            transport = transport,
        )
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    /** 一个封存好的 Record：音频是示例 WAV 的拷贝，标识都是新的。 */
    private fun fixture(): Fixture {
        val recordId = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()
        val wav = File(root, "recordings/$recordId.wav").also { it.parentFile?.mkdirs() }
        DeviceEnv.sample("zh").copyTo(wav)
        val pcm = WavReader.read(wav)
        val sealedAudio = SealedAudio(
            location = wav.path,
            sha256 = sha256(wav),
            sizeBytes = wav.length(),
            sampleCount = pcm.samples.size.toLong(),
            format = PcmFormat(pcm.sampleRateHz, pcm.channels),
        )
        val durationMs = sealedAudio.durationMs
        val evidence = RouteChangeEvent(
            captureSessionId = sessionId,
            epoch = 1,
            observedInput = ObservedInput(ObservedInputRouteType.BLUETOOTH_HFP, "测试构造的封存结果"),
            observedAtMonotonicNs = 1,
            verificationState = VerificationState.VERIFIED,
        )
        val result = RecordingResult(
            recordId = recordId,
            captureSessionId = sessionId,
            status = CaptureStatus.COMPLETE,
            endReason = EndReason.SCREEN_CONTROL,
            captureDurationMs = durationMs,
            speechDurationMs = durationMs,
            chunks = listOf(AudioChunk(UUID.randomUUID().toString(), 0, sealedAudio.sha256, 1, 0, durationMs)),
            verifiedRanges = listOf(TimeRange(0, durationMs)),
            discardedRanges = emptyList(),
        )
        val routeEvidenceId = UUID.randomUUID().toString()
        val startedAt = Instant.now()
        val entry = RecordEntry(
            recordId = recordId,
            captureSessionId = sessionId,
            startedAt = startedAt,
            status = result.status,
            endReason = result.endReason,
            captureDurationMs = durationMs,
            speechDurationMs = durationMs,
            speechSegments = result.verifiedRanges,
            audio = sealedAudio,
            routeEvidenceId = routeEvidenceId,
            inputRouteType = evidence.observedInput.routeType,
            headsetDisplayName = evidence.observedInput.displayName,
            transcription = TranscriptionStatus.PENDING,
        )
        return Fixture(entry, SealedRecording(result, evidence, routeEvidenceId, startedAt, sealedAudio))
    }

    private fun revisionCount(recordId: String): Int = runBlocking { runtime.library.get(recordId) }?.revisions?.size ?: 0

    /** 等到这个 Record 有 [count] 条 Revision。超时的消息里只有任务状态，没有任何正文。 */
    private fun awaitRevisions(recordId: String, count: Int, timeoutMs: Long = 300_000) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (revisionCount(recordId) >= count) return
            Thread.sleep(500)
        }
        val jobs = runtime.scheduler.jobs.value.filter { it.recordId == recordId }
            .joinToString { "${it.kind}:${it.state}:等待=${it.wait}:失败=${it.failure}" }
        fail("等不到第 $count 条 Revision（现在 ${revisionCount(recordId)} 条）；任务：$jobs")
    }

    private fun assertRealEngineRevisions(recordId: String, expected: Int) {
        val revisions = checkNotNull(runBlocking { runtime.library.get(recordId) }).revisions
        assertEquals(expected, revisions.size)
        revisions.forEach {
            assertEquals("Revision 要写明是产品里启用的那份模型转的", DeviceEnv.manifest.modelId, it.modelId)
            assertTrue("转出来的不应是空文本", it.text.isNotBlank())
        }
    }

    /** 依次走四个入口；每个入口都要真的转出 Revision（否则“零字节”可能只是因为什么都没发生）。 */
    fun runEveryEntryPoint(probe: ProcessProbe = SystemProcessProbe()): Evidence {
        val before = checkNotNull(probe.uidNetworkBytes()) { "本机 TrafficStats 必须能读到该 UID 的字节数，读不到就不能证明" }
        val revisions = linkedMapOf<String, Int>()

        // 1. 采集到转录：协调器封存好录音，交给管线
        val sealed = fixture()
        runtime.pipeline.recordingSealed(sealed.sealed)
        awaitRevisions(sealed.entry.recordId, 1)
        assertRealEngineRevisions(sealed.entry.recordId, 1)
        revisions[PIPELINE] = revisionCount(sealed.entry.recordId)

        // 2. 调度器：Record 已经在库里，直接提交
        val direct = fixture()
        runBlocking { runtime.library.addRecord(direct.entry) }
        runtime.scheduler.submit(direct.entry.recordId)
        awaitRevisions(direct.entry.recordId, 1)
        assertRealEngineRevisions(direct.entry.recordId, 1)
        revisions[SCHEDULER] = revisionCount(direct.entry.recordId)

        // 3. 重新转录第一个 Record：新任务、新 Revision，旧的不动
        runtime.scheduler.retranscribe(sealed.entry.recordId)
        awaitRevisions(sealed.entry.recordId, 2)
        assertRealEngineRevisions(sealed.entry.recordId, 2)
        revisions[RETRANSCRIBE] = revisionCount(sealed.entry.recordId)

        // 4. 进程重启后的恢复：库里有没转完、却没有任务的 Record（登记了、没提交）
        val orphan = fixture()
        runBlocking { runtime.library.addRecord(orphan.entry) }
        runtime.pipeline.resume()
        awaitRevisions(orphan.entry.recordId, 1)
        assertRealEngineRevisions(orphan.entry.recordId, 1)
        revisions[RESUME] = revisionCount(orphan.entry.recordId)

        val after = checkNotNull(probe.uidNetworkBytes()) { "本机 TrafficStats 必须能读到该 UID 的字节数，读不到就不能证明" }
        return Evidence(revisions.keys.toList(), after - before, transport.calls.get(), revisions)
    }

    override fun close() {
        runtime.scope.cancel()
        runtime.library.close()
        app.deleteDatabase(databaseName)
        app.deleteDatabase(jobsDatabaseNameFor(databaseName))
        root.deleteRecursively()
    }

    companion object {
        const val PIPELINE = "采集到转录：封存的录音交给管线"
        const val SCHEDULER = "调度器提交"
        const val RETRANSCRIBE = "重新转录"
        const val RESUME = "重启后的恢复"
        private const val MODEL_DIRECTORY = "offline-test"
    }
}
