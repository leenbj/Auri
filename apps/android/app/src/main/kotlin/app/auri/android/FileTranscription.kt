package app.auri.android

import app.auri.asr.ContractOutput
import app.auri.asr.LocalTranscriber
import app.auri.asr.TranscriptionResult
import app.auri.asr.TranscriptionState
import app.auri.asr.audio.WavReader
import app.auri.asr.engine.AsrEngineFactory
import app.auri.asr.manifest.ModelManifest
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * “转录这个文件”：清单 → WAV → 本地转录 → 合同文件 + 运行报告。验收宿主与仪器测试走同一条路。
 * 输入先读完再写输出：清单或 WAV 有问题时抛异常，不留下半截输出目录。
 */
class FileTranscription(
    private val engineFactory: AsrEngineFactory,
    private val probe: ProcessProbe,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    class Request(
        val manifestJson: String,
        val modelDir: File,
        val wav: File,
        /** 合同文件（capabilities.json 等）与 run-report.json 写在这里。 */
        val outDir: File,
        /** 第一次之后在同一个引擎上多跑几次，只为看推理耗时的波动。 */
        val warmRepeats: Int = 0,
    )

    fun run(request: Request): TranscriptionResult {
        require(request.warmRepeats >= 0) { "warmRepeats 不能是负数" }
        val manifest = ModelManifest.parse(request.manifestJson)
        val audio = WavReader.read(request.wav, maxWavBytes(manifest.limits.maxAudioDurationMs))

        val peakBefore = probe.peakRssKb()
        val netBefore = probe.uidNetworkBytes()

        LocalTranscriber(manifest, request.modelDir, engineFactory, nanoTime).use { transcriber ->
            val first = transcriber.transcribe(audio)
            val startToFirstResultMs = probe.msSinceProcessStart()

            // 只有引擎已经加载（出了结果）才有“热运行”可言；否则每次都会重新校验模型
            val warm = if (first.finalState.engineRan()) {
                List(request.warmRepeats) { i ->
                    val run = transcriber.transcribe(audio)
                    // 失败那次的推理耗时是 0，混进去会让基准好看得不真实：让整次运行失败，不留报告
                    check(run.finalState.engineRan()) { "第 ${i + 1} 次热运行没有跑完（终态 ${run.finalState}），基准不可信" }
                    run.metrics.inferenceMs
                }
            } else {
                emptyList()
            }

            val netAfter = probe.uidNetworkBytes()
            val report = RunReport(
                reportVersion = RunReport.VERSION,
                finalState = first.finalState.name,
                audio = ReportAudio(first.metrics.audioDurationMs, audio.sampleRateHz, audio.channels),
                metrics = with(first.metrics) { ReportMetrics(verifyMs, engineLoadMs, resampleMs, inferenceMs, rtf) },
                process = ReportProcess(
                    startToFirstResultMs = startToFirstResultMs,
                    vmHwmKbBefore = peakBefore,
                    vmHwmKbAfter = probe.peakRssKb(),
                    vmRssKbAfter = probe.currentRssKb(),
                    uidNetworkBytesDelta = if (netBefore != null && netAfter != null) netAfter - netBefore else null,
                ),
                warmInferenceMs = warm,
                // 原生库没能加载时读引擎版本会再抛一次链接错误；报告要能如实写“不可用”，不能因此崩溃
                engine = try { probe.engine() } catch (e: LinkageError) { ReportEngine.UNAVAILABLE },
                model = ReportModel(manifest.modelId, manifest.modelVersion),
                device = probe.device(),
            )
            ContractOutput.write(request.outDir, transcriber.capabilities, first)
            File(request.outDir, "run-report.json").writeText(reportJson.encodeToString(report), Charsets.UTF_8)
            return first
        }
    }

    companion object {
        /** 声明范围内数据量最大的 WAV：192 kHz、双声道、16 位 = 768000 字节/秒。 */
        private const val WORST_CASE_BYTES_PER_SECOND = 192_000L * 2 * 2
        private const val HEADER_SLACK_BYTES = 1L shl 20

        /**
         * 模型时长上限内最大的合法 WAV 有多大；比它还大的文件不用读就知道超限。没有上限或上限太大时用 [WavReader.DEFAULT_MAX_BYTES] 封顶。
         */
        internal fun maxWavBytes(maxAudioDurationMs: Long?): Long {
            val seconds = maxAudioDurationMs?.let { minOf((it + 999) / 1000, 3_600L) } ?: return WavReader.DEFAULT_MAX_BYTES
            return minOf(seconds * WORST_CASE_BYTES_PER_SECOND + HEADER_SLACK_BYTES, WavReader.DEFAULT_MAX_BYTES)
        }

        private fun TranscriptionState.engineRan() = this == TranscriptionState.SUCCEEDED || this == TranscriptionState.NO_SPEECH

        private val reportJson = Json {
            encodeDefaults = true
            explicitNulls = true // 读不到的读数要写 null，不能省略
            prettyPrint = true
        }
    }
}
