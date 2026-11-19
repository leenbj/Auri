package app.auri.sync.loop

import app.auri.capture.coordinator.Clock
import app.auri.sync.SyncLog
import app.auri.sync.engine.RetryPolicy
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 一次订阅至少活这么久再出故障，就不算“连续失败”：重新订阅的退避从头算。 */
internal const val STABLE_MS = 60_000L

/**
 * 一个触发来源（任务表的变化、网络信号、叫醒计划……）出了故障只记日志，不拖垮别的来源；
 * 然后按有界退避重新订阅：本机数据库暂时读不出来、系统服务暂时不可用都是暂时的，不该让这个来源永远失聪。
 * 日志只有来源名与异常类名。来源自己正常结束就不再重启；上一次订阅活得够久（至少 [STABLE_MS]）再出故障，退避从头算。
 */
internal fun CoroutineScope.restartingProducer(name: String, log: SyncLog, clock: Clock, retry: RetryPolicy, body: suspend () -> Unit): Job = launch {
    var failures = 0
    while (true) {
        val startedAt = clock.now()
        try {
            body()
            return@launch
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.event("loop.$name.failed ${failure::class.simpleName}")
        }
        if (Duration.between(startedAt, clock.now()).toMillis() >= STABLE_MS) failures = 0
        failures += 1
        delay(retry.delayMs(failures))
    }
}
