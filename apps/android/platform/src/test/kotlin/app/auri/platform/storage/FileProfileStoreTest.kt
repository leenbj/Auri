package app.auri.platform.storage

import app.auri.capture.calibration.CalibrationWizard
import app.auri.capture.calibration.EvidenceSetting
import app.auri.capture.calibration.HeadsetProfile
import app.auri.capture.calibration.profileIdFor
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 校准产出的档案要在重启之后还在，但读不出来的档案绝不能让应用崩，也不能被当成“已校准”：读不出来就是没有。
 * 保存要么整份成功，要么旧档案原样保留（保存到一半被打断也不能留下半份档案）。
 */
class FileProfileStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val file get() = File(folder.root, "profile/headset-profile.json")
    private fun store() = FileProfileStore(file)

    /** 一份合规的档案：什么都没测，所以全是“未测试”。 */
    private fun profile(model: String? = "FreeClip 2"): HeadsetProfile {
        val wizard = CalibrationWizard("run-1", EvidenceSetting.UNIT_TEST).also { it.start() }
        repeat(4) { wizard.next() }
        return wizard.buildProfile(profileIdFor(model), 1, model)!!
    }

    @Test
    fun `存下去再读回来是同一份档案，重启（新建一个存储）之后也在`() {
        val saved = profile()
        store().save(saved)

        assertEquals(saved, store().load())
    }

    @Test
    fun `没有档案文件就是没有档案`() {
        assertNull(store().load())
    }

    @Test
    fun `坏掉的文件读成没有档案，不抛异常：乱码、空文件、合规 JSON 但内容违反合同都一样`() {
        file.parentFile.mkdirs()
        listOf(
            "这不是 JSON",
            "",
            """{"schema_version": "1.2"}""",
            // 内容违反合同：没有证据却宣称“支持”（档案不能靠手改文件变成已校准）
            store().also { it.save(profile()) }.let { file.readText().replace("\"NOT_TESTED\"", "\"SUPPORTED_FOREGROUND\"") },
        ).forEach { content ->
            file.writeText(content)
            assertNull("读不出来的档案必须是 null：$content".take(80), store().load())
        }
    }

    @Test
    fun `覆盖保存换成新档案，目录里不留临时文件`() {
        store().save(profile("FreeClip"))
        val second = profile("FreeClip 2")
        store().save(second)

        assertEquals(second, store().load())
        assertEquals(listOf("headset-profile.json"), file.parentFile.list()!!.toList())
    }

    @Test
    fun `保存失败时抛出，旧档案原样还在：不会因为写到一半被打断就丢掉已有的校准`() {
        val old = profile("FreeClip")
        store().save(old)
        // 让临时文件的位置被一个目录占着：写临时文件必然失败
        File(file.parentFile, "headset-profile.json.tmp").mkdirs()

        try {
            store().save(profile("FreeClip 2"))
            fail("写不进去应当抛 IOException，不能悄悄吞掉")
        } catch (expected: IOException) {
            // 预期
        }

        assertEquals(old, store().load())
        assertTrue(file.isFile)
        assertFalse("失败后不能留下半份新档案", file.readText().contains("freeclip-2"))
    }

    @Test
    fun `写临时文件这一步失败时也要清理：临时位置不留残余（磁盘写满时会留下半份文件），旧档案还在`() {
        val old = profile("FreeClip")
        store().save(old)
        val temp = File(file.parentFile, "headset-profile.json.tmp")
        temp.mkdirs() // 临时位置被占着：写临时文件必然失败

        try {
            store().save(profile("FreeClip 2"))
            fail("写不进去应当抛 IOException")
        } catch (expected: IOException) {
            // 预期
        }

        assertFalse("失败之后临时位置不能留下东西", temp.exists())
        assertEquals(old, store().load())
    }

    @Test
    fun `替换这一步抛的不是 IOException（框架的运行时异常）：照样清掉写好的临时文件，异常原样抛出（同一个实例），旧档案还在`() {
        val old = profile("FreeClip")
        store().save(old)
        val thrown = UnsupportedOperationException("文件系统不支持这种替换")
        val broken = FileProfileStore(file) { _, _ -> throw thrown }

        try {
            broken.save(profile("FreeClip 2"))
            fail("运行时异常不能被吞掉，调用方要据此告诉用户“没保存上”")
        } catch (caught: UnsupportedOperationException) {
            assertSame("原样抛出，不能换成另一个异常", thrown, caught)
        }

        assertEquals("临时文件不能留在目录里", listOf("headset-profile.json"), file.parentFile.list()!!.toList())
        assertEquals(old, store().load())
    }

    @Test
    fun `替换这一步失败时抛出，并把写好的临时文件清掉：目录里不留垃圾`() {
        // 目标位置被一个非空目录占着：临时文件写得进去，但替换不了
        File(file, "占位").also { it.parentFile.mkdirs() }.writeText("x")

        try {
            store().save(profile())
            fail("替换失败应当抛 IOException")
        } catch (expected: IOException) {
            // 预期
        }

        assertEquals(listOf("headset-profile.json"), file.parentFile.list()!!.toList())
    }
}
