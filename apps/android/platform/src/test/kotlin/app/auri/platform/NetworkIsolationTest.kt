package app.auri.platform

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * 网络只出现在一个地方（Ticket 20）。
 *
 * 背景：Ticket 14 靠“应用根本没有 INTERNET 权限”证明本地转录没有网络路径。下载模型需要联网，而 Android 的网络权限是按应用（UID）授予的，
 * 不能只给某个模块——所以那种“整个应用都不能联网”的系统级证据不再成立。取而代之的是分层证据：
 *   1. 静态：下列网络 API 只出现在 :modelmgmt 的编译产物与源码里，其余所有生产模块里一处都没有（本测试）；
 *   2. 静态：INTERNET 权限只在 :modelmgmt 的清单里声明，合并清单里它的唯一来源也是 :modelmgmt（本测试 + :app 的 NoNetworkManifestTest）；
 *   3. 静态：:modelmgmt 看不见音频、文字、Record：它不依赖 :capture / :platform / :app，对 :asr 只引用清单与校验器那一个包（本测试）；
 *   4. 动态：飞行模式下整条转录照常成功，且该 UID 的收发字节数没有增加（仪器测试 OfflineProofTest，模拟器 E2、真机 E3）；
 *   5. 动态：没有用户操作，下载代码一个字节都不发（:modelmgmt 的 ModelManagerTest）。
 * 以后任何新增联网的模块（比如 Ticket 21 的网关同步）必须在 [networkModules] 里显式登记并说明它自己的隔离理由，而不是悄悄加一个权限。
 */
class NetworkIsolationTest {
    /** 允许出现网络 API 与 INTERNET 权限的模块。 */
    private val networkModules = setOf("modelmgmt")
    private val productionModules = listOf("capture", "asr", "platform", "app", "modelmgmt")

    /** 类文件常量池里出现这些内部名，就是引用了网络 API。java/net/URI、InetAddress 只做解析，不联网，不在其中。 */
    private val networkClassNames = listOf(
        "java/net/URL",
        "java/net/URLConnection",
        "java/net/HttpURLConnection",
        "java/net/Socket",
        "java/net/ServerSocket",
        "java/net/DatagramSocket",
        "java/net/MulticastSocket",
        "java/net/http/",
        "java/nio/channels/SocketChannel",
        "java/nio/channels/ServerSocketChannel",
        "java/nio/channels/DatagramChannel",
        "javax/net/",
        "okhttp3/",
        "android/net/http/",
        "android/webkit/WebView",
    )

    /** 源码里的网络 API 记号（去掉注释之后）；别名导入也逃不过，因为字节码那一层单独扫。 */
    private val networkSourceTokens = Regex(
        "\\b(HttpURLConnection|HttpsURLConnection|URLConnection|ServerSocket|DatagramSocket|SocketChannel|WebView|OkHttpClient|HttpClient)\\b" +
            "|\\bjava\\.net\\.(URL|Socket)\\b|\\bokhttp3\\b|\\bandroid\\.net\\.http\\b",
    )

    private fun moduleDir(module: String) = File("../$module")

    private fun classRoots(module: String): List<File> {
        val build = File(moduleDir(module), "build")
        val roots = if (module == "capture") {
            listOf(File(build, "classes/kotlin/main"), File(build, "classes/java/main"))
        } else {
            listOf(File(build, "tmp/kotlin-classes/debug"), File(build, "intermediates/javac/debug/compileDebugJavaWithJavac/classes"))
        }
        return roots.filter { it.isDirectory }
    }

    private fun classFiles(module: String): List<File> =
        classRoots(module).flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "class" }.toList() }

    private fun referencedNetworkApis(bytes: ByteArray): List<String> {
        val constants = bytes.toString(Charsets.ISO_8859_1)
        return networkClassNames.filter { it in constants }
    }

    private fun code(text: String): String = text
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private fun sourceFiles(module: String): List<File> =
        File(moduleDir(module), "src/main").walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList()

    private fun permissionsIn(manifest: File): List<String> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }.newDocumentBuilder().parse(manifest)
        return listOf("uses-permission", "uses-permission-sdk-23").flatMap { tag ->
            val nodes = doc.getElementsByTagName(tag)
            (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("android:name") }
        }
    }

    @Test
    fun `网络 API 只出现在登记过的模块里：其余模块的编译产物没有一处引用`() {
        for (module in productionModules) {
            assertTrue("$module 的编译产物找不到，没法扫描", classFiles(module).isNotEmpty())
        }
        val offenders = (productionModules - networkModules).flatMap { module ->
            classFiles(module).mapNotNull { file ->
                referencedNetworkApis(file.readBytes()).takeIf { it.isNotEmpty() }?.let { "$module: ${file.name} -> $it" }
            }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `扫描器本身有效：登记过的模块里确实扫得到网络引用，一个故意引用网络的类会被抓住`() {
        val inNetworkModules = networkModules.flatMap { classFiles(it) }.flatMap { referencedNetworkApis(it.readBytes()) }.toSet()
        assertTrue("下载传输必须被扫到，否则这个测试什么也证明不了：$inNetworkModules", "java/net/HttpURLConnection" in inNetworkModules)

        val example = checkNotNull(javaClass.classLoader?.getResourceAsStream("app/auri/platform/ExampleNetworkUser.class")).use { it.readBytes() }
        assertTrue("故意写的网络调用必须被抓住", referencedNetworkApis(example).isNotEmpty())
    }

    @Test
    fun `源码里除登记过的模块外没有网络 API 的记号`() {
        val offenders = (productionModules - networkModules).flatMap { module ->
            sourceFiles(module).filter { networkSourceTokens.containsMatchIn(code(it.readText())) }.map { "$module/${it.name}" }
        }
        assertEquals(emptyList<String>(), offenders)
        assertTrue("应当扫到 modelmgmt 自己的网络代码", networkModules.flatMap { sourceFiles(it) }.any { networkSourceTokens.containsMatchIn(code(it.readText())) })
    }

    @Test
    fun `INTERNET 权限只在登记过的模块自己的清单里声明`() {
        val declaring = productionModules.filter { module ->
            val manifest = File(moduleDir(module), "src/main/AndroidManifest.xml")
            manifest.isFile && "android.permission.INTERNET" in permissionsIn(manifest)
        }.toSet()
        assertEquals(networkModules, declaring)
    }

    @Test
    fun `modelmgmt 看不见音频文字与 Record：不依赖 capture platform app，对 asr 只用清单那个包`() {
        val build = File(moduleDir("modelmgmt"), "build.gradle.kts").readText()
        val dependencies = Regex("project\\(\":(\\w+)\"\\)").findAll(code(build)).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("asr"), dependencies)

        val forbidden = listOf("app/auri/capture/", "app/auri/platform/", "app/auri/android/", "app/auri/asr/engine/")
        val allowedAsr = "app/auri/asr/manifest/"
        val offenders = classFiles("modelmgmt").flatMap { file ->
            val constants = file.readBytes().toString(Charsets.ISO_8859_1)
            val asrReferences = Regex("app/auri/asr/[A-Za-z0-9_/]+").findAll(constants).map { it.value }.filterNot { it.startsWith(allowedAsr) }.toList()
            (forbidden.filter { it in constants } + asrReferences).map { "${file.name}: $it" }
        }
        assertEquals(emptyList<String>(), offenders)
    }
}
