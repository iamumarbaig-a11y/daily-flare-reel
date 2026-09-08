package com.thedailyflare.reel

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View

class ReelPreviewView(context: Context) : View(context) {
    var title: String = "Main heading"
    var headlines: List<String> = emptyList()
    var backgroundBitmap: Bitmap? = null
    var ctaBitmap: Bitmap? = null
    var showCta = false
    var visualProgress = 0f
    var effect: ReelEncoder.ImageEffect = ReelEncoder.ImageEffect.ZOOM_IN
    var effectIntensity = 0.18f
    var textPreviewProgress = 0f

    private var textPlaybackStartedAtMs = 0L
    private var textPlaybackRunning = false
    private val textPlaybackTick = object : Runnable {
        override fun run() {
            if (!textPlaybackRunning) return
            val words = ReelLayout.bodyWordCount(headlines)
            if (words <= 0) {
                textPlaybackRunning = false
                textPreviewProgress = 0f
                invalidate()
                return
            }
            val elapsed = SystemClock.elapsedRealtime() - textPlaybackStartedAtMs
            val duration = (words * 220L).coerceIn(900L, 9000L)
            textPreviewProgress = (elapsed.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            invalidate()
            if (textPreviewProgress >= 1f) {
                textPlaybackRunning = false
            } else {
                postDelayed(this, 16L)
            }
        }
    }

    fun playTextPreview() {
        removeCallbacks(textPlaybackTick)
        textPreviewProgress = 0f
        textPlaybackStartedAtMs = SystemClock.elapsedRealtime()
        textPlaybackRunning = true
        post(textPlaybackTick)
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        setMeasuredDimension(width, if (width > 0) (width * 16f / 9f).toInt() else 0)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = if (showCta) ctaBitmap else backgroundBitmap
        image?.let {
            val p = visualProgress.coerceIn(0f, 1f)
            canvas.save()
            when (effect) {
                ReelEncoder.ImageEffect.ZOOM_IN -> canvas.scale(1f + effectIntensity * p, 1f + effectIntensity * p, width / 2f, height / 2f)
                ReelEncoder.ImageEffect.ZOOM_OUT -> canvas.scale(1f + effectIntensity * (1f - p), 1f + effectIntensity * (1f - p), width / 2f, height / 2f)
                ReelEncoder.ImageEffect.PAN_LEFT -> canvas.translate(-width * effectIntensity * p, 0f)
                ReelEncoder.ImageEffect.PAN_RIGHT -> canvas.translate(width * effectIntensity * p, 0f)
                ReelEncoder.ImageEffect.PAN_UP -> canvas.translate(0f, -height * effectIntensity * p)
                ReelEncoder.ImageEffect.PAN_DOWN -> canvas.translate(0f, height * effectIntensity * p)
                ReelEncoder.ImageEffect.KEN_BURNS -> { canvas.scale(1f + effectIntensity * p, 1f + effectIntensity * p, width / 2f, height / 2f); canvas.translate(-width * effectIntensity * p * .35f, -height * effectIntensity * p * .2f) }
                ReelEncoder.ImageEffect.NONE -> Unit
            }
            ReelLayout.drawCover(canvas, it, width, height)
            canvas.restore()
        }
        if (showCta) return

        // Plain normal animation: reveal body words one-by-one, with no text effect.
        val total = ReelLayout.bodyWordCount(headlines)
        val visible = if (total <= 0) 0 else kotlin.math.ceil(total * textPreviewProgress.coerceIn(0f, 1f)).toInt().coerceIn(0, total)
        ReelLayout.draw(canvas, title, headlines, width, height, null, false, visible)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(textPlaybackTick)
        textPlaybackRunning = false
        super.onDetachedFromWindow()
    }
}
