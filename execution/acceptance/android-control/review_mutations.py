"""Run the review regression mutants only in a disposable `git archive` snapshot.

Run with the Android environment sourced and Python 3.12. Each mutant is restored
before the next one; a compiler error or a different failing test is not a kill.
"""

from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
ANDROID = ROOT / "apps/android"
if (ROOT / ".git").exists() or subprocess.run(
    ["git", "-C", str(ROOT), "rev-parse", "--is-inside-work-tree"],
    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False,
).returncode == 0:
    sys.exit("Refusing to mutate a worktree; run from a git archive snapshot.")

CAPTURE = "apps/android/capture/src/main/kotlin/app/auri/capture/"
PLATFORM = "apps/android/platform/src/main/kotlin/app/auri/platform/"
PLATFORM_TEST = "apps/android/platform/src/test/kotlin/app/auri/platform/"
APP = "apps/android/app/src/main/kotlin/app/auri/android/"

# (name, Gradle module, test class, expected failing test, [(path, old, new), ...])
MUTANTS = [
    ("F1", "app", "app.auri.android.home.HomeFlowTest", "manual capture while control mode is off", [
        (CAPTURE + "coordinator/CaptureCoordinator.kt", "if (!context.manual && !control.enableUserControlMode())", "if (!control.enableUserControlMode())"),
    ]),
    ("F2", "platform", "app.auri.platform.control.HeadsetControlChainTest", "start and toggle pressed while finalizing", [
        (CAPTURE + "control/HeadsetEventNormalizer.kt", "(issued == null || input.receivedAtNs - issued > config.maxIssuedAgeNs)", "(issued == null)"),
        (CAPTURE + "control/HeadsetEventNormalizer.kt", "val gestureNs = issued ?: input.receivedAtNs", "val gestureNs = issued?.takeIf { input.receivedAtNs - it <= config.maxIssuedAgeNs } ?: input.receivedAtNs"),
    ]),
    ("F3", "platform", "app.auri.platform.control.HeadsetControlChainTest", "turning control mode off rejects a racing headset start", [
        (CAPTURE + "coordinator/CaptureCoordinator.kt", "if (disableWhenIdle && (machineEvent == CaptureEvent.START", "if (false && (machineEvent == CaptureEvent.START"),
    ]),
    ("F4", "platform", "app.auri.platform.control.HeadsetControlChainTest", "distinct stop pause and toggle inside the debounce window", [
        (CAPTURE + "control/HeadsetEventNormalizer.kt", "private var sequence = 0L", "private var sequence = 0L\n    private var mutantLastNs: Long? = null"),
        (CAPTURE + "control/HeadsetEventNormalizer.kt", "val gestureNs = issued ?: input.receivedAtNs", "val gestureNs = issued ?: input.receivedAtNs\n        if (mutantLastNs != null && kotlin.math.abs(gestureNs - mutantLastNs!!) < 400_000_000L) return NormalizeResult.Drop(DropReason.DUPLICATE_RAW_EVENT)\n        mutantLastNs = gestureNs"),
    ]),
    ("F5", "platform", "app.auri.platform.feedback.FeedbackNotificationsTest", "previously audible channel is replaced", [
        (PLATFORM + "feedback/AndroidFeedbackPlayer.kt", "text, silent = true, onlyAlertOnce = true", "text, silent = false, onlyAlertOnce = true"),
    ]),
    ("F6-source", "platform", "app.auri.platform.control.MediaKeysTest", "an external keyboard is not attributed", [
        (CAPTURE + "control/HeadsetEventNormalizer.kt", "if (input.fromBluetoothHeadset) ControlSource.HEADSET_REMOTE", "if (input.fromExternalDevice == true) ControlSource.HEADSET_REMOTE"),
    ]),
    ("F6-evidence", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "unattributed media keys do not certify", [
        (CAPTURE + "calibration/Observation.kt", "internal fun ControlSource?.isHeadset(): Boolean = this == ControlSource.HEADSET_REMOTE", "internal fun ControlSource?.isHeadset(): Boolean = isRemote()"),
    ]),
    ("F6-lock", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "unattributed stop while locked does not certify", [
        (CAPTURE + "calibration/CalibrationFindings.kt", "o.source.isHeadset() && o.screenLocked == true && o.stateBefore == CaptureState.RECORDING", "o.source.isRemote() && o.screenLocked == true && o.stateBefore == CaptureState.RECORDING"),
    ]),
    ("F7", "platform", "app.auri.platform.system.ScreenLockEvidenceTest", "screen off without keyguard is unknown", [
        (PLATFORM + "system/AndroidScreenLockMonitor.kt", "interactive -> false\n    else -> null", "interactive -> false\n    else -> true"),
    ]),
    ("F8", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "a locked stop after the first locked start", [
        (CAPTURE + "calibration/CalibrationFindings.kt", "(!locked || lockedStopBefore(observations, i))", "true"),
    ]),
    ("F9", "platform", "app.auri.platform.control.NoSoundIntoBluetoothTest", "守卫自己能抓到", [
        (PLATFORM_TEST + "control/NoSoundIntoBluetoothTest.kt", r"\bsetSound\s*\((?!", r"\bsetSound\((?!"),
    ]),
    ("K1", "platform", "app.auri.platform.control.HeadsetControlAdapterTest", "关闭之后才送到的旧回调", [
        (PLATFORM + "control/HeadsetControlAdapter.kt", "if (!active || token != activation) return", "if (!active) return"),
    ]),
    ("K2", "platform", "app.auri.platform.control.HeadsetControlAdapterTest", "开启后按键变成带来源", [
        (PLATFORM + "control/HeadsetControlAdapter.kt", "normalizer.resetGestureState()\n            release()", "normalizer.resetGestureState()"),
    ]),
    ("K3", "platform", "app.auri.platform.control.HeadsetControlAdapterTest", "激活失败：返回 false", [
        (PLATFORM + "control/HeadsetControlAdapter.kt", "if (acquired) {", "if (true) {"),
    ]),
    ("K5", "capture", "app.auri.capture.control.HeadsetEventNormalizerTest", "系统不能归属耳机时来源", [
        (CAPTURE + "control/HeadsetEventNormalizer.kt", "if (input.fromBluetoothHeadset) ControlSource.HEADSET_REMOTE else ControlSource.UNKNOWN_REMOTE", "ControlSource.HEADSET_REMOTE"),
    ]),
    ("K6", "capture", "app.auri.capture.control.HeadsetEventNormalizerTest", "事件标识与序号唯一递增", [
        (CAPTURE + "control/HeadsetEventNormalizer.kt", "sequence += 1", "sequence += 0"),
    ]),
    ("K7", "capture", "app.auri.capture.control.HeadsetEventNormalizerTest", "同一个原始事件被递送两次", [
        (CAPTURE + "control/HeadsetEventNormalizer.kt", "if (identity in recent) return NormalizeResult.Drop", "if (false && identity in recent) return NormalizeResult.Drop"),
    ]),
    ("K8", "capture", "app.auri.capture.control.HeadsetEventNormalizerTest", "停止键只能停止", [
        (CAPTURE + "control/HeadsetEventNormalizer.kt", "return if (kind == MediaKind.STOP && mapped != BusinessCommand.IGNORE) BusinessCommand.STOP else mapped", "return mapped"),
    ]),
    ("K10", "platform", "app.auri.platform.control.HeadsetControlChainTest", "封存期间按下、封存完成之后才送到的切换键", [
        (CAPTURE + "coordinator/CaptureCoordinator.kt", "event.monotonicNs < finalizedAt)", "false)"),
    ]),
    ("K11", "platform", "app.auri.platform.control.HeadsetControlChainTest", "封存期间按下、封存完成之后才送到的切换键", [
        (CAPTURE + "control/HeadsetEventNormalizer.kt", "val gestureNs = issued ?: input.receivedAtNs", "val gestureNs = input.receivedAtNs"),
    ]),
    ("S1", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "跳过的步骤什么都不算", [
        (CAPTURE + "calibration/CalibrationWizard.kt", "if (outcome == StepOutcome.SKIPPED) data.reset()", "if (false) data.reset()"),
    ]),
    ("S2", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "重做之后只看最后一次尝试", [
        (CAPTURE + "calibration/CalibrationWizard.kt", "attempt += 1\n            reset()", "attempt += 1"),
    ]),
    ("S3", "capture", "app.auri.capture.calibration.CalibrationWizardStateTest", "每一步都能放弃", [
        (CAPTURE + "calibration/CalibrationWizard.kt", "if (phase != CalibrationPhase.COMPLETED) return null", "if (phase != CalibrationPhase.COMPLETED && phase != CalibrationPhase.ABORTED) return null"),
    ]),
    ("S4", "capture", "app.auri.capture.calibration.CalibrationWizardStateTest", "只适用于第二到第四个场景", [
        (CAPTURE + "calibration/CalibrationWizard.kt", "if (step == CalibrationStep.IDLE_EVENTS) return false", "if (false) return false"),
    ]),
    ("S5", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "没有完成的校准出不了档案", [
        (CAPTURE + "calibration/CalibrationWizard.kt", "if (phase != CalibrationPhase.COMPLETED) return null", "if (phase != CalibrationPhase.COMPLETED && phase != CalibrationPhase.IN_PROGRESS) return null"),
    ]),
    ("S6", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "屏幕上点开始和停止从不算耳机手势", [
        (CAPTURE + "calibration/Observation.kt", "internal fun ControlSource?.isHeadset(): Boolean = this == ControlSource.HEADSET_REMOTE", "internal fun ControlSource?.isHeadset(): Boolean = this == ControlSource.HEADSET_REMOTE || this == ControlSource.SCREEN_CONTROL"),
    ]),
    ("S8", "capture", "app.auri.capture.calibration.CalibrationWizardStateTest", "校准期间的临时映射", [
        (CAPTURE + "calibration/CalibrationWizard.kt", "ControlProfile.generic().observeOnly().copy(profileId = \"calibration-observe-only\")", "ControlProfile.generic().copy(profileId = \"calibration-observe-only\")"),
    ]),
    ("W1", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "同样的观察放在模拟器", [
        (CAPTURE + "calibration/CalibrationFindings.kt", "setting.level == EvidenceLevel.E3 && status != CompatStatus.NOT_TESTED", "status != CompatStatus.NOT_TESTED"),
    ]),
    ("W2", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "没有任何观察：每一步都点完成", [
        (CAPTURE + "calibration/CalibrationFindings.kt", "else -> Finding(Verdict.UNDETERMINED)", 'else -> Finding(Verdict.NEGATIVE, "$prefix:mutant")'),
    ]),
    ("W3", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "真机 + 真实耳机完整做完", [
        (CAPTURE + "calibration/CalibrationFindings.kt", "startAfterProcessTermination = CapabilityRecord.untested", "startAfterProcessTermination = capability(setting, listOf(stop), CompatStatus.SUPPORTED_FOREGROUND)"),
    ]),
    ("W4", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "前台开始停止要两项都有结论", [
        (CAPTURE + "calibration/CalibrationFindings.kt", "else -> CompatStatus.PARTIAL", "else -> CompatStatus.UNSUPPORTED"),
    ]),
    ("W5", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "锁屏开始时没在录音", [
        (CAPTURE + "calibration/CalibrationFindings.kt", "lockedWithRecording = o.state == CaptureState.RECORDING", "lockedWithRecording = true"),
    ]),
    ("W6", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "只做了停止：停止能力有结论", [
        (CAPTURE + "calibration/CalibrationFindings.kt", "val determined = findings.none { it.verdict == Verdict.UNDETERMINED }", "val determined = true"),
    ]),
    ("W7", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "命令映射只采用亲眼看到的", [
        (CAPTURE + "calibration/CalibrationFindings.kt", "kind in started && kind in stopped -> BusinessCommand.TOGGLE", "kind in started && kind in stopped -> BusinessCommand.START"),
    ]),
    ("R1", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "锁屏步骤只声明没效果", [
        (CAPTURE + "calibration/CalibrationFindings.kt", '"recording-survived-lock", declaredAbout = { false }', '"recording-survived-lock", declaredAbout = { it.wasStop(locked = true) }'),
    ]),
    ("R2", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "锁屏步骤里手势停止发生在没锁屏的时候", [
        (CAPTURE + "calibration/CalibrationFindings.kt", "declaredAbout = { stoppedWhileLocked && it.wasStart(locked = true) }", "declaredAbout = { it.wasStart(locked = true) }"),
    ]),
    ("R3", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "锁屏步骤里手势停止发生在没锁屏的时候", [
        (CAPTURE + "calibration/CalibrationFindings.kt", "any { it.isRemoteStop() && it.screenLocked == true }", "any { it.isRemoteStop() }"),
    ]),
    ("R4", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "手势开始之后准备没成功", [
        (CAPTURE + "calibration/CalibrationFindings.kt", ".firstOrNull { it.stateBefore == CaptureState.ROUTING && it.stateAfter != CaptureState.ROUTING }", ".firstOrNull { it.stateAfter == CaptureState.RECORDING }"),
    ]),
    ("R5", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "手势开始之后准备没成功", [
        (CAPTURE + "calibration/CalibrationFindings.kt", ".firstOrNull { it.stateBefore == CaptureState.ROUTING && it.stateAfter != CaptureState.ROUTING }", ".lastOrNull { it.stateBefore == CaptureState.ROUTING && it.stateAfter != CaptureState.ROUTING }"),
    ]),
    ("R7", "platform", "app.auri.platform.control.CompositeControlAdapterTest", "两个来源同时来事件", [
        (PLATFORM + "control/CompositeControlAdapter.kt", "synchronized(deliveryLock) {", "run {"),
    ]),
    ("R8", "platform", "app.auri.platform.control.HeadsetControlAdapterTest", "已经过了校验、正在递送的事件", [
        (PLATFORM + "control/HeadsetControlAdapter.kt", "synchronized(lock) {\n            if (!active || token != activation) return", "run {\n            if (!active || token != activation) return"),
    ]),
    ("R9", "platform", "app.auri.platform.storage.FileProfileStoreTest", "写临时文件这一步失败时也要清理", [
        (PLATFORM + "storage/FileProfileStore.kt", "temp.delete()", "Unit"),
    ]),
    ("R11", "app", "app.auri.android.control.ControlViewModelTest", "正在等录音保存完再关闭控制模式", [
        (APP + "control/ControlViewModel.kt", "local.value.phase !is Phase.Setup || local.value.turningOff", "local.value.phase !is Phase.Setup"),
    ]),
    ("R12", "app", "app.auri.android.control.ControlViewModelTest", "同一步被连点两下", [
        (APP + "control/ControlViewModel.kt", "if (shown != null && wizard.state.value.step != shown) return", "if (false) return"),
    ]),
    ("N1", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "第二步里中途锁了屏", [
        (CAPTURE + "calibration/CalibrationFindings.kt", "fun wasStop(locked: Boolean) = lockSteady && state", "fun wasStop(locked: Boolean) = state"),
    ]),
    ("N1b", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "第三步里中途锁了屏", [
        (CAPTURE + "calibration/CalibrationFindings.kt", "fun wasStart(locked: Boolean) = lockSteady && (state", "fun wasStart(locked: Boolean) = (state"),
    ]),
    ("N7", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "向导所知的采集状态落后于实际", [
        (CAPTURE + "calibration/CalibrationWizard.kt", "now.state == trackedState && now.screenLocked == knownLock", "now.screenLocked == knownLock"),
    ]),
    ("N8", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "事件已经到了、决定还没到时点", [
        (CAPTURE + "calibration/CalibrationWizard.kt", "if (pairing.size > 0) return false", "if (false) return false"),
    ]),
    ("N9", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "重做之后旧的决定才送到", [
        (CAPTURE + "calibration/CalibrationWizard.kt", "pairing.discard()\n        if (now != null)", "pairing.clear()\n        if (now != null)"),
    ]),
    ("N10", "capture", "app.auri.capture.calibration.CalibrationObservationTest", "步骤边界：上一步里还没等到决定的屏幕停止", [
        (CAPTURE + "calibration/CalibrationWizard.kt", "pairing.discard()\n        val following", "pairing.clear()\n        val following"),
    ]),
    ("N11", "app", "app.auri.android.control.ControlViewModelTest", "unknown lock reading between two locked readings", [
        (APP + "control/ControlViewModel.kt", "screenLocked.collect { locked -> wizard.onScreenLockChanged(locked, controls.snapshot.value.state) }", "screenLocked.collect { locked -> if (locked != null) wizard.onScreenLockChanged(locked, controls.snapshot.value.state) }"),
    ]),
    ("N12", "app", "app.auri.android.control.ControlViewModelTest", "声明没效果时，界面把实际的采集状态交给向导", [
        (APP + "control/ControlViewModel.kt", "runningWizard()?.reportNoEffect(currentEnvironment())", "runningWizard()?.reportNoEffect()"),
    ]),
    ("N13", "platform", "app.auri.platform.storage.FileProfileStoreTest", "替换这一步抛的不是 IOException", [
        (PLATFORM + "storage/FileProfileStore.kt", "} catch (e: Exception) {\n            // 不只是 IOException", "} catch (e: IOException) {\n            // 不只是 IOException"),
    ]),
    ("N14", "platform", "app.auri.platform.control.CompositeControlAdapterTest", "编号也在锁里取", [
        (PLATFORM + "control/CompositeControlAdapter.kt", "synchronized(deliveryLock) {\n            val numbered = event.copy(sequence = nextSequence())", "val numbered = event.copy(sequence = nextSequence())\n        synchronized(deliveryLock) {"),
    ]),
    ("Q1", "capture", "app.auri.capture.calibration.ControlDecisionPairingTest", "放弃归属之后", [
        (CAPTURE + "calibration/Observation.kt", "fun discard() = pending.forEach { it.live = false }", "fun discard() = pending.clear()"),
    ]),
    ("Q2", "capture", "app.auri.capture.calibration.ControlDecisionPairingTest", "放弃归属之后", [
        (CAPTURE + "calibration/Observation.kt", "return if (entry.live) entry.event else null", "return entry.event"),
    ]),
    ("Q3", "capture", "app.auri.capture.calibration.ControlDecisionPairingTest", "放弃归属之后", [
        (CAPTURE + "calibration/Observation.kt", "val size: Int get() = pending.count { it.live }", "val size: Int get() = pending.size"),
    ]),
    ("S1b", "capture", "app.auri.capture.calibration.CalibrationObservationTest", "有触发器的内部错误", [
        (CAPTURE + "calibration/CalibrationWizard.kt", "if (decision.trigger == null && decision.cause == Cause.INTERNAL_ERROR) pairing.clear()", "if (decision.cause == Cause.INTERNAL_ERROR) pairing.clear()"),
    ]),
    ("N2", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "锁屏变化之后又有了新的采集决定", [
        (CAPTURE + "calibration/CalibrationWizard.kt", "lockMovedSinceBaseline = screenLocked == null", "lockMovedSinceBaseline = lockMovedSinceBaseline || screenLocked == null"),
    ]),
    ("N3", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "第二步里中途锁了屏", [
        (CAPTURE + "calibration/CalibrationWizard.kt", "if (locked != knownLock) lockMovedSinceBaseline = true", "if (locked != knownLock) lockMovedSinceBaseline = false"),
    ]),
    ("N4", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "锁屏步骤里解锁时读不出锁屏状态", [
        (CAPTURE + "calibration/CalibrationWizard.kt", "if (locked != knownLock) lockMovedSinceBaseline = true", "if (locked == null) return\n        if (locked != knownLock) lockMovedSinceBaseline = true"),
    ]),
    ("N6", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "前台手势开始，路由到锁屏之后才确认", [
        (CAPTURE + "calibration/CalibrationFindings.kt", "it.stateAfter == CaptureState.RECORDING && it.screenLocked == locked", "it.stateAfter == CaptureState.RECORDING"),
    ]),
    ("Q5", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "一个不改变状态的无关决定", [
        (CAPTURE + "calibration/CalibrationWizard.kt", "if (decision.stateBefore != decision.stateAfter) {", "if (true) {"),
    ]),
    ("Q6", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "decisions without state changes reveal a lock and unlock", [
        (CAPTURE + "calibration/CalibrationWizard.kt", "knownLock = screenLocked\n            lockMovedSinceBaseline = true", "knownLock = screenLocked\n            lockMovedSinceBaseline = false"),
    ]),
    ("Q7", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "第四步：锁屏后读数读不出来", [
        (CAPTURE + "calibration/CalibrationFindings.kt", "o.stateBefore == CaptureState.RECORDING && o.stateAfter != CaptureState.RECORDING -> lockedWithRecording = false", "o.stateBefore == CaptureState.RECORDING && o.stateAfter != CaptureState.RECORDING -> Unit"),
    ]),
    ("Q8", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "声明那一刻界面读到的锁屏状态与向导所知的对不上", [
        (CAPTURE + "calibration/CalibrationWizard.kt", "now.state == trackedState && now.screenLocked == knownLock", "now.state == trackedState"),
    ]),
    ("Q10", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "中途锁过屏再解锁之后点“重做”", [
        (CAPTURE + "calibration/CalibrationWizard.kt", "pairing.discard()\n        if (now != null) {", "pairing.discard()\n        if (now != null && false) {"),
    ]),
    ("Q10a", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "点“重做”时界面读到的环境与向导所知的都不同", [
        (CAPTURE + "calibration/CalibrationWizard.kt", "trackedState = now.state", "Unit"),
    ]),
    ("Q10b", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "点“重做”时界面读到的环境与向导所知的都不同", [
        (CAPTURE + "calibration/CalibrationWizard.kt", "knownLock = now.screenLocked", "Unit"),
    ]),
    ("Q10c", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "点“重做”时界面读到的环境与向导所知的都不同", [
        (CAPTURE + "calibration/CalibrationWizard.kt", "lockMovedSinceBaseline = now.screenLocked == null", "Unit"),
    ]),
    ("Q16", "app", "app.auri.android.control.ControlViewModelTest", "声明没效果时，界面也把实际的锁屏状态交给向导", [
        (APP + "control/ControlViewModel.kt", "runningWizard()?.reportNoEffect(currentEnvironment())", "runningWizard()?.reportNoEffect()"),
    ]),
    ("Q17", "app", "app.auri.android.control.ControlViewModelTest", "中途锁过屏再解锁之后点“重做”", [
        (APP + "control/ControlViewModel.kt", "runningWizard()?.repeat(currentEnvironment())", "runningWizard()?.repeat()"),
    ]),
    ("Q18", "platform", "app.auri.platform.storage.FileProfileStoreTest", "替换这一步抛的不是 IOException", [
        (PLATFORM + "storage/FileProfileStore.kt", 'temp.delete()\n            throw e', 'temp.delete()\n            throw UnsupportedOperationException("mutant")'),
    ]),
    ("S1-old", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "命令处理时适配器抛了异常", [
        (CAPTURE + "calibration/CalibrationWizard.kt", "if (decision.trigger == null && decision.cause == Cause.INTERNAL_ERROR) pairing.clear()", "if (decision.trigger == null && decision.cause == Cause.INTERNAL_ERROR) Unit"),
    ]),
    ("S2-old", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "锁屏通知送到时，界面读到的实际采集状态", [
        (CAPTURE + "calibration/CalibrationWizard.kt", "val state = if (observedState == null || observedState == trackedState) trackedState else null", "val state = trackedState"),
    ]),
    ("S3-old", "capture", "app.auri.capture.calibration.CalibrationFindingsTest", "锁屏通知送到时，界面读到的实际采集状态", [
        (CAPTURE + "calibration/CalibrationWizard.kt", "val state = if (observedState == null || observedState == trackedState) trackedState else null", "val state = observedState ?: trackedState"),
    ]),
    ("S4-old", "app", "app.auri.android.control.ControlViewModelTest", "锁屏通知送到向导时，界面把此刻实际的采集状态", [
        (APP + "control/ControlViewModel.kt", "wizard.onScreenLockChanged(locked, controls.snapshot.value.state)", "wizard.onScreenLockChanged(locked)"),
    ]),
]


def memory_sample():
    swap = subprocess.check_output(["sysctl", "vm.swapusage"], text=True)
    pressure = subprocess.check_output(["memory_pressure"], text=True)
    match = re.search(r"used = ([0-9.]+)([MG])", swap)
    free = re.search(r"free percentage:\s*(\d+)%", pressure, re.I)
    assert match and free, "Could not read memory guard"
    used_mb = float(match.group(1)) * (1024 if match.group(2) == "G" else 1)
    return used_mb, int(free.group(1))


def main():
    previous = None
    failures = []
    requested = set(sys.argv[1:])
    known = {mutant[0] for mutant in MUTANTS}
    if requested - known:
        raise ValueError(f"Unknown mutants: {sorted(requested - known)}")
    selected = [mutant for mutant in MUTANTS if not requested or mutant[0] in requested]
    for name, module, test_class, expected_test, edits in selected:
        started = time.monotonic()
        while True:
            used, free = memory_sample()
            growing = previous is not None and time.monotonic() - previous[0] >= 60 and used - previous[1] > 500
            if free >= 20 and not growing:
                break
            if time.monotonic() - started >= 900:
                raise RuntimeError(f"Memory guard did not recover before {name}: swap={used} MiB free={free}%")
            previous = (time.monotonic(), used)
            print(f"{name}: waiting for memory, swap={used} MiB free={free}%", flush=True)
            time.sleep(60)
            time.sleep(60)
        if previous is None or time.monotonic() - previous[0] >= 60:
            previous = (time.monotonic(), used)
        originals = {}
        try:
            for relative, old, new in edits:
                path = ROOT / relative
                originals.setdefault(path, path.read_text())
                text = path.read_text()
                assert text.count(old) == 1, f"{name}: expected one match in {relative}"
                path.write_text(text.replace(old, new, 1))
            task = "test" if module == "capture" else "testDebugUnitTest"
            cmd = ["./gradlew", "--offline", "--no-daemon", "-Pkotlin.compiler.execution.strategy=in-process",
                   "-Dorg.gradle.workers.max=2", f":{module}:clean{task[0].upper() + task[1:]}",
                   f":{module}:{task}", "--tests", test_class]
            result = subprocess.run(cmd, cwd=ANDROID, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=900)
            result_dir = ANDROID / module / "build/test-results" / task
            failed_tests = []
            for xml in result_dir.glob("TEST-*.xml"):
                for case in ET.parse(xml).getroot().iter("testcase"):
                    if case.find("failure") is not None:
                        failed_tests.append(case.attrib.get("name", ""))
            killed = result.returncode != 0 and any(expected_test in test for test in failed_tests)
            print(f"{name}: {'KILLED' if killed else 'NOT KILLED'}; matching failures={sum(expected_test in t for t in failed_tests)}", flush=True)
            if not killed:
                failures.append(name)
                print(result.stdout[-3000:], flush=True)
        finally:
            for path, original in originals.items():
                path.write_text(original)
    print(f"Mutation checks: {len(selected) - len(failures)}/{len(selected)} killed", flush=True)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
