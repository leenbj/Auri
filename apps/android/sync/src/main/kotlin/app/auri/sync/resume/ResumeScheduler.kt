package app.auri.sync.resume

/**
 * 平台的“稍后把应用叫醒”：进程被系统杀掉之后，靠它在有网络时把没发完的任务接回来（Android 上是 JobScheduler，见 :syncplatform）。
 * 这里只有一个叫醒：有就是“还有活”，没有就是“没活了”。
 */
interface ResumeScheduler {
    /** 确保系统里有一个待执行的叫醒。已经有就什么也不做（重复登记会打断正在执行的那一个）。 */
    fun ensureScheduled()

    /** 取消待执行的叫醒；没有就什么也不做。 */
    fun cancel()

    companion object {
        val None: ResumeScheduler = object : ResumeScheduler {
            override fun ensureScheduled() = Unit

            override fun cancel() = Unit
        }
    }
}
