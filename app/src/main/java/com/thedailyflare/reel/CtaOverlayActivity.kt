package com.thedailyflare.reel

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
