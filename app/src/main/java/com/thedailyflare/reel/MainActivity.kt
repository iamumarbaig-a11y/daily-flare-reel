package com.thedailyflare.reel

import android.app.Activity
import android.content.ContentValues
import android.content.SharedPreferences
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.media.MediaPlayer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.view.ViewGroup
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.ProgressBar
import android.widget.FrameLayout
import android.widget.Toast
import android.widget.SeekBar
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.concurrent.thread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var preview: ReelPreviewView
    private lateinit var mainImageLabel: TextView
    private lateinit var ctaImageLabel: TextView
    private lateinit var imageStrip: LinearLayout
    private lateinit var musicLabel: TextView
    private lateinit var voiceSpinner: Spinner
    private lateinit var speedSpinner: Spinner
    private lateinit var voiceTts: VoiceTts
    private val voiceSpeeds = listOf(0.75f, 0.9f, 1.0f, 1.1f, 1.25f, 1.5f)
    private lateinit var voiceStatus: TextView
    private lateinit var kokoroSetupRow: LinearLayout
    private lateinit var openPkgButton: Button
    private lateinit var testButton: Button
    private lateinit var exportStatus: TextView
    private lateinit var exportProgress: ProgressBar
    private var voicePlayer: MediaPlayer? = null
    private var ctaPreviewPlayer: MediaPlayer? = null
    private val ctaText = "FOLLOW THE DAILY FLARE ON SOCIAL MEDIA."
    private var voiceOptions = emptyList<VoiceTts.VoiceOption>()
    private var selectedVoice: VoiceTts.VoiceOption? = null
    private lateinit var titleInput: EditText
    private lateinit var bulkHeadingsInput: EditText
    private val headlineInputs = mutableListOf<EditText>()
    private lateinit var previewFrame: FrameLayout
    private lateinit var visualPreviewSlider: SeekBar
    private lateinit var visualPreviewLabel: TextView
    private lateinit var visualPreviewPlayButton: Button
    private var visualPreviewPlaying = false
    private var visualPreviewStartedAtMs = 0L
    private var visualPreviewStartProgress = 0
    private val visualPreviewTick = object : Runnable {
        override fun run() {
            if (!visualPreviewPlaying) return
            val elapsed = SystemClock.elapsedRealtime() - visualPreviewStartedAtMs
            val duration = visualPreviewDurationMs()
            val next = (visualPreviewStartProgress + (elapsed.toFloat() / duration.toFloat() * 100f).toInt()).coerceIn(0, 100)
            visualPreviewSlider.progress = next
            if (next >= 100) stopVisualPreview(resetIcon = true) else preview.postDelayed(this, 16L)
        }
    }
    private var mainBitmap: Bitmap? = null
    private val reelBitmaps = mutableListOf<Bitmap>()
    private val reelImageUris = mutableListOf<Uri>()
    private val imageEffects = mutableListOf<ReelEncoder.ImageEffect>()
    private val imageEffectIntensities = mutableListOf<Float>()
    private var ctaBitmap: Bitmap? = null
    private var musicUri: Uri? = null
    private lateinit var musicIntensitySlider: SeekBar
    private lateinit var musicIntensityLabel: TextView
    private lateinit var preferences: SharedPreferences

    private var lastSavedOutputUri: Uri? = null
    private var exportStartedAtMs: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences("daily_flare_reel_preferences", MODE_PRIVATE)
        buildUi()
        restorePersistentSelections()
        restoreTemporaryEditorState(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        if (::voiceTts.isInitialized && ::voiceStatus.isInitialized) refreshKokoroState()
        if (::preview.isInitialized) preview.postDelayed({ preview.invalidate() }, 50L)
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 24, 32, 32) }
        scroll.addView(root)
        previewFrame = FrameLayout(this)
        preview = ReelPreviewView(this).apply { setBackgroundColor(0xFFEFEFEF.toInt()) }
        previewFrame.addView(preview, FrameLayout.LayoutParams(-1, -2))
        preview.setOnClickListener { preview.playTextPreview() }

        titleInput = edit("Main heading", 2).apply { addTextChangedListener(refreshWatcher()) }
        for (i in 1..7) headlineInputs.add(edit("Subheading $i", 2).apply { addTextChangedListener(refreshWatcher()) })

        root.addView(previewFrame, lp())
        visualPreviewLabel = label("VISUAL PREVIEW 0%")
        root.addView(visualPreviewLabel, lp())
        val previewControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        visualPreviewSlider = SeekBar(this).apply { max = 100; progress = 0 }
        visualPreviewSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { updateVisualPreview(progress / 100f) }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { stopVisualPreview(resetIcon = true) }
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        previewControls.addView(visualPreviewSlider, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        visualPreviewPlayButton = button("▶") { toggleVisualPreview() }.apply { contentDescription = "Play visual preview"; minHeight = dp(48); minWidth = dp(58) }
        previewControls.addView(visualPreviewPlayButton, LinearLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        root.addView(previewControls, lp())
        root.addView(twoColumnRow("" to button("IMAGE") { pickImages() }, "" to button("OUTRO") { pickImage(101) }), lp())
        root.addView(button("CTA OVERLAY") { startActivity(Intent(this, CtaOverlayActivity::class.java)) }, lp())
        mainImageLabel = label("No images selected"); root.addView(mainImageLabel, lp())
        imageStrip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        root.addView(imageStrip, lp())
        ctaImageLabel = label("No outro selected"); root.addView(ctaImageLabel, lp())
        section(root, "3. KOKORO AI VOICE")
        voiceStatus = label("Checking local Kokoro package...")
        voiceSpinner = Spinner(this); speedSpinner = Spinner(this)
        speedSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voiceSpeeds.map { "${it}×" })
        val savedSpeed = preferences.getFloat("voice_speed", 1.0f)
        speedSpinner.setSelection(voiceSpeeds.indexOf(savedSpeed).takeIf { it >= 0 } ?: 2, false)
        speedSpinner.onItemSelectedListener = simpleSelectionListener { position -> preferences.edit().putFloat("voice_speed", voiceSpeeds.getOrElse(position) { 1.0f }).apply() }
        root.addView(twoColumnRow("VOICE" to voiceSpinner, "SPEED" to speedSpinner), lp())
        root.addView(voiceStatus, lp())
        root.addView(label("PASTE ALL 8 HEADINGS"), lp())
        bulkHeadingsInput = edit("Paste the 8 numbered headings here", 8)
        root.addView(bulkHeadingsInput, lp())
        root.addView(button("DISTRIBUTE TO FIELDS") { distributeBulkHeadings() }, lp())
        root.addView(titleInput, lp())
        headlineInputs.forEach { root.addView(it, lp()) }
        voiceTts = VoiceTts(this)
        voiceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) { selectedVoice = null }
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                selectedVoice = voiceOptions.getOrNull(position)
                selectedVoice?.name?.let { preferences.edit().putString("voice_name", it).apply() }
            }
        }
        testButton = button("TEST") { testVoice() }
        openPkgButton = button("OPEN PKG") { startActivity(Intent(this, KokoroExperimentActivity::class.java)) }
        kokoroSetupRow = twoColumnRow("" to testButton, "" to openPkgButton)
        root.addView(kokoroSetupRow, lp())
        refreshKokoroState()

        section(root, "4. MUSIC")
        val musicControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        musicControls.addView(button("CHOOSE MUSIC") { pickAudio() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        musicIntensityLabel = label("5%")
        musicControls.addView(musicIntensityLabel, LinearLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_VERTICAL })
        root.addView(musicControls, lp())
        musicIntensitySlider = SeekBar(this).apply {
            max = 19
            val saved = preferences.getInt("music_intensity", 5).coerceIn(1, 20)
            progress = saved - 1
            musicIntensityLabel.text = "$saved%"
        }
        musicIntensitySlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = (progress + 1).coerceIn(1, 20)
                musicIntensityLabel.text = "$value%"
                if (fromUser) preferences.edit().putInt("music_intensity", value).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        root.addView(musicIntensitySlider, lp())
        musicLabel = label("No music selected"); root.addView(musicLabel, lp())
        exportStatus = label("Ready to export"); root.addView(exportStatus, lp())
        exportProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0 }
        root.addView(exportProgress, lp())
        root.addView(button("EXPORT REEL") { exportReel() }, lp())
        setContentView(scroll)
    }

    private fun refreshWatcher() = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refreshPreview() }
        override fun afterTextChanged(s: Editable?) = Unit
    }

    private fun distributeBulkHeadings() {
        val lines = bulkHeadingsInput.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.map { it.replace(Regex("^\\d+[.)]\\s*"), "") }.filter { it.isNotBlank() }
        if (lines.size != 8) { toast("Expected exactly 8 headings, found ${lines.size}"); return }
        titleInput.setText(lines[0])
        headlineInputs.forEachIndexed { index, input -> input.setText(lines.getOrNull(index + 1).orEmpty()) }
        refreshPreview(); toast("8 headings distributed to the fields")
    }

    private fun refreshKokoroState() {
        val previousName = selectedVoice?.name
        voiceTts.initialize({ options -> runOnUiThread {
            voiceOptions = options
            voiceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options.map { it.label })
            val persistedVoiceName = preferences.getString("voice_name", null)
            val restoredIndex = options.indexOfFirst { it.name == (persistedVoiceName ?: previousName) }.takeIf { it >= 0 } ?: 0
            if (options.isNotEmpty()) { voiceSpinner.setSelection(restoredIndex, false); selectedVoice = options[restoredIndex] } else selectedVoice = null
            voiceStatus.text = "Kokoro is ready locally — ${options.size} voices available"
            openPkgButton.visibility = android.view.View.GONE; testButton.visibility = android.view.View.GONE; kokoroSetupRow.visibility = android.view.View.GONE; voiceStatus.visibility = android.view.View.GONE
        } }, { error -> runOnUiThread {
            voiceOptions = emptyList(); selectedVoice = null
            voiceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, emptyList<String>())
            voiceStatus.text = error; voiceStatus.visibility = android.view.View.VISIBLE; openPkgButton.visibility = android.view.View.VISIBLE; testButton.visibility = android.view.View.VISIBLE; kokoroSetupRow.visibility = android.view.View.VISIBLE
        } })
    }

    private fun edit(hint: String, lines: Int) = EditText(this).apply { this.hint = hint; setSingleLine(lines == 1); maxLines = lines; textSize = 17f; setPadding(dp(14), dp(10), dp(14), dp(10)) }
    private fun twoColumnRow(left: Pair<String, android.view.View>, right: Pair<String, android.view.View>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP
        fun column(item: Pair<String, android.view.View>) = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; addView(TextView(this@MainActivity).apply { text = item.first; textSize = 13f; setTextColor(0xFF5D646B.toInt()); setPadding(0, 0, 0, dp(2)) }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)); addView(item.second, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        addView(column(left), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }); addView(column(right), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
    }
    private fun section(root: LinearLayout, value: String) { root.addView(TextView(this).apply { text = value; textSize = 18f; setTextColor(0xFF172A3A.toInt()); setPadding(0, 20, 0, 8) }, lp()) }
    private fun button(t: String, action: () -> Unit) = Button(this).apply { text = t; textSize = 15f; setTextColor(0xFFFFFFFF.toInt()); isAllCaps = false; typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD); minHeight = dp(52); setPadding(dp(20), 0, dp(20), 0); background = GradientDrawable().apply { setColor(0xFF172A3A.toInt()); cornerRadius = dp(18).toFloat() }; elevation = dp(3).toFloat(); setOnClickListener { action() } }
    private fun label(t: String) = TextView(this).apply { text = t; textSize = 16f; setTextColor(0xFF5D646B.toInt()); setPadding(0, dp(6), 0, dp(6)) }
    private fun lp() = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4); bottomMargin = dp(4) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun pickImage(code: Int) { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }, code) }
    private fun pickImages() { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }, 100) }
    private fun pickAudio() { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "audio/*"; addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }, 102) }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data); if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            100 -> {
                reelBitmaps.clear(); reelImageUris.clear(); imageEffects.clear(); imageEffectIntensities.clear(); val uris = mutableListOf<Uri>()
                data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.itemAt(i).uri) } ?: data.data?.let { uris.add(it) }
                uris.forEach { uri -> try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}; decodePortrait(uri)?.let { reelBitmaps.add(it); reelImageUris.add(uri); imageEffects.add(ReelEncoder.ImageEffect.PAN_ZOOM); imageEffectIntensities.add(0.5f) } }
                refreshImageStrip(); refreshPreview()
            }
            101 -> { data.data?.let { uri -> try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}; ctaBitmap?.recycle(); ctaBitmap = decodePortrait(uri); ctaImageLabel.text = "Outro selected"; refreshPreview() } }
            102 -> { data.data?.let { uri -> try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}; musicUri = uri; musicLabel.text = "Music selected" } }
        }
    }

    private fun refreshImageStrip() {
        imageStrip.removeAllViews()
        reelBitmaps.forEachIndexed { index, bitmap ->
            val image = ImageView(this).apply { setImageBitmap(bitmap); scaleType = ImageView.ScaleType.CENTER_CROP; layoutParams = LinearLayout.LayoutParams(dp(84), dp(120)).apply { marginEnd = dp(8) } }
            image.setOnClickListener { showImageOptions(index) }
            imageStrip.addView(image)
        }
    }

    private fun showImageOptions(index: Int) {
        val labels = arrayOf("PAN + ZOOM", "NONE", "ZOOM IN", "ZOOM OUT")
        android.app.AlertDialog.Builder(this).setTitle("Image ${index + 1}").setItems(labels) { _, which ->
            imageEffects[index] = when (which) { 1 -> ReelEncoder.ImageEffect.NONE; 2 -> ReelEncoder.ImageEffect.ZOOM_IN; 3 -> ReelEncoder.ImageEffect.ZOOM_OUT; else -> ReelEncoder.ImageEffect.PAN_ZOOM }
            refreshPreview()
        }.show()
    }

    private fun visualPreviewDurationMs(): Long {
        val imageCount = reelBitmaps.size.coerceAtLeast(1)
        return (imageCount * 3000L + 3000L).coerceAtLeast(1000L)
    }

    private fun updateVisualPreview(progress: Float) {
        visualPreviewLabel.text = "VISUAL PREVIEW ${(progress * 100f).toInt()}%"
        preview.setPreviewProgress(progress)
    }

    private fun toggleVisualPreview() {
        if (visualPreviewPlaying) { stopVisualPreview(resetIcon = true); return }
        visualPreviewPlaying = true
        visualPreviewStartedAtMs = SystemClock.elapsedRealtime()
        visualPreviewStartProgress = visualPreviewSlider.progress
        visualPreviewPlayButton.text = "⏸"
        preview.post(visualPreviewTick)
    }

    private fun stopVisualPreview(resetIcon: Boolean) {
        visualPreviewPlaying = false
        preview.removeCallbacks(visualPreviewTick)
        if (resetIcon) visualPreviewPlayButton.text = "▶"
    }

    private fun restorePersistentSelections() {
        musicUri = preferences.getString("music_uri", null)?.let(Uri::parse)
        if (musicUri != null) musicLabel.text = "Music selected"
    }

    private fun restoreTemporaryEditorState(savedInstanceState: Bundle?) {
        titleInput.setText(savedInstanceState?.getString("title") ?: "")
        headlineInputs.forEachIndexed { index, input -> input.setText(savedInstanceState?.getString("headline_$index") ?: "") }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("title", titleInput.text.toString())
        headlineInputs.forEachIndexed { index, input -> outState.putString("headline_$index", input.text.toString()) }
        super.onSaveInstanceState(outState)
    }

    private fun refreshPreview() {
        if (!::preview.isInitialized) return
        preview.setTextContent(titleInput.text.toString(), headlineInputs.map { it.text.toString() })
        preview.invalidate()
    }

    private fun testVoice() {
        val text = listOf(titleInput.text.toString(), *headlineInputs.map { it.text.toString() }.toTypedArray()).filter { it.isNotBlank() }.joinToString(". ")
        if (text.isBlank()) { toast("Enter headings first"); return }
        voicePlayer?.release()
        voicePlayer = voiceTts.speak(text, selectedVoice, preferences.getFloat("voice_speed", 1.0f))
    }

    private fun exportReel() {
        if (reelBitmaps.isEmpty()) { toast("Select images first"); return }
        exportStatus.text = "Exporting..."
        exportProgress.progress = 0
        exportStartedAtMs = SystemClock.elapsedRealtime()
        thread {
            try {
                val output = ReelEncoder(this).encode(
                    images = reelBitmaps,
                    imageEffects = imageEffects,
                    imageEffectIntensities = imageEffectIntensities,
                    title = titleInput.text.toString(),
                    headlines = headlineInputs.map { it.text.toString() },
                    musicUri = musicUri,
                    musicVolume = preferences.getInt("music_intensity", 5) / 100f,
                    onProgress = { p -> runOnUiThread { exportProgress.progress = p } }
                )
                runOnUiThread { lastSavedOutputUri = output; exportStatus.text = "Export complete"; toast("Reel exported") }
            } catch (e: Exception) {
                runOnUiThread { exportStatus.text = "Export failed: ${e.message ?: "Unknown error"}"; toast("Export failed") }
            }
        }
    }

    override fun onDestroy() {
        stopVisualPreview(resetIcon = false)
        voicePlayer?.release(); voicePlayer = null
        ctaPreviewPlayer?.release(); ctaPreviewPlayer = null
        super.onDestroy()
    }

    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }

    private fun decodePortrait(uri: Uri): Bitmap? = try {
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) { null }
}
