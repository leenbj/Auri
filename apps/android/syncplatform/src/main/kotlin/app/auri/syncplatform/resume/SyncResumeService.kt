package app.auri.syncplatform.resume

import android.app.job.JobParameters
import android.app.job.JobService
import app.auri.syncplatform.SyncRuntimeHost
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 系统因为“有网络了”把应用叫醒时跑的服务：跑**一轮**有时限的同步（SyncRuntime.resumePass），然后告诉系统还要不要再叫醒。
 * 进程是被系统拉起来的话，Application.onCreate 已经先装配好运行时并启动了同步循环；这一轮与循环自己的那一轮由引擎的锁串行。
 *
 * 收尾（再评 S8）：“还要不要再叫醒”是在 WakeGate 的锁里现算的（见 SyncRuntime.resumePass）——没活时当场同步取消系统里的叫醒，有活就返回 true、
 * 由下面的 jobFinished(reschedule = true) 请系统稍后再叫。jobFinished 本身是异步的：不经这把锁，这一轮判断“没活了”之后用户确认的预览会因为
 * getPendingJob 还看得见这个正在收尾的任务而没有登记叫醒，随后叫醒被这一轮自己带走。取消之后系统会回调 onStopJob，这里照常摘掉、不再 jobFinished。
 */
class SyncResumeService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val runtime = (application as? SyncRuntimeHost)?.syncRuntime ?: return false // 没有宿主就没有运行时：不重排，下次启动会装配
        // 先登记再开跑：一轮跑得再快，收尾时也一定找得到自己
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var reschedule = true // 没能得出结论（意外、被取消）就请系统稍后再来
            try {
                reschedule = runtime.resumePass()
            } finally {
                // 被系统叫停时 onStopJob 已经把它摘掉了，那时不能再 jobFinished
                if (running.remove(params.jobId) != null) jobFinished(params, reschedule)
            }
        }
        running[params.jobId] = job
        job.start()
        return true
    }

    /** 系统要停掉这一轮（网络没了、超过系统给的时间）：取消它，并请系统稍后再叫醒（没发完的任务还在）。 */
    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
