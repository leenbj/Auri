package app.auri.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import app.auri.android.control.CalibrationUi
import app.auri.android.control.ControlViewModel
import app.auri.android.control.ProfileCapability
import app.auri.capture.calibration.CalibrationStep
import app.auri.capture.calibration.EvidenceSetting
import app.auri.capture.contract.PlatformEvent

/**
 * 校准向导的简陋页面（设置 → 四步 → 结果），只摆 [CalibrationUi]，不做判断。
 * 结论只来自观察：没做、跳过、没观察到的能力一律显示“NOT_TESTED”；证据等级由环境决定，不是由页面或观察到的内容决定。
 */
@Composable
fun CalibrationSection(calibration: CalibrationUi, viewModel: ControlViewModel) {
    Text("耳机手势校准")
    when (calibration) {
        is CalibrationUi.Setup -> {
            Text("取证环境：" + describeSetting(calibration.setting))
            if (calibration.canConfirmRealDevice) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = calibration.realDeviceConfirmed, onCheckedChange = viewModel::setRealDeviceConfirmed)
                    Text("我确认：这是真手机，戴的是真耳机")
                }
            }
            var model by remember { mutableStateOf(calibration.modelText) }
            OutlinedTextField(value = model, onValueChange = { model = it; viewModel.setModelText(it) }, label = { Text("耳机型号（可不填，如 FreeClip 2）") })
            Button(onClick = viewModel::startCalibration) { Text("开始校准") }
        }
        is CalibrationUi.Running -> {
            Text("第 ${calibration.stepNumber} / ${calibration.stepCount} 步（第 ${calibration.attempt} 次尝试）")
            Text(describeStep(calibration.step))
            Text("收到的耳机事件：" + describeEventCounts(calibration.summary.eventCounts))
            Text("手势开始了录音：" + yesNo(calibration.summary.startedByGesture) + "；手势停止了录音：" + yesNo(calibration.summary.stoppedByGesture))
            if (calibration.summary.ownerReportedNoEffect) Text("你已声明：做了手势，没有反应")
            Text("屏幕：" + when (calibration.screenLocked) { true -> "锁定中"; false -> "未锁定"; null -> "读不出来" })
            Text("取证环境：" + describeSetting(calibration.setting))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::nextStep) { Text("这一步做完了") }
                Button(onClick = viewModel::skipStep) { Text("跳过") }
                Button(onClick = viewModel::repeatStep) { Text("重做") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::reportNoEffect, enabled = calibration.canReportNoEffect) { Text("做了手势，没反应") }
                Button(onClick = viewModel::abortCalibration) { Text("放弃") }
            }
        }
        is CalibrationUi.Finished -> {
            Text("校准完成，五项能力：")
            calibration.lines.forEach { Text("${describeCapability(it.capability)}：${it.status}（证据 ${it.evidenceLevel ?: "无"}）") }
            Text("取证环境：" + describeSetting(calibration.setting))
            if (!calibration.canSave) Text("没有任何可保存的结论：不会保存，也不会替换以前的档案")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::saveProfile, enabled = calibration.canSave && !calibration.saved) { Text(if (calibration.saved) "已保存" else "保存档案") }
                Button(onClick = viewModel::closeCalibration) { Text("关闭") }
            }
        }
    }
}

private fun yesNo(value: Boolean) = if (value) "是" else "否"

private fun describeEventCounts(counts: Map<PlatformEvent, Int>): String =
    if (counts.isEmpty()) "还没有" else counts.entries.joinToString("、") { "${it.key.name.lowercase()} ×${it.value}" }

internal fun describeSetting(setting: EvidenceSetting): String = when (setting) {
    EvidenceSetting.REAL_DEVICE_AND_HEADSET -> "真机 + 真实耳机（证据 E3，可以得出兼容结论）"
    EvidenceSetting.EMULATOR -> "模拟器（证据只算 E2，所有能力保持“未测试”）"
    EvidenceSetting.UNCONFIRMED -> "没有确认是真机 + 真实耳机（证据只算 E2，所有能力保持“未测试”）"
    EvidenceSetting.UNIT_TEST -> "单元测试（证据只算 E2）"
}

private fun describeStep(step: CalibrationStep): String = when (step) {
    CalibrationStep.IDLE_EVENTS -> "没有录音时，用耳机做你平常用来开始录音的手势（比如双击）。这一步只观察，不会开始录音。"
    CalibrationStep.GESTURE_WHILE_RECORDING -> "先点“开始录音”，等进入“录音中”，再用耳机做一次手势，看录音会不会停。"
    CalibrationStep.RESTART_AFTER_STOP -> "录音停下来之后，用耳机做手势，看能不能开始新的一段。"
    CalibrationStep.SCREEN_LOCKED -> "开始录音，锁屏，用耳机做手势，再解锁。看录音有没有继续、手势有没有用。"
}

private fun describeCapability(capability: ProfileCapability): String = when (capability) {
    ProfileCapability.FOREGROUND_START_STOP -> "前台开始 / 停止"
    ProfileCapability.STOP_WHILE_BLUETOOTH_MIC_ACTIVE -> "蓝牙麦克风采集中用手势停止"
    ProfileCapability.CONTINUE_RECORDING_WHEN_LOCKED -> "锁屏后录音继续"
    ProfileCapability.RESTART_AFTER_STOP_WHEN_LOCKED -> "锁屏时停止后用手势重新开始"
    ProfileCapability.START_AFTER_PROCESS_TERMINATION -> "进程被杀后用手势开始"
}
