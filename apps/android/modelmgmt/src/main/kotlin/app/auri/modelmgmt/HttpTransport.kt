package app.auri.modelmgmt

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 传输层自己判定的失败（地址不被策略允许、重定向有问题），不是网络 IO 失败，所以不继承 IOException：调用方不会把它当成“没有网络”。
 * 消息里永远不带地址：重定向之后的地址常是带签名、带有效期的临时地址，不能出现在日志或界面上。
 */
class TransportException(message: String) : Exception(message)

/** 一次 GET 的响应。调用方负责 [close]。 */
interface TransportResponse : Closeable {
    val status: Int

    /** 响应头，名字不区分大小写；没有就是 null。 */
    fun header(name: String): String?

    /** 只有 2xx 响应有正文；其余状态（包括 416、5xx）的正文是空的，调用方只看状态码。 */
    val body: InputStream

    /**
     * 可以从别的线程调用：让阻塞在 [body] 上的读取以 IOException 结束（暂停、取消时用）。
     * 多快取决于平台：JDK 的 HttpURLConnection 里进行中的读占着流的锁，disconnect() 要等读返回（最长一个读超时）才返回；
     * Android 的实现没有在设备上验证过。所以调用方必须在不怕阻塞的线程上调用它，并且不能依赖它的返回时间或“立刻生效”。
     */
    fun abort()
}

interface HttpTransport {
    /** 阻塞调用；重定向在里面跟随完，返回最终那个不是重定向的响应。 */
    fun get(url: String, headers: Map<String, String> = emptyMap()): TransportResponse
}

/**
 * 允许访问哪些地址：只认 https，地址里不能带口令。信任锚是清单里固定的 SHA-256 而不是主机名，
 * 所以不做主机白名单（GitHub 的下载会重定向到随时会变的存储主机）；明文 http 绝不允许，重定向时也不降级。
 * 只有本模块的单元测试能拿到“本机回环可用明文”的策略（internal）：它的对象只给回环地址放行，别的 http 照样拒绝。
 */
class UrlPolicy private constructor(private val allowLoopbackCleartext: Boolean) {
    fun allows(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (e: java.net.URISyntaxException) {
            return false
        }
        if (uri.userInfo != null) return false
        val host = uri.host
        if (host.isNullOrEmpty()) return false
        return when (uri.scheme?.lowercase()) {
            "https" -> true
            "http" -> allowLoopbackCleartext && host in LOOPBACK_HOSTS
            else -> false
        }
    }

    companion object {
        val HTTPS_ONLY = UrlPolicy(allowLoopbackCleartext = false)
        internal val LOOPBACK_CLEARTEXT_FOR_TESTS = UrlPolicy(allowLoopbackCleartext = true)
        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "[::1]")
    }
}

/** 基于 HttpURLConnection：Android 与 JVM 上都有，不引入第三方 HTTP 库。重定向自己跟随，好逐跳检查策略。 */
class UrlConnectionTransport(
    private val policy: UrlPolicy = UrlPolicy.HTTPS_ONLY,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val maxRedirects: Int = 5,
) : HttpTransport {
    override fun get(url: String, headers: Map<String, String>): TransportResponse {
        var current = url
        for (hop in 0..maxRedirects) {
            if (!policy.allows(current)) {
                throw TransportException(if (hop == 0) "地址不被允许：只接受 https" else "重定向的目标不被允许：只接受 https")
            }
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                useCaches = false
                // 压缩会让 Content-Length 与字节偏移对不上，续传就不成立
                setRequestProperty("Accept-Encoding", "identity")
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            val status = try {
                connection.responseCode
            } catch (e: IOException) {
                connection.disconnect()
                throw e
            }
            if (status !in REDIRECT_STATUSES) return UrlConnectionResponse(connection, status)

            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (location.isNullOrBlank()) throw TransportException("重定向没有给出目标地址")
            current = try {
                URI(current).resolve(location).toString()
            } catch (e: IllegalArgumentException) {
                throw TransportException("重定向的目标地址格式不对")
            }
        }
        throw TransportException("重定向次数超过 $maxRedirects 次")
    }

    private companion object {
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
    }
}

private class UrlConnectionResponse(private val connection: HttpURLConnection, override val status: Int) : TransportResponse {
    private val closed = AtomicBoolean(false)

    override fun header(name: String): String? = connection.getHeaderField(name)

    override val body: InputStream by lazy {
        if (status in 200..299) connection.inputStream else ByteArrayInputStream(ByteArray(0))
    }

    override fun abort() = connection.disconnect()

    override fun close() {
        if (closed.compareAndSet(false, true)) connection.disconnect()
    }
}
