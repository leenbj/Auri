package app.auri.capture.coordinator

import app.auri.capture.adapter.PcmFormat

internal data class SpeechPiece(val startSample: Long, val samples: ShortArray)

/** 10 ms analysis windows, 200 ms pre-roll and 300 ms post-roll. */
internal class SpeechFilter(format: PcmFormat, private val vad: VoiceActivityDetector) {
    private val window = ShortArray(format.sampleRateHz / 100 * format.channels)
    private val preRoll = ArrayDeque<SpeechPiece>()
    private var buffered = 0
    private var processed = 0L
    private var postRoll = 0
    var receivedSamples = 0L
        private set

    fun offer(samples: ShortArray): List<SpeechPiece> {
        receivedSamples += samples.size
        val output = mutableListOf<SpeechPiece>()
        var offset = 0
        while (offset < samples.size) {
            val count = minOf(window.size - buffered, samples.size - offset)
            samples.copyInto(window, buffered, offset, offset + count)
            buffered += count
            offset += count
            if (buffered == window.size) {
                process(window.copyOf(), output)
                buffered = 0
            }
        }
        return output
    }

    fun finish(): List<SpeechPiece> {
        if (buffered == 0) return emptyList()
        val output = mutableListOf<SpeechPiece>()
        process(window.copyOf(buffered), output)
        buffered = 0
        return output
    }

    private fun process(samples: ShortArray, output: MutableList<SpeechPiece>) {
        val piece = SpeechPiece(processed, samples)
        processed += samples.size
        when {
            vad.isSpeech(samples) -> {
                while (preRoll.isNotEmpty()) emit(preRoll.removeFirst(), output)
                emit(piece, output)
                postRoll = 30
            }
            postRoll > 0 -> {
                emit(piece, output)
                postRoll -= 1
            }
            else -> {
                preRoll.addLast(piece)
                if (preRoll.size > 20) preRoll.removeFirst()
            }
        }
    }

    private fun emit(piece: SpeechPiece, output: MutableList<SpeechPiece>) {
        val previous = output.lastOrNull()
        if (previous != null && previous.startSample + previous.samples.size == piece.startSample) {
            output[output.lastIndex] = SpeechPiece(previous.startSample, previous.samples + piece.samples)
        } else {
            output += piece
        }
    }
}
