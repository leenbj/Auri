package app.auri.sync.resume

import app.auri.sync.SyncLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 系统把应用叫醒之后要跑的那一轮：等启动做完（配对记录读出来）、跑一轮同步、告诉系统“还有没有活”。
 * 返回值就是 JobService.jobFinished 的“请稍后再叫醒我”：还有活（或这一轮没能得出结论）为 true。
 * [stillNeeded] 必须在 [WakeGate.finishing] 里做（再评 S8）：收尾判断与用户确认的“登记 + 提交”在同一把锁里，没活时同步取消系统里的叫醒——
 * 否则这一轮判断“没活了”之后、jobFinished 生效之前用户确认的预览，会因为系统里“还有一个”而没有登记叫醒，随后这个叫醒被这一轮自己带走。
 *
 * 一轮有时限 [limitMs]：不能一直占着系统给后台任务的执行时间；超时就取消这一轮（发到一半的请求按“结果不确定”留给下一轮先问）。
 * 被系统叫停（外部取消）时取消照常向上传，不再做收尾判断。日志只有事件名与异常类名。
 */
class ResumeRunner(
    private val ready: suspend () -> Unit,
    private val runPass: suspend () -> Unit,
    private val stillNeeded: suspend () -> Boolean,
    private val limitMs: Long = DEFAULT_LIMIT_MS,
    private val log: SyncLog = SyncLog.None,
) {
    suspend fun run(): Boolean {
        try {
            withTimeoutOrNull(limitMs) {
                ready()
                runPass()
            } ?: log.event("resume.timeout")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.event("resume.failed ${failure::class.simpleName}")
            return true // 没得出结论：不能当作没活了
        }
        return try {
            stillNeeded()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.event("resume.failed ${failure::class.simpleName}")
            true
        }
    }

    companion object {
        /** JobScheduler 给一个后台任务的执行时间是十分钟量级；留足余量，也够把积压的任务发完。 */
        const val DEFAULT_LIMIT_MS = 5 * 60_000L
    }
}
