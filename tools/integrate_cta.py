from pathlib import Path

root = Path('.')

# Add a CTA Overlay entry point without changing the existing OUTRO flow.
p = root / 'app/src/main/java/com/thedailyflare/reel/MainActivity.kt'
s = p.read_text()
anchor = 'root.addView(twoColumnRow("" to button("IMAGE") { pickImages() }, "" to button("OUTRO") { pickImage(101) }), lp())'
if 'CtaOverlayActivity::class.java' not in s:
    if anchor not in s:
        raise SystemExit('MainActivity UI anchor not found')
    p.write_text(s.replace(anchor, anchor + '\n        root.addView(button("CTA OVERLAY") { startActivity(Intent(this, CtaOverlayActivity::class.java)) }, lp())', 1))

# Keep the existing export integration. The final 3-second OUTRO branch is untouched.
p = root / 'app/src/main/java/com/thedailyflare/reel/ReelEncoder.kt'
s = p.read_text()
marker = 'val settledMask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)'
if 'val ctaOverlayRenderer = CtaOverlayRenderer' not in s:
    if marker not in s:
        raise SystemExit('ReelEncoder bitmap marker not found')
    s = s.replace(marker, marker + '\n        val ctaOverlayRenderer = CtaOverlayRenderer(context, CtaOverlayStore.load(context))', 1)
text_anchor = '''drawAnimatedText(canvas, title, headlines, width, height, frame, titleDelayFrames,
                            headlineWordCounts, segmentFrames, textEffect, textEffectIntensity, textRevealMode,
                            animatedLayer, settledMask)'''
if 'ctaOverlayRenderer.draw(canvas' not in s:
    if text_anchor not in s:
        raise SystemExit('ReelEncoder text render anchor not found')
    s = s.replace(text_anchor, text_anchor + '\n                        ctaOverlayRenderer.draw(canvas, frame.toLong() * 1000L / fps.toLong(), width, height)', 1)
if 'ctaOverlayRenderer.release()' not in s:
    old = 'finally { try { input?.release() } catch (_: Exception) {}; try { surface?.release() } catch (_: Exception) {}; if (started) try { muxer.stop() } catch (_: Exception) {}; muxer.release(); try { codec.stop() } catch (_: Exception) {}; codec.release() }'
    new = 'finally { try { input?.release() } catch (_: Exception) {}; try { surface?.release() } catch (_: Exception) {}; if (started) try { muxer.stop() } catch (_: Exception) {}; muxer.release(); try { codec.stop() } catch (_: Exception) {}; codec.release(); ctaOverlayRenderer.release() }'
    if old not in s:
        raise SystemExit('ReelEncoder finally anchor not found')
    s = s.replace(old, new, 1)
p.write_text(s)

# Keep these files self-contained for the debug build.
manifest = root / 'app/src/debug/AndroidManifest.xml'
manifest.parent.mkdir(parents=True, exist_ok=True)
manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <activity android:name=".CtaOverlayActivity" android:exported="false" />
    </application>
</manifest>
''')

# Make the existing main preview interactive. It uses low-rate, scaled frame extraction
# only for CTA preview, while the normal reel preview remains the existing Canvas renderer.
preview = root / 'app/src/main/java/com/thedailyflare/reel/ReelPreviewView.kt'
preview.write_text(r'''package com.thedailyflare.reel

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
        val timelineMs = (visualProgress.coerceIn(0f, 1f) * timelineDurationMs.coerceAtLeast(1L)).toLong()
        ctaRenderer?.draw(canvas, timelineMs, width, height)
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

    private fun currentTimelineMs(): Long = (visualProgress.coerceIn(0f, 1f) * timelineDurationMs.coerceAtLeast(1L)).toLong()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (showCta) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                refreshCtaRendererIfNeeded()
                val index = ctaRenderer?.hitTest(currentTimelineMs(), event.x, event.y, width, height) ?: -1
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
''')

# Efficient live preview renderer: decode scaled frames at ~15fps, not full-resolution 60fps.
renderer = root / 'app/src/main/java/com/thedailyflare/reel/CtaOverlayRenderer.kt'
s = r'''package com.thedailyflare.reel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import java.util.LinkedHashMap

/** Renders scheduled CTA video overlays on top of the narration section. */
class CtaOverlayRenderer(private val context: Context, overlays: List<CtaOverlay>) {
    private val entries = overlays.mapNotNull { overlay ->
        runCatching { Entry(overlay, MediaMetadataRetriever().also { it.setDataSource(context, overlay.uri) }) }.getOrNull()
    }

    private val cache = object : LinkedHashMap<String, Bitmap>(12, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            val remove = size > 10
            if (remove) eldest?.value?.recycle()
            return remove
        }
    }

    fun draw(canvas: Canvas, timelineMs: Long, width: Int, height: Int) {
        if (entries.isEmpty()) return
        entries.forEach { entry ->
            val o = entry.overlay
            val relative = timelineMs - o.startMs
            if (relative < 0L || relative >= o.durationMs) return@forEach
            val bitmap = frame(entry, relative) ?: return@forEach
            canvas.drawBitmap(bitmap, null, rectFor(o, bitmap, width, height), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
    }

    fun hitTest(timelineMs: Long, x: Float, y: Float, width: Int, height: Int): Int {
        for (i in entries.indices.reversed()) {
            val entry = entries[i]
            val o = entry.overlay
            val relative = timelineMs - o.startMs
            if (relative < 0L || relative >= o.durationMs) continue
            val bitmap = frame(entry, relative) ?: continue
            if (rectFor(o, bitmap, width, height).contains(x, y)) return i
        }
        return -1
    }

    private fun rectFor(o: CtaOverlay, bitmap: Bitmap, width: Int, height: Int): RectF {
        val scale = o.scale.coerceIn(0.03f, 1f)
        val targetW = width * scale
        val aspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
        val targetH = targetW / aspect
        val cx = width * o.x.coerceIn(0f, 1f)
        val cy = height * o.y.coerceIn(0f, 1f)
        return RectF(cx - targetW / 2f, cy - targetH / 2f, cx + targetW / 2f, cy + targetH / 2f)
    }

    private fun frame(entry: Entry, relativeMs: Long): Bitmap? {
        val bucket = (relativeMs / 66L) * 66L
        val key = "${entry.overlay.uri}|$bucket"
        cache[key]?.let { return it }
        val decoded = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                entry.retriever.getScaledFrameAtTime(relativeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST, 540, 960)
            } else {
                entry.retriever.getFrameAtTime(relativeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
            }
        }.getOrNull() ?: return null
        val prepared = makeChromaKeyTransparent(decoded)
        if (prepared !== decoded) decoded.recycle()
        cache[key] = prepared
        return prepared
    }

    /** Removes green-screen backing and also handles pure-black backing assets. */
    private fun makeChromaKeyTransparent(source: Bitmap): Bitmap {
        val copy = source.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(copy.width * copy.height)
        copy.getPixels(pixels, 0, copy.width, 0, 0, copy.width, copy.height)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c ushr 16) and 0xff
            val g = (c ushr 8) and 0xff
            val b = c and 0xff
            val maxRb = maxOf(r, b)
            if (g >= 70 && g > maxRb + 18 && g.toFloat() > maxRb * 1.18f) {
                val strength = ((g - maxRb - 18) * 255 / 100).coerceIn(0, 255)
                pixels[i] = (strength shl 24) or (c and 0x00ffffff)
            } else if (r <= 18 && g <= 18 && b <= 18) {
                pixels[i] = c and 0x00ffffff
            }
        }
        copy.setPixels(pixels, 0, copy.width, 0, 0, copy.width, copy.height)
        return copy
    }

    fun release() {
        entries.forEach { runCatching { it.retriever.release() } }
        cache.values.forEach { runCatching { if (!it.isRecycled) it.recycle() } }
        cache.clear()
    }

    private data class Entry(val overlay: CtaOverlay, val retriever: MediaMetadataRetriever)
}
'''
renderer.write_text(s)

# Feed the actual visual-preview duration into the CTA timeline.
p = root / 'app/src/main/java/com/thedailyflare/reel/MainActivity.kt'
s = p.read_text()
old = 'private fun updateVisualPreview(progress: Float) { visualPreviewLabel.text = "VISUAL PREVIEW ${(progress * 100).toInt()}%";'
if old in s and 'preview.timelineDurationMs = visualPreviewDurationMs()' not in s:
    s = s.replace(old, 'private fun updateVisualPreview(progress: Float) { preview.timelineDurationMs = visualPreviewDurationMs(); visualPreviewLabel.text = "VISUAL PREVIEW ${(progress * 100).toInt()}%";', 1)
p.write_text(s)
