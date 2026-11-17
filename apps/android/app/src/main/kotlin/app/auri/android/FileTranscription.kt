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
 * 输入先读完再写输出：清单或 WAV 有问题时抛异常，不留下半截输出目录；输出目录若是复用的，一开始就先清掉上一次的输出，
 * 这样无论这次在哪一步失败，目录里都不会躺着上一次的正文与报告。
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
        // 输出目录可能被复用：先清掉上一次的全套输出（连参数被拒绝的情形也一样），这次不论在哪一步失败，
        // 目录里都不会躺着上一次的正文与报告被误当成这次的结果
        clearOutputs(request.outDir, request.wav)
        require(request.warmRepeats >= 0) { "warmRepeats 不能是负数" }
        val manifest = ModelManifest.parse(request.manifestJson)
        val audio = WavReader.read(request.wav)

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
            File(request.outDir, REPORT_FILE).writeText(reportJson.encodeToString(report), Charsets.UTF_8)
            return first
        }
    }

    companion object {
        private const val REPORT_FILE = "run-report.json"

        /**
         * 清掉上一次留在 [outDir] 里的合同文件与运行报告。[input] 是这次要读的输入：它若就是其中之一，先拒绝而不是删掉它。
         * 入口在做别的校验之前也该先调这个，免得参数被拒绝时旧结果还留着。
         */
        fun clearOutputs(outDir: File, input: File? = null) = ContractOutput.clear(outDir, input, listOf(REPORT_FILE))

        private fun TranscriptionState.engineRan() = this == TranscriptionState.SUCCEEDED || this == TranscriptionState.NO_SPEECH

        private val reportJson = Json {
            encodeDefaults = true
            explicitNulls = true // 读不到的读数要写 null，不能省略
            prettyPrint = true
        }
    }
}
