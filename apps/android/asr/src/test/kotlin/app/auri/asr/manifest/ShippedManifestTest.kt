package app.auri.asr.manifest

import java.io.File
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仓库里随代码提交的真实清单：不是测试夹具，是设备上要信任的那份。
 * Gradle 单元测试的工作目录是模块目录 apps/android/asr，所以清单在 ../manifests。
 */
class ShippedManifestTest {
    private val manifestsDir = File("../manifests")
    private val senseVoice = File(manifestsDir, "sensevoice-small-int8-2024-07-17.manifest.json")

    private fun parse(file: File) = ModelManifest.parse(file.readText(Charsets.UTF_8))

    @Test
    fun `清单目录里每一份清单都能通过严格解析`() {
        val files = manifestsDir.listFiles { f -> f.name.endsWith(".manifest.json") }.orEmpty()

        assertTrue("manifests/ 下应当至少有一份清单", files.isNotEmpty())
        files.forEach { parse(it) }
    }

    @Test
    fun `SenseVoice 清单固定了约定的压缩包大小与 SHA-256 以及两个解出的文件`() {
        val m = parse(senseVoice)

        assertEquals("sense_voice", m.family)
        assertEquals(163002883L, m.archive.sizeBytes)
        assertEquals("7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e", m.archive.sha256)
        assertEquals(listOf("model", "tokens"), m.files.map { it.role })
        assertTrue("model_version 要带内容哈希前缀，名字里的日期不能标识内容", m.modelVersion.endsWith("+" + m.archive.sha256.take(12)))
        assertEquals(listOf("zh", "en", "ja", "ko", "yue"), m.languages)
        assertEquals(16000, m.sampleRateHz)
    }

    @Test
    fun `清单如实写明权重许可证没有得到最终确认`() {
        val license = parse(senseVoice).license

        assertEquals(Redistribution.UNCONFIRMED, license.redistribution)
        assertTrue(license.name, license.name.contains("FunASR"))
    }

    @Test
    fun `清单要求的引擎版本就是构建里固定的引擎版本`() {
        val pin = Properties().apply { File("../engine/sherpa-onnx.properties").inputStream().use { load(it) } }

        assertEquals("==" + pin.getProperty("version"), parse(senseVoice).engine.versionRange)
        assertEquals(pin.getProperty("name"), parse(senseVoice).engine.name)
    }
}
