package com.thedailyflare.reel

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.abs

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

        // Normal mode: reveal body text one word at a time with no visual effect.
        val total = ReelLayout.bodyWordCount(headlines)
        if (total <= 0) {
            ReelLayout.draw(canvas, title, headlines, width, height, null, false, 0)
            return
        }
        val visible = kotlin.math.ceil(total * textPreviewProgress.coerceIn(0f, 1f)).toInt().coerceIn(0, total)
        ReelLayout.draw(canvas, title, headlines, width, height, null, false, visible)
    }

