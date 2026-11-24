package app.auri.sync.discovery

import app.auri.sync.policy.RequestGate
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 一次解析的结果；包一层是为了分清“解析了、没成”（[value] 为 null）和“门不放行、根本没解析”（整个 [gated] 返回 null）。 */
private class Resolved<R : Any>(val value: R?)

/** 哨兵问一次门的间隔：授权被收回之后，系统的发现最多还多发这么久的多播查询。 */
const val RESOLVE_WATCH_MS = 250L

/**
 * mDNS 发现里“一个服务一个服务地解析”那一段（第四轮 F7）：[services] 是系统发现到的服务，[resolve] 解析一个（`NsdManager.resolveService`，
 * 会向局域网发请求），[accept] 收下解析成功的。
 *
 * 以前整个发现只在开头过一次门（第三轮 S12），4 秒监听窗口里门一过，后面来的每个服务想解析就解析、授权被收回了也不停。现在：
 * - 每个解析各过一次 [gate]（请求交出去的前一刻）；门不放行就**停**——不解析这一个、也不再听后面的，取消 [services]
 *   （真实现里 `awaitClose` 随之 `stopServiceDiscovery`，系统不再发多播查询）。解析途中授权被收回，已经在做的照常做完，之后的一个也不解析。
 * - 没有新服务来的时候授权被收回，等不到下一个服务触发检查：哨兵每隔 [watchMs] 问一次门，关上了就停，不等下一个服务、不等监听窗口到期。
 * - 调用方被取消（监听窗口到期）：两边一起停。
 *
 * 门不放行不是错误：正常返回，调用方（[walkAddresses]）自己会再问一次门，把这一轮按“被拦下”处理。
 */
suspend fun <S, R : Any> resolveWhileOpen(
    services: Flow<S>,
    gate: RequestGate,
    watchMs: Long = RESOLVE_WATCH_MS,
    resolve: suspend (S) -> R?,
    accept: (R) -> Unit,
) = coroutineScope {
    val collecting = launch {
        services.transformWhile { service ->
            val outcome = gate.gated { Resolved(resolve(service)) } ?: return@transformWhile false
            outcome.value?.let { emit(it) }
            true
        }.collect(accept)
    }
    val watching = launch {
        while (collecting.isActive) {
            delay(watchMs)
            if (gate.gated { Unit } == null) collecting.cancel()
        }
    }
    collecting.join()
    watching.cancel()
}
