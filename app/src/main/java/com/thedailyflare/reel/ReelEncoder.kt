package com.thedailyflare.reel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File

/** Encodes exactly 15 seconds of news followed by exactly 3 seconds of the supplied CTA image. */
class ReelEncoder {
    interface Drain { fun onFrame(frame: Int) {} }

    fun encode(background: Bitmap, ctaBitmap: Bitmap, title: String, headlines: List<String>, output: File, drain: Drain? = null) {
        val width = 1080
        val height = 1920
        val fps = 30
        val totalFrames = 18 * fps
        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FORMAT_SURFACE)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType("video/avc")
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var started = false
        val info = MediaCodec.BufferInfo()
        try {
            for (frame in 0 until totalFrames) {
                val showCta = frame >= 15 * fps
                val canvas = surface.lockCanvas(null)
                try {
                    canvas.drawBitmap(if (showCta) ctaBitmap else background, null, android.graphics.Rect(0, 0, width, height), null)
                    ReelLayout.draw(canvas, title, headlines, width, height, ctaBitmap, showCta)
                } finally { surface.unlockCanvasAndPost(canvas) }
                while (true) {
                    val result = codec.dequeueOutputBuffer(info, 0)
                    when {
                        result == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        result == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (started) throw IllegalStateException("Output format changed twice")
                            track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true
                        }
                        result >= 0 -> {
                            val encoded = codec.getOutputBuffer(result)
                            if (encoded != null && info.size > 0 && started) {
                                encoded.position(info.offset); encoded.limit(info.offset + info.size)
                                muxer.writeSampleData(track, encoded, info)
                            }
                            codec.releaseOutputBuffer(result, false)
                        }
                    }
                }
                drain?.onFrame(frame + 1)
            }
            surface.release()
            codec.signalEndOfInputStream()
            var eos = false
            while (!eos) {
                val result = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    result == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (started) throw IllegalStateException("Output format changed twice")
                        track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true
                    }
                    result >= 0 -> {
                        val encoded = codec.getOutputBuffer(result)
                        if (encoded != null && info.size > 0 && started) {
                            encoded.position(info.offset); encoded.limit(info.offset + info.size)
                            muxer.writeSampleData(track, encoded, info)
                        }
                        eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        codec.releaseOutputBuffer(result, false)
                    }
                }
            }
        } finally {
            if (started) muxer.stop()
            muxer.release(); codec.stop(); codec.release()
        }
    }

    private companion object { const val COLOR_FORMAT_SURFACE = 0x7F000789 }
}
