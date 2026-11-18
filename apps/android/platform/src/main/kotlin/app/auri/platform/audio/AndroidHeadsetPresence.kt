package app.auri.platform.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 系统音频设备增减时刷新“耳机已连接”。 */
class AndroidHeadsetPresence(context: Context) : HeadsetPresence {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val state = MutableStateFlow(read())

    override val status: StateFlow<HeadsetStatus> = state

    init {
        audioManager.registerAudioDeviceCallback(
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    state.value = read()
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    state.value = read()
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }

    private fun read() = headsetStatusOf(audioManager.getDevices(AudioManager.GET_DEVICES_ALL).map { it.toInputDevice() })
}
