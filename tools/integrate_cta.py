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

# Wire scheduled overlays into the narration frames only. The existing 3-second
# OUTRO branch remains untouched.
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

# Green-screen renderer with black fallback.
p = root / 'app/src/main/java/com/thedailyflare/reel/CtaOverlayRenderer.kt'
p.write_text(r'''package com.thedailyflare.reel

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
        val decoded = runCatching { entry.retriever.getFrameAtTime(relativeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST) }.getOrNull() ?: return null
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
''')

# Self-contained editor using the platform ActivityResult-free picker API.
p = root / 'app/src/main/java/com/thedailyflare/reel/CtaOverlayActivity.kt'
p.write_text(r'''package com.thedailyflare.reel

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class CtaOverlayActivity : Activity() {
    private val overlays = mutableListOf<CtaOverlay>()
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overlays.addAll(CtaOverlayStore.load(this))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 28, 28, 28) }
        root.addView(TextView(this).apply { text = "CTA OVERLAYS"; textSize = 22f; setPadding(0, 0, 0, 14) })
        root.addView(TextView(this).apply { text = "Add green-screen CTA videos. Each overlay has its own start time, duration, position and size."; textSize = 15f; setPadding(0, 0, 0, 14) })
        root.addView(Button(this).apply { text = "ADD CTA VIDEO"; setOnClickListener { pickVideo() } }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Button(this).apply { text = "SAVE & DONE"; setOnClickListener { CtaOverlayStore.save(this@CtaOverlayActivity, overlays); finish() } }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        refreshList()
    }

    private fun pickVideo() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "video/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, 700)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 700 || resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        val duration = runCatching {
            val r = MediaMetadataRetriever()
            r.setDataSource(this, uri)
            val value = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 3000L
            r.release()
            value
        }.getOrDefault(3000L).coerceAtLeast(1L)
        overlays.add(CtaOverlay(uri, 0L, duration, 0.82f, 0.80f, 0.25f))
        refreshList()
    }

    private fun refreshList() {
        list.removeAllViews()
        overlays.forEachIndexed { index, overlay ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 14, 0, 14) }
            row.addView(TextView(this).apply { text = "CTA ${index + 1}: ${overlay.uri.lastPathSegment ?: "video"}"; textSize = 16f })
            row.addView(TextView(this).apply { text = "Start ${overlay.startMs}ms · Duration ${overlay.durationMs}ms · X ${(overlay.x * 100).toInt()}% · Y ${(overlay.y * 100).toInt()}% · Size ${(overlay.scale * 100).toInt()}%"; textSize = 13f })
            val actions = LinearLayout(this).apply { gravity = Gravity.END }
            actions.addView(Button(this@CtaOverlayActivity).apply { text = "EDIT"; setOnClickListener { editOverlay(index) } })
            actions.addView(Button(this@CtaOverlayActivity).apply { text = "DELETE"; setOnClickListener { overlays.removeAt(index); refreshList() } })
            row.addView(actions)
            list.addView(row)
        }
    }

    private fun editOverlay(index: Int) {
        val o = overlays[index]
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 0) }
        val start = field("Start time (ms)", o.startMs.toString())
        val duration = field("Duration (ms)", o.durationMs.toString())
        val x = field("X position (0-100%)", (o.x * 100).toInt().toString())
        val y = field("Y position (0-100%)", (o.y * 100).toInt().toString())
        val scale = field("Size (0-100%)", (o.scale * 100).toInt().toString())
        listOf(start, duration, x, y, scale).forEach { box.addView(it) }
        AlertDialog.Builder(this).setTitle("Edit CTA ${index + 1}").setView(box).setPositiveButton("SAVE") { _, _ ->
            overlays[index] = o.copy(
                startMs = start.value().toLongOrNull()?.coerceAtLeast(0L) ?: o.startMs,
                durationMs = duration.value().toLongOrNull()?.coerceAtLeast(1L) ?: o.durationMs,
                x = (x.value().toFloatOrNull()?.div(100f) ?: o.x).coerceIn(0f, 1f),
                y = (y.value().toFloatOrNull()?.div(100f) ?: o.y).coerceIn(0f, 1f),
                scale = (scale.value().toFloatOrNull()?.div(100f) ?: o.scale).coerceIn(0.03f, 1f)
            )
            CtaOverlayStore.save(this, overlays)
            refreshList()
        }.setNegativeButton("CANCEL", null).show()
    }

    private fun field(hint: String, value: String): EditText = EditText(this).apply {
        this.hint = hint
        setText(value)
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
    }
    private fun EditText.value(): String = text.toString().trim()
}
''')

manifest = root / 'app/src/debug/AndroidManifest.xml'
manifest.parent.mkdir(parents=True, exist_ok=True)
manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <activity android:name=".CtaOverlayActivity" android:exported="false" />
    </application>
</manifest>
''')
