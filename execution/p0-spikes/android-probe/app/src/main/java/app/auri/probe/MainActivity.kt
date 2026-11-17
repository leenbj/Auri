package app.auri.probe

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import app.auri.probe.core.BusinessCommand
import app.auri.probe.core.CaptureEligibilityResult
import app.auri.probe.core.CaptureState
import app.auri.probe.core.CommandMapping
import app.auri.probe.core.RawAndroidControlInput
import app.auri.probe.core.RawChannel

class MainActivity : AppCompatActivity() {

    private lateinit var txtStatusHeader: TextView
    private lateinit var txtTwoLayerStatus: TextView
    private lateinit var txtLogTail: TextView
    private lateinit var switchArmed: SwitchCompat

    private val tailLines = ArrayDeque<String>()
    private var suppressSwitchCallback = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        ProbeLogger.log("runtime_permissions_result", grants)
        ProbeRuntimeRegistry.captureProbe.evaluateTwoLayers(logEvent = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ProbeRuntimeRegistry.init(application)

        txtStatusHeader = findViewById(R.id.txtStatusHeader)
        txtTwoLayerStatus = findViewById(R.id.txtTwoLayerStatus)
        txtLogTail = findViewById(R.id.txtLogTail)
        switchArmed = findViewById(R.id.switchArmed)

        val capture = ProbeRuntimeRegistry.captureProbe
        val media = ProbeRuntimeRegistry.mediaControlProbe

        ProbeLogger.onLine = { redactedLine ->
            runOnUiThread {
                tailLines.addFirst(redactedLine)
                while (tailLines.size > 30) tailLines.removeLast()
                txtLogTail.text = tailLines.joinToString("\n")
            }
        }

        capture.onStateChange = { state, sessionId ->
            runOnUiThread {
                txtStatusHeader.text = "State: ${state.name} | Session: ${sessionId.take(8)}"
            }
        }
        capture.onEligibilityChange = { result ->
            runOnUiThread { renderEligibility(result) }
        }

        findViewById<Button>(R.id.btnRequestPermissions).setOnClickListener {
            requestProbePermissions()
        }
        findViewById<Button>(R.id.btnCheckLayers).setOnClickListener {
            capture.evaluateTwoLayers(logEvent = true)
        }
        findViewById<Button>(R.id.btnProbeFgsOnly).setOnClickListener {
            capture.probeFgsOnly()
        }
        findViewById<Button>(R.id.btnManualStart).setOnClickListener {
            ProbeRuntimeRegistry.handleControlInput(
                RawAndroidControlInput.ScreenButton(
                    command = BusinessCommand.START,
                    monotonicNs = ProbeLogger.monoNanos(),
                ),
            )
        }
        findViewById<Button>(R.id.btnManualStop).setOnClickListener {
            ProbeRuntimeRegistry.handleControlInput(
                RawAndroidControlInput.ScreenButton(
                    command = BusinessCommand.STOP,
                    monotonicNs = ProbeLogger.monoNanos(),
                ),
            )
        }

        findViewById<Button>(R.id.btnMarkDouble).setOnClickListener { markGesture("double_tap") }
        findViewById<Button>(R.id.btnMarkTriple).setOnClickListener { markGesture("triple_tap") }
        findViewById<Button>(R.id.btnMarkLong).setOnClickListener { markGesture("long_press") }
        findViewById<Button>(R.id.btnMarkSwipe).setOnClickListener { markGesture("swipe") }

        findViewById<Button>(R.id.btnExportRedacted).setOnClickListener {
            ProbeLogger.writeRedactedCopy()
        }

        val spinnerMapping = findViewById<Spinner>(R.id.spinnerMapping)
        val spinnerPlayback = findViewById<Spinner>(R.id.spinnerPlaybackState)
        val spinnerSource = findViewById<Spinner>(R.id.spinnerAudioSource)
        val spinnerRouting = findViewById<Spinner>(R.id.spinnerRoutingMode)

        setupSpinner(spinnerMapping, CommandMapping.entries.map { it.wireValue }) { idx ->
            ProbeRuntimeRegistry.commandMapping = CommandMapping.entries[idx]
            ProbeLogger.log("mapping_selected", mapOf("mapping" to ProbeRuntimeRegistry.commandMapping.wireValue))
        }
        spinnerMapping.setSelection(CommandMapping.SEMANTIC.ordinal)

        setupSpinner(spinnerPlayback, MediaControlProbe.PlaybackStateMode.entries.map { it.wireValue }) { idx ->
            media.setPlaybackStateMode(MediaControlProbe.PlaybackStateMode.entries[idx])
        }
        spinnerPlayback.setSelection(MediaControlProbe.PlaybackStateMode.PAUSED.ordinal)

        setupSpinner(spinnerSource, CaptureProbe.AudioSourceChoice.entries.map { it.wireValue }) { idx ->
            capture.audioSourceChoice = CaptureProbe.AudioSourceChoice.entries[idx]
            ProbeLogger.log("audio_source_selected", mapOf("audio_source" to capture.audioSourceChoice.wireValue))
        }
        spinnerSource.setSelection(CaptureProbe.AudioSourceChoice.VOICE_COMMUNICATION.ordinal)

        setupSpinner(spinnerRouting, CaptureProbe.RoutingModeChoice.entries.map { it.wireValue }) { idx ->
            capture.routingModeChoice = CaptureProbe.RoutingModeChoice.entries[idx]
            ProbeLogger.log("routing_mode_selected", mapOf("routing_mode" to capture.routingModeChoice.wireValue))
        }
        spinnerRouting.setSelection(CaptureProbe.RoutingModeChoice.SET_COMMUNICATION_DEVICE.ordinal)

        switchArmed.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSwitchCallback) return@setOnCheckedChangeListener
            val playbackMode = MediaControlProbe.PlaybackStateMode.entries[spinnerPlayback.selectedItemPosition]
            val ok = ProbeRuntimeRegistry.setArmed(isChecked, playbackMode)
            if (!ok) {
                suppressSwitchCallback = true
                switchArmed.isChecked = !isChecked
                suppressSwitchCallback = false
            }
        }

        val initialEligibility = capture.evaluateTwoLayers(logEvent = true)
        renderEligibility(initialEligibility)
        txtStatusHeader.text = "State: ${capture.state().name}"
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isMediaKey(keyCode)) {
            ProbeLogger.log(
                "activity_key_down",
                mapOf(
                    "key_code" to keyCode,
                    "key_code_name" to KeyEvent.keyCodeToString(keyCode),
                    "repeat_count" to event.repeatCount,
                    "event_time_ms" to event.eventTime,
                    "is_external" to event.device?.isExternal,
                    "device_name" to event.device?.name,
                ),
            )
            if (ProbeRuntimeRegistry.isArmed()) {
                ProbeRuntimeRegistry.handleControlInput(
                    RawAndroidControlInput.KeyInput(
                        channel = RawChannel.ACTIVITY_KEY_EVENT,
                        keyCode = keyCode,
                        action = event.action,
                        repeatCount = event.repeatCount,
                        monotonicNs = ProbeLogger.monoNanos(),
                        eventTimeMs = event.eventTime,
                        isExternalHeadset = event.device?.isExternal,
                    ),
                )
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isMediaKey(keyCode)) {
            ProbeLogger.log(
                "activity_key_up",
                mapOf(
                    "key_code" to keyCode,
                    "key_code_name" to KeyEvent.keyCodeToString(keyCode),
                    "event_time_ms" to event.eventTime,
                ),
            )
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun markGesture(gesture: String) {
        ProbeLogger.log(
            "user_marker",
            mapOf("gesture" to gesture, "capture_state" to ProbeRuntimeRegistry.captureProbe.state().name),
        )
    }

    private fun requestProbePermissions() {
        val perms = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun renderEligibility(result: CaptureEligibilityResult) {
        txtTwoLayerStatus.text =
            "L1 mic=${result.permission.wireValue} bt=${result.bluetoothPermission.wireValue} | " +
            "L2 life=${result.lifecycle.wireValue} fgs=${result.fgsState.wireValue} | " +
            "canStart=${result.canStart.wireValue} (${result.reasonCode ?: result.layerNote})"
    }

    private fun setupSpinner(
        spinner: Spinner,
        items: List<String>,
        onSelected: (Int) -> Unit,
    ) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            var initialized = false
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!initialized) {
                    initialized = true
                    return
                }
                onSelected(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun isMediaKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_HEADSETHOOK,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_STOP,
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> true
        else -> false
    }
}
