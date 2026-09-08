package com.thedailyflare.reel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
        y += drawConnectedTextBlock(canvas, titleLines, y, titlePaint, white, RIGHT - LEFT, TITLE_PAD_X, TITLE_PAD_Y, TITLE_RADIUS, titleLineHeight) + GAP

        y += 16f

        val maxTextWidth = RIGHT - LEFT - TEXT_PAD_X * 2f
        val lineHeight = textPaint.textSize + 3f
        var remainingWords = visibleBodyWords.coerceAtLeast(0)

        for (headline in headlines.take(7)) {
            if (headline.isBlank() || remainingWords <= 0) break
            val words = headline.trim().split(Regex("[\\s\\n]+")).filter { it.isNotBlank() }
            val take = minOf(words.size, remainingWords)
            if (take <= 0) continue
            // Wrap the COMPLETE headline first, then reveal words inside those fixed
            // line positions. Re-wrapping a growing prefix made existing words jump or
            // briefly disappear whenever the next word pushed a line over its width.
            val fullLines = wrap(headline.trim(), textPaint, maxTextWidth)
            // Draw complete word glyphs at their final positions. Do not redraw a growing
            // string: that was causing the first glyph of a newly revealed word to look
            // clipped for a frame on some devices.
            y += drawWordByWordBlock(
                canvas, fullLines, take, y, textPaint, white,
                RIGHT - LEFT, TEXT_PAD_X, TEXT_PAD_Y, TEXT_RADIUS, lineHeight
            ) + GAP
            remainingWords -= take
            if (y > H - 80f) return
        }
    }

    /**
     * Reveals whole words at fixed positions. Each word is drawn independently, so a
     * newly appearing word can never inherit clipping or partial glyph rendering from
     * the previous frame.
     */
    private fun drawWordByWordBlock(
        canvas: Canvas,
        fullLines: List<String>,
        visibleWords: Int,
        startY: Float,
        paint: Paint,
        bgPaint: Paint,
        maxWidth: Float,
        padX: Float,
        padY: Float,
        radius: Float,
        lineHeight: Float
    ): Float {
        if (visibleWords <= 0) return 0f
        val visibleLines = ArrayList<List<String>>()
        var remaining = visibleWords
        for (line in fullLines) {
            if (remaining <= 0) break
            val words = line.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            val count = minOf(words.size, remaining)
            if (count > 0) visibleLines.add(words.take(count))
            remaining -= count
            if (count < words.size) break
        }
        if (visibleLines.isEmpty()) return 0f

        val rects = ArrayList<RectF>(visibleLines.size)
        var y = startY
        for (words in visibleLines) {
            val visibleText = words.joinToString(" ")
            val w = minOf(paint.measureText(visibleText) + padX * 2f, maxWidth)
            val h = lineHeight + padY * 2f
            rects.add(RectF(LEFT, y, LEFT + w, y + h))
            y += h + SAME_TEXT_GAP
        }

        val path = Path()
        for (i in rects.indices) {
            val r = rects[i]
            val tl = if (i == 0) radius else 0f
            val tr = if (i == 0) radius else 0f
            val br = if (i == rects.lastIndex) radius else 0f
            val bl = if (i == rects.lastIndex) radius else 0f
            path.addRoundRect(r, floatArrayOf(tl, tl, tr, tr, br, br, bl, bl), Path.Direction.CW)
        }
        canvas.drawPath(path, bgPaint)

        for (i in visibleLines.indices) {
            var x = rects[i].left + padX
            val baseline = rects[i].top + padY + paint.textSize
            val words = visibleLines[i]
            for ((wordIndex, word) in words.withIndex()) {
                canvas.drawText(word, x, baseline, paint)
                x += paint.measureText(word)
                if (wordIndex != words.lastIndex) x += paint.measureText(" ")
            }
        }

        return rects.sumOf { it.height().toDouble() }.toFloat() + SAME_TEXT_GAP * (rects.size - 1)
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
        lineHeight: Float
    ): Float {
        if (lines.isEmpty()) return 0f

        val rects = ArrayList<RectF>(lines.size)
        var y = startY
        for (line in lines) {
            val w = minOf(paint.measureText(line) + padX * 2f, maxWidth)
            val h = lineHeight + padY * 2f
            rects.add(RectF(LEFT, y, LEFT + w, y + h))
            y += h + SAME_TEXT_GAP
        }

        // Merge the wrapped lines into one continuous highlight silhouette.
        // The union follows each line's actual text width, while adjacent lines
        // have squared inner corners so the only rounded corners are on the outside.
        val path = Path()
        for (i in rects.indices) {
            val r = rects[i]
            val tl = if (i == 0) radius else 0f
            val tr = if (i == 0) radius else 0f
            val br = if (i == rects.lastIndex) radius else 0f
            val bl = if (i == rects.lastIndex) radius else 0f
            path.addRoundRect(
                r,
                floatArrayOf(tl, tl, tr, tr, br, br, bl, bl),
                Path.Direction.CW
            )
        }
        canvas.drawPath(path, bgPaint)

        for (i in rects.indices) {
            val r = rects[i]
            canvas.drawText(lines[i], r.left + padX, r.top + padY + paint.textSize, paint)
        }

        return rects.sumOf { it.height().toDouble() }.toFloat() + SAME_TEXT_GAP * (rects.size - 1)
    }

    /**
     * Reveals words without recalculating line wrapping. This keeps already-visible
     * words in exactly the same place while the next word appears.
     */
    private fun revealWordsInFixedLines(fullLines: List<String>, maxWords: Int): List<String> {
        if (maxWords <= 0) return emptyList()
        var remaining = maxWords
        val result = ArrayList<String>()
        for (line in fullLines) {
            if (remaining <= 0) break
            val words = line.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            val take = minOf(words.size, remaining)
            if (take > 0) result.add(words.take(take).joinToString(" "))
            remaining -= take
            if (take < words.size) break
        }
        return result
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
                else { result.add(line); line = word }
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