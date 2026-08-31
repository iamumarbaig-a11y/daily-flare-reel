package com.thedailyflare.reel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View

class ReelPreviewView(context: Context) : View(context) {
    var title: String = "Main heading"
    var headlines: List<String> = emptyList()
    var backgroundBitmap: Bitmap? = null
    var ctaBitmap: Bitmap? = null
    var showCta: Boolean = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = if (showCta) ctaBitmap else backgroundBitmap
        image?.let { canvas.drawBitmap(it, null, Rect(0, 0, width, height), null) }
        ReelLayout.draw(canvas, title, headlines, width, height, ctaBitmap, showCta)
    }
}
