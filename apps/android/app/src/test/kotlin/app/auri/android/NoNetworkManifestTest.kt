package app.auri.android

import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * NFR-008 / Ticket 14 第 4 项：离线转录没有任何网络路径。
 * Android 上没有 INTERNET 权限的进程连 AF_INET 套接字都建不出来（内核拒绝），所以“清单里没有 INTERNET”
 * 是比“没看到流量”更强的证据。设备上的套接字实测见仪器测试 OfflineProofTest。
 *
 * 检查的是**合并之后**的清单：:asr 与固定的 sherpa-onnx AAR 自带的清单在合并时也可能带进权限。
 */
class NoNetworkManifestTest {
    private val internet = "android.permission.INTERNET"

    private fun permissionsIn(manifest: File): List<String> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }.newDocumentBuilder().parse(manifest)
        val tags = listOf("uses-permission", "uses-permission-sdk-23")
        return tags.flatMap { tag ->
            val nodes = doc.getElementsByTagName(tag)
            (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("android:name") }
        }
    }

    private fun mergedAppManifest(): File {
        val resource = "com/android/tools/test_config.properties"
        val stream = javaClass.classLoader!!.getResourceAsStream(resource)
            ?: error("找不到 $resource；:app 需要 unitTests.isIncludeAndroidResources = true")
        val props = Properties().apply { stream.use { load(it) } }
        return File(props.getProperty("android_merged_manifest") ?: error("test_config.properties 里没有 android_merged_manifest"))
    }

    @Test
    fun `合并后的应用清单没有 INTERNET 权限`() {
        val manifest = mergedAppManifest()

        assertTrue("合并后的清单应当存在：$manifest", manifest.isFile)
        assertFalse(permissionsIn(manifest).toString(), internet in permissionsIn(manifest))
    }

    @Test
    fun `asr 库模块自己的清单不声明任何权限`() {
        assertEquals(emptyList<String>(), permissionsIn(File("../asr/src/main/AndroidManifest.xml")))
    }

    @Test
    fun `验收宿主的主清单不声明任何权限`() {
        assertEquals(emptyList<String>(), permissionsIn(File("src/main/AndroidManifest.xml")))
    }
}
