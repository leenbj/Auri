package app.auri.sync.testing

import app.auri.sync.loop.NetworkSignal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** 测试里的网络可用信号：手动开关，模拟 Wi-Fi 断开与恢复。 */
class FakeNetwork(initiallyAvailable: Boolean = true) : NetworkSignal {
    private val state = MutableStateFlow(initiallyAvailable)

    override val available: Flow<Boolean> = state

    fun set(available: Boolean) {
        state.value = available
    }
}
