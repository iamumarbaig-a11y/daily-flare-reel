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
    private const val LEFT = 108f       // 10% from left
    private const val TOP = 288f        // 15% from top
    private const val RIGHT = 972f
    private const val GAP = 18f

    fun draw(canvas: Canvas, title: String, headlines: List<String>, width: Int, height: Int) =
        draw(canvas, title, headlines, width, height, null, false)

    fun draw(canvas: Canvas, title: String, headlines: List<String>, width: Int, height: Int,
             ctaBitmap: Bitmap?, showCta: Boolean) {
        canvas.save()
        canvas.scale(width / W, height / H)
        if (showCta && ctaBitmap != null) {
            // CTA is a complete user-supplied image. Draw absolutely nothing over it.
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
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

        // Main heading: large black bold text with a fitted white rounded background.
        // The background follows the actual rendered line width; it is never a fixed block.
        var y = TOP
        val titleLines = wrap(title.ifBlank { "Main heading" }, titlePaint, RIGHT - LEFT - 44f)
        for (line in titleLines) {
            val blockWidth = minOf(titlePaint.measureText(line) + 44f, RIGHT - LEFT)
            val blockHeight = titlePaint.textSize + 28f
            canvas.drawRoundRect(
                RectF(LEFT, y, LEFT + blockWidth, y + blockHeight),
                20f, 20f, white
            )
            canvas.drawText(line, LEFT + 22f, y + titlePaint.textSize + 2f, titlePaint)
            y += blockHeight + GAP
        }
        y += 18f

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 34f
            typeface = Typeface.create("sans", Typeface.NORMAL)
        }
        val maxTextWidth = RIGHT - LEFT - 44f
        val lineHeight = 43f

        // Every WRAPPED LINE gets its own fitted white rounded block.
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
