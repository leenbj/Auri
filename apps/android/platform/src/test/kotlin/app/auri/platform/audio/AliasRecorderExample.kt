package app.auri.platform.audio

import android.media.AudioRecord as HiddenRecorder
import android.media.MediaRecorder as HiddenMediaRecorder

/** Negative example for the bytecode privacy guard; never part of production. */
internal class AliasRecorderExample(private val recorder: HiddenRecorder)
internal class AliasMediaRecorderExample(private val recorder: HiddenMediaRecorder) {
    fun selectMicrophone() = recorder.setAudioSource(HiddenMediaRecorder.AudioSource.MIC)
}
