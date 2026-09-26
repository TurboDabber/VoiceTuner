package com.turbodabber.voicetuner.audio.dsp

/** In-place mono float PCM. Implementations are confined to the audio thread. */
interface AudioProcessor {
    fun process(samples: FloatArray, count: Int, amount: Float)
}
