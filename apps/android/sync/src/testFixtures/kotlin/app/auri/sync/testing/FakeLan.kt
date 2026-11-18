package app.auri.sync.testing

import app.auri.sync.discovery.GatewayDiscovery
import app.auri.sync.http.HttpExchange
import app.auri.sync.http.HttpExchangeFactory
import app.auri.sync.http.HttpReply
import app.auri.sync.http.HttpRequest
import app.auri.sync.http.TransportFailure
import app.auri.sync.pairing.GatewayAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 一张用内存搭的“局域网”：哪个地址上坐着哪个密钥的服务器，由测试决定。
 * 它按真实通道的规矩行事：地址上没人 → Unreachable；对方的公钥与钉住的指纹不符 → KeyMismatch，**请求根本不会送到服务器**；
 * 只有指纹对上了，请求才交给那台服务器的 [Host.handler]。所以“令牌没有发给冒充者”可以直接从 [delivered] 看出来。
 */
class FakeLan {
    class Host(val identity: TlsFixtures.Identity, var handler: suspend (HttpRequest) -> HttpReply)

    val hosts = LinkedHashMap<GatewayAddress, Host>()

    /** 每一次连接尝试（含被拒绝的），按顺序。 */
    val attempts = CopyOnWriteArrayList<GatewayAddress>()

    /** 真正送到了服务器的请求（指纹对上之后）。 */
    val delivered = CopyOnWriteArrayList<Pair<GatewayAddress, HttpRequest>>()

    /** 每次 forPin 拿到的指纹，按顺序。 */
    val pinsUsed = CopyOnWriteArrayList<app.auri.sync.tls.SpkiPin>()

    val factory = HttpExchangeFactory { pin ->
        pinsUsed += pin
        object : HttpExchange {
            override suspend fun execute(address: GatewayAddress, request: HttpRequest): HttpReply {
                attempts += address
                val host = hosts[address] ?: throw TransportFailure.Unreachable()
                if (host.identity.pin != pin) throw TransportFailure.KeyMismatch()
                delivered += address to request
                return host.handler(request)
            }
        }
    }

    fun host(address: GatewayAddress, identity: TlsFixtures.Identity, handler: suspend (HttpRequest) -> HttpReply = { ScriptedExchange.json(200, """{"status":"ok"}""") }) {
        hosts[address] = Host(identity, handler)
    }
}

/** 脚本化的 mDNS：返回预设的地址，记录被问过几次、问的是哪个网关。 */
class FakeDiscovery(var result: suspend (String) -> List<GatewayAddress> = { emptyList() }) : GatewayDiscovery {
    val asked = CopyOnWriteArrayList<String>()

    override suspend fun find(gatewayId: String): List<GatewayAddress> {
        asked += gatewayId
        return result(gatewayId)
    }
}
