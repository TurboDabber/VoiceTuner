package com.turbodabber.voicetuner

import android.app.Instrumentation
import android.app.Activity
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import androidx.core.content.FileProvider
import com.turbodabber.voicetuner.audio.AacRecording
import com.turbodabber.voicetuner.audio.EffectSettings
import com.turbodabber.voicetuner.audio.dsp.DspPipeline
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.sin

/** Device smoke test uses a synthetic tone; never opens the microphone or sends messages. */
class RecordingSmokeTest : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        val file = File(targetContext.filesDir, "recordings/test/codec-smoke.m4a")
        val extractor = MediaExtractor()
        try {
            val dsp = DspPipeline(48_000)
            val block = FloatArray(256)
            AacRecording(file, 48_000) { targetContext.getString(it) }.use { writer ->
                repeat(375) { frame ->
                    for (i in block.indices) block[i] = if (frame < 188)
                        (0.3 * sin(2 * Math.PI * 451 * (frame * block.size + i) / 48_000)).toFloat() else 0f
                    dsp.process(block, block.size, EffectSettings(autotune = 1f, reverb = 0.7f))
                    writer.write(block, block.size)
                }
                writer.finish()
            }
            check(file.length() > 1000)
            val uri = FileProvider.getUriForFile(targetContext, "${targetContext.packageName}.recordings", file)
            targetContext.contentResolver.openAssetFileDescriptor(uri, "r")!!.use {
                extractor.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
            check(extractor.trackCount == 1)
            val format = extractor.getTrackFormat(0)
            check(format.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm")
            check(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) == 48_000)
            check(format.getLong(MediaFormat.KEY_DURATION) in 1_900_000..2_200_000)
            extractor.selectTrack(0)
            val buffer = ByteBuffer.allocate(8192)
            var packets = 0
            var lastTime = -1L
            while (extractor.readSampleData(buffer, 0) >= 0) {
                check(extractor.sampleTime >= lastTime)
                lastTime = extractor.sampleTime
                packets++
                extractor.advance()
            }
            check(packets > 80)
            result.putString("stream", "PASS: DSP → AAC/M4A, duration, packets, timestamps and FileProvider URI ($packets packets).\n")
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        } finally { extractor.release(); file.delete() }
    }
}
