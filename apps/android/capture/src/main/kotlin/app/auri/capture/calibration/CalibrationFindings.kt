package app.auri.capture.calibration

import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.CaptureState
import app.auri.capture.contract.PlatformEvent
import app.auri.capture.control.ControlProfile

/** 一步校准留下的全部东西。 */
internal class StepRecord(
    val step: CalibrationStep,
    val attempt: Int,
    /** 用户在这一步声明了“没效果”时，那一刻的环境；没声明为 null。 */
    val declared: DeclaredAttempt?,
    val observations: List<Observation>,
)

/**
 * 用户声明“做了手势，没有任何效果”那一刻的环境。声明说的是刚做的那次手势，而那次手势想停止还是想开始、
 * 是不是在锁屏时做的，只有这一刻说得清：录音中的手势是想停止，就绪或受阻时的手势是想开始。不知道（null）一律不算。
 *
 * [lockSteady]：自上一个起点（状态变化的采集决定，或用户点“重做”）以来，锁屏状态没有变过（也没有读不出来过）。
 * 变过的话，声明时的锁屏状态代表不了那次手势做的时候——比如锁屏时做了手势没反应、解锁回来才点“没反应”，
 * 声明时屏幕是亮的，手势却是在锁屏时做的。
 */
internal class DeclaredAttempt(val state: CaptureState?, val screenLocked: Boolean?, val lockSteady: Boolean) {
    fun wasStop(locked: Boolean) = lockSteady && state == CaptureState.RECORDING && screenLocked == locked

    fun wasStart(locked: Boolean) = lockSteady && (state == CaptureState.ARMED || state == CaptureState.BLOCKED) && screenLocked == locked
}

private enum class Verdict { POSITIVE, NEGATIVE, UNDETERMINED }

private class Finding(val verdict: Verdict, val evidenceId: String? = null)

/**
 * 从观察算出 Headset Profile。规则（每一条都有对应的测试，错一条就会把没测过的耳机行为记成“支持”）：
 *
 *  - 一项“检查”有三种结论：观察到成功 = POSITIVE；没观察到，但用户在这一步声明“做了手势没有任何效果” = NEGATIVE
 *    （向导从不会从“没收到事件”推断失败：没收到可能是用户没按、耳机断了）；其余 = UNDETERMINED。
 *    跳过的步骤观察与声明都已作废（见 CalibrationWizard.skip），什么都不算。同一步里观察到了成功，以观察为准。
 *    声明只回答它所指的那次手势，那次手势是什么由声明那一刻的环境（[DeclaredAttempt]：采集状态 + 锁屏状态）决定：
 *    录音中的手势想停止，就绪或受阻时的手势想开始；亮屏的检查（第二、三步）不采纳锁屏时的声明，锁屏的检查反之。
 *    第四步里它还说不了“录音撑没撑过锁屏”；对“锁屏时重新开始”，还要求锁屏中手势真的停下过录音（那才是这一步的流程）。
 *    声明之后又收到耳机事件（用户又试了一次）则声明作废，由 CalibrationWizard 在收到事件时清掉。
 *    声明那一刻的环境还要“站得住”才采纳：向导所知的采集状态与锁屏状态和界面给的实际环境一致、锁屏状态自上一个起点以来没变过
 *    也没读不出来过（[DeclaredAttempt]）；有事件已经到了、协调器的决定还没到时点的“没反应”根本不记（那次手势的结局还不知道）。
 *    声明说的是这一步要求的那种手势（第二步：停止；第三、四步：开始），向导分不出用户其实在说另一种——比如第三步里先试了停止、
 *    没反应，改用屏幕停止，再点“没反应”，会被记成“开始没反应”；这是与操作说明不符的操作，事件里看不出来。
 *  - 一次手势开始的结局属于它自己：手势让录音进入准备之后，紧接着离开准备的决定必须是“到达录音中”才算开始成功，
 *    并且到达那一刻的锁屏状态与开始时一致；准备被取消或受阻后，用户改点屏幕按钮录上的那一段，不能算在手势头上。
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

        val stop = ctx.check(record(CalibrationStep.GESTURE_WHILE_RECORDING), "stop-by-gesture", declaredAbout = { it.wasStop(locked = false) }) { obs ->
            obs.filterIsInstance<Observation.Decided>().any { it.isRemoteStop() && it.screenLocked == false }
        }
        val startAfterStop = ctx.check(
            record(CalibrationStep.RESTART_AFTER_STOP),
            "start-by-gesture-after-stop",
            declaredAbout = { it.wasStart(locked = false) },
        ) { startedAndRecording(it, locked = false) }
        val lockStep = record(CalibrationStep.SCREEN_LOCKED)
        // “没效果”说的是一次手势；录音撑没撑过锁屏，它回答不了
        val survive = ctx.check(lockStep, "recording-survived-lock", declaredAbout = { false }) { survivedLock(it) }
        // 这一步的流程是：锁屏中用手势停止、再用手势开始。保守起见，只有锁屏中手势真的停下过录音，
        // 并且声明那一刻是锁屏、就绪，才算对“锁屏时重新开始”的否定
        val stoppedWhileLocked = lockStep.observations.filterIsInstance<Observation.Decided>().any { it.isRemoteStop() && it.screenLocked == true }
        val restartLocked = ctx.check(
            lockStep,
            "start-by-gesture-while-locked",
            declaredAbout = { stoppedWhileLocked && it.wasStart(locked = true) },
        ) { startedAndRecording(it, locked = true) }

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
        /** [declaredAbout]：用户在这一步的“没效果”声明，说的是不是这一项检查所指的那次手势（看声明那一刻的环境）。 */
        fun check(record: StepRecord, name: String, declaredAbout: (DeclaredAttempt) -> Boolean, observed: (List<Observation>) -> Boolean): Finding {
            val prefix = "calibration:$runId:${stepKey(record.step)}:attempt-${record.attempt}"
            return when {
                observed(record.observations) -> Finding(Verdict.POSITIVE, "$prefix:$name:${setting.label}")
                record.declared?.let(declaredAbout) == true -> Finding(Verdict.NEGATIVE, "$prefix:owner-reported-no-effect:${setting.label}")
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
                    // 耳机手势在锁屏中遇到了正在进行的录音：录音撑到了这一刻
                    o.source.isRemote() && o.screenLocked == true && o.stateBefore == CaptureState.RECORDING -> return true
                    // 录音在这里结束了（任何原因，包括耳机让它停下、但这一刻读不出锁屏状态）：之后的录音不是同一段，不能再算它撑过了锁屏
                    o.stateBefore == CaptureState.RECORDING && o.stateAfter != CaptureState.RECORDING -> lockedWithRecording = false
                }
                is Observation.Arrived -> Unit
            }
        }
        return false
    }

    /**
     * 耳机手势在“已经封存过一段录音”之后开始了录音，并且录音真的起来了（到达录音中）；[locked] 指开始时的锁屏状态。
     * 手势开始只是进入准备：紧接着离开准备的那个决定必须是“到达录音中”。准备被取消、受阻之后，
     * 别的开始（比如用户改点屏幕按钮）录上的，不算在这次手势头上。
     */
    private fun startedAndRecording(observations: List<Observation>, locked: Boolean): Boolean =
        observations.indices.any { i ->
            val o = observations[i]
            o is Observation.Decided && o.isRemoteStart() && o.afterFinalize && o.screenLocked == locked &&
                observations.drop(i + 1).filterIsInstance<Observation.Decided>()
                    .firstOrNull { it.stateBefore == CaptureState.ROUTING && it.stateAfter != CaptureState.ROUTING }
                    // 录音到达“录音中”的那一刻，屏幕状态也要和开始时一致：中途解锁 / 锁屏的，没观察到在那种状态下真的录起来
                    ?.let { it.stateAfter == CaptureState.RECORDING && it.screenLocked == locked } == true
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
