package app.auri.sync.testing

import app.auri.sync.policy.DataFlowPolicy
import app.auri.sync.policy.DataFlowState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 内存里的 [DataFlowPolicy]。[unreadable] 为 true 时模拟“读不出来”：所有读取都按最保守的 [DataFlowState.Closed] 回答（真实实现也是这样）。
 * 默认是授权了、没开严格仅本地：测试替身的“授权”是在装配代码里显式给的（构造参数），不是引擎的默认；
 * 要测“没授权”“严格仅本地”“读不出来”的，构造时明说。
 */
class InMemoryDataFlowPolicy(initial: DataFlowState = GRANTED) : DataFlowPolicy {
    private val stored = MutableStateFlow(initial)

    @Volatile
    var unreadable: Boolean = false

    /** 设置被读过多少次：测试用来确认引擎真的每次发请求之前都问了。 */
    @Volatile
    var reads = 0
        private set

    /** 每次被读之前的钩子：测试用它在“引擎已经问过、还没发请求”的缝里改设置。 */
    @Volatile
    var onRead: (suspend () -> Unit)? = null

    override suspend fun current(): DataFlowState {
        reads += 1
        onRead?.invoke()
        return if (unreadable) DataFlowState.Closed else stored.value
    }

    override fun states(): Flow<DataFlowState> = stored.map { if (unreadable) DataFlowState.Closed else it }

    override suspend fun setGatewayTextGranted(granted: Boolean) {
        stored.value = stored.value.copy(gatewayTextGranted = granted)
    }

    override suspend fun setStrictLocal(on: Boolean) {
        stored.value = stored.value.copy(strictLocal = on)
    }

    companion object {
        val GRANTED = DataFlowState(gatewayTextGranted = true, strictLocal = false)
    }
}
