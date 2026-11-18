package app.auri.capture.control

import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.ControlSource
import app.auri.capture.contract.NormalizedControlEvent
import app.auri.capture.contract.PlatformEvent
import app.auri.capture.coordinator.IdGenerator

/** 一个原始输入没有变成事件的原因。供日志与校准向导区分“没收到”与“收到了但被去抖合并”。 */
enum class DropReason { KEY_UP, KEY_REPEAT, UNMAPPED_KEY, DUPLICATE_RAW_EVENT, INVALID_EVENT_TIME }

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
 *  3. 按键种类、设备和平台时间相同才视为重复；时间或种类不同的取消命令不能被窗口吞掉。
 *  4. START/TOGGLE 必须带可信且未过期的发生时刻；STOP 即使时间不可信也可以停止。
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
    private data class Identity(val kind: MediaKind, val deviceId: Int?, val issuedAtNs: Long?, val rawId: String?)
    private val recent = ArrayDeque<Identity>()

    @Synchronized
    fun normalize(input: RawMediaInput, profile: ControlProfile): NormalizeResult {
        when (input.phase) {
            KeyPhase.UP -> return NormalizeResult.Drop(DropReason.KEY_UP)
            KeyPhase.REPEAT -> return NormalizeResult.Drop(DropReason.KEY_REPEAT)
            KeyPhase.DOWN -> Unit
        }
        val platformEvent = platformEventOf(input.kind) ?: return NormalizeResult.Drop(DropReason.UNMAPPED_KEY)
        val command = commandFor(input.kind, platformEvent, profile)
        val issued = input.issuedAtNs?.takeIf { it >= 0 && it <= input.receivedAtNs }
        if ((command == BusinessCommand.START || command == BusinessCommand.TOGGLE) &&
            (issued == null || input.receivedAtNs - issued > config.maxIssuedAgeNs)
        ) return NormalizeResult.Drop(DropReason.INVALID_EVENT_TIME)

        val rawId = input.rawId?.takeIf { it.isNotEmpty() }?.take(MAX_RAW_ID_LENGTH)
        val identity = when {
            issued != null -> Identity(input.kind, input.deviceId, issued, null)
            rawId != null -> Identity(input.kind, input.deviceId, null, rawId)
            else -> null
        }
        if (identity != null) {
            if (identity in recent) return NormalizeResult.Drop(DropReason.DUPLICATE_RAW_EVENT)
            recent.addLast(identity)
            while (recent.size > config.rawIdMemory) recent.removeFirst()
        }

        val gestureNs = issued ?: input.receivedAtNs

        val event = NormalizedControlEvent(
            eventId = ids.newUuid(),
            source = if (input.fromExternalDevice == true) ControlSource.HEADSET_REMOTE else ControlSource.UNKNOWN_REMOTE,
            platformEvent = platformEvent,
            command = command,
            monotonicNs = gestureNs,
            sequence = sequence,
            profileId = profile.profileId,
            profileVersion = profile.profileVersion,
            rawEventId = rawId,
        )
        sequence += 1
        return NormalizeResult.Emit(event)
    }

    /** 清掉去抖窗口与已记住的原始标识（控制模式关闭后再打开时调用）。序号不清零。 */
    @Synchronized
    fun resetGestureState() {
        recent.clear()
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
