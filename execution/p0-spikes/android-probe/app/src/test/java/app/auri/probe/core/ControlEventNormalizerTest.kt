package app.auri.probe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlEventNormalizerTest {

    @Test
    fun keyUpAndRepeatEventsAreIgnored() {
        val normalizer = ControlEventNormalizer()

        val keyUp = RawAndroidControlInput.KeyInput(
            channel = RawChannel.MEDIA_SESSION_KEY_EVENT,
            keyCode = RawAndroidKeyCodes.KEYCODE_MEDIA_PLAY,
            action = RawAndroidKeyCodes.ACTION_UP,
            repeatCount = 0,
            monotonicNs = 1_000_000L,
            eventTimeMs = 100L,
            isExternalHeadset = true,
        )
        val upDecision = normalizer.normalize(keyUp, CommandMapping.SEMANTIC)
        assertEquals(BusinessCommand.IGNORE, upDecision.command)
        assertEquals("ignored_key_up", upDecision.reason)

        val repeatDown = RawAndroidControlInput.KeyInput(
            channel = RawChannel.MEDIA_SESSION_KEY_EVENT,
            keyCode = RawAndroidKeyCodes.KEYCODE_MEDIA_PLAY,
            action = RawAndroidKeyCodes.ACTION_DOWN,
            repeatCount = 1,
            monotonicNs = 2_000_000L,
            eventTimeMs = 101L,
            isExternalHeadset = true,
        )
        val repeatDecision = normalizer.normalize(repeatDown, CommandMapping.SEMANTIC)
        assertEquals(BusinessCommand.IGNORE, repeatDecision.command)
        assertEquals("ignored_key_repeat", repeatDecision.reason)
    }

    @Test
    fun semanticMappingTranslatesPlayPauseToggleAndLeavesNextPreviousIgnored() {
        val normalizer = ControlEventNormalizer()

        val play = normalizer.normalize(
            RawAndroidControlInput.KeyInput(
                channel = RawChannel.MEDIA_SESSION_KEY_EVENT,
                keyCode = RawAndroidKeyCodes.KEYCODE_MEDIA_PLAY,
                action = RawAndroidKeyCodes.ACTION_DOWN,
                repeatCount = 0,
                monotonicNs = 1_000_000_000L,
                eventTimeMs = 1000L,
                isExternalHeadset = true,
            ),
            CommandMapping.SEMANTIC,
        )
        assertEquals(BusinessCommand.START, play.command)
        assertEquals(PlatformEvent.PLAY, play.platformEvent)
        assertEquals(ControlSource.HEADSET_REMOTE, play.source)
        assertFalse(play.deduplicated)

        val pause = normalizer.normalize(
            RawAndroidControlInput.KeyInput(
                channel = RawChannel.MEDIA_SESSION_KEY_EVENT,
                keyCode = RawAndroidKeyCodes.KEYCODE_MEDIA_PAUSE,
                action = RawAndroidKeyCodes.ACTION_DOWN,
                repeatCount = 0,
                monotonicNs = 2_000_000_000L,
                eventTimeMs = 2000L,
                isExternalHeadset = false,
            ),
            CommandMapping.SEMANTIC,
        )
        assertEquals(BusinessCommand.STOP, pause.command)
        assertEquals(PlatformEvent.PAUSE, pause.platformEvent)
        assertEquals(ControlSource.UNKNOWN_REMOTE, pause.source)

        val toggle = normalizer.normalize(
            RawAndroidControlInput.KeyInput(
                channel = RawChannel.MEDIA_SESSION_KEY_EVENT,
                keyCode = RawAndroidKeyCodes.KEYCODE_MEDIA_PLAY_PAUSE,
                action = RawAndroidKeyCodes.ACTION_DOWN,
                repeatCount = 0,
                monotonicNs = 3_000_000_000L,
                eventTimeMs = 3000L,
                isExternalHeadset = true,
            ),
            CommandMapping.SEMANTIC,
        )
        assertEquals(BusinessCommand.TOGGLE, toggle.command)
        assertEquals(PlatformEvent.TOGGLE, toggle.platformEvent)

        val next = normalizer.normalize(
            RawAndroidControlInput.KeyInput(
                channel = RawChannel.MEDIA_SESSION_KEY_EVENT,
                keyCode = RawAndroidKeyCodes.KEYCODE_MEDIA_NEXT,
                action = RawAndroidKeyCodes.ACTION_DOWN,
                repeatCount = 0,
                monotonicNs = 4_000_000_000L,
                eventTimeMs = 4000L,
                isExternalHeadset = true,
            ),
            CommandMapping.SEMANTIC,
        )
        assertEquals(BusinessCommand.IGNORE, next.command)
        assertEquals(PlatformEvent.NEXT, next.platformEvent)
    }

    @Test
    fun logOnlyMappingIgnoresAllRemoteCommandsWhileKeepingObservedPlatformEvent() {
        val normalizer = ControlEventNormalizer()

        val decision = normalizer.normalize(
            RawAndroidControlInput.TransportCallback(
                callbackName = TransportCallbackName.ON_PLAY,
                monotonicNs = 5_000_000_000L,
                isExternalHeadset = null,
            ),
            CommandMapping.LOG_ONLY,
        )
        assertEquals(BusinessCommand.IGNORE, decision.command)
        assertEquals(PlatformEvent.PLAY, decision.platformEvent)
        assertEquals("log_only_mapping", decision.reason)
    }

    @Test
    fun duplicateTransportCallbackFollowingKeyDownIsDeduplicated() {
        val normalizer = ControlEventNormalizer()

        val keyDown = normalizer.normalize(
            RawAndroidControlInput.KeyInput(
                channel = RawChannel.MEDIA_SESSION_KEY_EVENT,
                keyCode = RawAndroidKeyCodes.KEYCODE_MEDIA_PLAY,
                action = RawAndroidKeyCodes.ACTION_DOWN,
                repeatCount = 0,
                monotonicNs = 10_000_000_000L,
                eventTimeMs = 5000L,
                isExternalHeadset = true,
            ),
            CommandMapping.SEMANTIC,
        )
        assertEquals(BusinessCommand.START, keyDown.command)
        assertFalse(keyDown.deduplicated)

        val pairedOnPlay = normalizer.normalize(
            RawAndroidControlInput.TransportCallback(
                callbackName = TransportCallbackName.ON_PLAY,
                monotonicNs = 10_010_000_000L,
                isExternalHeadset = null,
            ),
            CommandMapping.SEMANTIC,
        )
        assertEquals(BusinessCommand.IGNORE, pairedOnPlay.command)
        assertTrue(pairedOnPlay.deduplicated)
        assertEquals("deduplicated_paired_transport", pairedOnPlay.reason)
    }

    @Test
    fun identicalKeyEventTimeFromSecondChannelIsDeduplicated() {
        val normalizer = ControlEventNormalizer()

        val first = normalizer.normalize(
            RawAndroidControlInput.KeyInput(
                channel = RawChannel.MEDIA_BUTTON_BROADCAST,
                keyCode = RawAndroidKeyCodes.KEYCODE_MEDIA_PLAY_PAUSE,
                action = RawAndroidKeyCodes.ACTION_DOWN,
                repeatCount = 0,
                monotonicNs = 20_000_000_000L,
                eventTimeMs = 8888L,
                isExternalHeadset = true,
            ),
            CommandMapping.TOGGLE,
        )
        assertEquals(BusinessCommand.TOGGLE, first.command)

        val second = normalizer.normalize(
            RawAndroidControlInput.KeyInput(
                channel = RawChannel.MEDIA_SESSION_KEY_EVENT,
                keyCode = RawAndroidKeyCodes.KEYCODE_MEDIA_PLAY_PAUSE,
                action = RawAndroidKeyCodes.ACTION_DOWN,
                repeatCount = 0,
                monotonicNs = 20_025_000_000L,
                eventTimeMs = 8888L,
                isExternalHeadset = true,
            ),
            CommandMapping.TOGGLE,
        )
        assertEquals(BusinessCommand.IGNORE, second.command)
        assertTrue(second.deduplicated)
        assertEquals("deduplicated_raw_event_id", second.reason)
    }

    @Test
    fun headsetHookAndMediaStopFlagContractGapNotes() {
        val normalizer = ControlEventNormalizer()

        val hook = normalizer.normalize(
            RawAndroidControlInput.KeyInput(
                channel = RawChannel.MEDIA_SESSION_KEY_EVENT,
                keyCode = RawAndroidKeyCodes.KEYCODE_HEADSETHOOK,
                action = RawAndroidKeyCodes.ACTION_DOWN,
                repeatCount = 0,
                monotonicNs = 30_000_000_000L,
                eventTimeMs = 9000L,
                isExternalHeadset = true,
            ),
            CommandMapping.SEMANTIC,
        )
        assertEquals(BusinessCommand.TOGGLE, hook.command)
        assertEquals(PlatformEvent.TOGGLE, hook.platformEvent)
        assertEquals("android_keycode_headsethook_not_in_contract_platform_event", hook.contractGapNote)

        val stop = normalizer.normalize(
            RawAndroidControlInput.TransportCallback(
                callbackName = TransportCallbackName.ON_STOP,
                monotonicNs = 31_000_000_000L,
                isExternalHeadset = null,
            ),
            CommandMapping.SEMANTIC,
        )
        assertEquals(BusinessCommand.STOP, stop.command)
        assertEquals(PlatformEvent.PAUSE, stop.platformEvent)
        assertEquals("android_media_stop_not_in_contract_platform_event", stop.contractGapNote)
    }

    @Test
    fun screenButtonNeverUsesHeadsetProfileAndMapsDirectly() {
        val normalizer = ControlEventNormalizer()

        val start = normalizer.normalize(
            RawAndroidControlInput.ScreenButton(
                command = BusinessCommand.START,
                monotonicNs = 40_000_000_000L,
            ),
            CommandMapping.LOG_ONLY,
        )
        assertEquals(BusinessCommand.START, start.command)
        assertEquals(PlatformEvent.SCREEN_BUTTON, start.platformEvent)
        assertEquals(ControlSource.SCREEN_CONTROL, start.source)
        assertNull(start.contractGapNote)
    }
}
