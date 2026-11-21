package app.auri.syncplatform.resume

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import app.auri.sync.resume.ResumeScheduler

/**
 * [ResumeScheduler] 的 Android 实现：系统的 JobScheduler（平台自带，没有新增依赖）。
 * 登记的是一个跨重启保留（persisted）、要求有网络的叫醒；有网络时系统把应用进程拉起来，由 [SyncResumeService] 跑一轮同步。
 *
 * 限制（写进验收 README）：NETWORK_TYPE_ANY 要求系统判定“可以上互联网”的网络，只有局域网、没有互联网的 Wi-Fi 不满足它——
 * 那种情况下进程被杀之后要等用户再打开应用（启动时第一轮同步）才会接着发。进程活着时由 SyncLoop 负责，不受这个限制。
 */
class JobSchedulerResume(context: Context) : ResumeScheduler {
    private val context = context.applicationContext
    private val scheduler: JobScheduler get() = context.getSystemService(JobScheduler::class.java)

    override fun ensureScheduled() {
        // 已经有一个（含正在执行的）就不再登记：对同一个编号再次登记会取消正在执行的那一个——它正跑着我们这一轮同步。
        // 正在收尾的那一个（jobFinished 是异步的）这里也看得见，所以收尾判断不能在门外做：见 WakeGate.finishing（再评 S8）
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, SyncResumeService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true) // 需要 RECEIVE_BOOT_COMPLETED（清单里声明了）
            .build()
        check(scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) { "JobScheduler 拒绝了登记" }
    }

    override fun cancel() = scheduler.cancel(JOB_ID)

    companion object {
        /** 本应用里唯一的 JobScheduler 任务编号（Ticket 21）。 */
        const val JOB_ID = 2101
    }
}
