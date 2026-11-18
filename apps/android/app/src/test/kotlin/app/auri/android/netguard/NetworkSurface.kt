package app.auri.android.netguard

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File

/**
 * “代码里碰到了网络”的证据：编译后类文件常量池里的类名与类型描述符（不是注释、不是字符串里的说明文字）。
 * 所以文档里写到 TrustManager 不会误报，而真的实现了一个就一定会出现在常量池里。
 *
 * 要守住的是 Ticket 21 的网络规矩：网络只有一条路——:sync 的 PinnedHttpsClient，每一条连接都由 SpkiPinTrustManager 核对网关的公钥指纹；
 * 没有“信任所有证书”、没有“忽略主机名”、没有别的 HTTP 栈；录音、转录、本地库（:capture、:asr、:platform）与界面壳（:app）没有网络类。
 *
 * shortcut: 看不见靠反射、在运行时拼字符串才造出来的类名；这里依赖“没有人这样写”加评审。要防就得做字节码数据流分析，到出现这种需要时再说。
 */
internal object NetworkSurface {
    /** 允许出现的位置：模块 + 类路径前缀（相对类根目录，如 app/auri/sync/tls/）。 */
    data class Where(val module: String, val classPathPrefix: String)

    data class Rule(val id: String, val why: String, val names: List<String>, val allowedIn: List<Where>)

    private val syncHttp = Where("sync", "app/auri/sync/http/")
    private val syncTls = Where("sync", "app/auri/sync/tls/")
    private val nsd = Where("syncplatform", "app/auri/syncplatform/discovery/")
    private val connectivity = Where("syncplatform", "app/auri/syncplatform/network/")

    val rules: List<Rule> = listOf(
        Rule(
            id = "trust",
            why = "信任与证书校验只在 :sync 的 tls 包里（固定公钥指纹）：别处出现 TrustManager / SSLContext 就是在另起一套信任规则",
            names = listOf(
                "javax/net/ssl/X509TrustManager", "javax/net/ssl/X509ExtendedTrustManager", "javax/net/ssl/TrustManager",
                "javax/net/ssl/TrustManagerFactory", "javax/net/ssl/SSLContext", "javax/net/ssl/KeyManager", "javax/net/ssl/KeyManagerFactory",
            ),
            allowedIn = listOf(syncTls),
        ),
        Rule(
            id = "no-bypass",
            why = "没有主机名校验的后门，也没有别的 HTTP 栈：只有 PinnedHttpsClient 一条路，任何位置都不允许",
            names = listOf(
                "javax/net/ssl/HostnameVerifier", "javax/net/ssl/HttpsURLConnection", "java/net/HttpURLConnection", "java/net/URLConnection",
                "java/net/URL", "java/net/http/", "okhttp3/", "okio/", "org/apache/http/", "com/android/okhttp/", "android/net/http/",
            ),
            allowedIn = emptyList(),
        ),
        Rule(
            id = "sockets",
            why = "套接字、地址解析、系统连接管理与 NSD 只出现在 :sync 的 http/tls 包与 :syncplatform 的 discovery/network 包；录音、转录、本地库与界面壳里一个都不能有",
            names = listOf(
                "java/net/Socket", "java/net/ServerSocket", "java/net/DatagramSocket", "java/net/MulticastSocket", "java/net/SocketAddress",
                "java/net/InetSocketAddress", "java/net/InetAddress", "java/net/Inet4Address", "java/net/Inet6Address",
                "java/nio/channels/SocketChannel", "java/nio/channels/ServerSocketChannel", "java/nio/channels/DatagramChannel",
                "javax/net/SocketFactory", "javax/net/ServerSocketFactory", "javax/net/ssl/SSLSocket", "javax/net/ssl/SSLSocketFactory",
                "javax/net/ssl/SSLServerSocket", "javax/net/ssl/SSLEngine",
                "android/net/ConnectivityManager", "android/net/Network", "android/net/NetworkRequest", "android/net/NetworkCapabilities",
                "android/net/LinkProperties", "android/net/nsd/", "android/net/wifi/",
            ),
            allowedIn = listOf(syncHttp, syncTls, nsd, connectivity),
        ),
    )

    /** 任何位置都不能有明文 http:// 地址（网关只走 HTTPS）。 */
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

    /** 类名（或以 / 结尾的包名前缀）是否出现在常量里：作为类常量、或作为类型描述符 L...; 的一部分。 */
    fun references(constants: List<String>, name: String): Boolean = constants.any { constant ->
        if (name.endsWith("/")) constant.startsWith(name) || constant.contains("L$name")
        else constant == name || constant.contains("L$name;")
    }

    fun allowed(rule: Rule, module: String, classPath: String): Boolean =
        rule.allowedIn.any { it.module == module && classPath.startsWith(it.classPathPrefix) }

    /** 这个类（在给定模块与类路径下）违反了哪些规则；空 = 没有。每条形如 `模块/类路径: 规则 引用 名字`。 */
    fun violations(module: String, classPath: String, bytes: ByteArray): List<String> {
        val constants = constants(bytes)
        val found = rules.filterNot { allowed(it, module, classPath) }.flatMap { rule ->
            rule.names.filter { references(constants, it) }.map { "$module/$classPath: ${rule.id} 引用 $it" }
        }
        val cleartext = constants.filter { it.startsWith(CLEARTEXT) }.map { "$module/$classPath: cleartext 明文地址 ${it.take(40)}" }
        return found + cleartext
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

    val modules = listOf("capture", "asr", "platform", "app", "sync", "syncplatform")
    private val pureJvmModules = setOf("capture", "sync")
}
