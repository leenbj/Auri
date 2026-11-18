package app.auri.capture.scheduler

import java.util.concurrent.Executors
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * 转录引擎调用用的专用线程：一条守护线程，Java 优先级是最低的（Android 上映射成 nice 19）。
 * 原生推理线程由这条线程创建，会继承它的调度优先级；录音路径（采集线程、协调器）永远不与它抢 CPU 的先后。
 * 引擎调用是阻塞的、停不下来的（`cancel_support = none`），所以必须放在它自己的线程上：绝不能占用协调器或调度器邮箱的线程。
 * 与线程优先级无关的“不丢帧”由结构保证（录音与转录没有共享的线程与锁），见 TranscriptionDoesNotDropFramesTest。
 *
 * 用完要 [ExecutorCoroutineDispatcher.close]。
 */
fun lowPriorityTranscriptionDispatcher(name: String = "auri-transcribe"): ExecutorCoroutineDispatcher =
    Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, name).apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }.asCoroutineDispatcher()
