package app.auri.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.auri.android.sync.PairingProblem
import app.auri.android.sync.PairingProgress
import app.auri.android.sync.SyncUiState
import app.auri.sync.status.SyncCounts
import app.auri.sync.status.SyncHeadline
import app.auri.sync.status.SyncStatus
import app.auri.sync.status.UserCause

/**
 * 刻意简陋的同步页：只为把 SyncUiState 摆出来、让按钮能按。真正的界面由另一个人在 SyncUiState / SyncViewModel 上重做，
 * 这里不做样式，也不做任何状态判断（显示哪句话都来自 SyncUiState，句子在下面的 describe* 里）。
 * “等网关”“稍后重试”“需要你处理”是三种不同的情形，文字与操作都不一样，不能合并（Ticket 21 第 6 项）。
 *
 * 摄像头取景由调用方通过 [scanner] 提供（界面层不碰 Android 的相机类，也便于以后替换）。
 * [manualEntry] 是留给调试版的槽（没有摄像头时粘贴配对载荷）：这里不知道它是什么，release 版传进来的是空实现（评审 F14）。
 */
@Composable
fun SyncScreen(
    state: SyncUiState,
    scanning: Boolean,
    onBack: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onDismissProblem: () -> Unit,
    onSyncNow: () -> Unit,
    cameraUnavailable: Boolean = false,
    scanner: @Composable () -> Unit,
    manualEntry: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onBack) { Text("返回") }
            Button(onClick = onSyncNow, enabled = state.status != null && state.status.headline != SyncHeadline.NOT_PAIRED) { Text("现在同步") }
        }
        val status = state.status
        if (state.storageProblem) Text(describeStorageProblem())
        if (status == null) {
            Text("正在读取配对信息…")
        } else {
            Text(describeHeadline(status))
            status.causes.forEach { Text("· " + describeCause(it)) }
            Text(describeCounts(status.counts))
        }
        when (val pairing = state.pairing) {
            PairingProgress.Idle -> Unit
            PairingProgress.Pairing -> Text("正在配对…")
            is PairingProgress.Failed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(describeProblem(pairing.problem))
                Button(onClick = onDismissProblem) { Text("知道了") }
            }
        }
        val paired = status != null && status.headline != SyncHeadline.NOT_PAIRED
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (scanning) {
                Button(onClick = onStopScan) { Text("停止扫码") }
            } else {
                Button(onClick = onStartScan, enabled = state.pairing != PairingProgress.Pairing) { Text(if (paired) "重新配对（扫码）" else "扫码配对") }
            }
        }
        if (cameraUnavailable) Text("摄像头不可用（没有摄像头、没有授权或正被占用）。")
        if (scanning) scanner()
        manualEntry()
    }
}

/** 一句话说清现在是哪种情形。三种“同步没完成”的句子互不相同，只有需要用户处理的那一种让用户做事。 */
internal fun describeHeadline(status: SyncStatus): String = when (status.headline) {
    SyncHeadline.NOT_PAIRED -> "还没有配对：录下的文字只保存在这台手机上，不会发出去。请在网关电脑上运行配对命令，再扫描它显示的二维码。"
    SyncHeadline.NEEDS_USER -> "需要你处理：下面的问题解决之前，相关文字不会再自动发送。"
    SyncHeadline.RETRYING_LATER -> "网关暂时没有成功处理，稍后会自动重试，不需要你做什么。" + (status.nextRetryAt?.let { "（下次重试：$it）" } ?: "")
    SyncHeadline.WAITING_FOR_GATEWAY -> "现在连不上网关（可能不在同一个网络，或网关没开机）。连上之后会自动发送，不需要你做什么。"
    SyncHeadline.SENDING -> "正在发送…"
    SyncHeadline.UP_TO_DATE -> "已同步：所有文字都已进入网关。"
}

/** 读本机的同步记录时出了故障（比如数据库暂时被锁）：和“网关连不上”是两回事，下面显示的状态是最后一次读到的。 */
internal fun describeStorageProblem(): String = "本机的同步记录暂时读不出来，正在自动重试，不需要你做什么。下面显示的是最后一次读到的状态。"

internal fun describeCause(cause: UserCause): String = when (cause) {
    UserCause.KEY_MISMATCH -> "这台网关的身份与配对时记录的不一样（可能换了网关或证书）。为安全起见没有发送任何内容。请在网关上重新生成配对二维码，重新配对。"
    UserCause.AUTH_REJECTED -> "网关不再承认这台手机（可能被撤销了）。请重新配对。"
    UserCause.PAIRING_LOST -> "本机保存的配对信息丢失（例如清除了安全存储）。请重新配对。"
    UserCause.VERSION_CONFLICT -> "有一版文字与网关上同一版的内容冲突，没有被收下。"
    UserCause.RECORD_DELETED -> "有一条记录已在网关上被删除，它的文字不会再发送。"
    UserCause.NOT_ORIGIN_DEVICE -> "网关说有一条记录属于另一台设备，没有被收下。"
    UserCause.REQUEST_REJECTED -> "网关拒绝了某一版文字（请求不合规）。重试不会有用。"
    UserCause.LOCAL_DATA_MISSING -> "本机找不到某一版文字的内容，无法发送。"
}

internal fun describeCounts(counts: SyncCounts): String =
    "排队 ${counts.queued} · 发送中 ${counts.sending} · 等网关 ${counts.waitingForGateway} · 稍后重试 ${counts.retryingLater} · 需处理 ${counts.needsUser} · 已入库 ${counts.stored}"

internal fun describeProblem(problem: PairingProblem): String = when (problem) {
    PairingProblem.INVALID_QR -> "这不是 Auri 的配对二维码。"
    PairingProblem.EXPIRED -> "二维码已过期。请在网关上重新生成配对二维码。"
    PairingProblem.NOT_FOUND -> "在局域网里找不到网关。请确认手机和网关电脑连在同一个网络，网关已启动。"
    PairingProblem.KEY_MISMATCH -> "对方不是二维码对应的那台网关（公钥指纹不符），配对码没有发给它。"
    PairingProblem.CODE_REJECTED -> "配对码无效、已过期或已被使用。请在网关上重新生成。"
    PairingProblem.GATEWAY_ERROR -> "网关返回了错误，没有配对成功。"
    PairingProblem.UNCERTAIN -> "请求发出后没有收到明确的回答，配对码可能已经被消耗。请在网关上重新生成配对码再试。"
    PairingProblem.WRONG_GATEWAY -> "对方的网关编号与二维码不一致，没有配对。"
    PairingProblem.NOT_SAVED -> "网关那边配好了，但这台手机没能保存令牌。请在网关上撤销这台设备后重新配对。"
    PairingProblem.UNKNOWN -> "配对没有完成，请再试一次。"
}
