package com.turbodabber.voicetuner.audio.dsp

import com.turbodabber.voicetuner.audio.EffectSettings

class DspPipeline(sampleRate: Int) {
    private val pitch: AudioProcessor = PitchCorrectionPlaceholder()
    private val reverb: AudioProcessor = ReverbProcessor(sampleRate)
    fun process(samples: FloatArray, count: Int, settings: EffectSettings) {
        require(count in 0..samples.size)
        for (i in 0 until count) if (!samples[i].isFinite()) samples[i] = 0f
        pitch.process(samples, count, settings.autotune)
        reverb.process(samples, count, settings.reverb)
        for (i in 0 until count) samples[i] = samples[i].coerceIn(-1f, 1f)
    }
}
