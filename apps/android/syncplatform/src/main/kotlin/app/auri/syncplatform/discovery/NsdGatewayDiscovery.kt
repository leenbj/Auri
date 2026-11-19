package app.auri.syncplatform.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import app.auri.sync.SyncLog
import app.auri.sync.discovery.GatewayDiscovery
import app.auri.sync.pairing.GatewayAddress
import java.io.IOException
import java.net.Inet4Address
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 用系统的 NsdManager 在局域网里找网关（mDNS，`_auri-gateway._tcp`）。只在记下的地址都连不上时才会被用到。
 *
 * 广告谁都能发，所以这里只产出“候选地址”：是不是真网关由握手时的公钥指纹决定，不由广告里的 gateway_id 决定
 * （TXT 里的 gateway_id 只用来挑出同一个网关的广告）。只取 IPv4 地址：链路本地的 IPv6 需要网卡作用域，连不上也没法记。
 *
 * 不论怎么结束（找到、超时、被取消、系统报错）都停止发现；系统 API 抛出的异常都当作“没找到”，不向上抛。
 * 模拟器的网络是 NAT，多播过不去，所以这一层只有真机在同一个局域网里才能验证（见验收文档的真机步骤）。
 */
class NsdGatewayDiscovery(
    context: Context,
    private val serviceType: String = SERVICE_TYPE,
    private val listenMs: Long = 4_000,
    private val log: SyncLog = SyncLog.None,
) : GatewayDiscovery {
    private val manager = context.applicationContext.getSystemService(NsdManager::class.java)

    override suspend fun find(gatewayId: String): List<GatewayAddress> {
        val found = LinkedHashSet<GatewayAddress>()
        withTimeoutOrNull(listenMs) {
            try {
                services().collect { service ->
                    // 一次只解析一个：旧版系统同时解析多个会直接报 FAILURE_ALREADY_ACTIVE
                    val resolved = resolve(service) ?: return@collect
                    val host = (resolved.host as? Inet4Address)?.hostAddress
                    gatewayAddressFrom(host, resolved.port, textAttributes(resolved), gatewayId)?.let(found::add)
                }
            } catch (failure: IOException) {
                log.event("nsd.failed")
            }
        }
        return found.toList()
    }

    private fun services(): Flow<NsdServiceInfo> = callbackFlow {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                trySend(serviceInfo)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(IOException("NSD 启动发现失败：$errorCode"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
        }
        try {
            manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (failure: RuntimeException) {
            close(IOException("NSD 不可用", failure))
        }
        awaitClose { runCatching { manager.stopServiceDiscovery(listener) } }
    }

    @Suppress("DEPRECATION") // API 34 起有 registerServiceInfoCallback；minSdk 31 上只有 resolveService
    private suspend fun resolve(service: NsdServiceInfo): NsdServiceInfo? = suspendCancellableCoroutine { continuation ->
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (continuation.isActive) continuation.resume(null)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                if (continuation.isActive) continuation.resume(serviceInfo)
            }
        }
        try {
            manager.resolveService(service, listener)
        } catch (failure: RuntimeException) {
            if (continuation.isActive) continuation.resume(null)
        }
    }

    private fun textAttributes(info: NsdServiceInfo): Map<String, String> =
        info.attributes.mapValues { (_, value) -> value?.toString(Charsets.UTF_8).orEmpty() }

    companion object {
        /** 与网关 MDNS_SERVICE_TYPE 一致（配对载荷里的 mdns_service_type）。 */
        const val SERVICE_TYPE = "_auri-gateway._tcp"
    }
}

/**
 * 把一条解析好的 mDNS 广告变成候选地址：必须有 IPv4 地址、端口合法、TXT 里的 gateway_id 与要找的相同。
 * 不满足任何一条就是 null（别的网关、残缺的广告、只有 IPv6 的广告都不是候选）。
 */
internal fun gatewayAddressFrom(host: String?, port: Int, attributes: Map<String, String>, gatewayId: String): GatewayAddress? {
    if (host == null || !GatewayAddress.isValidHost(host) || !GatewayAddress.isValidPort(port)) return null
    if (attributes["gateway_id"] != gatewayId) return null
    return GatewayAddress(host, port)
}
