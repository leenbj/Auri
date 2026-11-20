package app.auri.sync.policy

import kotlinx.coroutines.flow.Flow

/**
 * 文字能不能离开手机进网关：两道互相独立的开关，**任何一道不放行就一个字节也不发**（PRD SEC-002 的“授权分离”与“严格仅本地”统一门禁）。
 * 与“音频上传”“Agent 读取”“自动发送”各自独立：配对只说明手机认得这台网关，不说明用户同意把文字送过去。
 *
 * - [gatewayTextGranted]：用户明确授权“文字可以进我指定的网关”。默认关；读不出来、读到不认识的值都当关。
 * - [strictLocal]：严格仅本地。统一的策略门禁，开着就压过上面的授权：发送、重试、预览确认、被系统叫醒后的补发，所有提交路径都不发，
 *   也不会改走云端或别的通道（V1 本来就只有本地转录，ADR-0019）。读不出来、读到不认识的值都当开。
 */
data class DataFlowState(val gatewayTextGranted: Boolean, val strictLocal: Boolean) {
    /** 只有授权了、并且没开严格仅本地，文字才可以进网关。 */
    val textMayEnterGateway: Boolean get() = gatewayTextGranted && !strictLocal

    companion object {
        /** 用户什么都没设置过：没有授权；严格仅本地也没开（它是用户主动收紧的开关）。所以此时文字照样不会发。 */
        val Unset = DataFlowState(gatewayTextGranted = false, strictLocal = false)

        /** 读不出来：最保守的那一种——不授权、严格仅本地。 */
        val Closed = DataFlowState(gatewayTextGranted = false, strictLocal = true)
    }
}

/** 持久化的 [DataFlowState] 加上改它的入口。引擎在每一次可能发请求的地方先问它（见 SyncEngine）。 */
interface DataFlowPolicy {
    /**
     * 现在的设置。**读取出任何故障都按 [DataFlowState.Closed] 回答，绝不抛出、绝不当成放行**。
     */
    suspend fun current(): DataFlowState

    /** 设置的变化（含订阅那一刻的当前值）；读取故障时同样发出 [DataFlowState.Closed]，恢复后发出真实值。 */
    fun states(): Flow<DataFlowState>

    /** 用户在界面上明确打开（true）或收回（false）“文字可以进网关”。 */
    suspend fun setGatewayTextGranted(granted: Boolean)

    /** 用户打开或关闭“严格仅本地”。 */
    suspend fun setStrictLocal(on: Boolean)
}
