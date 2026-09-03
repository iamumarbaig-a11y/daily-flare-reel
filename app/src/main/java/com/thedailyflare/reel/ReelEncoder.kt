package com.thedailyflare.reel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import androidx.media3.common.util.UnstableApi
import java.io.File

/** Renders one continuous 18-second video: 15s news with subtle zoom, then 3s CTA image. */
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

        // Cache the text overlay once. Only the photograph moves underneath it.
        val textOverlay = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(textOverlay).apply {
                ReelLayout.draw(this, title, headlines, width, height, null, false)
            }
            encodeFrames(background, ctaBitmap, textOverlay, mainFrames, totalFrames, fps, output, drain)
        } finally {
            textOverlay.recycle()
        }
    }

    private fun encodeFrames(
        background: Bitmap,
        ctaBitmap: Bitmap,
        textOverlay: Bitmap,
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
                val waitNs = nextFrameNs - System.nanoTime()
                if (waitNs > 0L) {
                    val millis = waitNs / 1_000_000L
                    val nanos = (waitNs % 1_000_000L).toInt()
                    Thread.sleep(millis, nanos)
                }
                nextFrameNs += frameIntervalNs

                val canvas = surface.lockCanvas(null)
                try {
                    if (frame < mainFrames) {
                        // Gentle Ken Burns zoom: 1.00x -> 1.10x across the 15-second news scene.
                        val progress = frame.toFloat() / (mainFrames - 1).coerceAtLeast(1)
                        drawZoomedCover(canvas, background, width, height, 1f + 0.10f * progress)
                        canvas.drawBitmap(textOverlay, 0f, 0f, null)
                    } else {
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

    private fun drawZoomedCover(
        canvas: Canvas,
        bitmap: Bitmap,
        width: Int,
        height: Int,
        zoom: Float
    ) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return

        val targetRatio = width.toFloat() / height.toFloat()
        val sourceRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        var cropWidth: Float
        var cropHeight: Float

        if (sourceRatio > targetRatio) {
            cropHeight = bitmap.height.toFloat()
            cropWidth = cropHeight * targetRatio
        } else {
            cropWidth = bitmap.width.toFloat()
            cropHeight = cropWidth / targetRatio
        }

        cropWidth /= zoom
        cropHeight /= zoom

        // Keep the crop centered so the movement stays subtle and professional.
        val left = ((bitmap.width - cropWidth) / 2f).toInt().coerceAtLeast(0)
        val top = ((bitmap.height - cropHeight) / 2f).toInt().coerceAtLeast(0)
        val right = (left + cropWidth.toInt()).coerceAtMost(bitmap.width)
        val bottom = (top + cropHeight.toInt()).coerceAtMost(bitmap.height)

        canvas.drawBitmap(bitmap, Rect(left, top, right, bottom), Rect(0, 0, width, height), null)
    }
}
