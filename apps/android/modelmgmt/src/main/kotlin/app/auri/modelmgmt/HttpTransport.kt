package app.auri.modelmgmt

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

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
 * 允许访问哪些地址：只认 https，地址里不能带口令，拒绝本地名称和内部地址。信任锚是清单里固定的 SHA-256 而不是主机名，
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
            "https" -> !blockedHost(host)
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

private fun blockedHost(host: String): Boolean {
    val name = host.removeSurrounding("[", "]").trimEnd('.').lowercase()
    if (name in setOf("localhost", "local", "internal") || listOf(".localhost", ".local", ".internal").any(name::endsWith)) return true
    if (':' !in name && !name.all { it.isDigit() || it == '.' }) return false
    return try { unsafeAddress(InetAddress.getByName(name)) } catch (_: IOException) { true }
}

internal fun unsafeAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return true
    val bytes = address.address
    if (address is Inet4Address) {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        return first == 0 || (first == 100 && second in 64..127)
    }
    return (bytes[0].toInt() and 0xfe) == 0xfc
}

internal class BlockedAddressException : UnknownHostException("不允许访问内部地址")

/** 返回给客户端的就是实际连接使用的地址，不再做第二次 DNS 查询。 */
internal class PublicDns(private val resolve: (String) -> List<InetAddress> = { Dns.SYSTEM.lookup(it) }) : Dns {
    override fun lookup(hostname: String): List<InetAddress> = resolve(hostname).also { addresses ->
        if (addresses.isEmpty() || addresses.any(::unsafeAddress)) throw BlockedAddressException()
    }
}

/** 重定向逐跳检查；HTTPS 连接使用经过校验的 DNS 结果。 */
class UrlConnectionTransport(
    private val policy: UrlPolicy = UrlPolicy.HTTPS_ONLY,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val maxRedirects: Int = 5,
) : HttpTransport {
    private val secureClient by lazy {
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .dns(PublicDns())
            .followRedirects(false)
            .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()
    }

    override fun get(url: String, headers: Map<String, String>): TransportResponse = follow(url, headers) { current, requested ->
        if (URI(current).scheme.equals("https", ignoreCase = true)) openSecure(current, requested) else openLoopback(current, requested)
    }

    internal fun follow(url: String, headers: Map<String, String>, open: (String, Map<String, String>) -> TransportResponse): TransportResponse {
        var current = url
        for (hop in 0..maxRedirects) {
            if (!policy.allows(current)) {
                throw TransportException(if (hop == 0) "地址不被允许" else "重定向的目标不被允许")
            }
            val response = open(current, headers)
            if (response.status !in REDIRECT_STATUSES) return response

            val location = response.header("Location")
            response.close()
            if (location.isNullOrBlank()) throw TransportException("重定向没有给出目标地址")
            current = try {
                URI(current).resolve(location).toString()
            } catch (e: IllegalArgumentException) {
                throw TransportException("重定向的目标地址格式不对")
            }
        }
        throw TransportException("重定向次数超过 $maxRedirects 次")
    }

    private fun openSecure(url: String, headers: Map<String, String>): TransportResponse {
        val request = Request.Builder().url(url).header("Accept-Encoding", "identity").apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
        val call = secureClient.newCall(request)
        val response = try {
            call.execute()
        } catch (e: BlockedAddressException) {
            throw TransportException("地址解析到不允许的内部网络")
        }
        return SecureResponse(call, response)
    }

    private fun openLoopback(url: String, headers: Map<String, String>): TransportResponse {
        val connection = (URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
            setRequestProperty("Accept-Encoding", "identity")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        val status = try {
            connection.responseCode
        } catch (e: IOException) {
            connection.disconnect()
            throw e
        }
        return UrlConnectionResponse(connection, status)
    }

    private companion object {
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
    }
}

private class SecureResponse(private val call: Call, private val response: Response) : TransportResponse {
    override val status: Int get() = response.code
    override fun header(name: String): String? = response.header(name)
    override val body: InputStream by lazy {
        if (status in 200..299) response.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0)) else ByteArrayInputStream(ByteArray(0))
    }
    override fun abort() = call.cancel()
    override fun close() = response.close()
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
