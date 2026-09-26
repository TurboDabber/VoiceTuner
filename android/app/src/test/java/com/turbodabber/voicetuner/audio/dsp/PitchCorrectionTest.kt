package com.turbodabber.voicetuner.audio.dsp

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class PitchCorrectionTest {
    private fun tone(frequency: Double, strength: Float, scale: TuningScale = TuningScale.CHROMATIC, root: Int = 0): FloatArray {
        val processor = PitchCorrectionProcessor(48000)
        processor.configure(root, scale, true)
        val output = FloatArray(48000 * 3)
        val block = FloatArray(256)
        var offset = 0
        while (offset < output.size) {
            val count = minOf(block.size, output.size - offset)
            for (i in 0 until count) block[i] = (0.3 * sin(2 * PI * frequency * (offset + i) / 48000)).toFloat()
            processor.process(block, count, strength)
            block.copyInto(output, offset, 0, count)
            offset += count
        }
        return output
    }
    private fun peak(samples: FloatArray, low: Int, high: Int): Double {
        var best = 0.0
        var peak = 0.0
        for (step in low * 2..high * 2) {
            val frequency = step / 2.0
            var real = 0.0
            var imaginary = 0.0
            // Last second, Hann window, after detector and grains have settled.
            for (i in 0 until 48000 step 4) {
                val value = samples[samples.size - 48000 + i] * (0.5 - 0.5 * cos(2 * PI * i / 48000))
                real += value * cos(2 * PI * frequency * i / 48000)
                imaginary += value * sin(2 * PI * frequency * i / 48000)
            }
            val power = real * real + imaginary * imaginary
            if (power > best) { best = power; peak = frequency }
        }
        return peak
    }
    @Test fun correctsSharpAndFlatTonesToA() {
        assertEquals(440.0, peak(tone(451.0, 1f), 420, 470), 2.0)
        assertEquals(440.0, peak(tone(429.0, 1f), 410, 460), 2.0)
    }
    @Test fun strengthMovesPitchPartWayAndZeroPreservesInput() {
        assertEquals(451.0, peak(tone(451.0, 0f), 435, 455), 0.5)
        val half = peak(tone(451.0, 0.5f), 435, 455)
        assertTrue("Half strength peak: $half", half in 443.0..448.0)
    }
    @Test fun correctsLowVoiceAndKeepsSilenceFinite() {
        assertEquals(110.0, peak(tone(112.0, 1f), 100, 120), 1.0)
        val samples = FloatArray(48000)
        PitchCorrectionProcessor(48000).process(samples, samples.size, 1f)
        assertTrue(samples.all { it == 0f })
    }
    @Test fun selectedScaleChangesActualOutputPitch() {
        // C is allowed in A minor; A major instead pulls this slightly sharp C to C#.
        assertEquals(261.63, peak(tone(265.0, 1f, TuningScale.MINOR, 9), 250, 285), 2.0)
        assertEquals(277.18, peak(tone(265.0, 1f, TuningScale.MAJOR, 9), 250, 285), 2.0)
    }
    @Test fun quantizerTransposesScalesAcrossOctaves() {
        assertEquals(60, TuningScale.MINOR.nearestNote(60.2, 9))
        assertEquals(61, TuningScale.MAJOR.nearestNote(60.2, 9))
        assertEquals(72, TuningScale.MINOR.nearestNote(72.2, 9))
        assertEquals(63, TuningScale.MINOR_PENTATONIC.nearestNote(62.8, 0))
        for (root in 0..11) {
            assertEquals(60 + root, TuningScale.MINOR.nearestNote(60.1 + root, root))
        }
    }
}
