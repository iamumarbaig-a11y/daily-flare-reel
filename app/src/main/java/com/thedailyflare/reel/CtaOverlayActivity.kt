package com.thedailyflare.reel

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
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
    private lateinit var preview: CtaEditorPreviewView
    private var selectedIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overlays.addAll(CtaOverlayStore.load(this).map { overlay ->
            val detected = detectDurationMs(overlay.uri)
            if (overlay.durationMs < 100L && detected > 100L) overlay.copy(durationMs = detected) else overlay
        })
        CtaOverlayStore.save(this, overlays)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        root.addView(TextView(this).apply {
            text = "CTA OVERLAYS"
            textSize = 22f
            setPadding(0, 0, 0, 8)
        })
        root.addView(TextView(this).apply {
            text = "Drag the CTA directly on the preview. Pinch to resize. It loops so you can see the video animation."
            textSize = 14f
            setPadding(0, 0, 0, 10)
        })

        preview = CtaEditorPreviewView(this)
        preview.onOverlayChanged = { index, value ->
            if (index in overlays.indices) {
                overlays[index] = value
                CtaOverlayStore.save(this, overlays)
                refreshList()
            }
        }
        root.addView(preview, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(Button(this).apply {
            text = "ADD CTA VIDEO"
            setOnClickListener { pickVideo() }
        }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Button(this).apply {
            text = "SAVE & DONE"
            setOnClickListener { saveAndFinish() }
        }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        refreshList()
        preview.setOverlays(overlays, selectedIndex)
    }

    private fun saveAndFinish() {
        CtaOverlayStore.save(this, overlays)
        setResult(RESULT_OK)
        finish()
    }

    override fun onPause() {
        CtaOverlayStore.save(this, overlays)
        super.onPause()
    }

    private fun pickVideo() {
        // Some Android document providers do not advertise WebM as video/*.
        // Use the explicit MIME list with */* so WebM files are still selectable.
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "video/mp4",
                "video/webm",
                "video/x-webm",
                "video/3gpp",
                "video/quicktime",
                "video/x-matroska",
                "video/*"
            ))
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, 700)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 700 || resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        val duration = detectDurationMs(uri).coerceAtLeast(1000L)
        overlays.add(CtaOverlay(uri, 0L, duration, 0.50f, 0.50f, 0.25f))
        selectedIndex = overlays.lastIndex
        CtaOverlayStore.save(this, overlays)
        refreshList()
        preview.setOverlays(overlays, selectedIndex)
    }

    private fun detectDurationMs(uri: Uri): Long {
        val retrieverDuration = runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(this, uri)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }
        }.getOrDefault(0L)
        if (retrieverDuration >= 100L) return retrieverDuration

        val playerDuration = runCatching {
            MediaPlayer.create(this, uri)?.let { player ->
                val d = player.duration.toLong()
                player.release()
                d
            } ?: 0L
        }.getOrDefault(0L)
        return playerDuration.coerceAtLeast(0L)
    }

    private fun refreshList() {
        list.removeAllViews()
        overlays.forEachIndexed { index, overlay ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 10, 0, 10)
                setOnClickListener {
                    selectedIndex = index
                    preview.setOverlays(overlays, selectedIndex)
                    refreshList()
                }
            }
            row.addView(TextView(this).apply {
                text = "CTA ${index + 1}: ${overlay.uri.lastPathSegment ?: "video"}${if (index == selectedIndex) "  ← SELECTED" else ""}"
                textSize = 16f
            })
            row.addView(TextView(this).apply {
                text = "Start ${overlay.startMs}ms · Duration ${overlay.durationMs}ms · X ${(overlay.x * 100).toInt()}% · Y ${(overlay.y * 100).toInt()}% · Size ${(overlay.scale * 100).toInt()}%"
                textSize = 13f
            })
            val actions = LinearLayout(this).apply { gravity = Gravity.END }
            actions.addView(Button(this@CtaOverlayActivity).apply {
                text = "EDIT"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(211, 47, 47))
                setOnClickListener { editOverlay(index) }
            })
            actions.addView(Button(this@CtaOverlayActivity).apply {
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
    }

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
        val dialog = AlertDialog.Builder(this)
            .setTitle("Edit CTA ${index + 1}")
            .setView(box)
            .setPositiveButton("SAVE") { _, _ ->
                applyFields(index, startField, durationField, xField, yField, scaleField)
            }
            .setNegativeButton("CANCEL", null)
            .create()

        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, fieldStart: Int, before: Int, count: Int) {
                val current = overlays.getOrNull(index) ?: return
                overlays[index] = current.copy(
                    startMs = startField.valueLong(current.startMs),
                    durationMs = durationField.valueLong(current.durationMs).coerceAtLeast(1L),
                    x = (xField.valueFloat(current.x * 100f) / 100f).coerceIn(0f, 1f),
                    y = (yField.valueFloat(current.y * 100f) / 100f).coerceIn(0f, 1f),
                    scale = (scaleField.valueFloat(current.scale * 100f) / 100f).coerceIn(0.03f, 1f)
                )
                CtaOverlayStore.save(this@CtaOverlayActivity, overlays)
                preview.setOverlays(overlays, index)
                refreshList()
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        }
        listOf(startField, durationField, xField, yField, scaleField).forEach { it.addTextChangedListener(watcher) }
        dialog.show()
    }

    private fun applyFields(index: Int, start: EditText, duration: EditText, x: EditText, y: EditText, scale: EditText) {
        val o = overlays[index]
        overlays[index] = o.copy(
            startMs = start.valueLong(o.startMs),
            durationMs = duration.valueLong(o.durationMs).coerceAtLeast(1L),
            x = (x.valueFloat(o.x * 100f) / 100f).coerceIn(0f, 1f),
            y = (y.valueFloat(o.y * 100f) / 100f).coerceIn(0f, 1f),
            scale = (scale.valueFloat(o.scale * 100f) / 100f).coerceIn(0.03f, 1f)
        )
        CtaOverlayStore.save(this, overlays)
        refreshList()
        preview.setOverlays(overlays, index)
    }

    private fun field(hint: String, value: String): EditText = EditText(this).apply {
        this.hint = hint
        setText(value)
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
    }

    private fun EditText.valueLong(fallback: Long): Long = text.toString().trim().toLongOrNull()?.coerceAtLeast(0L) ?: fallback
    private fun EditText.valueFloat(fallback: Float): Float = text.toString().trim().toFloatOrNull() ?: fallback
}
