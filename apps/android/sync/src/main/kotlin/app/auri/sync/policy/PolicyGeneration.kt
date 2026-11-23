package app.auri.sync.policy

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * 授权设置的世代（再评 S3，第四轮 1 补上写窗口那道缝）：每次改设置（授权、收回、换网关、严格仅本地）**开始前和落定后**各加一，读它不挂起、不碰存储。
 *
 * 引擎问放行是一次读存档的挂起；“问完”到“请求交给 HTTP 客户端”之间还有别的本地操作，用户随时可能收回授权。
 * 所以每次问放行之前先记下世代，每个请求交出去的前一刻再比一次：世代变过，就说明这中间有人动了设置——什么都不发，重新来过。
 * 这比再多问几次放行可靠：再问一次也只是“问完就算”，缝只是变窄、没有消失。
 *
 * 为什么前后各加一：前一次让“已经开始改、还没落定”的这段时间里所有请求前的比对都失败（宁可多拦一次，不放过一次）；
 * 后一次让这段时间里开始问放行的（可能读到的还是旧值）事后也比对失败。落定的那一次由实现放在**存储的写之后、同一个存储线程上**，
 * 这样之后的任何读都排在它后面。成败都算落定：写失败也可能已经写了一半。
 *
 * **写窗口里的世代不是计数，是一次性的章（第四轮 1）**：光靠前后各加一，窗口里读到的世代与窗口里读到的授权可以对不上——
 * 写还排在队里时问的放行读到的是**旧**授权，记下的却是**新**世代（计数已经加过一）；写提交之后、落定之前，请求交出去的前一刻比的世代恰好没变，
 * 收回已经提交，请求照样出去。所以有任何一次写开着（开始了、没落定）的时候，读世代每次都得到一个**新的负数**（-1、-2……，只减不增，
 * 永不重复，也不会等于任何落定后的世代——那些都是非负数）：窗口里记下的章，窗口里交接比不上，窗口关了之后同样比不上；
 * 窗口里开始问的放行因此一概作废，等窗口关上之后重新问。开着的写按个数记，两次写重叠要都落定才算关上。
 * 窗口是“按实例”的：只有改这份设置的写会开它，别的设置、别的实例不受影响。
 *
 * 请求前的比较与启动、世代变更、世代读取共用同一把锁，避免另一个线程在两者之间完成收回；请求第一次挂起后不持锁等待网络。
 */
class PolicyGeneration {
    private val counter = AtomicLong()

    // 下面两个只在 handoffLock 里读写
    private var writing = 0
    private var windowReads = 0L

    /** 现在的世代；有写开着时每次读都是一个新的负数（见类说明）。 */
    val value: Long
        get() = synchronized(handoffLock) { if (writing > 0) --windowReads else counter.get() }

    /** 要开始改设置了：在动存储之前调用。 */
    fun changing() {
        synchronized(handoffLock) {
            counter.incrementAndGet()
            writing++
        }
    }

    /** 设置落定了（改成了、或失败了）：在存储的写之后调用。 */
    fun changed() {
        synchronized(handoffLock) {
            counter.incrementAndGet()
            if (writing > 0) writing--
        }
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

    /**
     * 一次写的凭据：存储的写要挂起、要换线程的地方（SQLite）用它，**保证窗口至多开一次、至多关一次**。
     * 写块有可能根本没跑起来（调用方在块开始前被取消、拿不到数据库连接、调度器拒绝）：那种情况下 `finally` 里的落定
     * 把窗口关上——不然开着的窗口永远关不上，之后每个请求都被拦（宁可拦也不放，但不能永远拦）。
     */
    fun write() = Write()

    inner class Write internal constructor() {
        private val state = AtomicInteger(NEW)

        /** 开窗口（幂等）；已经落定的凭据不会再开。 */
        fun changing() {
            if (state.compareAndSet(NEW, OPEN)) this@PolicyGeneration.changing()
        }

        /** 关窗口（幂等）：数据库线程上的 `finally` 与调用方外层的 `finally` 都可以调，只有第一次算数。 */
        fun changed() {
            if (state.compareAndSet(OPEN, SETTLED)) this@PolicyGeneration.changed() else state.compareAndSet(NEW, SETTLED)
        }
    }

    companion object {
        private const val NEW = 0
        private const val OPEN = 1
        private const val SETTLED = 2

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
