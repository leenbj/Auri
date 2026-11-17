package app.auri.asr.audio

/**
 * 交错存放的 16 位整数 PCM。取值范围与合同 adapter.schema.json 的 asr_capabilities.formats 一致：
 * 采样率 8000–192000 Hz，1 或 2 个声道。
 */
class PcmAudio(val samples: ShortArray, val sampleRateHz: Int, val channels: Int) {
    init {
        require(sampleRateHz in SAMPLE_RATE_RANGE) { "采样率超出合同范围 8000–192000：$sampleRateHz" }
        require(channels in 1..2) { "声道数必须是 1 或 2：$channels" }
        require(samples.size % channels == 0) { "样本数 ${samples.size} 不是声道数 $channels 的整数倍" }
    }

    /** 每个声道各一个样本算一帧。 */
    val frames: Int get() = samples.size / channels

    val durationMs: Long get() = frames * 1000L / sampleRateHz

    companion object {
        val SAMPLE_RATE_RANGE = 8000..192000
    }
}
