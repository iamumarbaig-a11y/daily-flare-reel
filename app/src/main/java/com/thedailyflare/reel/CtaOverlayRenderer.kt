package com.thedailyflare.reel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import java.io.File
import java.util.LinkedHashMap

/** Renders scheduled CTA video overlays without rebuilding the decoder during transforms. */
class CtaOverlayRenderer(private val context: Context, overlays: List<CtaOverlay>) {
    private val entries = overlays.mapNotNull { createEntry(it) }.toMutableList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val cache = object : LinkedHashMap<String, Bitmap>(12, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            val remove = size > 10
            if (remove) eldest?.value?.recycle()
            return remove
        }
    }

    private fun createEntry(overlay: CtaOverlay): Entry? = runCatching {
        if (!overlay.frameDir.isNullOrBlank()) {
            Entry(overlay, null, overlay.durationMs.coerceAtLeast(1L))
        } else {
            val retriever = MediaMetadataRetriever().also { it.setDataSource(context, overlay.uri) }
            val sourceDurationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(1L) ?: 1L
            Entry(overlay, retriever, sourceDurationMs)
        }
    }.getOrNull()

    /** Update transform/timing data in-place. Decoder and frame cache stay alive. */
    fun updateOverlay(index: Int, overlay: CtaOverlay) {
        if (index !in entries.indices) return
        entries[index] = entries[index].copy(overlay = overlay)
    }

    fun draw(canvas: Canvas, timelineMs: Long, width: Int, height: Int): Boolean {
        if (entries.isEmpty()) return false
        var drew = false
        entries.forEach { entry ->
            val o = entry.overlay
            val relative = timelineMs - o.startMs
            if (relative < 0L || relative >= o.durationMs) return@forEach
            val bitmap = frame(entry, relative) ?: return@forEach
            canvas.drawBitmap(bitmap, null, rectFor(o, bitmap, width, height), paint)
            drew = true
        }
        return drew
    }

    fun drawEditing(canvas: Canvas, width: Int, height: Int) {
        if (entries.isEmpty()) return
        entries.forEach { entry ->
            val bitmap = frame(entry, 0L) ?: return@forEach
            canvas.drawBitmap(bitmap, null, rectFor(entry.overlay, bitmap, width, height), paint)
        }
    }

    /** Returns the topmost CTA whose visible rectangle contains the touch point at this timeline position. */
    fun hitTest(timelineMs: Long, x: Float, y: Float, width: Int, height: Int): Int {
        for (index in entries.indices.reversed()) {
            val entry = entries[index]
            val o = entry.overlay
            val relative = timelineMs - o.startMs
            if (relative < 0L || relative >= o.durationMs) continue
            val bitmap = frame(entry, relative) ?: continue
            if (rectFor(o, bitmap, width, height).contains(x, y)) return index
        }
        return -1
    }

    /** Hit-test using the first frame so a CTA can still be selected while outside its timeline window. */
    fun hitTestEditing(x: Float, y: Float, width: Int, height: Int): Int {
        for (index in entries.indices.reversed()) {
            val entry = entries[index]
            val bitmap = frame(entry, 0L) ?: continue
            if (rectFor(entry.overlay, bitmap, width, height).contains(x, y)) return index
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
        val o = entry.overlay
        if (!o.frameDir.isNullOrBlank()) {
            val files = runCatching {
                File(o.frameDir).listFiles { f -> f.isFile && f.extension.equals("png", ignoreCase = true) }
                    ?.sortedBy { it.name }
                    ?: emptyList()
            }.getOrDefault(emptyList())
            if (files.isNotEmpty()) {
                val frameIndex = ((relativeMs.coerceAtLeast(0L) * o.frameRate) / 1000f)
                    .toInt().coerceIn(0, files.lastIndex)
                val file = files[frameIndex]
                val key = "${o.frameDir}|${file.name}"
                cache[key]?.let { return it }
                val decoded = BitmapFactory.decodeFile(file.absolutePath) ?: return null
                if (decoded.config != Bitmap.Config.ARGB_8888) {
                    val rgba = decoded.copy(Bitmap.Config.ARGB_8888, true)
                    decoded.recycle()
                    cache[key] = rgba
                    return rgba
                }
                cache[key] = decoded
                return decoded
            }
        }

        val retriever = entry.retriever ?: return null
        val sourcePositionMs = if (entry.sourceDurationMs > 1L) relativeMs % entry.sourceDurationMs else relativeMs
        val bucket = (sourcePositionMs / 66L) * 66L
        val key = "${o.uri}|$bucket"
        cache[key]?.let { return it }
        val decoded = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(sourcePositionMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST, 540, 960)
            } else {
                retriever.getFrameAtTime(sourcePositionMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
            }
        }.getOrNull() ?: return null
        val prepared = makeChromaKeyTransparent(decoded)
        if (prepared !== decoded) decoded.recycle()
        cache[key] = prepared
        return prepared
    }

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
        entries.forEach { runCatching { it.retriever?.release() } }
        cache.values.forEach { runCatching { if (!it.isRecycled) it.recycle() } }
        cache.clear()
    }

    private data class Entry(
        val overlay: CtaOverlay,
        val retriever: MediaMetadataRetriever?,
        val sourceDurationMs: Long
    )
}