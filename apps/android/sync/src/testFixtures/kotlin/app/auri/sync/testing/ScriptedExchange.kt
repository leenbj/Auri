package app.auri.sync.testing

import app.auri.sync.http.HttpExchange
import app.auri.sync.http.HttpReply
import app.auri.sync.http.HttpRequest
import app.auri.sync.pairing.GatewayAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 在 HTTP 往返这一层的脚本化“网关”：测试决定每个请求得到什么响应，或者抛出哪种传输失败。
 * 它是系统边界上的替身（真网关的黑盒测试另有 realgateway 包），请求被逐个记录，供测试核对“发了什么、发给了谁”。
 */
class ScriptedExchange(private val script: suspend (GatewayAddress, HttpRequest) -> HttpReply) : HttpExchange {
    val calls = CopyOnWriteArrayList<Pair<GatewayAddress, HttpRequest>>()

    override suspend fun execute(address: GatewayAddress, request: HttpRequest): HttpReply {
        calls += address to request
        return script(address, request)
    }

    companion object {
        fun json(status: Int, body: String) = HttpReply(status, mapOf("content-type" to "application/json"), body.toByteArray(Charsets.UTF_8))
    }
}
