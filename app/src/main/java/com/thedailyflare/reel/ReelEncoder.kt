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

        // Render the two static scenes once. Previously ReelLayout measured/wrapped
        // and drew all text on every frame. On text-heavy reels that could take longer
        // than one 33.3ms frame interval, so the surface timestamps drifted and the
        // final CTA frames were pushed beyond the 18s output. Cached bitmaps make each
        // submitted frame a single fast blit.
        val mainFrame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val ctaFrame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(mainFrame).apply {
                ReelLayout.drawCover(this, background, width, height)
                ReelLayout.draw(this, title, headlines, width, height, null, false)
            }
            Canvas(ctaFrame).apply {
                ReelLayout.drawCover(this, ctaBitmap, width, height)
            }
            encodeFrames(mainFrame, ctaFrame, mainFrames, totalFrames, fps, output, drain)
        } finally {
            mainFrame.recycle()
            ctaFrame.recycle()
        }
    }

    private fun encodeFrames(
        mainFrame: Bitmap,
        ctaFrame: Bitmap,
        mainFrames: Int,
        totalFrames: Int,
        fps: Int,
        output: File,
        drain: Drain?
    ) {
        val width = 1080
        val height = 1920
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
        var nextFrameNs = System.nanoTime()

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()

            for (frame in 0 until totalFrames) {
                // Pace from an absolute timeline. Cached rendering is fast enough that
                // this maintains ~30fps without accumulating text-rendering delays.
                val waitNs = nextFrameNs - System.nanoTime()
                if (waitNs > 0L) {
                    val millis = waitNs / 1_000_000L
                    val nanos = (waitNs % 1_000_000L).toInt()
                    Thread.sleep(millis, nanos)
                }
                nextFrameNs += frameIntervalNs

                val canvas = surface.lockCanvas(null)
                try {
                    canvas.drawBitmap(if (frame < mainFrames) mainFrame else ctaFrame, 0f, 0f, null)
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
