package app.auri.probe

import android.app.Application
import android.os.Build
import app.auri.probe.core.CommandMapping
import app.auri.probe.core.ControlEventNormalizer
import app.auri.probe.core.ForegroundServiceState
import app.auri.probe.core.RawAndroidControlInput

/**
 * Process-wide registry so BroadcastReceiver, ForegroundService, and MainActivity share one
 * serialized probe instance when alive, and stay DISARMED after process termination.
 */
object ProbeRuntimeRegistry {
    lateinit var lifecycleProbe: LifecycleProbe
        private set
    lateinit var mediaControlProbe: MediaControlProbe
        private set
    lateinit var captureProbe: CaptureProbe
        private set

    private val normalizer = ControlEventNormalizer()

    @Volatile
    var commandMapping: CommandMapping = CommandMapping.SEMANTIC

    @Volatile
    private var initialized = false

    fun init(app: Application) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            ProbeLogger.init(app)
            lifecycleProbe = LifecycleProbe(app).also { it.start() }
            mediaControlProbe = MediaControlProbe(app)
            captureProbe = CaptureProbe(app, lifecycleProbe)
            initialized = true
            ProbeLogger.log(
                "process_initialized",
                mapOf(
                    "sdk_int" to Build.VERSION.SDK_INT,
                    "release" to Build.VERSION.RELEASE,
                    "model" to Build.MODEL,
                    "brand" to Build.BRAND,
                ),
            )
        }
    }

    fun isArmed(): Boolean = initialized && mediaControlProbe.isArmed()

    fun setArmed(armed: Boolean, mode: MediaControlProbe.PlaybackStateMode): Boolean {
        if (!initialized) return false
        return if (armed) {
            mediaControlProbe.arm(mode)
            captureProbe.enableControlMode()
            true
        } else {
            val disabled = captureProbe.disableControlMode()
            if (disabled) {
                mediaControlProbe.disarm()
            }
            disabled
        }
    }

    fun handleControlInput(input: RawAndroidControlInput) {
        if (!initialized) return
        val decision = normalizer.normalize(input, commandMapping)
        ProbeLogger.log(
            "normalized_control_event",
            mapOf(
                "sequence" to decision.sequence,
                "source" to decision.source.wireValue,
                "platform_event" to decision.platformEvent?.wireValue,
                "command" to decision.command.name,
                "raw_label" to decision.rawPlatformLabel,
                "deduplicated" to decision.deduplicated,
                "reason" to decision.reason,
                "contract_gap_note" to decision.contractGapNote,
            ),
        )
        captureProbe.applyNormalizedCommand(decision)
    }

    fun onFgsStarted(
        fgsState: ForegroundServiceState,
        sessionId: String,
        generation: Long,
        probeOnly: Boolean,
    ) {
        if (!initialized) return
        captureProbe.onFgsStarted(fgsState, sessionId, generation, probeOnly)
    }

    fun onFgsFailed(
        fgsState: ForegroundServiceState,
        sessionId: String,
        generation: Long,
        errorMessage: String,
    ) {
        if (!initialized) return
        captureProbe.onFgsFailed(fgsState, sessionId, generation, errorMessage)
    }

    fun onFgsStopped() {
        if (!initialized) return
        captureProbe.onFgsStopped()
    }
}

class ProbeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ProbeRuntimeRegistry.init(this)
    }
}
