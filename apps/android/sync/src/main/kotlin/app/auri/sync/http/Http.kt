package app.auri.sync.http

import app.auri.sync.pairing.GatewayAddress

/**
 * 发给网关的一个 HTTP 请求。Host、Content-Length、Connection 由客户端自己加，调用方只给方法、路径、其他头与正文。
 * 路径与头在这里就校验：不允许空白与控制字符（CR/LF 进来就是请求走私）。
 */
class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
) {
    init {
        require(method in METHODS) { "不支持的方法 $method" }
        require(path.startsWith("/") && path.all { it in '!'..'~' }) { "路径里不能有空白或控制字符" }
        headers.forEach { (name, value) ->
            require(name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '-' }) { "非法的头名字" }
            require(value.all { it in ' '..'~' }) { "头 $name 的值里不能有控制字符或非 ASCII 字符" }
        }
        require(body == null || method != "GET") { "GET 不带正文" }
    }

    // 头里有 Authorization：任何日志里都不能把整个请求打出来
    override fun toString() = "$method $path"

    private companion object {
        val METHODS = setOf("GET", "PUT", "POST", "DELETE")
    }
}

/** 网关的 HTTP 响应。头的名字统一小写。 */
class HttpReply(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    override fun toString() = "HTTP $status (${body.size} bytes)"
}

/**
 * 与网关的一次请求/响应往返（App 里唯一的网络边界）。实现必须每次都是一条新连接，并且每次都核对网关的公钥指纹。
 * 失败一律抛 [TransportFailure]，而且要说清请求有没有可能已经送到：这决定了同步引擎是直接重发，还是先问网关结果。
 */
interface HttpExchange {
    @Throws(TransportFailure::class)
    suspend fun execute(address: GatewayAddress, request: HttpRequest): HttpReply
}

/** 构造“只信这个公钥”的往返通道。配对前用二维码里的公钥，配对后用记下来的公钥。 */
fun interface HttpExchangeFactory {
    fun forPin(pin: app.auri.sync.tls.SpkiPin): HttpExchange
}

sealed class TransportFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** 连接或握手没完成（包括对方根本不说 TLS）：请求一定没有发出，令牌与配对码都没有离开手机。 */
    class Unreachable(cause: Throwable? = null) : TransportFailure("gateway unreachable", cause)

    /** 对方出示的公钥与记下的指纹不一致：已拒绝连接，请求一定没有发出。需要用户重新配对。 */
    class KeyMismatch : TransportFailure("gateway public key does not match the pinned fingerprint")

    /** 请求可能已经（部分）发出，但没拿到完整响应（超时、断线、响应不完整）：结果不确定。 */
    class Interrupted(cause: Throwable? = null) : TransportFailure("gateway exchange interrupted", cause)
}

data class Timeouts(
    /** 建立 TCP 连接的上限。 */
    val connectMs: Int = 3_000,
    /** 握手、写请求、读响应每一步等待的上限。 */
    val ioMs: Int = 10_000,
    /** 从开始到拿到完整响应的总上限（防止对端一点一点地滴数据把请求拖住）。 */
    val totalMs: Long = 30_000,
)
