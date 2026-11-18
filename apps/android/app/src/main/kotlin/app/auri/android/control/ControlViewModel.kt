package app.auri.android.control

import androidx.lifecycle.ViewModel
import app.auri.android.home.CaptureControls
import app.auri.android.home.CaptureFact
import app.auri.android.home.CoordinatorControls
import app.auri.capture.adapter.Subscription
import app.auri.capture.calibration.CalibrationPhase
import app.auri.capture.calibration.CalibrationState
import app.auri.capture.calibration.CalibrationStep
import app.auri.capture.calibration.CalibrationWizard
import app.auri.capture.calibration.CompatStatus
import app.auri.capture.calibration.CurrentEnvironment
import app.auri.capture.calibration.EvidenceLevel
import app.auri.capture.calibration.EvidenceSetting
import app.auri.capture.calibration.HeadsetCapabilities
import app.auri.capture.calibration.ProfileStore
import app.auri.capture.calibration.StepSummary
import app.auri.capture.calibration.modelHintOf
import app.auri.capture.calibration.profileIdFor
import app.auri.capture.contract.CaptureState
import app.auri.capture.contract.NormalizedControlEvent
import app.auri.capture.control.ActiveControlProfile
import app.auri.capture.control.HeadsetControlStatus
import app.auri.capture.coordinator.CaptureCoordinator
import app.auri.capture.coordinator.CaptureDecision
import app.auri.capture.coordinator.CaptureSnapshot
import app.auri.capture.feedback.Feedback
import app.auri.capture.feedback.FeedbackKind
import app.auri.platform.control.ScreenControlAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * ===================================================================================================================
 *  耳机控制（Control Mode）与校准向导的数据与操作：另一个人会在这上面做真正的界面，界面只需要读 ControlUiState、调
 *  ControlViewModel 的方法。这里的 Compose 页面（ui/ControlScreen.kt、ui/CalibrationScreen.kt）刻意简陋，只为把状态摆出来。
 * ===================================================================================================================
 *
 * 读：`ControlViewModel.state: StateFlow<ControlUiState>`（唯一的可观察入口）。
 *
 *   ControlUiState
 *   ├─ controlMode: ControlModeUi     控制模式（耳机手势能不能开始 / 停止录音）。三个互相独立的事实，不要合并成一句话：
 *   │    ├─ on                        控制模式开着吗 = 采集状态不是 DISARMED。关着时耳机的媒体按键不会触发任何东西。
 *   │    ├─ headset                   耳机按键通道：OFF 没占着 / ACTIVE 向系统要到了、在等按键 / UNAVAILABLE 系统没给。
 *   │    │                            ACTIVE 不等于“耳机手势一定能到”（系统不会通知应用别的应用抢走了媒体按键，要靠校准结果说话）；
 *   │    │                            UNAVAILABLE 时控制模式照样开着、屏幕按钮照样能用。
 *   │    └─ turningOff                用户要求关闭、但这次录音还没保存完：已发出停止，保存完回到待命才真的关（录音不丢）。
 *   ├─ capture: CaptureFact           采集状态与受阻原因（同首页）。
 *   ├─ manual: ManualControls         屏幕上的“开始 / 停止”按钮此刻能不能按。**按钮永远显示**（手动兜底），能不能按只由采集状态决定，
 *   │                                 耳机通道、控制模式、校准进行到哪一步都不影响它们。
 *   ├─ feedback: FeedbackUi?          最近一次反馈（开始 / 停止 / 没能开始）的屏幕文字；同时有振动与通知，没有声音（声音会进蓝牙通路）。
 *   ├─ profile: ProfileFact           当前在用的耳机档案：没校准过 = Generic（通用映射），校准保存过 = Calibrated(档案标识, 版本)。
 *   ├─ calibration: CalibrationUi     校准向导，三个阶段：
 *   │    ├─ Setup     设置页：取证环境 setting（模拟器永远是 EMULATOR；真机默认 UNCONFIRMED，只有用户明确确认才是
 *   │    │            REAL_DEVICE_AND_HEADSET）、是否允许确认真机、耳机型号文字。证据等级由环境决定，不由观察到的内容决定：
 *   │    │            只有“真机 + 真实耳机”才可能得出兼容结论（E3），其余一律只能是“未测试”并带 E2。
 *   │    ├─ Running   进行中：第几步（step 与 stepNumber / stepCount）、第几次尝试（attempt，重做加一）、这一步目前观察到的概况
 *   │    │            （summary：耳机事件种类与个数、是否由手势开始 / 停止、用户是否声明“没反应”）、屏幕是否锁着（screenLocked，
 *   │    │            null = 读不出来）、这一步能不能声明“没反应”（第一步只观察，不能）。
 *   │    └─ Finished  完成：五项能力各自的结论（lines，没测的就是 NOT_TESTED）、有没有可保存的结论（canSave）、是否已保存。
 *   └─ notice: ControlNotice?         需要告诉用户的一次性消息（校准已放弃、档案没保存上）；界面显示后调用 dismissNotice()。
 *
 * 写（都不阻塞，可在主线程调）：
 *   - start() / stop()                 屏幕上的“开始 / 停止”按钮（权限请求在界面层做，同首页）。
 *   - setControlMode(on)               开 / 关控制模式。关闭时若正在录音，先停止、等保存完回到待命再关；校准进行中关闭会放弃校准。
 *   - setRealDeviceConfirmed / setModelText   设置页的两个输入（只影响下一轮校准）。
 *   - startCalibration()               开始校准（控制模式没开会先开）；第一步只观察，不会因为耳机手势开始录音。
 *   - nextStep() / skipStep() / repeatStep() / reportNoEffect() / abortCalibration()
 *                                      这一步做完 / 跳过（观察作废）/ 重做（清掉这一步的观察）/ 声明“做了手势没反应” / 放弃整轮。
 *   - saveProfile() / closeCalibration()   完成后保存档案 / 关闭结果页。没有任何结论的结果不会被保存，也不会顶掉以前的档案。
 *   - dismissNotice()
 *
 * 规矩：没测的能力一律是 NOT_TESTED，“已连接 / 已配对”不是证据；校准不会改动合同或网关，档案只存在这台手机上。
 * 日志与界面里没有设备名以外的标识；转录文字与本页无关。
 * ===================================================================================================================
 */

/** 耳机控制界面对采集的全部操作：首页的开始 / 停止与快照，再加决定流与控制模式开关。生产实现是 [CoordinatorControlModeControls]，测试里用假的。 */
interface ControlModeControls : CaptureControls {
    /** 协调器对每个输入做出的决定（没有回放：订阅之前的看不到）。校准向导靠它判断“手势到底有没有开始 / 停止录音”。 */
    val decisions: SharedFlow<CaptureDecision>

    fun enableControlMode()

    fun disableControlMode()
}

class CoordinatorControlModeControls(
    private val coordinator: CaptureCoordinator,
    screen: ScreenControlAdapter,
) : ControlModeControls, CaptureControls by CoordinatorControls(coordinator, screen) {
    override val decisions: SharedFlow<CaptureDecision> get() = coordinator.decisions

    override fun enableControlMode() = coordinator.enableControlMode()

    override fun disableControlMode() = coordinator.disableControlMode()
}

data class ControlUiState(
    val controlMode: ControlModeUi,
    val capture: CaptureFact,
    val manual: ManualControls,
    val feedback: FeedbackUi?,
    val profile: ProfileFact,
    val calibration: CalibrationUi,
    val notice: ControlNotice?,
)

data class ControlModeUi(val on: Boolean, val headset: HeadsetControlStatus, val turningOff: Boolean)

data class ManualControls(val startEnabled: Boolean, val stopEnabled: Boolean)

data class FeedbackUi(val kind: FeedbackKind, val screenText: String)

sealed interface ProfileFact {
    /** 没有保存过校准结果：用通用映射（play → 开始，pause → 停止，切换 → 切换）。 */
    data object Generic : ProfileFact

    data class Calibrated(val profileId: String, val profileVersion: Int) : ProfileFact
}

/** 五项能力，与 Headset Profile 的 capabilities 一一对应（合同里每个生命周期场景单独赋值，不合并）。 */
enum class ProfileCapability { FOREGROUND_START_STOP, STOP_WHILE_BLUETOOTH_MIC_ACTIVE, CONTINUE_RECORDING_WHEN_LOCKED, RESTART_AFTER_STOP_WHEN_LOCKED, START_AFTER_PROCESS_TERMINATION }

data class CapabilityLine(val capability: ProfileCapability, val status: CompatStatus, val evidenceLevel: EvidenceLevel?)

sealed interface CalibrationUi {
    data class Setup(val setting: EvidenceSetting, val canConfirmRealDevice: Boolean, val realDeviceConfirmed: Boolean, val modelText: String) : CalibrationUi

    data class Running(
        val step: CalibrationStep,
        val stepNumber: Int,
        val stepCount: Int,
        val attempt: Int,
        val summary: StepSummary,
        val screenLocked: Boolean?,
        val canReportNoEffect: Boolean,
        val setting: EvidenceSetting,
    ) : CalibrationUi

    data class Finished(val lines: List<CapabilityLine>, val canSave: Boolean, val saved: Boolean, val setting: EvidenceSetting) : CalibrationUi
}

sealed interface ControlNotice {
    /** 校准被放弃（用户放弃，或控制模式被关闭）；没有保存任何档案。 */
    data object CalibrationAborted : ControlNotice

    /** 档案没能写进手机存储；结果还在，可以再试。当前在用的映射没有改变。 */
    data object ProfileSaveFailed : ControlNotice
}

class ControlViewModel(
    private val controls: ControlModeControls,
    headset: StateFlow<HeadsetControlStatus>,
    feedback: StateFlow<Feedback?>,
    private val screenLocked: StateFlow<Boolean?>,
    private val observeControlEvents: ((NormalizedControlEvent) -> Unit) -> Subscription,
    private val active: ActiveControlProfile,
    private val store: ProfileStore,
    private val isEmulator: Boolean,
    private val newRunId: () -> String,
    private val scope: CoroutineScope,
) : ViewModel() {
    /** 一轮进行中的校准：向导，加上把世界接到向导上的三根线（控制事件旁听、协调器决定、锁屏变化）。 */
    private class Run(val wizard: CalibrationWizard) {
        var observer: Subscription? = null
        val jobs = mutableListOf<Job>()

        fun stop() {
            observer?.cancel()
            jobs.forEach { it.cancel() }
        }
    }

    private sealed interface Phase {
        data object Setup : Phase

        data class Running(val run: Run, val wizard: CalibrationState) : Phase

        data class Finished(val run: Run, val lines: List<CapabilityLine>, val canSave: Boolean, val saved: Boolean) : Phase
    }

    private data class Local(
        val realDeviceConfirmed: Boolean = false,
        val modelText: String = "",
        val phase: Phase = Phase.Setup,
        val turningOff: Boolean = false,
        val notice: ControlNotice? = null,
        val profile: ProfileFact = ProfileFact.Generic,
    )

    private val local = MutableStateFlow(Local(profile = store.load()?.let { ProfileFact.Calibrated(it.profileId, it.profileVersion) } ?: ProfileFact.Generic))

    val state: StateFlow<ControlUiState> = combine(controls.snapshot, headset, feedback, screenLocked, local) { snapshot, headsetStatus, latest, locked, current ->
        buildState(snapshot, headsetStatus, latest, locked, current)
    }.stateIn(scope, SharingStarted.Eagerly, buildState(controls.snapshot.value, headset.value, feedback.value, screenLocked.value, local.value))

    init {
        // 等待“保存完再关”的请求，在回到待命（或受阻）时兑现；已经没有控制模式了就只是撤销请求
        scope.launch(start = CoroutineStart.UNDISPATCHED) { controls.snapshot.collect { onCaptureState(it.state) } }
    }

    // ---- 屏幕按钮 ----

    fun start() = controls.start()

    fun stop() = controls.stop()

    // ---- 控制模式 ----

    fun setControlMode(on: Boolean) {
        val captureState = controls.snapshot.value.state
        if (on) {
            local.update { it.copy(turningOff = false) }
            if (captureState == CaptureState.DISARMED) controls.enableControlMode()
            return
        }
        if (captureState == CaptureState.DISARMED) return
        abortCalibration()
        when (captureState) {
            // 协调器只在这三个状态接受“关闭”
            CaptureState.ARMED, CaptureState.ROUTING, CaptureState.BLOCKED -> controls.disableControlMode()
            // 录音中与保存中被拒绝：先停止（保存中已经在停了），等回到待命再关，这次录音不丢
            CaptureState.RECORDING, CaptureState.FINALIZING -> {
                local.update { it.copy(turningOff = true) }
                if (captureState == CaptureState.RECORDING) controls.stop()
                // 读状态到登记请求之间，保存可能已经完成：收集器那时还没有请求可兑现，这里按最新状态补一次
                onCaptureState(controls.snapshot.value.state)
            }
            CaptureState.DISARMED -> Unit
        }
    }

    private fun onCaptureState(captureState: CaptureState) {
        if (!local.value.turningOff) return
        when (captureState) {
            CaptureState.ARMED, CaptureState.BLOCKED -> if (local.getAndUpdate { it.copy(turningOff = false) }.turningOff) controls.disableControlMode()
            CaptureState.DISARMED -> local.update { it.copy(turningOff = false) }
            CaptureState.ROUTING, CaptureState.RECORDING, CaptureState.FINALIZING -> Unit
        }
    }

    // ---- 校准：设置页 ----

    fun setRealDeviceConfirmed(confirmed: Boolean) = local.update { it.copy(realDeviceConfirmed = confirmed) }

    fun setModelText(text: String) = local.update { it.copy(modelText = text) }

    // ---- 校准：向导 ----

    fun startCalibration() {
        // 正在等录音保存完再关闭控制模式：此时开出来的校准，保存一完成就没有控制模式了
        if (local.value.phase !is Phase.Setup || local.value.turningOff) return
        if (controls.snapshot.value.state == CaptureState.DISARMED) controls.enableControlMode()
        val wizard = CalibrationWizard(newRunId(), currentSetting(local.value))
        val run = Run(wizard)
        wizard.start()
        active.useTemporary(wizard.controlProfile())
        // 旁听在协调器之前看到每个控制事件（合成适配器保证），所以协调器的决定到达时向导一定已经见过对应的事件
        run.observer = observeControlEvents { wizard.onControlEvent(it) }
        run.jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) { controls.decisions.collect { wizard.onDecision(it, screenLocked.value) } }
        // 读不出来（null）不算观察，但向导之前知道的锁屏状态从此不可信，要让它知道；
        // 同时交出此刻实际的采集状态（快照比决定流新），向导所知的落后时不会把它当成“录音着”
        run.jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            screenLocked.collect { locked -> wizard.onScreenLockChanged(locked, controls.snapshot.value.state) }
        }
        run.jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            wizard.state.collect { latest ->
                local.update { current -> (current.phase as? Phase.Running)?.takeIf { it.run === run }?.let { current.copy(phase = it.copy(wizard = latest)) } ?: current }
            }
        }
        local.update { it.copy(phase = Phase.Running(run, wizard.state.value), notice = null) }
    }

    /** 这一步做完了。 */
    fun nextStep(shown: CalibrationStep? = null) = advance(shown) { it.next() }

    /** 跳过这一步：它的观察作废，对应的能力保持未测试。 */
    fun skipStep(shown: CalibrationStep? = null) = advance(shown) { it.skip() }

    fun repeatStep() {
        runningWizard()?.repeat(currentEnvironment())
    }

    fun reportNoEffect() {
        runningWizard()?.reportNoEffect(currentEnvironment())
    }

    // 此刻实际的采集状态与锁屏状态：向导自己所知的来自协调器的决定与锁屏通知，送到向导有时延，声明与重做以这里读到的为准
    private fun currentEnvironment() = CurrentEnvironment(controls.snapshot.value.state, screenLocked.value)

    fun abortCalibration() {
        val running = local.value.phase as? Phase.Running ?: return
        running.run.wizard.abort()
        endRun(running.run)
        local.update { it.copy(phase = Phase.Setup, notice = ControlNotice.CalibrationAborted) }
    }

    fun closeCalibration() {
        if (local.value.phase is Phase.Finished) local.update { it.copy(phase = Phase.Setup) }
    }

    /** 保存校准结果：档案标识来自型号文字，同一型号再次保存版本加一。没有结论的结果不保存；保存失败时当前映射不变、结果还在。 */
    fun saveProfile() {
        val finished = local.value.phase as? Phase.Finished ?: return
        if (!finished.canSave || finished.saved) return
        val hint = modelHintOf(local.value.modelText)
        val profileId = profileIdFor(hint)
        val earlier = try {
            store.load()
        } catch (e: Exception) {
            null
        }
        val version = if (earlier?.profileId == profileId) earlier.profileVersion + 1 else 1
        val profile = finished.run.wizard.buildProfile(profileId, version, hint) ?: return
        try {
            store.save(profile)
        } catch (e: Exception) {
            local.update { it.copy(notice = ControlNotice.ProfileSaveFailed) }
            return
        }
        active.useSaved(profile.controlProfile())
        local.update { it.copy(phase = finished.copy(saved = true), profile = ProfileFact.Calibrated(profile.profileId, profile.profileVersion)) }
    }

    fun dismissNotice() = local.update { it.copy(notice = null) }

    override fun onCleared() {
        (local.value.phase as? Phase.Running)?.let { endRun(it.run) }
        scope.cancel()
    }

    // ---- 内部 ----

    private fun runningWizard(): CalibrationWizard? = (local.value.phase as? Phase.Running)?.run?.wizard

    /** [shown]：点击时屏幕上显示的步骤。连点时第二下还带着旧步骤，不能作用到已经翻过去的下一步。 */
    private fun advance(shown: CalibrationStep?, action: (CalibrationWizard) -> Boolean) {
        val running = local.value.phase as? Phase.Running ?: return
        val wizard = running.run.wizard
        if (shown != null && wizard.state.value.step != shown) return
        if (!action(wizard)) return
        if (wizard.state.value.phase == CalibrationPhase.COMPLETED) finishRun(running.run) else active.useTemporary(wizard.controlProfile())
    }

    /** 整轮校准结束：撤掉向导的临时映射与旁听，算出五项结论给结果页。 */
    private fun finishRun(run: Run) {
        endRun(run)
        val hint = modelHintOf(local.value.modelText)
        val preview = run.wizard.buildProfile(profileIdFor(hint), 1, hint)
        val capabilities = preview?.capabilities
        val lines = if (capabilities == null) emptyList() else linesOf(capabilities)
        // 没有任何非“未测试”的结论就没有可保存的东西：保存它只会顶掉以前的档案
        val canSave = preview != null && preview.capabilities.all.any { !it.isUntested }
        local.update { it.copy(phase = Phase.Finished(run, lines, canSave, saved = false)) }
    }

    private fun endRun(run: Run) {
        run.stop()
        active.useTemporary(null)
    }

    private fun currentSetting(current: Local): EvidenceSetting = when {
        isEmulator -> EvidenceSetting.EMULATOR
        current.realDeviceConfirmed -> EvidenceSetting.REAL_DEVICE_AND_HEADSET
        else -> EvidenceSetting.UNCONFIRMED
    }

    private fun linesOf(capabilities: HeadsetCapabilities): List<CapabilityLine> = listOf(
        ProfileCapability.FOREGROUND_START_STOP to capabilities.foregroundStartStop,
        ProfileCapability.STOP_WHILE_BLUETOOTH_MIC_ACTIVE to capabilities.stopWhileBluetoothMicActive,
        ProfileCapability.CONTINUE_RECORDING_WHEN_LOCKED to capabilities.continueRecordingWhenLocked,
        ProfileCapability.RESTART_AFTER_STOP_WHEN_LOCKED to capabilities.restartAfterStopWhenLocked,
        ProfileCapability.START_AFTER_PROCESS_TERMINATION to capabilities.startAfterProcessTermination,
    ).map { (capability, record) -> CapabilityLine(capability, record.status, record.evidenceLevel) }

    private fun buildState(snapshot: CaptureSnapshot, headset: HeadsetControlStatus, latest: Feedback?, locked: Boolean?, current: Local) = ControlUiState(
        controlMode = ControlModeUi(on = snapshot.state != CaptureState.DISARMED, headset = headset, turningOff = current.turningOff),
        capture = CaptureFact(snapshot.state, snapshot.blockedReason),
        manual = ManualControls(startEnabled = snapshot.state in START_STATES, stopEnabled = snapshot.state in STOP_STATES),
        feedback = latest?.let { FeedbackUi(it.kind, it.screenText) },
        profile = current.profile,
        calibration = calibrationUi(current, locked),
        notice = current.notice,
    )

    private fun calibrationUi(current: Local, locked: Boolean?): CalibrationUi = when (val phase = current.phase) {
        Phase.Setup -> CalibrationUi.Setup(
            setting = currentSetting(current),
            canConfirmRealDevice = !isEmulator,
            realDeviceConfirmed = current.realDeviceConfirmed && !isEmulator,
            modelText = current.modelText,
        )
        is Phase.Running -> {
            val step = phase.wizard.step ?: CalibrationStep.entries.last()
            CalibrationUi.Running(
                step = step,
                stepNumber = step.ordinal + 1,
                stepCount = CalibrationStep.entries.size,
                attempt = phase.wizard.attempt,
                summary = phase.wizard.summary,
                screenLocked = locked,
                canReportNoEffect = step != CalibrationStep.IDLE_EVENTS,
                setting = phase.run.wizard.setting,
            )
        }
        is Phase.Finished -> CalibrationUi.Finished(phase.lines, phase.canSave, phase.saved, phase.run.wizard.setting)
    }

    private companion object {
        /** 没在录也没在封存的状态都能开始；BLOCKED 时再按“开始”就是重试（与首页一致）。 */
        val START_STATES = setOf(CaptureState.DISARMED, CaptureState.ARMED, CaptureState.BLOCKED)

        /** 准备阶段（取消）与录音中都能停止；封存中不能（已经在停了）。 */
        val STOP_STATES = setOf(CaptureState.ROUTING, CaptureState.RECORDING)
    }
}
