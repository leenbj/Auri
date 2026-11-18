package app.auri.sync.testing

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/** 服务器收到的一个 HTTP 请求（逐字节记录，用来核对“令牌有没有被发出去”“正文有没有走样”）。 */
class ReceivedRequest(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    override fun toString() = "$method $path (${body.size} bytes)"
}

/** 服务器对一个请求的反应。除了正常回应，还能模拟网络上各种“半路出事”。 */
sealed interface ServerAction {
    class Respond(
        val status: Int,
        val body: ByteArray = ByteArray(0),
        val headers: Map<String, String> = mapOf("Content-Type" to "application/json"),
        /** true：用 chunked 传输编码发正文（每块 7 字节，故意切碎）。 */
        val chunked: Boolean = false,
        /** true：不发 Content-Length，靠关闭连接表示结束。 */
        val closeDelimited: Boolean = false,
    ) : ServerAction

    /** 收到请求就断开，什么都不回：手机看到“请求可能已经送到了，结果不知道”。 */
    data object CloseWithoutResponse : ServerAction

    /** 收到请求后一直不回应，直到服务器关闭。 */
    data object Hang : ServerAction

    /** 声明了 [declaredLength] 字节的正文，只发 [sent] 字节就断开。 */
    class Truncated(val declaredLength: Int, val sent: ByteArray) : ServerAction

    /** 原样写出这些字节再断开（畸形响应）。 */
    class Raw(val bytes: ByteArray) : ServerAction
}

/**
 * 真正的 TLS 服务器（JDK 的 SSLServerSocket，监听本机回环），用 [TlsFixtures] 里的身份出示证书。
 * 固定公钥的客户端对它做真握手；服务器自己记录“完成了几次握手”“收到了哪些请求”，
 * 所以“每次连接都核对公钥”“指纹不符时令牌根本没有发出去”都能直接从服务器这一侧观察到，不靠客户端自己的说法。
 */
class TestTlsServer(
    private val identity: TlsFixtures.Identity,
    port: Int = 0,
    private val script: (ReceivedRequest) -> ServerAction,
) : AutoCloseable {
    private val serverSocket: SSLServerSocket
    private val connections = CopyOnWriteArrayList<Socket>()

    /** 服务器这一侧完成的 TLS 握手次数。 */
    val completedHandshakes = AtomicInteger()

    /** 握手没成功的连接数（客户端在握手阶段就拒绝或掉线）。 */
    val failedHandshakes = AtomicInteger()

    val received = CopyOnWriteArrayList<ReceivedRequest>()

    /** 每次握手之后会话的创建时间：复用了会话的连接，看到的是第一次握手的时间。 */
    val sessionCreationTimes = CopyOnWriteArrayList<Long>()
    val port: Int get() = serverSocket.localPort

    init {
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(identity.keyStore, TlsFixtures.PASSWORD.toCharArray())
        }.keyManagers
        val context = SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }
        serverSocket = (context.serverSocketFactory.createServerSocket(port, 50, java.net.InetAddress.getByName("127.0.0.1")) as SSLServerSocket)
        thread(isDaemon = true, name = "test-tls-accept") {
            while (!serverSocket.isClosed) {
                val socket = try {
                    serverSocket.accept() as SSLSocket
                } catch (_: SocketException) {
                    break
                }
                connections += socket
                thread(isDaemon = true, name = "test-tls-conn") { serve(socket) }
            }
        }
    }

    private fun serve(socket: SSLSocket) {
        try {
            socket.soTimeout = 30_000
            try {
                socket.startHandshake()
                sessionCreationTimes += socket.session.creationTime
                completedHandshakes.incrementAndGet()
            } catch (_: Exception) {
                failedHandshakes.incrementAndGet()
                return
            }
            val request = readRequest(socket.getInputStream()) ?: return
            received += request
            respond(socket.getOutputStream(), script(request))
        } catch (_: Exception) {
            // 客户端中途走了：这是测试里的常态
        } finally {
            runCatching { socket.close() }
            connections -= socket
        }
    }

    private fun respond(out: OutputStream, action: ServerAction) {
        when (action) {
            ServerAction.CloseWithoutResponse -> Unit
            ServerAction.Hang -> Thread.sleep(60_000)
            is ServerAction.Truncated -> {
                out.write(head(200, listOf("Content-Type" to "application/json", "Content-Length" to action.declaredLength.toString())))
                out.write(action.sent)
                out.flush()
            }

            is ServerAction.Raw -> {
                out.write(action.bytes)
                out.flush()
            }

            is ServerAction.Respond -> {
                val headers = action.headers.toList().toMutableList()
                headers += "Connection" to "close"
                when {
                    action.chunked -> {
                        headers += "Transfer-Encoding" to "chunked"
                        out.write(head(action.status, headers))
                        action.body.toList().chunked(7).forEach { piece ->
                            out.write("${piece.size.toString(16)}\r\n".toByteArray())
                            out.write(piece.toByteArray())
                            out.write("\r\n".toByteArray())
                        }
                        out.write("0\r\n\r\n".toByteArray())
                    }

                    action.closeDelimited -> {
                        out.write(head(action.status, headers))
                        out.write(action.body)
                    }

                    else -> {
                        headers += "Content-Length" to action.body.size.toString()
                        out.write(head(action.status, headers))
                        out.write(action.body)
                    }
                }
                out.flush()
            }
        }
    }

    private fun head(status: Int, headers: List<Pair<String, String>>): ByteArray =
        (listOf("HTTP/1.1 $status ${reason(status)}") + headers.map { "${it.first}: ${it.second}" }).joinToString("\r\n", postfix = "\r\n\r\n").toByteArray()

    /** 立刻断开所有连接并关闭监听（模拟网关进程退出）。 */
    override fun close() {
        runCatching { serverSocket.close() }
        connections.forEach { runCatching { it.close() } }
    }

    companion object {
        private fun reason(status: Int) = when (status) {
            200 -> "OK"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            409 -> "Conflict"
            410 -> "Gone"
            422 -> "Unprocessable Entity"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            else -> "Status"
        }

        fun readRequest(input: InputStream): ReceivedRequest? {
            val head = readHead(input) ?: return null
            val lines = head.split("\r\n")
            val (method, path) = lines.first().split(" ").let { it[0] to it[1] }
            val headers = lines.drop(1).filter { it.isNotEmpty() }.associate { line -> line.substringBefore(':').trim() to line.substringAfter(':').trim() }
            val length = headers.entries.firstOrNull { it.key.equals("content-length", ignoreCase = true) }?.value?.toInt() ?: 0
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) return null
                read += n
            }
            return ReceivedRequest(method, path, headers, body)
        }

        private fun readHead(input: InputStream): String? {
            val buffer = ByteArrayOutputStream()
            var last4 = 0
            while (true) {
                val b = input.read()
                if (b < 0) return null
                buffer.write(b)
                last4 = (last4 shl 8) or b
                if (last4 == 0x0D0A0D0A) return buffer.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r\n\r\n")
            }
        }
    }
}

/** 只会明文回话的 TCP 服务器：手机的 TLS 握手撞上它必须失败，而且不能被当成“公钥不符”。 */
class PlainTcpServer(private val reply: ByteArray = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray()) : AutoCloseable {
    private val serverSocket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    val port: Int get() = serverSocket.localPort
    val connectionsAccepted = AtomicInteger()

    init {
        thread(isDaemon = true, name = "test-plain-accept") {
            while (!serverSocket.isClosed) {
                val socket = try {
                    serverSocket.accept()
                } catch (_: SocketException) {
                    break
                }
                connectionsAccepted.incrementAndGet()
                thread(isDaemon = true) {
                    runCatching {
                        socket.getOutputStream().write(reply)
                        socket.getOutputStream().flush()
                        Thread.sleep(200)
                    }
                    runCatching { socket.close() }
                }
            }
        }
    }

    override fun close() {
        runCatching { serverSocket.close() }
    }
}
