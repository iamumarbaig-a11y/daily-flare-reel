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
        val headlineWordCounts = headlines.take(7).map { headline ->
            headline.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        }
        val headlineSpeechWeights = headlines.take(7).map { speechWeight(it) }
        encodeFrames(
            background,
            ctaBitmap,
            title,
            headlines,
            bodyWordCount,
            headlineWordCounts,
            headlineSpeechWeights,
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
        headlineWordCounts: List<Int>,
        headlineSpeechWeights: List<Float>,
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
        val segmentFrames = allocateSegmentFrames(revealFrames, headlineWordCounts, headlineSpeechWeights)

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

                        // Title is immediate. Body words still appear one by one,
                        // but each subheading gets its own slice of the measured
                        // narration time. This avoids forcing every sentence to reveal
                        // at one constant speed when Kokoro naturally changes pace.
                        val visibleWords = visibleWordsAtFrame(
                            frame = frame,
                            titleDelayFrames = titleDelayFrames,
                            headlineWordCounts = headlineWordCounts,
                            segmentFrames = segmentFrames
                        )

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


    private fun speechWeight(text: String): Float {
        if (text.isBlank()) return 0f
        var weight = 0f
        for (ch in text) {
            when {
                ch.isWhitespace() -> Unit
                ch.isLetterOrDigit() -> weight += 1f
                ch == ',' || ch == ';' || ch == ':' -> weight += 3f
                ch == '.' || ch == '!' || ch == '?' -> weight += 6f
                else -> weight += 1f
            }
        }
        // A small word component prevents very short words from making a
        // segment unrealistically fast while keeping the text itself unchanged.
        val words = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        return weight + words * 2f
    }

    private fun allocateSegmentFrames(
        totalFrames: Int,
        wordCounts: List<Int>,
        weights: List<Float>
    ): IntArray {
        if (wordCounts.isEmpty()) return IntArray(0)
        val active = wordCounts.indices.filter { wordCounts[it] > 0 }
        val result = IntArray(wordCounts.size)
        if (active.isEmpty()) return result

        val totalWeight = active.sumOf { weights.getOrElse(it) { 0f }.toDouble() }.toFloat()
        var used = 0
        for ((position, index) in active.withIndex()) {
            val remainingSlots = active.size - position - 1
            val frames = if (position == active.lastIndex) {
                (totalFrames - used).coerceAtLeast(1)
            } else {
                val share = if (totalWeight > 0f) {
                    totalFrames.toFloat() * weights.getOrElse(index) { 0f } / totalWeight
                } else {
                    totalFrames.toFloat() / active.size
                }
                share.toInt().coerceAtLeast(1).coerceAtMost((totalFrames - used - remainingSlots).coerceAtLeast(1))
            }
            result[index] = frames
            used += frames
        }
        return result
    }

    private fun visibleWordsAtFrame(
        frame: Int,
        titleDelayFrames: Int,
        headlineWordCounts: List<Int>,
        segmentFrames: IntArray
    ): Int {
        var elapsed = (frame - titleDelayFrames + 1).coerceAtLeast(0)
        var visible = 0

        for (index in headlineWordCounts.indices) {
            val words = headlineWordCounts[index]
            if (words <= 0) continue
            val duration = segmentFrames.getOrElse(index) { 0 }.coerceAtLeast(1)
            if (elapsed >= duration) {
                visible += words
                elapsed -= duration
            } else {
                val progress = elapsed.toFloat() / duration.toFloat()
                visible += kotlin.math.ceil(words * progress).toInt().coerceIn(0, words)
                break
            }
        }
        return visible
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
