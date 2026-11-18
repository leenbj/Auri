package app.auri.sync.testing

import app.auri.sync.loop.NetworkSignal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * 测试里的网络可用信号：手动开关，模拟 Wi-Fi 断开与恢复。
 *
 * 和真实的系统回调一样不在源头去重（StateFlow 会把“可用→可用”吞掉，表达不了“默认网络换了一个”）：
 * [set] 只在值真的变了时才发信号，[switchToAnotherNetwork] 在已经可用时再发一次“可用”。新订阅者先拿到当前值。
 */
class FakeNetwork(initiallyAvailable: Boolean = true) : NetworkSignal {
    private val events = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = 64)
    private var current = initiallyAvailable

    init {
        events.tryEmit(initiallyAvailable)
    }

    override val available: Flow<Boolean> = events

    fun set(available: Boolean) {
        if (available == current) return
        current = available
        events.tryEmit(available)
    }

    /** 默认网络换成了另一个可用的网络（Wi-Fi 断开后落到移动数据，或 Wi-Fi 回来又换回去）：信号仍是“可用”，但这是一次新的网络事件。 */
    fun switchToAnotherNetwork() {
        check(current) { "没有网络时谈不上换网络" }
        events.tryEmit(true)
    }
}
