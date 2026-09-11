package com.thedailyflare.reel

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** Live CTA video preview/editor. The selected CTA loops continuously while editing. */
class CtaEditorPreviewView(context: Context) : View(context) {
    private var overlays: List<CtaOverlay> = emptyList()
    private var selectedIndex = 0
    private var renderer: CtaOverlayRenderer? = null
    private var playing = false
    private var startedAt = 0L
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var originalX = 0f
    private var originalY = 0f
    private var lastPinchDistance = 0f
    private var originalScale = 0f
    private var interactionChanged = false

    var onOverlayChanged: ((Int, CtaOverlay) -> Unit)? = null
    var onOverlayInteractionFinished: (() -> Unit)? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!playing) return
            invalidate()
            postDelayed(this, 33L)
        }
    }

    fun setOverlays(value: List<CtaOverlay>, selected: Int = selectedIndex) {
        overlays = value.toList()
        selectedIndex = selected.coerceIn(0, (overlays.size - 1).coerceAtLeast(0))
        rebuildRenderer()
        if (overlays.isNotEmpty()) startPlayback() else stopPlayback()
        invalidate()
    }

    fun select(index: Int) {
        if (index !in overlays.indices) return
        if (selectedIndex == index) return
        selectedIndex = index
        // Selection is allowed to rebuild because it changes the media source.
        rebuildRenderer()
        startedAt = SystemClock.elapsedRealtime()
        invalidate()
    }

    fun getSelectedIndex(): Int = selectedIndex

    fun startPlayback() {
        removeCallbacks(tick)
        startedAt = SystemClock.elapsedRealtime()
        playing = true
        post(tick)
    }

    fun stopPlayback() {
        playing = false
        removeCallbacks(tick)
    }

    private fun rebuildRenderer() {
        renderer?.release()
        renderer = if (overlays.isEmpty()) null else {
            val selected = overlays[selectedIndex]
            CtaOverlayRenderer(context, listOf(selected.copy(startMs = 0L)))
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, if (width > 0) (width * 16f / 9f).toInt() else 0)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFF101010.toInt())
        if (overlays.isEmpty() || renderer == null) return
        val selected = overlays[selectedIndex]
        val duration = selected.durationMs.coerceAtLeast(1L)
        val elapsed = (SystemClock.elapsedRealtime() - startedAt) % duration
        val drawn = renderer?.draw(canvas, elapsed, width, height) == true
        if (!drawn) renderer?.drawEditing(canvas, width, height)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (overlays.isEmpty() || selectedIndex !in overlays.indices) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val o = overlays[selectedIndex]
                dragStartX = event.x
                dragStartY = event.y
                originalX = o.x
                originalY = o.y
                interactionChanged = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    lastPinchDistance = pointerDistance(event)
                    originalScale = overlays[selectedIndex].scale
                    interactionChanged = true
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val o = overlays[selectedIndex]
                val changed = if (event.pointerCount >= 2 && lastPinchDistance > 0f) {
                    val d = pointerDistance(event)
                    if (d <= 1f) false else {
                        val scale = (originalScale * (d / lastPinchDistance)).coerceIn(0.03f, 1f)
                        updateOverlay(o.copy(scale = scale))
                        true
                    }
                } else {
                    val x = (originalX + (event.x - dragStartX) / width.toFloat()).coerceIn(0f, 1f)
                    val y = (originalY + (event.y - dragStartY) / height.toFloat()).coerceIn(0f, 1f)
                    updateOverlay(o.copy(x = x, y = y))
                    true
                }
                if (changed) {
                    interactionChanged = true
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                lastPinchDistance = 0f
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                lastPinchDistance = 0f
                if (interactionChanged) onOverlayInteractionFinished?.invoke()
                interactionChanged = false
                return true
            }
        }
        return true
    }

    private fun updateOverlay(value: CtaOverlay) {
        overlays = overlays.toMutableList().also { it[selectedIndex] = value }
        // Position/scale changes only alter the transform. Keep the existing
        // MediaMetadataRetriever and decoded frame cache alive for smooth dragging.
        renderer?.updateOverlay(0, value)
        onOverlayChanged?.invoke(selectedIndex, value)
    }

    private fun pointerDistance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
    }

    override fun onDetachedFromWindow() {
        stopPlayback()
        renderer?.release()
        renderer = null
        super.onDetachedFromWindow()
    }
}
