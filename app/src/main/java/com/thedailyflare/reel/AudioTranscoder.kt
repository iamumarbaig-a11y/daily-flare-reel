package com.thedailyflare.reel

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.floor

/** Decodes selected music and re-encodes it as AAC so MP4 muxing is reliable. */
class AudioTranscoder(private val context: Context) {
    companion object {
        private const val MUSIC_VOLUME = 0.10f
    }

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
                                if (pts < 0L) {
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
                                    scalePcm16(temp, MUSIC_VOLUME)
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

    /**
     * Mixes the generated TTS voice with the selected background music.
     * Music stays at 20%; voice is kept at full level and starts at 0 seconds.
     */
    @Volatile var lastError: String? = null
        private set

    fun transcodeMixed(musicUri: Uri, voiceFile: File, output: File, ctaVoiceFile: File? = null): Boolean {
        lastError = null
        return try {
            val music = decodeToPcm { extractor -> extractor.setDataSource(context, musicUri, null) } ?: return false
            val voice = decodeToPcm { extractor -> extractor.setDataSource(voiceFile.absolutePath) } ?: return false
            val ctaVoice = ctaVoiceFile?.takeIf { it.exists() && it.length() > 0L }?.let { decodeToPcm { extractor -> extractor.setDataSource(it.absolutePath) } }
            val targetRate = music.sampleRate
            val targetChannels = music.channels
            val musicSamples = toTarget(music, targetRate, targetChannels)
            val voiceSamples = toTarget(voice, targetRate, targetChannels)
            val ctaSamples = ctaVoice?.let { toTarget(it, targetRate, targetChannels) } ?: ShortArray(0)
            // Main narration followed by CTA speech; silence naturally fills any remaining CTA time.
            val totalSamples = voiceSamples.size + ctaSamples.size
            val mixed = ByteArray(totalSamples * 2)
            var i = 0
            while (i < totalSamples) {
                val musicValue = if (musicSamples.isNotEmpty()) (musicSamples[i % musicSamples.size] * MUSIC_VOLUME).toInt() else 0
                val voiceValue = when {
                    i < voiceSamples.size -> voiceSamples[i].toInt()
                    i - voiceSamples.size < ctaSamples.size -> ctaSamples[i - voiceSamples.size].toInt()
                    else -> 0
                }
                val value = (musicValue + voiceValue).coerceIn(-32768, 32767)
                mixed[i * 2] = (value and 0xFF).toByte()
                mixed[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
                i++
            }
            encodePcmToAac(mixed, targetRate, targetChannels, output)
        } catch (e: Exception) { lastError = "${e.javaClass.simpleName}: ${e.message ?: "unknown"}"; false }
    }

    private data class PcmData(val samples: ShortArray, val sampleRate: Int, val channels: Int)

    private fun decodeToPcm(configure: (MediaExtractor) -> Unit): PcmData? {
        val extractor = MediaExtractor()
        try {
            configure(extractor)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            val bytes = ByteArrayOutputStream()
            var inputDone = false
            var outputDone = false
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            try {
                while (!outputDone) {
                    if (!inputDone) {
                        val index = decoder.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val input = decoder.getInputBuffer(index) ?: return null
                            input.clear()
                            val pts = extractor.sampleTime
                            if (pts < 0L) {
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
                            val out = decoder.outputFormat
                            if (out.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            if (out.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = out.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        else -> if (index >= 0) {
                            val buffer = decoder.getOutputBuffer(index)
                            if (buffer != null && info.size > 0) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val temp = ByteArray(info.size)
                                buffer.get(temp)
                                bytes.write(temp)
                            }
                            val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            decoder.releaseOutputBuffer(index, false)
                            if (eos) outputDone = true
                        }
                    }
                }
            } finally {
                try { decoder.stop() } catch (_: Exception) { }
                decoder.release()
            }
            val raw = bytes.toByteArray()
            val samples = when (pcmEncoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> {
                    val count = raw.size / 4
                    val out = ShortArray(count)
                    val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                    var i = 0
                    while (i < count) {
                        out[i] = (buffer.float.coerceIn(-1f, 1f) * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                        i++
                    }
                    out
                }
                else -> {
                    val count = raw.size / 2
                    val out = ShortArray(count)
                    var i = 0
                    while (i < count) {
                        out[i] = (((raw[i * 2 + 1].toInt() shl 8) or (raw[i * 2].toInt() and 0xFF))).toShort()
                        i++
                    }
                    out
                }
            }
            return PcmData(samples, sampleRate, channels)
        } finally {
            extractor.release()
        }
    }

    private fun toTarget(source: PcmData, targetRate: Int, targetChannels: Int): ShortArray {
        if (source.sampleRate <= 0 || source.channels <= 0) return ShortArray(0)
        val sourceFrames = source.samples.size / source.channels
        val targetFrames = ((sourceFrames.toLong() * targetRate) / source.sampleRate).toInt()
        val result = ShortArray(targetFrames * targetChannels)
        var frame = 0
        while (frame < targetFrames) {
            val sourceFrame = minOf(sourceFrames - 1, ((frame.toLong() * source.sampleRate) / targetRate).toInt())
            var channel = 0
            while (channel < targetChannels) {
                val sourceChannel = if (source.channels == 1) 0 else minOf(channel, source.channels - 1)
                result[frame * targetChannels + channel] = source.samples[sourceFrame * source.channels + sourceChannel]
                channel++
            }
            frame++
        }
        return result
    }

    /** PCM output from the Android decoder is 16-bit signed little-endian. */
    private fun scalePcm16(data: ByteArray, volume: Float) {
        var i = 0
        while (i + 1 < data.size) {
            val sample = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort().toInt()
            val scaled = (sample * volume).toInt().coerceIn(-32768, 32767)
            data[i] = (scaled and 0xFF).toByte()
            data[i + 1] = ((scaled shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    private fun encodePcmToAac(pcm: ByteArray, sampleRate: Int, channels: Int, output: File): Boolean {
        if (sampleRate <= 0 || channels <= 0) return false
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, 2)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        if (output.exists()) output.delete()
        output.parentFile?.mkdirs()
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
                            encoder.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
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
