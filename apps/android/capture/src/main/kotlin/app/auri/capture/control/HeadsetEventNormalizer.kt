package app.auri.capture.control

import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.ControlSource
import app.auri.capture.contract.NormalizedControlEvent
import app.auri.capture.contract.PlatformEvent
import app.auri.capture.coordinator.IdGenerator
import kotlin.math.abs

/** 一个原始输入没有变成事件的原因。供日志与校准向导区分“没收到”与“收到了但被去抖合并”。 */
enum class DropReason { KEY_UP, KEY_REPEAT, UNMAPPED_KEY, DUPLICATE_RAW_EVENT, SAME_GESTURE_BURST }

sealed interface NormalizeResult {
    data class Emit(val event: NormalizedControlEvent) : NormalizeResult

    data class Drop(val reason: DropReason) : NormalizeResult
}

/**
 * 把平台的媒体按键翻成合同里的 normalized_control_event，并做去抖（PRD FR-HS-001 / FR-HS-004）。纯 Kotlin，无平台依赖。
 *
 * 规则：
 *  1. 只有按下（DOWN）算一次手势；抬起与自动重复丢弃。
 *  2. 同一个原始标识只算一次（两个通道递送同一个事件、或迟到的重复）。
 *  3. 一次手势同时发出的一串事件只产生第一个：与上一个产生的事件相隔不足 [ControlConfig.gestureWindowNs] 的都丢弃，
 *     所以一次手势绝不会先开始再立刻停止。窗口只管这一件事——窗口过后的停止 / 切换照常通过，
 *     录音准备中（ROUTING）的取消不会被去抖吞掉。
 *  4. 时间戳是手势发生的时刻（平台给了且可信），否则是接收时刻：协调器靠它丢掉封存期间发出、封存之后才送到的过时命令。
 *  5. 命令只经过档案的映射；停止键（MEDIA_STOP）永远只能停止，即使档案把 pause 映射成了切换。
 *  6. 来源：系统能证明来自外接设备才是 headset_remote，其余都是 unknown_remote。不猜左右耳，不假设存在原始双击。
 *
 * 线程安全：可从任意线程调用。序号在对象的整个生命期内单调递增；[resetGestureState] 只清去抖状态。
 */
class HeadsetEventNormalizer(
    private val ids: IdGenerator,
    private val config: ControlConfig = ControlConfig(),
) {
    private var sequence = 0L
    private var lastEmittedNs: Long? = null
    private val recentRawIds = ArrayDeque<String>()

    @Synchronized
    fun normalize(input: RawMediaInput, profile: ControlProfile): NormalizeResult {
        when (input.phase) {
            KeyPhase.UP -> return NormalizeResult.Drop(DropReason.KEY_UP)
            KeyPhase.REPEAT -> return NormalizeResult.Drop(DropReason.KEY_REPEAT)
            KeyPhase.DOWN -> Unit
        }
        val platformEvent = platformEventOf(input.kind) ?: return NormalizeResult.Drop(DropReason.UNMAPPED_KEY)

        val rawId = input.rawId?.takeIf { it.isNotEmpty() }?.take(MAX_RAW_ID_LENGTH)
        if (rawId != null) {
            if (rawId in recentRawIds) return NormalizeResult.Drop(DropReason.DUPLICATE_RAW_EVENT)
            recentRawIds.addLast(rawId)
            while (recentRawIds.size > config.rawIdMemory) recentRawIds.removeFirst()
        }

        val gestureNs = gestureTimeNs(input)
        val last = lastEmittedNs
        if (last != null && abs(gestureNs - last) < config.gestureWindowNs) return NormalizeResult.Drop(DropReason.SAME_GESTURE_BURST)

        val event = NormalizedControlEvent(
            eventId = ids.newUuid(),
            source = if (input.fromExternalDevice == true) ControlSource.HEADSET_REMOTE else ControlSource.UNKNOWN_REMOTE,
            platformEvent = platformEvent,
            command = commandFor(input.kind, platformEvent, profile),
            monotonicNs = gestureNs,
            sequence = sequence,
            profileId = profile.profileId,
            profileVersion = profile.profileVersion,
            rawEventId = rawId,
        )
        sequence += 1
        lastEmittedNs = gestureNs
        return NormalizeResult.Emit(event)
    }

    /** 清掉去抖窗口与已记住的原始标识（控制模式关闭后再打开时调用）。序号不清零。 */
    @Synchronized
    fun resetGestureState() {
        lastEmittedNs = null
        recentRawIds.clear()
    }

    private fun gestureTimeNs(input: RawMediaInput): Long {
        val issued = input.issuedAtNs
        val trustworthy = issued != null && issued >= 0 && issued <= input.receivedAtNs && input.receivedAtNs - issued <= config.maxIssuedAgeNs
        return if (trustworthy) issued!! else input.receivedAtNs
    }

    private fun commandFor(kind: MediaKind, platformEvent: PlatformEvent, profile: ControlProfile): BusinessCommand {
        val mapped = profile.commandFor(platformEvent)
        // 停止键是明确的停止意图：只能停止（观察档案里仍然被忽略），不会因为 pause 被映射成切换而开始录音
        return if (kind == MediaKind.STOP && mapped != BusinessCommand.IGNORE) BusinessCommand.STOP else mapped
    }

    private fun platformEventOf(kind: MediaKind): PlatformEvent? = when (kind) {
        MediaKind.PLAY -> PlatformEvent.PLAY
        MediaKind.PAUSE, MediaKind.STOP -> PlatformEvent.PAUSE
        MediaKind.PLAY_PAUSE, MediaKind.HEADSET_HOOK -> PlatformEvent.TOGGLE
        MediaKind.NEXT -> PlatformEvent.NEXT
        MediaKind.PREVIOUS -> PlatformEvent.PREVIOUS
        MediaKind.OTHER -> null
    }

    private companion object {
        const val MAX_RAW_ID_LENGTH = 200
    }
}
