package app.auri.android

import app.auri.asr.TranscriptionState
import app.auri.asr.audio.WavFormatException
import app.auri.asr.audio.WavReader
import app.auri.asr.engine.AsrEngine
import app.auri.asr.engine.AsrEngineFactory
import app.auri.asr.engine.EngineOutput
import app.auri.asr.manifest.ModelRefusal
import app.auri.asr.manifest.ModelRefusedException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * “转录这个文件”的整条流程：清单 → WAV → 转录 → 合同文件与运行报告。
 * 引擎、进程探针、时钟都是假的，所以这里证明的是报告里每个数字取自对的时刻、对的来源，而且报告不含口述正文。
 */
class FileTranscriptionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val transcript = "你好，世界。这是口述的正文。"

    private class Clock {
        var ns = 0L
        fun advanceMs(ms: Long) { ns += ms * 1_000_000 }
    }

    private class FakeProbe(private val clock: Clock) : ProcessProbe {
        var peakReads = ArrayDeque(listOf(1000L, 5000L))
        var rssKb: Long? = 4000L
        var processStartedAtMs = 2000L
        var netBytes = 500L
        override fun peakRssKb(): Long? = peakReads.removeFirstOrNull()
        override fun currentRssKb(): Long? = rssKb
        override fun msSinceProcessStart(): Long = processStartedAtMs + clock.ns / 1_000_000
        override fun uidNetworkBytes(): Long? = netBytes
        override fun device() = ReportDevice("Pixel 6", "ranchu", "google/test/fingerprint", 35, listOf("arm64-v8a"))

        /** 非 null 时 [engine] 抛它：模拟原生库根本没能加载。 */
        var engineInfoFailure: Throwable? = null
        override fun engine() = engineInfoFailure?.let { throw it } ?: ReportEngine("2.0.1", "1.28.2", "af2d8d8", 2)
    }

    private class FakeEngine(private val clock: Clock, private val probe: FakeProbe, val text: String) : AsrEngine {
        val inferenceMs = ArrayDeque(listOf(50L))
        var netBytesPerCall = 0L
        var calls = 0

        /** 第几次调用抛异常（从 1 数起）；null 表示从不失败。 */
        var failOnCall: Int? = null
        override fun recognize(samples: FloatArray): EngineOutput {
            calls++
            if (calls == failOnCall) throw IllegalStateException("引擎故障")
            clock.advanceMs(inferenceMs.removeFirstOrNull() ?: 20L)
            probe.netBytes += netBytesPerCall
            return EngineOutput(text, "<|zh|>")
        }
        override fun close() {}
    }

    private val clock = Clock()
    private val probe = FakeProbe(clock)
    private val engine = FakeEngine(clock, probe, transcript)
    private var factoryCalls = 0
    private val factory = AsrEngineFactory { _ -> factoryCalls++; engine }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 写出两个小文件并返回与之吻合的清单 JSON。 */
    private fun installModel(dir: File): String {
        dir.mkdirs()
        val model = ByteArray(2048) { (it * 13 + 5).toByte() }
        val tokens = "a 0\n".toByteArray()
        File(dir, "model.int8.onnx").writeBytes(model)
        File(dir, "tokens.txt").writeBytes(tokens)
        return """
            {
              "manifest_version": 1, "model_id": "test-model", "model_version": "test-1", "family": "sense_voice",
              "languages": ["zh","en"], "sample_rate_hz": 16000, "limits": { "max_audio_duration_ms": 30000 },
              "engine": { "name": "sherpa-onnx", "version_range": "==2.0.1" },
              "license": { "name": "test", "source_url": "https://example.invalid/l", "redistribution": "unconfirmed", "note": "测试" },
              "archive": { "url": "https://example.invalid/m.tar.bz2", "size_bytes": 1, "sha256": "${"b".repeat(64)}" },
              "files": [
                { "role": "model", "path": "model.int8.onnx", "size_bytes": ${model.size}, "sha256": "${sha256(model)}" },
                { "role": "tokens", "path": "tokens.txt", "size_bytes": ${tokens.size}, "sha256": "${sha256(tokens)}" }
              ]
            }
        """.trimIndent()
    }

    private fun writeWav(file: File, ms: Int = 1000, rate: Int = 16000) {
        val frames = (rate.toLong() * ms / 1000).toInt() // 先转 Long 再乘：192 kHz × 64 s 的 Int 乘积会溢出
        val pcm = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { pcm.putShort((it % 40).toShort()) }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcm.capacity()).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(pcm.capacity())
        file.writeBytes(header.array() + pcm.array())
    }

    private fun transcription() = FileTranscription(factory, probe, nanoTime = { clock.ns })

    private fun request(
        manifestJson: String = installModel(File(tmp.root, "model")),
        modelDir: File = File(tmp.root, "model"),
        warmRepeats: Int = 0,
    ): FileTranscription.Request {
        val wav = File(tmp.root, "in.wav").also { writeWav(it) }
        return FileTranscription.Request(manifestJson, modelDir, wav, File(tmp.root, "out"), warmRepeats)
    }

    private fun report(): JsonObject =
        Json.parseToJsonElement(File(tmp.root, "out/run-report.json").readText(Charsets.UTF_8)).jsonObject

    @Test
    fun `成功时写出合同文件与运行报告`() {
        val result = transcription().run(request())

        assertEquals(TranscriptionState.SUCCEEDED, result.finalState)
        assertTrue(File(tmp.root, "out/revision-content.json").isFile)
        assertTrue(File(tmp.root, "out/trace.json").isFile)
        val r = report()
        assertEquals("SUCCEEDED", r.getValue("final_state").jsonPrimitive.content)
        assertEquals(1000L, r.getValue("audio").jsonObject.getValue("duration_ms").jsonPrimitive.long)
        assertEquals(0.05, r.getValue("metrics").jsonObject.getValue("rtf").jsonPrimitive.double, 1e-9)
        assertEquals(1, factoryCalls)
    }

    @Test
    fun `运行报告不含口述正文也不含本机路径`() {
        transcription().run(request())

        val text = File(tmp.root, "out/run-report.json").readText(Charsets.UTF_8)
        assertFalse("正文只能出现在修订内容文件里", text.contains(transcript))
        assertFalse(text.contains(tmp.root.path))
    }

    @Test
    fun `冷启动取自第一次转录结束的那一刻 不含之后的热运行`() {
        transcription().run(request(warmRepeats = 2))

        // 进程启动后 2000 ms 开始；第一次推理 50 ms；热运行各 20 ms 之后才结束，不能算进冷启动
        assertEquals(2050L, report().getValue("process").jsonObject.getValue("start_to_first_result_ms").jsonPrimitive.long)
    }

    @Test
    fun `峰值内存取运行前后两次读数 当前驻留内存取运行之后`() {
        transcription().run(request())

        val p = report().getValue("process").jsonObject
        assertEquals(1000L, p.getValue("vm_hwm_kb_before").jsonPrimitive.long)
        assertEquals(5000L, p.getValue("vm_hwm_kb_after").jsonPrimitive.long)
        assertEquals(4000L, p.getValue("vm_rss_kb_after").jsonPrimitive.long)
    }

    @Test
    fun `读不到内存数据时写 null 而不是 0`() {
        probe.peakReads = ArrayDeque()
        probe.rssKb = null

        transcription().run(request())

        val p = report().getValue("process").jsonObject
        assertTrue(p.getValue("vm_hwm_kb_before") is JsonNull)
        assertTrue(p.getValue("vm_hwm_kb_after") is JsonNull)
        assertTrue(p.getValue("vm_rss_kb_after") is JsonNull)
    }

    @Test
    fun `本应用 UID 的网络字节增量如实写进报告 有流量就不是 0`() {
        engine.netBytesPerCall = 7

        transcription().run(request())

        assertEquals(7L, report().getValue("process").jsonObject.getValue("uid_network_bytes_delta").jsonPrimitive.long)
    }

    @Test
    fun `没有流量时增量是 0`() {
        transcription().run(request())

        assertEquals(0L, report().getValue("process").jsonObject.getValue("uid_network_bytes_delta").jsonPrimitive.long)
    }

    @Test
    fun `热运行的推理耗时按顺序写进报告 并且复用同一个引擎`() {
        engine.inferenceMs.addAll(listOf(30L, 20L))

        transcription().run(request(warmRepeats = 2))

        val r = report()
        assertEquals(50L, r.getValue("metrics").jsonObject.getValue("inference_ms").jsonPrimitive.long)
        assertEquals(JsonArray(listOf(JsonPrimitive(30L), JsonPrimitive(20L))), r.getValue("warm_inference_ms"))
        assertEquals(1, factoryCalls)
        assertEquals(3, engine.calls)
    }

    @Test
    fun `热运行中途失败时整次运行失败 不把失败的那次记成 0 毫秒耗时`() {
        engine.failOnCall = 3 // 第一次转录与第一次热运行成功，第二次热运行失败
        val req = request(warmRepeats = 2)

        val e = assertThrows(IllegalStateException::class.java) { transcription().run(req) }

        assertTrue("消息要指出是第几次热运行：${e.message}", e.message!!.contains("第 2 次"))
        assertFalse("没有可信的基准就不留报告", req.outDir.exists())
    }

    /** 上一次成功运行留下的全套输出，外加一个不属于本程序的文件。 */
    private fun leaveStaleOutputs(outDir: File) {
        outDir.mkdirs()
        listOf("revision-content.json", "error.json", "capabilities.json", "task-handle.json", "trace.json", "run-report.json")
            .forEach { File(outDir, it).writeText("STALE-$it", Charsets.UTF_8) }
        File(outDir, "keep.txt").writeText("不是本程序写的文件", Charsets.UTF_8)
    }

    private fun assertNoStaleOutputsButUnrelatedFileKept(outDir: File) {
        val leftovers = outDir.listFiles()!!.filter { it.readText(Charsets.UTF_8).startsWith("STALE-") }.map { it.name }
        assertEquals("上一次的正文与报告不能在这次失败之后还躺着，被当成这次的结果", emptyList<String>(), leftovers)
        assertTrue("只清本程序写的文件，目录里别的东西不动", File(outDir, "keep.txt").isFile)
    }

    @Test
    fun `热运行失败时 复用的输出目录里上一次的正文与报告被清掉`() {
        engine.failOnCall = 3
        val req = request(warmRepeats = 2)
        leaveStaleOutputs(req.outDir)

        assertThrows(IllegalStateException::class.java) { transcription().run(req) }

        assertNoStaleOutputsButUnrelatedFileKept(req.outDir)
    }

    @Test
    fun `WAV 不合法时 复用的输出目录里上一次的正文与报告也被清掉`() {
        val req = request()
        req.wav.writeText("这不是 WAV")
        leaveStaleOutputs(req.outDir)

        assertThrows(WavFormatException::class.java) { transcription().run(req) }

        assertNoStaleOutputsButUnrelatedFileKept(req.outDir)
    }

    @Test
    fun `原生库加载失败时报告里的引擎信息写 unavailable 而不是让整次运行崩溃`() {
        probe.engineInfoFailure = UnsatisfiedLinkError("no libsherpa-onnx-jni.so")
        val failing = AsrEngineFactory { _ -> throw UnsatisfiedLinkError("no libsherpa-onnx-jni.so") }

        val result = FileTranscription(failing, probe, nanoTime = { clock.ns }).run(request())

        assertEquals(TranscriptionState.FAILED, result.finalState)
        assertEquals("FAILED", report().getValue("final_state").jsonPrimitive.content)
        assertEquals("unavailable", report().getValue("engine").jsonObject.getValue("version").jsonPrimitive.content)
    }

    @Test
    fun `设备与引擎信息原样写进报告`() {
        transcription().run(request())

        val r = report()
        assertEquals("ranchu", r.getValue("device").jsonObject.getValue("hardware").jsonPrimitive.content)
        assertEquals("2.0.1", r.getValue("engine").jsonObject.getValue("version").jsonPrimitive.content)
        assertEquals("test-model", r.getValue("model").jsonObject.getValue("model_id").jsonPrimitive.content)
    }

    @Test
    fun `模型校验不过时终态是等待模型 仍写出文件 并且不创建引擎`() {
        val json = installModel(File(tmp.root, "model"))
        File(tmp.root, "model/tokens.txt").writeText("a 1\n") // 同样大小，内容不同

        val result = transcription().run(request(json))

        assertEquals(TranscriptionState.WAITING_MODEL, result.finalState)
        assertEquals(0, factoryCalls)
        assertEquals("WAITING_MODEL", report().getValue("final_state").jsonPrimitive.content)
        assertTrue(File(tmp.root, "out/error.json").isFile)
        assertFalse(File(tmp.root, "out/revision-content.json").exists())
    }

    @Test
    fun `WAV 不合法时抛出明确的异常 不留下半截输出`() {
        val req = request()
        req.wav.writeText("这不是 WAV")

        assertThrows(WavFormatException::class.java) { transcription().run(req) }

        assertFalse(req.outDir.exists())
        assertEquals(0, factoryCalls)
    }

    @Test
    fun `很大的超长音频也走时长超限的合同失败 而不是在读文件时就按格式错误抛出`() {
        // 192 kHz × 64 s 的 16 位单声道 = 24.6 MB：体积不小，但它是一份合法的、只是太长的音频，结局应与别的超长音频一致
        val req = request()
        writeWav(req.wav, ms = 64_000, rate = 192_000)

        val result = transcription().run(req)

        assertEquals(TranscriptionState.FAILED, result.finalState)
        assertTrue(File(req.outDir, "error.json").readText(Charsets.UTF_8).contains("ASR_RESOURCE_LIMIT"))
        assertEquals("超长音频不能送进引擎", 0, engine.calls)
        assertEquals("也不该为它创建（校验并加载）引擎", 0, factoryCalls)
    }

    @Test
    fun `超过读取上限的大文件在读进内存之前就被拒绝 不碰引擎也不留输出`() {
        val req = request()
        RandomAccessFile(req.wav, "rw").use { it.setLength(WavReader.DEFAULT_MAX_BYTES + 1) } // 稀疏文件，不占磁盘

        val e = assertThrows(WavFormatException::class.java) { transcription().run(req) }

        assertTrue("消息要说明是大小上限：${e.message}", e.message!!.contains("上限"))
        assertFalse(req.outDir.exists())
        assertEquals(0, factoryCalls)
    }

    @Test
    fun `清单不合法时抛出清单无效 不碰引擎`() {
        val e = assertThrows(ModelRefusedException::class.java) { transcription().run(request(manifestJson = "{}")) }

        assertEquals(ModelRefusal.MANIFEST_INVALID, e.refusal)
        assertEquals(0, factoryCalls)
    }

    @Test
    fun `热运行次数不能是负数`() {
        assertThrows(IllegalArgumentException::class.java) { transcription().run(request(warmRepeats = -1)) }
    }

    @Test
    fun `参数被拒绝时 复用的输出目录里上一次的正文与报告同样被清掉`() {
        // “这次失败了”不能和“目录里还躺着上一次成功的结果”同时成立，不管是在哪一步被拒绝的
        val req = request(warmRepeats = -1)
        leaveStaleOutputs(req.outDir)

        assertThrows(IllegalArgumentException::class.java) { transcription().run(req) }

        assertNoStaleOutputsButUnrelatedFileKept(req.outDir)
    }

    @Test
    fun `输入文件恰好就是输出目录里将被清掉的文件时 先拒绝 不能把用户的输入删了`() {
        val req = request()
        req.outDir.mkdirs()
        val input = File(req.outDir, "trace.json").also { writeWav(it) }
        val before = input.readBytes()

        assertThrows(IllegalArgumentException::class.java) {
            transcription().run(FileTranscription.Request(req.manifestJson, req.modelDir, input, req.outDir))
        }

        assertTrue("输入还在", input.isFile)
        assertTrue("内容没变", before.contentEquals(input.readBytes()))
        assertEquals(0, factoryCalls)
    }

    @Test
    fun `通过符号链接指向将被清掉的输出文件的输入也一样被拒绝`() {
        val req = request()
        req.outDir.mkdirs()
        val target = File(req.outDir, "run-report.json").also { writeWav(it) }
        val link = File(tmp.root, "alias.wav")
        java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())

        assertThrows(IllegalArgumentException::class.java) {
            transcription().run(FileTranscription.Request(req.manifestJson, req.modelDir, link, req.outDir))
        }

        assertTrue("链接指向的文件还在", target.isFile && target.length() > 44)
    }
}
