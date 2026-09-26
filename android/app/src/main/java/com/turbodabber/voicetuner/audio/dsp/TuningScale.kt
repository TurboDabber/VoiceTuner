package com.turbodabber.voicetuner.audio.dsp

import kotlin.math.abs
import kotlin.math.floor

enum class TuningScale(val label: String, private val notes: IntArray) {
    CHROMATIC("Chromatyczna", intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)),
    MINOR("Molowa", intArrayOf(0, 2, 3, 5, 7, 8, 10)),
    MAJOR("Durowa", intArrayOf(0, 2, 4, 5, 7, 9, 11)),
    MINOR_PENTATONIC("Pentatonika molowa", intArrayOf(0, 3, 5, 7, 10));

    fun nearestNote(midi: Double, root: Int): Int {
        require(midi.isFinite())
        val octave = floor(midi / 12).toInt()
        var best = 0
        var distance = Double.POSITIVE_INFINITY
        for (oct in octave - 1..octave + 1) for (note in notes) {
            val candidate = oct * 12 + Math.floorMod(root, 12) + note
            val candidateDistance = abs(candidate - midi)
            if (candidateDistance < distance) { best = candidate; distance = candidateDistance }
        }
        return best
    }
}
