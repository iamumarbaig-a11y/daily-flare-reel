package com.thedailyflare.reel

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File

/** Decodes selected music and re-encodes it as AAC so MP4 muxing is reliable. */
class AudioTranscoder(private val context: Context) {
    companion object { private const val MAX_US = 18_000_000L }

    fun transcode(uri: Uri, output: File): Boolean {
        return try {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)
            try {
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: return false
                extractor.selectTrack(track)
                val inputFormat = extractor.getTrackFormat(track)
                val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return false
                val decoder = MediaCodec.createDecoderByType(mime)
                decoder.configure(inputFormat, null, null, 0)
                decoder.start()

                val pcm = ByteArrayOutputStream()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

                try {
                    while (!outputDone) {
                        if (!inputDone) {
                            val index = decoder.dequeueInputBuffer(10_000)
                            if (index >= 0) {
                                val input = decoder.getInputBuffer(index) ?: return false
                                input.clear()
                                val pts = extractor.sampleTime
                                if (pts < 0L || pts >= MAX_US) {
                                    decoder.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    val size = extractor.readSampleData(input, 0)
                                    if (size < 0) {
                                        decoder.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                        inputDone = true
                                    } else {
                                        decoder.queueInputBuffer(index, 0, size, pts, 0)
                                        extractor.advance()
                                    }
                                }
                            }
                        }

                        when (val index = decoder.dequeueOutputBuffer(info, 10_000)) {
                            MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val format = decoder.outputFormat
                                if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }
                            else -> if (index >= 0) {
                                val buffer = decoder.getOutputBuffer(index)
                                val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                                if (buffer != null && info.size > 0) {
                                    buffer.position(info.offset)
                                    buffer.limit(info.offset + info.size)
                                    val temp = ByteArray(info.size)
                                    buffer.get(temp)
                                    pcm.write(temp)
                                }
                                decoder.releaseOutputBuffer(index, false)
                                if (eos) outputDone = true
                            }
                        }
                    }
                } finally {
                    try { decoder.stop() } catch (_: Exception) { }
                    decoder.release()
                }

                if (pcm.size() == 0) return false
                encodePcmToAac(pcm.toByteArray(), sampleRate, channels, output)
            } finally {
                extractor.release()
            }
        } catch (_: Exception) { false }
    }

    private fun encodePcmToAac(pcm: ByteArray, sampleRate: Int, channels: Int, output: File): Boolean {
        if (sampleRate <= 0 || channels <= 0) return false
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, 2)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var track = -1
        var inputOffset = 0
        var inputEos = false
        var outputEos = false
        var wrote = false
        val info = MediaCodec.BufferInfo()

        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            while (!outputEos) {
                if (!inputEos) {
                    val index = encoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val input = encoder.getInputBuffer(index) ?: return false
                        input.clear()
                        val remaining = pcm.size - inputOffset
                        if (remaining <= 0) {
                            encoder.queueInputBuffer(index, 0, 0, MAX_US, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            val count = minOf(remaining, input.capacity())
                            input.put(pcm, inputOffset, count)
                            val bytesPerSecond = sampleRate.toLong() * channels * 2L
                            val pts = (inputOffset.toLong() * 1_000_000L) / bytesPerSecond
                            encoder.queueInputBuffer(index, 0, count, pts, 0)
                            inputOffset += count
                        }
                    }
                }

                when (val index = encoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) return false
                        track = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    else -> if (index >= 0) {
                        val encoded = encoder.getOutputBuffer(index)
                        val config = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (encoded != null && info.size > 0 && muxerStarted && !config) {
                            encoded.position(info.offset)
                            encoded.limit(info.offset + info.size)
                            muxer.writeSampleData(track, encoded, info)
                            wrote = true
                        }
                        outputEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        encoder.releaseOutputBuffer(index, false)
                    }
                }
            }
            if (muxerStarted && wrote) muxer.stop()
            return wrote
        } finally {
            if (muxerStarted && !wrote) try { muxer.stop() } catch (_: Exception) { }
            muxer.release()
            try { encoder.stop() } catch (_: Exception) { }
            encoder.release()
        }
    }
}
