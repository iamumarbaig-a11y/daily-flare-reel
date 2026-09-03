package com.thedailyflare.reel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import androidx.media3.common.util.UnstableApi
import java.io.File

/** Renders a narration-length news reel followed by a 3-second CTA. */
@UnstableApi
class ReelEncoder(private val context: Context) {
    interface Drain { fun onFrame(frame: Int, total: Int) {} }

    fun encode(
        background: Bitmap,
        ctaBitmap: Bitmap,
        title: String,
        headlines: List<String>,
        voiceDurationMs: Long,
        titleSpeechMs: Long,
        output: File,
        drain: Drain? = null
    ) {
        output.delete()
        val width = 1080
        val height = 1920
        val fps = 60
        // Narration controls the reel length. Never force variable text into 15 seconds.
        val mainFrames = (((voiceDurationMs.coerceAtLeast(1L) + 999L) / 1000L) * fps).toInt().coerceAtLeast(fps)
        val ctaFrames = 3 * fps // CTA remains a clean 3-second visual ending
        val totalFrames = mainFrames + ctaFrames

        val bodyWordCount = ReelLayout.bodyWordCount(headlines)
        encodeFrames(
            background,
            ctaBitmap,
            title,
            headlines,
            bodyWordCount,
            voiceDurationMs,
            titleSpeechMs,
            mainFrames,
            totalFrames,
            fps,
            output,
            drain
        )
    }

    private fun encodeFrames(
        background: Bitmap,
        ctaBitmap: Bitmap,
        title: String,
        headlines: List<String>,
        bodyWordCount: Int,
        voiceDurationMs: Long,
        titleSpeechMs: Long,
        mainFrames: Int,
        totalFrames: Int,
        fps: Int,
        output: File,
        drain: Drain?
    ) {
        val width = 1080
        val height = 1920
        // Keep the headline immediate, but reveal the body across the actual
        // narration length instead of a fixed three-second animation.
        val titleDelayFrames = ((titleSpeechMs.coerceIn(0L, voiceDurationMs.coerceAtLeast(0L)) / 1000f) * fps).toInt()
        val narrationFrames = if (voiceDurationMs > 0L) {
            (((voiceDurationMs - titleSpeechMs).coerceAtLeast(1L) / 1000f) * fps).toInt()
        } else {
            3 * fps
        }
        val revealFrames = narrationFrames.coerceIn(1, mainFrames - titleDelayFrames.coerceAtMost(mainFrames - 1))

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
                        val progress = frame.toFloat() / (mainFrames - 1).coerceAtLeast(1)
                        val smoothProgress = progress * progress * (3f - 2f * progress)
                        drawZoomedCover(canvas, background, width, height, 1f + 0.18f * smoothProgress)

                        // Title is immediate. The body reveal follows the measured
                        // narration duration, giving the reader a visual pace close
                        // to the selected TTS voice.
                        val visibleWords = if (bodyWordCount == 0) {
                            0
                        } else {
                            val revealProgress =
                                ((frame - titleDelayFrames + 1).toFloat() / revealFrames.toFloat()).coerceIn(0f, 1f)
                            kotlin.math.ceil(bodyWordCount * revealProgress).toInt()
                        }

                        ReelLayout.draw(
                            canvas,
                            title,
                            headlines,
                            width,
                            height,
                            null,
                            false,
                            visibleWords
                        )
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
                drain?.onFrame(frame + 1, totalFrames)
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

        val baseScale = maxOf(
            width.toFloat() / bitmap.width.toFloat(),
            height.toFloat() / bitmap.height.toFloat()
        )
        val scale = baseScale * zoom
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f

        canvas.drawBitmap(
            bitmap,
            null,
            RectF(left, top, left + drawWidth, top + drawHeight),
            null
        )
    }
}
