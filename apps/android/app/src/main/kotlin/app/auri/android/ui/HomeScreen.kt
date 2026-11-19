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
import app.auri.android.home.HomeUiState
import app.auri.android.home.Notice
import app.auri.android.home.RecordItem
import app.auri.android.home.TextState
import app.auri.capture.contract.CaptureState
import app.auri.capture.contract.ObservedInputRouteType

/**
 * 刻意简陋的首页：只为把 HomeUiState 摆出来、让按钮能按。真正的界面由另一个人在 HomeUiState / HomeViewModel 上重做，
 * 这里不做样式，也不做任何状态判断（按钮能不能按、显示什么都来自 HomeUiState）。
 * 三个事实分三行：耳机连接、实际输入来源、采集状态——不能合并成一句话（耳机已连接 ≠ 正在用耳机麦克风）。
 */
@Composable
fun HomeScreen(state: HomeUiState, onStart: () -> Unit, onStop: () -> Unit, onDismissNotice: () -> Unit, onOpenSync: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        onOpenSync?.let { Button(onClick = it) { Text("同步与配对") } }
        Text("耳机：" + if (state.headset.connected) "已连接" + (state.headset.name?.let { "（$it）" } ?: "") else "未连接")
        Text("实际输入：" + describeInput(state.input.source, state.input.deviceName))
        Text("采集状态：" + describeCapture(state.capture.state) + (state.capture.blockedReason?.let { "（原因：$it）" } ?: ""))
        state.durationLimit?.let { Text("已达到录音时长上限（${it.limitSeconds} 秒），录音已自动停止") }
        state.notice?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(describeNotice(it))
                Button(onClick = onDismissNotice) { Text("知道了") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart, enabled = state.startEnabled) { Text("开始录音") }
            Button(onClick = onStop, enabled = state.stopEnabled) { Text("停止") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.records, key = { it.recordId }) { RecordRow(it) }
        }
    }
}

@Composable
private fun RecordRow(item: RecordItem) {
    Column {
        Text("${item.startedAt}　${item.durationMs / 1000.0} 秒")
        Text(describeText(item.text))
        (item.text as? TextState.Done)?.let { Text("模型：${it.modelId ?: "未知"} ${it.modelVersion ?: ""}") }
    }
}

internal fun describeInput(source: ObservedInputRouteType?, name: String?): String = when (source) {
    null -> "未观测到"
    ObservedInputRouteType.BLUETOOTH_HFP -> "蓝牙耳机（HFP）" + suffix(name)
    ObservedInputRouteType.BLUETOOTH_LE_AUDIO -> "蓝牙耳机（LE Audio）" + suffix(name)
    ObservedInputRouteType.BLUETOOTH_UNKNOWN_PROFILE -> "蓝牙设备（协议未知）" + suffix(name)
    ObservedInputRouteType.BUILT_IN_MIC -> "手机麦克风"
    else -> "其他输入（$source）" + suffix(name)
}

private fun suffix(name: String?) = name?.let { "（$it）" }.orEmpty()

internal fun describeCapture(state: CaptureState): String = when (state) {
    CaptureState.DISARMED -> "未开启"
    CaptureState.ARMED -> "待命"
    CaptureState.ROUTING -> "正在确认耳机麦克风"
    CaptureState.RECORDING -> "录音中"
    CaptureState.FINALIZING -> "保存中"
    CaptureState.BLOCKED -> "没有录音"
}

private fun describeText(text: TextState): String = when (text) {
    TextState.Transcribing -> "转录中…"
    is TextState.Done -> text.text
    TextState.NoSpeech -> "无语音"
    is TextState.WaitingModel -> "等待转录模型" + (text.reason?.let { "（$it）" } ?: "")
    is TextState.Failed -> "转录失败" + (text.reason?.let { "（$it）" } ?: "")
}

private fun describeNotice(notice: Notice): String = when (notice) {
    is Notice.RecordingLost -> "这次录音没能保存（${notice.reason}）"
    Notice.RecordNotSaved -> "录音已保存，但没能记入列表"
}
