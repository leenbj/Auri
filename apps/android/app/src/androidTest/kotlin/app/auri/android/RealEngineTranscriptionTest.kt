package app.auri.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.auri.asr.TranscriptionOutcome
import app.auri.asr.TranscriptionResult
import app.auri.asr.TranscriptionState
import app.auri.asr.audio.WavReader
import app.auri.asr.engine.SherpaOnnxEngineFactory
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ticket 14：真引擎 + 真模型在设备上转录（模拟器 E2，真机 E3）。
 *
 * 期望文本是**固定的这份 int8 模型包在固定引擎上的实测输出**，不是人工标注的真值：
 * 它们只证明“整条管线（WAV 读取 → 重采样 → 特征 → 引擎 → 解码）没有被改坏”，不证明识别准确率；
 * 准确率评测是 Ticket 15 的事。所以只要求相对编辑距离不超过 [TOLERANCE]，而不是逐字相等。
 */
@RunWith(AndroidJUnit4::class)
class RealEngineTranscriptionTest {
    private companion object {
        const val TOLERANCE = 0.2

        /** 样本名 → (实测输出, 期望的合同语言标签) */
        val observed = mapOf(
            "zh" to ("开饭时间早上9点至下午5点。" to "zh"),
            "en" to ("The tribal chieftain called for the boy and presented him with 50 pieces of code." to "en"),
            "ja" to ("うちの中学は弁当制で持っていきない場合は50円の学校販売のパンを買う。" to "ja"),
            "ko" to ("조금만 생각을 하면서 살면 훨씬 편할 거야." to "ko"),
            "yue" to ("呢几个字都表达唔到我想讲嘅意思。" to "yue"),
        )
    }

    private fun transcribe(wav: File, outName: String): TranscriptionResult =
        FileTranscription(SherpaOnnxEngineFactory(), SystemProcessProbe()).run(
            FileTranscription.Request(DeviceEnv.manifestJson, DeviceEnv.modelDir, wav, DeviceEnv.outDir(outName)),
        )

    private fun textOf(result: TranscriptionResult): String =
        (result.outcome as? TranscriptionOutcome.Succeeded)?.content?.text ?: error("期望 SUCCEEDED，实际 ${result.finalState}：${result.outcome.javaClass.simpleName}")

    private fun checkSample(name: String) {
        DeviceEnv.requireInstalled(name)
        val (expectedText, expectedLanguage) = observed.getValue(name)

        val result = transcribe(DeviceEnv.sample(name), "real-$name")

        assertEquals(TranscriptionState.SUCCEEDED, result.finalState)
        val content = (result.outcome as TranscriptionOutcome.Succeeded).content
        val distance = TestAudio.relativeDistance(content.text, expectedText)
        assertTrue("$name：与实测输出的相对编辑距离 $distance 超过 $TOLERANCE", distance <= TOLERANCE)
        assertEquals(expectedLanguage, content.language)
        assertTrue("RTF 必须是有限正数", result.metrics.rtf!! > 0.0)
    }

    @Test fun chineseSampleIsTranscribed() = checkSample("zh")

    @Test fun englishSampleIsTranscribed() = checkSample("en")

    @Test fun japaneseSampleIsTranscribed() = checkSample("ja")

    @Test fun koreanSampleIsTranscribed() = checkSample("ko")

    @Test fun cantoneseSampleIsTranscribed() = checkSample("yue")

    /**
     * 已知限制（2026-11-18 在 API 35 模拟器上实测，E2）：SenseVoice-Small int8 对 3 秒纯数字静音**不会**返回空文本，
     * 而是编出一两个字（观察到韩文“그.”），于是合同状态是 SUCCEEDED 而不是 NO_SPEECH。
     * 所以 NO_SPEECH 只能靠“引擎返回空文本”触发，不能指望引擎自己识别静音；
     * 静音过滤必须由会话内的 VAD 在送进引擎之前做（ADR-0016，Ticket 16 之后），否则静音会变成幻觉文本入库。
     * 这里把这个事实钉成测试：将来换了模型或加了前置闸门导致它不再成立，测试会失败，逼着相关人员有意识地更新。
     * NO_SPEECH 的状态映射本身由 :asr 的 JVM 测试用假引擎覆盖。
     */
    @Test
    fun digitalSilenceIsNotRecognisedAsNoSpeechByTheEngineAlone() {
        DeviceEnv.requireInstalled()
        val silence = File(DeviceEnv.target.cacheDir, "silence-3s.wav")
        TestAudio.writeWav(silence, ShortArray(16000 * 3), 16000)

        val result = transcribe(silence, "real-silence-hallucination")

        assertEquals(TranscriptionState.SUCCEEDED, result.finalState)
        val text = textOf(result)
        assertTrue("幻觉文本应当只有寥寥几个字，实际 ${text.length} 个字符", TestAudio.normalize(text).length <= 8)
    }

    /**
     * 48 kHz 与 44.1 kHz 的输入走真正的重采样再进引擎：把中文样本先升采样成这两个采样率，
     * 转录结果必须和直接转录 16 kHz 原件的结果几乎一致。升采样用的是同一个重采样器，
     * 所以这证明的是“重采样路径上语音内容没有被破坏”，反走样的独立证据在 :asr 的正弦波测试里。
     */
    @Test
    fun speechResampledFrom48kAnd44k1TranscribesLikeTheOriginal() {
        DeviceEnv.requireInstalled("zh")
        val original = WavReader.read(DeviceEnv.sample("zh"))
        val direct = textOf(transcribe(DeviceEnv.sample("zh"), "real-zh-16k-direct"))

        for ((label, rate) in listOf("48k" to 48000, "44k1" to 44100)) {
            val wav = File(DeviceEnv.target.cacheDir, "zh-$label.wav")
            TestAudio.writeWav(wav, TestAudio.resampledPcm(original, rate), rate)

            val result = transcribe(wav, "real-zh-$label")

            val distance = TestAudio.relativeDistance(textOf(result), direct)
            assertTrue("$label：与 16 kHz 直接转录的相对编辑距离 $distance", distance <= 0.1)
            assertEquals("输入文件本身就是 $rate Hz", rate, WavReader.read(wav).sampleRateHz)
        }
    }
}
