package com.thedailyflare.reel

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/** 15s news composition followed by a supplied 3s CTA image. */
object ReelLayout {
    private const val W = 1080f
    private const val H = 1920f
    private const val LEFT = 108f
    private const val TOP = 288f
    private const val RIGHT = 972f

    fun draw(canvas: Canvas, title: String, headlines: List<String>, width: Int, height: Int) =
        draw(canvas, title, headlines, width, height, null, false)

    fun draw(canvas: Canvas, title: String, headlines: List<String>, width: Int, height: Int, ctaBitmap: android.graphics.Bitmap?, showCta: Boolean) {
        canvas.save()
        canvas.scale(width / W, height / H)
        if (showCta && ctaBitmap != null) {
            canvas.drawBitmap(ctaBitmap, null, RectF(0f, 0f, W, H), null)
        } else {
            drawNews(canvas, title, headlines)
        }
        canvas.restore()
    }

    private fun drawNews(canvas: Canvas, title: String, headlines: List<String>) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        paint.typeface = Typeface.create("sans", Typeface.BOLD)
        paint.textSize = 72f
        var y = TOP
        for (line in wrap(title.ifBlank { "Main heading" }, paint, RIGHT - LEFT).take(2)) {
            canvas.drawText(line, LEFT, y, paint)
            y += 82f
        }

        y += 82f
        paint.textSize = 34f
        paint.typeface = Typeface.create("sans", Typeface.NORMAL)
        for (headline in headlines.take(7)) {
            if (headline.isBlank()) continue
            val lines = wrap(headline, paint, RIGHT - LEFT - 44f)
            val lineHeight = 43f
            val blockHeight = lines.size * lineHeight + 24f
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            canvas.drawRoundRect(RectF(LEFT, y, RIGHT, y + blockHeight), 20f, 20f, bg)
            var ty = y + 39f
            for (line in lines) {
                canvas.drawText(line, LEFT + 22f, ty, paint)
                ty += lineHeight
            }
            y += blockHeight + 18f
            if (y > H - 80f) break
        }
    }

    private fun wrap(value: String, paint: Paint, maxWidth: Float): List<String> {
        val result = mutableListOf<String>()
        for (paragraph in value.split("\n")) {
            var line = ""
            for (word in paragraph.trim().split(Regex("\\s+"))) {
                if (word.isEmpty()) continue
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (line.isEmpty() || paint.measureText(candidate) <= maxWidth) line = candidate
                else { result.add(line); line = word }
            }
            if (line.isNotEmpty()) result.add(line)
        }
        return if (result.isEmpty()) listOf("") else result
    }
}
