package app.auri.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.auri.android.home.HomeUiState
import app.auri.android.home.InterruptionFact
import app.auri.android.home.Notice
import app.auri.android.home.RecordItem
import app.auri.android.home.TextState
import app.auri.capture.contract.CaptureState
import app.auri.capture.contract.EndReason
import app.auri.capture.contract.ErrorCode
import app.auri.capture.contract.ObservedInputRouteType

/**
 * 刻意简陋的首页：只为把 HomeUiState 摆出来、让按钮能按。真正的界面由另一个人在 HomeUiState / HomeViewModel 上重做，
 * 这里不做样式，也不做任何状态判断（按钮能不能按、显示什么都来自 HomeUiState）。
 * 三个事实分三行：耳机连接、实际输入来源、采集状态——不能合并成一句话（耳机已连接 ≠ 正在用耳机麦克风）。
 */
@Composable
fun HomeScreen(state: HomeUiState, onStart: () -> Unit, onStop: () -> Unit, onDismissNotice: () -> Unit, onSetDuration: (String) -> Unit, onOpenSync: (() -> Unit)? = null) {
    var durationText by rememberSaveable { mutableStateOf(state.durationMinutes.toString()) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        onOpenSync?.let { Button(onClick = it) { Text("同步与配对") } }
        Text("耳机：" + if (state.headset.connected) "已连接" + (state.headset.name?.let { "（$it）" } ?: "") else "未连接")
        Text("实际输入：" + describeInput(state.input.source, state.input.deviceName))
        Text("采集状态：" + describeCapture(state.capture.state) + (state.capture.blockedReason?.let { "（原因：$it）" } ?: ""))
        state.interruption?.let { Text(describeInterruption(it)) }
        if (state.durationWarning) Text("录音将在一分钟后停止")
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = durationText, onValueChange = { durationText = it }, label = { Text("录音上限（分钟）") }, singleLine = true, modifier = Modifier.weight(1f))
            Button(onClick = { onSetDuration(durationText) }) { Text("保存") }
        }
        state.durationSettingError?.let { Text(it) }
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
        item.truncatedBy?.let { Text(describeTruncation(it)) }
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

/**
 * 录音被打断之后首页要说的话：停了、为什么、已录的部分怎么样了、不会自己继续。
 * 先看结束原因（同一个错误码在不同原因下含义不同：INPUT_DEVICE_MISSING 可以是耳机断开，也可以是蓝牙权限被关），再看错误码。
 */
internal fun describeInterruption(fact: InterruptionFact): String {
    val cause = when {
        fact.reason == EndReason.PERMISSION_REVOKED -> when (fact.code) {
            ErrorCode.PERMISSION_MIC_DENIED -> "麦克风权限被关闭"
            ErrorCode.INPUT_DEVICE_MISSING -> "蓝牙连接权限被关闭"
            else -> "录音所需的权限被关闭"
        }
        fact.code == ErrorCode.STORAGE_EXHAUSTED -> "存储空间已满"
        fact.code == ErrorCode.AUDIO_INTERRUPTED -> "来电或其他应用占用了音频"
        fact.code == ErrorCode.BACKGROUND_START_DENIED -> "系统没有允许应用在后台继续录音"
        fact.reason == EndReason.ROUTE_LOST -> "耳机断开，或输入来源变了"
        else -> "被系统中断"
    }
    val saved = when (fact.code) {
        ErrorCode.STORAGE_EXHAUSTED -> "已写入的部分（如果有）已保存，列表里没有的话下次启动应用时会找回，腾出空间后请重新开始"
        else -> "已录下并确认来自耳机的部分已保存，需要时请重新开始"
    }
    return "录音已停止：$cause。$saved；不会自动继续。"
}

/** 列表里一条被截断的 Record 的标记：为什么被截断（只含已确认的那部分）。 */
internal fun describeTruncation(reason: EndReason): String = when (reason) {
    EndReason.ROUTE_LOST -> "被截断：耳机断开或输入来源变了，只含断开之前确认的部分"
    EndReason.SYSTEM_INTERRUPTION -> "被截断：被系统打断（来电、存储写满等），只含打断之前保存的部分"
    EndReason.PERMISSION_REVOKED -> "被截断：录音所需的权限被关闭，只含关闭之前保存的部分"
    EndReason.PROCESS_TERMINATED -> "被截断：应用被中途关闭，已从磁盘找回写入过的部分，结尾可能少一小段"
    else -> "被截断"
}

internal fun describeNotice(notice: Notice): String = when (notice) {
    is Notice.RecordingLost -> "这次录音没能完整保存（${notice.reason}）；如果手机上已经写下了一部分，下次启动应用时会尝试找回"
    Notice.RecordNotSaved -> "录音已保存，但没能记入列表"
}
