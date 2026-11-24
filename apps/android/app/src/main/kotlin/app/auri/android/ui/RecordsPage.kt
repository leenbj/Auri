package app.auri.android.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.auri.android.revisions.PreparedExport
import app.auri.android.revisions.RecordDetailViewModel
import app.auri.android.revisions.RecordsViewModel
import app.auri.android.revisions.RevisionDescriptions
import app.auri.android.revisions.TimeRangePreset
import app.auri.capture.contract.RevisionSource
import app.auri.capture.contract.ReviewState
import app.auri.sync.revisions.ExportFormat
import app.auri.sync.revisions.ResendState
import app.auri.sync.revisions.SendStatusFilter

/**
 * 记录库页面（Ticket 23）：列表筛选、音频保留期设置、TXT/Markdown 导出、以及单条记录的修订/重发/删除详情。
 */
@Composable
fun RecordsPage(
    listViewModel: RecordsViewModel,
    detailFor: (String) -> RecordDetailViewModel,
) {
    var selectedRecordId by rememberSaveable { mutableStateOf<String?>(null) }
    val currentId = selectedRecordId
    if (currentId != null) {
        BackHandler { selectedRecordId = null }
        RecordDetailScreen(
            viewModel = detailFor(currentId),
            onBack = { selectedRecordId = null },
        )
    } else {
        RecordsListScreen(
            viewModel = listViewModel,
            onSelect = { selectedRecordId = it },
        )
    }
}

@Composable
private fun RecordsListScreen(
    viewModel: RecordsViewModel,
    onSelect: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var retentionInput by rememberSaveable(state.retentionDays) { mutableStateOf(state.retentionDays.toString()) }
    ExportHandler(state.pendingExport, onConsumed = viewModel::consumeExport)

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.keyword,
            onValueChange = viewModel::setKeyword,
            label = { Text("按关键词筛选") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (preset in TimeRangePreset.entries) {
                val label = when (preset) {
                    TimeRangePreset.ALL -> "全部时间"
                    TimeRangePreset.TODAY -> "今天"
                    TimeRangePreset.LAST_7_DAYS -> "近7天"
                    TimeRangePreset.LAST_30_DAYS -> "近30天"
                }
                OutlinedButton(onClick = { viewModel.setTimePreset(preset) }, enabled = state.timePreset != preset) {
                    Text(label)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (filter in listOf(SendStatusFilter.ALL, SendStatusFilter.STORED, SendStatusFilter.WAITING_GATEWAY, SendStatusFilter.AWAITING_CONFIRM, SendStatusFilter.NEEDS_USER)) {
                OutlinedButton(onClick = { viewModel.setSendStatus(filter) }, enabled = state.sendStatus != filter) {
                    Text(RevisionDescriptions.describeSendStatusFilter(filter))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.prepareExport(ExportFormat.TXT) }, enabled = state.records.isNotEmpty()) { Text("导出 TXT") }
            Button(onClick = { viewModel.prepareExport(ExportFormat.MARKDOWN) }, enabled = state.records.isNotEmpty()) { Text("导出 Markdown") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = retentionInput,
                onValueChange = { retentionInput = it },
                label = { Text("音频保留期（天，默认30）") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { viewModel.setRetentionDays(retentionInput) }) { Text("保存保留期") }
        }
        Text(RevisionDescriptions.AUDIO_RETENTION_NOTICE)
        state.retentionError?.let { Text(it) }
        state.notice?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(it)
                OutlinedButton(onClick = viewModel::dismissNotice) { Text("知道了") }
            }
        }
        HorizontalDivider()
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.records, key = { it.recordId }) { entry ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${entry.startedAt}  共 ${entry.revisions.size} 版  音频：${if (entry.audio != null) "有" else "已删"}")
                    Text(entry.latestText ?: "（无文字）")
                    OutlinedButton(onClick = { onSelect(entry.recordId) }) { Text("查看修订 / 重发 / 删除") }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun RecordDetailScreen(
    viewModel: RecordDetailViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    ExportHandler(state.pendingExport, onConsumed = viewModel::consumeExport)

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack) { Text("返回列表") }
            if (state.record != null) {
                OutlinedButton(onClick = { viewModel.prepareExport(ExportFormat.TXT) }) { Text("导出 TXT") }
                OutlinedButton(onClick = { viewModel.prepareExport(ExportFormat.MARKDOWN) }) { Text("导出 MD") }
            }
        }
        state.notice?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(it)
                OutlinedButton(onClick = viewModel::dismissNotice) { Text("知道了") }
            }
        }
        if (state.deleted || state.record == null) {
            Text("该记录已删除（本地与网关均不可见；Agent 已读走的内容无法收回）。")
            return
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(state.noAutoResendNotice)
                OutlinedTextField(
                    value = state.draftText,
                    onValueChange = viewModel::updateDraft,
                    label = { Text("修改正文（保存后生成新版本）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                state.editError?.let { Text(RevisionDescriptions.describeEditError(it)) }
                Button(onClick = viewModel::saveEdit) { Text("保存为新版本") }
            }

            if (state.revisions.size > 1) {
                item {
                    HorizontalDivider()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("收到过旧版本的目的地（需逐个确认重发）：")
                        OutlinedButton(onClick = { viewModel.refreshRecipients(automatic = false) }) { Text("刷新状态") }
                    }
                }
                items(state.olderRecipients, key = { it.destinationId }) { recipient ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${recipient.destinationName}：收到过第 ${recipient.receivedRevision} 版（${RevisionDescriptions.describeOlderDelivery(recipient.olderDeliveryView)}）")
                        Text("当前版本状态：${RevisionDescriptions.describeResendState(recipient.resendState)}")
                        if (recipient.resendState == ResendState.NOT_REQUESTED) {
                            Button(onClick = { viewModel.confirmResend(recipient.destinationId) }) {
                                Text("确认重发最新一版给 ${recipient.destinationName}")
                            }
                        }
                    }
                }
            }

            item {
                HorizontalDivider()
                Text("不可变修订历史（共 ${state.revisions.size} 版）：")
            }
            items(state.revisions.reversed(), key = { it.number }) { rev ->
                val src = if (rev.source == RevisionSource.ASR) "语音转录" else "用户修订"
                val review = if (rev.reviewState == ReviewState.REVIEWED) "已审核" else "未审核"
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("第 ${rev.number} 版 · $src · $review · ${rev.createdAt}")
                    Text(rev.text)
                }
            }

            item {
                HorizontalDivider()
                Text(RevisionDescriptions.DELETE_AUDIO_NOTICE)
                OutlinedButton(onClick = viewModel::deleteAudioOnly, enabled = state.record?.audio != null) {
                    Text(if (state.record?.audio != null) "仅删除本地音频（保留文字）" else "本地音频已删除")
                }
                HorizontalDivider()
                Text(state.deleteRecordNotice)
                if (!confirmingDelete) {
                    OutlinedButton(onClick = { confirmingDelete = true }) { Text("删除整条记录") }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            confirmingDelete = false
                            viewModel.deleteRecord()
                        }) { Text("确认删除整条记录") }
                        OutlinedButton(onClick = { confirmingDelete = false }) { Text("取消") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportHandler(pending: PreparedExport?, onConsumed: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val current = pending
        if (uri != null && current != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(current.content.toByteArray(Charsets.UTF_8))
                }
            } catch (_: Exception) {
                // 写入失败时不记正文日志
            }
        }
        onConsumed()
    }
    LaunchedEffect(pending) {
        if (pending != null) launcher.launch(pending.fileName)
    }
}
