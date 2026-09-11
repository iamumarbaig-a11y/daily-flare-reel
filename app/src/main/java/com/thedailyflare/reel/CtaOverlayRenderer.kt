package com.thedailyflare.reel

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

    fun draw(canvas: Canvas, timelineMs: Long, width: Int, height: Int): Boolean {
        if (entries.isEmpty()) return false
        var drew = false
        entries.forEach { entry ->
            val o = entry.overlay
            val relative = timelineMs - o.startMs
            if (relative < 0L || relative >= o.durationMs) return@forEach
            val bitmap = frame(entry, relative) ?: return@forEach
            canvas.drawBitmap(bitmap, null, rectFor(o, bitmap, width, height), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            drew = true
        }
        return drew
    }

    /** Draw all configured CTAs at their first video frame so they remain editable even when the playhead is outside their schedule. */
    fun drawEditing(canvas: Canvas, width: Int, height: Int) {
        entries.forEach { entry ->
            val bitmap = frame(entry, 0L) ?: return@forEach
            canvas.drawBitmap(bitmap, null, rectFor(entry.overlay, bitmap, width, height), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
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

    fun hitTestEditing(x: Float, y: Float, width: Int, height: Int): Int {
        for (i in entries.indices.reversed()) {
            val entry = entries[i]
            val bitmap = frame(entry, 0L) ?: continue
            if (rectFor(entry.overlay, bitmap, width, height).contains(x, y)) return i
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
