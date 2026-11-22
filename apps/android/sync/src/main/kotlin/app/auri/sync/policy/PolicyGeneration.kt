package app.auri.sync.policy

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * 授权设置的世代（再评 S3）：每次改设置（授权、收回、换网关、严格仅本地）**开始前和落定后**各加一，读它不挂起、不碰存储。
 *
 * 引擎问放行是一次读存档的挂起；“问完”到“请求交给 HTTP 客户端”之间还有别的本地操作，用户随时可能收回授权。
 * 所以每次问放行之前先记下世代，每个请求交出去的前一刻再比一次：世代变过，就说明这中间有人动了设置——什么都不发，重新来过。
 * 这比再多问几次放行可靠：再问一次也只是“问完就算”，缝只是变窄、没有消失。
 *
 * 为什么前后各加一：前一次让“已经开始改、还没落定”的这段时间里所有请求前的比对都失败（宁可多拦一次，不放过一次）；
 * 后一次让这段时间里开始问放行的（可能读到的还是旧值）事后也比对失败。落定的那一次由实现放在**存储的写之后、同一个存储线程上**，
 * 这样之后的任何读都排在它后面。成败都算落定：写失败也可能已经写了一半。
 *
 * 请求前的比较与启动和世代变更共用锁，避免另一个线程在两者之间完成收回；请求第一次挂起后不持锁等待网络。
 */
class PolicyGeneration {
    private val counter = AtomicLong()

    /** 现在的世代。 */
    val value: Long get() = counter.get()

    /** 要开始改设置了：在动存储之前调用。 */
    fun changing() {
        synchronized(handoffLock) { counter.incrementAndGet() }
    }

    /** 设置落定了（改成了、或失败了）：在存储的写之后调用。 */
    fun changed() {
        synchronized(handoffLock) { counter.incrementAndGet() }
    }

    /** 一次完整的改动：前后各加一，抛了也算落定。存储写本身是同步的地方（内存实现）用它。 */
    inline fun <T> around(change: () -> T): T {
        changing()
        try {
            return change()
        } finally {
            changed()
        }
    }

    companion object {
        // Policy wrappers and their underlying generation must use the same lock.
        private val handoffLock = Any()

        suspend fun <T : Any> handoff(permitted: () -> Boolean, request: suspend () -> T): T? = coroutineScope {
            val started = synchronized(handoffLock) {
                if (permitted()) async(start = CoroutineStart.UNDISPATCHED) { request() } else null
            }
            started?.await()
        }
    }
}
