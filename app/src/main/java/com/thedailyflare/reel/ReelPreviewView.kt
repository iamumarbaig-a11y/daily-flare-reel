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
    var textEffect = "FADE + POP"
    var textEffectIntensity = 25
    var textRevealMode = "WORD BY WORD"

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
                ReelEncoder.ImageEffect.ZOOM_OUT -> canvas.scale(1f + effectIntensity * (1f-p), 1f + effectIntensity * (1f-p), width / 2f, height / 2f)
                ReelEncoder.ImageEffect.PAN_LEFT -> canvas.translate(-width * effectIntensity * p, 0f)
                ReelEncoder.ImageEffect.PAN_RIGHT -> canvas.translate(width * effectIntensity * p, 0f)
                ReelEncoder.ImageEffect.PAN_UP -> canvas.translate(0f, -height * effectIntensity * p)
                ReelEncoder.ImageEffect.PAN_DOWN -> canvas.translate(0f, height * effectIntensity * p)
                ReelEncoder.ImageEffect.KEN_BURNS -> { canvas.scale(1f + effectIntensity*p, 1f + effectIntensity*p, width/2f, height/2f); canvas.translate(-width*effectIntensity*p*.35f, -height*effectIntensity*p*.2f) }
                ReelEncoder.ImageEffect.NONE -> Unit
            }
            ReelLayout.drawCover(canvas, it, width, height)
            canvas.restore()
        }
        if (showCta) return

        val total = ReelLayout.bodyWordCount(headlines)
        if (total <= 0) { ReelLayout.draw(canvas, title, headlines, width, height, null, false, 0); return }
        val progress = textPreviewProgress.coerceIn(0f, 1f)
        val visible = when (textRevealMode) {
            "INSTANT" -> total
            else -> kotlin.math.ceil(total * progress).toInt().coerceIn(0, total)
        }
        if (visible == 0) { ReelLayout.draw(canvas, title, headlines, width, height, null, false, 0); return }

        // Draw everything that has already appeared normally and permanently.
        val settledWords = if (visible == total && progress >= .999f) visible else (visible - 1).coerceAtLeast(0)
        ReelLayout.draw(canvas, title, headlines, width, height, null, false, settledWords)

        // Build a transparent layer containing ONLY the newly appearing word.
        val layer = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val layerCanvas = Canvas(layer)
        ReelLayout.draw(layerCanvas, title, headlines, width, height, null, false, visible)
        layerCanvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        val erase = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
        val settledMask = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val maskCanvas = Canvas(settledMask)
        ReelLayout.draw(maskCanvas, title, headlines, width, height, null, false, settledWords)
        layerCanvas.drawBitmap(settledMask, 0f, 0f, erase)
        layerCanvas.restore()
        settledMask.recycle()

        val wordPosition = progress * total
        val rawPhase = abs(wordPosition - kotlin.math.floor(wordPosition.toDouble()).toFloat())
        val phase = if (visible >= total && progress >= .999f) 1f else rawPhase.coerceIn(0.05f, 1f)
        val settle = phase * phase * (3f - 2f * phase)
        val amount = textEffectIntensity.coerceIn(0, 100) / 100f

        val save = canvas.save()
        applyTextPreviewEffect(canvas, settle, amount)
        canvas.drawBitmap(layer, 0f, 0f, null)
        canvas.restoreToCount(save)
        layer.recycle()
    }

    private fun applyTextPreviewEffect(canvas: Canvas, settle: Float, amount: Float) {
        if (textEffect == "NONE" || amount <= 0f) return
        val cx = width / 2f; val cy = height * .42f; val remaining = 1f - settle
        when (textEffect) {
            "POP" -> canvas.scale(1f - amount*.32f*remaining, 1f - amount*.32f*remaining, cx, cy)
            "FADE + POP" -> { canvas.scale(1f - amount*.28f*remaining, 1f - amount*.28f*remaining, cx, cy); canvas.saveLayerAlpha(0f,0f,width.toFloat(),height.toFloat(),(255f*(.35f+.65f*settle)).toInt()) }
            "SLIDE UP" -> canvas.translate(0f, height*amount*.10f*remaining)
            "BOUNCE" -> { val over=kotlin.math.sin(settle*Math.PI).toFloat()*amount*.16f; canvas.scale(1f+over-remaining*amount*.12f,1f+over-remaining*amount*.12f,cx,cy) }
            "BLUR IN" -> { canvas.scale(1f+amount*.12f*remaining,1f+amount*.12f*remaining,cx,cy); canvas.saveLayerAlpha(0f,0f,width.toFloat(),height.toFloat(),(255f*(.25f+.75f*settle)).toInt()) }
            "SLIDE + FADE" -> { canvas.translate(0f,height*amount*.08f*remaining); canvas.saveLayerAlpha(0f,0f,width.toFloat(),height.toFloat(),(255f*(.30f+.70f*settle)).toInt()) }
        }
    }
}