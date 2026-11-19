package app.auri.platform.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 敏感文件（配对记录里的设备令牌、录音、耳机配置）必须放在 noBackupFilesDir：系统的云备份和换机迁移都不会带走那里的东西。
 * 清单里的备份规则是另一道独立的防线——这里守的是“即使规则被改坏，这些文件也不会离开这台手机”。
 * 老版本放在 filesDir（会被备份）里的文件，第一次用之前要原样挪过来，不丢数据、也不在旧位置留一份。
 */
class NoBackupStorageTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val noBackup get() = File(folder.root, "no_backup")
    private val legacy get() = File(folder.root, "files")

    @Test
    fun `敏感文件的位置在 noBackup 目录之下，不在会被备份的旧目录`() {
        val placed = NoBackupStorage.place(noBackup, legacy, "pairing.sealed")

        assertEquals(File(noBackup, "pairing.sealed"), placed)
        assertFalse("没有旧数据时什么也不创建", placed.exists())
    }

    @Test
    fun `旧位置有文件、新位置没有：挪过来，内容不变，旧位置不再留一份`() {
        legacy.mkdirs()
        File(legacy, "pairing.sealed").writeBytes(byteArrayOf(1, 2, 3))

        val placed = NoBackupStorage.place(noBackup, legacy, "pairing.sealed")

        assertEquals(listOf<Byte>(1, 2, 3), placed.readBytes().toList())
        assertFalse("旧位置不能再留着一份（它还在会被备份的目录里）", File(legacy, "pairing.sealed").exists())
    }

    @Test
    fun `整个目录（录音）也能挪，里面的文件一个不少`() {
        File(legacy, "recordings").mkdirs()
        File(legacy, "recordings/a.wav").writeBytes(byteArrayOf(9))
        File(legacy, "recordings/b.wav").writeBytes(byteArrayOf(8, 7))

        val placed = NoBackupStorage.place(noBackup, legacy, "recordings")

        assertEquals(listOf("a.wav", "b.wav"), placed.list()!!.sorted())
        assertFalse(File(legacy, "recordings").exists())
    }

    @Test
    fun `新位置已经有了：不覆盖，旧位置也不动（两份不能互相顶替）`() {
        legacy.mkdirs()
        noBackup.mkdirs()
        File(legacy, "pairing.sealed").writeBytes(byteArrayOf(1))
        File(noBackup, "pairing.sealed").writeBytes(byteArrayOf(2))

        val placed = NoBackupStorage.place(noBackup, legacy, "pairing.sealed")

        assertEquals(listOf<Byte>(2), placed.readBytes().toList())
        assertTrue("新位置已有时旧文件原样保留（由调用方决定怎么处置）", File(legacy, "pairing.sealed").exists())
    }

    @Test
    fun `再调用一次结果不变（幂等）`() {
        legacy.mkdirs()
        File(legacy, "p").writeBytes(byteArrayOf(5))

        val first = NoBackupStorage.place(noBackup, legacy, "p")
        val second = NoBackupStorage.place(noBackup, legacy, "p")

        assertEquals(first, second)
        assertEquals(listOf<Byte>(5), second.readBytes().toList())
    }
}
