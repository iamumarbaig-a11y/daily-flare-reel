package com.thedailyflare.reel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File

/** Encodes a 15-second news reel followed by a 3-second CTA end card. */
class ReelEncoder {
    interface Drain { fun onFrame(frame: Int) {} }

    fun encode(background: Bitmap, title: String, headlines: List<String>, output: File, drain: Drain? = null) {
        require(output.parentFile?.exists() != false) { "Output directory does not exist" }

        val width = 1080
        val height = 1920
        val fps = 30
        val totalFrames = 18 * fps
        val bitrate = 6_000_000

        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfoCompat.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val codec = MediaCodec.createEncoderByType("video/avc")
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = codec.createInputSurface()
        codec.start()

        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrack = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            for (frame in 0 until totalFrames) {
                drawFrame(inputSurface, background, title, headlines, width, height, frame >= 15 * fps)
                drain(codec, muxer, bufferInfo, videoTrack, muxerStarted).also {
                    videoTrack = it.first
                    muxerStarted = it.second
                }
                drain?.onFrame(frame + 1)
            }

            inputSurface.release()
            signalEndOfInputStream(codec)

            var eos = false
            while (!eos) {
                val result = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    result == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    result == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) throw IllegalStateException("Output format changed twice")
                        videoTrack = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    result >= 0 -> {
                        val encoded = codec.getOutputBuffer(result)
                        if (encoded != null && bufferInfo.size > 0 && muxerStarted) {
                            encoded.position(bufferInfo.offset)
                            encoded.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(videoTrack, encoded, bufferInfo)
                        }
                        eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        codec.releaseOutputBuffer(result, false)
                    }
                }
            }
        } finally {
            if (muxerStarted) muxer.stop()
            muxer.release()
            codec.stop()
            codec.release()
        }
    }

    private fun drawFrame(
        surface: Surface,
        background: Bitmap,
        title: String,
        headlines: List<String>,
        width: Int,
        height: Int,
        showCta: Boolean
    ) {
        val canvas: Canvas = surface.lockCanvas(null)
        try {
            canvas.drawBitmap(background, null, android.graphics.Rect(0, 0, width, height), null)
            ReelLayout.draw(canvas, title, headlines, width, height, showCta)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    private fun drain(
        codec: MediaCodec,
        muxer: MediaMuxer,
        info: MediaCodec.BufferInfo,
        currentTrack: Int,
        started: Boolean
    ): Pair<Int, Boolean> {
        var track = currentTrack
        var muxerStarted = started
        while (true) {
            val result = codec.dequeueOutputBuffer(info, 0)
            when {
                result == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                result == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) throw IllegalStateException("Output format changed twice")
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                result >= 0 -> {
                    val encoded = codec.getOutputBuffer(result)
                    if (encoded != null && info.size > 0 && muxerStarted) {
                        encoded.position(info.offset)
                        encoded.limit(info.offset + info.size)
                        muxer.writeSampleData(track, encoded, info)
                    }
                    codec.releaseOutputBuffer(result, false)
                }
            }
        }
        return Pair(track, muxerStarted)
    }

    private fun signalEndOfInputStream(codec: MediaCodec) {
        codec.signalEndOfInputStream()
    }

    private object MediaCodecInfoCompat {
        const val COLOR_FormatSurface = 0x7F000789
    }
}
