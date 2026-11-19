package app.auri.android.netguard

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 21 的网络卫士（评审 F1/F13 重写）。行为上由 :sync 的 SpkiPinTrustManagerTest / PinnedHttpsClientTest 证明（指纹不对一律拒绝，没有任何旁路）；
 * 这里扫七个生产模块编译出的类文件和源码，证明代码里**没有别的路**：
 *  - 只有登记过的**具体的类**可以碰网络（PinnedHttpsClient、PinnedTls、SpkiPinTrustManager、NsdGatewayDiscovery、AndroidNetworkSignal、
 *    :modelmgmt 下载模型的传输、BrowserLink），不是整个包；:capture、:asr、:platform 与界面壳一个网络类都没有；
 *  - 没有第二套信任规则（TrustManager、SSLContext 只在 tls 的两个类里）、没有“忽略主机名”、没有 WebView、DownloadManager 或别的 HTTP 栈；
 *  - 没有明文 http:// 地址，也没有 :BrowserLink 之外的“查看网址”意图；
 *  - 字节码和源码两层都查：别名躲不过字节码，通配导入躲不过源码层。
 * 内核层面的“根本不能联网”证明在 Ticket 20/21 起不可能再成立（INTERNET 是整个应用一份），这里是它的代替品里**静态**的那一半；
 * 动态的一半是设备上的零字节实测（OfflineProofTest）。
 * 卫士自己是否会“真的拦住”由正对照证明：测试源码里故意写了违规的类（Examples.kt），卫士必须逐个认出来；
 * 同一个合规的类放到不该放的位置也必须被认出来；登记表里不能有多余的项（多余的登记会悄悄放宽范围）。
 */
class NoNetworkCodeGuardTest {
    private val androidDir = File("..")

    private fun allClasses(): List<Triple<String, String, File>> =
        NetworkSurface.modules.flatMap { module ->
            NetworkSurface.productionClasses(module, androidDir).map { (path, file) -> Triple(module, path, file) }
        }

    private fun exampleBytes(example: Class<*>): ByteArray =
        checkNotNull(example.classLoader.getResourceAsStream(example.name.replace('.', '/') + ".class")) { "找不到 ${example.name}" }.use { it.readBytes() }

    private fun violationsOfExample(example: Class<*>, module: String = "app"): List<String> =
        NetworkSurface.violations(module, example.name.replace('.', '/') + ".class", exampleBytes(example))

    // ---- 真实的生产代码 ----

    @Test
    fun `七个生产模块编译出的类里没有任何违反网络规矩的引用`() {
        val classes = allClasses()
        val violations = classes.flatMap { (module, path, file) -> NetworkSurface.violations(module, path, file.readBytes()) }

        assertEquals(violations.joinToString("\n"), emptyList<String>(), violations)
        assertTrue("应当扫到类文件", classes.size > 300)
    }

    @Test
    fun `下载模型的传输也在扫描范围里：modelmgmt 的类被扫到，它的网络类出现在预期的位置（F1）`() {
        val classes = allClasses()

        assertTrue("modelmgmt 没有被扫描", NetworkSurface.modules.contains("modelmgmt"))
        assertTrue(classes.any { it.first == "modelmgmt" && it.second == "app/auri/modelmgmt/UrlConnectionTransport.class" })
    }

    @Test
    fun `扫描真的看到了东西：每个模块都有类，网络类出现在预期的位置`() {
        val classes = allClasses()

        for (module in NetworkSurface.modules) {
            assertTrue("$module 没有扫到任何类（输出目录不对？）", classes.any { it.first == module })
        }
        fun constantsOf(module: String, path: String) =
            NetworkSurface.constants(classes.single { it.first == module && it.second == path }.third.readBytes())
        assertTrue(NetworkSurface.references(constantsOf("sync", "app/auri/sync/tls/SpkiPinTrustManager.class"), "javax/net/ssl/X509ExtendedTrustManager"))
        assertTrue(NetworkSurface.references(constantsOf("sync", "app/auri/sync/tls/PinnedTls.class"), "javax/net/ssl/SSLContext"))
        assertTrue(NetworkSurface.references(constantsOf("sync", "app/auri/sync/http/PinnedHttpsClient\$Connection.class"), "javax/net/ssl/SSLSocket"))
        assertTrue(NetworkSurface.references(constantsOf("syncplatform", "app/auri/syncplatform/network/AndroidNetworkSignal.class"), "android/net/ConnectivityManager"))
        assertTrue(NetworkSurface.references(constantsOf("syncplatform", "app/auri/syncplatform/discovery/NsdGatewayDiscovery.class"), "android/net/nsd/NsdManager"))
        assertTrue(NetworkSurface.references(constantsOf("modelmgmt", "app/auri/modelmgmt/UrlConnectionTransport.class"), "okhttp3/OkHttpClient"))
        assertTrue(NetworkSurface.references(constantsOf("modelmgmt", "app/auri/modelmgmt/UrlConnectionResponse.class"), "java/net/HttpURLConnection"))
        assertTrue(NetworkSurface.references(constantsOf("app", "app/auri/android/BrowserLink.class"), "android.intent.action.VIEW"))
    }

    @Test
    fun `整个应用里唯一的信任管理器实现是 SpkiPinTrustManager，唯一建 SSLContext 的是 PinnedTls`() {
        val classes = allClasses()
        fun referencing(vararg names: String) = classes.filter { (_, _, file) ->
            val constants = NetworkSurface.constants(file.readBytes())
            names.any { NetworkSurface.references(constants, it) }
        }.map { "${it.first}/${it.second}" }.sorted()

        assertEquals(
            listOf("sync/app/auri/sync/tls/SpkiPinTrustManager.class"),
            referencing("javax/net/ssl/X509TrustManager", "javax/net/ssl/X509ExtendedTrustManager"),
        )
        assertEquals(listOf("sync/app/auri/sync/tls/PinnedTls.class"), referencing("javax/net/ssl/SSLContext"))
    }

    @Test
    fun `没有任何生产模块的类引用 HostnameVerifier 或 HttpsURLConnection`() {
        val classes = allClasses()
        val hits = classes.filter { (_, _, file) ->
            val constants = NetworkSurface.constants(file.readBytes())
            NetworkSurface.references(constants, "javax/net/ssl/HostnameVerifier") || NetworkSurface.references(constants, "javax/net/ssl/HttpsURLConnection")
        }.map { "${it.first}/${it.second}" }

        assertEquals(emptyList<String>(), hits)
        assertTrue(classes.isNotEmpty())
    }

    private fun productionSources(): List<Triple<String, String, File>> =
        NetworkSurface.modules.flatMap { module ->
            listOf("main", "debug", "release").map { File(androidDir, "$module/src/$it") }.filter { it.isDirectory }.flatMap { dir ->
                dir.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.map { Triple(module, it.invariantSeparatorsPath, it) }.toList()
            }
        }

    @Test
    fun `源码里同样没有：信任类只在 tls 的两个类，主机名校验、WebView、DownloadManager 与别的 HTTP 栈除登记的传输外任何地方都没有`() {
        val files = productionSources()
        for (module in NetworkSurface.modules) {
            assertTrue("$module 没有扫到任何源文件（目录不对？）", files.any { it.first == module })
        }
        assertTrue("应当扫到全部生产源文件（当前 90 多个）：${files.size}", files.size >= 85)

        val problems = files.flatMap { (module, path, file) -> NetworkSurface.sourceViolations(module, path, file.readText()) }

        assertEquals(problems.joinToString("\n"), emptyList<String>(), problems)
    }

    @Test
    fun `登记表没有多余项：每条登记都有编译出来的类和源文件，而且它真的用到了这条规则里的名字（F13）`() {
        val classes = allClasses()
        val constantsByClass = classes.associate { (module, path, file) -> (module to path) to NetworkSurface.constants(file.readBytes()) }

        for (rule in NetworkSurface.rules) {
            for (entry in rule.allowedIn) {
                val family = classes.filter { entry.matches(it.first, it.second) }
                assertTrue("${rule.id}：登记的 ${entry.module}/${entry.classPath} 在编译产物里找不到任何类（改名了？）", family.isNotEmpty())
                assertTrue(
                    "${rule.id}：登记的 ${entry.module}/${entry.classPath} 没有用到这条规则里的任何名字——多余的登记会悄悄放宽范围，删掉它",
                    family.any { (module, path, _) -> rule.names.any { NetworkSurface.references(constantsByClass.getValue(module to path), it) } },
                )
                assertTrue(
                    "${rule.id}：登记的源文件 ${entry.source} 不存在",
                    File(androidDir, "${entry.module}/src/main/kotlin/${entry.source}").isFile,
                )
            }
        }
    }

    @Test
    fun `引用了网络名字的生产类恰好都在登记的传输里：没有哪个类靠“同一个包”蹭上白名单（F13）`() {
        val classes = allClasses()
        val registered = NetworkSurface.rules.flatMap { it.allowedIn }.distinct()

        val strays = classes.filter { (module, path, file) ->
            val constants = NetworkSurface.constants(file.readBytes())
            val usesNetwork = NetworkSurface.rules.any { rule -> rule.names.any { NetworkSurface.references(constants, it) } }
            usesNetwork && registered.none { it.matches(module, path) }
        }.map { "${it.first}/${it.second}" }

        assertEquals(emptyList<String>(), strays)
    }

    // ---- 正对照：卫士真的认得出违规写法 ----

    @Test
    fun `卫士认得出信任所有证书的实现`() {
        val found = violationsOfExample(TrustAllExample::class.java)

        assertTrue(found.toString(), found.any { "trust 引用 javax/net/ssl/X509TrustManager" in it })
    }

    @Test
    fun `卫士认得出忽略主机名的写法`() {
        val found = violationsOfExample(HostnameBypassExample::class.java)

        assertTrue(found.toString(), found.any { "no-bypass 引用 javax/net/ssl/HostnameVerifier" in it })
        assertTrue(found.toString(), found.any { "no-bypass 引用 javax/net/ssl/HttpsURLConnection" in it })
    }

    @Test
    fun `卫士认得出自己开套接字`() {
        val found = violationsOfExample(RawSocketExample::class.java)

        assertTrue(found.toString(), found.any { "sockets 引用 java/net/Socket" in it })
    }

    @Test
    fun `卫士认得出未登记的类自己开套接字：放在 sync 的 http 包里也一样（F13）`() {
        val bytes = exampleBytes(UnpinnedRawSocketClient::class.java)

        val inHttpPackage = NetworkSurface.violations("sync", "app/auri/sync/http/UnpinnedRawSocketClient.class", bytes)

        assertTrue(inHttpPackage.toString(), inHttpPackage.any { "sockets 引用 java/net/Socket" in it })
    }

    @Test
    fun `登记的是具体的类：同包的兄弟类、同名前缀的类、别的模块里的同名类都不算（F13）`() {
        val real = File(androidDir, "sync/build/classes/kotlin/main/app/auri/sync/http/PinnedHttpsClient\$Connection.class").readBytes()

        assertEquals(emptyList<String>(), NetworkSurface.violations("sync", "app/auri/sync/http/PinnedHttpsClient\$Connection.class", real))
        assertTrue(NetworkSurface.violations("sync", "app/auri/sync/http/PinnedHttpsClientEvil.class", real).any { "sockets" in it })
        assertTrue(NetworkSurface.violations("sync", "app/auri/sync/http/Sibling.class", real).any { "sockets" in it })
        assertTrue(NetworkSurface.violations("syncplatform", "app/auri/sync/http/PinnedHttpsClient\$Connection.class", real).any { "sockets" in it })
    }

    @Test
    fun `卫士认得出 WebView（F1）`() {
        val found = violationsOfExample(WebViewExample::class.java)

        assertTrue(found.toString(), found.any { "webview 引用 android/webkit/" in it })
    }

    @Test
    fun `卫士认得出起了别名的 HttpURLConnection：别名在字节码里还是原来的类（F1）`() {
        val found = violationsOfExample(AliasedHttpUrlConnectionExample::class.java)

        assertTrue(found.toString(), found.any { "引用 java/net/HttpURLConnection" in it })
        assertTrue(found.toString(), found.any { "引用 java/net/URL" in it })
    }

    @Test
    fun `卫士认得出 DownloadManager（F1）`() {
        val found = violationsOfExample(DownloadManagerExample::class.java)

        assertTrue(found.toString(), found.any { "download-manager 引用 android/app/DownloadManager" in it })
    }

    @Test
    fun `卫士认得出查看网址的意图（F1）`() {
        val found = violationsOfExample(ViewIntentExample::class.java)

        assertTrue(found.toString(), found.any { "view-intent 引用 android.intent.action.VIEW" in it })
    }

    @Test
    fun `卫士认得出明文地址`() {
        val found = violationsOfExample(PlainHttpExample::class.java)

        assertTrue(found.toString(), found.any { "cleartext" in it })
    }

    @Test
    fun `卫士认得出直接用系统连接管理`() {
        val found = violationsOfExample(ConnectivityExample::class.java)

        assertTrue(found.toString(), found.any { "sockets 引用 android/net/ConnectivityManager" in it })
    }

    @Test
    fun `卫士认得出另建 SSLContext 与信任管理器数组`() {
        val found = violationsOfExample(SslContextExample::class.java)

        assertTrue(found.toString(), found.any { "trust 引用 javax/net/ssl/SSLContext" in it })
        assertTrue(found.toString(), found.any { "trust 引用 javax/net/ssl/TrustManager" in it })
    }

    @Test
    fun `同一个合规的类放到不该放的位置也被认出：位置才是规矩`() {
        val real = File(androidDir, "sync/build/classes/kotlin/main/app/auri/sync/tls/SpkiPinTrustManager.class").readBytes()

        assertEquals(emptyList<String>(), NetworkSurface.violations("sync", "app/auri/sync/tls/SpkiPinTrustManager.class", real))
        assertTrue(NetworkSurface.violations("app", "app/auri/android/SpkiPinTrustManager.class", real).any { "trust" in it })
        assertTrue(NetworkSurface.violations("sync", "app/auri/sync/gateway/SpkiPinTrustManager.class", real).any { "trust" in it })
        assertTrue(NetworkSurface.violations("syncplatform", "app/auri/sync/tls/SpkiPinTrustManager.class", real).any { "trust" in it })
    }

    @Test
    fun `源码层认得出别名导入、通配导入、WebView、loadUrl、套接字与查看网址的意图（F1）`() {
        fun found(text: String, module: String = "app", path: String = "/x/apps/android/app/src/main/kotlin/app/auri/android/X.kt") =
            NetworkSurface.sourceViolations(module, path, text)

        assertTrue(found("import java.net.HttpURLConnection as Plain\nval c: Plain? = null").any { "http-stack" in it })
        assertTrue(found("import java.net.*\nval s = Socket()").any { "sockets" in it })
        assertTrue(found("import android.net.*\nfun f(m: ConnectivityManager) = Unit").any { "sockets" in it })
        assertTrue(found("import android.webkit.WebView\nfun f(w: WebView) = w.loadUrl(\"x\")").any { "webview" in it })
        assertTrue(found("fun f(w: Any) = w.loadUrl(\"x\")").any { "webview" in it })
        assertTrue(found("fun f() = Intent(Intent.ACTION_VIEW, uri)").any { "view-intent" in it })
        assertTrue(found("val manager = context.getSystemService(DownloadManager::class.java)").any { "download-manager" in it })
        assertTrue(found("val c = URL(address).openConnection()").any { "http-stack" in it })
        assertTrue(found("val r = retrofit2.Retrofit.Builder()").any { "http-stack" in it })
        assertEquals("注释与字符串里的说明文字不算", emptyList<String>(), found("// 用 Socket 和 WebView 的做法不对\nval doc = \"ACTION_VIEW 与 DownloadManager\""))
    }

    @Test
    fun `源码层也按登记的文件放行：登记的传输文件放行，同包的别的文件与别的模块里的同名文件不放行（F13）`() {
        val text = "import java.net.Socket\nval s = Socket()"
        val pinned = "/x/apps/android/sync/src/main/kotlin/app/auri/sync/http/PinnedHttpsClient.kt"

        assertEquals(emptyList<String>(), NetworkSurface.sourceViolations("sync", pinned, text))
        assertTrue(NetworkSurface.sourceViolations("sync", pinned.replace("PinnedHttpsClient", "Sibling"), text).isNotEmpty())
        assertTrue(NetworkSurface.sourceViolations("syncplatform", pinned.replace("/sync/src/", "/syncplatform/src/"), text).isNotEmpty())
        assertTrue(NetworkSurface.sourceViolations("app", pinned, text).isNotEmpty())
    }

    @Test
    fun `类文件解析器读得出常量，读不懂的内容大声失败`() {
        val constants = NetworkSurface.constants(exampleBytes(PlainHttpExample::class.java))

        assertTrue("http://example.invalid/upload" in constants)
        assertTrue("app/auri/android/netguard/PlainHttpExample" in constants)
        val broken = runCatching { NetworkSurface.constants(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)) }
        assertTrue("不是类文件必须报错，不能当成没有引用", broken.isFailure)
    }

    @Test
    fun `源码扫描去掉注释与字符串内容，但不会被同一行的网址藏住后面的词`() {
        val code = SourceText.withoutCommentsAndStrings(
            """
            // TrustManager 只在注释里
            /* HostnameVerifier
               也只在块注释里 */
            val doc = "TrustManager 在字符串里"
            val url = "https://example.invalid/"; val bad = object : X509TrustManager {}
            val raw = ${'"'}${'"'}${'"'}HttpsURLConnection 在原始字符串里${'"'}${'"'}${'"'}
            """.trimIndent(),
        )

        assertTrue(code, "TrustManager 只在注释里" !in code && "HostnameVerifier" !in code && "在字符串里" !in code && "HttpsURLConnection" !in code)
        assertTrue("网址后面同一行的 X509TrustManager 必须还看得见：$code", Regex("\\bX509TrustManager\\b").containsMatchIn(code))
    }

    @Test
    fun `前缀规则按包匹配，不会把同名前缀的别的类当成命中`() {
        assertTrue(NetworkSurface.references(listOf("okhttp3/OkHttpClient"), "okhttp3/"))
        assertTrue(NetworkSurface.references(listOf("(Lokhttp3/Request;)V"), "okhttp3/"))
        assertTrue(!NetworkSurface.references(listOf("java/net/SocketAddressLike"), "java/net/SocketAddress"))
        assertTrue(!NetworkSurface.references(listOf("Ljava/net/SocketAddressLike;"), "java/net/SocketAddress"))
        assertTrue(NetworkSurface.references(listOf("([Ljava/net/Socket;)V"), "java/net/Socket"))
    }
}

/** 源码里去掉注释与字符串内容后再找词，文档里写到 TrustManager、字符串里写 "https://" 都不会误报或藏住后面的词。 */
internal object SourceText {
    fun withoutCommentsAndStrings(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                text.startsWith("\"\"\"", i) -> {
                    val end = text.indexOf("\"\"\"", i + 3).let { if (it < 0) text.length else it + 3 }
                    out.append("\"\"")
                    i = end
                }
                c == '"' -> {
                    i++
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\') i++
                        if (i < text.length && text[i] == '\n') break
                        i++
                    }
                    out.append("\"\"")
                    i++
                }
                text.startsWith("//", i) -> while (i < text.length && text[i] != '\n') i++
                text.startsWith("/*", i) -> {
                    val end = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                    repeat(text.substring(i, end).count { it == '\n' }) { out.append('\n') }
                    i = end
                }
                c == '\'' && i + 2 < text.length && (text[i + 2] == '\'' || text[i + 1] == '\\') -> {
                    i++
                    while (i < text.length && text[i] != '\'') {
                        if (text[i] == '\\') i++
                        i++
                    }
                    out.append("''")
                    i++
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}
