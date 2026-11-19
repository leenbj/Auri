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
import app.auri.android.control.ControlNotice
import app.auri.android.control.ControlUiState
import app.auri.android.control.ControlViewModel
import app.auri.android.control.ProfileFact
import app.auri.capture.control.HeadsetControlStatus

/**
 * 刻意简陋的耳机控制页：只为把 [ControlUiState] 摆出来、让按钮能按。真正的界面由另一个人在 ControlUiState / ControlViewModel 上重做，
 * 这里不做样式，也不做任何状态判断（能不能按、显示什么都来自 ControlUiState）。
 * 控制模式、耳机按键通道、采集状态分开写成三行，不能合并成一句话（通道要到了 ≠ 耳机手势一定能到）。
 * 屏幕上的“开始录音 / 停止”永远在这里，是耳机手势之外的手动兜底。
 */
@Composable
fun ControlScreen(state: ControlUiState, viewModel: ControlViewModel, onStart: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.notice?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(describeNotice(it))
                Button(onClick = viewModel::dismissNotice) { Text("知道了") }
            }
        }
        Text("控制模式：" + (if (state.controlMode.on) "开" else "关") + if (state.controlMode.turningOff) "（这次录音保存完就关）" else "")
        Text("耳机按键通道：" + describeHeadset(state.controlMode.headset))
        Text("采集状态：" + describeCapture(state.capture.state) + (state.capture.blockedReason?.let { "（原因：$it）" } ?: ""))
        Text("耳机档案：" + describeProfile(state.profile))
        state.feedback?.let { Text("最近一次反馈：${it.screenText}") }
        Button(onClick = { viewModel.setControlMode(!state.controlMode.on) }, enabled = !state.controlMode.turningOff) {
            Text(if (state.controlMode.on) "关闭控制模式" else "开启控制模式")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart, enabled = state.manual.startEnabled) { Text("开始录音") }
            Button(onClick = viewModel::stop, enabled = state.manual.stopEnabled) { Text("停止") }
        }
        CalibrationSection(state.calibration, viewModel)
    }
}

private fun describeHeadset(status: HeadsetControlStatus): String = when (status) {
    HeadsetControlStatus.OFF -> "没有占着（控制模式关着，耳机按键不会触发任何东西）"
    HeadsetControlStatus.ACTIVE -> "已向系统要到，在等耳机按键（能不能真收到，以校准结果为准）"
    HeadsetControlStatus.UNAVAILABLE -> "系统没有给出按键通道（屏幕按钮照常可用）"
}

private fun describeProfile(profile: ProfileFact): String = when (profile) {
    ProfileFact.Generic -> "没校准过，用通用映射（播放 → 开始，暂停 → 停止，切换 → 切换）"
    is ProfileFact.Calibrated -> "${profile.profileId}（版本 ${profile.profileVersion}）"
}

private fun describeNotice(notice: ControlNotice): String = when (notice) {
    ControlNotice.CalibrationAborted -> "校准已放弃，没有保存任何档案"
    ControlNotice.ProfileSaveFailed -> "档案没能保存到手机存储；结果还在，可以再试一次，当前在用的映射没有改变"
}
