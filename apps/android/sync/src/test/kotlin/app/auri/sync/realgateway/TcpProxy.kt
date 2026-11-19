package app.auri.sync.realgateway

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * 回环上的纯 TCP 转发，不碰里面的字节（TLS 是手机与网关之间端到端的，转发器看不懂也改不了）。
 * 用途：1) 数一数真正到过网关的连接（[reachedGateway]）；2) 手机记着的地址不变，背后的网关可以换：关机（[target] 为 null）、
 * 重启后换了端口、换成另一台公钥不同的网关。
 */
class TcpProxy(@Volatile var target: Int?) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort

    /** 手机连进来的次数。 */
    val accepted = AtomicInteger()

    /** 其中真正连到了网关的次数（网关不在时手机连得进转发器，却到不了网关）。 */
    val reachedGateway = AtomicInteger()

    init {
        thread(isDaemon = true, name = "tcp-proxy-accept") {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                accepted.incrementAndGet()
                thread(isDaemon = true, name = "tcp-proxy-conn") { relay(client) }
            }
        }
    }

    private fun relay(client: Socket) {
        val upstream = target?.let { gatewayPort ->
            try {
                Socket(InetAddress.getLoopbackAddress(), gatewayPort)
            } catch (_: IOException) {
                null
            }
        }
        if (upstream == null) {
            // 网关不在：对手机来说和端口没人听一样，TLS 握手没能开始
            client.close()
            return
        }
        reachedGateway.incrementAndGet()
        val forward = thread(isDaemon = true, name = "tcp-proxy-up") { pump(client, upstream) }
        val backward = thread(isDaemon = true, name = "tcp-proxy-down") { pump(upstream, client) }
        forward.join()
        backward.join()
        client.close()
        upstream.close()
    }

    /** 一个方向搬到对方关闭为止；只关写端（半关闭），另一个方向的剩余字节还能送完。 */
    private fun pump(from: Socket, to: Socket) {
        try {
            val buffer = ByteArray(16 * 1024)
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (_: IOException) {
            // 对方断了：下面照样半关闭
        } finally {
            try {
                to.shutdownOutput()
            } catch (_: IOException) {
            }
        }
    }

    override fun close() {
        server.close()
    }
}
