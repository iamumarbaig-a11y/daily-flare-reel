package com.thedailyflare.reel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

class ReelPreviewView(context: Context) : View(context) {
    var title: String = "The Daily Flare"
    var headlines: List<String> = emptyList()
    var backgroundBitmap: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        backgroundBitmap?.let { canvas.drawBitmap(it, null, Rect(0, 0, width, height), paint) }
        ReelLayout.draw(canvas, title, headlines, width, height)
    }
}
