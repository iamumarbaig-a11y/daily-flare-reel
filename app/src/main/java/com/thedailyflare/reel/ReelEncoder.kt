package com.thedailyflare.reel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer

/** Encodes a dedicated 15-second news segment and a dedicated 3-second CTA segment, then joins them. */
class ReelEncoder {
    interface Drain { fun onFrame(frame: Int) {} }

    fun encode(background: Bitmap, ctaBitmap: Bitmap, title: String, headlines: List<String>, output: File, drain: Drain? = null) {
        val mainSegment = File(output.parentFile, "daily_flare_main_15s.mp4")
        val ctaSegment = File(output.parentFile, "daily_flare_cta_3s.mp4")
        mainSegment.delete(); ctaSegment.delete(); output.delete()
        try {
            encodeSegment(background, title, headlines, mainSegment, 15, false, drain, 0)
            require(mainSegment.exists() && mainSegment.length() > 0) { "15-second main segment produced no output" }
            encodeSegment(ctaBitmap, "", emptyList(), ctaSegment, 3, true, drain, 450)
            require(ctaSegment.exists() && ctaSegment.length() > 0) { "3-second CTA segment produced no output" }
            joinVideoSegments(mainSegment, ctaSegment, output)
            require(output.exists() && output.length() > 0) { "Joining main and CTA segments produced no output" }
        } finally {
            mainSegment.delete(); ctaSegment.delete()
        }
    }

    private fun encodeSegment(
        bitmap: Bitmap,
        title: String,
        headlines: List<String>,
        output: File,
        seconds: Int,
        ctaOnly: Boolean,
        drain: Drain?,
        frameOffset: Int
    ) {
        val width = 1080
        val height = 1920
        val fps = 30
        val totalFrames = seconds * fps
        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FORMAT_SURFACE)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType("video/avc")
        var surface: Surface? = null
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var started = false
        val info = MediaCodec.BufferInfo()
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()

            for (frame in 0 until totalFrames) {
                val canvas: Canvas = surface.lockCanvas(null)
                try {
                    ReelLayout.drawCover(canvas, bitmap, width, height)
                    if (!ctaOnly) ReelLayout.draw(canvas, title, headlines, width, height, null, false)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                Thread.sleep(1000L / fps)

                while (true) {
                    val result = codec.dequeueOutputBuffer(info, 0)
                    when {
                        result == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        result == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (started) throw IllegalStateException("Output format changed twice")
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.setOrientationHint(0)
                            muxer.start()
                            started = true
                        }
                        result >= 0 -> {
                            val encoded = codec.getOutputBuffer(result)
                            if (encoded != null && info.size > 0 && started) {
                                encoded.position(info.offset)
                                encoded.limit(info.offset + info.size)
                                muxer.writeSampleData(track, encoded, info)
                            }
                            codec.releaseOutputBuffer(result, false)
                        }
                    }
                }
                drain?.onFrame(frameOffset + frame + 1)
            }

            codec.signalEndOfInputStream()
            surface.release(); surface = null
            var eos = false
            while (!eos) {
                val result = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    result == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    result == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (started) throw IllegalStateException("Output format changed twice")
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.setOrientationHint(0)
                        muxer.start()
                        started = true
                    }
                    result >= 0 -> {
                        val encoded = codec.getOutputBuffer(result)
                        if (encoded != null && info.size > 0 && started) {
                            encoded.position(info.offset)
                            encoded.limit(info.offset + info.size)
                            muxer.writeSampleData(track, encoded, info)
                        }
                        eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        codec.releaseOutputBuffer(result, false)
                    }
                }
            }
        } finally {
            try { surface?.release() } catch (_: Exception) { }
            if (started) try { muxer.stop() } catch (_: Exception) { }
            muxer.release()
            try { codec.stop() } catch (_: Exception) { }
            codec.release()
        }
    }

    private fun joinVideoSegments(main: File, cta: File, output: File) {
        val first = MediaExtractor()
        val second = MediaExtractor()
        first.setDataSource(main.absolutePath)
        second.setDataSource(cta.absolutePath)
        try {
            val firstTrack = findVideoTrack(first)
            val secondTrack = findVideoTrack(second)
            val firstFormat = first.getTrackFormat(firstTrack)
            val secondFormat = second.getTrackFormat(secondTrack)
            require(firstFormat.getInteger(MediaFormat.KEY_WIDTH) == secondFormat.getInteger(MediaFormat.KEY_WIDTH)) { "Main and CTA video widths differ" }
            require(firstFormat.getInteger(MediaFormat.KEY_HEIGHT) == secondFormat.getInteger(MediaFormat.KEY_HEIGHT)) { "Main and CTA video heights differ" }
            require(firstFormat.getString(MediaFormat.KEY_MIME) == secondFormat.getString(MediaFormat.KEY_MIME)) { "Main and CTA video codecs differ" }

            first.selectTrack(firstTrack)
            second.selectTrack(secondTrack)
            val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(0)
            val outTrack = muxer.addTrack(firstFormat)
            muxer.start()
            try {
                copySamples(first, muxer, outTrack, 0L)
                copySamples(second, muxer, outTrack, 15_000_000L)
            } finally {
                try { muxer.stop() } catch (_: Exception) { }
                muxer.release()
            }
        } finally {
            first.release(); second.release()
        }
    }

    private fun findVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) return i
        }
        throw IllegalStateException("No video track found")
    }

    private fun copySamples(extractor: MediaExtractor, muxer: MediaMuxer, outTrack: Int, offsetUs: Long) {
        val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            if (size == 0 && (extractor.sampleFlags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                extractor.advance()
                continue
            }
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime + offsetUs
            // EOS belongs to each temporary segment. It must NOT terminate the
            // final joined track at 15 seconds, otherwise the CTA samples after it
            // are ignored by players.
            info.flags = extractor.sampleFlags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()
            muxer.writeSampleData(outTrack, buffer, info)
            extractor.advance()
        }
    }

    private companion object { const val COLOR_FORMAT_SURFACE = 0x7F000789 }
}
