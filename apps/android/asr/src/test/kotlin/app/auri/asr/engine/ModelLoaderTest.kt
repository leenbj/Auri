package app.auri.asr.engine

import app.auri.asr.TestModel
import app.auri.asr.manifest.ModelRefusal
import app.auri.asr.manifest.ModelRefusedException
import app.auri.asr.manifest.VerifiedModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 清单第二项的核心：哈希不符时加载器在碰引擎之前就拒绝。
 * 引擎工厂在原生库里一旦遇到坏模型可能直接让进程崩溃，所以“工厂一次都没被调用”才是要证明的事。
 */
class ModelLoaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class CountingFactory : AsrEngineFactory {
        var calls = 0
        var lastModel: VerifiedModel? = null
        val engine = object : AsrEngine {
            override fun recognize(samples: FloatArray) = EngineOutput("", null)
            override fun close() {}
        }

        override fun create(model: VerifiedModel): AsrEngine {
            calls++
            lastModel = model
            return engine
        }
    }

    private fun assertRefusedWithoutEngine(model: TestModel, expected: ModelRefusal) {
        val factory = CountingFactory()
        try {
            ModelLoader.load(model.manifest, model.dir, factory)
            fail("应当拒绝")
        } catch (e: ModelRefusedException) {
            assertEquals(expected, e.refusal)
        }
        assertEquals("拒绝之前不得创建引擎", 0, factory.calls)
    }

    @Test
    fun `哈希不符时引擎工厂一次都不会被调用`() {
        val model = TestModel.install(tmp.newFolder("m"))
        model.modelFile().writeBytes(model.modelBytes.copyOf().also { it[0] = (it[0].toInt() xor 0xff).toByte() })

        assertRefusedWithoutEngine(model, ModelRefusal.HASH_MISMATCH)
    }

    @Test
    fun `文件被截断时引擎工厂一次都不会被调用`() {
        val model = TestModel.install(tmp.newFolder("m"))
        model.modelFile().writeBytes(model.modelBytes.copyOf(100))

        assertRefusedWithoutEngine(model, ModelRefusal.SIZE_MISMATCH)
    }

    @Test
    fun `文件缺失时引擎工厂一次都不会被调用`() {
        val model = TestModel.install(tmp.newFolder("m"))
        assertTrue(model.tokensFile().delete())

        assertRefusedWithoutEngine(model, ModelRefusal.NOT_INSTALLED)
    }

    @Test
    fun `校验通过后恰好创建一次引擎 并交出已校验的模型`() {
        val model = TestModel.install(tmp.newFolder("m"))
        val factory = CountingFactory()

        val engine = ModelLoader.load(model.manifest, model.dir, factory)

        assertEquals(1, factory.calls)
        assertSame(factory.engine, engine)
        assertEquals(model.manifest, factory.lastModel!!.manifest)
        assertEquals(model.modelFile().canonicalFile, factory.lastModel!!.file("model"))
    }
}
