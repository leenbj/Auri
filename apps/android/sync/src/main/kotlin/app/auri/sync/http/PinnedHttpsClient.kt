package app.auri.sync.http

import app.auri.sync.pairing.GatewayAddress
import app.auri.sync.tls.PinnedTls
import app.auri.sync.tls.SpkiPin
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 与网关的 HTTPS 往返：自己写的最小 HTTP/1.1 客户端，跑在 [SSLSocket] 上。
 *
 * 为什么不用 HttpsURLConnection / OkHttp：身份检查必须是“公钥指纹”，不是主机名；通用 HTTP 栈要靠 HostnameVerifier 才能放过
 * IP 变了的自签证书，那就得在 App 里留一个“放行一切”形状的类。这里根本没有 HostnameVerifier，
 * 校验只有一处：握手中的 [app.auri.sync.tls.SpkiPinTrustManager] 比对指纹。
 *
 * 每个请求：新建连接 → 新的 TLS 上下文（没有会话复用，每次都是完整握手）→ 核对指纹 → 才写请求（令牌在此之后才会离开手机）→
 * 读完响应 → 关闭。不跟随重定向、不走系统代理、不压缩、不保持连接。
 *
 * 总时限（[Timeouts.totalMs]）管住整个往返：主机名解析、连接、握手、写请求、读响应共用同一个截止时刻。
 * 到点时看门狗直接关掉底层套接字，阻塞在连接、握手、写或读里的调用都会立刻抛出；解析不是套接字操作，单独放在限时等待里。
 *
 * 失败分三类（[TransportFailure]）：连接/握手没成（请求一定没发出）、公钥不符（请求一定没发出）、请求可能已发出但没读完响应（结果不确定）。
 */
class PinnedHttpsClient(
    private val pin: SpkiPin,
    private val timeouts: Timeouts = Timeouts(),
    private val maxResponseBytes: Int = 1 shl 20,
    /** 主机名解析，默认走系统。做成参数只为了让测试能模拟一个卡住的解析器。 */
    private val resolve: (String) -> InetAddress = InetAddress::getByName,
) : HttpExchange {
    override suspend fun execute(address: GatewayAddress, request: HttpRequest): HttpReply {
        val connection = Connection(address, request)
        return try {
            coroutineScope {
                // 子协程自己吞下异常、以 Result 返回：取消时它因套接字被关而抛出的 IO 错误，不能顶替取消，更不能当成失败传给父协程
                val work = async(Dispatchers.IO) { runCatching { connection.run() } }
                // 看门狗：到总时限就把套接字关掉。放在 IO 调度器上按真实时间计时，不受调用方调度器（比如测试的虚拟时间）影响
                val watchdog = launch(Dispatchers.IO) {
                    delay(timeouts.totalMs)
                    connection.abort()
                }
                val outcome = try {
                    work.await()
                } catch (cancelled: CancellationException) {
                    // 阻塞的读写不会响应协程取消，关掉套接字才能让它立刻返回
                    connection.abort()
                    throw cancelled
                } finally {
                    watchdog.cancel()
                }
                outcome.getOrThrow()
            }
        } finally {
            connection.close()
        }
    }

    private inner class Connection(private val address: GatewayAddress, private val request: HttpRequest) {
        private val plain = Socket()
        @Volatile private var tlsSocket: SSLSocket? = null
        private val deadlineNs = System.nanoTime() + timeouts.totalMs * 1_000_000

        fun close() {
            runCatching { tlsSocket?.close() }
            runCatching { plain.close() }
        }

        /**
         * 从别的线程强行中断：先关底层 TCP 套接字，再关 TLS 层。
         * 反过来不行：TLS 层的 close() 要先抢写锁去发 close_notify，而写锁正被一个卡在“对端不收数据”里的写操作占着，close() 会陪它一起卡住。
         */
        fun abort() {
            runCatching { plain.close() }
            runCatching { tlsSocket?.close() }
        }

        fun run(): HttpReply {
            val tls = PinnedTls(pin)
            val socket = connect(tls)
            val input = try {
                sendRequest(socket)
                socket.getInputStream().buffered()
            } catch (e: IOException) {
                throw TransportFailure.Interrupted(e)
            }
            return try {
                readResponse(input, socket)
            } catch (e: IOException) {
                throw TransportFailure.Interrupted(e)
            }
        }

        /** 解析主机名，最多等到总时限；解析器自己卡住时丢下它的线程（守护线程，解析器返回后自行结束），调用方不陪着卡。 */
        private fun resolveWithinDeadline(): InetAddress {
            val lookup = RESOLVER.submit(Callable { resolve(address.host) })
            try {
                return lookup.get(((deadlineNs - System.nanoTime()) / 1_000_000).coerceAtLeast(1), TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                lookup.cancel(true)
                throw SocketTimeoutException("name resolution exceeded the total deadline")
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }

        /** 建立 TCP、完成 TLS 握手并核对指纹。这一步任何失败，请求都还没有发出。 */
        private fun connect(tls: PinnedTls): SSLSocket {
            try {
                plain.connect(InetSocketAddress(resolveWithinDeadline(), address.port), timeouts.connectMs)
                plain.soTimeout = timeouts.ioMs
                val socket = tls.socketFactory.createSocket(plain, address.host, address.port, true) as SSLSocket
                tlsSocket = socket
                socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
                // 握手只有在 SpkiPinTrustManager 认可对方公钥时才会完成；每次都是新的 TLS 上下文，没有会话复用可以绕过它
                socket.startHandshake()
                return socket
            } catch (failure: TransportFailure) {
                throw failure
            } catch (e: IOException) {
                if (tls.trustManager.mismatchObserved) throw TransportFailure.KeyMismatch()
                throw TransportFailure.Unreachable(e)
            } catch (e: RuntimeException) {
                if (tls.trustManager.mismatchObserved) throw TransportFailure.KeyMismatch()
                throw TransportFailure.Unreachable(e)
            }
        }

        private fun sendRequest(socket: SSLSocket) {
            val body = request.body
            val host = if (':' in address.host) "[${address.host.substringBefore('%')}]" else address.host
            val head = buildString {
                append(request.method).append(' ').append(request.path).append(" HTTP/1.1\r\n")
                append("Host: ").append(host).append(':').append(address.port).append("\r\n")
                append("Accept: application/json\r\n")
                append("Connection: close\r\n")
                request.headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
                if (body != null || request.method != "GET") append("Content-Length: ").append(body?.size ?: 0).append("\r\n")
                append("\r\n")
            }
            val out = socket.getOutputStream()
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            if (body != null) out.write(body)
            out.flush()
        }

        private fun readResponse(input: InputStream, socket: SSLSocket): HttpReply {
            val head = readHead(input, socket)
            val lines = head.split("\r\n")
            val status = STATUS_LINE.matchEntire(lines.first())?.groupValues?.get(1)?.toInt() ?: throw IOException("malformed status line")
            val headers = HashMap<String, String>()
            lines.drop(1).forEach { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) throw IOException("malformed header line")
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            val body = when {
                status in 100..199 || status == 204 || status == 304 -> ByteArray(0)
                headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true -> readChunked(input, socket)
                headers["content-length"] != null -> {
                    val length = headers.getValue("content-length").toIntOrNull()?.takeIf { it >= 0 } ?: throw IOException("bad content-length")
                    if (length > maxResponseBytes) throw IOException("response too large")
                    readFully(input, length, socket)
                }

                else -> readToEnd(input, socket)
            }
            return HttpReply(status, headers, body)
        }

        private fun readHead(input: InputStream, socket: SSLSocket): String {
            val buffer = ByteArrayOutputStream()
            var last4 = 0
            while (true) {
                val b = readByte(input, socket)
                if (b < 0) throw IOException("connection closed before response head was complete")
                buffer.write(b)
                if (buffer.size() > MAX_HEAD_BYTES) throw IOException("response head too large")
                last4 = (last4 shl 8) or b
                if (last4 == CRLF_CRLF) return buffer.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r\n\r\n")
            }
        }

        private fun readChunked(input: InputStream, socket: SSLSocket): ByteArray {
            val out = ByteArrayOutputStream()
            while (true) {
                val size = chunkSize(readLine(input, socket))
                if (size == 0L) {
                    // 拖尾头：读到空行为止
                    while (readLine(input, socket).isNotEmpty()) Unit
                    return out.toByteArray()
                }
                // 用 Long 比、先比后分配：已读字节加上声称的块大小在 Int 里会溢出成负数，从而绕过上限去分配 2 GB 的数组；
                // 比较通过之后 size 不会超过剩余额度，所以 toInt 与分配都被上限卡住
                if (size > maxResponseBytes - out.size().toLong()) throw IOException("response too large")
                out.write(readFully(input, size.toInt(), socket))
                if (readLine(input, socket).isNotEmpty()) throw IOException("missing chunk terminator")
            }
        }

        /** 块大小行：最多 16 位十六进制数字（装得进 Long），允许 ";扩展"；不接受正负号、0x 前缀、空白或空串。 */
        private fun chunkSize(line: String): Long {
            val digits = line.substringBefore(';').trim()
            if (!CHUNK_SIZE.matches(digits)) throw IOException("bad chunk size")
            return digits.toLongOrNull(16) ?: throw IOException("bad chunk size")
        }

        private fun readToEnd(input: InputStream, socket: SSLSocket): ByteArray {
            val out = ByteArrayOutputStream()
            while (true) {
                val b = readByte(input, socket)
                if (b < 0) return out.toByteArray()
                out.write(b)
                if (out.size() > maxResponseBytes) throw IOException("response too large")
            }
        }

        private fun readLine(input: InputStream, socket: SSLSocket): String {
            val line = ByteArrayOutputStream()
            while (true) {
                val b = readByte(input, socket)
                if (b < 0) throw IOException("connection closed inside a chunked body")
                if (b == '\n'.code) return line.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r")
                line.write(b)
                if (line.size() > MAX_HEAD_BYTES) throw IOException("line too long")
            }
        }

        private fun readFully(input: InputStream, length: Int, socket: SSLSocket): ByteArray {
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                applyDeadline(socket)
                val n = input.read(body, read, length - read)
                if (n < 0) throw IOException("response body shorter than its declared length")
                read += n
            }
            return body
        }

        private fun readByte(input: InputStream, socket: SSLSocket): Int {
            applyDeadline(socket)
            return input.read()
        }

        /** 每次读之前把套接字超时压到“剩余总时间”以内：对端一点一点滴数据也拖不过总时限。 */
        private fun applyDeadline(socket: SSLSocket) {
            val remainingMs = (deadlineNs - System.nanoTime()) / 1_000_000
            if (remainingMs <= 0) throw SocketTimeoutException("total deadline exceeded")
            socket.soTimeout = minOf(timeouts.ioMs.toLong(), remainingMs).toInt().coerceAtLeast(1)
        }
    }

    private companion object {
        val STATUS_LINE = Regex("^HTTP/1\\.[01] ([0-9]{3})(?: .*)?$")
        val CHUNK_SIZE = Regex("[0-9a-fA-F]{1,16}")

        // shortcut: 不设上限的守护线程池，解析器卡住几分钟又被高频调用时线程会堆积；请求频率受同步退避约束，真遇到再换有界池
        val RESOLVER = Executors.newCachedThreadPool { task -> Thread(task, "auri-sync-resolve").apply { isDaemon = true } }
        const val MAX_HEAD_BYTES = 16 * 1024
        const val CRLF_CRLF = 0x0D0A0D0A
    }
}
