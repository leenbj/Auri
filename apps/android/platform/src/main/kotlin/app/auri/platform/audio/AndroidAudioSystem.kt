package app.auri.platform.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import app.auri.capture.adapter.PcmFormat
import app.auri.capture.adapter.Subscription

/**
 * [AudioSystem] 的 Android 实现。只有 JVM 测不到的框架调用在这里，逻辑都在 [BluetoothCaptureAdapter]。
 * 只能请求蓝牙：[openRecorder] 的参数是 [BluetoothInput]，且录音一建好就必须把它设为偏好设备，设不上就拒绝，
 * 不会留下一个“默认输入”（可能是手机麦克风）的录音。
 *
 * 移植自 execution/p0-spikes/android-probe 的 CaptureProbe：MODE_IN_COMMUNICATION + setCommunicationDevice + 偏好设备。
 * 没有照搬的：探针允许 AudioSource.MIC 与“只设偏好设备”两种可选路径、拿输出设备当输入的偏好设备（这里只用输入设备），
 * 且日志带设备地址尾号（这里不记设备标识）。
 *
 * 设备地址只在内部用来把“输入设备”对上“同一个耳机的通信设备”，不外传、不记录。
 * 在真机上的行为（SCO 建立耗时、音频模式的副作用、不同耳机的差异）没有验证过：见 execution/acceptance/android-capture/README.md。
 */
@SuppressLint("MissingPermission") // RECORD_AUDIO 与 BLUETOOTH_CONNECT 由 CaptureEnvironment 在开始之前检查
class AndroidAudioSystem(context: Context) : AudioSystem {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var modeBefore: Int? = null

    override fun bluetoothInputs(): List<BluetoothInput> =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).mapNotNull { BluetoothInput.from(it.toInputDevice()) }

    @Synchronized
    override fun requestCommunicationRoute(input: BluetoothInput): Boolean {
        val info = inputInfo(input) ?: return false
        val target = audioManager.availableCommunicationDevices
            .firstOrNull { it.type == info.type && it.address == info.address } ?: return false
        if (modeBefore == null) modeBefore = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        return audioManager.setCommunicationDevice(target)
    }

    @Synchronized
    override fun clearCommunicationRoute() {
        audioManager.clearCommunicationDevice()
        modeBefore?.let { audioManager.mode = it }
        modeBefore = null
    }

    override fun requestAudioFocus(onLost: () -> Unit): Subscription? {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener({ change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) onLost()
            }, handler)
            .build()
        if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return null
        return Subscription { audioManager.abandonAudioFocusRequest(request) }
    }

    override fun openRecorder(input: BluetoothInput, format: PcmFormat): Recorder {
        val info = checkNotNull(inputInfo(input)) { "蓝牙输入设备已经不在了" }
        val channelMask = if (format.channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val minBytes = AudioRecord.getMinBufferSize(format.sampleRateHz, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        check(minBytes > 0) { "系统不支持这个录音格式" }
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(format.sampleRateHz)
                    .setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBytes * 2, 4096))
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED || !record.setPreferredDevice(info)) {
            record.release()
            error("录音没能指向蓝牙输入设备")
        }
        return AndroidRecorder(record, handler)
    }

    private fun inputInfo(input: BluetoothInput): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == input.device.id }

    private class AndroidRecorder(private val record: AudioRecord, private val handler: Handler) : Recorder {
        private var released = false

        override fun startRecording() = record.startRecording()

        override fun read(buffer: ShortArray): Int = record.read(buffer, 0, buffer.size)

        override fun routedDevice(): InputDevice? = record.routedDevice?.toInputDevice()

        override fun onRoutingChanged(callback: () -> Unit): Subscription {
            val listener = AudioRouting.OnRoutingChangedListener { callback() }
            record.addOnRoutingChangedListener(listener, handler)
            return Subscription { record.removeOnRoutingChangedListener(listener) }
        }

        @Synchronized
        override fun stopAndRelease() {
            if (released) return
            released = true
            runCatching { if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() }
            runCatching { record.release() }
        }
    }
}

internal fun AudioDeviceInfo.toInputDevice() = InputDevice(id, type, productName?.toString())
