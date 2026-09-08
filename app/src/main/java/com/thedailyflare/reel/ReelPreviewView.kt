package com.thedailyflare.reel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import kotlin.math.ceil

class ReelPreviewView(context: Context) : View(context) {
    var title: String = "Main heading"
        set(value) { field = value; restartPreview() }
    var headlines: List<String> = emptyList()
        set(value) { field = value; restartPreview() }
    var backgroundBitmap: Bitmap? = null
    var ctaBitmap: Bitmap? = null
    var showCta: Boolean = false

    private var previewStartMs = System.currentTimeMillis()
    private var previewRunning = true
    private val framePeriodMs = 33L

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        previewRunning = true
        restartPreview()
    }

    override fun onDetachedFromWindow() {
        previewRunning = false
        super.onDetachedFromWindow()
    }

    private fun restartPreview() {
        previewStartMs = System.currentTimeMillis()
        previewRunning = true
        postInvalidateOnAnimation()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = if (width > 0) (width * 16f / 9f).toInt() else 0
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = if (showCta) ctaBitmap else backgroundBitmap
        image?.let { ReelLayout.drawCover(canvas, it, width, height) }

        if (showCta) {
            ReelLayout.draw(canvas, title, headlines, width, height, ctaBitmap, true, 0)
            return
        }

        val bodyWordCount = ReelLayout.bodyWordCount(headlines)
        val elapsedMs = (System.currentTimeMillis() - previewStartMs).coerceAtLeast(0L)
        // Use an 18-second looping preview, matching the normal reel pacing.
        // The title is always visible; body text reveals progressively by word.
        val progress = ((elapsedMs % 18_000L).toFloat() / 18_000f).coerceIn(0f, 1f)
        val visibleWords = if (bodyWordCount <= 0) {
            0
        } else {
            ceil(bodyWordCount * progress).toInt().coerceIn(0, bodyWordCount)
        }

        ReelLayout.draw(canvas, title, headlines, width, height, null, false, visibleWords)

        if (previewRunning) {
            postInvalidateDelayed(framePeriodMs)
        }
    }
}
