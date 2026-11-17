package app.auri.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.auri.asr.TranscriptionOutcome
import app.auri.asr.TranscriptionResult
import app.auri.asr.TranscriptionState
import app.auri.asr.contract.ErrorCode
import app.auri.asr.engine.AsrEngineFactory
import app.auri.asr.engine.SherpaOnnxEngineFactory
import app.auri.asr.manifest.VerifiedModel
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ticket 14 第 2 项（设备侧）：哈希或大小对不上的真模型文件，加载器在**碰到原生引擎之前**就拒绝。
 * 用真的 239 MB 模型文件做篡改（翻一个字节、截断），真的 [SherpaOnnxEngineFactory] 包一层计数器：
 * 被拒绝时计数必须是 0，进程还活着（把篡改过的 ONNX 交给原生引擎可能直接让进程崩溃）。
 */
@RunWith(AndroidJUnit4::class)
class ModelRefusalOnDeviceTest {
    private val scratch = File(DeviceEnv.target.cacheDir, "refusal-${UUID.randomUUID()}")

    @After
    fun cleanUp() {
        scratch.deleteRecursively()
    }

    private class CountingFactory(private val real: AsrEngineFactory) : AsrEngineFactory {
        var creates = 0
        override fun create(model: VerifiedModel) = real.create(model).also { creates++ }
    }

    private fun run(modelDir: File, outName: String, factory: CountingFactory): TranscriptionResult =
        FileTranscription(factory, SystemProcessProbe()).run(
            FileTranscription.Request(DeviceEnv.manifestJson, modelDir, DeviceEnv.sample("zh"), DeviceEnv.outDir(outName)),
        )

    /** 真模型目录的拷贝；[mutate] 对拷贝里的模型文件做篡改。 */
    private fun tamperedCopy(name: String, mutate: (File) -> Unit): File {
        val dir = File(scratch, name).also { it.mkdirs() }
        File(DeviceEnv.modelDir, "tokens.txt").copyTo(File(dir, "tokens.txt"))
        val model = File(dir, "model.int8.onnx")
        File(DeviceEnv.modelDir, "model.int8.onnx").copyTo(model)
        mutate(model)
        return dir
    }

    private fun assertRefused(result: TranscriptionResult, code: ErrorCode, factory: CountingFactory) {
        assertEquals(TranscriptionState.WAITING_MODEL, result.finalState)
        val error = (result.outcome as TranscriptionOutcome.WaitingModel).error
        assertEquals(code, error.code)
        assertEquals("原生引擎一次都不能被创建", 0, factory.creates)
    }

    @Test
    fun oneFlippedByteInTheRealModelIsRefusedBeforeTheEngineIsTouched() {
        DeviceEnv.requireInstalled("zh")
        val dir = tamperedCopy("flipped") { model ->
            RandomAccessFile(model, "rw").use { f ->
                f.seek(100_000)
                val original = f.read()
                f.seek(100_000)
                f.write(original xor 0xFF)
            }
        }
        assertEquals("篡改后大小不变，只能靠哈希发现", File(DeviceEnv.modelDir, "model.int8.onnx").length(), File(dir, "model.int8.onnx").length())
        val factory = CountingFactory(SherpaOnnxEngineFactory())

        assertRefused(run(dir, "refused-corrupted", factory), ErrorCode.MODEL_INTEGRITY_FAILED, factory)
    }

    @Test
    fun aTruncatedRealModelIsRefusedBeforeTheEngineIsTouched() {
        DeviceEnv.requireInstalled("zh")
        val dir = tamperedCopy("truncated") { model -> RandomAccessFile(model, "rw").use { it.setLength(1L shl 20) } }
        val factory = CountingFactory(SherpaOnnxEngineFactory())

        assertRefused(run(dir, "refused-truncated", factory), ErrorCode.MODEL_INTEGRITY_FAILED, factory)
    }

    @Test
    fun aModelDirectoryThatDoesNotExistIsNotInstalledNotCorrupt() {
        DeviceEnv.requireInstalled("zh")
        val factory = CountingFactory(SherpaOnnxEngineFactory())

        val result = run(File(scratch, "never-installed"), "refused-not-installed", factory)

        assertRefused(result, ErrorCode.MODEL_NOT_INSTALLED, factory)
        assertTrue((result.outcome as TranscriptionOutcome.WaitingModel).error.retryable)
    }

    @Test
    fun theIntactModelStillWorksAfterRefusals() {
        DeviceEnv.requireInstalled("zh")
        val factory = CountingFactory(SherpaOnnxEngineFactory())
        assertRefused(run(File(scratch, "never-installed"), "refused-then-ok-1", factory), ErrorCode.MODEL_NOT_INSTALLED, factory)

        val result = run(DeviceEnv.modelDir, "refused-then-ok-2", factory)

        assertEquals(TranscriptionState.SUCCEEDED, result.finalState)
        assertEquals(1, factory.creates)
        assertFalse((result.outcome as TranscriptionOutcome.Succeeded).content.text.isBlank())
    }
}
