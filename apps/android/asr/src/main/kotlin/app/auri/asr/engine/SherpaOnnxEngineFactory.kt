package app.auri.asr.engine

import app.auri.asr.manifest.VerifiedModel
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.VersionInfo

/**
 * sherpa-onnx 的 [AsrEngineFactory]。创建识别器之前先把原生二进制的版本对照清单：
 * 对不上就拒绝，不去碰识别器（读 [VersionInfo] 只加载 JNI 库，不读模型）。
 *
 * 这个类需要原生库，JVM 单元测试跑不了；它在模拟器/真机的仪器测试里跑（E2/E3）。
 */
class SherpaOnnxEngineFactory(
    private val numThreads: Int = DEFAULT_THREADS,
    private val engineVersion: () -> String = { VersionInfo.version },
) : AsrEngineFactory {
    override fun create(model: VerifiedModel): AsrEngine {
        val required = model.manifest.engine
        check(required.name == ENGINE_NAME) { "清单要求的引擎是 ${required.name}，这里只提供 $ENGINE_NAME" }
        val actual = engineVersion()
        check(required.accepts(actual)) { "引擎版本 $actual 不满足清单要求 ${required.versionRange}" }
        val config = SherpaOnnxConfigs.build(model, numThreads)
        return SherpaOnnxEngine(OfflineRecognizer(config = config), model.manifest.sampleRateHz)
    }

    companion object {
        const val ENGINE_NAME = "sherpa-onnx"

        /** 临时取值：手机上的线程数与速度的关系归 Ticket 15 的评测，这里留出核给音频链路。 */
        const val DEFAULT_THREADS = 2

        /** 打包进应用的原生引擎的实际版本；读它会加载 JNI 库，不读模型。 */
        fun runtimeInfo() = EngineRuntimeInfo(VersionInfo.version, VersionInfo.onnxruntimeVersion, VersionInfo.gitSha1)
    }
}

data class EngineRuntimeInfo(val version: String, val onnxruntimeVersion: String, val gitSha1: String)

private class SherpaOnnxEngine(
    private val recognizer: OfflineRecognizer,
    private val sampleRateHz: Int,
) : AsrEngine {
    override fun recognize(samples: FloatArray): EngineOutput {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRateHz)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            return EngineOutput(result.text, result.lang.ifBlank { null })
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()
}
