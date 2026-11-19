package app.auri.capture.calibration

import app.auri.capture.control.ActiveControlProfile
import app.auri.capture.control.ControlProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileIdentityTest {
    private val idPattern = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")

    @Test
    fun `档案标识：型号折成小写字母数字连字符；什么都不剩就用默认名`() {
        assertEquals("android-freeclip-2", profileIdFor("FreeClip 2"))
        assertEquals("android-huawei-freeclip-2", profileIdFor("  HUAWEI -- FreeClip_2!! "))
        listOf(null, "", "   ", "耳机", "!!!").forEach { assertEquals("'$it'", "android-headset", profileIdFor(it)) }
    }

    @Test
    fun `档案标识永远符合合同的格式，再长的型号也不超过 100 个字符`() {
        listOf("FreeClip 2", "x".repeat(500), "a-".repeat(80), "a".repeat(91) + " b", "A B C D E F G H I J K L M N O P Q R S T U V W X Y Z ".repeat(5)).forEach {
            val id = profileIdFor(it)
            assertTrue("$id", idPattern.matches(id) && id.length <= 100)
            ControlProfile(id, 1, ControlProfile.generic().mapping) // 运行时映射接受它
        }
    }

    @Test
    fun `型号提示：去掉首尾空白，空的是未知（null），最长 100 个字符`() {
        assertEquals("FreeClip 2", modelHintOf("  FreeClip 2 "))
        assertNull(modelHintOf(null))
        assertNull(modelHintOf("   "))
        assertEquals(100, modelHintOf("x".repeat(300))!!.length)
        assertEquals("尾部空白被去掉", "x".repeat(99), modelHintOf("x".repeat(99) + " " + "y")!!)
    }

    @Test
    fun `临时映射盖在保存的映射上，撤掉后恢复`() {
        val saved = ControlProfile.generic().copy(profileId = "android-saved")
        val temporary = ControlProfile.generic().observeOnly().copy(profileId = "calibration-observe-only")
        val active = ActiveControlProfile()
        assertEquals("没有保存过档案就是默认映射", ControlProfile.GENERIC_ID, active.current.profileId)

        active.useSaved(saved)
        assertSame(saved, active.current)
        active.useTemporary(temporary)
        assertSame("校准期间用临时映射", temporary, active.current)
        active.useSaved(saved.copy(profileVersion = 2))
        assertSame("保存新档案不打断校准中的临时映射", temporary, active.current)
        active.useTemporary(null)
        assertEquals(2, active.current.profileVersion)
    }
}
