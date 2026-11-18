package app.auri.modelmgmt

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 真实的 HttpURLConnection 实现，对着本机假服务器：状态、头、Range、重定向、策略与“立刻掐断阻塞的读取”。 */
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
    fun `没有 Location 的重定向是错误`() {
        server.statusOverride = 302
        assertThrows(TransportException::class.java) { transport.get(server.url) }
    }

    @Test
    fun `abort 立刻让卡在读取上的线程失败，不必等读超时`() {
        server.stallAfterBytes = 20_000
        val slow = UrlConnectionTransport(policy = UrlPolicy.LOOPBACK_CLEARTEXT_FOR_TESTS, readTimeoutMs = 60_000)
        val response = slow.get(server.url)
        val failure = AtomicReference<Throwable?>()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val reader = Thread {
            try {
                val buffer = ByteArray(8192)
                var total = 0
                started.countDown()
                while (true) {
                    val n = response.body.read(buffer)
                    if (n < 0) break
                    total += n
                }
            } catch (e: Throwable) {
                failure.set(e)
            } finally {
                finished.countDown()
            }
        }
        reader.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        Thread.sleep(300) // 让它读完前 20000 字节、卡在服务器那一头
        assertEquals("读线程应该卡着", 1L, finished.count)

        response.abort()

        assertTrue("abort 之后读线程必须很快结束", finished.await(5, TimeUnit.SECONDS))
        assertTrue(failure.get() is IOException)
        response.close()
        server.releaseStall()
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
