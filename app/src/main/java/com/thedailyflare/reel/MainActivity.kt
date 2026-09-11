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
        root.addView(label("PASTE ALL 7 HEADINGS"), lp())
        bulkHeadingsInput = edit("Paste the 7 numbered headings here", 8)
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
        if (lines.size != 7) { toast("Expected exactly 7 headings, found ${lines.size}"); return }
        titleInput.setText(lines[0])
        headlineInputs.forEachIndexed { index, input -> input.setText(lines.getOrNull(index + 1).orEmpty()) }
        refreshPreview(); toast("7 headings distributed to the fields")
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
                data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) } ?: data.data?.let { uris.add(it) }
                uris.forEach { uri -> try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}; decodePortrait(uri)?.let { reelBitmaps.add(it); reelImageUris.add(uri); imageEffects.add(ReelEncoder.ImageEffect.ZOOM_IN); imageEffectIntensities.add(0.18f) } }
                mainBitmap = reelBitmaps.firstOrNull(); preview.backgroundBitmap = mainBitmap; mainImageLabel.text = when (reelBitmaps.size) { 0 -> "No images selected"; 1 -> "1 image selected"; else -> "${reelBitmaps.size} images selected" }; renderImageThumbnails(); refreshPreview()
            }
            101 -> { val uri = data.data ?: return; try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}; ctaBitmap = decodePortrait(uri); preview.ctaBitmap = ctaBitmap; ctaImageLabel.text = "Outro selected"; preferences.edit().putString("cta_uri", uri.toString()).apply(); preview.invalidate() }
            102 -> { val uri = data.data ?: return; try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}; musicUri = uri; musicLabel.text = "Music selected"; preferences.edit().putString("music_uri", uri.toString()).apply() }
        }
    }

    private fun renderImageThumbnails() {
        imageStrip.removeAllViews(); val size = dp(58); val radius = dp(10).toFloat()
        reelBitmaps.forEachIndexed { index, bitmap ->
            val wrapper = FrameLayout(this).apply { background = GradientDrawable().apply { setColor(0xFF172A3A.toInt()); cornerRadius = radius }; setPadding(dp(2), dp(2), dp(2), dp(2)); elevation = dp(2).toFloat() }
            val image = ImageView(this).apply { setImageBitmap(bitmap); scaleType = ImageView.ScaleType.CENTER_CROP; contentDescription = "Reel image ${index + 1}" }
            wrapper.addView(image, FrameLayout.LayoutParams(-1, -1)); wrapper.setOnClickListener { showImagePreview(index) }
            imageStrip.addView(wrapper, LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(8) })
        }
    }

    private fun showImagePreview(index: Int) {
        val bitmap = reelBitmaps.getOrNull(index) ?: return
        val effects = ReelEncoder.ImageEffect.values()
        val dialog = android.app.AlertDialog.Builder(this).create()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(16)) }
        root.addView(ImageView(this).apply { setImageBitmap(bitmap); scaleType = ImageView.ScaleType.CENTER_CROP }, LinearLayout.LayoutParams(-1, dp(360)))
        root.addView(label("EFFECT"))
        val effectSpinner = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, effects.map { it.name.replace('_',' ') }); setSelection(effects.indexOf(imageEffects.getOrElse(index) { ReelEncoder.ImageEffect.ZOOM_IN }).coerceAtLeast(0)) }
        root.addView(effectSpinner); root.addView(label("INTENSITY"))
        val valueLabel = label("${(imageEffectIntensities.getOrElse(index){0.18f} * 100).toInt()}%")
        val slider = SeekBar(this).apply { max = 50; progress = (imageEffectIntensities.getOrElse(index){0.18f} * 100).toInt().coerceIn(0,50) }
        slider.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { valueLabel.text = "$progress%" }; override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit; override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit })
        root.addView(slider); root.addView(valueLabel)
        root.addView(button("APPLY") { imageEffects[index] = effects[effectSpinner.selectedItemPosition]; imageEffectIntensities[index] = slider.progress / 100f; dialog.dismiss() })
        dialog.setView(root); dialog.show()
    }

    private fun restorePersistentSelections() { preferences.getString("cta_uri", null)?.let { restoreCta(Uri.parse(it)) }; preferences.getString("music_uri", null)?.let { restoreMusic(Uri.parse(it)) } }
    private fun restoreCta(uri: Uri) { val bitmap = decodePortrait(uri) ?: return; ctaBitmap = bitmap; preview.ctaBitmap = bitmap; ctaImageLabel.text = "Outro selected"; preview.invalidate() }
    private fun restoreMusic(uri: Uri) { try { contentResolver.openInputStream(uri)?.close(); musicUri = uri; musicLabel.text = "Music selected" } catch (_: Exception) {} }
    private fun simpleSelectionListener(onSelected: (Int) -> Unit) = object : android.widget.AdapterView.OnItemSelectedListener { override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit; override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) = onSelected(position) }
    private fun decodePortrait(uri: Uri): Bitmap? {
        return try {
            val decoded = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
            val orientation = contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } ?: ExifInterface.ORIENTATION_NORMAL
            val matrix = Matrix(); when (orientation) { ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f); ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f); ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f); ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }; ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f); ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }; ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f) }
            val oriented = if (orientation == ExifInterface.ORIENTATION_NORMAL) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
            centerCropPortrait(oriented)
        } catch (_: Exception) { null }
    }
    private fun centerCropPortrait(source: Bitmap): Bitmap { val targetW=1080; val targetH=1920; val targetRatio=targetW.toFloat()/targetH; val sourceRatio=source.width.toFloat()/source.height; val cropW:Int; val cropH:Int; if(sourceRatio>targetRatio){cropH=source.height;cropW=(cropH*targetRatio).toInt()}else{cropW=source.width;cropH=(cropW/targetRatio).toInt()}; val left=(source.width-cropW)/2; val top=(source.height-cropH)/2; val cropped=Bitmap.createBitmap(source,left,top,cropW,cropH); val scaled=Bitmap.createScaledBitmap(cropped,targetW,targetH,true); if(cropped!==source)cropped.recycle();if(scaled!==source)source.recycle();return scaled }
    private fun toggleVisualPreview() { if (visualPreviewPlaying) { stopVisualPreview(resetIcon = true); return }; if (visualPreviewSlider.progress >= 100) visualPreviewSlider.progress = 0; visualPreviewStartProgress = visualPreviewSlider.progress; visualPreviewStartedAtMs = SystemClock.elapsedRealtime(); visualPreviewPlaying = true; visualPreviewPlayButton.text = "⏸"; startCachedVoicePreview(visualPreviewStartProgress); preview.removeCallbacks(visualPreviewTick); preview.post(visualPreviewTick) }
    private fun startCachedVoicePreview(startProgress: Int) { }
    private fun stopPreviewAudio() { try { voicePlayer?.stop(); voicePlayer?.release() } catch (_: Exception) {}; try { ctaPreviewPlayer?.stop(); ctaPreviewPlayer?.release() } catch (_: Exception) {}; voicePlayer = null; ctaPreviewPlayer = null }
    private fun stopVisualPreview(resetIcon: Boolean) { visualPreviewPlaying = false; if (::preview.isInitialized) preview.removeCallbacks(visualPreviewTick); stopPreviewAudio(); if (resetIcon && ::visualPreviewPlayButton.isInitialized) visualPreviewPlayButton.text = "▶" }
    private fun visualPreviewDurationMs(): Long { val bodyWords = ReelLayout.bodyWordCount(headlineInputs.map { it.text.toString() }); return (6500L + bodyWords * 220L).coerceIn(6500L, 22000L) }
    private fun updateVisualPreview(progress: Float) { preview.timelineDurationMs = visualPreviewDurationMs(); visualPreviewLabel.text = "VISUAL PREVIEW ${(progress * 100).toInt()}%"; val images = reelBitmaps; if (images.isEmpty()) { preview.visualProgress = progress; preview.invalidate(); return }; val segment = (progress * images.size).toInt().coerceIn(0, images.size - 1); preview.backgroundBitmap = images[segment]; preview.effect = imageEffects.getOrElse(segment) { ReelEncoder.ImageEffect.ZOOM_IN }; preview.effectIntensity = imageEffectIntensities.getOrElse(segment) { 0.18f }; preview.visualProgress = (progress * images.size - segment).coerceIn(0f, 1f); preview.timelineProgress = progress; preview.textPreviewProgress = progress; preview.showCta = progress >= 0.98f && ctaBitmap != null; preview.invalidate() }
    override fun onSaveInstanceState(outState: Bundle) { if (::titleInput.isInitialized) { outState.putString("temporary_title", titleInput.text.toString()); outState.putStringArrayList("temporary_headlines", ArrayList(headlineInputs.map { it.text.toString() })); outState.putStringArrayList("temporary_image_uris", ArrayList(reelImageUris.map { it.toString() })) }; super.onSaveInstanceState(outState) }
    private fun restoreTemporaryEditorState(state: Bundle?) { if (state == null) return; titleInput.setText(state.getString("temporary_title", "")); val headlines = state.getStringArrayList("temporary_headlines").orEmpty(); headlineInputs.forEachIndexed { index, input -> input.setText(headlines.getOrNull(index).orEmpty()) }; val imageUris = state.getStringArrayList("temporary_image_uris").orEmpty().mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }; if (imageUris.isNotEmpty()) { reelBitmaps.clear(); reelImageUris.clear(); imageEffects.clear(); imageEffectIntensities.clear(); imageUris.forEach { uri -> decodePortrait(uri)?.let { bitmap -> reelBitmaps.add(bitmap); reelImageUris.add(uri); imageEffects.add(ReelEncoder.ImageEffect.ZOOM_IN); imageEffectIntensities.add(0.18f) } }; mainBitmap = reelBitmaps.firstOrNull(); preview.backgroundBitmap = mainBitmap; mainImageLabel.text = when (reelBitmaps.size) { 0 -> "No images selected"; 1 -> "1 image selected"; else -> "${reelBitmaps.size} images selected" }; renderImageThumbnails() }; refreshPreview() }
    private fun refreshPreview() { preview.title=titleInput.text.toString().trim().ifBlank { "Main heading" }; preview.headlines=headlineInputs.map{it.text.toString().trim()}; preview.textPreviewProgress = 0f; preview.invalidate() }
    private fun testVoice() { if (!::voiceTts.isInitialized) return toast("Voice service is still loading"); val selected=selectedVoice ?: voiceOptions.getOrNull(voiceSpinner.selectedItemPosition) ?: return toast("Select a Kokoro voice first"); val parts=mutableListOf<String>(); val heading=titleInput.text.toString().trim(); if(heading.isNotBlank())parts.add(heading); headlineInputs.map{it.text.toString().trim()}.filter{it.isNotBlank()}.forEach{parts.add(it)}; val speechText=parts.joinToString(". "); if(speechText.isBlank())return toast("Enter a heading or subheading first"); toast("Generating and playing voice..."); val output=File(cacheDir,"daily_flare_voice.wav"); val selectedSpeed=voiceSpeeds.getOrElse(speedSpinner.selectedItemPosition){1.0f}; voiceTts.speakToFile(speechText,selected,output,selectedSpeed){ok,duration->runOnUiThread{if(!ok)toast("Voice generation failed")else{playVoiceFile(output);toast("Playing Kokoro voice at ${selectedSpeed}×: ${duration} ms")}}} }
    private fun getAudioDurationMs(file: File): Long { val retriever=MediaMetadataRetriever(); return try{retriever.setDataSource(file.absolutePath);retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0L}catch(_:Exception){0L}finally{try{retriever.release()}catch(_:Exception){}} }
    private fun playVoiceFile(file: File){try{voicePlayer?.release();voicePlayer=MediaPlayer().apply{setDataSource(file.absolutePath);setOnCompletionListener{it.release();voicePlayer=null};prepare();start()}}catch(_:Exception){toast("Voice was generated but could not be played")}}
    private fun updateExportProgress(percent:Int,stage:String,detailEta:Boolean=true){val clamped=percent.coerceIn(0,100);val etaText=if(detailEta&&clamped in 1..99&&exportStartedAtMs>0L){val elapsedMs=System.currentTimeMillis()-exportStartedAtMs;val estimatedTotalMs=(elapsedMs.toDouble()*100.0/clamped.toDouble()).toLong();val remainingMs=(estimatedTotalMs-elapsedMs).coerceAtLeast(0L);" • ${formatDuration(remainingMs)} left"}else"";exportProgress.progress=clamped;exportStatus.text="$stage $clamped%$etaText"}
    private fun formatDuration(ms:Long):String{val totalSeconds=((ms+999L)/1000L).toInt().coerceAtLeast(0);return if(totalSeconds<60)"${totalSeconds}s" else "${totalSeconds/60}m ${totalSeconds%60}s"}
    private fun exportReel(){
        val backgrounds=reelBitmaps.ifEmpty{listOfNotNull(mainBitmap)}; if(backgrounds.isEmpty())return toast("Choose at least one image"); val cta=ctaBitmap?:return toast("Choose the outro image"); val music=musicUri?:return toast("Choose music")
        val title=titleInput.text.toString().trim().ifBlank{"Main heading"}; val headlines=headlineInputs.map{it.text.toString().trim()}; exportStartedAtMs=System.currentTimeMillis(); lastSavedOutputUri=null; updateExportProgress(0,"Preparing export...",false); toast("Starting export")
        thread(name="daily-flare-export"){try{val video=File(cacheDir,"daily_flare_video.mp4");var voice=File(cacheDir,"daily_flare_export_voice.wav");var ctaVoice=File(cacheDir,"daily_flare_cta_voice.wav");val audio=File(cacheDir,"daily_flare_mixed_audio_aac.mp4");val output=File(cacheDir,"daily_flare_reel_18s.mp4");video.delete();voice.delete();ctaVoice.delete();audio.delete();output.delete(); val speechParts=mutableListOf<String>();if(title.isNotBlank())speechParts.add(title);headlines.filter{it.isNotBlank()}.forEach{speechParts.add(it)};val speechText=speechParts.joinToString(". ");if(speechText.isBlank())throw IllegalStateException("Enter a heading or subheading for the voice");val selectedVoiceOption=this@MainActivity.selectedVoice?:voiceOptions.getOrNull(voiceSpinner.selectedItemPosition)?:throw IllegalStateException("Select a Kokoro voice first");if(!::voiceTts.isInitialized)throw IllegalStateException("Kokoro voice service is not ready");val selectedSpeed=voiceSpeeds.getOrElse(speedSpinner.selectedItemPosition){1.0f};runOnUiThread{updateExportProgress(2,"Loading Kokoro model...")};val voiceGenerationStartedAt=System.currentTimeMillis();runOnUiThread{updateExportProgress(4,"Generating Kokoro narration...")};val voiceLatch=CountDownLatch(1);var voiceOk=false;voiceTts.speakToFile(speechText,selectedVoiceOption,voice,selectedSpeed){ok,_->voiceOk=ok;voiceLatch.countDown()};if(!voiceLatch.await(60,TimeUnit.SECONDS)||!voiceOk||!voice.exists()||voice.length()<128L||voice.lastModified()<voiceGenerationStartedAt)throw IllegalStateException("Kokoro voice generation failed");val voiceDurationMs=getAudioDurationMs(voice);if(voiceDurationMs<=0L)throw IllegalStateException("Generated Kokoro narration has no duration");runOnUiThread{updateExportProgress(8,"Kokoro narration generated",false)};
val timingTexts=listOf(title)+headlines
val weights=timingTexts.map { it.trim().split(Regex("\\s+")).count { word -> word.isNotBlank() }.coerceAtLeast(0) }
val bodyWeight=weights.drop(1).sum().coerceAtLeast(1)
val titleWeight=weights.firstOrNull()?.coerceAtLeast(0) ?: 0
val measuredTitleSpeechMs=if (titleWeight > 0) (voiceDurationMs.toDouble()*titleWeight.toDouble()/(titleWeight+bodyWeight).toDouble()).toLong() else 0L
val measuredHeadlineDurationsMs=headlines.indices.map { index -> (voiceDurationMs-measuredTitleSpeechMs).coerceAtLeast(1L)*weights.getOrElse(index+1){0}.toLong()/bodyWeight.toLong() }
ReelEncoder(this).encode(backgrounds,cta,title,headlines,voiceDurationMs,measuredTitleSpeechMs,measuredHeadlineDurationsMs,video,imageEffects,imageEffectIntensities,"NONE",0,"WORD BY WORD",object:ReelEncoder.Drain{override fun onFrame(frame:Int,total:Int){val percent=20+((frame*60L)/total.coerceAtLeast(1)).toInt();runOnUiThread{updateExportProgress(percent,"Rendering video...")}}});if(!video.exists()||video.length()==0L)throw IllegalStateException("Video rendering produced no output");runOnUiThread{updateExportProgress(82,"Generating Kokoro outro voice...")};val ctaLatch=CountDownLatch(1);var ctaOk=false;voiceTts.speakToFile(ctaText,selectedVoiceOption,ctaVoice,selectedSpeed){ok,_->ctaOk=ok;ctaLatch.countDown()};if(!ctaLatch.await(30,TimeUnit.SECONDS)||!ctaOk||!ctaVoice.exists()||ctaVoice.length()<128L||getAudioDurationMs(ctaVoice)<=0L)throw IllegalStateException("CTA voice generation failed");runOnUiThread{updateExportProgress(88,"Mixing generated voice and music...")};if(!AudioTranscoder(this).transcodeMixed(music,voice,audio,ctaVoice)||!audio.exists()||audio.length()==0L)throw IllegalStateException("Voice and music could not be mixed");runOnUiThread{updateExportProgress(95,"Finalizing video...")};if(!AudioMuxer().mux(video,audio,output)||!output.exists()||output.length()==0L)throw IllegalStateException("Audio/video muxing failed");val savedUri=saveToGallery(output);lastSavedOutputUri=savedUri;runOnUiThread{updateExportProgress(100,if(savedUri!=null)"Export complete ✓"else"Export completed but could not save to gallery",false);if(savedUri!=null)showSharePopup(savedUri)}}catch(e:Exception){runOnUiThread{exportStatus.text="Export failed";toast("Export failed: ${e.message?:"unknown error"}")}}}
    }
    private fun saveToGallery(source:File):Uri?{if(!source.exists()||source.length()==0L)return null;return try{if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q){val values=ContentValues().apply{put(MediaStore.Video.Media.DISPLAY_NAME,"daily_flare_reel_${System.currentTimeMillis()}.mp4");put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");put(MediaStore.Video.Media.RELATIVE_PATH,Environment.DIRECTORY_MOVIES+"/Daily Flare Reel");put(MediaStore.Video.Media.IS_PENDING,1)};val uri=contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values)?:return null;try{contentResolver.openOutputStream(uri)?.use{out->source.inputStream().use{input->input.copyTo(out)}}?:return null;values.clear();values.put(MediaStore.Video.Media.IS_PENDING,0);if(contentResolver.update(uri,values,null,null)>0)uri else null}catch(_:Exception){contentResolver.delete(uri,null,null);null}}else{val dir=File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),"Daily Flare Reel").apply{mkdirs()};val destination=File(dir,"daily_flare_reel_${System.currentTimeMillis()}.mp4");source.copyTo(destination,overwrite=true);sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,Uri.fromFile(destination)));if(destination.exists()&&destination.length()>0L)Uri.fromFile(destination) else null}}catch(_:Exception){null}}
    private fun showSharePopup(uri:Uri){android.app.AlertDialog.Builder(this).setTitle("Export complete").setMessage("Your Daily Flare Reel is ready.").setNegativeButton("CLOSE",null).setPositiveButton("SHARE"){_,_->startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="video/mp4";putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Share Daily Flare Reel"))}.show()}
    override fun onDestroy(){stopVisualPreview(resetIcon=false);voicePlayer?.release();voicePlayer=null;if(::voiceTts.isInitialized)voiceTts.shutdown();super.onDestroy()}
    private fun toast(message:String)=Toast.makeText(this,message,Toast.LENGTH_LONG).show()
}
