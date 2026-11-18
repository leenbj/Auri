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
import app.auri.android.modelui.JobRow
import app.auri.android.modelui.ModelRow
import app.auri.android.modelui.ModelsUiState
import app.auri.android.modelui.describeActiveModel
import app.auri.android.modelui.describeInstalled
import app.auri.android.modelui.describeJob
import app.auri.android.modelui.describeRedistribution
import app.auri.android.modelui.describeStart
import app.auri.android.modelui.describeTransfer
import app.auri.android.modelui.formatBytes
import app.auri.capture.scheduler.JobKind

/**
 * 刻意简陋的“模型与转录”页：只为把 ModelsUiState 摆出来、让按钮能按。真正的界面由另一个人在 ModelsUiState / ModelsViewModel 上重做，
 * 这里不做样式，也不做任何状态判断（按钮出现与否、显示什么字都来自 ModelsUiState 与 modelui 里的描述函数）。
 */
@Composable
fun ModelsScreen(
    state: ModelsUiState,
    onStart: (String) -> Unit,
    onPause: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onOpenLicense: (String) -> Unit,
    onCancelJob: (String) -> Unit,
    onRetranscribe: (String) -> Unit,
    onRecheck: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(describeActiveModel(state.activeModel)) }
        items(state.models, key = { it.modelId }) { ModelRowView(it, onStart, onPause, onCancelDownload, onOpenLicense) }
        item { Text("转录任务") }
        if (state.jobs.isEmpty()) item { Text("还没有转录任务") }
        items(state.jobs, key = { it.jobId }) { JobRowView(it, onCancelJob, onRetranscribe, onRecheck) }
    }
}

@Composable
private fun ModelRowView(
    row: ModelRow,
    onStart: (String) -> Unit,
    onPause: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onOpenLicense: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${row.modelId} ${row.version}" + if (row.isDefault) "（默认）" else "")
        Text("语言：" + row.languages.joinToString("、"))
        Text("下载 ${formatBytes(row.downloadBytes)}，解包后 ${formatBytes(row.unpackedBytes)}，从零装好需要可用空间 ${formatBytes(row.spaceNeededBytes)}")
        Text("许可：${row.license.name}；${describeRedistribution(row.license.redistribution)}")
        Text(row.license.note)
        Text(row.license.url)
        Text("固定的哈希（SHA-256）：${row.archiveSha256}")
        Text(describeInstalled(row))
        describeTransfer(row.transfer)?.let { Text(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.start?.let { Button(onClick = { onStart(row.modelId) }) { Text(describeStart(it)) } }
            if (row.canPause) Button(onClick = { onPause(row.modelId) }) { Text("暂停") }
            if (row.canCancel) Button(onClick = { onCancelDownload(row.modelId) }) { Text("放弃下载") }
            Button(onClick = { onOpenLicense(row.license.url) }) { Text("查看许可") }
        }
    }
}

@Composable
private fun JobRowView(row: JobRow, onCancel: (String) -> Unit, onRetranscribe: (String) -> Unit, onRecheck: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("录音 ${row.recordId.take(8)}" + if (row.kind == JobKind.RETRANSCRIBE) "（重新转录）" else "")
        Text(describeJob(row.status))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (row.canRecheck) Button(onClick = onRecheck) { Text("重新检查") }
            if (row.canCancel) Button(onClick = { onCancel(row.jobId) }) { Text("取消") }
            if (row.canRetranscribe) Button(onClick = { onRetranscribe(row.recordId) }) { Text("重新转录") }
        }
    }
}
