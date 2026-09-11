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
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
        preview = CtaEditorPreviewView(this)
        preview.onOverlayChanged = { index, overlay ->
            if (index in overlays.indices) overlays[index] = overlay
        }
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val add = Button(this).apply { text = "ADD CTA VIDEO"; setOnClickListener { pickVideo() } }
        root.addView(add)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val done = Button(this).apply { text = "SAVE & DONE"; setOnClickListener { CtaOverlayStore.save(this@CtaOverlayActivity, overlays); finish() } }
        root.addView(done)
        setContentView(root)
        refreshList()
    }

    override fun onResume() { super.onResume(); preview.startPlayback() }
    override fun onPause() { preview.stopPlayback(); super.onPause() }

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
            // Existing MP4/native CTA path is deliberately unchanged.
            addOverlay(uri, null)
            return
        }

        // WebM alpha uses the libvpx VP9 decoder. It reads the WebM alpha sidecar
        // and emits real RGBA PNG frames; no WebView/browser path is involved.
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
        overlays.add(CtaOverlay(uri, 0L, duration, 0.5f, 0.5f, 0.25f, frameDir, CtaAlphaDecoder.FRAME_RATE))
        selectedIndex = overlays.lastIndex
        CtaOverlayStore.save(this, overlays)
        refreshList()
        preview.setOverlays(overlays, selectedIndex)
    }

    private fun refreshList() {
        list.removeAllViews()
        overlays.forEachIndexed { index, overlay ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val label = TextView(this).apply {
                text = "CTA ${index + 1}  •  ${overlay.startMs}ms → ${overlay.startMs + overlay.durationMs}ms"
                setPadding(8, 12, 8, 12)
                setOnClickListener { selectedIndex = index; preview.select(index) }
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
                    selectedIndex = selectedIndex.coerceIn(0, (overlays.size - 1).coerceAtLeast(0))
                    CtaOverlayStore.save(this@CtaOverlayActivity, overlays)
                    refreshList()
                    preview.setOverlays(overlays, selectedIndex)
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
        selectedIndex = index
        preview.select(index)
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
        overlays[index] = current.copy(
            startMs = start.valueLong(current.startMs).coerceAtLeast(0L),
            durationMs = duration.valueLong(current.durationMs).coerceAtLeast(1L),
            x = (x.valueFloat(current.x * 100f) / 100f).coerceIn(0f, 1f),
            y = (y.valueFloat(current.y * 100f) / 100f).coerceIn(0f, 1f),
            scale = (scale.valueFloat(current.scale * 100f) / 100f).coerceIn(0.03f, 1f)
        )
        CtaOverlayStore.save(this, overlays)
        refreshList()
    }
}
