package com.thedailyflare.reel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.abs

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

    // Text animation settings are fed directly from MainActivity so the silent
    // visual preview uses the same selections the user sees in the dialog.
    var textEffect: String = "FADE + POP"
    var textEffectIntensity: Int = 25
    var textRevealMode: String = "WORD BY WORD"

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = if (width > 0) (width * 16f / 9f).toInt() else 0
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = if (showCta) ctaBitmap else backgroundBitmap
        image?.let {
            val p = visualProgress.coerceIn(0f, 1f)
            canvas.save()
            when (effect) {
                ReelEncoder.ImageEffect.ZOOM_IN -> {
                    val z = 1f + effectIntensity * p
                    canvas.scale(z, z, width / 2f, height / 2f)
                }
                ReelEncoder.ImageEffect.ZOOM_OUT -> {
                    val z = 1f + effectIntensity * (1f - p)
                    canvas.scale(z, z, width / 2f, height / 2f)
                }
                ReelEncoder.ImageEffect.PAN_LEFT -> canvas.translate(-width * effectIntensity * p, 0f)
                ReelEncoder.ImageEffect.PAN_RIGHT -> canvas.translate(width * effectIntensity * p, 0f)
                ReelEncoder.ImageEffect.PAN_UP -> canvas.translate(0f, -height * effectIntensity * p)
                ReelEncoder.ImageEffect.PAN_DOWN -> canvas.translate(0f, height * effectIntensity * p)
                ReelEncoder.ImageEffect.KEN_BURNS -> {
                    val z = 1f + effectIntensity * p
                    canvas.scale(z, z, width / 2f, height / 2f)
                    canvas.translate(-width * effectIntensity * p * 0.35f, -height * effectIntensity * p * 0.2f)
                }
                ReelEncoder.ImageEffect.NONE -> Unit
            }
            ReelLayout.drawCover(canvas, it, width, height)
            canvas.restore()
        }

        if (showCta) return

        val totalWords = ReelLayout.bodyWordCount(headlines)
        val progress = textPreviewProgress.coerceIn(0f, 1f)
        val revealPosition = when (textRevealMode) {
            "CHARACTER BY CHARACTER" -> progress * 2.2f
            "INSTANT" -> 1f
            else -> progress
        }

        // Ceil lets the currently appearing word exist while its effect is still
        // animating, instead of jumping directly to a fully static word.
        val visibleWords = when (textRevealMode) {
            "INSTANT" -> totalWords
            else -> kotlin.math.ceil(totalWords * progress).toInt().coerceIn(0, totalWords)
        }

        val amount = (textEffectIntensity.coerceIn(0, 100) / 100f)
        val revealWordPosition = revealPosition * totalWords\n        val rawPhase = abs(revealWordPosition - kotlin.math.floor(revealWordPosition.toDouble()).toFloat())
        val phase = if (visibleWords == 0 || totalWords == 0) 0f else {
            // 0 at the start of each new reveal, 1 when it settles.
            rawPhase.coerceIn(0f, 1f)
        }
        val settle = phase * phase * (3f - 2f * phase)

        canvas.save()
        applyTextPreviewEffect(canvas, settle, amount)
        ReelLayout.draw(canvas, title, headlines, width, height, null, false, visibleWords)
        canvas.restore()
    }

    private fun applyTextPreviewEffect(canvas: Canvas, settle: Float, amount: Float) {
        if (textEffect == "NONE" || amount <= 0f) return

        val cx = width / 2f
        val cy = height * 0.42f
        val remaining = 1f - settle

        when (textEffect) {
            "POP" -> {
                val startScale = 1f - amount * 0.32f
                val scale = startScale + (1f - startScale) * settle
                canvas.scale(scale, scale, cx, cy)
            }
            "FADE + POP" -> {
                val startScale = 1f - amount * 0.28f
                val scale = startScale + (1f - startScale) * settle
                canvas.scale(scale, scale, cx, cy)
                canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (255f * (0.35f + 0.65f * settle)).toInt())
            }
            "SLIDE UP" -> {
                canvas.translate(0f, height * amount * 0.10f * remaining)
            }
            "BOUNCE" -> {
                val overshoot = kotlin.math.sin(settle * Math.PI).toFloat() * amount * 0.16f
                val scale = 1f + overshoot - remaining * amount * 0.12f
                canvas.scale(scale, scale, cx, cy)
            }
            "BLUR IN" -> {
                // Hardware Canvas blur is not portable across the Android versions
                // supported by this app. We approximate the visual reveal with a
                // strong soft fade + scale so preview and export stay responsive.
                val scale = 1f + amount * 0.12f * remaining
                canvas.scale(scale, scale, cx, cy)
                canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (255f * (0.25f + 0.75f * settle)).toInt())
            }
            "SLIDE + FADE" -> {
                canvas.translate(0f, height * amount * 0.08f * remaining)
                canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (255f * (0.30f + 0.70f * settle)).toInt())
            }
        }
    }
}
