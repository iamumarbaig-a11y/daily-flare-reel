package com.thedailyflare.reel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File

/** Encodes the complete 18-second reel as one continuous H.264 stream: 15s main + 3s CTA. */
class ReelEncoder {
    interface Drain { fun onFrame(frame: Int) {} }

    fun encode(background: Bitmap, ctaBitmap: Bitmap, title: String, headlines: List<String>, output: File, drain: Drain? = null) {
        output.delete()

        val width = 1080
        val height = 1920
        val fps = 30
        val mainFrames = 15 * fps
        val totalFrames = 18 * fps
        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val codec = MediaCodec.createEncoderByType("video/avc")
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var surface: Surface? = null
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
                    // Frames 0..449 = 15s main image + text.
                    // Frames 450..539 = 3s CTA image only.
                    if (frame < mainFrames) {
                        ReelLayout.drawCover(canvas, background, width, height)
                        ReelLayout.draw(canvas, title, headlines, width, height, null, false)
                    } else {
                        ReelLayout.drawCover(canvas, ctaBitmap, width, height)
                    }
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }

                Thread.sleep(1000L / fps)
                drainEncoder(codec, muxer, info, startedState = { started }, setStarted = { value ->
                    started = value
                    if (value) track = muxer.trackCount - 1
                })

                drain?.onFrame(frame + 1)
            }

            codec.signalEndOfInputStream()
            surface.release()
            surface = null

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

            require(output.exists() && output.length() > 0) { "18-second video produced no output" }
        } finally {
            try { surface?.release() } catch (_: Exception) { }
            if (started) try { muxer.stop() } catch (_: Exception) { }
            muxer.release()
            try { codec.stop() } catch (_: Exception) { }
            codec.release()
        }
    }

    private fun drainEncoder(
        codec: MediaCodec,
        muxer: MediaMuxer,
        info: MediaCodec.BufferInfo,
        startedState: () -> Boolean,
        setStarted: (Boolean) -> Unit
    ) {
        var started = startedState()
        var track = -1
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
                    setStarted(true)
                }
                result >= 0 -> {
                    val encoded = codec.getOutputBuffer(result)
                    if (encoded != null && info.size > 0 && started && track >= 0) {
                        encoded.position(info.offset)
                        encoded.limit(info.offset + info.size)
                        muxer.writeSampleData(track, encoded, info)
                    }
                    codec.releaseOutputBuffer(result, false)
                }
            }
        }
    }
}
