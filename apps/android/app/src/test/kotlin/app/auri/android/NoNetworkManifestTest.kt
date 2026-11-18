package app.auri.android

import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element

/**
 * NFR-008：离线转录没有任何网络路径。检查的是**合并之后**的清单：:asr 与固定的 sherpa-onnx AAR 自带的清单在合并时也可能带进权限。
 *
 * Ticket 14 时这里断言“合并清单里没有 INTERNET”（没有这个权限的进程连 AF_INET 套接字都建不出来，比“没看到流量”更强）。
 * Ticket 20 必须下载模型，下载要 INTERNET，而 Android 的网络权限按应用（UID）授予、不能只给某个模块，所以那条断言**不可能再成立**，
 * 不是被放宽：下载这个功能本身与它矛盾。取而代之的是：INTERNET 恰好出现一次，而且 AGP 的清单合并报告里它的来源**只有 :modelmgmt**
 * ——:asr、:platform、:app、固定的引擎 AAR 以及任何传递依赖都不是它的来源；网络 API 只出现在 :modelmgmt 另有 :platform 的 NetworkIsolationTest 扫描。
 * 报告读不到、读不懂都算失败（证明不了就不通过，不悄悄放行）。设备上的零字节实测见仪器测试 OfflineProofTest。
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

    /**
     * 合并报告里某个权限条目的来源：条目从一行 `uses-permission#<名字>` 开始，后面是若干 `ADDED|MERGED|IMPLIED|INJECTED from [<库>] <路径>`
     * 行（来自应用自己的主清单时没有方括号）和缩进的属性行。返回每个来源行的库名（主清单记为 `<app>`）。
     */
    internal fun sourcesOf(report: List<String>, permission: String): List<String> {
        val header = report.indexOf("uses-permission#$permission")
        if (header < 0) fail("合并报告里找不到 uses-permission#$permission 条目：报告读不懂，或合并清单里根本没有它")
        val from = Regex("^(ADDED|MERGED|IMPLIED|INJECTED) from (?:\\[([^\\]]+)\\] )?")
        return report.drop(header + 1)
            .takeWhile { it.startsWith("\t") || from.containsMatchIn(it) }
            .mapNotNull { line -> from.find(line)?.let { it.groupValues[2].ifEmpty { "<app>" } } }
    }

    private fun mergeReport(): List<String> {
        val file = File("build/outputs/logs/manifest-merger-debug-report.txt")
        assertTrue("清单合并报告应当存在：${file.absolutePath}", file.isFile)
        return file.readLines()
    }

    @Test
    fun `合并后的应用清单申请 INTERNET 恰好一次，来源只有 modelmgmt（下载模型）`() {
        val manifest = mergedAppManifest()
        assertTrue("合并后的清单应当存在：$manifest", manifest.isFile)

        assertEquals("下载需要它：缺了它，下载在系统层就被拒；多了说明有别的来源", 1, permissionsIn(manifest).count { it == internet })
        assertEquals(setOf(":modelmgmt"), sourcesOf(mergeReport(), internet).toSet())
    }

    @Test
    fun `来源解析器自己有效：别的库也声明 INTERNET 时会被抓住，条目缺失时失败而不是放行`() {
        val withOther = listOf(
            "uses-permission#android.permission.INTERNET",
            "ADDED from [:modelmgmt] /x/AndroidManifest.xml:12:5-67",
            "\tandroid:name",
            "\t\tADDED from [:modelmgmt] /x/AndroidManifest.xml:12:22-64",
            "MERGED from [com.example:lib:1.0] /y/AndroidManifest.xml:3:5-67",
            "MERGED from /app/src/main/AndroidManifest.xml:3:5-67",
            "provider#androidx.startup.InitializationProvider",
            "MERGED from [androidx.startup:startup-runtime:1.1.1] /z/AndroidManifest.xml:1:1-2:3",
        )
        assertEquals(setOf(":modelmgmt", "com.example:lib:1.0", "<app>"), sourcesOf(withOther, internet).toSet())

        val without = listOf("uses-permission#android.permission.RECORD_AUDIO", "ADDED from [:platform] /p/AndroidManifest.xml:1:1-2:2")
        try {
            sourcesOf(without, internet)
            fail("没有 INTERNET 条目的报告必须失败")
        } catch (expected: AssertionError) {
            assertTrue(expected.message.orEmpty().contains("找不到"))
        }
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
