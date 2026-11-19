package app.auri.sync.discovery

import app.auri.sync.SyncLog
import app.auri.sync.pairing.GatewayAddress
import kotlinx.coroutines.CancellationException

/**
 * 在局域网里用 mDNS（`_auri-gateway._tcp`）找网关。
 * mDNS 里谁都能发广告，所以结果只是“候选地址”：能不能信它，看握手时的公钥指纹，不看广告里写的 gateway_id。
 * [find] 的 gatewayId 只用来挑出 TXT 记录里同一个网关的广告，减少无谓的连接尝试。
 */
interface GatewayDiscovery {
    suspend fun find(gatewayId: String): List<GatewayAddress>
}

/** 候选地址的一次尝试：连上并完成要做的事（Done）、没连上（Unreachable）、对方的公钥不符（Mismatch）。 */
internal sealed interface Step<out T> {
    data class Done<T>(val value: T) : Step<T>

    data object Unreachable : Step<Nothing>

    data object Mismatch : Step<Nothing>
}

internal sealed interface Walk<out T> {
    data class Found<T>(val value: T) : Walk<T>

    /** 没有任何地址成功，而且至少有一个地址出示了不符的公钥。 */
    data object Mismatched : Walk<Nothing>

    data object NothingReachable : Walk<Nothing>
}

/**
 * 先试 [stored] 里的地址（按顺序），全都连不上才去 mDNS 找，找到的新地址（已经试过的跳过）再逐个试。
 * 公钥不符不会中断：某个地址上换成了别的设备，真正的网关可能在另一个地址。
 * 发现过程出任何异常都当作“什么也没找到”（系统 API 可能抛），取消则照常向上抛。
 */
internal suspend fun <T> walkAddresses(
    stored: List<GatewayAddress>,
    discovery: GatewayDiscovery,
    gatewayId: String,
    log: SyncLog,
    attempt: suspend (GatewayAddress) -> Step<T>,
): Walk<T> {
    var mismatched = false
    val tried = LinkedHashSet<GatewayAddress>()

    suspend fun tryAll(candidates: List<GatewayAddress>): Walk.Found<T>? {
        for (address in candidates) {
            if (!tried.add(address)) continue
            when (val step = attempt(address)) {
                is Step.Done -> return Walk.Found(step.value)
                Step.Mismatch -> mismatched = true
                Step.Unreachable -> Unit
            }
        }
        return null
    }

    tryAll(stored)?.let { return it }
    val discovered = try {
        discovery.find(gatewayId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        log.event("discovery.failed")
        emptyList()
    }
    log.event("discovery.found count=${discovered.size}")
    tryAll(discovered)?.let { return it }
    return if (mismatched) Walk.Mismatched else Walk.NothingReachable
}
