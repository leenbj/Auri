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
