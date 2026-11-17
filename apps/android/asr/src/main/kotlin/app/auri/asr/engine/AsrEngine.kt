package app.auri.asr.engine

import app.auri.asr.manifest.VerifiedModel

/** 引擎一次识别的原始产出。[language] 是引擎自己报告的标记，可能带引擎私有的包装（如 `<|zh|>`），也可能没有。 */
data class EngineOutput(val text: String, val language: String?)

interface AsrEngine : AutoCloseable {
    /** [samples] 必须已经是清单里声明的采样率的单声道 float PCM，取值范围 [-1, 1]。 */
    fun recognize(samples: FloatArray): EngineOutput
}

/** 只接受 [VerifiedModel]：没有通过清单校验的路径无法交到引擎手里。 */
fun interface AsrEngineFactory {
    fun create(model: VerifiedModel): AsrEngine
}
