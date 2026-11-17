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
        val audio = WavReader.read(request.wav)

        val peakBefore = probe.peakRssKb()
        val netBefore = probe.uidNetworkBytes()

        LocalTranscriber(manifest, request.modelDir, engineFactory, nanoTime).use { transcriber ->
            val first = transcriber.transcribe(audio)
            val startToFirstResultMs = probe.msSinceProcessStart()

            // 只有引擎已经加载（出了结果）才有“热运行”可言；否则每次都会重新校验模型
            val engineRan = first.finalState == TranscriptionState.SUCCEEDED || first.finalState == TranscriptionState.NO_SPEECH
            val warm = if (engineRan) List(request.warmRepeats) { transcriber.transcribe(audio).metrics.inferenceMs } else emptyList()

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
                engine = probe.engine(),
                model = ReportModel(manifest.modelId, manifest.modelVersion),
                device = probe.device(),
            )
            ContractOutput.write(request.outDir, transcriber.capabilities, first)
            File(request.outDir, "run-report.json").writeText(reportJson.encodeToString(report), Charsets.UTF_8)
            return first
        }
    }

    private companion object {
        val reportJson = Json {
            encodeDefaults = true
            explicitNulls = true // 读不到的读数要写 null，不能省略
            prettyPrint = true
        }
    }
}
