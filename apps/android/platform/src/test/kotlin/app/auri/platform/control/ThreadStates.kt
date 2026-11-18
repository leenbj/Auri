package app.auri.platform.control

import java.util.concurrent.TimeUnit

/**
 * 等线程要么卡在监视器上（BLOCKED：被锁挡住了），要么已经跑完（TERMINATED：越过去了）。
 * 并发测试用它判断“第二个线程有没有被锁挡住”，不靠睡一段时间去猜线程调度。最多等 5 秒，超时就返回，由调用方断言线程当时的状态。
 */
internal fun awaitBlockedOrDone(thread: Thread) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (thread.state != Thread.State.BLOCKED && thread.state != Thread.State.TERMINATED && System.nanoTime() < deadline) {
        Thread.yield()
    }
}
