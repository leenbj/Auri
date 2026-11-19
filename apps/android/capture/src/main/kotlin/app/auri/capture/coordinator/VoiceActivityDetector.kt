package app.auri.capture.coordinator

/** Examines only frames from an already verified route. */
fun interface VoiceActivityDetector {
    fun isSpeech(samples: ShortArray): Boolean
}

/** shortcut: threshold is uncalibrated, tune it using real-headset E3 recordings. */
class EnergyVoiceActivityDetector(private val threshold: Int = 300) : VoiceActivityDetector {
    override fun isSpeech(samples: ShortArray): Boolean =
        samples.isNotEmpty() && samples.sumOf { kotlin.math.abs(it.toInt()).toLong() } >= threshold.toLong() * samples.size
}
