package app.auri.capture.machine

import app.auri.capture.contract.CaptureState
import app.auri.capture.contract.CaptureState.ARMED
import app.auri.capture.contract.CaptureState.BLOCKED
import app.auri.capture.contract.CaptureState.DISARMED
import app.auri.capture.contract.CaptureState.FINALIZING
import app.auri.capture.contract.CaptureState.RECORDING
import app.auri.capture.contract.CaptureState.ROUTING
import app.auri.capture.contract.EndReason
import app.auri.capture.machine.CaptureEvent.DISABLE_CONTROL_MODE
import app.auri.capture.machine.CaptureEvent.DURATION_LIMIT
import app.auri.capture.machine.CaptureEvent.ENABLE_CONTROL_MODE
import app.auri.capture.machine.CaptureEvent.FINALIZE_COMPLETE
import app.auri.capture.machine.CaptureEvent.PERMISSION_REVOKED
import app.auri.capture.machine.CaptureEvent.PROCESS_RESTORED
import app.auri.capture.machine.CaptureEvent.ROUTE_LOST
import app.auri.capture.machine.CaptureEvent.ROUTE_TIMEOUT
import app.auri.capture.machine.CaptureEvent.ROUTE_UNVERIFIED
import app.auri.capture.machine.CaptureEvent.ROUTE_VERIFIED
import app.auri.capture.machine.CaptureEvent.START
import app.auri.capture.machine.CaptureEvent.STOP
import app.auri.capture.machine.CaptureEvent.SYSTEM_INTERRUPTION
import app.auri.capture.machine.CaptureEvent.TOGGLE
import app.auri.capture.machine.CaptureEvent.USER_CANCEL

/** contracts/fixtures/state-machines/capture.machine.json 的 events（PRD 第 12.2 节）。 */
enum class CaptureEvent {
    ENABLE_CONTROL_MODE,
    START,
    STOP,
    TOGGLE,
    PERMISSION_REVOKED,
    PROCESS_RESTORED,
    DISABLE_CONTROL_MODE,
    ROUTE_VERIFIED,
    USER_CANCEL,
    ROUTE_UNVERIFIED,
    ROUTE_TIMEOUT,
    DURATION_LIMIT,
    ROUTE_LOST,
    SYSTEM_INTERRUPTION,
    FINALIZE_COMPLETE,
}

/** 一次事件的结果。表里没有的（状态，事件）组合是 REJECTED：状态不变，调用方得到原因（迟到的旧回调也属于这一类）。 */
enum class Outcome { TRANSITION, IGNORED, REJECTED }

/**
 * 转移表里的一条规则。[endReason] 只在会结束录音的转移上有值；STOP 的 end_reason 取决于命令来源
 * （headset_command / volume_gesture / screen_control），所以表里留空，由协调器按来源补上。
 */
data class MachineRule(
    val from: CaptureState,
    val event: CaptureEvent,
    val outcome: Outcome,
    val to: CaptureState,
    val endReason: EndReason? = null,
)

/**
 * 采集状态机的转移表，逐条对应 capture.machine.json（CaptureMachineTableTest 与原始 JSON 对表）。
 * 它只回答“这个事件在这个状态下怎么办”，不做任何副作用；副作用（开闸、关闸、落盘、定时器）都在协调器里。
 */
object CaptureMachine {
    val initialState: CaptureState = DISARMED

    private fun go(from: CaptureState, event: CaptureEvent, to: CaptureState, endReason: EndReason? = null) =
        MachineRule(from, event, Outcome.TRANSITION, to, endReason)

    private fun ignore(state: CaptureState, event: CaptureEvent) = MachineRule(state, event, Outcome.IGNORED, state)

    val rules: List<MachineRule> =
        listOf(
            go(DISARMED, ENABLE_CONTROL_MODE, ARMED),
            ignore(DISARMED, START),
            ignore(DISARMED, STOP),
            ignore(DISARMED, TOGGLE),
            ignore(DISARMED, PERMISSION_REVOKED),
            ignore(DISARMED, PROCESS_RESTORED),

            go(ARMED, START, ROUTING),
            go(ARMED, TOGGLE, ROUTING),
            ignore(ARMED, STOP),
            go(ARMED, PERMISSION_REVOKED, BLOCKED),
            go(ARMED, DISABLE_CONTROL_MODE, DISARMED),
            go(ARMED, PROCESS_RESTORED, DISARMED),

            go(ROUTING, ROUTE_VERIFIED, RECORDING),
            go(ROUTING, STOP, ARMED),
            go(ROUTING, TOGGLE, ARMED),
            go(ROUTING, USER_CANCEL, ARMED),
            ignore(ROUTING, START),
            go(ROUTING, ROUTE_UNVERIFIED, BLOCKED),
            go(ROUTING, ROUTE_TIMEOUT, BLOCKED),
            go(ROUTING, PERMISSION_REVOKED, BLOCKED),
            go(ROUTING, DISABLE_CONTROL_MODE, DISARMED),
            go(ROUTING, PROCESS_RESTORED, DISARMED),

            go(RECORDING, STOP, FINALIZING),
            go(RECORDING, TOGGLE, FINALIZING, EndReason.HEADSET_COMMAND),
            ignore(RECORDING, START),
            go(RECORDING, DURATION_LIMIT, FINALIZING, EndReason.DURATION_LIMIT),
            go(RECORDING, ROUTE_LOST, FINALIZING, EndReason.ROUTE_LOST),
            go(RECORDING, SYSTEM_INTERRUPTION, FINALIZING, EndReason.SYSTEM_INTERRUPTION),
            go(RECORDING, PERMISSION_REVOKED, FINALIZING, EndReason.PERMISSION_REVOKED),
            go(RECORDING, PROCESS_RESTORED, DISARMED, EndReason.PROCESS_TERMINATED),

            go(FINALIZING, FINALIZE_COMPLETE, ARMED),
            ignore(FINALIZING, STOP),
            ignore(FINALIZING, TOGGLE),
            ignore(FINALIZING, START),
            ignore(FINALIZING, PERMISSION_REVOKED),
            go(FINALIZING, PROCESS_RESTORED, DISARMED),

            go(BLOCKED, START, ROUTING),
            go(BLOCKED, TOGGLE, ROUTING),
            ignore(BLOCKED, STOP),
            ignore(BLOCKED, PERMISSION_REVOKED),
            go(BLOCKED, DISABLE_CONTROL_MODE, DISARMED),
            go(BLOCKED, PROCESS_RESTORED, DISARMED),
        )

    private val index: Map<Pair<CaptureState, CaptureEvent>, MachineRule> = rules.associateBy { it.from to it.event }

    /** 返回这个组合的规则；没有规则（null）就是被拒绝。 */
    fun resolve(state: CaptureState, event: CaptureEvent): MachineRule? = index[state to event]
}
