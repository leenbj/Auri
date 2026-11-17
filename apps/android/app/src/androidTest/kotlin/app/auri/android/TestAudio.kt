package app.auri.android

import app.auri.asr.audio.PcmAudio
import app.auri.asr.audio.Resampler
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 仪器测试用的音频小工具：写 16 位 PCM 的 WAV、用 :asr 的重采样器换采样率、算文本编辑距离。 */
object TestAudio {
    fun writeWav(file: File, samples: ShortArray, sampleRateHz: Int) {
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { pcm.putShort(it) }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcm.capacity()).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(sampleRateHz).putInt(sampleRateHz * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(pcm.capacity())
        file.parentFile?.mkdirs()
        file.writeBytes(header.array() + pcm.array())
    }

    /** 把单声道音频换成 [targetRateHz] 的 16 位 PCM（用 :asr 的重采样器，削顶保护）。 */
    fun resampledPcm(audio: PcmAudio, targetRateHz: Int): ShortArray =
        Resampler.toMonoFloat(audio, targetRateHz).let { floats ->
            ShortArray(floats.size) { max(-32768f, min(32767f, floats[it] * 32768f)).roundToInt().toShort() }
        }

    /** 忽略大小写、空白与标点，只比较字母和数字。 */
    fun normalize(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

    fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1).also { it[0] = i }
            for (j in 1..b.length) {
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            previous = current
        }
        return previous[b.length]
    }

    /** 归一化之后的相对编辑距离：0 完全一致，1 完全不同。 */
    fun relativeDistance(actual: String, expected: String): Double {
        val a = normalize(actual)
        val e = normalize(expected)
        return editDistance(a, e).toDouble() / max(1, e.length)
    }
}
