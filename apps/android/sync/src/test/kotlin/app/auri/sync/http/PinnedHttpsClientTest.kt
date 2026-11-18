package app.auri.sync.http

import app.auri.sync.pairing.GatewayAddress
import app.auri.sync.testing.PlainTcpServer
import app.auri.sync.testing.ServerAction
import app.auri.sync.testing.TestTlsServer
import app.auri.sync.testing.TlsFixtures
import java.net.ServerSocket
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Ticket 21 第 2 项：每一次连接都要核对网关公钥指纹，不符就拒绝，而且不存在“忽略并继续”的路径。
 * 这里全部是真 TLS 握手（JDK 的 SSLServerSocket + 真实证书），指纹不符时服务器那一侧是否收到了请求、令牌有没有发出去，
 * 都从服务器的记录里看，不靠客户端自己的说法。
 */
class PinnedHttpsClientTest {
    private val closeables = mutableListOf<AutoCloseable>()
    private val okJson = ServerAction.Respond(200, """{"status":"ok"}""".toByteArray())

    @After
    fun tearDown() = closeables.forEach { runCatching { it.close() } }

    private fun serve(identity: TlsFixtures.Identity, port: Int = 0, script: (app.auri.sync.testing.ReceivedRequest) -> ServerAction = { okJson }) =
        TestTlsServer(identity, port, script).also { closeables += it }

    private fun address(server: TestTlsServer) = GatewayAddress("127.0.0.1", server.port)

    private fun client(identity: TlsFixtures.Identity, timeouts: Timeouts = Timeouts(connectMs = 2_000, ioMs = 3_000, totalMs = 10_000), maxResponse: Int = 1 shl 20) =
        PinnedHttpsClient(identity.pin, timeouts, maxResponse)

    private fun get(client: HttpExchange, server: TestTlsServer, path: String = "/health") =
        runBlocking { client.execute(address(server), HttpRequest("GET", path)) }

    private inline fun <reified F : TransportFailure> failureOf(block: () -> Unit): F {
        try {
            block()
        } catch (e: TransportFailure) {
            assertTrue("期望 ${F::class.simpleName}，实际 $e", e is F)
            return e as F
        }
        fail("应当抛出 ${F::class.simpleName}")
        error("unreachable")
    }

    @Test
    fun `指纹相符：请求原样送到，响应原样回来`() {
        val server = serve(TlsFixtures.gatewayA) { ServerAction.Respond(200, """{"echo":"好"}""".toByteArray()) }
        val body = """{"text":"你好，世界"}""".toByteArray()

        val reply = runBlocking {
            client(TlsFixtures.gatewayA).execute(
                address(server),
                HttpRequest("PUT", "/v1/records/x/revisions/1", mapOf("Authorization" to "Bearer secret-token-value", "Content-Type" to "application/json"), body),
            )
        }

        assertEquals(200, reply.status)
        assertEquals("""{"echo":"好"}""", reply.body.toString(Charsets.UTF_8))
        val seen = server.received.single()
        assertEquals("PUT", seen.method)
        assertEquals("/v1/records/x/revisions/1", seen.path)
        assertArrayEquals(body, seen.body)
        assertEquals("Bearer secret-token-value", seen.header("Authorization"))
        assertEquals(body.size.toString(), seen.header("Content-Length"))
        assertEquals("close", seen.header("Connection")?.lowercase())
        assertEquals("127.0.0.1:${server.port}", seen.header("Host"))
    }

    @Test
    fun `指纹不符：拒绝连接，而且服务器一个请求都没收到，令牌根本没有发出去`() {
        val impostor = serve(TlsFixtures.gatewayB)

        val failure = runBlocking {
            runCatching {
                client(TlsFixtures.gatewayA).execute(
                    address(impostor),
                    HttpRequest("PUT", "/v1/records/x/revisions/1", mapOf("Authorization" to "Bearer secret-token-value"), "{}".toByteArray()),
                )
            }.exceptionOrNull()
        }

        assertTrue("应当是 KeyMismatch，实际 $failure", failure is TransportFailure.KeyMismatch)
        Thread.sleep(300)
        assertTrue("冒充的服务器不该收到任何请求：${impostor.received}", impostor.received.isEmpty())
        assertEquals("握手不该完成", 0, impostor.completedHandshakes.get())
    }

    @Test
    fun `每次连接都核对：同一个地址上换了密钥的服务器，下一次请求就被拒绝`() {
        val genuine = serve(TlsFixtures.gatewayA)
        val port = genuine.port
        val pinned = client(TlsFixtures.gatewayA)

        assertEquals(200, runBlocking { pinned.execute(GatewayAddress("127.0.0.1", port), HttpRequest("GET", "/health")) }.status)
        genuine.close()
        val impostor = serve(TlsFixtures.gatewayB, port)

        failureOf<TransportFailure.KeyMismatch> { runBlocking { pinned.execute(GatewayAddress("127.0.0.1", port), HttpRequest("GET", "/health")) } }
        Thread.sleep(200)
        assertTrue(impostor.received.isEmpty())
    }

    @Test
    fun `没有会话复用：三次请求是三次互相独立的完整握手`() {
        val server = serve(TlsFixtures.gatewayA)
        val pinned = client(TlsFixtures.gatewayA)

        repeat(3) { runBlocking { pinned.execute(address(server), HttpRequest("GET", "/health")) } }

        assertEquals(3, server.completedHandshakes.get())
        // 复用会话的握手，服务器端看到的会话创建时间是第一次的；三次都是新握手，创建时间两两不同
        assertEquals("会话创建时间应当各不相同（否则就是复用了会话）", 3, server.sessionCreationTimes.distinct().size)
    }

    @Test
    fun `证书里没有任何 SAN 也照样连得上：身份只看公钥指纹，不看主机名或 IP`() {
        val server = serve(TlsFixtures.gatewayWithoutSan)

        assertEquals(200, get(client(TlsFixtures.gatewayWithoutSan), server).status)
        // 换一个主机名连同一台服务器（localhost 也是回环）：照样通过，因为我们不做主机名核对
        val byName = runBlocking { client(TlsFixtures.gatewayWithoutSan).execute(GatewayAddress("localhost", server.port), HttpRequest("GET", "/health")) }
        assertEquals(200, byName.status)
    }

    @Test
    fun `证书过期但公钥没变也接受，续签（同一把密钥换新证书）后指纹不变`() {
        val expired = serve(TlsFixtures.gatewayExpired)
        assertEquals(200, get(client(TlsFixtures.gatewayExpired), expired).status)

        val renewed = TlsFixtures.renewed(TlsFixtures.gatewayA)
        assertNotEquals(TlsFixtures.gatewayA.certificate, renewed.certificate)
        assertEquals("续签不应改变公钥指纹", TlsFixtures.gatewayA.pin, renewed.pin)
        val afterRenewal = serve(renewed)
        // 手机里记的还是续签前的指纹
        assertEquals(200, get(client(TlsFixtures.gatewayA), afterRenewal).status)
    }

    @Test
    fun `对方不说 TLS：握手失败，按不可达处理，不能误报成公钥不符`() {
        PlainTcpServer().also { closeables += it }.let { plain ->
            failureOf<TransportFailure.Unreachable> {
                runBlocking { client(TlsFixtures.gatewayA).execute(GatewayAddress("127.0.0.1", plain.port), HttpRequest("GET", "/health")) }
            }
        }
    }

    @Test
    fun `端口没人监听：不可达`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        failureOf<TransportFailure.Unreachable> {
            runBlocking { client(TlsFixtures.gatewayA).execute(GatewayAddress("127.0.0.1", closedPort), HttpRequest("GET", "/health")) }
        }
    }

    @Test
    fun `请求已经发出但响应迟迟不来：读超时，结果不确定`() {
        val server = serve(TlsFixtures.gatewayA) { ServerAction.Hang }
        val started = System.nanoTime()

        failureOf<TransportFailure.Interrupted> {
            runBlocking { client(TlsFixtures.gatewayA, Timeouts(connectMs = 2_000, ioMs = 400, totalMs = 5_000)).execute(address(server), HttpRequest("GET", "/health")) }
        }

        assertTrue("超时应当在 ioMs 附近发生", (System.nanoTime() - started) / 1_000_000 in 300..3_000)
        assertEquals("请求确实送到了服务器", 1, server.received.size)
    }

    @Test
    fun `请求已经发出但连接被断开：结果不确定`() {
        val server = serve(TlsFixtures.gatewayA) { ServerAction.CloseWithoutResponse }
        failureOf<TransportFailure.Interrupted> { get(client(TlsFixtures.gatewayA), server) }
        assertEquals(1, server.received.size)
    }

    @Test
    fun `响应被截断、畸形、超过上限：都是结果不确定`() {
        val truncated = serve(TlsFixtures.gatewayA) { ServerAction.Truncated(100, "short".toByteArray()) }
        failureOf<TransportFailure.Interrupted> { get(client(TlsFixtures.gatewayA), truncated) }

        val garbage = serve(TlsFixtures.gatewayA) { ServerAction.Raw("this is not http\r\n\r\n".toByteArray()) }
        failureOf<TransportFailure.Interrupted> { get(client(TlsFixtures.gatewayA), garbage) }

        val big = serve(TlsFixtures.gatewayA) { ServerAction.Respond(200, ByteArray(4096) { 'a'.code.toByte() }) }
        failureOf<TransportFailure.Interrupted> { get(client(TlsFixtures.gatewayA, maxResponse = 1024), big) }
    }

    @Test
    fun `chunked 与靠关闭连接结束的响应都能完整读出`() {
        val text = """{"items":[1,2,3,4,5,6,7,8,9,10],"note":"分块传输"}"""
        val chunked = serve(TlsFixtures.gatewayA) { ServerAction.Respond(200, text.toByteArray(), chunked = true) }
        assertEquals(text, get(client(TlsFixtures.gatewayA), chunked).body.toString(Charsets.UTF_8))

        val delimited = serve(TlsFixtures.gatewayA) { ServerAction.Respond(200, text.toByteArray(), closeDelimited = true) }
        assertEquals(text, get(client(TlsFixtures.gatewayA), delimited).body.toString(Charsets.UTF_8))
    }

    @Test
    fun `4xx 与 5xx 是正常返回的响应，不是传输失败；头名字小写`() {
        val server = serve(TlsFixtures.gatewayA) { ServerAction.Respond(401, """{"code":"GATEWAY_AUTH_REVOKED"}""".toByteArray(), mapOf("Content-Type" to "application/json", "X-Custom" to "v")) }

        val reply = get(client(TlsFixtures.gatewayA), server)

        assertEquals(401, reply.status)
        assertEquals("application/json", reply.headers["content-type"])
        assertEquals("v", reply.headers["x-custom"])
    }

    @Test
    fun `路径或头里夹带换行或空白：请求在发出之前就被拒绝`() {
        for (path in listOf("/a b", "/a\r\nX: 1", "relative", "/a\u0000")) {
            runCatching { HttpRequest("GET", path) }.let { assertTrue("路径 ${path.replace("\r", "\\r").replace("\n", "\\n")}", it.exceptionOrNull() is IllegalArgumentException) }
        }
        assertTrue(runCatching { HttpRequest("GET", "/ok", mapOf("Authorization" to "Bearer x\r\nX-Injected: 1")) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { HttpRequest("GET", "/ok", mapOf("Bad Name" to "x")) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `协程被取消时立刻返回，不等读超时`() {
        val server = serve(TlsFixtures.gatewayA) { ServerAction.Hang }
        runBlocking {
            val call = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                client(TlsFixtures.gatewayA, Timeouts(connectMs = 2_000, ioMs = 20_000, totalMs = 60_000)).execute(address(server), HttpRequest("GET", "/health"))
            }
            // 等请求真的送到服务器（它此刻正挂在读响应上）
            withTimeout(5_000) { while (server.received.isEmpty()) kotlinx.coroutines.delay(20) }
            val started = System.nanoTime()
            withTimeout(2_000) { call.cancelAndJoin() }
            assertTrue("取消应当几乎立刻生效", (System.nanoTime() - started) / 1_000_000 < 1_500)
        }
    }
}
