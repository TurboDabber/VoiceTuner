package com.turbodabber.voicetuner.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.File
import java.nio.ByteOrder

/** Worker-confined streaming AAC-LC encoder. Only finalized M4A files are published. */
class AacRecording(private val destination: File, private val sampleRate: Int) : AutoCloseable {
    private val pending = File(destination.parentFile, destination.name + ".partial")
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var muxerStarted = false
    private var ended = false
    private var committed = false
    private var samplesWritten = 0L
    private val info = MediaCodec.BufferInfo()

    init {
        try {
            destination.parentFile?.mkdirs()
            muxer = MediaMuxer(pending.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec = MediaCodec.createEncoderByType("audio/mp4a-latm")
            codec!!.let {
                val format = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                }
                it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                it.start()
            }
        } catch (e: Exception) { close(); throw e }
    }

    fun write(samples: FloatArray, count: Int) {
        val encoder = checkNotNull(codec)
        var offset = 0
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (offset < count) {
            drain()
            val index = encoder.dequeueInputBuffer(10_000)
            check(SystemClock.elapsedRealtime() < deadline) { "Koder audio przestał odpowiadać." }
            if (index < 0) continue
            val input = checkNotNull(encoder.getInputBuffer(index)).apply { clear(); order(ByteOrder.nativeOrder()) }
            val length = minOf(count - offset, input.remaining() / 2)
            check(length > 0)
            repeat(length) { input.putShort((samples[offset + it].coerceIn(-1f, 1f) * 32767f).toInt().toShort()) }
            encoder.queueInputBuffer(index, 0, length * 2, samplesWritten * 1_000_000 / sampleRate, 0)
            samplesWritten += length
            offset += length
        }
        drain()
    }

    private fun drain() {
        val encoder = checkNotNull(codec)
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted)
                    track = checkNotNull(muxer).addTrack(encoder.outputFormat)
                    muxer!!.start()
                    muxerStarted = true
                }
                index >= 0 -> {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(muxerStarted)
                            val buffer = checkNotNull(encoder.getOutputBuffer(index))
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer!!.writeSampleData(track, buffer, info)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) ended = true
                    } finally { encoder.releaseOutputBuffer(index, false) }
                }
                else -> return
            }
        }
    }

    fun finish(): File {
        check(samplesWritten > 0) { "Nagranie jest puste. Nagraj głos i spróbuj ponownie." }
        val encoder = checkNotNull(codec)
        val deadline = SystemClock.elapsedRealtime() + 5000
        var queued = false
        while (!ended) {
            check(SystemClock.elapsedRealtime() < deadline) { "Nie udało się zakończyć zapisu nagrania." }
            if (!queued) {
                val index = encoder.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    encoder.queueInputBuffer(index, 0, 0, samplesWritten * 1_000_000 / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    queued = true
                }
            }
            drain()
            if (!ended) Thread.sleep(2)
        }
        check(muxerStarted)
        muxer!!.stop()
        muxerStarted = false
        muxer!!.release()
        muxer = null
        check(pending.renameTo(destination)) { "Nie udało się zapisać pliku nagrania." }
        committed = true
        return destination
    }

    override fun close() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
        muxer?.let { if (muxerStarted) runCatching { it.stop() }; runCatching { it.release() } }
        muxer = null
        if (!committed) pending.delete()
    }
}
