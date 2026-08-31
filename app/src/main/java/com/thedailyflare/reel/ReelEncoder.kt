package com.thedailyflare.reel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import androidx.media3.common.util.UnstableApi
import java.io.File

/** Renders one continuous 18-second video: 15s news, then 3s CTA image. */
@UnstableApi
class ReelEncoder(private val context: Context) {
    interface Drain { fun onFrame(frame: Int) {} }

    fun encode(
        background: Bitmap,
        ctaBitmap: Bitmap,
        title: String,
        headlines: List<String>,
        output: File,
        drain: Drain? = null
    ) {
        output.delete()

        val width = 1080
        val height = 1920
        val fps = 30
        val mainFrames = 15 * fps
        val ctaFrames = 3 * fps
        val totalFrames = mainFrames + ctaFrames

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
        val frameIntervalNs = 1_000_000_000L / fps
        val renderStartNs = System.nanoTime()

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()

            for (frame in 0 until totalFrames) {
                // Pace against an absolute timeline rather than sleeping 33ms every loop.
                // This prevents encoder/dequeue latency from accumulating and pushing the
                // 15-second -> 3-second CTA transition toward the end of the 18s video.
                val targetNs = renderStartNs + frame * frameIntervalNs
                val remainingNs = targetNs - System.nanoTime()
                if (remainingNs > 0L) {
                    val millis = remainingNs / 1_000_000L
                    val nanos = (remainingNs % 1_000_000L).toInt()
                    if (millis > 0L || nanos > 0) Thread.sleep(millis, nanos)
                }

                val canvas: Canvas = surface.lockCanvas(null)
                try {
                    if (frame < mainFrames) {
                        ReelLayout.drawCover(canvas, background, width, height)
                        ReelLayout.draw(canvas, title, headlines, width, height, null, false)
                    } else {
                        // Frames 450..539 are the complete final 3 seconds.
                        ReelLayout.drawCover(canvas, ctaBitmap, width, height)
                    }
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }

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
        } finally {
            try { surface?.release() } catch (_: Exception) { }
            if (started) try { muxer.stop() } catch (_: Exception) { }
            muxer.release()
            try { codec.stop() } catch (_: Exception) { }
            codec.release()
        }

        require(output.exists() && output.length() > 0L) { "18-second video produced no output" }
    }
}
