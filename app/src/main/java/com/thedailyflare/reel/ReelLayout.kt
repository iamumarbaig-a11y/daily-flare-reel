package com.thedailyflare.reel

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/** 1080x1920 reference layout. The final 3 seconds are a clean CTA card. */
object ReelLayout {
    fun draw(canvas: Canvas, title: String, headlines: List<String>, width: Int, height: Int) {
        draw(canvas, title, headlines, width, height, showCta = false)
    }

    fun draw(canvas: Canvas, title: String, headlines: List<String>, width: Int, height: Int, showCta: Boolean) {
        val sx = width / 1080f
        val sy = height / 1920f
        canvas.save()
        canvas.scale(sx, sy)
        if (showCta) {
            drawCta(canvas)
        } else {
            drawNews(canvas, title, headlines)
        }
        canvas.restore()
    }

    private fun drawNews(canvas: Canvas, title: String, headlines: List<String>) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create("sans", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        p.textSize = 72f
        canvas.drawText(title, 540f, 350f, p)
        p.textSize = 44f
        var y = 500f
        for (line in headlines.take(8)) {
            val r = RectF(90f, y - 48f, 990f, y + 18f)
            p.color = Color.WHITE
            canvas.drawRoundRect(r, 24f, 24f, p)
            p.color = Color.BLACK
            canvas.drawText(line, 540f, y, p)
            y += 120f
        }
    }

    private fun drawCta(canvas: Canvas) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans", Typeface.BOLD)
        }
        p.color = Color.WHITE
        canvas.drawRect(0f, 0f, 1080f, 1920f, p)

        p.color = Color.BLACK
        p.textSize = 86f
        canvas.drawText("THE DAILY FLARE", 540f, 820f, p)

        p.textSize = 58f
        canvas.drawText("Follow for the latest news", 540f, 980f, p)

        p.textSize = 44f
        canvas.drawText("Like • Share • Stay informed", 540f, 1085f, p)
    }
}
