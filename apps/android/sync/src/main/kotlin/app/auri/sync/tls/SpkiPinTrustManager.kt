package app.auri.sync.tls

import java.net.Socket
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509ExtendedTrustManager

/** 对方的公钥与配对时记下的指纹不一致。握手因此中止，请求不会发出。 */
class GatewayKeyMismatchException : CertificateException("gateway public key does not match the pinned fingerprint")

/**
 * 网关身份的唯一裁判：**只有**服务器出示的叶证书的公钥指纹等于配对时记下的指纹才通过（ADR-0018）。
 *
 * - 不核对证书链、不核对主机名、不看有效期：自签证书没有可信的链；局域网 IP 会变，证书的 SAN 跟不上；
 *   续签用同一把密钥，指纹不变。身份就是那把公钥，所以只看公钥。
 * - 只认链里的第一张（叶）证书：握手签名是用叶证书的私钥做的；攻击者把真证书塞在链的后面没有用。
 * - 这是 App 里唯一允许存在的 TrustManager（NoNetworkCodeGuardTest 逐类核对），没有任何“信任全部 / 忽略错误并继续”的路径。
 * - 继承 [X509ExtendedTrustManager]：JSSE 不会再给它套一层“按主机名核对”的包装。
 * - 每次连接新建一个实例（见 [PinnedTls]），[mismatchObserved] 只属于那一次连接。
 */
class SpkiPinTrustManager(private val pin: SpkiPin) : X509ExtendedTrustManager() {
    /** 这次连接里是否因为指纹不符拒绝过对方：握手失败时靠它区分“冒充/换了密钥”与“网络问题”。 */
    @Volatile
    var mismatchObserved: Boolean = false
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = verify(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = verify(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = verify(chain)

    // 我们是客户端，不验证任何“客户端证书”
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = refuseClientCertificates()

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = refuseClientCertificates()

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = refuseClientCertificates()

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun verify(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull()
        if (leaf == null || !pin.matches(leaf)) {
            mismatchObserved = true
            throw GatewayKeyMismatchException()
        }
    }

    private fun refuseClientCertificates(): Nothing = throw CertificateException("this endpoint does not accept client certificates")
}

/** 一次连接专用的 TLS 上下文：全新的 SSLContext（没有任何可复用的会话）+ 全新的 [SpkiPinTrustManager]。 */
class PinnedTls(pin: SpkiPin) {
    val trustManager = SpkiPinTrustManager(pin)

    val socketFactory: SSLSocketFactory =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), SecureRandom()) }.socketFactory
}
