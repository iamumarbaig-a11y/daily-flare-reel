package com.thedailyflare.reel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.util.LinkedHashMap

/**
 * Decodes CTA overlay video frames on demand for the existing frame renderer.
 * Coordinates are normalized to the 1080x1920 reel canvas.
 *
 * The renderer deliberately does not modify the existing outro path. It is only
 * used for overlays whose active interval falls inside the narration section.
 */
class CtaOverlayRenderer(
    private val context: Context,
    overlays: List<CtaOverlay>
) {
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
            val scale = o.scale.coerceIn(0.03f, 1f)
            val targetW = width * scale
            val aspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
            val targetH = targetW / aspect
            val cx = width * o.x.coerceIn(0f, 1f)
            val cy = height * o.y.coerceIn(0f, 1f)
            val dst = RectF(cx - targetW / 2f, cy - targetH / 2f, cx + targetW / 2f, cy + targetH / 2f)
            canvas.drawBitmap(bitmap, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
    }

    private fun frame(entry: Entry, relativeMs: Long): Bitmap? {
        val bucket = (relativeMs / 33L) * 33L
        val key = "${entry.overlay.uri}|$bucket"
        cache[key]?.let { return it }
        val decoded = runCatching {
            entry.retriever.getFrameAtTime(relativeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
        }.getOrNull() ?: return null
        val prepared = makeDarkBackgroundTransparent(decoded)
        if (prepared !== decoded) decoded.recycle()
        cache[key] = prepared
        return prepared
    }

    /**
     * Android decoders do not expose alpha consistently for every video codec.
     * CTA assets commonly arrive as RGB video with a pure black backing layer,
     * so remove near-black pixels while retaining antialiased edge alpha.
     * True alpha-capable sources are left visually unchanged except for this
     * safe near-black keying step.
     */
    private fun makeDarkBackgroundTransparent(source: Bitmap): Bitmap {
        if (source.config == Bitmap.Config.ARGB_8888) {
            val copy = source.copy(Bitmap.Config.ARGB_8888, true)
            val pixels = IntArray(copy.width * copy.height)
            copy.getPixels(pixels, 0, copy.width, 0, 0, copy.width, copy.height)
            for (i in pixels.indices) {
                val c = pixels[i]
                val r = (c ushr 16) and 0xff
                val g = (c ushr 8) and 0xff
                val b = c and 0xff
                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                if (max <= 18) {
                    pixels[i] = c and 0x00ffffff
                } else if (max <= 45 && max - min <= 10) {
                    val alpha = ((max - 18) * 255 / 27).coerceIn(0, 255)
                    pixels[i] = (alpha shl 24) or (c and 0x00ffffff)
                }
            }
            copy.setPixels(pixels, 0, copy.width, 0, 0, copy.width, copy.height)
            return copy
        }
        return source.copy(Bitmap.Config.ARGB_8888, true)
    }

    fun release() {
        entries.forEach { runCatching { it.retriever.release() } }
        cache.values.forEach { runCatching { if (!it.isRecycled) it.recycle() } }
        cache.clear()
    }

    private data class Entry(
        val overlay: CtaOverlay,
        val retriever: MediaMetadataRetriever
    )
}
