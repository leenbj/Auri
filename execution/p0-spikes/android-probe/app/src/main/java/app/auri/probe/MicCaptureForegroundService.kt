package app.auri.probe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.auri.probe.core.ForegroundServiceState

/**
 * Microphone-typed ForegroundService required by Android 14+ for capture outside foreground.
 * Started ONLY for active capture or explicit Layer-2 probe; never used as permanent keep-alive.
 */
class MicCaptureForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_FOR_CAPTURE
        if (action == ACTION_STOP) {
            ProbeLogger.log("fgs_stop_requested", mapOf("start_id" to startId))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            ProbeRuntimeRegistry.onFgsStopped()
            return START_NOT_STICKY
        }

        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID) ?: "fgs_probe_only"
        val generation = intent?.getLongExtra(EXTRA_GENERATION, -1L) ?: -1L
        val probeOnly = (action == ACTION_PROBE_ONLY)

        ensureChannel()
        val notification = buildNotification()

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            val actualType = foregroundServiceType
            val hasMicType = (actualType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0
            val fgsState = if (hasMicType) {
                ForegroundServiceState.RUNNING_MICROPHONE
            } else {
                ForegroundServiceState.RUNNING_WRONG_TYPE
            }
            ProbeLogger.log(
                "fgs_start_foreground_result",
                mapOf(
                    "success" to hasMicType,
                    "session_id" to sessionId,
                    "generation" to generation,
                    "probe_only" to probeOnly,
                    "expected_fgs_type" to ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                    "actual_fgs_type" to actualType,
                    "fgs_state" to fgsState.wireValue,
                ),
            )
            ProbeRuntimeRegistry.onFgsStarted(
                fgsState = fgsState,
                sessionId = sessionId,
                generation = generation,
                probeOnly = probeOnly,
            )
        } catch (se: SecurityException) {
            ProbeLogger.log(
                "fgs_start_foreground_failed",
                mapOf(
                    "session_id" to sessionId,
                    "generation" to generation,
                    "exception_class" to se.javaClass.simpleName,
                    "error" to (se.message ?: "security_exception"),
                ),
            )
            ProbeRuntimeRegistry.onFgsFailed(
                fgsState = ForegroundServiceState.START_FAILED_SECURITY,
                sessionId = sessionId,
                generation = generation,
                errorMessage = se.message ?: "SecurityException",
            )
            stopSelf()
        } catch (e: Exception) {
            ProbeLogger.log(
                "fgs_start_foreground_failed",
                mapOf(
                    "session_id" to sessionId,
                    "generation" to generation,
                    "exception_class" to e.javaClass.simpleName,
                    "error" to (e.message ?: "start_foreground_exception"),
                ),
            )
            ProbeRuntimeRegistry.onFgsFailed(
                fgsState = ForegroundServiceState.START_FAILED_OTHER,
                sessionId = sessionId,
                generation = generation,
                errorMessage = e.message ?: e.javaClass.simpleName,
            )
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        ProbeLogger.log("fgs_destroyed")
        ProbeRuntimeRegistry.onFgsStopped()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.fgs_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.fgs_notification_title))
            .setContentText(getString(R.string.fgs_notification_body))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

    companion object {
        const val CHANNEL_ID = "auri_probe_mic_fgs"
        const val NOTIFICATION_ID = 9001
        const val ACTION_START_FOR_CAPTURE = "app.auri.probe.action.START_FOR_CAPTURE"
        const val ACTION_PROBE_ONLY = "app.auri.probe.action.PROBE_ONLY"
        const val ACTION_STOP = "app.auri.probe.action.STOP"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_GENERATION = "generation"
    }
}
