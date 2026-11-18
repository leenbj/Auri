package app.auri.capture.calibration

import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.CaptureState
import app.auri.capture.contract.PlatformEvent
import app.auri.capture.control.ControlProfile

/** 一步校准留下的全部东西。 */
internal class StepRecord(
    val step: CalibrationStep,
    val attempt: Int,
    val ownerReportedNoEffect: Boolean,
    val observations: List<Observation>,
)

private enum class Verdict { POSITIVE, NEGATIVE, UNDETERMINED }

private class Finding(val verdict: Verdict, val evidenceId: String? = null)

/**
 * 从观察算出 Headset Profile。规则（每一条都有对应的测试，错一条就会把没测过的耳机行为记成“支持”）：
 *
 *  - 一项“检查”有三种结论：观察到成功 = POSITIVE；没观察到，但用户在这一步声明“做了手势没有任何效果” = NEGATIVE
 *    （向导从不会从“没收到事件”推断失败：没收到可能是用户没按、耳机断了）；其余 = UNDETERMINED。
 *    跳过的步骤观察与声明都已作废（见 CalibrationWizard.skip），什么都不算。同一步里观察到了成功，以观察为准。
 *  - 一项能力由一或两项检查决定：全部成功 = 支持；全部声明没效果 = 不支持；成功与没效果混合 = PARTIAL；
 *    只要有一项没有结论，整项能力就保持 NOT_TESTED。
 *  - 耳机手势指来自遥控的事件（耳机，或系统没说清来源的媒体键）；屏幕上的点击从不算。
 *    “录音中”意味着路由已经验证过是蓝牙麦克风；“前台”要求锁屏状态明确是未锁定，未知不算。
 *  - 取证环境不是真机 + 真实耳机时，所有能力都只能是 NOT_TESTED，最多带 E2 证据说明跑过（合同不允许 E2 的兼容结论）。
 *  - start_after_process_termination 在这四个场景里测不到（进程被杀后没有任何组件能收到按键），永远是未测试。
 */
internal object CalibrationFindings {
    fun profile(
        runId: String,
        setting: EvidenceSetting,
        records: List<StepRecord>,
        profileId: String,
        profileVersion: Int,
        modelHint: String?,
    ): HeadsetProfile {
        fun record(step: CalibrationStep) = records.first { it.step == step }
        val ctx = Context(runId, setting)

        val stop = ctx.check(record(CalibrationStep.GESTURE_WHILE_RECORDING), "stop-by-gesture") { obs ->
            obs.filterIsInstance<Observation.Decided>().any { it.isRemoteStop() && it.screenLocked == false }
        }
        val startAfterStop = ctx.check(record(CalibrationStep.RESTART_AFTER_STOP), "start-by-gesture-after-stop") { startedAndRecording(it, locked = false) }
        val survive = ctx.check(record(CalibrationStep.SCREEN_LOCKED), "recording-survived-lock") { survivedLock(it) }
        val restartLocked = ctx.check(record(CalibrationStep.SCREEN_LOCKED), "start-by-gesture-while-locked") { startedAndRecording(it, locked = true) }

        return HeadsetProfile(
            profileId = profileId,
            profileVersion = profileVersion,
            manufacturerHint = null,
            modelHint = modelHint,
            firmwareConstraint = null,
            platform = ProfilePlatform.ANDROID,
            rawGestureVisible = false,
            commandMapping = adoptedMapping(records),
            capabilities = HeadsetCapabilities(
                foregroundStartStop = capability(setting, listOf(stop, startAfterStop), CompatStatus.SUPPORTED_FOREGROUND),
                stopWhileBluetoothMicActive = capability(setting, listOf(stop), CompatStatus.SUPPORTED_FOREGROUND),
                continueRecordingWhenLocked = capability(setting, listOf(survive), CompatStatus.SUPPORTED_RECORDING_IN_BACKGROUND),
                restartAfterStopWhenLocked = capability(setting, listOf(restartLocked), CompatStatus.SUPPORTED_BACKGROUND_RESTART),
                startAfterProcessTermination = CapabilityRecord.untested,
            ),
        )
    }

    private class Context(val runId: String, val setting: EvidenceSetting) {
        fun check(record: StepRecord, name: String, observed: (List<Observation>) -> Boolean): Finding {
            val prefix = "calibration:$runId:${stepKey(record.step)}:attempt-${record.attempt}"
            return when {
                observed(record.observations) -> Finding(Verdict.POSITIVE, "$prefix:$name:${setting.label}")
                record.ownerReportedNoEffect -> Finding(Verdict.NEGATIVE, "$prefix:owner-reported-no-effect:${setting.label}")
                else -> Finding(Verdict.UNDETERMINED)
            }
        }
    }

    private fun stepKey(step: CalibrationStep): String = when (step) {
        CalibrationStep.IDLE_EVENTS -> "idle-events"
        CalibrationStep.GESTURE_WHILE_RECORDING -> "gesture-while-recording"
        CalibrationStep.RESTART_AFTER_STOP -> "restart-after-stop"
        CalibrationStep.SCREEN_LOCKED -> "screen-locked"
    }

    private fun capability(setting: EvidenceSetting, findings: List<Finding>, supported: CompatStatus): CapabilityRecord {
        val ids = findings.mapNotNull { it.evidenceId }
        val determined = findings.none { it.verdict == Verdict.UNDETERMINED }
        val status = when {
            !determined -> CompatStatus.NOT_TESTED
            findings.all { it.verdict == Verdict.POSITIVE } -> supported
            findings.all { it.verdict == Verdict.NEGATIVE } -> CompatStatus.UNSUPPORTED
            else -> CompatStatus.PARTIAL
        }
        return when {
            setting.level == EvidenceLevel.E3 && status != CompatStatus.NOT_TESTED -> CapabilityRecord(status, EvidenceLevel.E3, ids)
            // 兼容结论要 E3 以上的证据；E2 只能说明“跑过”，所以状态保持未测试，证据等级如实写 E2
            setting.level != EvidenceLevel.E3 && ids.isNotEmpty() -> CapabilityRecord(CompatStatus.NOT_TESTED, EvidenceLevel.E2, ids)
            else -> CapabilityRecord.untested
        }
    }

    /**
     * 录音中的耳机手势在锁屏期间的结局：
     * 锁屏开始时正在录音，并且之后（锁屏中又收到了耳机手势，或解锁时录音仍在）录音没有被非命令的原因结束。
     * 中途被路由丢失、系统中断、时长上限等结束，后面即使又有一段录音，也不算同一段录音“撑过了锁屏”。
     */
    private fun survivedLock(observations: List<Observation>): Boolean {
        var lockedWithRecording = false
        for (o in observations) {
            when (o) {
                is Observation.LockChanged ->
                    if (o.locked) {
                        lockedWithRecording = o.state == CaptureState.RECORDING
                    } else {
                        if (lockedWithRecording && o.state == CaptureState.RECORDING) return true
                        lockedWithRecording = false
                    }
                is Observation.Decided -> when {
                    !lockedWithRecording -> Unit
                    o.source == null && o.stateBefore == CaptureState.RECORDING && o.stateAfter != CaptureState.RECORDING -> lockedWithRecording = false
                    o.source.isRemote() && o.screenLocked == true && o.stateBefore == CaptureState.RECORDING -> return true
                }
                is Observation.Arrived -> Unit
            }
        }
        return false
    }

    /** 耳机手势在“已经封存过一段录音”之后开始了录音，并且录音真的起来了（到达录音中）；[locked] 指开始时的锁屏状态。 */
    private fun startedAndRecording(observations: List<Observation>, locked: Boolean): Boolean =
        observations.indices.any { i ->
            val o = observations[i]
            o is Observation.Decided && o.isRemoteStart() && o.afterFinalize && o.screenLocked == locked &&
                observations.drop(i + 1).any { it is Observation.Decided && it.stateAfter == CaptureState.RECORDING }
        }

    /**
     * 命令映射：在默认映射上，只对亲眼看到“开始过”或“停止过”录音的事件种类（play、pause、toggle）做改动——
     * 两样都做过是切换，只开始过是开始，只停止过是停止；没观察到的保持默认。下一首、上一首永远是忽略。
     */
    private fun adoptedMapping(records: List<StepRecord>): Map<PlatformEvent, BusinessCommand> {
        val decided = records.flatMap { it.observations }.filterIsInstance<Observation.Decided>()
        val started = decided.filter { it.isRemoteStart() }.mapNotNull { it.platformEvent }.toSet()
        val stopped = decided.filter { it.isRemoteStop() }.mapNotNull { it.platformEvent }.toSet()
        val observed = listOf(PlatformEvent.PLAY, PlatformEvent.PAUSE, PlatformEvent.TOGGLE).mapNotNull { kind ->
            val command = when {
                kind in started && kind in stopped -> BusinessCommand.TOGGLE
                kind in started -> BusinessCommand.START
                kind in stopped -> BusinessCommand.STOP
                else -> null
            }
            command?.let { kind to it }
        }
        return ControlProfile.generic().mapping + observed
    }
}
