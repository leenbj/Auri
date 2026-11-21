package app.auri.sync.testing

import app.auri.sync.pairing.GatewayIdentity
import app.auri.sync.policy.DataFlowPolicy
import app.auri.sync.policy.DataFlowState
import app.auri.sync.policy.StoredDataFlow
import app.auri.sync.policy.current
import app.auri.sync.policy.states
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * 内存里的 [DataFlowPolicy]。[unreadable] 为 true 时模拟“读不出来”：所有读取都按最保守的 [StoredDataFlow.Closed] 回答（真实实现也是这样）。
 * 默认是授权了、没开严格仅本地：测试替身的“授权”是在装配代码里显式给的（构造参数），不是引擎的默认；
 * 要测“没授权”“严格仅本地”“读不出来”的，构造时明说。
 *
 * 授权和真实实现一样是**给某一个网关的**（复评 R3）：[initial] 里 `gatewayTextGranted = true` 就是“授权给了 [gateway]”。
 * [gateway] 是界面上那个开关指的网关（真实应用里就是配对着的那个）：装配代码把它写成引擎实际连的网关（SyncRig、DispatchRig 都这么做），
 * 默认是 [SyncSamples.pairedGateway] 的身份；用真网关的测试在配对之后把它改成配对得到的那个。
 * 没有给通配的捷径：换了配对网关的测试必须明说“用户对新网关另行同意了”。
 */
class InMemoryDataFlowPolicy(
    initial: DataFlowState = GRANTED,
    gateway: GatewayIdentity? = SyncSamples.pairedGateway().identity,
) : DataFlowPolicy {
    /** 开关指的网关；见类说明。 */
    @Volatile
    var gateway: GatewayIdentity? = gateway

    private val state = MutableStateFlow(StoredDataFlow(grantedTo = gateway.takeIf { initial.gatewayTextGranted }, strictLocal = initial.strictLocal))

    @Volatile
    var unreadable: Boolean = false

    /** 设置被读过多少次：测试用来确认引擎真的每次发请求之前都问了。 */
    @Volatile
    var reads = 0
        private set

    /** 每次被读之前的钩子：测试用它在“引擎已经问过、还没发请求”的缝里改设置。 */
    @Volatile
    var onRead: (suspend () -> Unit)? = null

    override suspend fun stored(): StoredDataFlow {
        reads += 1
        onRead?.invoke()
        return if (unreadable) StoredDataFlow.Closed else state.value
    }

    override fun storedStates(): Flow<StoredDataFlow> = state.map { if (unreadable) StoredDataFlow.Closed else it }

    override suspend fun grantTo(gateway: GatewayIdentity) {
        state.value = state.value.copy(grantedTo = gateway)
    }

    override suspend fun revokeGrant() {
        state.value = state.value.copy(grantedTo = null)
    }

    override suspend fun forgetGrantExcept(gateway: GatewayIdentity?) {
        if (state.value.grantedTo != gateway) state.value = state.value.copy(grantedTo = null)
    }

    override suspend fun setStrictLocal(on: Boolean) {
        state.value = state.value.copy(strictLocal = on)
    }

    /** 方便测试：用户对 [gateway]（开关指的网关）打开或收回授权。 */
    suspend fun setGatewayTextGranted(granted: Boolean) {
        if (granted) grantTo(checkNotNull(gateway) { "这个替身没有网关，授权给不了谁" }) else revokeGrant()
    }

    /** 方便测试：对 [gateway]（开关指的网关）现在的设置，也就是界面上看到的那份。 */
    suspend fun current(): DataFlowState = current(gateway)

    /** 方便测试：对 [gateway] 的设置的变化。 */
    fun states(): Flow<DataFlowState> = states(flowOf(gateway))

    companion object {
        val GRANTED = DataFlowState(gatewayTextGranted = true, strictLocal = false)
    }
}
