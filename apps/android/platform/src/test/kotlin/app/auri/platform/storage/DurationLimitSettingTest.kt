package app.auri.platform.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DurationLimitSettingTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `default and valid limits persist while invalid values are rejected`() {
        val file = folder.root.resolve("duration-limit")
        val setting = DurationLimitSetting(file)
        assertEquals(30, setting.minutes.value)
        setting.save(120)
        assertEquals(120, DurationLimitSetting(file).minutes.value)
        assertThrows(IllegalArgumentException::class.java) { setting.save(121) }
        assertThrows(IllegalArgumentException::class.java) { setting.save(0) }
        assertEquals(120, DurationLimitSetting(file).minutes.value)
    }

    @Test
    fun `corrupt or out of range saved values use default and the minimum is valid`() {
        val file = folder.root.resolve("duration-limit")
        for (invalid in listOf("", "garbage", "0", "121")) {
            file.writeText(invalid)
            assertEquals(30, DurationLimitSetting(file).minutes.value)
        }
        DurationLimitSetting(file).save(1)
        assertEquals(1, DurationLimitSetting(file).minutes.value)
    }
}
