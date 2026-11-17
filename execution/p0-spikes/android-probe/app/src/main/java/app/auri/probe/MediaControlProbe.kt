package app.auri.probe

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import app.auri.probe.core.RawAndroidControlInput
import app.auri.probe.core.RawChannel
import app.auri.probe.core.TransportCallbackName

/**
 * Registers framework MediaSession without playing any audio.
 * Logs raw KeyEvents delivered to onMediaButtonEvent AND transport callbacks (onPlay/onPause/onStop/etc.).
 */
class MediaControlProbe(private val context: Context) {

    enum class PlaybackStateMode(val wireValue: String) {
        NONE("none"),
        PAUSED("paused"),
        PLAYING("playing"),
    }

    private var session: MediaSession? = null
    private var playbackMode: PlaybackStateMode = PlaybackStateMode.PAUSED

    fun isArmed(): Boolean = session != null

    fun arm(mode: PlaybackStateMode = playbackMode) {
        playbackMode = mode
        if (session != null) {
            applyPlaybackState(mode)
            return
        }

        val mediaSession = MediaSession(context.applicationContext, "AuriProbeMediaSession")
        mediaSession.setCallback(
            object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = IntentCompat.getParcelableExtra(
                        mediaButtonIntent,
                        Intent.EXTRA_KEY_EVENT,
                        KeyEvent::class.java,
                    )
                    if (event != null) {
                        val isExternal = event.device?.isExternal
                        ProbeLogger.log(
                            "media_session_key_event",
                            mapOf(
                                "key_code" to event.keyCode,
                                "key_code_name" to KeyEvent.keyCodeToString(event.keyCode),
                                "action" to event.action,
                                "repeat_count" to event.repeatCount,
                                "source" to event.source,
                                "event_time_ms" to event.eventTime,
                                "device_id" to event.deviceId,
                                "is_external" to isExternal,
                                "device_name" to event.device?.name,
                            ),
                        )
                        ProbeRuntimeRegistry.handleControlInput(
                            RawAndroidControlInput.KeyInput(
                                channel = RawChannel.MEDIA_SESSION_KEY_EVENT,
                                keyCode = event.keyCode,
                                action = event.action,
                                repeatCount = event.repeatCount,
                                monotonicNs = ProbeLogger.monoNanos(),
                                eventTimeMs = event.eventTime,
                                isExternalHeadset = isExternal,
                            ),
                        )
                    } else {
                        ProbeLogger.log("media_session_key_event_missing_extra")
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }

                override fun onPlay() {
                    logTransportAndForward(TransportCallbackName.ON_PLAY)
                }

                override fun onPause() {
                    logTransportAndForward(TransportCallbackName.ON_PAUSE)
                }

                override fun onStop() {
                    logTransportAndForward(TransportCallbackName.ON_STOP)
                }

                override fun onSkipToNext() {
                    logTransportAndForward(TransportCallbackName.ON_SKIP_TO_NEXT)
                }

                override fun onSkipToPrevious() {
                    logTransportAndForward(TransportCallbackName.ON_SKIP_TO_PREVIOUS)
                }
            },
            Handler(Looper.getMainLooper()),
        )

        val receiverComponent = ComponentName(context.applicationContext, ProbeMediaButtonReceiver::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mediaSession.setMediaButtonBroadcastReceiver(receiverComponent)
        } else {
            val buttonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(receiverComponent)
            val pending = PendingIntent.getBroadcast(
                context.applicationContext,
                0,
                buttonIntent,
                PendingIntent.FLAG_IMMUTABLE,
            )
            @Suppress("DEPRECATION")
            mediaSession.setMediaButtonReceiver(pending)
        }

        session = mediaSession
        applyPlaybackState(mode)
        mediaSession.isActive = true
        ProbeLogger.log("media_session_armed", mapOf("playback_state_mode" to mode.wireValue))
    }

    fun setPlaybackStateMode(mode: PlaybackStateMode) {
        playbackMode = mode
        if (session != null) {
            applyPlaybackState(mode)
        } else {
            ProbeLogger.log("playback_state_mode_selected_while_disarmed", mapOf("mode" to mode.wireValue))
        }
    }

    fun disarm() {
        val current = session ?: return
        current.isActive = false
        current.release()
        session = null
        ProbeLogger.log("media_session_disarmed")
    }

    private fun applyPlaybackState(mode: PlaybackStateMode) {
        val current = session ?: return
        val actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS

        val stateCode = when (mode) {
            PlaybackStateMode.NONE -> PlaybackState.STATE_NONE
            PlaybackStateMode.PAUSED -> PlaybackState.STATE_PAUSED
            PlaybackStateMode.PLAYING -> PlaybackState.STATE_PLAYING
        }
        val speed = if (mode == PlaybackStateMode.PLAYING) 1.0f else 0.0f
        current.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(stateCode, 0L, speed)
                .build(),
        )
        ProbeLogger.log("media_session_playback_state_updated", mapOf("mode" to mode.wireValue))
    }

    private fun logTransportAndForward(callbackName: TransportCallbackName) {
        ProbeLogger.log("media_session_transport", mapOf("callback" to callbackName.wireValue))
        ProbeRuntimeRegistry.handleControlInput(
            RawAndroidControlInput.TransportCallback(
                callbackName = callbackName,
                monotonicNs = ProbeLogger.monoNanos(),
                isExternalHeadset = null,
            ),
        )
    }
}
