package app.auri.capture.calibration

import app.auri.capture.contract.CaptureState
import app.auri.capture.contract.NormalizedControlEvent
import app.auri.capture.contract.PlatformEvent
import app.auri.capture.control.ControlProfile
import app.auri.capture.coordinator.CaptureDecision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 校准向导的四个场景，按这个顺序走（Ticket 17）。 */
enum class CalibrationStep {
    /** 不录音时按耳机手势：只观察会收到哪些平台事件，不会开始录音。 */
    IDLE_EVENTS,

    /** 录音中（蓝牙麦克风已在采集）再做一次手势：能否停止。必测项。 */
    GESTURE_WHILE_RECORDING,

    /** 停止之后再做手势：能否开始下一段。 */
    RESTART_AFTER_STOP,

    /** 锁屏：录音能否继续，锁屏时手势能否停止、再开始。 */
    SCREEN_LOCKED,
}

enum class CalibrationPhase { NOT_STARTED, IN_PROGRESS, COMPLETED, ABORTED }

enum class StepOutcome { PENDING, DONE, SKIPPED }

/**
 * 取证的环境。证据等级由它决定，不由观察到的内容决定：应用无法证明自己跑在真机上、手里是真耳机，
 * 所以 E3 要求“不是模拟器，并且用户明确确认这是真机 + 真实耳机”；其余一律 E2（单测、模拟器、未确认）。
 */
enum class EvidenceSetting(val level: EvidenceLevel, val label: String) {
    REAL_DEVICE_AND_HEADSET(EvidenceLevel.E3, "real-device"),
    EMULATOR(EvidenceLevel.E2, "emulator"),
    UNCONFIRMED(EvidenceLevel.E2, "unconfirmed"),
    UNIT_TEST(EvidenceLevel.E2, "unit-test"),
}

/** 当前这一步目前观察到的概况，界面用；只有种类、个数与是否发生，没有任何设备标识或内容。 */
data class StepSummary(
    /** 来自耳机（或来源不明的遥控）的平台事件个数；屏幕按钮不算。 */
    val eventCounts: Map<PlatformEvent, Int> = emptyMap(),
    /** 耳机手势让录音进入准备（开始）。 */
    val startedByGesture: Boolean = false,
    /** 耳机手势让录音停止。 */
    val stoppedByGesture: Boolean = false,
    /** 用户声明“我做了手势，没有任何效果”。只有用户能声明，向导不会从“没有事件”推断。 */
    val ownerReportedNoEffect: Boolean = false,
)

data class CalibrationState(
    val phase: CalibrationPhase,
    val step: CalibrationStep?,
    /** 当前场景第几次尝试（从 1 开始；重做加一）。 */
    val attempt: Int,
    val stepOutcomes: Map<CalibrationStep, StepOutcome>,
    val summary: StepSummary,
)

/**
 * 校准向导：带用户走完四个场景，记录观察，最后产出 Headset Profile。纯逻辑，没有 Android 依赖。
 *
 * 一个实例只跑一轮（重新校准就新建一个）。每一步用户可以：完成（[next]）、跳过（[skip]，这一步的观察作废）、
 * 重做（[repeat]，清掉这一步的观察）、声明没效果（[reportNoEffect]）；任何时候可以放弃（[abort]，没有档案）。
 *
 * 结论只来自观察：没做的、跳过的、没观察到的能力保持 NOT_TESTED；“已连接 / 已配对”不是证据。
 */
class CalibrationWizard(val runId: String, val setting: EvidenceSetting) {
    init {
        // 编号会写进证据标识（用冒号分隔），所以只允许短的字母数字串；不能带设备名之类的东西
        require(RUN_ID_PATTERN.matches(runId)) { "校准编号只能是 1 到 80 个字母、数字、点、下划线或连字符" }
    }

    private class StepData {
        var outcome: StepOutcome = StepOutcome.PENDING
        var attempt: Int = 1
        /** 用户声明“没效果”那一刻的环境；没声明为 null。 */
        var declared: DeclaredAttempt? = null
        val observations = ArrayList<Observation>()

        fun add(observation: Observation) {
            observations += observation
            if (observations.size > MAX_OBSERVATIONS_PER_STEP) observations.removeAt(0) // 一步只有几次手势，上限只防失控
        }

        fun reset() {
            declared = null
            observations.clear()
        }
    }

    private val steps: Map<CalibrationStep, StepData> = CalibrationStep.entries.associateWith { StepData() }
    private var phase = CalibrationPhase.NOT_STARTED
    private var current: CalibrationStep? = null

    // 整轮校准共用：协调器的状态来自它发出的决定；“已经封存过一段”决定后面的开始算不算“停止之后再开始”
    private val pairing = ControlDecisionPairing()
    private var trackedState: CaptureState? = null
    private var finalizeSeen = false

    // 最近一次知道的锁屏状态（锁屏变化，或决定处理时附带的）：声明时记下那次手势是不是在锁屏时做的。
    // 读不出来（null）就是不知道：旧值可能已经过期，不再沿用。
    private var knownLock: Boolean? = null

    // 自上一个采集决定以来，锁屏状态变过或读不出来过：此时声明的锁屏状态代表不了那次手势做的时候（决定自带的读数会让它重新对齐）
    private var lockMovedSinceDecision = false

    private val flow = MutableStateFlow(snapshot())
    val state: StateFlow<CalibrationState> = flow.asStateFlow()

    @Synchronized
    fun start(): Boolean {
        if (phase != CalibrationPhase.NOT_STARTED) return false
        phase = CalibrationPhase.IN_PROGRESS
        current = CalibrationStep.entries.first()
        publish()
        return true
    }

    /** 这一步做完了，进入下一步；最后一步做完则整轮完成。 */
    @Synchronized
    fun next(): Boolean = finishStep(StepOutcome.DONE)

    /** 跳过这一步：它的观察与声明作废，对应的能力保持未测试。 */
    @Synchronized
    fun skip(): Boolean = finishStep(StepOutcome.SKIPPED)

    /** 重做这一步：清掉这一步的观察与声明，尝试次数加一。 */
    @Synchronized
    fun repeat(): Boolean {
        val step = current ?: return false
        steps.getValue(step).apply {
            attempt += 1
            reset()
        }
        // 被丢弃的那次尝试里还没等到决定的事件：它的决定晚到时，不能记到新的尝试头上
        pairing.clear()
        publish()
        return true
    }

    /**
     * 用户声明这一步“做了手势，没有任何效果”。第一个场景只观察事件，没有“效果”可言，不适用。
     * 声明说的是用户刚做的那次手势，并且记下这一刻的采集状态与锁屏状态，结论据此判断那次手势想做什么；
     * 之后又收到耳机事件（用户又试了一次），声明作废，要再声明一次才算最近这次。
     *
     * [currentState] 是界面此刻读到的实际采集状态（向导自己所知的状态来自协调器的决定，决定送到向导有时延）：
     * 给了而且与向导所知的对不上，就当不知道，这次声明不会被采纳为结论；不给就只用向导所知的。
     */
    @Synchronized
    fun reportNoEffect(currentState: CaptureState? = null): Boolean {
        val step = current ?: return false
        if (step == CalibrationStep.IDLE_EVENTS) return false
        val state = if (currentState == null || currentState == trackedState) trackedState else null
        steps.getValue(step).declared = DeclaredAttempt(state, knownLock, lockSteady = !lockMovedSinceDecision)
        publish()
        return true
    }

    /** 放弃整轮：所有观察作废，没有档案。 */
    @Synchronized
    fun abort(): Boolean {
        if (phase != CalibrationPhase.IN_PROGRESS) return false
        phase = CalibrationPhase.ABORTED
        current = null
        steps.values.forEach { it.reset() }
        pairing.clear()
        publish()
        return true
    }

    /**
     * 一个控制事件到达（在协调器之前调用）。只有来自遥控的事件才算“耳机事件”；屏幕按钮的事件只用来配对决定。
     * 没有进行中的步骤时忽略。
     */
    @Synchronized
    fun onControlEvent(event: NormalizedControlEvent) {
        val step = current ?: return
        pairing.offer(event)
        if (event.source.isRemote()) {
            steps.getValue(step).apply {
                add(Observation.Arrived(event.source, event.platformEvent))
                // 又收到一次耳机事件 = 用户又试了一次：之前“没反应”的声明说的是更早那次尝试，作废
                declared = null
            }
        }
        publish()
    }

    /** 协调器处理完一个输入（来自它的 decisions 流）。[screenLocked] 是处理时的锁屏状态，不知道就是 null。 */
    @Synchronized
    fun onDecision(decision: CaptureDecision, screenLocked: Boolean?) {
        val step = current ?: return
        val cause = pairing.match(decision)
        steps.getValue(step).add(
            Observation.Decided(
                source = cause?.source,
                platformEvent = cause?.platformEvent,
                stateBefore = decision.stateBefore,
                stateAfter = decision.stateAfter,
                screenLocked = screenLocked,
                afterFinalize = finalizeSeen,
            ),
        )
        trackedState = decision.stateAfter
        // 决定自带的锁屏读数让“采集状态”与“锁屏状态”重新对齐；读不出来则仍然不可信
        knownLock = screenLocked
        lockMovedSinceDecision = screenLocked == null
        if (decision.stateBefore == CaptureState.FINALIZING && decision.stateAfter == CaptureState.ARMED) finalizeSeen = true
        publish()
    }

    /** 屏幕锁定或解锁；null = 这次读不出来（不算观察，但之前知道的锁屏状态从此不可信）。 */
    @Synchronized
    fun onScreenLockChanged(locked: Boolean?) {
        val step = current ?: return
        if (locked != knownLock) lockMovedSinceDecision = true
        knownLock = locked
        if (locked != null) steps.getValue(step).add(Observation.LockChanged(locked, trackedState))
        publish()
    }

    /**
     * 当前这一步应当使用的临时命令映射；不在进行中为 null。
     * 第一步只观察（所有命令都是 IGNORE，观察期间不会开始录音）；之后的步骤里任何播放类事件都当作切换，
     * 这样耳机发的是哪种事件都能开始也能停止，向导再把“实际观察到的”记下来。
     */
    @Synchronized
    fun controlProfile(): ControlProfile? = when (current) {
        null -> null
        CalibrationStep.IDLE_EVENTS -> OBSERVE_ONLY
        else -> TOGGLE_ALL
    }

    /** 整轮完成后产出档案；没完成、被放弃时为 null。 */
    @Synchronized
    fun buildProfile(profileId: String, profileVersion: Int, modelHint: String?): HeadsetProfile? {
        if (phase != CalibrationPhase.COMPLETED) return null
        val records = CalibrationStep.entries.map {
            val data = steps.getValue(it)
            StepRecord(it, data.attempt, data.declared, data.observations.toList())
        }
        return CalibrationFindings.profile(runId, setting, records, profileId, profileVersion, modelHint)
    }

    private fun finishStep(outcome: StepOutcome): Boolean {
        val step = current ?: return false
        val data = steps.getValue(step)
        data.outcome = outcome
        if (outcome == StepOutcome.SKIPPED) {
            data.reset()
        } else if (pairing.size > 0) {
            // 这一步结束时还有事件没等到协调器的决定：决定晚到时不会再记到这一步头上，而那次手势也许其实成功了，
            // 所以这一步里“没效果”的声明不能当结论
            data.declared = null
        }
        // 步骤边界之前的事件，它们的决定晚到时不归因给任何手势（宁可漏记，不可错记）
        pairing.clear()
        val following = CalibrationStep.entries.getOrNull(step.ordinal + 1)
        if (following == null) {
            phase = CalibrationPhase.COMPLETED
            current = null
        } else {
            current = following
        }
        publish()
        return true
    }

    private fun publish() {
        flow.value = snapshot()
    }

    private fun snapshot(): CalibrationState {
        val step = current
        val data = step?.let { steps.getValue(it) }
        return CalibrationState(
            phase = phase,
            step = step,
            attempt = data?.attempt ?: 1,
            stepOutcomes = CalibrationStep.entries.associateWith { steps.getValue(it).outcome },
            summary = data?.let { summaryOf(it) } ?: StepSummary(),
        )
    }

    private fun summaryOf(data: StepData): StepSummary {
        val decided = data.observations.filterIsInstance<Observation.Decided>()
        return StepSummary(
            eventCounts = data.observations.filterIsInstance<Observation.Arrived>().groupingBy { it.platformEvent }.eachCount(),
            startedByGesture = decided.any { it.isRemoteStart() },
            stoppedByGesture = decided.any { it.isRemoteStop() },
            ownerReportedNoEffect = data.declared != null,
        )
    }

    private companion object {
        const val MAX_OBSERVATIONS_PER_STEP = 200
        val RUN_ID_PATTERN = Regex("^[A-Za-z0-9._-]{1,80}$")

        val OBSERVE_ONLY: ControlProfile = ControlProfile.generic().observeOnly().copy(profileId = "calibration-observe-only")
        val TOGGLE_ALL: ControlProfile = ControlProfile.generic()
            .withSingleEventToggle(PlatformEvent.PLAY)
            .withSingleEventToggle(PlatformEvent.PAUSE)
            .copy(profileId = "calibration-toggle-all")
    }
}
