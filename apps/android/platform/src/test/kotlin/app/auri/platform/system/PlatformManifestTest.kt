package app.auri.platform.system

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element

/**
 * 清单声明了什么，就等于应用被允许做什么。这里钉死：权限恰好这七个、没有网络、没有开机启动，
 * 前台服务是麦克风类型且不导出。权限是否真的授予、前台服务是否真的能起，是真机/模拟器上的事。
 */
class PlatformManifestTest {
    private val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))

    private fun permissions(): Set<String> {
        val nodes = doc.getElementsByTagName("uses-permission")
        return (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("android:name") }.toSet()
    }

    @Test
    fun `权限恰好是七个：麦克风、蓝牙连接、前台服务与它的麦克风类型、通知、音频设置、振动`() {
        assertEquals(
            setOf(
                "android.permission.RECORD_AUDIO",
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_MICROPHONE",
                "android.permission.POST_NOTIFICATIONS",
                // AndroidAudioSystem 录音前把音频模式切到 MODE_IN_COMMUNICATION、结束时恢复：AudioManager.setMode 要求
                // MODIFY_AUDIO_SETTINGS（普通权限，安装即授予）；没有它系统只记一条警告，模式切换被悄悄丢弃。探针清单里有它。
                "android.permission.MODIFY_AUDIO_SETTINGS",
                // 开始 / 停止 / 没能开始的提示靠振动节奏区分（Ticket 17）。普通权限、安装即授予；提示里没有声音（NoSoundIntoBluetoothTest）。
                "android.permission.VIBRATE",
            ),
            permissions(),
        )
    }

    @Test
    fun `没有网络权限，也没有开机启动：不会有任何东西在开机后自己开始录音或联网`() {
        assertFalse(permissions().any { it.endsWith("INTERNET") || it.endsWith("RECEIVE_BOOT_COMPLETED") })
        assertEquals("清单里不应有广播接收器", 0, doc.getElementsByTagName("receiver").length)
    }

    @Test
    fun `前台服务只有一个：麦克风类型，不导出`() {
        val services = doc.getElementsByTagName("service")

        assertEquals(1, services.length)
        val service = services.item(0) as Element
        assertEquals(".service.MicCaptureForegroundService", service.getAttribute("android:name"))
        assertEquals("microphone", service.getAttribute("android:foregroundServiceType"))
        assertEquals("false", service.getAttribute("android:exported"))
    }
}
