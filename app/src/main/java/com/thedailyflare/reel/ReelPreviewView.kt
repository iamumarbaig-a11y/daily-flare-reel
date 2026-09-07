package com.thedailyflare.reel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View

class ReelPreviewView(context: Context) : View(context) {
    var title: String = "Main heading"
    var headlines: List<String> = emptyList()
    var backgroundBitmap: Bitmap? = null
    var ctaBitmap: Bitmap? = null
    var showCta: Boolean = false
    var visualProgress: Float = 0f
    var effect: ReelEncoder.ImageEffect = ReelEncoder.ImageEffect.ZOOM_IN
    var effectIntensity: Float = 0.18f
    var textPreviewProgress: Float = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = if (width > 0) (width * 16f / 9f).toInt() else 0
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = if (showCta) ctaBitmap else backgroundBitmap
        image?.let {
            // Visual-only approximation: apply the chosen motion effect without narration or rendering.
            val p = visualProgress.coerceIn(0f, 1f)
            canvas.save()
            when (effect) {
                ReelEncoder.ImageEffect.ZOOM_IN -> { val z = 1f + effectIntensity * p; canvas.scale(z, z, width / 2f, height / 2f) }
                ReelEncoder.ImageEffect.ZOOM_OUT -> { val z = 1f + effectIntensity * (1f - p); canvas.scale(z, z, width / 2f, height / 2f) }
                ReelEncoder.ImageEffect.PAN_LEFT -> canvas.translate(-width * effectIntensity * p, 0f)
                ReelEncoder.ImageEffect.PAN_RIGHT -> canvas.translate(width * effectIntensity * p, 0f)
                ReelEncoder.ImageEffect.PAN_UP -> canvas.translate(0f, -height * effectIntensity * p)
                ReelEncoder.ImageEffect.PAN_DOWN -> canvas.translate(0f, height * effectIntensity * p)
                ReelEncoder.ImageEffect.KEN_BURNS -> { val z = 1f + effectIntensity * p; canvas.scale(z, z, width / 2f, height / 2f); canvas.translate(-width * effectIntensity * p * 0.35f, -height * effectIntensity * p * 0.2f) }
                else -> Unit
            }
            ReelLayout.drawCover(canvas, it, width, height)
            canvas.restore()
        }
        val totalWords = ReelLayout.bodyWordCount(headlines)
        val visibleWords = (totalWords * textPreviewProgress.coerceIn(0f, 1f)).toInt()
        ReelLayout.draw(canvas, title, headlines, width, height, ctaBitmap, showCta, visibleWords)
    }
}
