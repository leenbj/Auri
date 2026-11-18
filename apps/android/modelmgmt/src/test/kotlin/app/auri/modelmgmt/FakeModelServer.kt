package app.auri.modelmgmt

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class RecordedRequest(
    val path: String,
    val range: String?,
    val ifRange: String?,
    val acceptEncoding: String?,
)

/**
 * 本机的假模型服务器（只听 127.0.0.1，单元测试不碰真实网络）。
 * 用最朴素的 ServerSocket 手写 HTTP/1.1，而不用 JDK 自带的 com.sun.net.httpserver：Android 库模块的单元测试编译类路径里
 * 没有 jdk.httpserver 模块；手写也让“中途掐断”“长度不符”这类坏行为能精确控制。每个响应后都关连接（Connection: close）。
 *
 * 支持 Range / If-Range / 206 / 416、强校验器（ETag）与 Last-Modified，并能按需制造各种坏行为：
 * 忽略 Range、中途断线、中途卡住、重定向、固定错误状态、内容中途被换掉。每个请求的相关头都被记下来，测试据此断言“续传从哪个字节开始”。
 */
class FakeModelServer(content: ByteArray, private val path: String = "/archive") : AutoCloseable {
    @Volatile var content: ByteArray = content
    @Volatile var etag: String? = "\"v1\""
    @Volatile var lastModified: String? = null
    @Volatile var supportRange: Boolean = true
    @Volatile var statusOverride: Int? = null

    /** 对 [path] 的请求先 302 到这个路径；这个路径本身正常提供内容。 */
    @Volatile var redirectToPath: String? = null

    /** 服务器不理会 If-Range（有的服务器就是这样）：校验器变了也照给 206，客户端必须自己比对响应里的校验器。 */
    @Volatile var ignoreIfRange: Boolean = false

    /** 不声明 Content-Length，用分块传输把正文送完（制造“长度未知”的响应）。 */
    @Volatile var chunked: Boolean = false

    /** 只回答下一个带 Range 的请求这个状态码（一次性），之后恢复正常。 */
    @Volatile var nextRangeStatus: Int? = null

    /** 每个响应只送出这么多正文字节就掐断连接（制造“网络中断”）。 */
    @Volatile var dropAfterBytes: Long? = null

    /** 每个响应送出这么多正文字节后卡住，直到 [releaseStall]（制造“网速为零”，用来测暂停）。 */
    @Volatile var stallAfterBytes: Long? = null
    private val stallGate = CountDownLatch(1)

    val requests = CopyOnWriteArrayList<RecordedRequest>()

    private val serverSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "fake-model-server").apply { isDaemon = true }
    }

    init {
        executor.execute {
            while (!serverSocket.isClosed) {
                val socket = try {
                    serverSocket.accept()
                } catch (e: IOException) {
                    return@execute
                }
                executor.execute { socket.use { serve(it) } }
            }
        }
    }

    val port: Int get() = serverSocket.localPort
    val url: String get() = "http://127.0.0.1:$port$path"
    fun urlOf(otherPath: String) = "http://127.0.0.1:$port$otherPath"

    fun releaseStall() = stallGate.countDown()

    /** 等到有至少 [count] 个请求到达；超时说明客户端根本没来。 */
    fun awaitRequests(count: Int, timeoutMs: Long = 10_000) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (requests.size < count) {
            check(System.nanoTime() < deadline) { "等了 ${timeoutMs}ms 只收到 ${requests.size} 个请求，期望至少 $count 个" }
            Thread.sleep(10)
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = reader.readLine() ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isEmpty()) break
                headers.putIfAbsent(line.substringBefore(':').trim().lowercase(), line.substringAfter(':').trim())
            }
            val requestPath = requestLine.split(' ').getOrElse(1) { "/" }.substringBefore('?')
            val range = headers["range"]
            val ifRange = headers["if-range"]
            requests += RecordedRequest(requestPath, range, ifRange, headers["accept-encoding"])
            respond(BufferedOutputStream(socket.getOutputStream()), requestPath, range, ifRange)
        } catch (e: IOException) {
            // 客户端先走了（暂停、取消、断线）：对假服务器来说是正常情况
        }
    }

    private fun respond(out: OutputStream, requestPath: String, range: String?, ifRange: String?) {
        val redirect = redirectToPath
        if (redirect != null && requestPath == path) {
            header(out, 302, listOf("Location" to redirect))
            return
        }
        statusOverride?.let {
            header(out, it, emptyList())
            return
        }
        val body = content
        var start = 0L
        var status = 200
        if (range != null) {
            nextRangeStatus?.let {
                nextRangeStatus = null
                header(out, it, emptyList())
                return
            }
        }
        val rangeApplies = supportRange && range != null &&
            (ifRange == null || ignoreIfRange || ifRange == etag || ifRange == lastModified)
        if (rangeApplies) {
            start = Regex("^bytes=(\\d+)-$").matchEntire(range!!)?.groupValues?.get(1)?.toLong() ?: 0L
            if (start >= body.size) {
                header(out, 416, listOf("Content-Range" to "bytes */${body.size}"))
                return
            }
            status = 206
        }
        val headers = ArrayList<Pair<String, String>>()
        etag?.let { headers += "ETag" to it }
        lastModified?.let { headers += "Last-Modified" to it }
        if (supportRange) headers += "Accept-Ranges" to "bytes"
        if (status == 206) headers += "Content-Range" to "bytes $start-${body.size - 1}/${body.size}"
        if (chunked) headers += "Transfer-Encoding" to "chunked" else headers += "Content-Length" to (body.size - start).toString()
        header(out, status, headers)

        var sent = 0L
        var position = start.toInt()
        while (position < body.size) {
            val limit = listOfNotNull(dropAfterBytes, stallAfterBytes).minOrNull()
            val chunk = minOf(8192, body.size - position, (limit?.minus(sent))?.toInt() ?: Int.MAX_VALUE)
            if (chunk <= 0) {
                val stall = stallAfterBytes
                if (stall != null && sent >= stall) {
                    out.flush()
                    stallGate.await(60, TimeUnit.SECONDS)
                    stallAfterBytes = null
                    continue
                }
                // dropAfterBytes：不把声明的长度写完就关连接，客户端会读到提前结束
                out.flush()
                return
            }
            if (chunked) out.write("${Integer.toHexString(chunk)}\r\n".toByteArray(Charsets.ISO_8859_1))
            out.write(body, position, chunk)
            if (chunked) out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
            position += chunk
            sent += chunk
        }
        if (chunked) out.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun header(out: OutputStream, status: Int, headers: List<Pair<String, String>>) {
        val text = StringBuilder("HTTP/1.1 $status ${reason(status)}\r\n")
        headers.forEach { (name, value) -> text.append(name).append(": ").append(value).append("\r\n") }
        if (headers.none { it.first == "Content-Length" || it.first == "Transfer-Encoding" }) text.append("Content-Length: 0\r\n")
        text.append("Connection: close\r\n\r\n")
        out.write(text.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        206 -> "Partial Content"
        302 -> "Found"
        404 -> "Not Found"
        416 -> "Range Not Satisfiable"
        503 -> "Service Unavailable"
        else -> "Status"
    }

    override fun close() {
        stallGate.countDown()
        serverSocket.close()
        executor.shutdownNow()
    }
}
