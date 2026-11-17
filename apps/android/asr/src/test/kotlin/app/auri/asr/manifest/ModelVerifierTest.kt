package app.auri.asr.manifest

import app.auri.asr.TestModel
import app.auri.asr.contract.ErrorCode
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T-023：模型文件损坏或哈希不符时必须拒绝加载。
 * 这里验证“拒绝”这个决定本身，以及它给出的原因和合同错误码；引擎有没有被碰到由 ModelLoaderTest 验证。
 */
class ModelVerifierTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun refusalOf(model: TestModel, dir: File = model.dir): ModelRefusedException {
        try {
            ModelVerifier().verify(model.manifest, dir)
        } catch (e: ModelRefusedException) {
            return e
        }
        fail("应当拒绝，但通过了校验")
        error("unreachable")
    }

    @Test
    fun `与清单完全吻合的文件通过校验`() {
        val model = TestModel.install(tmp.newFolder("m"))

        val verified = ModelVerifier().verify(model.manifest, model.dir)

        assertArrayEquals(model.modelBytes, verified.file("model").readBytes())
        assertArrayEquals(model.tokensBytes, verified.file("tokens").readBytes())
        assertEquals(model.manifest, verified.manifest)
    }

    @Test
    fun `改动一个字节而大小不变的模型文件被拒绝 原因是哈希不符`() {
        val model = TestModel.install(tmp.newFolder("m"))
        val corrupted = model.modelBytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x01).toByte() }
        model.modelFile().writeBytes(corrupted)

        val e = refusalOf(model)

        assertEquals(ModelRefusal.HASH_MISMATCH, e.refusal)
        assertEquals(ErrorCode.MODEL_INTEGRITY_FAILED, e.errorCode)
        assertTrue("消息要指出是哪个文件", e.message!!.contains("model.int8.onnx"))
    }

    @Test
    fun `被截断的模型文件被拒绝 原因是大小不符`() {
        val model = TestModel.install(tmp.newFolder("m"))
        model.modelFile().writeBytes(model.modelBytes.copyOf(model.modelBytes.size - 1))

        val e = refusalOf(model)

        assertEquals(ModelRefusal.SIZE_MISMATCH, e.refusal)
        assertEquals(ErrorCode.MODEL_INTEGRITY_FAILED, e.errorCode)
    }

    @Test
    fun `被截成空文件和被追加内容的文件同样被拒绝`() {
        val model = TestModel.install(tmp.newFolder("m"))

        model.tokensFile().writeBytes(ByteArray(0))
        assertEquals(ModelRefusal.SIZE_MISMATCH, refusalOf(model).refusal)

        model.tokensFile().writeBytes(model.tokensBytes + byteArrayOf(0x0a))
        assertEquals(ModelRefusal.SIZE_MISMATCH, refusalOf(model).refusal)
    }

    @Test
    fun `缺少文件被拒绝 并映射为模型未安装`() {
        val model = TestModel.install(tmp.newFolder("m"))
        assertTrue(model.tokensFile().delete())

        val e = refusalOf(model)

        assertEquals(ModelRefusal.NOT_INSTALLED, e.refusal)
        assertEquals(ErrorCode.MODEL_NOT_INSTALLED, e.errorCode)
    }

    @Test
    fun `模型目录不存在被拒绝 并映射为模型未安装`() {
        val model = TestModel.install(tmp.newFolder("m"))

        val e = refusalOf(model, dir = File(tmp.root, "never-created"))

        assertEquals(ModelRefusal.NOT_INSTALLED, e.refusal)
        assertEquals(ErrorCode.MODEL_NOT_INSTALLED, e.errorCode)
    }

    @Test
    fun `用同名目录冒充文件被拒绝`() {
        val model = TestModel.install(tmp.newFolder("m"))
        assertTrue(model.tokensFile().delete())
        assertTrue(model.tokensFile().mkdir())

        assertEquals(ModelRefusal.NOT_INSTALLED, refusalOf(model).refusal)
    }

    @Test
    fun `符号链接指向模型目录之外的文件被拒绝 即使内容恰好一致`() {
        val model = TestModel.install(tmp.newFolder("m"))
        val outside = tmp.newFile("outside.onnx").also { it.writeBytes(model.modelBytes) }
        assertTrue(model.modelFile().delete())
        Files.createSymbolicLink(model.modelFile().toPath(), outside.toPath())

        val e = refusalOf(model)

        assertEquals(ModelRefusal.UNSAFE_PATH, e.refusal)
        assertEquals(ErrorCode.MODEL_INTEGRITY_FAILED, e.errorCode)
    }

    @Test
    fun `拒绝消息不包含文件内容`() {
        val model = TestModel.install(tmp.newFolder("m"))
        val replacement = "SECRET!!".toByteArray()
        assertEquals("要走哈希比较这条路径，替换内容必须与原文件等长", model.tokensBytes.size, replacement.size)
        model.tokensFile().writeBytes(replacement)

        val e = refusalOf(model)

        assertEquals(ModelRefusal.HASH_MISMATCH, e.refusal)
        assertFalse(e.message!!.contains("SECRET"))
    }
}
