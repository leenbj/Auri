package app.auri.asr.engine

import app.auri.asr.TestModel
import app.auri.asr.manifest.ModelVerifier
import app.auri.asr.manifest.VerifiedModel
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎配置只能由“校验通过的文件”造出来。这里不加载原生库（JVM 上没有），
 * 只检查传给引擎的东西：路径、线程、运行后端、版本门槛。真引擎在模拟器的仪器测试里跑。
 */
class SherpaOnnxConfigsTest {
    private fun verified(): VerifiedModel {
        val dir = Files.createTempDirectory("auri-cfg").toFile()
        val installed = TestModel.install(dir)
        return ModelVerifier().verify(installed.manifest, dir)
    }

    @Test
    fun `传给引擎的路径是校验过的绝对路径而不是清单里的相对路径`() {
        val model = verified()

        val config = SherpaOnnxConfigs.build(model, numThreads = 2)

        val sv = config.modelConfig.senseVoice
        assertEquals(model.file("model").path, sv.model)
        assertEquals(model.file("tokens").path, config.modelConfig.tokens)
        assertTrue(File(sv.model).isAbsolute)
        assertTrue(File(config.modelConfig.tokens).isAbsolute)
    }

    @Test
    fun `只用 CPU 后端并且不开引擎的调试输出`() {
        val config = SherpaOnnxConfigs.build(verified(), numThreads = 3)

        assertEquals("cpu", config.modelConfig.provider)
        assertFalse(config.modelConfig.debug)
        assertEquals(3, config.modelConfig.numThreads)
    }

    @Test
    fun `特征采样率来自清单`() {
        val model = verified()

        assertEquals(model.manifest.sampleRateHz, SherpaOnnxConfigs.build(model, 1).featConfig.sampleRate)
    }

    @Test
    fun `语言交给模型自动判断而不是写死`() {
        assertEquals("", SherpaOnnxConfigs.build(verified(), 1).modelConfig.senseVoice.language)
    }

    @Test
    fun `未知的模型家族被拒绝而不是套用某个默认值`() {
        val ok = verified()
        val unknown = VerifiedModel(ok.manifest.copy(family = "whisper"), mapOf("model" to ok.file("model"), "tokens" to ok.file("tokens")))

        val e = assertThrows(IllegalArgumentException::class.java) { SherpaOnnxConfigs.build(unknown, 1) }

        assertTrue(e.message, e.message!!.contains("whisper"))
    }

    @Test
    fun `线程数必须是正数`() {
        assertThrows(IllegalArgumentException::class.java) { SherpaOnnxConfigs.build(verified(), 0) }
    }

    @Test
    fun `实际引擎版本和清单要求的精确版本一致才放行`() {
        val engine = verified().manifest.engine

        assertTrue(engine.accepts("2.0.1"))
        assertFalse(engine.accepts("2.0.2"))
        assertFalse(engine.accepts("2.0"))
        assertFalse(engine.accepts("==2.0.1"))
        assertFalse(engine.accepts(""))
    }
}
