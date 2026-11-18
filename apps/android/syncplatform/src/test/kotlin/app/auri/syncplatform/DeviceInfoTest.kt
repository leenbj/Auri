package app.auri.syncplatform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 配对时报给网关的设备信息：能在网关命令行里认出是哪台手机，但不带任何识别个人的标识；字段长度永远合乎合同。 */
class DeviceInfoTest {
    @Test
    fun `只有系统版本和厂商型号`() {
        val info = deviceInfoOf("0.0.1", "15", 35, "Google", "Pixel 9")

        assertEquals("0.0.1", info.appVersion)
        assertEquals("Android 15 (API 35)", info.osBuild)
        assertEquals("Google Pixel 9", info.displayName)
    }

    @Test
    fun `厂商或型号缺失时仍给出合格的名字`() {
        assertEquals("Pixel 9", deviceInfoOf("1", "15", 35, null, "Pixel 9").displayName)
        assertEquals("Google", deviceInfoOf("1", "15", 35, " Google ", "").displayName)
        assertEquals("Android 设备", deviceInfoOf("1", "15", 35, "", null).displayName)
        assertEquals("Android ? (API 35)", deviceInfoOf("1", null, 35, "G", "P").osBuild)
    }

    @Test
    fun `过长的字段按合同上限截断而不是抛异常`() {
        val info = deviceInfoOf("v".repeat(200), "r".repeat(200), 35, "M".repeat(200), "N".repeat(200))

        assertEquals(64, info.appVersion.length)
        assertTrue(info.osBuild.length <= 100)
        assertEquals(100, info.displayName.length)
    }

    @Test
    fun `应用版本为空时也合格`() {
        assertEquals("unknown", deviceInfoOf("", "15", 35, "G", "P").appVersion)
    }
}
