package com.thedailyflare.reel

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class CtaOverlayActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var preview: CtaEditorPreviewView
    private var overlays = mutableListOf<CtaOverlay>()
    private var selectedIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overlays = CtaOverlayStore.load(this).toMutableList()
        selectedIndex = if (overlays.isEmpty()) -1 else CtaOverlayStore.loadSelectedIndex(this, overlays.size)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
        preview = CtaEditorPreviewView(this)
        preview.onOverlayChanged = { index, overlay ->
            if (index in overlays.indices) {
                // CTA library uses one shared canvas placement/size. Changing the
                // active CTA keeps every saved CTA ready to appear at the same spot.
                overlays = overlays.map { it.copy(x = overlay.x, y = overlay.y, scale = overlay.scale) }.toMutableList()
            }
        }
        preview.onOverlayInteractionFinished = {
            CtaOverlayStore.save(this, overlays)
            CtaOverlayStore.saveSelectedIndex(this, selectedIndex)
        }
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val add = Button(this).apply { text = "ADD CTA VIDEO"; setOnClickListener { pickVideo() } }
        root.addView(add)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val done = Button(this).apply { text = "SAVE & DONE"; setOnClickListener { saveAndFinish() } }
        root.addView(done)
        setContentView(root)
        refreshList()
    }

    override fun onResume() { super.onResume(); preview.startPlayback() }
    override fun onPause() { preview.stopPlayback(); super.onPause() }

    private fun saveAndFinish() {
        CtaOverlayStore.save(this, overlays)
        if (selectedIndex >= 0) CtaOverlayStore.saveSelectedIndex(this, selectedIndex)
        finish()
    }

    private fun pickVideo() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, 700)
    }

    @Deprecated("Deprecated in Android SDK")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 700 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }

        val displayName = queryDisplayName(uri).orEmpty().lowercase()
        val uriText = uri.toString().lowercase()
        val mime = runCatching { contentResolver.getType(uri).orEmpty().lowercase() }.getOrDefault("")
        val isWebm = displayName.endsWith(".webm") || uriText.contains(".webm") || mime.contains("webm")

        if (!isWebm) {
            addOverlay(uri, null)
            return
        }

        Toast.makeText(this, "Preparing WebM CTA…", Toast.LENGTH_SHORT).show()
        Thread {
            val frameDir = CtaAlphaDecoder.decode(this, uri)
            runOnUiThread {
                if (frameDir != null) {
                    val duration = runCatching {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(this, uri)
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                        } finally {
                            runCatching { retriever.release() }
                        }
                    }.getOrNull()?.coerceAtLeast(1L) ?: 3000L
                    addOverlay(uri, frameDir.absolutePath, duration)
                    Toast.makeText(this, "WebM CTA ready", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "WebM CTA could not be decoded", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun queryDisplayName(uri: android.net.Uri): String? {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor: Cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    private fun addOverlay(uri: android.net.Uri, frameDir: String?, durationOverrideMs: Long? = null) {
        val duration = durationOverrideMs?.coerceAtLeast(1L) ?: runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrNull()?.coerceAtLeast(1L) ?: 3000L

        // New library items inherit the current canvas placement and size.
        val placement = overlays.getOrNull(selectedIndex)
        val x = placement?.x ?: 0.5f
        val y = placement?.y ?: 0.5f
        val scale = placement?.scale ?: 0.25f
        overlays.add(CtaOverlay(uri, 0L, duration, x, y, scale, frameDir, CtaAlphaDecoder.FRAME_RATE))
        selectedIndex = overlays.lastIndex
        CtaOverlayStore.save(this, overlays)
        CtaOverlayStore.saveSelectedIndex(this, selectedIndex)
        refreshList()
    }

    private fun selectCta(index: Int) {
        if (index !in overlays.indices) return
        // Carry the current canvas placement/size to the newly selected CTA.
        // This makes the library behave like one active overlay slot.
        val current = overlays.getOrNull(selectedIndex)
        if (current != null && selectedIndex != index) {
            val target = overlays[index]
            overlays[index] = target.copy(x = current.x, y = current.y, scale = current.scale)
        }
        selectedIndex = index
        CtaOverlayStore.save(this, overlays)
        CtaOverlayStore.saveSelectedIndex(this, selectedIndex)
        preview.select(index)
        preview.setOverlays(overlays, selectedIndex)
    }

    private fun refreshList() {
        list.removeAllViews()
        overlays.forEachIndexed { index, overlay ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val label = TextView(this).apply {
                text = if (index == selectedIndex) "✓ CTA ${index + 1}  •  ACTIVE" else "CTA ${index + 1}"
                setPadding(8, 12, 8, 12)
                setOnClickListener { selectCta(index) }
            }
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val actions = LinearLayout(this)
            actions.addView(Button(this).apply {
                text = "EDIT"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(211, 47, 47))
                setOnClickListener { editOverlay(index) }
            })
            actions.addView(Button(this).apply {
                text = "DELETE"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(211, 47, 47))
                setOnClickListener {
                    overlays.removeAt(index)
                    selectedIndex = when {
                        overlays.isEmpty() -> -1
                        selectedIndex > index -> selectedIndex - 1
                        selectedIndex == index -> selectedIndex.coerceIn(0, overlays.lastIndex)
                        else -> selectedIndex
                    }
                    CtaOverlayStore.save(this@CtaOverlayActivity, overlays)
                    if (selectedIndex >= 0) CtaOverlayStore.saveSelectedIndex(this@CtaOverlayActivity, selectedIndex)
                    refreshList()
                }
            })
            row.addView(actions)
            list.addView(row)
        }
        preview.setOverlays(overlays, selectedIndex)
    }

    private fun field(label: String, value: String): EditText = EditText(this).apply {
        hint = label
        setText(value)
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
    }

    private fun EditText.valueLong(fallback: Long): Long = text.toString().trim().toLongOrNull() ?: fallback
    private fun EditText.valueFloat(fallback: Float): Float = text.toString().trim().toFloatOrNull() ?: fallback

    private fun editOverlay(index: Int) {
        selectCta(index)
        val o = overlays[index]
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 0) }
        val startField = field("Start time (ms)", o.startMs.toString())
        val durationField = field("Duration (ms)", o.durationMs.toString())
        val xField = field("X position (0-100%)", (o.x * 100).toInt().toString())
        val yField = field("Y position (0-100%)", (o.y * 100).toInt().toString())
        val scaleField = field("Size (0-100%)", (o.scale * 100).toInt().toString())
        listOf(startField, durationField, xField, yField, scaleField).forEach { box.addView(it) }
        AlertDialog.Builder(this).setTitle("Edit CTA ${index + 1}").setView(box)
            .setPositiveButton("SAVE") { _, _ -> applyFields(index, startField, durationField, xField, yField, scaleField) }
            .setNegativeButton("CANCEL", null).show()
    }

    private fun applyFields(index: Int, start: EditText, duration: EditText, x: EditText, y: EditText, scale: EditText) {
        val current = overlays.getOrNull(index) ?: return
        val newX = (x.valueFloat(current.x * 100f) / 100f).coerceIn(0f, 1f)
        val newY = (y.valueFloat(current.y * 100f) / 100f).coerceIn(0f, 1f)
        val newScale = (scale.valueFloat(current.scale * 100f) / 100f).coerceIn(0.03f, 1f)
        // Position and size are shared by the whole CTA library; timing remains per item.
        overlays = overlays.map { it.copy(x = newX, y = newY, scale = newScale) }.toMutableList()
        overlays[index] = overlays[index].copy(
            startMs = start.valueLong(current.startMs).coerceAtLeast(0L),
            durationMs = duration.valueLong(current.durationMs).coerceAtLeast(1L)
        )
        CtaOverlayStore.save(this, overlays)
        refreshList()
    }
}
