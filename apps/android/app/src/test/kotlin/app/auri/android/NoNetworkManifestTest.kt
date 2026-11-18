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
 * 原先的证据是“整个应用的清单里没有 INTERNET”（没有它的进程连 AF_INET 套接字都建不出来）。Ticket 21 让应用能把文字发到局域网网关，
 * 应用必须声明 INTERNET，这条整体证据不再成立。换成两条仍然可以证伪的证据（需求冲突的说明见 execution/acceptance/android-sync/README.md）：
 *  - 这里：INTERNET 只来自 :syncplatform 一个模块（合并清单的来源报告里，声明它的清单只有这一个）——:asr、:capture、:platform、
 *    应用主清单和任何第三方库（含固定的 sherpa-onnx AAR）都不带进网络权限，也没有明文流量与网络安全配置的口子；
 *  - NoNetworkCodeGuardTest：转录、录音、本地库的编译类里一个网络类都没有。
 * 设备上“转录期间没有任何网络字节”的实测见仪器测试 OfflineProofTest（飞行模式 + TrafficStats）。
 *
 * 检查的是**合并之后**的清单与它的来源报告：库与 AAR 自带的清单在合并时也可能带进权限。
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

    /** 合并报告里某个元素的来源行：`ADDED from …` / `MERGED from [:模块] …` / `INJECTED from …`。 */
    private fun sourcesOf(element: String, report: File): List<String> {
        val lines = report.readLines()
        val start = lines.indexOf(element)
        check(start >= 0) { "合并报告里没有 $element：${report.path}" }
        return lines.drop(start + 1).takeWhile { it.startsWith("ADDED from") || it.startsWith("MERGED from") || it.startsWith("INJECTED from") || it.startsWith("\t") }
            .filter { it.startsWith("ADDED from") || it.startsWith("MERGED from") || it.startsWith("INJECTED from") }
    }

    private val mergeReport = File("build/outputs/logs/manifest-merger-debug-report.txt")

    @Test
    fun `合并后的应用清单里 INTERNET 只来自 syncplatform（配对与同步要用，别的地方一个都不许带进来）`() {
        val manifest = mergedAppManifest()
        assertTrue("合并后的清单应当存在：$manifest", manifest.isFile)
        assertTrue("配对与同步需要 INTERNET：合并后的清单里应当有它", internet in permissionsIn(manifest))
        assertTrue("合并来源报告应当存在：${mergeReport.absolutePath}", mergeReport.isFile)

        val sources = sourcesOf("uses-permission#$internet", mergeReport)

        assertTrue("来源报告里应当有 INTERNET 的来源", sources.isNotEmpty())
        assertTrue(sources.toString(), sources.all { "syncplatform" in it })
    }

    @Test
    fun `合并后的应用清单没有放开明文流量，也没有自定义网络安全配置`() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }.newDocumentBuilder().parse(mergedAppManifest())
        val application = doc.getElementsByTagName("application").item(0) as Element

        assertEquals("", application.getAttribute("android:networkSecurityConfig"))
        assertTrue(application.getAttribute("android:usesCleartextTraffic") != "true")
    }

    @Test
    fun `只有 syncplatform 的清单声明 INTERNET：capture、asr、platform、应用主清单都不声明`() {
        for (module in listOf("asr", "platform", "app")) {
            assertFalse(module, internet in permissionsIn(File("../$module/src/main/AndroidManifest.xml")))
        }
        assertTrue(internet in permissionsIn(File("../syncplatform/src/main/AndroidManifest.xml")))
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
