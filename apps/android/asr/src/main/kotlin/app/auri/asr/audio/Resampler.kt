package app.auri.asr.audio

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 把任意采样率的 16 位 PCM 变成引擎要的单声道 float（目标采样率通常是 16 kHz），是真正的重采样，不是改文件头。
 *
 * 算法：有理数倍率的多相（polyphase）FIR 重采样，滤波器是凯泽窗（Kaiser window）加窗的 sinc 低通。
 * 把采样率 Fin 换成 Fout，约掉最大公约数得到 L/M（L = 插值倍数，M = 抽取倍数），等价于
 * “补 L-1 个零 → 低通 → 每 M 个取 1 个”，但多相实现只算最终要输出的那些点，不真的补零。
 * 低通的截止频率取 min(Fin, Fout)/2：降采样时挡住新奈奎斯特频率以上的成分，防止折叠（混叠）；
 * 升采样时挡住零插入产生的镜像。过渡带为截止频率的 ±10%，阻带衰减 80 dB。
 * 滤波器以中心抽头为零时刻（非因果、零相位），所以没有群延迟，输出第 m 个样本对应输入的 m·Fin/Fout 时刻。
 *
 * 参考：
 *  - J. F. Kaiser，“Nonrecursive digital filter design using the I0-sinh window function”，1974，
 *    以及 Kaiser & Schafer，“On the use of the I0-sinh window for spectrum analysis”，IEEE Trans. ASSP，1980：
 *    窗参数 β = 0.1102·(A − 8.7)，抽头数 N ≈ (A − 7.95) / (2.285·Δω) + 1（A 为阻带衰减 dB，Δω 为过渡带宽 rad/样本）。
 *  - A. V. Oppenheim & R. W. Schafer，《Discrete-Time Signal Processing》，第 7 章（凯泽窗 FIR 设计）与第 4 章（采样率转换）。
 *  - R. E. Crochiere & L. R. Rabiner，《Multirate Digital Signal Processing》，Prentice-Hall，1983（多相抽取/插值）。
 *  - J. O. Smith，“Digital Audio Resampling Home Page”，CCRMA, Stanford：https://ccrma.stanford.edu/~jos/resample/
 */
object Resampler {
    private const val STOPBAND_ATTENUATION_DB = 80.0
    private const val TRANSITION_FRACTION = 0.2 // 过渡带总宽 = 截止频率的 20%，以截止频率为中心

    /** 先把多声道取平均混成单声道，再换成 [targetRateHz]；样本归一化到 [-1, 1)。 */
    fun toMonoFloat(audio: PcmAudio, targetRateHz: Int): FloatArray {
        require(targetRateHz in PcmAudio.SAMPLE_RATE_RANGE) { "目标采样率超出 8000–192000：$targetRateHz" }
        val mono = downmix(audio)
        return if (audio.sampleRateHz == targetRateHz) mono else polyphase(mono, audio.sampleRateHz, targetRateHz)
    }

    private fun downmix(audio: PcmAudio): FloatArray {
        val s = audio.samples
        return if (audio.channels == 1) {
            FloatArray(audio.frames) { s[it] / 32768f }
        } else {
            FloatArray(audio.frames) { (s[2 * it] + s[2 * it + 1]) / 65536f }
        }
    }

    private fun polyphase(x: FloatArray, inRate: Int, outRate: Int): FloatArray {
        if (x.isEmpty()) return FloatArray(0)
        val g = gcd(inRate, outRate)
        val up = outRate / g
        val down = inRate / g
        val intermediateHz = inRate.toDouble() * up // 补零之后的“中间采样率”

        val cutoffHz = min(inRate, outRate) / 2.0
        val transitionOmega = 2.0 * PI * TRANSITION_FRACTION * cutoffHz / intermediateHz
        val half = ceil((STOPBAND_ATTENUATION_DB - 7.95) / (2.285 * transitionOmega) / 2.0).toInt()
        val taps = kaiserSincLowpass(half, cutoffHz, intermediateHz, up)

        val outLen = ceilDiv(x.size.toLong() * up, down.toLong()).toInt()
        val out = FloatArray(outLen)
        for (m in 0 until outLen) {
            val t = m.toLong() * down // 输出样本在中间采样率时间轴上的位置
            val first = maxOf(0L, ceilDiv(t - half, up.toLong()))
            val last = minOf(x.size - 1L, Math.floorDiv(t + half, up.toLong()))
            var acc = 0.0
            var tap = (t - first * up + half).toInt() // 输入样本 k 对应的抽头下标 = t - k·up + half
            var k = first.toInt()
            while (k <= last) {
                acc += x[k] * taps[tap]
                tap -= up
                k++
            }
            out[m] = acc.toFloat()
        }
        return out
    }

    /**
     * 长度 2·half+1、中心在 half 的凯泽窗 sinc 低通，直流增益归一为 [gain]（插值倍数，补零会把幅度压低这么多倍）。
     */
    private fun kaiserSincLowpass(half: Int, cutoffHz: Double, sampleRateHz: Double, gain: Int): DoubleArray {
        val beta = 0.1102 * (STOPBAND_ATTENUATION_DB - 8.7)
        val i0Beta = besselI0(beta)
        val taps = DoubleArray(2 * half + 1)
        var sum = 0.0
        for (n in -half..half) {
            val r = n.toDouble() / half
            val window = besselI0(beta * sqrt(1.0 - r * r)) / i0Beta
            val x = 2.0 * cutoffHz * n / sampleRateHz
            val sinc = if (n == 0) 1.0 else sin(PI * x) / (PI * x)
            val value = 2.0 * cutoffHz / sampleRateHz * sinc * window
            taps[n + half] = value
            sum += value
        }
        val scale = gain / sum
        for (i in taps.indices) taps[i] *= scale
        return taps
    }

    /** 第一类零阶修正贝塞尔函数 I0，按幂级数求和到项小于总和的 1e-12。 */
    private fun besselI0(x: Double): Double {
        val quarterSquare = x * x / 4.0
        var term = 1.0
        var sum = 1.0
        var k = 1
        while (term > 1e-12 * sum) {
            term *= quarterSquare / (k.toDouble() * k)
            sum += term
            k++
        }
        return sum
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    private fun ceilDiv(a: Long, b: Long): Long = -Math.floorDiv(-a, b)
}
