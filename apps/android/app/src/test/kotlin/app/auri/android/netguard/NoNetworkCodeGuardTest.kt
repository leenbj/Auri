package app.auri.android.netguard

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 21 的网络卫士。行为上由 :sync 的 SpkiPinTrustManagerTest / PinnedHttpsClientTest 证明（指纹不对一律拒绝，没有任何旁路）；
 * 这里扫六个生产模块编译出的类文件和源码，证明代码里**没有别的路**：
 *  - 没有第二套信任规则（TrustManager、SSLContext 只在 :sync 的 tls 包）；
 *  - 没有“忽略主机名”、没有别的 HTTP 栈（HostnameVerifier、HttpsURLConnection、OkHttp、java.net.http……任何位置都没有）；
 *  - 套接字、地址解析、系统连接管理与 NSD 只出现在 :sync 的 http/tls 与 :syncplatform 的 discovery/network；
 *    录音、转录、本地库与界面壳（:capture、:asr、:platform、:app）里一个网络类都没有；
 *  - 没有明文 http:// 地址。
 * 卫士自己是否会“真的拦住”由正对照证明：测试源码里故意写了几个违规的类（Examples.kt），卫士必须逐个认出来；
 * 同一个合规的类放到不该放的位置也必须被认出来。
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
    fun `六个生产模块编译出的类里没有任何违反网络规矩的引用`() {
        val classes = allClasses()
        val violations = classes.flatMap { (module, path, file) -> NetworkSurface.violations(module, path, file.readBytes()) }

        assertEquals(violations.joinToString("\n"), emptyList<String>(), violations)
        assertTrue("应当扫到类文件", classes.size > 300)
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

    @Test
    fun `源码里同样没有：信任类只在 tls 包的源文件，主机名校验与别的 HTTP 栈任何地方都没有`() {
        val sourceDirs = NetworkSurface.modules.flatMap { module ->
            listOf("main", "debug", "release").map { File(androidDir, "$module/src/$it") }
        }.filter { it.isDirectory }
        val files = sourceDirs.flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList()
        }
        for (module in NetworkSurface.modules) {
            assertTrue("$module 没有扫到任何源文件（目录不对？）", files.any { it.invariantSeparatorsPath.contains("/$module/src/") })
        }
        assertTrue("应当扫到全部生产源文件（当前 85 个）：${files.size}", files.size >= 80)

        val trustTokens = Regex("\\b(X509TrustManager|X509ExtendedTrustManager|TrustManager|TrustManagerFactory|SSLContext)\\b")
        val forbiddenTokens = Regex(
            "\\b(HostnameVerifier|HttpsURLConnection|HttpURLConnection|URLConnection|OkHttpClient|trustAll|TRUST_ALL|ALLOW_ALL_HOSTNAME_VERIFIER|setDefaultSSLSocketFactory|setDefaultHostnameVerifier)\\b",
        )
        val problems = files.flatMap { file ->
            val code = SourceText.withoutCommentsAndStrings(file.readText())
            val path = file.invariantSeparatorsPath
            val inTls = path.contains("/sync/src/main/kotlin/app/auri/sync/tls/")
            buildList {
                if (!inTls) trustTokens.findAll(code).forEach { add("$path: 信任类 ${it.value} 只许出现在 :sync 的 tls 包") }
                forbiddenTokens.findAll(code).forEach { add("$path: ${it.value} 任何位置都不允许") }
            }
        }

        assertEquals(problems.joinToString("\n"), emptyList<String>(), problems)
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
