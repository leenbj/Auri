package app.auri.capture.calibration

import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.CaptureState
import app.auri.capture.contract.ControlSource
import app.auri.capture.contract.NormalizedControlEvent
import app.auri.capture.contract.PlatformEvent
import app.auri.capture.coordinator.CaptureDecision
import app.auri.capture.coordinator.Cause
import app.auri.capture.machine.CaptureEvent

/**
 * 校准向导的一条观察。只有种类、状态与布尔值，没有设备标识、名称或任何内容。
 * 结论（档案里的能力状态）全部由这些观察按固定规则算出，见 [CalibrationFindings]。
 */
internal sealed interface Observation {
    /** 一个来自遥控（耳机或来源不明）的平台事件到达了应用；它之后被协调器怎么处理，看后面配对的 [Decided]。 */
    data class Arrived(val source: ControlSource, val platformEvent: PlatformEvent) : Observation

    /**
     * 协调器处理了一个输入。[source] 是引起它的控制事件的来源；为 null 表示不是控制命令引起的
     * （路由确认、路由丢失、时长上限、封存完成……），或者对不上任何待配对的控制事件——后一种宁可不归因。
     * [afterFinalize]：这一轮校准里，在这个决定之前是否已经有一段录音封存完成（“停止之后再开始”的前提）。
     */
    data class Decided(
        val source: ControlSource?,
        val platformEvent: PlatformEvent?,
        val stateBefore: CaptureState,
        val stateAfter: CaptureState,
        val screenLocked: Boolean?,
        val afterFinalize: Boolean,
    ) : Observation

    /** 屏幕锁定状态变化。[state] 是变化时向导所知的采集状态，不知道就是 null。 */
    data class LockChanged(val locked: Boolean, val state: CaptureState?) : Observation
}

/** 平台事件来自遥控：耳机，或系统没说清来源的媒体键。屏幕按钮不是。 */
internal fun ControlSource?.isRemote(): Boolean = this == ControlSource.HEADSET_REMOTE || this == ControlSource.UNKNOWN_REMOTE

internal fun ControlSource?.isHeadset(): Boolean = this == ControlSource.HEADSET_REMOTE

/** 遥控让录音进入准备（开始）：从就绪或受阻转到准备中。被忽略、被拒绝的决定前后状态相同，不会满足。 */
internal fun Observation.Decided.isRemoteStart(): Boolean =
    source.isHeadset() && (stateBefore == CaptureState.ARMED || stateBefore == CaptureState.BLOCKED) && stateAfter == CaptureState.ROUTING

/** 遥控让正在进行的录音停止（进入封存）。录音中 = 实际输入来源已经验证过。 */
internal fun Observation.Decided.isRemoteStop(): Boolean =
    source.isHeadset() && stateBefore == CaptureState.RECORDING && stateAfter == CaptureState.FINALIZING

/**
 * 把协调器的决定与引起它的控制事件配对。
 *
 * 协调器按先后顺序处理控制命令，每个命令恰好一个决定，但决定里不带命令的来源，所以要靠配对。
 * 事件必须先于决定到达（向导的接入点在协调器之前，见 CompositeControlAdapter.tap）。
 * 宁可漏记，不可错记：决定的触发器必须与待配对事件的命令一致，对不上的事件视为没等到决定而丢掉，
 * 完全对不上的决定不归因——这样丢一个决定，不会把屏幕上的点击记成耳机手势。
 *
 * [discard]（步骤边界、重做）放弃的是“归属”，不是“位置”：协调器的决定按命令先后到达，被放弃的事件的决定还会到，
 * 它们要继续排在队列里，由它们自己吸收那些决定，之后到的新事件才不会被旧决定错配。
 */
internal class ControlDecisionPairing(private val capacity: Int = 16) {
    private class Entry(val event: NormalizedControlEvent, var live: Boolean = true)

    private val pending = ArrayDeque<Entry>()

    /** 还在等决定、归属当前这次尝试的事件个数（被 [discard] 放弃归属的不算）。 */
    val size: Int get() = pending.count { it.live }

    fun offer(event: NormalizedControlEvent) {
        pending.addLast(Entry(event))
        while (pending.size > capacity) pending.removeFirst()
    }

    /** 返回引起这个决定的事件；不是控制决定，或对不上，或那个事件已被放弃归属，返回 null（对不上时不改动待配对队列）。 */
    fun match(decision: CaptureDecision): NormalizedControlEvent? {
        if (!isControlDecision(decision)) return null
        val index = pending.indexOfFirst { triggerOf(it.event.command) == decision.trigger }
        if (index < 0) return null
        repeat(index) { pending.removeFirst() }
        val entry = pending.removeFirst()
        return if (entry.live) entry.event else null
    }

    /** 全部清掉，包括位置（整轮放弃时用）。 */
    fun clear() = pending.clear()

    /** 现有的事件放弃归属：它们的决定到了也不记到任何手势头上，但仍占着队列里的位置。 */
    fun discard() = pending.forEach { it.live = false }

    // 没有触发器的决定只有“命令被忽略”这一种才对应某个控制事件（内部错误等无法确定是哪个命令）；
    // 路由确认之类有触发器、但不是 START/STOP/TOGGLE 的决定，与任何命令的触发器都对不上，在 match 里自然配不上
    private fun isControlDecision(decision: CaptureDecision): Boolean = decision.trigger != null || decision.cause == Cause.IGNORE_COMMAND

    private fun triggerOf(command: BusinessCommand): CaptureEvent? = when (command) {
        BusinessCommand.START -> CaptureEvent.START
        BusinessCommand.STOP -> CaptureEvent.STOP
        BusinessCommand.TOGGLE -> CaptureEvent.TOGGLE
        BusinessCommand.IGNORE -> null
    }
}
