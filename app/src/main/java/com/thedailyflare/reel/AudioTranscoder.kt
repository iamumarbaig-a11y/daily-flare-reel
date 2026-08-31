package com.thedailyflare.reel

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

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
                if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) copyAac(extractor, inputFormat, output)
                else transcodeWithCodecs(extractor, inputFormat, mime, output)
            } finally { extractor.release() }
        } catch (_: Exception) { false }
    }

    private fun copyAac(extractor: MediaExtractor, format: MediaFormat, output: File): Boolean {
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        return try {
            val track = muxer.addTrack(format)
            muxer.start()
            val buffer = ByteBuffer.allocateDirect(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var wrote = false
            while (true) {
                val pts = extractor.sampleTime
                if (pts < 0L || pts >= MAX_US) break
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, pts, extractor.sampleFlags)
                muxer.writeSampleData(track, buffer, info)
                wrote = true
                extractor.advance()
            }
            muxer.stop()
            wrote
        } finally { muxer.release() }
    }

    private fun transcodeWithCodecs(extractor: MediaExtractor, inputFormat: MediaFormat, mime: String, output: File): Boolean {
        val decoder = MediaCodec.createDecoderByType(mime)
        val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val encoderFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, 2)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        decoder.configure(inputFormat, null, null, 0)
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        decoder.start(); encoder.start()

        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var audioTrack = -1
        var muxerStarted = false
        var extractorEos = false
        var decoderEosQueued = false
        var encoderEos = false
        var wroteSamples = false
        val decoderInfo = MediaCodec.BufferInfo()
        val encoderInfo = MediaCodec.BufferInfo()

        try {
            while (!encoderEos) {
                if (!extractorEos) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val pts = extractor.sampleTime
                        if (pts < 0L || pts >= MAX_US) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            extractorEos = true
                        } else {
                            val input = decoder.getInputBuffer(inIndex) ?: throw IllegalStateException("No decoder input buffer")
                            input.clear()
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                extractorEos = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, size, pts, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val decOut = decoder.dequeueOutputBuffer(decoderInfo, 10_000)
                if (decOut >= 0) {
                    val out = decoder.getOutputBuffer(decOut)
                    val eos = (decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (decoderInfo.size > 0 && out != null) {
                        out.position(decoderInfo.offset)
                        out.limit(decoderInfo.offset + decoderInfo.size)
                        while (out.hasRemaining()) {
                            val encoderIn = encoder.dequeueInputBuffer(10_000)
                            if (encoderIn < 0) continue
                            val encInput = encoder.getInputBuffer(encoderIn) ?: break
                            val chunk = minOf(out.remaining(), encInput.capacity())
                            encInput.clear()
                            val oldLimit = out.limit()
                            out.limit(out.position() + chunk)
                            encInput.put(out)
                            out.limit(oldLimit)
                            val pcmBytesPerSecond = sampleRate.toLong() * channels * 2L
                            val chunkPts = decoderInfo.presentationTimeUs
                            encoder.queueInputBuffer(encoderIn, 0, chunk, chunkPts, 0)
                        }
                    }
                    if (eos && !decoderEosQueued) {
                        val encoderIn = encoder.dequeueInputBuffer(10_000)
                        if (encoderIn >= 0) {
                            encoder.queueInputBuffer(encoderIn, 0, 0, decoderInfo.presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            decoderEosQueued = true
                        }
                    }
                    decoder.releaseOutputBuffer(decOut, false)
                }

                while (true) {
                    val encOut = encoder.dequeueOutputBuffer(encoderInfo, 0)
                    when {
                        encOut == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        encOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            audioTrack = muxer.addTrack(encoder.outputFormat)
                            muxer.start(); muxerStarted = true
                        }
                        encOut >= 0 -> {
                            val encoded = encoder.getOutputBuffer(encOut)
                            val codecConfig = (encoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            if (encoded != null && encoderInfo.size > 0 && muxerStarted && !codecConfig && encoderInfo.presentationTimeUs < MAX_US) {
                                encoded.position(encoderInfo.offset)
                                encoded.limit(encoderInfo.offset + encoderInfo.size)
                                muxer.writeSampleData(audioTrack, encoded, encoderInfo)
                                wroteSamples = true
                            }
                            encoderEos = (encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            encoder.releaseOutputBuffer(encOut, false)
                        }
                    }
                    if (encoderEos) break
                }
            }
            if (muxerStarted && wroteSamples) muxer.stop()
            return wroteSamples
        } finally {
            if (muxerStarted && !wroteSamples) try { muxer.stop() } catch (_: Exception) { }
            muxer.release()
            try { decoder.stop() } catch (_: Exception) { }
            try { decoder.release() } catch (_: Exception) { }
            try { encoder.stop() } catch (_: Exception) { }
            try { encoder.release() } catch (_: Exception) { }
        }
    }
}
