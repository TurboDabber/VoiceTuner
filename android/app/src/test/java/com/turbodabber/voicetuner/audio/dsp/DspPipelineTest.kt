package com.turbodabber.voicetuner.audio.dsp

import com.turbodabber.voicetuner.audio.EffectSettings
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class DspPipelineTest {
    @Test fun drySignalIsUnchanged() {
        val samples = floatArrayOf(-0.5f, 0f, 0.4f, 0.1f)
        val expected = samples.copyOf()
        DspPipeline(48_000).process(samples, samples.size, EffectSettings(0f, 0f))
        assertArrayEquals(expected, samples, 0f)
    }
    @Test fun impulseProducesFiniteDecayingTailAcrossBlocks() {
        val pipeline = DspPipeline(48_000)
        val block = FloatArray(256)
        var earlyTail = 0.0
        var lateTail = 0.0
        repeat(4000) { frame ->
            block.fill(0f)
            if (frame == 0) block[0] = 1f
            pipeline.process(block, block.size, EffectSettings(reverb = 1f))
            for (sample in block) {
                assertTrue(sample.isFinite() && abs(sample) <= 1f)
                if (frame in 5..100) earlyTail += abs(sample)
                if (frame > 3900) lateTail += abs(sample)
            }
        }
        assertTrue(earlyTail > 0.01)
        assertTrue(lateTail < earlyTail * 0.001)
    }
    @Test fun invalidSamplesAreSanitizedAndOutputIsBounded() {
        val samples = floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, -4f, 4f)
        DspPipeline(48_000).process(samples, samples.size, EffectSettings(reverb = 0f))
        assertArrayEquals(floatArrayOf(0f, 0f, -1f, 1f), samples, 0f)
    }
    @Test fun partialBlockLeavesUnusedSamplesAlone() {
        val samples = floatArrayOf(0.3f, 0.4f, 7f)
        DspPipeline(48_000).process(samples, 2, EffectSettings(reverb = 0f))
        assertEquals(7f, samples[2], 0f)
    }
    @Test fun pitchStageExplicitlyRemainsBypass() {
        val samples = floatArrayOf(0.2f, -0.4f)
        val expected = samples.copyOf()
        DspPipeline(48_000).process(samples, 2, EffectSettings(1f, 0f))
        assertArrayEquals(expected, samples, 0f)
    }

    @Test fun maximumReverbRemovesDirectVoiceAndExtendsTail() {
        fun tailEnergy(amount: Float): Double {
            val pipeline = DspPipeline(48_000)
            val block = FloatArray(256)
            val settings = EffectSettings(reverb = amount)
            repeat(50) { pipeline.process(block, block.size, settings) }
            block[0] = 1f
            pipeline.process(block, block.size, settings)
            if (amount == 1f) assertTrue("Maximum must be wet-only after smoothing", abs(block[0]) < 0.001f)
            var energy = 0.0
            repeat(1000) { frame ->
                block.fill(0f)
                pipeline.process(block, block.size, settings)
                if (frame > 400) for (sample in block) energy += sample * sample
            }
            return energy
        }
        assertTrue(tailEnergy(1f) > tailEnergy(0.25f) * 100 + 0.00001)
    }
}
