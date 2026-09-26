package com.turbodabber.voicetuner.audio.dsp

import kotlin.math.*

/** Monophonic scale-aware correction: YIN-style difference detector + two overlapping
 * variable-delay grains. No formant preservation; intended for voice, not polyphonic audio.
 * All buffers are reused on the audio thread. At zero strength output is exact bypass.
 */
class PitchCorrectionProcessor(private val sampleRate: Int) : AudioProcessor {
    init { require(sampleRate >= 8000) }
    private val decimation = (sampleRate / 12000).coerceAtLeast(1)
    private val analysisRate = sampleRate.toDouble() / decimation
    private val maxLag = (analysisRate / 65).toInt()
    private val minLag = (analysisRate / 1000).toInt().coerceAtLeast(2)
    private val window = maxLag * 2
    private val history = FloatArray(window + maxLag + 2)
    private val frame = FloatArray(history.size)
    private val difference = DoubleArray(maxLag + 1)
    private var historyIndex = 0
    private var historyCount = 0
    private var analysisHop = 0
    private var decimationCount = 0
    private var sum = 0f
    private val grainLength = sampleRate * 0.04
    private val delay = FloatArray(ceil(grainLength).toInt() + 8)
    private var writeIndex = 0
    private var phase = 0.25
    private var ratio = 1.0
    private var correctionSemitones = 0.0
    private var voiced = false
    private var wet = 0.0
    private var root = 0
    private var scale = TuningScale.CHROMATIC
    private var hardTune = false
    private var previousNote: Int? = null
    private val crossfade = FloatArray(2049) { (0.5 - 0.5 * cos(2 * PI * it / 2048)).toFloat() }

    fun configure(rootNote: Int, tuningScale: TuningScale, hard: Boolean) {
        val normalizedRoot = Math.floorMod(rootNote, 12)
        if (normalizedRoot != root || tuningScale != scale || hardTune != hard) {
            previousNote = null
            analysisHop = Int.MAX_VALUE / 2 // Apply new tuning on the next decimated sample.
        }
        root = normalizedRoot
        scale = tuningScale
        hardTune = hard
    }

    override fun process(samples: FloatArray, count: Int, amount: Float) {
        require(count in 0..samples.size)
        val strength = if (amount.isFinite()) amount.coerceIn(0f, 1f).toDouble() else 0.0
        val responseSeconds = if (hardTune) 0.001 + (1.0 - strength) * 0.019 else 0.025 + (1.0 - strength) * 0.1
        val ratioSmoothing = 1.0 - exp(-1.0 / (sampleRate * responseSeconds))
        val mixSmoothing = 1.0 - exp(-1.0 / (sampleRate * if (hardTune) 0.003 else 0.01))
        for (i in 0 until count) {
            val input = samples[i]
            sum += input
            if (++decimationCount == decimation) {
                history[historyIndex] = sum / decimation
                historyIndex = (historyIndex + 1) % history.size
                historyCount = (historyCount + 1).coerceAtMost(history.size)
                sum = 0f
                decimationCount = 0
                if (++analysisHop >= (analysisRate * if (hardTune) 0.005 else 0.01).toInt()) {
                    analysisHop = 0
                    if (historyCount == history.size) detect()
                }
            }
            delay[writeIndex] = input
            val targetRatio = if (voiced) 2.0.pow(correctionSemitones * strength / 12.0) else 1.0
            ratio += (targetRatio - ratio) * ratioSmoothing
            wet += ((if (voiced && strength > 0) 1.0 else 0.0) - wet) * mixSmoothing
            phase += (1.0 - ratio) / grainLength
            phase -= floor(phase)
            val secondPhase = (phase + 0.5) % 1.0
            // Smooth raised-cosine grain edges instead of triangular crossfades.
            val lookup = phase * 2048
            val index = lookup.toInt()
            val weight = crossfade[index] + (crossfade[index + 1] - crossfade[index]) * (lookup - index)
            val shifted = readDelay(4 + phase * grainLength) * weight +
                readDelay(4 + secondPhase * grainLength) * (1.0 - weight)
            val dry = readDelay(4 + grainLength * 0.5)
            samples[i] = if (strength == 0.0) input else (dry * (1.0 - wet) + shifted * wet).toFloat()
            writeIndex = (writeIndex + 1) % delay.size
        }
    }

    private fun readDelay(distance: Double): Float {
        var position = writeIndex - distance
        if (position < 0) position += delay.size
        val left = position.toInt()
        val fraction = (position - left).toFloat()
        return delay[left] * (1f - fraction) + delay[(left + 1) % delay.size] * fraction
    }

    private fun detect() {
        var energy = 0.0
        for (i in frame.indices) {
            frame[i] = history[(historyIndex + i) % history.size]
            energy += frame[i] * frame[i]
        }
        voiced = false
        if (energy / frame.size < 0.00001) { previousNote = null; return }
        var accumulated = 0.0
        difference[0] = 1.0
        for (lag in 1..maxLag) {
            var value = 0.0
            for (i in 0 until window) {
                val delta = (frame[i] - frame[i + lag]).toDouble()
                value += delta * delta
            }
            accumulated += value
            difference[lag] = if (accumulated > 0) value * lag / accumulated else 1.0
        }
        var lag = minLag
        while (lag < maxLag - 1) {
            if (difference[lag] < 0.12) {
                while (lag + 1 < maxLag && difference[lag + 1] < difference[lag]) lag++
                if (lag >= maxLag) return
                val left = difference[lag - 1]
                val center = difference[lag]
                val right = difference[lag + 1]
                val denominator = left - 2 * center + right
                val offset = if (abs(denominator) > 1e-12) (0.5 * (left - right) / denominator).coerceIn(-0.5, 0.5) else 0.0
                val frequency = analysisRate / (lag + offset)
                val midi = 69 + 12 * log2(frequency / 440.0)
                var target = scale.nearestNote(midi, root)
                // Small hysteresis avoids random note flipping near a scale boundary.
                previousNote?.let { previous ->
                    if (abs(midi - previous) <= abs(midi - target) + 0.08) target = previous
                }
                previousNote = target
                correctionSemitones = (target - midi).coerceIn(-3.0, 3.0)
                voiced = true
                return
            }
            lag++
        }
    }
}
