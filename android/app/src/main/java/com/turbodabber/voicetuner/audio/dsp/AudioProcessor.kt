package com.turbodabber.voicetuner.audio.dsp

/** In-place mono float PCM. Implementations are confined to the audio thread. */
interface AudioProcessor {
    fun process(samples: FloatArray, count: Int, amount: Float)
}

/** Explicit bypass until a pitch detector + pitch shifter are implemented.
 * Do not claim pitch correction just because the UI supplies an amount.
 */
class PitchCorrectionPlaceholder : AudioProcessor {
    override fun process(samples: FloatArray, count: Int, amount: Float) = Unit
}
