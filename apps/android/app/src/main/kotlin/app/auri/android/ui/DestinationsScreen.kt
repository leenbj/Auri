package app.auri.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.auri.android.destinations.DestinationItem
import app.auri.android.destinations.DestinationsUiState
import app.auri.android.destinations.describeCreateHint
import app.auri.android.destinations.describeFailure
import app.auri.android.destinations.describeIntent
import app.auri.android.destinations.describeSendMode
import app.auri.android.destinations.describeSendModeDetail
import app.auri.sync.gateway.SendMode

/**
 * 刻意简陋的目的地列表与创建页：只为把 DestinationsUiState 摆出来、让按钮能按。真正的界面另有人重做，这里不做样式、不做状态判断
 * （创建按钮能不能点、显示哪句话都来自 DestinationsUiState / describe*）。
 * 发送方式两项都不预选：用户必须自己点一个，创建按钮才亮。
 */
@Composable
fun DestinationsScreen(
    state: DestinationsUiState,
    onSetName: (String) -> Unit,
    onChooseSendMode: (SendMode) -> Unit,
    onSetAllowActionRequests: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onRefresh: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenReview: () -> Unit,
    onDismissProblem: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenReview) { Text("待发送与标签") }
                Button(onClick = onRefresh, enabled = !state.refreshing) { Text(if (state.refreshing) "正在刷新…" else "刷新") }
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
        item { Text("创建目的地") }
        item { Text(describeCreateHint()) }
        item {
            OutlinedTextField(
                value = state.form.name,
                onValueChange = onSetName,
                label = { Text("名字（比如 Claude Code、写作助手）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        items(SendMode.entries.toList(), key = { it.wire }) { mode ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RadioButton(selected = state.form.sendMode == mode, onClick = { onChooseSendMode(mode) })
                Text(describeSendModeDetail(mode), Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(checked = state.form.allowActionRequests, onCheckedChange = onSetAllowActionRequests)
                Text("也接收标成“请求执行”的文字（标签只是给 Agent 看的数据，不会被自动执行）")
            }
        }
        item {
            Button(onClick = onSubmit, enabled = state.form.canSubmit && !state.creating) { Text(if (state.creating) "正在创建…" else "创建") }
            if (state.form.sendMode == null) Text("先选一种发送方式。")
        }
        item { Text(if (state.destinations.isEmpty()) "还没有目的地。" else "目的地（${state.destinations.size}）") }
        items(state.destinations, key = { it.destinationId }) { DestinationRow(it, onOpenDetail) }
    }
}

@Composable
private fun DestinationRow(item: DestinationItem, onOpenDetail: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(item.name + if (item.active) "" else "（已撤销）")
        Text(describeSendMode(item.sendMode) + " · 接收：" + item.allowedIntents.sortedBy { it.wire }.joinToString("、") { describeIntent(it) })
        Button(onClick = { onOpenDetail(item.destinationId) }) { Text("查看") }
    }
}
