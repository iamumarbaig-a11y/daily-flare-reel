package com.thedailyflare.reel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/** 15-second news composition. The final 3 seconds are the supplied CTA image only. */
object ReelLayout {
    private const val W = 1080f
    private const val H = 1920f
    private const val LEFT = 108f
    private const val TOP = 288f
    private const val RIGHT = 972f
    private const val GAP = 18f

    fun draw(canvas: Canvas, title: String, headlines: List<String>, width: Int, height: Int) =
        draw(canvas, title, headlines, width, height, null, false)

    fun draw(canvas: Canvas, title: String, headlines: List<String>, width: Int, height: Int,
             ctaBitmap: Bitmap?, showCta: Boolean) {
        canvas.save()
        canvas.scale(width / W, height / H)
        if (showCta && ctaBitmap != null) {
            // The CTA image is complete. Absolutely no generated text is drawn over it.
            canvas.drawBitmap(ctaBitmap, null, RectF(0f, 0f, W, H), null)
        } else {
            drawNews(canvas, title, headlines)
        }
        canvas.restore()
    }

    private fun drawNews(canvas: Canvas, title: String, headlines: List<String>) {
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 72f
            typeface = Typeface.create("sans", Typeface.BOLD)
        }

        // Main heading: black text only. No background rectangle.
        var y = TOP + titlePaint.textSize
        for (line in wrap(title.ifBlank { "Main heading" }, titlePaint, RIGHT - LEFT)) {
            canvas.drawText(line, LEFT, y, titlePaint)
            y += 82f
        }
        y += 36f

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 34f
            typeface = Typeface.create("sans", Typeface.NORMAL)
        }
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val maxTextWidth = RIGHT - LEFT - 44f
        val lineHeight = 43f

        // Every WRAPPED LINE is its own fitted white rounded block.
        // The block width follows the rendered text width instead of filling the screen.
        for (headline in headlines.take(7)) {
            if (headline.isBlank()) continue
            for (line in wrap(headline, textPaint, maxTextWidth)) {
                val blockWidth = minOf(textPaint.measureText(line) + 44f, RIGHT - LEFT)
                val blockHeight = lineHeight + 24f
                canvas.drawRoundRect(
                    RectF(LEFT, y, LEFT + blockWidth, y + blockHeight),
                    20f, 20f, white
                )
                canvas.drawText(line, LEFT + 22f, y + 39f, textPaint)
                y += blockHeight + GAP
                if (y > H - 80f) return
            }
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
