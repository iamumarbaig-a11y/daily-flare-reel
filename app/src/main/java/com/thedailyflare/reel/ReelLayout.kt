package com.thedailyflare.reel

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/** 1080x1920 reference layout: one larger heading followed by compact headline fragments. */
object ReelLayout {
    data class Card(val text: String, val rect: RectF)

    fun draw(canvas: Canvas, title: String, headlines: List<String>, width: Int, height: Int) {
        val sx = width / 1080f
        val sy = height / 1920f
        canvas.save()
        canvas.scale(sx, sy)
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
        canvas.restore()
    }
}
