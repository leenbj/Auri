package app.auri.modelmgmt

import java.net.SocketTimeoutException
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 真实的 HttpURLConnection 实现，对着本机假服务器：状态、头、Range、重定向、策略与停住的连接上的读超时。 */
class UrlConnectionTransportTest {
    private val content = ByteArray(100_000) { (it * 31).toByte() }
    private val server = FakeModelServer(content)
    private val transport = UrlConnectionTransport(policy = UrlPolicy.LOOPBACK_CLEARTEXT_FOR_TESTS)

    @After fun stopServer() = server.close()

    @Test
    fun `普通请求拿到状态、校验器与正文，并且要求服务器不要压缩`() {
        transport.get(server.url).use { response ->
            assertEquals(200, response.status)
            assertEquals("\"v1\"", response.header("etag"))
            assertEquals("bytes", response.header("Accept-Ranges"))
            assertEquals(content.size.toString(), response.header("Content-Length"))
            assertTrue(content.contentEquals(response.body.readBytes()))
        }
        // 压缩会让 Content-Length 与字节偏移对不上，续传就不成立
        assertEquals("identity", server.requests.single().acceptEncoding)
    }

    @Test
    fun `Range 与 If-Range 原样发给服务器，得到 206 与 Content-Range`() {
        transport.get(server.url, mapOf("Range" to "bytes=99000-", "If-Range" to "\"v1\"")).use { response ->
            assertEquals(206, response.status)
            assertEquals("bytes 99000-99999/100000", response.header("Content-Range"))
            assertTrue(content.copyOfRange(99_000, 100_000).contentEquals(response.body.readBytes()))
        }
        assertEquals("bytes=99000-", server.requests.single().range)
        assertEquals("\"v1\"", server.requests.single().ifRange)
    }

    @Test
    fun `错误状态不抛异常，由调用方按状态码决定；正文是空的`() {
        server.statusOverride = 503
        transport.get(server.url).use { response ->
            assertEquals(503, response.status)
            assertEquals(0, response.body.readBytes().size)
        }
    }

    @Test
    fun `重定向被跟随，Range 头带到最终地址，路径相对或绝对都行`() {
        server.redirectToPath = "/blob"
        transport.get(server.url, mapOf("Range" to "bytes=10-")).use { response ->
            assertEquals(206, response.status)
            assertTrue(content.copyOfRange(10, content.size).contentEquals(response.body.readBytes()))
        }
        assertEquals(listOf("/archive", "/blob"), server.requests.map { it.path })
        assertEquals(listOf("bytes=10-", "bytes=10-"), server.requests.map { it.range })
    }

    @Test
    fun `重定向最多跟随 5 次，转圈的重定向以失败告终，错误信息里没有地址`() {
        server.redirectToPath = "/archive" // 自己重定向到自己
        val e = assertThrows(TransportException::class.java) { transport.get(server.url) }
        assertEquals(6, server.requests.size) // 第一次 + 5 次重定向
        assertFalse(e.message.orEmpty(), e.message.orEmpty().contains("127.0.0.1"))
        assertFalse(e.message.orEmpty(), e.message.orEmpty().contains(server.port.toString()))
    }

    @Test
    fun `默认策略只认 https：http 地址在发出任何连接之前就被拒绝`() {
        val strict = UrlConnectionTransport() // 生产默认值
        assertThrows(TransportException::class.java) { strict.get(server.url) }
        assertEquals("连接都没有发出", 0, server.requests.size)
    }

    @Test
    fun `重定向到非回环的 http 地址被拒绝，不降级`() {
        // 即使放行了本机回环明文，重定向到别的主机的 http 也不行（用文档保留网段，不会真的连出去）
        server.redirectToPath = "http://203.0.113.5/model"
        assertThrows(TransportException::class.java) { transport.get(server.url) }
        assertEquals(1, server.requests.size)
    }

    @Test
    fun `重定向到 HTTPS 回环地址在连接前被拒绝`() {
        server.redirectToPath = "https://127.0.0.1:${server.port}/private"
        val guarded = UrlConnectionTransport(policy = UrlPolicy.LOOPBACK_CLEARTEXT_FOR_TESTS, connectTimeoutMs = 500, readTimeoutMs = 500)

        assertThrows(TransportException::class.java) { guarded.get(server.url) }
        assertEquals(1, server.requests.size)
    }

    @Test
    fun `私有网段与本地域名不允许作为 HTTPS 地址`() {
        val policy = UrlPolicy.HTTPS_ONLY
        for (url in listOf("https://10.2.3.4/model", "https://127.0.0.1/model", "https://localhost/model", "https://host.local/model", "https://host.internal/model", "https://[fd00::1]/model")) {
            assertFalse(url, policy.allows(url))
        }
    }

    @Test
    fun `假服务器重定向到私有网段时在连接前拒绝`() {
        server.redirectToPath = "https://10.2.3.4/model"
        assertThrows(TransportException::class.java) { transport.get(server.url) }
        assertEquals(1, server.requests.size)
    }

    @Test
    fun `解析结果包含私有地址则拒绝，普通公网地址可连接`() {
        val public = InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
        val private = InetAddress.getByAddress(byteArrayOf(10, 1, 2, 3))
        assertEquals(listOf(public), PublicDns { listOf(public) }.lookup("cdn.example.com"))
        assertThrows(BlockedAddressException::class.java) { PublicDns { listOf(public, private) }.lookup("cdn.example.com") }
    }

    @Test
    fun `假服务器重定向到普通公网名称时继续取得内容`() {
        server.redirectToPath = "https://cdn.example.com/blob"
        val visited = mutableListOf<String>()
        val response = transport.follow(server.url, emptyMap()) { url, headers ->
            visited += url
            val target = if (url.startsWith("https://cdn.example.com/")) server.urlOf("/blob") else url
            val connection = (URL(target).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            val status = connection.responseCode
            object : TransportResponse {
                override val status = status
                override fun header(name: String) = connection.getHeaderField(name)
                override val body get() = connection.inputStream
                override fun abort() = connection.disconnect()
                override fun close() = connection.disconnect()
            }
        }
        response.use { assertTrue(content.contentEquals(it.body.readBytes())) }
        assertEquals(listOf(server.url, "https://cdn.example.com/blob"), visited)
    }

    @Test
    fun `没有 Location 的重定向是错误`() {
        server.statusOverride = 302
        assertThrows(TransportException::class.java) { transport.get(server.url) }
    }

    /**
     * 连接停住时读取不会一直挂着：读超时一到就以 IOException 失败，而不是读到“正常结束”（那样下载器会把半截当成整份）。
     *
     * 这里测不出 abort 的效果，也不该假装测得出：JDK 的 HttpURLConnection 里，进行中的读占着 MeteredStream 的锁，
     * disconnect() 与流的 close() 都要等这把锁，所以在 JVM 上 abort 总是晚于读超时（实测：abort 在 60 秒读超时后才返回）。
     * 下载器因此在另一条线程上调用 abort 并放弃读线程，“暂停立刻生效”由 ResumableDownloaderTest 用同一个真实传输层证明。
     * 这个用例里 abort 照下载器的用法放在另一条线程上，只要求它不死锁、读线程以超时结束。
     */
    @Test
    fun `连接停住时读超时让读取以 IOException 失败，不会一直挂着，也不会读到正常结束`() {
        server.stallAfterBytes = 20_000
        val shortTimeout = UrlConnectionTransport(policy = UrlPolicy.LOOPBACK_CLEARTEXT_FOR_TESTS, readTimeoutMs = 1_500)
        val response = shortTimeout.get(server.url)
        val failure = AtomicReference<Throwable?>()
        val received = AtomicLong()
        val finished = CountDownLatch(1)
        val reader = Thread {
            try {
                val buffer = ByteArray(8192)
                while (true) {
                    val n = response.body.read(buffer)
                    if (n < 0) break
                    received.addAndGet(n.toLong())
                }
            } catch (e: Throwable) {
                failure.set(e)
            } finally {
                finished.countDown()
            }
        }
        reader.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (received.get() < 20_000 && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals("服务器停住之前发的字节都应读到", 20_000L, received.get())

        val aborted = CountDownLatch(1)
        Thread { response.abort(); aborted.countDown() }.apply { isDaemon = true }.start()

        assertTrue("读超时之后读线程必须结束，而不是一直挂着", finished.await(10, TimeUnit.SECONDS))
        assertTrue("abort 不能死锁", aborted.await(10, TimeUnit.SECONDS))
        assertTrue("停住的连接要以读超时失败，不是正常结束：${failure.get()}", failure.get() is SocketTimeoutException)
        assertEquals("读超时之后不会再多出字节", 20_000L, received.get())
        response.close()
    }

    @Test
    fun `abort 与 close 可以任意顺序重复调用：下载器的收尾路径会两个都调`() {
        val response = transport.get(server.url)
        response.abort()
        response.close()
        response.abort()
        response.close()
    }
}
