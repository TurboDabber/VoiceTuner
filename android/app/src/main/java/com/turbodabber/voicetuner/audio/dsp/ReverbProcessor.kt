package com.turbodabber.voicetuner.audio.dsp

/** Four damped parallel combs and two serial all-pass diffusers.
 * Buffers are allocated once; wet mix is smoothed per sample to avoid slider clicks.
 */
class ReverbProcessor(sampleRate: Int) : AudioProcessor {
    private class Comb(size: Int) {
        private val buffer = FloatArray(size)
        private var index = 0
        private var damped = 0f
        fun tick(input: Float, feedback: Float): Float {
            val delayed = buffer[index]
            damped = delayed * 0.7f + damped * 0.3f
            buffer[index] = input + damped * feedback
            index = (index + 1) % buffer.size
            return delayed
        }
    }
    private class AllPass(size: Int) {
        private val buffer = FloatArray(size)
        private var index = 0
        fun tick(input: Float): Float {
            val delayed = buffer[index]
            val output = delayed - input * 0.5f
            buffer[index] = input + output * 0.5f
            index = (index + 1) % buffer.size
            return output
        }
    }
    init { require(sampleRate > 0) }
    private val combs = doubleArrayOf(0.0593, 0.0719, 0.0833, 0.0971)
        .map { Comb((sampleRate * it).toInt().coerceAtLeast(1)) }
    private val diffusers = doubleArrayOf(0.005, 0.0017)
        .map { AllPass((sampleRate * it).toInt().coerceAtLeast(1)) }
    private var wet = 0f
    override fun process(samples: FloatArray, count: Int, amount: Float) {
        val target = if (amount.isFinite()) amount.coerceIn(0f, 1f) else 0f
        for (i in 0 until count) {
            wet += (target - wet) * 0.002f
            val dry = samples[i]
            // At maximum: long decay and no direct voice. Feedback stays below unity.
            val feedback = 0.55f + 0.41f * wet
            var tail = 0f
            for (comb in combs) tail += comb.tick(dry * 0.2f, feedback)
            for (diffuser in diffusers) tail = diffuser.tick(tail)
            samples[i] = dry * (1f - wet) + tail * wet * 1.8f
        }
    }
}
