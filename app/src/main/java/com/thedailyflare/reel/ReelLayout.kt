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

    // Compact editorial text treatment, tuned for a 1080×1920 reel.
    private const val LEFT = 80f
    private const val TOP = 250f
    private const val RIGHT = 940f
    private const val GAP = 14f
    private const val SAME_TEXT_GAP = -0.1f

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
            Rect(0, top, bitmap.width, (top + cropHeight).coerceAtMost(bitmap.height))
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

    private fun drawNews(
        canvas: Canvas,
        title: String,
        headlines: List<String>,
        visibleBodyWords: Int
    ) {
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

        // Tight, text-sized white cards. Wrapped lines from one heading remain
        // visually connected while separate headlines retain a small gap.
        val titleMaxWidth = RIGHT - LEFT - (TITLE_PAD_X * 2f)
        val titleLines = wrap(title.ifBlank { "Main heading" }, titlePaint, titleMaxWidth)
        val titleLineHeight = titlePaint.textSize + 2f
        val titleBlockHeight = titleLineHeight + TITLE_PAD_Y * 2f
        for ((index, line) in titleLines.withIndex()) {
            val blockWidth = minOf(
                titlePaint.measureText(line) + TITLE_PAD_X * 2f,
                RIGHT - LEFT
            )
            canvas.drawRoundRect(
                RectF(LEFT, y, LEFT + blockWidth, y + titleBlockHeight),
                TITLE_RADIUS, TITLE_RADIUS, white
            )
            canvas.drawText(
                line,
                LEFT + TITLE_PAD_X,
                y + TITLE_PAD_Y + titlePaint.textSize,
                titlePaint
            )
            y += titleBlockHeight + if (index == titleLines.lastIndex) GAP else SAME_TEXT_GAP
        }

        y += 16f

        val maxTextWidth = RIGHT - LEFT - TEXT_PAD_X * 2f
        val lineHeight = textPaint.textSize + 3f
        val blockHeight = lineHeight + TEXT_PAD_Y * 2f

        var remainingWords = visibleBodyWords.coerceAtLeast(0)
        for (headline in headlines.take(7)) {
            if (headline.isBlank() || remainingWords <= 0) break

            val words = headline.trim().split(Regex("[\\s\\n]+")).filter { it.isNotBlank() }
            val take = minOf(words.size, remainingWords)
            if (take <= 0) break
            val visibleText = visiblePrefixPreservingLineBreaks(headline, take)
            remainingWords -= take

            val lines = wrap(visibleText, textPaint, maxTextWidth)
            for ((index, line) in lines.withIndex()) {
                val blockWidth = minOf(
                    textPaint.measureText(line) + TEXT_PAD_X * 2f,
                    RIGHT - LEFT
                )
                canvas.drawRoundRect(
                    RectF(LEFT, y, LEFT + blockWidth, y + blockHeight),
                    TEXT_RADIUS, TEXT_RADIUS, white
                )
                canvas.drawText(
                    line,
                    LEFT + TEXT_PAD_X,
                    y + TEXT_PAD_Y + textPaint.textSize,
                    textPaint
                )
                y += blockHeight + if (index == lines.lastIndex) GAP else SAME_TEXT_GAP
                if (y > H - 80f) return
            }
        }
    }

    private fun visiblePrefixPreservingLineBreaks(value: String, maxWords: Int): String {
        if (maxWords <= 0) return ""
        var remaining = maxWords
        val out = StringBuilder()

        val sourceLines = value.split("\n")
        for ((lineIndex, sourceLine) in sourceLines.withIndex()) {
            if (remaining <= 0) break
            val words = sourceLine.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.isEmpty()) {
                if (lineIndex < sourceLines.lastIndex && out.isNotEmpty()) out.append('\n')
                continue
            }
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
