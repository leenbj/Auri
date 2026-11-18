package app.auri.capture.coordinator

/**
 * Quarantine：路由确认之前收到的音频帧的内存隔离区（FR-REC-002）。
 * 它只存在于内存里，不碰任何存储；只有一个出口——[discard]。路由确认成功时同样丢弃：
 * 系统的确认只说明“现在”的路由是蓝牙耳机，说明不了这些更早的帧是从哪里采到的，
 * 把它们提升进 Record 就可能让手机麦克风的声音混进来。代价是按下开始到路由确认之间的头一小段话不会被保留。
 * 超过 [maxSamples] 时丢最旧的，内存占用有上限。
 */
internal class Quarantine(private val maxSamples: Long) {
    private val held = ArrayDeque<ShortArray>()

    /** 当前持有的采样数。 */
    var size: Long = 0
        private set

    /** 因超出上限而丢掉的累计采样数。 */
    var overflowed: Long = 0
        private set

    fun offer(samples: ShortArray) {
        held.addLast(samples)
        size += samples.size
        while (size > maxSamples && held.isNotEmpty()) {
            val oldest = held.removeFirst()
            size -= oldest.size
            overflowed += oldest.size
        }
    }

    /** 丢弃全部内容，返回丢掉的采样数。内容从不被读出。 */
    fun discard(): Long {
        val discarded = size
        held.clear()
        size = 0
        return discarded
    }
}
