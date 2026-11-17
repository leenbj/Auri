package app.auri.asr.engine

import app.auri.asr.manifest.VerifiedModel
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig

/** 校验通过的模型 → sherpa-onnx 配置。每个模型家族一个分支，未知家族直接拒绝，不套默认值。 */
internal object SherpaOnnxConfigs {
    fun build(model: VerifiedModel, numThreads: Int): OfflineRecognizerConfig {
        require(numThreads > 0) { "numThreads 必须是正数" }
        val manifest = model.manifest
        val modelConfig = when (manifest.family) {
            "sense_voice" -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = model.file("model").path,
                    language = "", // 空串 = 模型自己判断语言
                    useInverseTextNormalization = true, // 听写要标点与数字格式
                ),
                numThreads = numThreads,
                debug = false,
                provider = "cpu",
                tokens = model.file("tokens").path,
            )
            else -> throw IllegalArgumentException("不支持的模型家族：${manifest.family}")
        }
        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = manifest.sampleRateHz, featureDim = 80),
            modelConfig = modelConfig,
        )
    }
}
