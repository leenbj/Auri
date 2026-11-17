package app.auri.probe

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.PowerManager
import androidx.core.content.ContextCompat
import app.auri.probe.core.AppLifecycleState

/**
 * Tracks foreground/background/locked lifecycle transitions accurately without guessing.
 */
class LifecycleProbe(private val app: Application) {
    private var startedActivities = 0
    private var started = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = currentState()
            ProbeLogger.setAppState(state.wireValue)
            ProbeLogger.log(
                "screen_or_keyguard_broadcast",
                mapOf("action" to intent.action, "computed_state" to state.wireValue),
            )
        }
    }

    fun start() {
        if (started) return
        started = true

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                ProbeLogger.log("activity_created", mapOf("activity" to activity.javaClass.simpleName))
            }

            override fun onActivityStarted(activity: Activity) {
                startedActivities += 1
                val state = currentState()
                ProbeLogger.setAppState(state.wireValue)
                ProbeLogger.log(
                    "activity_started",
                    mapOf("started_count" to startedActivities, "computed_state" to state.wireValue),
                )
            }

            override fun onActivityResumed(activity: Activity) {
                val state = currentState()
                ProbeLogger.setAppState(state.wireValue)
                ProbeLogger.log("activity_resumed", mapOf("computed_state" to state.wireValue))
            }

            override fun onActivityPaused(activity: Activity) {
                ProbeLogger.log("activity_paused", mapOf("computed_state" to currentState().wireValue))
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                val state = currentState()
                ProbeLogger.setAppState(state.wireValue)
                ProbeLogger.log(
                    "activity_stopped",
                    mapOf("started_count" to startedActivities, "computed_state" to state.wireValue),
                )
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {
                ProbeLogger.log("activity_destroyed", mapOf("activity" to activity.javaClass.simpleName))
            }
        })

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(app, screenReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        val initial = currentState()
        ProbeLogger.setAppState(initial.wireValue)
    }

    fun currentState(): AppLifecycleState {
        val pm = app.getSystemService(PowerManager::class.java)
        val km = app.getSystemService(KeyguardManager::class.java)
        val interactive = pm?.isInteractive ?: true
        val locked = km?.isKeyguardLocked ?: false
        return when {
            !interactive || locked -> AppLifecycleState.LOCKED
            startedActivities > 0 -> AppLifecycleState.FOREGROUND
            else -> AppLifecycleState.BACKGROUND
        }
    }
}
