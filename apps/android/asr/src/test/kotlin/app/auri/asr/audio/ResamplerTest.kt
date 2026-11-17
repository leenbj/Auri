package app.auri.asr.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 真正的重采样，不是改文件头：把 16 kHz 以外的耳机/手机采样率变成引擎要的 16 kHz 单声道 float。
 * 每条断言都对应一种“做错了也能跑出结果”的实现：
 *  - 只改头或原样当 16 kHz 读 → 音高变了（频率断言会失败）；
 *  - 直接隔点抽样（无低通）→ 高频折叠进语音频带（混叠断言会失败）；
 *  - 忘了乘插值倍数 → 幅度偏小（幅度/直流断言会失败）；
 *  - 滤波器有群延迟 → 时间轴整体后移，段落时间戳会错位（冲激位置断言会失败）。
 */
class ResamplerTest {

    private fun sine(freqHz: Double, rateHz: Int, seconds: Double, amplitude: Double = 0.5): ShortArray =
        ShortArray((seconds * rateHz).toInt()) { n ->
            Math.round(amplitude * 32768.0 * sin(2.0 * PI * freqHz * n / rateHz)).toInt().coerceIn(-32768, 32767).toShort()
        }

    private fun mono(samples: ShortArray, rateHz: Int) = PcmAudio(samples, rateHz, channels = 1)

    /** 汉宁窗单频点 DFT 幅度；窗内是稳态区域，避开两端的滤波器暂态。 */
    private fun amplitudeAt(x: FloatArray, rateHz: Int, freqHz: Double, from: Int = x.size / 10, to: Int = x.size - x.size / 10): Double {
        var re = 0.0
        var im = 0.0
        var windowSum = 0.0
        val len = to - from
        for (n in from until to) {
            val w = 0.5 - 0.5 * cos(2.0 * PI * (n - from) / (len - 1))
            val phase = 2.0 * PI * freqHz * n / rateHz
            re += w * x[n] * cos(phase)
            im -= w * x[n] * sin(phase)
            windowSum += w
        }
        return 2.0 * hypot(re, im) / windowSum
    }

    /**
     * 在期望频率上幅度正确，并且偏离 1% 处几乎没有能量。
     * 稳态窗口约 1.6 s，汉宁窗主瓣半宽约 1.25 Hz，所以偏 1%（>= 3 Hz）就会落到旁瓣里：
     * 这同时证明了“频率被保留”，不只是“某处有能量”。
     */
    private fun assertToneKept(out: FloatArray, rateHz: Int, freqHz: Double, amplitude: Double) {
        assertEquals("幅度 $freqHz Hz", amplitude, amplitudeAt(out, rateHz, freqHz), amplitude * 0.005)
        val offPitch = amplitudeAt(out, rateHz, freqHz * 1.01)
        assertTrue("频率 $freqHz Hz 偏移 1% 处不应有能量，实际 $offPitch", offPitch < amplitude * 0.1)
    }

    private fun interiorRms(x: FloatArray): Double {
        val from = x.size / 10
        val to = x.size - x.size / 10
        var sum = 0.0
        for (n in from until to) sum += x[n].toDouble() * x[n]
        return sqrt(sum / (to - from))
    }

    private fun db(ratio: Double) = 20.0 * log10(ratio)

    // ---- 频率与幅度被保留 ----

    @Test
    fun `48k 降到 16k 保留频率和幅度`() {
        for (freq in listOf(440.0, 1000.0, 3400.0, 6000.0)) {
            val out = Resampler.toMonoFloat(mono(sine(freq, 48000, 2.0), 48000), 16000)

            assertToneKept(out, 16000, freq, 0.5)
        }
    }

    @Test
    fun `44_1k 降到 16k 保留频率和幅度`() {
        for (freq in listOf(1000.0, 5000.0)) {
            val out = Resampler.toMonoFloat(mono(sine(freq, 44100, 2.0), 44100), 16000)

            assertToneKept(out, 16000, freq, 0.5)
        }
    }

    @Test
    fun `8k 升到 16k 保留频率和幅度 因为 HFP 窄带耳机真的是 8k`() {
        for (freq in listOf(300.0, 1000.0, 3000.0)) {
            val out = Resampler.toMonoFloat(mono(sine(freq, 8000, 2.0), 8000), 16000)

            assertToneKept(out, 16000, freq, 0.5)
        }
    }

    // ---- 新奈奎斯特频率以上的成分被衰减，而不是折叠 ----

    @Test
    fun `48k 输入里高于 8k 的音被衰减 不会折叠进语音频带`() {
        // 10 kHz 折叠到 6 kHz，12 kHz 折叠到 4 kHz，20 kHz 折叠到 4 kHz
        for (freq in listOf(10000.0, 12000.0, 20000.0)) {
            val amplitude = 0.9
            val out = Resampler.toMonoFloat(mono(sine(freq, 48000, 2.0, amplitude), 48000), 16000)

            val residual = db(interiorRms(out) / (amplitude / sqrt(2.0)))
            assertTrue("$freq Hz 的残留应低于 -60 dB，实际 ${"%.1f".format(residual)} dB", residual < -60.0)
        }
    }

    @Test
    fun `44_1k 输入里高于 8k 的音被衰减`() {
        // 15 kHz 折叠到 1 kHz，正落在语音最敏感的频段
        val out = Resampler.toMonoFloat(mono(sine(15000.0, 44100, 2.0, 0.9), 44100), 16000)

        val residual = db(interiorRms(out) / (0.9 / sqrt(2.0)))
        assertTrue("残留应低于 -60 dB，实际 ${"%.1f".format(residual)} dB", residual < -60.0)
    }

    @Test
    fun `带内与带外叠加时 带内保留 带外抑制`() {
        val inBand = sine(1000.0, 48000, 2.0, 0.4)
        val outOfBand = sine(12000.0, 48000, 2.0, 0.4)
        val sum = ShortArray(inBand.size) { (inBand[it] + outOfBand[it]).toShort() }

        val out = Resampler.toMonoFloat(mono(sum, 48000), 16000)

        assertToneKept(out, 16000, 1000.0, 0.4)
        val alias = amplitudeAt(out, 16000, 4000.0) // 12 kHz 若发生混叠会落在这里
        assertTrue("折叠分量应低于 -60 dB，实际 ${"%.1f".format(db(alias / 0.4))} dB", db(alias / 0.4) < -60.0)
    }

    @Test
    fun `8k 升到 16k 不产生镜像分量`() {
        // 3 kHz 的音在零插入后会在 8k-3k=5 kHz 处留下镜像，必须被低通滤掉
        val out = Resampler.toMonoFloat(mono(sine(3000.0, 8000, 2.0, 0.8), 8000), 16000)

        assertToneKept(out, 16000, 3000.0, 0.8)
        val image = amplitudeAt(out, 16000, 5000.0)
        assertTrue("镜像应低于 -60 dB，实际 ${"%.1f".format(db(image / 0.8))} dB", db(image / 0.8) < -60.0)
    }

    // ---- 增益、对齐与长度 ----

    @Test
    fun `直流增益为 1`() {
        for (rate in listOf(48000, 44100, 8000)) {
            val out = Resampler.toMonoFloat(mono(ShortArray(rate) { 16384 }, rate), 16000) // 0.5

            val mid = out.size / 2
            assertEquals("$rate Hz", 0.5, out[mid].toDouble(), 0.002)
        }
    }

    @Test
    fun `滤波器零相位 一个冲激留在原来的时刻`() {
        for (rate in listOf(48000, 44100, 8000)) {
            val impulseAt = rate / 10 // 0.1 s
            val x = ShortArray(rate / 2).also { it[impulseAt] = 20000 }

            val out = Resampler.toMonoFloat(mono(x, rate), 16000)

            val peak = out.indices.maxByOrNull { abs(out[it]) }!!
            assertEquals("$rate Hz 的 0.1 s 处冲激应落在 16 kHz 的第 1600 个样本", 1600, peak)
        }
    }

    @Test
    fun `输出长度等于时长按目标采样率换算后向上取整`() {
        for ((rate, count) in listOf(48000 to 48123, 44100 to 44117, 8000 to 8001, 32000 to 31999, 24000 to 5)) {
            val out = Resampler.toMonoFloat(mono(ShortArray(count), rate), 16000)

            assertEquals("$rate Hz × $count", ceil(count * 16000.0 / rate).toInt(), out.size)
        }
    }

    @Test
    fun `满幅度正弦不会被重采样推出范围`() {
        val out = Resampler.toMonoFloat(mono(sine(1000.0, 48000, 1.0, 0.999), 48000), 16000)

        assertTrue(out.all { abs(it) <= 1.001f })
    }

    @Test
    fun `相同采样率只做格式转换 不经过滤波器`() {
        val samples = shortArrayOf(0, 1, -1, 16384, -32768, 32767)

        val out = Resampler.toMonoFloat(mono(samples, 16000), 16000)

        assertArrayEquals(
            floatArrayOf(0f, 1f / 32768f, -1f / 32768f, 0.5f, -1f, 32767f / 32768f),
            out,
            0f,
        )
    }

    // ---- 声道 ----

    @Test
    fun `立体声先取平均混成单声道`() {
        val rate = 16000
        val left = sine(1000.0, rate, 1.0, 0.5)
        val right = ShortArray(left.size) { (-left[it]).toShort() }
        val interleavedOpposite = ShortArray(left.size * 2) { if (it % 2 == 0) left[it / 2] else right[it / 2] }
        val interleavedSame = ShortArray(left.size * 2) { left[it / 2] }

        val cancelled = Resampler.toMonoFloat(PcmAudio(interleavedOpposite, rate, 2), 16000)
        val kept = Resampler.toMonoFloat(PcmAudio(interleavedSame, rate, 2), 16000)

        assertTrue("反相的两路平均后应为静音", cancelled.all { abs(it) < 1e-6f })
        assertToneKept(kept, rate, 1000.0, 0.5)
        assertEquals("混声道后样本数是帧数", left.size, kept.size)
    }

    // ---- 边界 ----

    @Test
    fun `空输入得到空输出 单个样本不抛异常`() {
        assertEquals(0, Resampler.toMonoFloat(mono(ShortArray(0), 48000), 16000).size)

        val one = Resampler.toMonoFloat(mono(shortArrayOf(1000), 48000), 16000)
        assertEquals(1, one.size)
        assertTrue(one[0].isFinite())
    }

    @Test
    fun `不合法的格式在构造时就被拒绝`() {
        fun rejects(block: () -> Unit) {
            try { block(); fail("应当拒绝") } catch (_: IllegalArgumentException) { }
        }
        rejects { PcmAudio(ShortArray(10), 7999, 1) }
        rejects { PcmAudio(ShortArray(10), 192001, 1) }
        rejects { PcmAudio(ShortArray(10), 16000, 0) }
        rejects { PcmAudio(ShortArray(10), 16000, 3) }
        rejects { PcmAudio(ShortArray(11), 16000, 2) } // 声道数不能整除样本数
        rejects { Resampler.toMonoFloat(mono(ShortArray(10), 16000), 7999) }
    }

    @Test
    fun `时长按帧数计算 立体声不会翻倍`() {
        assertEquals(1000L, PcmAudio(ShortArray(16000), 16000, 1).durationMs)
        assertEquals(1000L, PcmAudio(ShortArray(32000), 16000, 2).durationMs)
        assertEquals(500L, PcmAudio(ShortArray(24000), 48000, 1).durationMs)
    }
}
