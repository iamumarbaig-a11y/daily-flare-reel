package com.thedailyflare.reel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View

class ReelPreviewView(context: Context) : View(context) {
    var title: String = "Main heading"
    var headlines: List<String> = emptyList()
    var backgroundBitmap: Bitmap? = null
    var ctaBitmap: Bitmap? = null
    var showCta: Boolean = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = if (width > 0) (width * 16f / 9f).toInt() else 0
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = if (showCta) ctaBitmap else backgroundBitmap
        image?.let { ReelLayout.drawCover(canvas, it, width, height) }
        ReelLayout.draw(canvas, title, headlines, width, height, ctaBitmap, showCta)
    }
}
