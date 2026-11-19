package app.auri.android.netguard

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File

/**
 * “代码里碰到了网络”的证据：编译后类文件常量池里的类名与类型描述符（不是注释、不是字符串里的说明文字），再加上源码里的同一批词。
 * 所以文档里写到 TrustManager 不会误报，而真的实现了一个就一定会出现在常量池里；起别名（import … as …、typealias）改不了字节码里的类名，
 * 通配导入（import java.net.*）在源码层也有专门的规则。两层各守一道：字节码防别名，源码防“还没编译进去”的写法。
 *
 * 要守住的是网络规矩（评审 F1/F13，**取代**“包里没有 INTERNET”这条内核层面的证明——那条在 Ticket 20/21 起不可能再成立）：
 * 整个应用里**只有登记过的具体的类**可以碰网络，按规则分别登记，其余所有生产模块（含 :capture、:asr、:platform 的音频与转录部分、应用壳 :app）
 * 一个网络类都不能引用。登记的是类，不是包：放在 :sync 的 http 包里的另一个类、同名前缀的类、别的模块里的同名类都不算。
 *  - :sync 的 PinnedHttpsClient（每条连接由 SpkiPinTrustManager 核对网关的公钥指纹）、PinnedTls、SpkiPinTrustManager；
 *  - :syncplatform 的 NsdGatewayDiscovery（mDNS 发现）与 AndroidNetworkSignal（系统网络回调）；
 *  - :modelmgmt 下载模型的传输（HttpTransport.kt 里的那几个类）；
 *  - :app 的 BrowserLink：把网址交给系统浏览器，本应用自己不联网取它。
 * WebView、DownloadManager、别的 HTTP 栈（Retrofit、Ktor、Apache HttpClient……）任何位置都没有；信任类只在 tls 的两个类里；
 * 没有“信任所有证书”、没有“忽略主机名”；没有明文 http:// 地址。
 *
 * 看不见的东西，要说清楚：
 *  - 靠反射、在运行时拼字符串才造出来的类名；这里依赖“没有人这样写”加评审。要防就得做字节码数据流分析，到出现这种需要时再说。
 *  - 原生代码：固定版本的 sherpa-onnx AAR 里的 .so 能不能自己开套接字，字节码与源码扫描都看不到。那一层只能靠设备上实测零字节
 *    （仪器测试 OfflineProofTest，覆盖产品里的各个转录入口）和 AAR 的固定哈希（EnginePinTest）。
 *  - 第三方库：OkHttp（:modelmgmt 的传输）自己的内部不扫描，只管我们的类怎么引用它。
 */
internal object NetworkSurface {
    /**
     * 一个登记过的传输类：模块 + 类路径（相对类根目录、不含 `.class`；外部类与它的 `$` 内部类、匿名类、lambda 一起算）。
     * [source] 是它所在的源文件（相对 `src/main/kotlin`）：源码层按文件登记，同一个文件里的类不好分开。
     */
    data class Transport(val module: String, val classPath: String, val source: String) {
        fun matches(module: String, classFile: String): Boolean =
            this.module == module && (classFile == "$classPath.class" || classFile.startsWith("$classPath\$"))
    }

    /** [names]：字节码里的类名（以 / 结尾的是包前缀）或常量；[source]：源码里的同一批词（别名、通配导入也算）。 */
    data class Rule(val id: String, val why: String, val names: List<String>, val source: Regex, val allowedIn: List<Transport>)

    private fun sync(path: String) = Transport("sync", "app/auri/sync/$path", "app/auri/sync/$path.kt")
    private fun syncplatform(path: String) = Transport("syncplatform", "app/auri/syncplatform/$path", "app/auri/syncplatform/$path.kt")

    val pinnedClient = sync("http/PinnedHttpsClient")
    val pinnedTls = Transport("sync", "app/auri/sync/tls/PinnedTls", "app/auri/sync/tls/SpkiPinTrustManager.kt") // 与 SpkiPinTrustManager 在同一个源文件里
    val spkiTrustManager = sync("tls/SpkiPinTrustManager")
    val nsdDiscovery = syncplatform("discovery/NsdGatewayDiscovery")
    val networkSignal = syncplatform("network/AndroidNetworkSignal")
    val browserLink = Transport("app", "app/auri/android/BrowserLink", "app/auri/android/BrowserLink.kt")

    /** :modelmgmt 下载模型的传输：同一个源文件 HttpTransport.kt 里的几个类，每个类只登记到它真的用到的那几条规则。 */
    private fun download(name: String) = Transport("modelmgmt", "app/auri/modelmgmt/$name", "app/auri/modelmgmt/HttpTransport.kt")

    private val downloadAddressing = listOf(download("HttpTransportKt"), download("PublicDns"))
    private val downloadHttp = listOf(download("PublicDns"), download("SecureResponse"), download("UrlConnectionResponse"), download("UrlConnectionTransport"))

    val rules: List<Rule> = listOf(
        Rule(
            id = "trust",
            why = "信任与证书校验只在 :sync 的 PinnedTls 与 SpkiPinTrustManager（固定公钥指纹）：别处出现 TrustManager / SSLContext 就是在另起一套信任规则",
            names = listOf(
                "javax/net/ssl/X509TrustManager", "javax/net/ssl/X509ExtendedTrustManager", "javax/net/ssl/TrustManager",
                "javax/net/ssl/TrustManagerFactory", "javax/net/ssl/SSLContext", "javax/net/ssl/KeyManager", "javax/net/ssl/KeyManagerFactory",
            ),
            source = Regex("\\b(X509TrustManager|X509ExtendedTrustManager|TrustManager|TrustManagerFactory|SSLContext|KeyManager|KeyManagerFactory)\\b"),
            allowedIn = listOf(pinnedTls, spkiTrustManager),
        ),
        Rule(
            id = "no-bypass",
            why = "没有主机名校验的后门、没有“信任所有证书”的开关：任何位置都不允许",
            names = listOf("javax/net/ssl/HostnameVerifier", "javax/net/ssl/HttpsURLConnection"),
            source = Regex(
                "\\b(HostnameVerifier|HttpsURLConnection|trustAll|TRUST_ALL|ALLOW_ALL_HOSTNAME_VERIFIER|setDefaultSSLSocketFactory|setDefaultHostnameVerifier)\\b",
            ),
            allowedIn = emptyList(),
        ),
        Rule(
            id = "http-stack",
            why = "HTTP 栈只有两个：:sync 的 PinnedHttpsClient（自己写在套接字上，不用下面这些）和 :modelmgmt 下载模型用的 OkHttp 与 URLConnection；别处一个都不许",
            names = listOf(
                "java/net/URL", "java/net/URLConnection", "java/net/HttpURLConnection", "java/net/http/", "okhttp3/", "okio/", "retrofit2/",
                "io/ktor/", "org/apache/http/", "com/squareup/", "com/android/okhttp/", "android/net/http/", "org/chromium/net/",
            ),
            source = Regex(
                "\\b(HttpURLConnection|URLConnection|OkHttpClient|HttpClient|Retrofit|HttpUrl)\\b|\\bjava\\.net\\.URL\\b|\\bjava\\.net\\.http\\b" +
                    "|\\b(okhttp3|okio|retrofit2|com\\.squareup|org\\.apache\\.http|org\\.chromium\\.net)\\b|\\bio\\.ktor\\b|\\bandroid\\.net\\.http\\b|\\.openConnection\\(",
            ),
            allowedIn = downloadHttp,
        ),
        Rule(
            id = "sockets",
            why = "套接字、地址解析、系统连接管理与 NSD 只出现在登记过的传输类里；录音、转录、本地库与界面壳里一个都不能有",
            names = listOf(
                "java/net/Socket", "java/net/ServerSocket", "java/net/DatagramSocket", "java/net/MulticastSocket", "java/net/SocketAddress",
                "java/net/InetSocketAddress", "java/net/InetAddress", "java/net/Inet4Address", "java/net/Inet6Address",
                "java/nio/channels/SocketChannel", "java/nio/channels/ServerSocketChannel", "java/nio/channels/DatagramChannel",
                "javax/net/SocketFactory", "javax/net/ServerSocketFactory", "javax/net/ssl/SSLSocket", "javax/net/ssl/SSLSocketFactory",
                "javax/net/ssl/SSLServerSocket", "javax/net/ssl/SSLEngine",
                "android/net/ConnectivityManager", "android/net/Network", "android/net/NetworkRequest", "android/net/NetworkCapabilities",
                "android/net/LinkProperties", "android/net/nsd/", "android/net/wifi/",
            ),
            source = Regex(
                "\\b(Socket|ServerSocket|DatagramSocket|MulticastSocket|SocketChannel|ServerSocketChannel|DatagramChannel|SocketFactory|ServerSocketFactory" +
                    "|SSLSocket|SSLSocketFactory|SSLServerSocket|SSLEngine|InetSocketAddress|InetAddress|Inet4Address|Inet6Address" +
                    "|ConnectivityManager|NsdManager|WifiManager|NetworkRequest|NetworkCapabilities|LinkProperties)\\b" +
                    "|\\b(java\\.net|javax\\.net|javax\\.net\\.ssl|android\\.net)\\.\\*|\\bandroid\\.net\\.(nsd|wifi)\\b",
            ),
            allowedIn = listOf(pinnedClient, pinnedTls, spkiTrustManager, nsdDiscovery, networkSignal) + downloadAddressing,
        ),
        Rule(
            id = "webview",
            why = "WebView 是另一套完整的网络栈（自带缓存、Cookie、脚本）：本应用的任何位置都不用它",
            names = listOf("android/webkit/"),
            source = Regex("\\b(WebView|WebViewClient|WebChromeClient|loadUrl)\\b|\\bandroid\\.webkit\\b"),
            allowedIn = emptyList(),
        ),
        Rule(
            id = "download-manager",
            why = "系统下载管理器替应用在后台联网：模型下载走 :modelmgmt 自己的可暂停、可校验的下载器，不用它",
            names = listOf("android/app/DownloadManager"),
            source = Regex("\\bDownloadManager\\b"),
            allowedIn = emptyList(),
        ),
        Rule(
            id = "view-intent",
            why = "“查看网址”的意图把网址交给别的应用去取；整个应用只有 BrowserLink 一个类可以发它（许可全文交给系统浏览器看）",
            // Intent.ACTION_VIEW 是编译期常量，编译后就是字符串 android.intent.action.VIEW 留在常量池里
            names = listOf("android.intent.action.VIEW"),
            source = Regex("\\bACTION_VIEW\\b"),
            allowedIn = listOf(browserLink),
        ),
    )

    /** 任何位置都不能有明文 http:// 地址（网关与模型下载都只走 HTTPS）。 */
    private const val CLEARTEXT = "http://"

    /** 一个类文件里的全部 UTF8 常量（类名、描述符、字符串常量、方法名……）。 */
    fun constants(bytes: ByteArray): List<String> {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == 0xCAFEBABE.toInt()) { "不是类文件" }
        input.readUnsignedShort()
        input.readUnsignedShort()
        val count = input.readUnsignedShort()
        val out = ArrayList<String>()
        var index = 1
        while (index < count) {
            when (val tag = input.readUnsignedByte()) {
                1 -> out += input.readUTF()
                3, 4 -> input.skipBytes(4)
                5, 6 -> { input.skipBytes(8); index++ } // long / double 占两个槽位
                7, 8, 16, 19, 20 -> input.skipBytes(2)
                9, 10, 11, 12, 17, 18 -> input.skipBytes(4)
                15 -> input.skipBytes(3)
                else -> error("未知的常量池标签 $tag")
            }
            index++
        }
        return out
    }

    /** 类名（或以 / 结尾的包名前缀）是否出现在常量里：作为类常量、或作为类型描述符 L...; 的一部分；不带 / 的常量（如意图动作）要整个相等。 */
    fun references(constants: List<String>, name: String): Boolean = constants.any { constant ->
        if (name.endsWith("/")) constant.startsWith(name) || constant.contains("L$name")
        else constant == name || constant.contains("L$name;")
    }

    fun allowed(rule: Rule, module: String, classFile: String): Boolean = rule.allowedIn.any { it.matches(module, classFile) }

    /** 这个类（在给定模块与类路径下）违反了哪些规则；空 = 没有。每条形如 `模块/类路径: 规则 引用 名字`。 */
    fun violations(module: String, classFile: String, bytes: ByteArray): List<String> {
        val constants = constants(bytes)
        val found = rules.filterNot { allowed(it, module, classFile) }.flatMap { rule ->
            rule.names.filter { references(constants, it) }.map { "$module/$classFile: ${rule.id} 引用 $it" }
        }
        val cleartext = constants.filter { it.startsWith(CLEARTEXT) }.map { "$module/$classFile: cleartext 明文地址 ${it.take(40)}" }
        return found + cleartext
    }

    private fun allowedSource(rule: Rule, module: String, path: String): Boolean =
        rule.allowedIn.any { it.module == module && path.endsWith("/$module/src/main/kotlin/${it.source}") }

    /** 这份源码（给定模块与路径，路径用 / 分隔）违反了哪些规则；注释与字符串里的字不算，import 行与别名算。 */
    fun sourceViolations(module: String, path: String, text: String): List<String> {
        val code = SourceText.withoutCommentsAndStrings(text)
        return rules.filterNot { allowedSource(it, module, path) }.flatMap { rule ->
            rule.source.findAll(code).map { "$path: ${rule.id} 源码里出现 ${it.value}" }.toList()
        }
    }

    /** 生产模块里编译出的类：(类路径, 文件)。纯 JVM 模块与 Android 库的输出位置不同（同 NoPhoneMicPathTest）。 */
    fun productionClasses(module: String, androidDir: File = File("..")): List<Pair<String, File>> {
        val build = File(androidDir, "$module/build")
        val roots = if (module in pureJvmModules) {
            listOf(File(build, "classes/kotlin/main"), File(build, "classes/java/main"))
        } else {
            listOf(File(build, "tmp/kotlin-classes/debug"), File(build, "intermediates/javac/debug/compileDebugJavaWithJavac/classes"))
        }
        return roots.filter { it.isDirectory }.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "class" }.map { it.relativeTo(root).invariantSeparatorsPath to it }.toList()
        }
    }

    val modules = listOf("capture", "asr", "platform", "app", "modelmgmt", "sync", "syncplatform")
    private val pureJvmModules = setOf("capture", "sync")
}
