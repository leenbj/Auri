package app.auri.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import app.auri.probe.core.RawAndroidControlInput
import app.auri.probe.core.RawChannel

/**
 * Manifest-declared ACTION_MEDIA_BUTTON receiver to observe whether Android delivers media buttons
 * when the app is backgrounded or after process termination (PRD §4.4, §12.1).
 * Never auto-starts recording when process was killed (must return to DISARMED).
 */
class ProbeMediaButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        ProbeLogger.init(context)
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return

        val event = IntentCompat.getParcelableExtra(
            intent,
            Intent.EXTRA_KEY_EVENT,
            KeyEvent::class.java,
        )
        val armed = ProbeRuntimeRegistry.isArmed()
        val isExternal = event?.device?.isExternal

        ProbeLogger.log(
            "media_button_broadcast",
            mapOf(
                "in_process_armed" to armed,
                "key_code" to event?.keyCode,
                "key_code_name" to event?.let { KeyEvent.keyCodeToString(it.keyCode) },
                "action" to event?.action,
                "repeat_count" to event?.repeatCount,
                "event_time_ms" to event?.eventTime,
                "is_external" to isExternal,
                "device_name" to event?.device?.name,
            ),
        )

        if (!armed) {
            // Process restored by broadcast or user disarmed: stay DISARMED, never auto-open mic.
            ProbeLogger.log("media_button_broadcast_ignored_disarmed_or_cold_process")
            return
        }

        if (event != null) {
            ProbeRuntimeRegistry.handleControlInput(
                RawAndroidControlInput.KeyInput(
                    channel = RawChannel.MEDIA_BUTTON_BROADCAST,
                    keyCode = event.keyCode,
                    action = event.action,
                    repeatCount = event.repeatCount,
                    monotonicNs = ProbeLogger.monoNanos(),
                    eventTimeMs = event.eventTime,
                    isExternalHeadset = isExternal,
                ),
            )
        }
    }
}
