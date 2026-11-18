package app.auri.modelmgmt

import app.auri.asr.manifest.ModelManifest
import java.io.File
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 解包：只写清单里列的文件、去掉包的顶层目录、一切别的条目都不落盘。
 * 它与 execution/acceptance/asr-android/prepare_model.py 的解包规则一致（那个脚本是 Ticket 14 的验收工具）。
 */
class ArchiveInstallerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val model by lazy { TestModel(tmp.newFolder(newFolderName("model"))) }
    private val manifest by lazy { ModelManifest.parse(model.manifestJson) }
    private val into by lazy { tmp.newFolder(newFolderName("into")) }

    private fun archiveOf(vararg items: TestArchives.Item): File =
        File(tmp.root, newFolderName("a") + ".tar.bz2").also { TestArchives.write(it, items.toList()) }

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            fail("应当以 ARCHIVE_INVALID 失败")
        } catch (e: ModelException) {
            assertEquals(ModelErrorKind.ARCHIVE_INVALID, e.error.kind)
        }
    }

    @Test
    fun `只解清单里的文件并去掉顶层目录，说明文件与示例音频一个字节都不写`() {
        ArchiveInstaller.extract(model.archiveFile, manifest, into)

        assertEquals(listOf("model.bin", "tokens.txt"), into.walkTopDown().filter { it.isFile }.map { it.relativeTo(into).path }.sorted().toList())
        assertArrayEquals(model.modelBytes, File(into, "model.bin").readBytes())
        assertArrayEquals(model.tokensBytes, File(into, "tokens.txt").readBytes())
    }

    @Test
    fun `包里没有顶层目录也可以`() {
        val archive = archiveOf(
            TestArchives.Item("./model.bin", model.modelBytes),
            TestArchives.Item("tokens.txt", model.tokensBytes),
        )

        ArchiveInstaller.extract(archive, manifest, into)

        assertArrayEquals(model.modelBytes, File(into, "model.bin").readBytes())
        assertArrayEquals(model.tokensBytes, File(into, "tokens.txt").readBytes())
    }

    @Test
    fun `路径逃逸与绝对路径的条目不会写到目标目录之外，也不会被当成清单里的文件`() {
        val outsideName = "escape-${System.nanoTime()}.txt"
        val archive = archiveOf(
            TestArchives.Item("top/model.bin", model.modelBytes),
            TestArchives.Item("top/tokens.txt", model.tokensBytes),
            TestArchives.Item("top/../../$outsideName", "x".toByteArray()),
            TestArchives.Item("/tmp/$outsideName", "x".toByteArray()),
            TestArchives.Item("top/sub/../../../$outsideName", "x".toByteArray()),
        )

        ArchiveInstaller.extract(archive, manifest, into)

        assertFalse(File(into.parentFile, outsideName).exists())
        assertFalse(File("/tmp/$outsideName").exists())
        assertEquals(listOf("model.bin", "tokens.txt"), into.walkTopDown().filter { it.isFile }.map { it.name }.sorted().toList())
    }

    @Test
    fun `符号链接顶替清单里的文件名：不跟随、不算文件，于是缺文件`() {
        val target = File(tmp.root, "secret.txt").apply { writeText("别的地方的文件") }
        val archive = archiveOf(
            TestArchives.Item("top/model.bin", type = TarConstants.LF_SYMLINK, linkName = target.path),
            TestArchives.Item("top/tokens.txt", model.tokensBytes),
        )

        assertInvalid { ArchiveInstaller.extract(archive, manifest, into) }

        assertFalse("不能经链接写出文件", File(into, "model.bin").exists())
        assertEquals("链接指向的文件原样不动", "别的地方的文件", target.readText())
    }

    @Test
    fun `缺少清单要求的文件`() {
        val archive = archiveOf(TestArchives.Item("top/model.bin", model.modelBytes))

        assertInvalid { ArchiveInstaller.extract(archive, manifest, into) }
    }

    @Test
    fun `条目大小与清单不符：在写盘之前就拒绝`() {
        val archive = archiveOf(
            TestArchives.Item("top/model.bin", model.modelBytes + byteArrayOf(1)),
            TestArchives.Item("top/tokens.txt", model.tokensBytes),
        )

        assertInvalid { ArchiveInstaller.extract(archive, manifest, into) }

        assertFalse(File(into, "model.bin").exists())
    }

    @Test
    fun `同一个文件在包里出现两次`() {
        val archive = archiveOf(
            TestArchives.Item("top/model.bin", model.modelBytes),
            TestArchives.Item("top/tokens.txt", model.tokensBytes),
            TestArchives.Item("top/model.bin", model.modelBytes),
        )

        assertInvalid { ArchiveInstaller.extract(archive, manifest, into) }
    }

    @Test
    fun `不是 bzip2 的字节`() {
        val garbage = File(tmp.root, "garbage.tar.bz2").apply { writeBytes(ByteArray(5000) { (it * 7).toByte() }) }

        assertInvalid { ArchiveInstaller.extract(garbage, manifest, into) }
    }

    @Test
    fun `被截断的压缩包`() {
        val truncated = File(tmp.root, "truncated.tar.bz2").apply { writeBytes(model.archiveBytes.copyOf(model.archiveBytes.size / 2)) }

        assertInvalid { ArchiveInstaller.extract(truncated, manifest, into) }
    }

    @Test
    fun `检查点抛出的异常会中断解包（暂停时靠它停下来）`() {
        var calls = 0
        class Stop : RuntimeException()

        try {
            ArchiveInstaller.extract(model.archiveFile, manifest, into) { if (++calls == 3) throw Stop() }
            fail("检查点的异常应当原样传出来")
        } catch (e: Stop) {
            assertTrue(calls >= 3)
        }
        assertFalse("没解完就不该有完整文件", File(into, "tokens.txt").exists())
    }
}
