package app.auri.capture.scheduler

/**
 * 手机此刻的几项读数。平台层（`:platform` 的 AndroidResourceMonitor）负责从系统读出来，这里只做判断，所以规则能在 JVM 里测。
 * 读不出来的项由平台层决定怎么办（见 [ResourceMonitor]：监视器抛异常时调度器按“可用”处理，不让一个读不出来的传感器永远挡住转录；
 * 引擎自己的失败路径兜底，SchedulerWaitingTest 钉住这一条）。
 */
data class ResourceReadings(
    /** `PowerManager.getCurrentThermalStatus()`：0 无、1 轻微、2 中等、3 严重、4 危急、5 紧急、6 即将关机。 */
    val thermalStatus: Int,
    /** 省电模式（`PowerManager.isPowerSaveMode()`）。 */
    val batterySaver: Boolean,
    /** `ActivityManager.MemoryInfo.availMem`。 */
    val availableMemoryBytes: Long,
    /** `ActivityManager.MemoryInfo.lowMemory`：系统自己认为内存紧张。 */
    val systemLowMemory: Boolean,
    /** 应用私有目录所在分区的可用空间。 */
    val freeStorageBytes: Long,
)

/**
 * 阈值是我选的保守初值，**没有真机标定**（真机温度、内存的实测是 NOT_RUN，步骤见 execution/acceptance/android-models/README.md）：
 *  - 温度：严重（3）及以上才等。中等（2）只表示“有点热、开始降频”，这时再等会让一部热一点的手机永远转不了；
 *  - 省电模式：用户明确要省电，所以开着就等，关掉或充电（系统会自动关省电）后继续；
 *  - 内存：可用不足 800 MiB（SenseVoice int8 的权重 239 MB，加上推理的工作内存）或系统自报内存紧张；
 *  - 存储：可用不足 100 MiB（转录只写几 KB 文字，但空间见底时数据库写入会失败，Revision 会丢）。
 */
data class ResourceThresholds(
    val thermalBlockAt: Int = THERMAL_SEVERE,
    val minAvailableMemoryBytes: Long = 800L * 1024 * 1024,
    val minFreeStorageBytes: Long = 100L * 1024 * 1024,
) {
    companion object {
        const val THERMAL_SEVERE = 3
    }
}

object ResourcePolicy {
    /**
     * 多项同时不满足时只报一个原因，按这个顺序：温度、省电模式、内存、存储。
     * （用户最能自己处理的放后面：存储可以腾空间；温度与省电只能等或关掉省电模式。）
     */
    fun evaluate(readings: ResourceReadings, thresholds: ResourceThresholds = ResourceThresholds()): ResourceStatus = when {
        readings.thermalStatus >= thresholds.thermalBlockAt -> ResourceStatus.Low(ResourceReason.THERMAL)
        readings.batterySaver -> ResourceStatus.Low(ResourceReason.BATTERY_SAVER)
        readings.systemLowMemory || readings.availableMemoryBytes < thresholds.minAvailableMemoryBytes ->
            ResourceStatus.Low(ResourceReason.LOW_MEMORY)
        readings.freeStorageBytes < thresholds.minFreeStorageBytes -> ResourceStatus.Low(ResourceReason.LOW_STORAGE)
        else -> ResourceStatus.Ok
    }
}
