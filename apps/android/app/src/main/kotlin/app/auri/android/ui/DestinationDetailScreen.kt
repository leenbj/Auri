package app.auri.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.auri.android.destinations.CredentialUi
import app.auri.android.destinations.DestinationDetailUiState
import app.auri.android.destinations.DestinationItem
import app.auri.android.destinations.ItemStatusRow
import app.auri.android.destinations.RevokeUi
import app.auri.android.destinations.UNREVIEWED_LABEL
import app.auri.android.destinations.describeCredentialRules
import app.auri.android.destinations.describeDelivery
import app.auri.android.destinations.describeFailure
import app.auri.android.destinations.describeIntent
import app.auri.android.destinations.describeNotAuthenticated
import app.auri.android.destinations.describeSendModeDetail
import app.auri.sync.gateway.DestinationCredential

/**
 * 刻意简陋的目的地详情页：这个目的地的设置、凭证面板、撤销，以及每条文字在它上面的状态。
 * 状态一栏只显示网关能证明的（已入库 / 已领取 / 已确认）；“已确认”就是“Agent 回了确认”，不说成已处理（describeDelivery）。
 * 凭证只在 [CredentialUi.Shown] 时出现这一次；这个页面没有任何保存状态（rememberSaveable）去持有它。
 */
@Composable
fun DestinationDetailScreen(
    state: DestinationDetailUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRequestCredential: () -> Unit,
    onConfirmRotate: () -> Unit,
    onCancelRotate: () -> Unit,
    onCopyCredential: (DestinationCredential) -> Unit,
    onDismissCredential: () -> Unit,
    onRequestRevoke: () -> Unit,
    onConfirmRevoke: () -> Unit,
    onCancelRevoke: () -> Unit,
    onDismissProblem: () -> Unit,
) {
    SecureWhileShown(state.credential is CredentialUi.Shown)
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onBack) { Text("返回") }
                Button(onClick = onRefresh, enabled = !state.itemsLoading) { Text(if (state.itemsLoading) "正在查询…" else "刷新状态") }
            }
        }
        state.problem?.let { problem ->
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(describeFailure(problem), Modifier.weight(1f))
                    Button(onClick = onDismissProblem) { Text("知道了") }
                }
            }
        }
        val destination = state.destination
        if (destination == null) {
            if (state.loaded) item { Text("找不到这个目的地（可能已被删除）。") }
            return@LazyColumn
        }
        item { Header(destination) }
        item { CredentialPanel(destination, state.credential, onRequestCredential, onConfirmRotate, onCancelRotate, onCopyCredential, onDismissCredential) }
        if (destination.active) item { RevokePanel(state.revoke, onRequestRevoke, onConfirmRevoke, onCancelRevoke) }
        item { Text(if (state.items.isEmpty()) "还没有文字发给它。" else "最近的文字") }
        items(state.items, key = { "${it.key.recordId}/${it.key.revision}" }) { ItemRow(it) }
    }
}

@Composable
private fun Header(destination: DestinationItem) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(destination.name + if (destination.active) "" else "（已撤销）")
        Text(describeSendModeDetail(destination.sendMode))
        Text("接收：" + destination.allowedIntents.sortedBy { it.wire }.joinToString("、") { describeIntent(it) })
    }
}

@Composable
private fun CredentialPanel(
    destination: DestinationItem,
    credential: CredentialUi,
    onRequest: () -> Unit,
    onConfirmRotate: () -> Unit,
    onCancelRotate: () -> Unit,
    onCopy: (DestinationCredential) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("给 Agent 用的凭证")
        when (credential) {
            CredentialUi.Hidden -> {
                if (destination.active) {
                    Text(describeCredentialRules(rotating = destination.credentialIssued))
                    Button(onClick = onRequest) { Text(if (destination.credentialIssued) "轮换凭证" else "生成凭证") }
                } else {
                    Text("这个目的地已被撤销，它的凭证已经失效。")
                }
            }
            CredentialUi.ConfirmRotate -> {
                Text(describeCredentialRules(rotating = true))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onConfirmRotate) { Text("确认轮换（需要核验本人）") }
                    Button(onClick = onCancelRotate) { Text("取消") }
                }
            }
            CredentialUi.Working -> Text("正在核验本人并向网关要凭证…")
            is CredentialUi.Shown -> {
                Text("凭证只显示这一次，请现在复制：")
                Text(credential.credential.reveal())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onCopy(credential.credential) }) { Text("复制并关闭") }
                    Button(onClick = onDismiss) { Text("不复制，关闭") }
                }
            }
            is CredentialUi.NotAuthenticated -> {
                Text(describeNotAuthenticated(credential.result))
                Button(onClick = onDismiss) { Text("知道了") }
            }
            is CredentialUi.Failed -> {
                Text(describeFailure(credential.failure))
                Button(onClick = onDismiss) { Text("知道了") }
            }
        }
    }
}

@Composable
private fun RevokePanel(revoke: RevokeUi, onRequest: () -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit) {
    when (revoke) {
        RevokeUi.IDLE -> Button(onClick = onRequest) { Text("撤销这个目的地") }
        RevokeUi.CONFIRMING -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("撤销后它的凭证立刻失效，还没被 Agent 领取的文字也不会再发给它。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConfirm) { Text("确认撤销") }
                Button(onClick = onCancel) { Text("取消") }
            }
        }
        RevokeUi.WORKING -> Text("正在撤销…")
    }
}

@Composable
private fun ItemRow(row: ItemStatusRow) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(row.excerpt ?: "（本机已经没有这条文字）")
        Text(describeIntent(row.intent) + " · " + UNREVIEWED_LABEL + " · " + row.createdAt)
        Text(describeDelivery(row.view))
    }
}
