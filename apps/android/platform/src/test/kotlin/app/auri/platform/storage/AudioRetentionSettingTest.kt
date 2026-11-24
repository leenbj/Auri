package app.auri.platform.storage

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** FR-TXT-005：可设置音频保留期，默认转录成功后 30 天。 */
class AudioRetentionSettingTest {
    @Test
    fun `文件不存在时默认 30 天，保存后跨重启持久`() {
        val dir = Files.createTempDirectory("auri-retention-test").toFile()
        try {
            val file = File(dir, "audio-retention-days")
            val first = AudioRetentionSetting(file)
            assertEquals(30, first.days.value)

            first.save(7)
            assertEquals(7, first.days.value)

            val reopened = AudioRetentionSetting(file)
            assertEquals(7, reopened.days.value)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `超出 1 到 365 天的输入被拒绝，损坏的文件回退到默认 30 天`() {
        val dir = Files.createTempDirectory("auri-retention-invalid").toFile()
        try {
            val file = File(dir, "audio-retention-days")
            val setting = AudioRetentionSetting(file)
            for (bad in listOf(0, -5, 366)) {
                try {
                    setting.save(bad)
                    fail("应当拒绝 $bad")
                } catch (_: IllegalArgumentException) {
                    // 预期
                }
            }
            file.writeText("not-a-number")
            assertEquals(30, AudioRetentionSetting(file).days.value)
        } finally {
            dir.deleteRecursively()
        }
    }
}
