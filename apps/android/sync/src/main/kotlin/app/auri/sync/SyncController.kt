package app.auri.sync

import app.auri.sync.pairing.PairingOutcome
import app.auri.sync.pairing.PairingPayload
import app.auri.sync.status.SyncStatus
import kotlinx.coroutines.flow.Flow

/**
 * 界面（视图模型）对配对与同步的全部操作：读同步状态、配对、现在同步。
 * 生产实现是 :syncplatform 的 SyncRuntime；视图模型的测试里用假的，这样测试不需要 Android、网络与数据库。
 */
interface SyncController {
    /** 同步状态汇总：配对状态与任务表任何一边变了都会发出新的一份（相同的不重复发）。 */
    val status: Flow<SyncStatus>

    /** 用二维码里的载荷配对。网关的回答之外的任何失败都不抛出，都在 [PairingOutcome] 里。 */
    suspend fun pair(payload: PairingPayload): PairingOutcome

    /** 立刻跑一轮同步（回到前台、用户点了“现在同步”）。 */
    fun syncNow()

    /** 用户明确打开（true）或收回（false）“文字可以进我的网关”。默认关；这是独立于配对的一道授权（评审 B1）。 */
    suspend fun setGatewayTextGranted(granted: Boolean)

    /** 用户打开或关闭“严格仅本地”：开着时文字一个字节也不会离开手机，压过上面的授权。 */
    suspend fun setStrictLocal(on: Boolean)
}
