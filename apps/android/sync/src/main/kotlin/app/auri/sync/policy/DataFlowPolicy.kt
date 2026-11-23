package app.auri.sync.policy

import app.auri.sync.pairing.GatewayIdentity
import app.auri.sync.pairing.PairingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 文字能不能离开手机进网关：两道互相独立的开关，**任何一道不放行就一个字节也不发**（PRD SEC-002 的“授权分离”与“严格仅本地”统一门禁）。
 * 与“音频上传”“Agent 读取”“自动发送”各自独立：配对只说明手机认得这台网关，不说明用户同意把文字送过去。
 *
 * - [gatewayTextGranted]：用户明确授权“文字可以进我指定的网关”，**指定的那一个**：这是对某个网关（见 [StoredDataFlow.viewFor]）算出来的，
 *   换了配对的网关就不再算数，要用户对新网关另行同意（复评 R3）。默认关；读不出来、读到不认识的值都当关。
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

/**
 * 存下来的两道开关。授权记着它是给哪个网关的：[grantedTo] 为 null = 没授权。
 * 引擎、状态汇总、界面看到的是对某个网关的 [viewFor]，不是这份存档本身。
 */
data class StoredDataFlow(val grantedTo: GatewayIdentity?, val strictLocal: Boolean) {
    /** 对 [gateway] 来说现在的两道开关：授权只有给的恰好是它才算数；没有网关就谈不上授权。 */
    fun viewFor(gateway: GatewayIdentity?): DataFlowState =
        DataFlowState(gatewayTextGranted = gateway != null && grantedTo == gateway, strictLocal = strictLocal)

    companion object {
        /** 用户什么都没设置过。 */
        val Unset = StoredDataFlow(grantedTo = null, strictLocal = false)

        /** 读不出来：最保守的那一种——不授权、严格仅本地。 */
        val Closed = StoredDataFlow(grantedTo = null, strictLocal = true)
    }
}

/** 持久化的两道开关加上改它们的入口。引擎在每一次可能发请求的地方先问它（见 SyncEngine，问的是“对我要联系的这个网关”）。 */
interface DataFlowPolicy {
    /**
     * 设置的世代（再评 S3，见 [PolicyGeneration]）：每次改设置开始前与落定后各加一，读它不挂起。
     * 引擎问放行之前先记下它、请求交给 HTTP 客户端的前一刻再比一次：变过就什么都不发。实现必须保证“改了设置之后再读到的世代一定变过”，
     * 并且一次改动从开始到落定的整段时间里，每次读到的世代都不同于以后任何一次读到的（第四轮 1：写还排在队里时问的放行读到的是旧授权，
     * 不能因为“世代恰好没再变”在写提交之后放行）。
     */
    val generation: Long

    /**
     * 存档现在的样子。**读取出任何故障都按 [StoredDataFlow.Closed] 回答，绝不抛出、绝不当成放行**。
     */
    suspend fun stored(): StoredDataFlow

    /** 存档的变化（含订阅那一刻的当前值）；读取故障时同样发出 [StoredDataFlow.Closed]，恢复后发出真实值。 */
    fun storedStates(): Flow<StoredDataFlow>

    /** 用户在界面上明确同意“文字可以进 [gateway]”。同时只有一个网关有授权：换一个网关授权，原来那个的就没有了。 */
    suspend fun grantTo(gateway: GatewayIdentity)

    /** 用户收回“文字可以进网关”（不论原来授权给了谁）。 */
    suspend fun revokeGrant()

    /**
     * 配对换了网关：授权不是给 [gateway] 的就作废（[gateway] 为 null = 没有配对，任何授权都作废）。给 [gateway] 的授权原样保留（同一台网关重新配对不用重新同意）。
     * 这是主动清理；就算它没跑成，读的时候授权也只对给出它的网关算数（[StoredDataFlow.viewFor]）。
     */
    suspend fun forgetGrantExcept(gateway: GatewayIdentity?)

    /** 用户打开或关闭“严格仅本地”。 */
    suspend fun setStrictLocal(on: Boolean)
}

/** 对 [gateway]（引擎要联系的、或界面上配对着的那个网关）现在的设置。读不出来按 [DataFlowState.Closed]。 */
suspend fun DataFlowPolicy.current(gateway: GatewayIdentity?): DataFlowState = stored().viewFor(gateway)

/** 对 [gateway] 流里当前网关的设置的变化（存档变了、或配对的网关换了，都重新算）。 */
fun DataFlowPolicy.states(gateway: Flow<GatewayIdentity?>): Flow<DataFlowState> =
    combine(storedStates(), gateway) { stored, current -> stored.viewFor(current) }.distinctUntilChanged()

/**
 * 启动时对账（复评 R3）：授权只留给现在配对着的那个网关。换配对时的主动清理（PairingService 的 beforeSaving）万一没跑成，这里补上。
 * 配对记录读不出来（[PairingState.Lost]）不等于没配对，不动授权：它在读的时候本来就只对给出它的网关算数。
 */
suspend fun DataFlowPolicy.reconcile(pairing: PairingState) = when (pairing) {
    is PairingState.Paired -> forgetGrantExcept(pairing.gateway.identity)
    PairingState.NotPaired -> forgetGrantExcept(null)
    PairingState.Lost -> Unit
}

/**
 * 用户在界面上拨“允许把文字发送到我配对的网关”（复评 R3）。打开 = 同意发给 [pairing] 里现在配对着的**这一个**网关（之后换了配对，这份同意就不再算数）；
 * 没有配对着的网关就没有“我指定的网关”可同意，什么也不记并返回 false。收回不看配对：不论原来给了谁，都收回。
 */
suspend fun DataFlowPolicy.setGrantFor(pairing: PairingState, granted: Boolean): Boolean {
    if (!granted) {
        revokeGrant()
        return true
    }
    val gateway = (pairing as? PairingState.Paired)?.gateway ?: return false
    grantTo(gateway.identity)
    return true
}
