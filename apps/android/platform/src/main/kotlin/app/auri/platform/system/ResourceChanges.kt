package app.auri.platform.system

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/** 每项注册成功后立即保存清理句柄，后续注册失败也会释放它。 */
internal fun resourceChanges(
    thermal: (() -> Unit) -> AutoCloseable,
    battery: (() -> Unit) -> AutoCloseable,
): Flow<Unit> = callbackFlow {
    var thermalRegistration: AutoCloseable? = null
    var batteryRegistration: AutoCloseable? = null
    try {
        thermalRegistration = thermal { trySend(Unit) }
        batteryRegistration = battery { trySend(Unit) }
        awaitClose()
    } finally {
        runCatching { batteryRegistration?.close() }
        runCatching { thermalRegistration?.close() }
    }
}.buffer(Channel.CONFLATED)
