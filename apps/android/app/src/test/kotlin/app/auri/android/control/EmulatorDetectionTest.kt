package app.auri.android.control

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 校准证据等级的环境依据：判成“模拟器”就只能得到 E2。
 * 两个方向的错误代价不同：真机被错判成模拟器，车主的真实校准就永远拿不到 E3；
 * 所以只认模拟器独有的强特征，宁可漏判（漏判时还有“用户必须明确确认真机 + 真实耳机”这道手动确认）。
 */
class EmulatorDetectionTest {
    private data class Device(val label: String, val fingerprint: String, val model: String, val hardware: String, val product: String)

    private val realDevices = listOf(
        Device("Pixel 8", "google/shiba/shiba:15/AP4A.250205.002/12821496:user/release-keys", "Pixel 8", "shiba", "shiba"),
        Device("华为", "HUAWEI/ANE-LX1/HWANE:9/HUAWEIANE-L21/9.1.0.360C432:user/release-keys", "ANE-LX1", "hi6250", "ANE-LX1EEA"),
        Device("小米", "Xiaomi/zeus/zeus:14/UKQ1.230804.001/V816.0.7.0.UMCCNXM:user/release-keys", "2201123C", "qcom", "zeus"),
        // 名字里碰巧带 sdk / generic 字样的真机型号不能被误伤
        Device("名字像模拟器的真机", "vendor/sdkphone/sdkphone:14/ABC/1:user/release-keys", "Generic Phone", "mt6893", "sdkphone"),
    )

    private val emulators = listOf(
        Device("AVD API 35 arm64", "google/sdk_gphone64_arm64/emu64a:15/AE3A.240806.043/12960925:userdebug/dev-keys", "sdk_gphone64_arm64", "ranchu", "sdk_gphone64_arm64"),
        Device("旧版 x86 AVD", "generic_x86/sdk_google_phone_x86/generic_x86:8.0.0/OSR1.170901.056/4385858:userdebug/test-keys", "Android SDK built for x86", "goldfish", "sdk_google_phone_x86"),
    )

    private fun Device.isEmulator() = looksLikeEmulator(fingerprint, model, hardware, product)

    @Test
    fun `真机一律不是模拟器`() {
        realDevices.forEach { assertEquals(it.label, false, it.isEmulator()) }
    }

    @Test
    fun `官方模拟器一律认得出`() {
        emulators.forEach { assertEquals(it.label, true, it.isEmulator()) }
    }

    @Test
    fun `每一条强特征单独出现就够：别的字段都像真机也判模拟器`() {
        val real = realDevices.first()
        fun withOnly(fingerprint: String = real.fingerprint, model: String = real.model, hardware: String = real.hardware, product: String = real.product) =
            looksLikeEmulator(fingerprint, model, hardware, product)

        assertEquals("fingerprint 以 generic 开头", true, withOnly(fingerprint = "generic/vbox86p/vbox86p:9/PQ3A/1:userdebug/test-keys"))
        assertEquals("型号写着 Emulator", true, withOnly(model = "Android Emulator"))
        assertEquals("型号写着 Android SDK built for", true, withOnly(model = "Android SDK built for arm64"))
        assertEquals("硬件 goldfish", true, withOnly(hardware = "goldfish"))
        assertEquals("硬件 ranchu", true, withOnly(hardware = "ranchu"))
        assertEquals("产品名以 sdk_ 开头", true, withOnly(product = "sdk_gphone64_x86_64"))
    }

    @Test
    fun `字段为空串也不崩，也不判成模拟器`() {
        assertEquals(false, looksLikeEmulator("", "", "", ""))
    }
}
