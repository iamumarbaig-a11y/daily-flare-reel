package com.thedailyflare.reel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
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
    private const val SAME_TEXT_GAP = -0.1f

    fun drawCover(canvas: Canvas, bitmap: Bitmap, width: Int, height: Int) {
        if (width <= 0 || height <= 0 || bitmap.width <= 0 || bitmap.height <= 0) return
        val sourceRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val targetRatio = width.toFloat() / height.toFloat()
        val src = if (sourceRatio > targetRatio) {
            val cropWidth = (bitmap.height * targetRatio).toInt().coerceAtLeast(1)
            val left = ((bitmap.width - cropWidth) / 2).coerceAtLeast(0)
            Rect(left, 0, (left + cropWidth).coerceAtMost(bitmap.width), bitmap.height)
        } else {
            val cropHeight = (bitmap.width / targetRatio).toInt().coerceAtLeast(1)
            val top = ((bitmap.height - cropHeight) / 2).coerceAtLeast(0)
            Rect(0, top, bitmap.width, (top + cropHeight).coerceAtMost(bitmap.height))
        }
        canvas.drawBitmap(bitmap, src, Rect(0, 0, width, height), null)
    }

    fun draw(canvas: Canvas, title: String, headlines: List<String>, width: Int, height: Int) =
        draw(canvas, title, headlines, width, height, null, false)

    fun draw(canvas: Canvas, title: String, headlines: List<String>, width: Int, height: Int,
             ctaBitmap: Bitmap?, showCta: Boolean) {
        canvas.save()
        canvas.scale(width / W, height / H)
        if (showCta && ctaBitmap != null) drawCover(canvas, ctaBitmap, W.toInt(), H.toInt())
        else drawNews(canvas, title, headlines)
        canvas.restore()
    }

    private fun drawNews(canvas: Canvas, title: String, headlines: List<String>) {
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 72f
            typeface = Typeface.create("sans", Typeface.BOLD)
        }
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        var y = TOP

        // Main heading: each wrapped line is its own tight white rounded block.
        // Only the gap between lines belonging to this same heading is -0.1.
        val titleLines = wrap(title.ifBlank { "Main heading" }, titlePaint, RIGHT - LEFT - 44f)
        val titleLineHeight = titlePaint.textSize + 8f
        val titleBlockHeight = titleLineHeight + 28f
        for ((index, line) in titleLines.withIndex()) {
            val blockWidth = minOf(titlePaint.measureText(line) + 44f, RIGHT - LEFT)
            canvas.drawRoundRect(RectF(LEFT, y, LEFT + blockWidth, y + titleBlockHeight), 20f, 20f, white)
            canvas.drawText(line, LEFT + 22f, y + titlePaint.textSize + 2f, titlePaint)
            y += titleBlockHeight + if (index == titleLines.lastIndex) GAP else SAME_TEXT_GAP
        }

        // Keep the existing heading-to-subheading spacing unchanged.
        y += 18f

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 34f
            typeface = Typeface.create("sans", Typeface.BOLD)
        }
        val maxTextWidth = RIGHT - LEFT - 44f
        val lineHeight = 43f
        val blockHeight = lineHeight + 24f

        // Each wrapped line of every subheading gets its own compact white
        // rounded block. Only lines belonging to the same subheading use -0.1
        // spacing. Separate subheadings retain the normal GAP.
        for (headline in headlines.take(7)) {
            if (headline.isBlank()) continue
            val lines = wrap(headline, textPaint, maxTextWidth)
            for ((index, line) in lines.withIndex()) {
                val blockWidth = minOf(textPaint.measureText(line) + 44f, RIGHT - LEFT)
                canvas.drawRoundRect(RectF(LEFT, y, LEFT + blockWidth, y + blockHeight), 20f, 20f, white)
                canvas.drawText(line, LEFT + 22f, y + 39f, textPaint)
                y += blockHeight + if (index == lines.lastIndex) GAP else SAME_TEXT_GAP
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
