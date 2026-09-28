package com.turbodabber.voicetuner.audio

import com.turbodabber.voicetuner.R
import android.annotation.SuppressLint
import android.media.*
import android.os.Build
import android.os.Process
import com.turbodabber.voicetuner.audio.dsp.DspPipeline
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread
import java.io.File

/** Single worker owns AudioRecord/AudioTrack and releases both in finally.
 * Non-blocking I/O keeps STOP bounded even when a device stops delivering samples.
 */
class AudioEngine(private val text: (Int) -> String,
                  private val recordingFile: File? = null,
                  private val onStarted: () -> Unit, private val onFinished: (String?, File?) -> Unit) {
    private val running = AtomicBoolean(false)
    fun stop() { running.set(false) }

    @SuppressLint("MissingPermission") // Service checks RECORD_AUDIO before starting.
    fun start() {
        check(running.compareAndSet(false, true))
        thread(name = "VoiceTuner-Audio") {
            var recorder: AudioRecord? = null
            var player: AudioTrack? = null
            var failure: String? = null
            var recording: AacRecording? = null
            var result: File? = null
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                val sampleRate = 48_000
                val encoding = AudioFormat.ENCODING_PCM_FLOAT
                val inputMin = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, encoding)
                val outputMin = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, encoding)
                check(inputMin > 0 && outputMin > 0) { text(R.string.unsupported_audio) }
                recorder = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(encoding)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(maxOf(inputMin * 2, 4096)).build()
                if (recordingFile != null) recording = AacRecording(recordingFile, sampleRate, text)
                else player = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(encoding)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setBufferSizeInBytes(maxOf(outputMin * 2, 4096)).build()
                check(recorder.state == AudioRecord.STATE_INITIALIZED && (player == null || player.state == AudioTrack.STATE_INITIALIZED)) {
                    text(R.string.audio_init_failed)
                }
                val pipeline = DspPipeline(sampleRate)
                val block = FloatArray(256)
                player?.setVolume(0.5f)
                var capturedSamples = 0L
                if (running.get()) {
                    recorder.startRecording()
                    check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { text(R.string.mic_unavailable) }
                    player?.play()
                    onStarted()
                }
                while (running.get()) {
                    if (Build.VERSION.SDK_INT >= 29 && recorder.activeRecordingConfiguration?.isClientSilenced == true) {
                        error(text(R.string.mic_silenced))
                    }
                    val count = recorder.read(block, 0, block.size, AudioRecord.READ_NON_BLOCKING)
                    check(count >= 0) { text(R.string.read_failed) }
                    if (count == 0) { LockSupport.parkNanos(2_000_000); continue }
                    pipeline.process(block, count, AudioSession.effects)
                    recording?.write(block, count)
                    capturedSamples += count
                    if (recording != null && capturedSamples >= sampleRate * 600L) running.set(false)
                    var offset = 0
                    while (player != null && offset < count && running.get()) {
                        val written = player.write(block, offset, count - offset, AudioTrack.WRITE_NON_BLOCKING)
                        check(written >= 0) { text(R.string.write_failed) }
                        offset += written
                        if (written == 0) LockSupport.parkNanos(2_000_000)
                    }
                }
                // Release microphone immediately; finish encoding and reverb tail without capture.
                recorder.stop()
                if (recording != null && capturedSamples > 0) {
                    val settings = AudioSession.effects
                    var tailSamples = (sampleRate * 6 * settings.reverb).toInt()
                    while (tailSamples > 0) {
                        block.fill(0f)
                        val count = minOf(block.size, tailSamples)
                        pipeline.process(block, count, settings)
                        recording.write(block, count)
                        tailSamples -= count
                    }
                    result = recording.finish()
                } else if (recording != null) error(text(R.string.empty_recording))
            } catch (e: Exception) {
                failure = e.message ?: text(R.string.audio_failed)
            } finally {
                running.set(false)
                recorder?.let { runCatching { it.stop() }; it.release() }
                player?.let { runCatching { it.pause(); it.flush(); it.stop() }; it.release() }
                recording?.close()
                onFinished(failure, result)
            }
        }
    }
}
