package app.auri.android.destinations

import app.auri.sync.SyncLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow

/** 读失败之后第一次重试前等多久；之后每次翻倍，到 [READ_RETRY_MAX_MS] 为止；读到一次就从头算。 */
internal const val READ_RETRY_BASE_MS = 2_000L
internal const val READ_RETRY_MAX_MS = 30_000L

/**
 * 读发送账（SQLite）的流在上游抛出时，不让异常逃出视图模型的作用域（评审 H4）。视图模型的作用域是 SupervisorJob 加线程池、没有异常处理器：
 * 急切的 stateIn 里逃出来的异常会一路走到线程的默认处理器，在 Android 上就是整个进程退出——包括正在录音的前台服务。
 *
 * 这里把它包住：记一行日志（只有事件名与异常类名：不含异常信息，更没有正文、编号、路径），调 [onFailure] 让调用方发出“读不到”的状态
 * （[onFailure] 拿到最近一次读到的值，没有就是 null；界面据此如实说“读不到、会重试”，而不是一直空白或一直显示旧内容装作没事），
 * 等一会儿再重新订阅上游重读；等待时间翻倍增长到 [READ_RETRY_MAX_MS]，读到一次就从头算，永不放弃。
 *
 * 只包上游：下游（收集者）抛出的异常、取消，以及 Error（比如内存耗尽）照常往外传。上游正常结束就正常结束。
 */
internal fun <T> Flow<T>.containReadFailures(
    name: String,
    log: SyncLog,
    onFailure: suspend FlowCollector<T>.(last: T?) -> Unit = {},
): Flow<T> = flow {
    var last: T? = null
    var failures = 0
    while (true) {
        var failure: Exception? = null
        this@containReadFailures
            .catch { cause ->
                if (cause is CancellationException || cause !is Exception) throw cause
                failure = cause
            }
            .collect { value ->
                last = value
                failures = 0
                emit(value)
            }
        val caught = failure ?: return@flow
        log.event("$name.read.failed ${caught::class.simpleName}")
        onFailure(last)
        delay((READ_RETRY_BASE_MS shl failures.coerceAtMost(MAX_DOUBLINGS)).coerceAtMost(READ_RETRY_MAX_MS))
        failures += 1
    }
}

private const val MAX_DOUBLINGS = 5
