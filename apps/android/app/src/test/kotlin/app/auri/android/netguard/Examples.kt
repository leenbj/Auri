package app.auri.android.netguard

import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.X509TrustManager

// 这些类故意违反网络规矩，只存在于测试源码里：NoNetworkCodeGuardTest 用它们当“正对照”，
// 证明卫士真的认得出这些写法（卫士只看生产模块的类，这里的类不会被当成生产代码扫到）。

/** 信任所有证书。 */
class TrustAllExample : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** 忽略主机名（全局）。 */
class HostnameBypassExample {
    fun install() {
        HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
    }
}

/** 自己开套接字。 */
class RawSocketExample {
    fun open(): java.net.Socket = java.net.Socket("example.invalid", 80)
}

/** 明文地址。 */
class PlainHttpExample {
    val url: String = "http://example.invalid/upload"
}

/** 直接用系统连接管理。 */
class ConnectivityExample(private val manager: android.net.ConnectivityManager) {
    fun active() = manager.activeNetwork
}

/** 自带 SSLContext 与信任管理器数组。 */
class SslContextExample {
    fun context(trust: Array<javax.net.ssl.TrustManager>): javax.net.ssl.SSLContext =
        javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, trust, null) }
}

/** 未登记的“传输类”：自己开套接字、不经过 PinnedHttpsClient（评审 F13：卫士按具体的类登记，放在 sync 的 http 包里也不行）。 */
class UnpinnedRawSocketClient {
    fun send(host: String, bytes: ByteArray) {
        java.net.Socket(host, 80).use { it.getOutputStream().write(bytes) }
    }
}

/** WebView 加载网址（评审 F1：WebView 是另一套完整的网络栈）。 */
class WebViewExample(private val view: android.webkit.WebView) {
    fun open() = view.loadUrl("https://example.invalid/")
}

/** 起了别名也躲不过：别名在字节码里还是原来的类（评审 F1）。 */
typealias PlainConnection = java.net.HttpURLConnection

class AliasedHttpUrlConnectionExample {
    fun open(url: java.net.URL): PlainConnection = url.openConnection() as PlainConnection
}

/** 系统下载管理器：替应用在后台联网（评审 F1）。 */
class DownloadManagerExample(private val context: android.content.Context) {
    fun manager(): android.app.DownloadManager = context.getSystemService(android.app.DownloadManager::class.java)
}

/** 查看网址的意图：把网址交给别的应用去取（评审 F1）。 */
class ViewIntentExample {
    fun intent() = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://example.invalid/"))
}
