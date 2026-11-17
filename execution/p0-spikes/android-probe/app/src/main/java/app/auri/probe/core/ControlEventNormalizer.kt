package app.auri.probe.core

/**
 * Raw Android KeyEvent integer constants kept decoupled from android.view.KeyEvent
 * so this module compiles and runs on plain JVM tests.
 */
object RawAndroidKeyCodes {
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1

    const val KEYCODE_HEADSETHOOK = 79
    const val KEYCODE_MEDIA_PLAY_PAUSE = 85
    const val KEYCODE_MEDIA_STOP = 86
    const val KEYCODE_MEDIA_NEXT = 87
    const val KEYCODE_MEDIA_PREVIOUS = 88
    const val KEYCODE_MEDIA_PLAY = 126
    const val KEYCODE_MEDIA_PAUSE = 127
}

enum class RawChannel(val wireValue: String) {
    MEDIA_SESSION_KEY_EVENT("media_session_key_event"),
    MEDIA_SESSION_TRANSPORT("media_session_transport"),
    MEDIA_BUTTON_BROADCAST("media_button_broadcast"),
    ACTIVITY_KEY_EVENT("activity_key_event"),
    SCREEN_UI("screen_ui"),
}

enum class TransportCallbackName(val wireValue: String) {
    ON_PLAY("onPlay"),
    ON_PAUSE("onPause"),
    ON_STOP("onStop"),
    ON_SKIP_TO_NEXT("onSkipToNext"),
    ON_SKIP_TO_PREVIOUS("onSkipToPrevious"),
}

sealed class RawAndroidControlInput {
    abstract val monotonicNs: Long

    data class KeyInput(
        val channel: RawChannel,
        val keyCode: Int,
        val action: Int,
        val repeatCount: Int,
        override val monotonicNs: Long,
        val eventTimeMs: Long,
        val isExternalHeadset: Boolean?,
    ) : RawAndroidControlInput()

    data class TransportCallback(
        val callbackName: TransportCallbackName,
        override val monotonicNs: Long,
        val isExternalHeadset: Boolean?,
    ) : RawAndroidControlInput()

    data class ScreenButton(
        val command: BusinessCommand,
        override val monotonicNs: Long,
    ) : RawAndroidControlInput()
}

data class NormalizedControlDecision(
    val sequence: Long,
    val source: ControlSource,
    val platformEvent: PlatformEvent?,
    val command: BusinessCommand,
    val rawPlatformLabel: String,
    val rawEventId: String?,
    val deduplicated: Boolean,
    val reason: String,
    val contractGapNote: String? = null,
)

/**
 * Normalizes Android media-button KeyEvents, MediaSession transport callbacks, and screen actions
 * into contract-level BusinessCommands with multi-channel deduplication (PRD FR-HS-001, FR-HS-004).
 */
class ControlEventNormalizer(
    private val pairedTransportWindowNs: Long = 150_000_000L,
) {
    private var sequenceCounter: Long = 0L
    private var lastRawEventId: String? = null
    private var lastKeyDownPlatformEvent: PlatformEvent? = null
    private var lastKeyDownMonotonicNs: Long = -1L

    fun normalize(
        input: RawAndroidControlInput,
        mapping: CommandMapping,
    ): NormalizedControlDecision {
        sequenceCounter += 1
        return when (input) {
            is RawAndroidControlInput.ScreenButton -> NormalizedControlDecision(
                sequence = sequenceCounter,
                source = ControlSource.SCREEN_CONTROL,
                platformEvent = PlatformEvent.SCREEN_BUTTON,
                command = input.command,
                rawPlatformLabel = "screen_button_${input.command.name.lowercase()}",
                rawEventId = null,
                deduplicated = false,
                reason = "screen_control",
            )
            is RawAndroidControlInput.KeyInput -> normalizeKeyInput(input, mapping)
            is RawAndroidControlInput.TransportCallback -> normalizeTransport(input, mapping)
        }
    }

    private fun normalizeKeyInput(
        input: RawAndroidControlInput.KeyInput,
        mapping: CommandMapping,
    ): NormalizedControlDecision {
        val rawId = "key:${input.keyCode}:action:${input.action}:t:${input.eventTimeMs}"
        val source = classifyRemoteSource(input.isExternalHeadset)

        if (input.action != RawAndroidKeyCodes.ACTION_DOWN) {
            return NormalizedControlDecision(
                sequence = sequenceCounter,
                source = source,
                platformEvent = mapKeyCode(input.keyCode)?.platformEvent,
                command = BusinessCommand.IGNORE,
                rawPlatformLabel = keyCodeLabel(input.keyCode),
                rawEventId = rawId,
                deduplicated = false,
                reason = "ignored_key_up",
            )
        }

        if (input.repeatCount > 0) {
            return NormalizedControlDecision(
                sequence = sequenceCounter,
                source = source,
                platformEvent = mapKeyCode(input.keyCode)?.platformEvent,
                command = BusinessCommand.IGNORE,
                rawPlatformLabel = keyCodeLabel(input.keyCode),
                rawEventId = rawId,
                deduplicated = false,
                reason = "ignored_key_repeat",
            )
        }

        if (lastRawEventId == rawId) {
            return NormalizedControlDecision(
                sequence = sequenceCounter,
                source = source,
                platformEvent = mapKeyCode(input.keyCode)?.platformEvent,
                command = BusinessCommand.IGNORE,
                rawPlatformLabel = keyCodeLabel(input.keyCode),
                rawEventId = rawId,
                deduplicated = true,
                reason = "deduplicated_raw_event_id",
            )
        }

        val mapped = mapKeyCode(input.keyCode)
        lastRawEventId = rawId
        if (mapped != null) {
            lastKeyDownPlatformEvent = mapped.platformEvent
            lastKeyDownMonotonicNs = input.monotonicNs
        }

        if (mapped == null) {
            return NormalizedControlDecision(
                sequence = sequenceCounter,
                source = source,
                platformEvent = null,
                command = BusinessCommand.IGNORE,
                rawPlatformLabel = keyCodeLabel(input.keyCode),
                rawEventId = rawId,
                deduplicated = false,
                reason = "unmapped_key_code",
            )
        }

        val command = applyMapping(mapped.platformEvent, mapped.isStopSemantic, mapping)
        val reason = if (mapping == CommandMapping.LOG_ONLY) "log_only_mapping" else "mapped_${mapping.wireValue}"
        return NormalizedControlDecision(
            sequence = sequenceCounter,
            source = source,
            platformEvent = mapped.platformEvent,
            command = command,
            rawPlatformLabel = keyCodeLabel(input.keyCode),
            rawEventId = rawId,
            deduplicated = false,
            reason = reason,
            contractGapNote = mapped.contractGapNote,
        )
    }

    private fun normalizeTransport(
        input: RawAndroidControlInput.TransportCallback,
        mapping: CommandMapping,
    ): NormalizedControlDecision {
        val source = classifyRemoteSource(input.isExternalHeadset)
        val mapped = mapTransport(input.callbackName)

        val withinPairedWindow = lastKeyDownMonotonicNs >= 0 &&
            (input.monotonicNs - lastKeyDownMonotonicNs) in 0L..pairedTransportWindowNs
        if (withinPairedWindow && isPairedCallback(lastKeyDownPlatformEvent, mapped.platformEvent)) {
            return NormalizedControlDecision(
                sequence = sequenceCounter,
                source = source,
                platformEvent = mapped.platformEvent,
                command = BusinessCommand.IGNORE,
                rawPlatformLabel = input.callbackName.wireValue,
                rawEventId = null,
                deduplicated = true,
                reason = "deduplicated_paired_transport",
                contractGapNote = mapped.contractGapNote,
            )
        }

        val command = applyMapping(mapped.platformEvent, mapped.isStopSemantic, mapping)
        val reason = if (mapping == CommandMapping.LOG_ONLY) "log_only_mapping" else "mapped_${mapping.wireValue}"
        return NormalizedControlDecision(
            sequence = sequenceCounter,
            source = source,
            platformEvent = mapped.platformEvent,
            command = command,
            rawPlatformLabel = input.callbackName.wireValue,
            rawEventId = null,
            deduplicated = false,
            reason = reason,
            contractGapNote = mapped.contractGapNote,
        )
    }

    private fun isPairedCallback(keyPlatform: PlatformEvent?, transportPlatform: PlatformEvent): Boolean {
        if (keyPlatform == null) return false
        if (keyPlatform == transportPlatform) return true
        // KEYCODE_MEDIA_PLAY_PAUSE / KEYCODE_HEADSETHOOK triggers onPlay or onPause via super.onMediaButtonEvent.
        return keyPlatform == PlatformEvent.TOGGLE &&
            (transportPlatform == PlatformEvent.PLAY || transportPlatform == PlatformEvent.PAUSE)
    }

    private fun applyMapping(
        platformEvent: PlatformEvent,
        isStopSemantic: Boolean,
        mapping: CommandMapping,
    ): BusinessCommand {
        if (mapping == CommandMapping.LOG_ONLY) return BusinessCommand.IGNORE
        return when (mapping) {
            CommandMapping.LOG_ONLY -> BusinessCommand.IGNORE
            CommandMapping.SEMANTIC -> when (platformEvent) {
                PlatformEvent.PLAY -> BusinessCommand.START
                PlatformEvent.PAUSE -> BusinessCommand.STOP
                PlatformEvent.TOGGLE -> BusinessCommand.TOGGLE
                PlatformEvent.NEXT,
                PlatformEvent.PREVIOUS,
                PlatformEvent.VOLUME_CHANGE,
                PlatformEvent.SCREEN_BUTTON -> if (isStopSemantic) BusinessCommand.STOP else BusinessCommand.IGNORE
            }
            CommandMapping.TOGGLE -> when (platformEvent) {
                PlatformEvent.PLAY,
                PlatformEvent.PAUSE,
                PlatformEvent.TOGGLE -> BusinessCommand.TOGGLE
                PlatformEvent.NEXT,
                PlatformEvent.PREVIOUS,
                PlatformEvent.VOLUME_CHANGE,
                PlatformEvent.SCREEN_BUTTON -> BusinessCommand.IGNORE
            }
        }
    }

    private fun classifyRemoteSource(isExternalHeadset: Boolean?): ControlSource =
        if (isExternalHeadset == true) ControlSource.HEADSET_REMOTE else ControlSource.UNKNOWN_REMOTE

    private data class MappedPlatform(
        val platformEvent: PlatformEvent,
        val isStopSemantic: Boolean = false,
        val contractGapNote: String? = null,
    )

    private fun mapKeyCode(keyCode: Int): MappedPlatform? = when (keyCode) {
        RawAndroidKeyCodes.KEYCODE_MEDIA_PLAY -> MappedPlatform(PlatformEvent.PLAY)
        RawAndroidKeyCodes.KEYCODE_MEDIA_PAUSE -> MappedPlatform(PlatformEvent.PAUSE, isStopSemantic = true)
        RawAndroidKeyCodes.KEYCODE_MEDIA_STOP -> MappedPlatform(
            platformEvent = PlatformEvent.PAUSE,
            isStopSemantic = true,
            contractGapNote = "android_media_stop_not_in_contract_platform_event",
        )
        RawAndroidKeyCodes.KEYCODE_MEDIA_PLAY_PAUSE -> MappedPlatform(PlatformEvent.TOGGLE)
        RawAndroidKeyCodes.KEYCODE_HEADSETHOOK -> MappedPlatform(
            platformEvent = PlatformEvent.TOGGLE,
            contractGapNote = "android_keycode_headsethook_not_in_contract_platform_event",
        )
        RawAndroidKeyCodes.KEYCODE_MEDIA_NEXT -> MappedPlatform(PlatformEvent.NEXT)
        RawAndroidKeyCodes.KEYCODE_MEDIA_PREVIOUS -> MappedPlatform(PlatformEvent.PREVIOUS)
        else -> null
    }

    private fun mapTransport(name: TransportCallbackName): MappedPlatform = when (name) {
        TransportCallbackName.ON_PLAY -> MappedPlatform(PlatformEvent.PLAY)
        TransportCallbackName.ON_PAUSE -> MappedPlatform(PlatformEvent.PAUSE, isStopSemantic = true)
        TransportCallbackName.ON_STOP -> MappedPlatform(
            platformEvent = PlatformEvent.PAUSE,
            isStopSemantic = true,
            contractGapNote = "android_media_stop_not_in_contract_platform_event",
        )
        TransportCallbackName.ON_SKIP_TO_NEXT -> MappedPlatform(PlatformEvent.NEXT)
        TransportCallbackName.ON_SKIP_TO_PREVIOUS -> MappedPlatform(PlatformEvent.PREVIOUS)
    }

    private fun keyCodeLabel(keyCode: Int): String = when (keyCode) {
        RawAndroidKeyCodes.KEYCODE_HEADSETHOOK -> "KEYCODE_HEADSETHOOK"
        RawAndroidKeyCodes.KEYCODE_MEDIA_PLAY_PAUSE -> "KEYCODE_MEDIA_PLAY_PAUSE"
        RawAndroidKeyCodes.KEYCODE_MEDIA_STOP -> "KEYCODE_MEDIA_STOP"
        RawAndroidKeyCodes.KEYCODE_MEDIA_NEXT -> "KEYCODE_MEDIA_NEXT"
        RawAndroidKeyCodes.KEYCODE_MEDIA_PREVIOUS -> "KEYCODE_MEDIA_PREVIOUS"
        RawAndroidKeyCodes.KEYCODE_MEDIA_PLAY -> "KEYCODE_MEDIA_PLAY"
        RawAndroidKeyCodes.KEYCODE_MEDIA_PAUSE -> "KEYCODE_MEDIA_PAUSE"
        else -> "KEYCODE_$keyCode"
    }
}
