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
import app.auri.android.destinations.ReviewItem
import app.auri.android.destinations.ReviewTarget
import app.auri.android.destinations.SendReviewUiState
import app.auri.android.destinations.UNREVIEWED_LABEL
import app.auri.android.destinations.describeDataFlowHold
import app.auri.android.destinations.describeDecision
import app.auri.android.destinations.describeIntent
import app.auri.android.destinations.describeIntentNote
import app.auri.android.destinations.describeSendMode
import app.auri.sync.engine.JobKey
import app.auri.sync.gateway.Intent

/**
 * 刻意简陋的“待发送”页：预览确认的文字在这里等你点“确认发送”；每条文字的标签（备忘 / 请求执行）在发出去之前可以改。
 * 正文只是显示给你看的数据，不会被当作指令。
 */
@Composable
fun SendReviewScreen(
    state: SendReviewUiState,
    onBack: () -> Unit,
    onConfirm: (JobKey, String) -> Unit,
    onSkip: (JobKey, String) -> Unit,
    onSetIntent: (JobKey, Intent) -> Unit,
    onSetDefaultIntent: (Intent) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Button(onClick = onBack) { Text("返回") } }
        describeDataFlowHold(state.dataFlow)?.let { hold -> item { Text(hold) } }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("新录的文字默认标成：" + describeIntent(state.defaultIntent))
                Text(describeIntentNote(state.defaultIntent))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Intent.entries.forEach { intent ->
                        Button(onClick = { onSetDefaultIntent(intent) }, enabled = intent != state.defaultIntent) { Text(describeIntent(intent)) }
                    }
                }
            }
        }
        item { Text(if (state.items.isEmpty()) "还没有文字。" else "最近的文字（等你确认的排在前面）") }
        items(state.items, key = { "${it.key.recordId}/${it.key.revision}" }) { Item(it, onConfirm, onSkip, onSetIntent) }
    }
}

@Composable
private fun Item(item: ReviewItem, onConfirm: (JobKey, String) -> Unit, onSkip: (JobKey, String) -> Unit, onSetIntent: (JobKey, Intent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(item.text ?: "（本机已经没有这条文字）")
        Text("标签：" + describeIntent(item.intent) + " · " + UNREVIEWED_LABEL + if (item.intentLocked) " · 已开始发送，标签不能再改" else "")
        if (item.canChangeIntent) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Intent.entries.forEach { intent ->
                    Button(onClick = { onSetIntent(item.key, intent) }, enabled = intent != item.intent) { Text("标成" + describeIntent(intent)) }
                }
            }
        }
        item.targets.forEach { Target(item.key, it, onConfirm, onSkip) }
    }
}

@Composable
private fun Target(key: JobKey, target: ReviewTarget, onConfirm: (JobKey, String) -> Unit, onSkip: (JobKey, String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text((target.name ?: "（不认识的目的地）") + " · " + describeSendMode(target.sendMode) + " · " + describeDecision(target.decision, target.eligible))
        if (target.awaitingUser) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onConfirm(key, target.destinationId) }) { Text("确认发送") }
                Button(onClick = { onSkip(key, target.destinationId) }) { Text("不发送") }
            }
        }
    }
}
