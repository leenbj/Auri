package app.auri.sync.policy

import app.auri.sync.http.HttpRequest
import app.auri.sync.http.PinnedHttpsClient
import app.auri.sync.http.Timeouts
import app.auri.sync.http.TransportFailure
import app.auri.sync.pairing.GatewayAddress
import app.auri.sync.testing.TlsFixtures
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 交接（[PolicyGeneration.handoff]）在真实客户端上的性质（第三轮 1b）：交接把“比对世代 + 让请求跑到第一次挂起”放在进程级的锁里，
 * 所以请求第一次挂起之前的那一小段必须不阻塞；之后真正的网络等待（解析主机名、连接、握手）在 IO 线程上，不持锁。
 * 为什么要紧：授权设置的改动（用户点开关，可能在主线程）要拿同一把锁——请求在等网络时若还握着它，开关就卡住，甚至卡死界面。
 * 另外两条：取消要照常传到在途的请求；请求或比对抛出的异常要原样交给调用方（不能被当成“没放行”），而且锁不能因此漏掉。
 * 这里用真的 [PinnedHttpsClient]（卡住的解析器、接受连接却不说话的服务器），不是只会挂起的替身。
 */
class PolicyHandoffRealClientTest {
    // 每一步各自的上限都远大于测试等的时间：能让在途请求提前结束的只有释放解析器、取消、或关掉服务器
    private val patient = Timeouts(connectMs = 20_000, ioMs = 20_000, totalMs = 30_000)

    /** 接受连接，但既不读也不写：TLS 握手发出 ClientHello 之后一直等不到回应；[accepted] 说明请求已经在路上了。 */
    private class StallingServer : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val held = CopyOnWriteArrayList<Socket>()
        val accepted = CountDownLatch(1)
        val address: GatewayAddress get() = GatewayAddress("127.0.0.1", server.localPort)

        init {
            thread(isDaemon = true, name = "test-stalling-accept") {
                while (!server.isClosed) {
                    val socket = try {
                        server.accept()
                    } catch (_: SocketException) {
                        break
                    }
                    held += socket
                    accepted.countDown()
                }
            }
        }

        override fun close() {
            runCatching { server.close() }
            held.forEach { runCatching { it.close() } }
        }
    }

    /** 另一个线程里改一次授权设置（前后各动一次世代）：5 秒内做完才算“没有被在途的请求挡住”。 */
    private suspend fun assertPolicyChangesAreNotBlocked() {
        val generation = PolicyGeneration()
        val done = CountDownLatch(1)
        thread(isDaemon = true, name = "test-policy-change") {
            generation.changing()
            generation.changed()
            done.countDown()
        }
        assertTrue("a policy change must not wait for a request that is still in flight", withContext(Dispatchers.IO) { done.await(5, TimeUnit.SECONDS) })
    }

    @Test
    fun `主机名解析卡住：在途的请求不挡授权设置的改动；解析器放弃后请求按连不上返回`() = runBlocking<Unit> {
        val resolving = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = PinnedHttpsClient(TlsFixtures.gatewayA.pin, patient, 1 shl 20) {
            resolving.countDown()
            release.await(15, TimeUnit.SECONDS)
            throw UnknownHostException("stuck resolver")
        }
        val request = async(Dispatchers.Default) {
            runCatching { PolicyGeneration.handoff({ true }) { client.execute(GatewayAddress("gateway.example", 8443), HttpRequest("GET", "/health")) } }
        }
        try {
            assertTrue("the request must be in flight (inside the resolver)", withContext(Dispatchers.IO) { resolving.await(10, TimeUnit.SECONDS) })
            assertPolicyChangesAreNotBlocked()
        } finally {
            release.countDown()
        }
        val failure = request.await().exceptionOrNull()
        assertTrue("once the resolver gives up the request fails as unreachable, not as an exception of the handoff: $failure", failure is TransportFailure.Unreachable)
    }

    @Test
    fun `握手卡住：在途的请求不挡授权设置的改动`() = runBlocking<Unit> {
        StallingServer().use { server ->
            val client = PinnedHttpsClient(TlsFixtures.gatewayA.pin, patient)
            val request = async(Dispatchers.Default) {
                runCatching { PolicyGeneration.handoff({ true }) { client.execute(server.address, HttpRequest("GET", "/health")) } }
            }
            try {
                assertTrue("the request must be in flight (connected, waiting for the handshake)", withContext(Dispatchers.IO) { server.accepted.await(10, TimeUnit.SECONDS) })
                assertPolicyChangesAreNotBlocked()
            } finally {
                server.close() // 对端挂断，握手立刻失败，请求结束
            }
            request.await()
        }
    }

    @Test
    fun `取消调用方：取消穿过交接传到卡在握手里的请求，很快结束，也不留着锁`() = runBlocking<Unit> {
        StallingServer().use { server ->
            val client = PinnedHttpsClient(TlsFixtures.gatewayA.pin, patient)
            val request = async(Dispatchers.Default) {
                PolicyGeneration.handoff({ true }) { client.execute(server.address, HttpRequest("GET", "/health")) }
            }
            assertTrue("the request must be in flight", withContext(Dispatchers.IO) { server.accepted.await(10, TimeUnit.SECONDS) })

            request.cancel()

            // 单步的上限是 20 秒：5 秒内结束，只可能是取消真的传到了请求（并关掉了它的套接字）
            assertTrue("cancelling the caller must end the in-flight request promptly", withTimeoutOrNull(5_000) { request.join() } != null)
            assertTrue(request.isCancelled)
            assertPolicyChangesAreNotBlocked()
        }
    }

    @Test
    fun `请求抛出的异常原样交给调用方，不是“没放行”；之后锁是空的`() = runBlocking<Unit> {
        val immediate = runCatching { PolicyGeneration.handoff({ true }) { throw IllegalStateException("boom") } }.exceptionOrNull()
        assertTrue("an exception before the first suspension reaches the caller as it is: $immediate", immediate is IllegalStateException && immediate.message == "boom")

        val later = runCatching { PolicyGeneration.handoff({ true }) { yield(); throw IllegalArgumentException("later") } }.exceptionOrNull()
        assertTrue("an exception after the first suspension reaches the caller as it is: $later", later is IllegalArgumentException && later.message == "later")

        assertPolicyChangesAreNotBlocked()
    }

    @Test
    fun `比对抛出异常：原样交给调用方，请求一行也没有运行；之后锁是空的`() = runBlocking<Unit> {
        var started = false
        val thrown = runCatching { PolicyGeneration.handoff({ throw IllegalStateException("generation unreadable") }) { started = true; 1 } }.exceptionOrNull()

        assertTrue("the check's exception reaches the caller: $thrown", thrown is IllegalStateException && thrown.message == "generation unreadable")
        assertFalse("the request must not run when the check could not be made", started)
        assertPolicyChangesAreNotBlocked()
    }

    @Test
    fun `放行并运行完的请求把结果原样交回；没放行返回 null、请求不运行`() = runBlocking<Unit> {
        assertEquals(7, PolicyGeneration.handoff({ true }) { yield(); 7 })

        var started = false
        assertNull(PolicyGeneration.handoff({ false }) { started = true; 7 })
        assertFalse(started)
    }
}
