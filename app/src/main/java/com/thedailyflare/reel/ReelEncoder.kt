package com.thedailyflare.reel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.Paint
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
    enum class ImageEffect { NONE, ZOOM_IN, ZOOM_OUT, PAN_LEFT, PAN_RIGHT, PAN_UP, PAN_DOWN, KEN_BURNS }
    interface Drain { fun onFrame(frame: Int, total: Int) {} }

    fun encode(
        backgrounds: List<Bitmap>,
        ctaBitmap: Bitmap,
        title: String,
        headlines: List<String>,
        voiceDurationMs: Long,
        titleSpeechMs: Long,
        headlineSpeechDurationsMs: List<Long>,
        output: File,
        effects: List<ImageEffect> = emptyList(),
        intensities: List<Float> = emptyList(),
        drain: Drain? = null
    ) {
        output.delete()
        val width = 1080; val height = 1920; val fps = 60
        val mainFrames = (((voiceDurationMs.coerceAtLeast(1L) + 999L) / 1000L) * fps).toInt().coerceAtLeast(fps)
        val totalFrames = mainFrames + 3 * fps
        val bodyWordCount = ReelLayout.bodyWordCount(headlines)
        val headlineWordCounts = headlines.take(7).map { it.trim().split(Regex("\\s+")).count { w -> w.isNotBlank() } }
        encodeFrames(backgrounds, ctaBitmap, title, headlines, bodyWordCount, headlineWordCounts,
            headlineSpeechDurationsMs, voiceDurationMs, titleSpeechMs, mainFrames, totalFrames, fps,
            output, effects, intensities, drain)
    }

    private fun encodeFrames(
        backgrounds: List<Bitmap>, ctaBitmap: Bitmap, title: String, headlines: List<String>, bodyWordCount: Int,
        headlineWordCounts: List<Int>, headlineSpeechDurationsMs: List<Long>, voiceDurationMs: Long, titleSpeechMs: Long,
        mainFrames: Int, totalFrames: Int, fps: Int, output: File,
        effects: List<ImageEffect>, intensities: List<Float>, drain: Drain?
    ) {
        val width = 1080; val height = 1920
        val titleDelayFrames = ((titleSpeechMs.coerceIn(0L, voiceDurationMs.coerceAtLeast(0L)) / 1000f) * fps).toInt()
        val narrationFrames = if (voiceDurationMs > 0L) (((voiceDurationMs - titleSpeechMs).coerceAtLeast(1L) / 1000f) * fps).toInt() else 3 * fps
        val revealFrames = narrationFrames.coerceIn(1, mainFrames - titleDelayFrames.coerceAtMost(mainFrames - 1))
        val segmentFrames = allocateSegmentFrames(revealFrames, headlineWordCounts, headlineSpeechDurationsMs)

        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000); setInteger(MediaFormat.KEY_FRAME_RATE, fps); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType("video/avc"); val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var surface: Surface? = null; var track = -1; var started = false; val info = MediaCodec.BufferInfo()
        val frameIntervalNs = 1_000_000_000L / fps; var nextFrameNs = System.nanoTime()
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); surface = codec.createInputSurface(); codec.start()
            for (frame in 0 until totalFrames) {
                val waitNs = nextFrameNs - System.nanoTime(); if (waitNs > 0L) Thread.sleep(waitNs / 1_000_000L, (waitNs % 1_000_000L).toInt()); nextFrameNs += frameIntervalNs
                val canvas = surface.lockCanvas(null)
                try {
                    if (frame < mainFrames) {
                        val count = backgrounds.size.coerceAtLeast(1)
                        val imageIndex = ((frame.toLong() * count) / mainFrames.coerceAtLeast(1)).toInt().coerceIn(0, count - 1)
                        val segmentStart = (imageIndex.toLong() * mainFrames) / count
                        val segmentEnd = ((imageIndex + 1L) * mainFrames) / count
                        val segmentLength = (segmentEnd - segmentStart).coerceAtLeast(1L)
                        val imageProgress = ((frame - segmentStart).toFloat() / segmentLength.toFloat()).coerceIn(0f, 1f)
                        // Smooth crossfade during the final 0.35s of each image segment.
                        val transitionFrames = minOf((fps * 0.35f).toInt().coerceAtLeast(1), (segmentLength / 2).toInt().coerceAtLeast(1))
                        val framesToEnd = (segmentEnd - frame).toInt()
                        if (imageIndex < count - 1 && framesToEnd <= transitionFrames) {
                            val fade = (1f - framesToEnd.toFloat() / transitionFrames.toFloat()).coerceIn(0f, 1f)
                            drawEffectCover(canvas, backgrounds[imageIndex], width, height,
                                effects.getOrElse(imageIndex) { ImageEffect.ZOOM_IN },
                                intensities.getOrElse(imageIndex) { 0.18f }, imageProgress, 1f)
                            val nextProgress = (fade * transitionFrames / segmentLength.toFloat()).coerceIn(0f, 1f)
                            drawEffectCover(canvas, backgrounds[imageIndex + 1], width, height,
                                effects.getOrElse(imageIndex + 1) { ImageEffect.ZOOM_IN },
                                intensities.getOrElse(imageIndex + 1) { 0.18f }, nextProgress, fade)
                        } else {
                            drawEffectCover(canvas, backgrounds[imageIndex], width, height,
                                effects.getOrElse(imageIndex) { ImageEffect.ZOOM_IN },
                                intensities.getOrElse(imageIndex) { 0.18f }, imageProgress)
                        }
                        val visibleWords = visibleWordsAtFrame(frame, titleDelayFrames, headlineWordCounts, segmentFrames)
                        ReelLayout.draw(canvas, title, headlines, width, height, null, false, visibleWords)
                    } else ReelLayout.drawCover(canvas, ctaBitmap, width, height)
                } finally { surface.unlockCanvasAndPost(canvas) }
                while (true) {
                    val result = codec.dequeueOutputBuffer(info, 0)
                    when {
                        result == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        result == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { if (started) error("Output format changed twice"); track = muxer.addTrack(codec.outputFormat); muxer.setOrientationHint(0); muxer.start(); started = true }
                        result >= 0 -> { val encoded = codec.getOutputBuffer(result); if (encoded != null && info.size > 0 && started) { encoded.position(info.offset); encoded.limit(info.offset + info.size); muxer.writeSampleData(track, encoded, info) }; codec.releaseOutputBuffer(result, false) }
                    }
                }
                drain?.onFrame(frame + 1, totalFrames)
            }
            codec.signalEndOfInputStream(); surface.release(); surface = null
            var eos = false
            while (!eos) {
                val result = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    result == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    result == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { if (started) error("Output format changed twice"); track = muxer.addTrack(codec.outputFormat); muxer.setOrientationHint(0); muxer.start(); started = true }
                    result >= 0 -> { val encoded = codec.getOutputBuffer(result); if (encoded != null && info.size > 0 && started) { encoded.position(info.offset); encoded.limit(info.offset + info.size); muxer.writeSampleData(track, encoded, info) }; eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0; codec.releaseOutputBuffer(result, false) }
                }
            }
        } finally { try { surface?.release() } catch (_: Exception) {}; if (started) try { muxer.stop() } catch (_: Exception) {}; muxer.release(); try { codec.stop() } catch (_: Exception) {}; codec.release() }
        require(output.exists() && output.length() > 0L) { "18-second video produced no output" }
    }

    private fun drawEffectCover(canvas: Canvas, bitmap: Bitmap, width: Int, height: Int, effect: ImageEffect, intensity: Float, progress: Float, alpha: Float = 1f) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return
        val amount = intensity.coerceIn(0f, 0.5f)
        val p = progress.coerceIn(0f, 1f)
        val eased = p * p * (3f - 2f * p)
        val zoom = when (effect) { ImageEffect.ZOOM_IN -> 1f + amount * eased; ImageEffect.ZOOM_OUT -> 1f + amount * (1f - eased); ImageEffect.KEN_BURNS -> 1f + amount * eased; else -> 1f }
        val baseScale = maxOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height) * zoom
        val drawWidth = bitmap.width * baseScale; val drawHeight = bitmap.height * baseScale
        val travelX = (drawWidth - width).coerceAtLeast(0f) * amount
        val travelY = (drawHeight - height).coerceAtLeast(0f) * amount
        val left = when (effect) { ImageEffect.PAN_LEFT -> -travelX * eased; ImageEffect.PAN_RIGHT -> -travelX * (1f - eased); else -> (width - drawWidth) / 2f }
        val top = when (effect) { ImageEffect.PAN_UP -> -travelY * eased; ImageEffect.PAN_DOWN -> -travelY * (1f - eased); else -> (height - drawHeight) / 2f }
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + drawWidth, top + drawHeight), Paint(Paint.ANTI_ALIAS_FLAG).apply { this.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt() })
    }

    private fun allocateSegmentFrames(totalFrames: Int, wordCounts: List<Int>, measuredDurationsMs: List<Long>): IntArray {
        if (wordCounts.isEmpty()) return IntArray(0); val active = wordCounts.indices.filter { wordCounts[it] > 0 }; val result = IntArray(wordCounts.size); if (active.isEmpty()) return result
        val totalMeasured = active.sumOf { measuredDurationsMs.getOrElse(it) { 0L }.coerceAtLeast(1L) }.coerceAtLeast(1L); var used = 0
        for ((position, index) in active.withIndex()) { val remainingSlots = active.size - position - 1; val frames = if (position == active.lastIndex) (totalFrames - used).coerceAtLeast(1) else { val measured = measuredDurationsMs.getOrElse(index) { 0L }.coerceAtLeast(1L); (totalFrames.toDouble() * measured / totalMeasured).toInt().coerceAtLeast(1).coerceAtMost((totalFrames - used - remainingSlots).coerceAtLeast(1)) }; result[index] = frames; used += frames }
        return result
    }

    private fun visibleWordsAtFrame(frame: Int, titleDelayFrames: Int, headlineWordCounts: List<Int>, segmentFrames: IntArray): Int {
        var elapsed = (frame - titleDelayFrames + 1).coerceAtLeast(0); var visible = 0
        for (index in headlineWordCounts.indices) { val words = headlineWordCounts[index]; if (words <= 0) continue; val duration = segmentFrames.getOrElse(index) { 0 }.coerceAtLeast(1); if (elapsed >= duration) { visible += words; elapsed -= duration } else { visible += kotlin.math.ceil(words * (elapsed.toFloat() / duration)).toInt().coerceIn(0, words); break } }
        return visible
    }
}