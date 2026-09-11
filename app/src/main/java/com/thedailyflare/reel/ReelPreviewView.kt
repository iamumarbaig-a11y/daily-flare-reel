package com.thedailyflare.reel

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

class ReelPreviewView(context: Context) : View(context) {
    var title: String = "Main heading"
    var headlines: List<String> = emptyList()
    var backgroundBitmap: Bitmap? = null
    var ctaBitmap: Bitmap? = null
    var showCta = false
    var visualProgress = 0f
    var timelineProgress = 0f
    var effect: ReelEncoder.ImageEffect = ReelEncoder.ImageEffect.ZOOM_IN
    var effectIntensity = 0.18f
    var textPreviewProgress = 0f
    var timelineDurationMs: Long = 12000L

    private var textPlaybackStartedAtMs = 0L
    private var textPlaybackRunning = false
    private val textPlaybackTick = object : Runnable {
        override fun run() {
            if (!textPlaybackRunning) return
            val words = ReelLayout.bodyWordCount(headlines)
            if (words <= 0) {
                textPlaybackRunning = false
                textPreviewProgress = 0f
                invalidate()
                return
            }
            val elapsed = SystemClock.elapsedRealtime() - textPlaybackStartedAtMs
            val duration = (words * 220L).coerceIn(900L, 9000L)
            textPreviewProgress = (elapsed.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            invalidate()
            if (textPreviewProgress >= 1f) textPlaybackRunning = false else postDelayed(this, 16L)
        }
    }

    private var ctaRenderer: CtaOverlayRenderer? = null
    private var ctaOverlays: List<CtaOverlay> = emptyList()
    private var lastCtaRefreshMs = 0L
    private var draggingCtaIndex = -1
    private var lastPinchDistance = 0f
    private var dragging = false

    fun playTextPreview() {
        removeCallbacks(textPlaybackTick)
        textPreviewProgress = 0f
        textPlaybackStartedAtMs = SystemClock.elapsedRealtime()
        textPlaybackRunning = true
        post(textPlaybackTick)
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        setMeasuredDimension(width, if (width > 0) (width * 16f / 9f).toInt() else 0)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = if (showCta) ctaBitmap else backgroundBitmap
        image?.let {
            val p = visualProgress.coerceIn(0f, 1f)
            canvas.save()
            when (effect) {
                ReelEncoder.ImageEffect.ZOOM_IN -> canvas.scale(1f + effectIntensity * p, 1f + effectIntensity * p, width / 2f, height / 2f)
                ReelEncoder.ImageEffect.ZOOM_OUT -> canvas.scale(1f + effectIntensity * (1f - p), 1f + effectIntensity * (1f - p), width / 2f, height / 2f)
                ReelEncoder.ImageEffect.PAN_LEFT -> canvas.translate(-width * effectIntensity * p, 0f)
                ReelEncoder.ImageEffect.PAN_RIGHT -> canvas.translate(width * effectIntensity * p, 0f)
                ReelEncoder.ImageEffect.PAN_UP -> canvas.translate(0f, -height * effectIntensity * p)
                ReelEncoder.ImageEffect.PAN_DOWN -> canvas.translate(0f, height * effectIntensity * p)
                ReelEncoder.ImageEffect.KEN_BURNS -> { canvas.scale(1f + effectIntensity * p, 1f + effectIntensity * p, width / 2f, height / 2f); canvas.translate(-width * effectIntensity * p * .35f, -height * effectIntensity * p * .2f) }
                ReelEncoder.ImageEffect.NONE -> Unit
            }
            ReelLayout.drawCover(canvas, it, width, height)
            canvas.restore()
        }
        if (showCta) return

        val total = ReelLayout.bodyWordCount(headlines)
        val visible = if (total <= 0) 0 else kotlin.math.ceil(total * textPreviewProgress.coerceIn(0f, 1f)).toInt().coerceIn(0, total)
        ReelLayout.draw(canvas, title, headlines, width, height, null, false, visible)

        refreshCtaRendererIfNeeded()
        val timelineMs = (timelineProgress.coerceIn(0f, 1f) * timelineDurationMs.coerceAtLeast(1L)).toLong()
        val active = ctaRenderer?.draw(canvas, timelineMs, width, height) ?: false
        if (!active && ctaOverlays.isNotEmpty()) {
            ctaRenderer?.drawEditing(canvas, width, height)
            drawCtaEditHint(canvas)
        }
    }

    private fun drawCtaEditHint(canvas: Canvas) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            setColor(0xAAFFFFFF.toInt())
        }
        val w = width * 0.30f
        val h = height * 0.08f
        canvas.drawRoundRect(width - w - 18f, 18f, width - 18f, 18f + h, 12f, 12f, paint)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 28f; setColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD }
        canvas.drawText("CTA OVERLAY", width - w + 2f, 18f + h / 2f + 10f, textPaint)
    }

    private fun refreshCtaRendererIfNeeded() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCtaRefreshMs < 250L) return
        lastCtaRefreshMs = now
        val latest = CtaOverlayStore.load(context)
        if (latest != ctaOverlays) {
            ctaRenderer?.release()
            ctaOverlays = latest
            ctaRenderer = if (latest.isEmpty()) null else CtaOverlayRenderer(context, latest)
            if (draggingCtaIndex >= latest.size) draggingCtaIndex = -1
        }
    }

    private fun currentTimelineMs(): Long = (timelineProgress.coerceIn(0f, 1f) * timelineDurationMs.coerceAtLeast(1L)).toLong()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (showCta) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                refreshCtaRendererIfNeeded()
                val timeline = currentTimelineMs()
                val activeIndex = ctaRenderer?.hitTest(timeline, event.x, event.y, width, height) ?: -1
                val index = if (activeIndex >= 0) activeIndex else ctaRenderer?.hitTestEditing(event.x, event.y, width, height) ?: -1
                if (index >= 0) {
                    draggingCtaIndex = index
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (dragging && event.pointerCount >= 2) {
                    lastPinchDistance = pointerDistance(event)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging || draggingCtaIndex < 0) return false
                val index = draggingCtaIndex
                val current = ctaOverlays.getOrNull(index) ?: return true
                if (event.pointerCount >= 2) {
                    val d = pointerDistance(event)
                    if (lastPinchDistance > 0f && d > 1f) {
                        val factor = (d / lastPinchDistance).coerceIn(0.90f, 1.10f)
                        ctaOverlays = ctaOverlays.toMutableList().also { it[index] = current.copy(scale = (current.scale * factor).coerceIn(0.05f, 0.80f)) }
                        lastPinchDistance = d
                    }
                } else {
                    val x = (event.x / width.toFloat()).coerceIn(0f, 1f)
                    val y = (event.y / height.toFloat()).coerceIn(0f, 1f)
                    ctaOverlays = ctaOverlays.toMutableList().also { it[index] = current.copy(x = x, y = y) }
                }
                rebuildCtaRenderer()
                invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                lastPinchDistance = 0f
                if (dragging) return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    CtaOverlayStore.save(context, ctaOverlays)
                    dragging = false
                    draggingCtaIndex = -1
                    lastPinchDistance = 0f
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                    return true
                }
            }
        }
        return true
    }

    private fun rebuildCtaRenderer() {
        ctaRenderer?.release()
        ctaRenderer = if (ctaOverlays.isEmpty()) null else CtaOverlayRenderer(context, ctaOverlays)
    }

    private fun pointerDistance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(textPlaybackTick)
        textPlaybackRunning = false
        ctaRenderer?.release()
        ctaRenderer = null
        super.onDetachedFromWindow()
    }
}
