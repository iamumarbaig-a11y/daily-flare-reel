package com.thedailyflare.reel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface

/** Dynamic news composition. The final 3 seconds are the supplied outro image. */
object ReelLayout {
    private const val W = 1080f
    private const val H = 1920f

    private const val LEFT = 80f
    private const val TOP = 250f
    private const val RIGHT = 940f
    private const val GAP = 14f
    private const val SAME_TEXT_GAP = 0f

    private const val TITLE_SIZE = 52f
    private const val TITLE_PAD_X = 16f
    private const val TITLE_PAD_Y = 10f
    private const val TITLE_RADIUS = 12f

    private const val TEXT_SIZE = 31f
    private const val TEXT_PAD_X = 14f
    private const val TEXT_PAD_Y = 7f
    private const val TEXT_RADIUS = 10f

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
            Rect(0, top, (top + cropHeight).coerceAtMost(bitmap.height), bitmap.width)
        }
        canvas.drawBitmap(bitmap, src, Rect(0, 0, width, height), null)
    }

    fun draw(canvas: Canvas, title: String, headlines: List<String>, width: Int, height: Int) =
        draw(canvas, title, headlines, width, height, null, false, 0)

    fun draw(
        canvas: Canvas,
        title: String,
        headlines: List<String>,
        width: Int,
        height: Int,
        ctaBitmap: Bitmap?,
        showCta: Boolean
    ) = draw(canvas, title, headlines, width, height, ctaBitmap, showCta, Int.MAX_VALUE)

    fun draw(
        canvas: Canvas,
        title: String,
        headlines: List<String>,
        width: Int,
        height: Int,
        ctaBitmap: Bitmap?,
        showCta: Boolean,
        visibleBodyWords: Int
    ) {
        canvas.save()
        canvas.scale(width / W, height / H)
        if (showCta && ctaBitmap != null) drawCover(canvas, ctaBitmap, W.toInt(), H.toInt())
        else drawNews(canvas, title, headlines, visibleBodyWords)
        canvas.restore()
    }

    private fun drawNews(canvas: Canvas, title: String, headlines: List<String>, visibleBodyWords: Int) {
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = TITLE_SIZE
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = TEXT_SIZE
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

        var y = TOP

        val titleMaxWidth = RIGHT - LEFT - (TITLE_PAD_X * 2f)
        val titleLines = wrap(title.ifBlank { "Main heading" }, titlePaint, titleMaxWidth)
        val titleLineHeight = titlePaint.textSize + 2f
        drawConnectedTextBlock(
            canvas = canvas,
            lines = titleLines,
            startY = y,
            paint = titlePaint,
            bgPaint = white,
            maxWidth = RIGHT - LEFT,
            padX = TITLE_PAD_X,
            padY = TITLE_PAD_Y,
            radius = TITLE_RADIUS,
            lineHeight = titleLineHeight
        ) { used -> y += used + GAP }

        y += 16f

        val maxTextWidth = RIGHT - LEFT - TEXT_PAD_X * 2f
        val lineHeight = textPaint.textSize + 3f
        var remainingWords = visibleBodyWords.coerceAtLeast(0)

        for (headline in headlines.take(7)) {
            if (headline.isBlank() || remainingWords <= 0) break
            val words = headline.trim().split(Regex("[\\s\\n]+")).filter { it.isNotBlank() }
            val take = minOf(words.size, remainingWords)
            if (take <= 0) continue
            val visibleText = visiblePrefixPreservingLineBreaks(headline, take)
            remainingWords -= take
            val lines = wrap(visibleText, textPaint, maxTextWidth)

            drawConnectedTextBlock(
                canvas = canvas,
                lines = lines,
                startY = y,
                paint = textPaint,
                bgPaint = white,
                maxWidth = RIGHT - LEFT,
                padX = TEXT_PAD_X,
                padY = TEXT_PAD_Y,
                radius = TEXT_RADIUS,
                lineHeight = lineHeight
            ) { used -> y += used + GAP }

            if (y > H - 80f) return
        }
    }

    private fun drawConnectedTextBlock(
        canvas: Canvas,
        lines: List<String>,
        startY: Float,
        paint: Paint,
        bgPaint: Paint,
        maxWidth: Float,
        padX: Float,
        padY: Float,
        radius: Float,
        lineHeight: Float,
        onComplete: (Float) -> Unit
    ) {
        if (lines.isEmpty()) return

        val lineRects = ArrayList<RectF>(lines.size)
        var y = startY

        for (line in lines) {
            val width = minOf(paint.measureText(line) + padX * 2f, maxWidth)
            val h = lineHeight + padY * 2f
            lineRects.add(RectF(LEFT, y, LEFT + width, y + h))
            y += h + SAME_TEXT_GAP
        }

        // Draw the highlight as one continuous union. For wrapped lines, extend
        // each adjacent pair to the larger right edge in their shared strip so
        // there can be no visible horizontal seam between lines.
        for (i in lineRects.indices) {
            val rect = lineRects[i]
            val previousRight = if (i > 0) lineRects[i - 1].right else rect.left
            val nextRight = if (i < lineRects.lastIndex) lineRects[i + 1].right else rect.left
            val left = rect.left
            val right = maxOf(rect.right, previousRight, nextRight)
            val top = rect.top
            val bottom = rect.bottom

            val topRadius = if (i == 0) radius else 0f
            val bottomRadius = if (i == lineRects.lastIndex) radius else 0f
            val path = android.graphics.Path().apply {
                addRoundRect(
                    RectF(left, top, right, bottom),
                    floatArrayOf(
                        topRadius, topRadius,
                        topRadius, topRadius,
                        bottomRadius, bottomRadius,
                        bottomRadius, bottomRadius
                    ),
                    android.graphics.Path.Direction.CW
                )
            }
            canvas.drawPath(path, bgPaint)
        }

        for (i in lineRects.indices) {
            val rect = lineRects[i]
            canvas.drawText(lines[i], rect.left + padX, rect.top + padY + paint.textSize, paint)
        }

        onComplete(lineRects.sumOf { it.height().toDouble() }.toFloat() + SAME_TEXT_GAP * (lines.size - 1))
    }

    private fun visiblePrefixPreservingLineBreaks(value: String, maxWords: Int): String {
        if (maxWords <= 0) return ""
        var remaining = maxWords
        val out = StringBuilder()
        val sourceLines = value.split("\n")
        for ((lineIndex, sourceLine) in sourceLines.withIndex()) {
            if (remaining <= 0) break
            val words = sourceLine.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.isEmpty()) continue
            val take = minOf(words.size, remaining)
            if (out.isNotEmpty() && lineIndex > 0) out.append('\n')
            out.append(words.take(take).joinToString(" "))
            remaining -= take
            if (take < words.size) break
        }
        return out.toString()
    }

    private fun wrap(value: String, paint: Paint, maxWidth: Float): List<String> {
        val result = mutableListOf<String>()
        for (paragraph in value.split("\n")) {
            var line = ""
            for (word in paragraph.trim().split(Regex("\\s+"))) {
                if (word.isEmpty()) continue
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (line.isEmpty() || paint.measureText(candidate) <= maxWidth) line = candidate
                else {
                    result.add(line)
                    line = word
                }
            }
            if (line.isNotEmpty()) result.add(line)
        }
        return if (result.isEmpty()) listOf("") else result
    }

    fun bodyWordCount(headlines: List<String>): Int =
        headlines.take(7).sumOf { headline ->
            headline.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        }
}
