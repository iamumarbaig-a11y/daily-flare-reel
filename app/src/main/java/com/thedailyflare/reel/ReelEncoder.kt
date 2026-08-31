package com.thedailyflare.reel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Renders the 15-second news portion, then uses Media3 Composition to append the
 * CTA image as a real 3-second video clip. This avoids manually concatenating
 * H.264 samples, which can leave players showing only the first clip.
 */
@UnstableApi
class ReelEncoder(private val context: Context) {
    interface Drain { fun onFrame(frame: Int) {} }

    fun encode(background: Bitmap, ctaBitmap: Bitmap, title: String, headlines: List<String>, output: File, drain: Drain? = null) {
        output.delete()
        val mainSegment = File(output.parentFile, "daily_flare_main_15s.mp4")
        val ctaImage = File(output.parentFile, "daily_flare_cta.jpg")
        val composed = File(output.parentFile, "daily_flare_composed_18s.mp4")
        mainSegment.delete(); ctaImage.delete(); composed.delete()
        try {
            encodeMainSegment(background, title, headlines, mainSegment, drain)
            require(mainSegment.exists() && mainSegment.length() > 0L) { "15-second main segment produced no output" }

            ctaImage.outputStream().use { out ->
                require(ctaBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) { "CTA image could not be written" }
            }
            require(ctaImage.exists() && ctaImage.length() > 0L) { "CTA image produced no output" }

            composeMainAndCta(mainSegment, ctaImage, composed)
            require(composed.exists() && composed.length() > 0L) { "Media3 composition produced no output" }
            composed.copyTo(output, overwrite = true)
            require(output.exists() && output.length() > 0L) { "18-second video produced no output" }
        } finally {
            mainSegment.delete(); ctaImage.delete(); composed.delete()
        }
    }

    private fun encodeMainSegment(background: Bitmap, title: String, headlines: List<String>, output: File, drain: Drain?) {
        val width = 1080
        val height = 1920
        val fps = 30
        val totalFrames = 15 * fps
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
                    ReelLayout.drawCover(canvas, background, width, height)
                    ReelLayout.draw(canvas, title, headlines, width, height, null, false)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                Thread.sleep(1000L / fps)
                drainEncoder(codec, muxer, info, startedState = { started }, onStart = { newTrack -> track = newTrack; started = true })
                drain?.onFrame(frame + 1)
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
                        muxer.start(); started = true
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
        onStart: (Int) -> Unit
    ) {
        while (true) {
            val result = codec.dequeueOutputBuffer(info, 0)
            when {
                result == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                result == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (startedState()) throw IllegalStateException("Output format changed twice")
                    val track = muxer.addTrack(codec.outputFormat)
                    muxer.setOrientationHint(0)
                    muxer.start()
                    onStart(track)
                }
                result >= 0 -> {
                    val encoded = codec.getOutputBuffer(result)
                    if (encoded != null && info.size > 0 && startedState()) {
                        encoded.position(info.offset); encoded.limit(info.offset + info.size)
                        val trackField = currentTrack(muxer)
                        muxer.writeSampleData(trackField, encoded, info)
                    }
                    codec.releaseOutputBuffer(result, false)
                }
            }
        }
    }

    // MediaMuxer doesn't expose its track id after start. The main encoding path
    // therefore uses the single video track id cached by the wrapper below.
    private var cachedTrack = -1

    private fun currentTrack(muxer: MediaMuxer): Int = cachedTrack

    private fun composeMainAndCta(mainSegment: File, ctaImage: File, output: File) {
        val main = EditedMediaItem.Builder(MediaItem.fromUri(mainSegment.toURI().toString())).build()
        val ctaMedia = MediaItem.Builder()
            .setUri(ctaImage.toURI().toString())
            .setImageDurationMs(3_000)
            .build()
        val cta = EditedMediaItem.Builder(ctaMedia).setFrameRate(30).build()
        val videoSequence = EditedMediaItemSequence.withVideoFrom(listOf(main, cta))
        val composition = Composition.Builder(videoSequence).build()
        val latch = CountDownLatch(1)
        val error = AtomicReference<Throwable?>(null)
        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) { latch.countDown() }
                override fun onError(composition: Composition, result: ExportResult, exportException: ExportException) {
                    error.set(exportException); latch.countDown()
                }
            })
            .build()
        transformer.start(composition, output.absolutePath)
        if (!latch.await(120, TimeUnit.SECONDS)) throw IllegalStateException("CTA composition timed out")
        error.get()?.let { throw it }
    }
}
