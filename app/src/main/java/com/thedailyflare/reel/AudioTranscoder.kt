package com.thedailyflare.reel

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/** Converts selected music to AAC-in-MP4 so it can be muxed reliably on Android. */
class AudioTranscoder(private val context: Context) {
    companion object { private const val MAX_US = 18_000_000L }

    fun transcode(uri: Uri, output: File): Boolean {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return false
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) return copyAac(extractor, inputFormat, output)
            return transcodeWithCodecs(extractor, inputFormat, mime, output)
        } finally {
            try { extractor.release() } catch (_: Exception) { }
        }
    }

    private fun copyAac(extractor: MediaExtractor, format: MediaFormat, output: File): Boolean {
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        return try {
            val track = muxer.addTrack(format)
            muxer.start()
            val buffer = ByteBuffer.allocateDirect(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val pts = extractor.sampleTime
                if (pts < 0L || pts >= MAX_US) break
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, pts, extractor.sampleFlags)
                muxer.writeSampleData(track, buffer, info)
                extractor.advance()
            }
            muxer.stop()
            true
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
        var decoderEos = false
        var encoderEos = false
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
                                decoder.queueInputBuffer(inIndex, 0, size, pts, extractor.sampleFlags)
                                extractor.advance()
                            }
                        }
                    }
                }

                val decOut = decoder.dequeueOutputBuffer(decoderInfo, 10_000)
                if (decOut >= 0) {
                    val out = decoder.getOutputBuffer(decOut)
                    val encoderIn = encoder.dequeueInputBuffer(10_000)
                    if (encoderIn >= 0) {
                        val encInput = encoder.getInputBuffer(encoderIn) ?: throw IllegalStateException("No encoder input buffer")
                        encInput.clear()
                        if (out != null && decoderInfo.size > 0) {
                            out.position(decoderInfo.offset)
                            out.limit(decoderInfo.offset + decoderInfo.size)
                            encInput.put(out)
                        }
                        val flags = if ((decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        encoder.queueInputBuffer(encoderIn, 0, encInput.position(), decoderInfo.presentationTimeUs, flags)
                        decoderEos = flags != 0
                    }
                    decoder.releaseOutputBuffer(decOut, false)
                }

                while (true) {
                    val encOut = encoder.dequeueOutputBuffer(encoderInfo, 0)
                    when {
                        encOut == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        encOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (muxerStarted) throw IllegalStateException("Audio output format changed twice")
                            audioTrack = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        encOut >= 0 -> {
                            val encoded = encoder.getOutputBuffer(encOut)
                            if (encoded != null && encoderInfo.size > 0 && muxerStarted && encoderInfo.presentationTimeUs < MAX_US) {
                                encoded.position(encoderInfo.offset)
                                encoded.limit(encoderInfo.offset + encoderInfo.size)
                                muxer.writeSampleData(audioTrack, encoded, encoderInfo)
                            }
                            encoderEos = (encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            encoder.releaseOutputBuffer(encOut, false)
                        }
                    }
                    if (encoderEos) break
                }
            }
            if (muxerStarted) muxer.stop()
        } finally {
            muxer.release()
            try { decoder.stop() } catch (_: Exception) { }
            try { decoder.release() } catch (_: Exception) { }
            try { encoder.stop() } catch (_: Exception) { }
            try { encoder.release() } catch (_: Exception) { }
        }
        return true
    }
}
