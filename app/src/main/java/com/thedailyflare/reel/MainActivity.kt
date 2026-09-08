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
import android.os.Handler
import android.os.Looper
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
    private lateinit var textEffectButton: Button
    private var selectedTextEffect = "FADE + POP"
    private var textEffectIntensity = 25
    private var textRevealMode = "WORD BY WORD"
    private var mainBitmap: Bitmap? = null
    private val reelBitmaps = mutableListOf<Bitmap>()
    private val imageEffects = mutableListOf<ReelEncoder.ImageEffect>()
    private val imageEffectIntensities = mutableListOf<Float>()
    private var ctaBitmap: Bitmap? = null
    private var musicUri: Uri? = null
    private lateinit var musicIntensitySpinner: Spinner
    private lateinit var preferences: SharedPreferences
    private val musicIntensities = listOf(5, 10, 15, 20)
    private var lastSavedOutputUri: Uri? = null
    private var exportStartedAtMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences("daily_flare_reel_preferences", MODE_PRIVATE)
        selectedTextEffect = preferences.getString("text_effect", "FADE + POP") ?: "FADE + POP"
        textEffectIntensity = preferences.getInt("text_effect_intensity", 25)
        textRevealMode = preferences.getString("text_reveal_mode", "WORD BY WORD") ?: "WORD BY WORD"
        buildUi()
        restorePersistentSelections()
    }

    override fun onResume() {
        super.onResume()
        if (::voiceTts.isInitialized && ::voiceStatus.isInitialized) refreshKokoroState()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 24, 32, 32) }
        scroll.addView(root)
        // Top app branding removed; the reel preview starts at the top.
        previewFrame = FrameLayout(this)
        preview = ReelPreviewView(this).apply {
            setBackgroundColor(0xFFEFEFEF.toInt())
            textEffect = selectedTextEffect
            textEffectIntensity = this@MainActivity.textEffectIntensity
            textRevealMode = this@MainActivity.textRevealMode
        }
        previewFrame.addView(preview, FrameLayout.LayoutParams(-1, -2))

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
        textEffectButton = button("TEXT: $selectedTextEffect · ${textEffectIntensity}%") { showTextEffectSettings() }
        root.addView(textEffectButton, lp())
        root.addView(twoColumnRow("" to button("IMAGE") { pickImages() }, "" to button("OUTRO") { pickImage(101) }), lp())
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
        root.addView(twoColumnRow("VOICE" to voiceSpinner, "SPEED" to speedSpinner), lp())
        root.addView(voiceStatus, lp())
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
        musicIntensitySpinner = Spinner(this)
        musicIntensitySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, musicIntensities.map { "$it%" })
        val savedMusicIntensity = preferences.getInt("music_intensity", 10)
        musicIntensitySpinner.setSelection(musicIntensities.indexOf(savedMusicIntensity).takeIf { it >= 0 } ?: 1, false)
        musicIntensitySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                preferences.edit().putInt("music_intensity", musicIntensities.getOrElse(position) { 10 }).apply()
            }
        }
        root.addView(twoColumnRow("MUSIC" to button("CHOOSE MUSIC") { pickAudio() }, "INTENSITY" to musicIntensitySpinner), lp())
        musicLabel = label("No music selected"); root.addView(musicLabel, lp())
        exportStatus = label("Ready to export"); root.addView(exportStatus, lp())
        exportProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0 }
        root.addView(exportProgress, lp())
        root.addView(button("EXPORT REEL") { exportReel() }, lp())
        setContentView(scroll)
    }

    private fun showTextEffectSettings() {
        val effects = arrayOf("NONE", "POP", "FADE + POP", "SLIDE UP", "BOUNCE", "BLUR IN", "SLIDE + FADE")
        val revealModes = arrayOf("WORD BY WORD", "CHARACTER BY CHARACTER", "INSTANT")
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(12)) }
        root.addView(label("TEXT REVEAL"))
        val reveal = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, revealModes); setSelection(revealModes.indexOf(textRevealMode).coerceAtLeast(0)) }
        root.addView(reveal)
        root.addView(label("TEXT EFFECT"))
        val spinner = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, effects); setSelection(effects.indexOf(selectedTextEffect).coerceAtLeast(0)) }
        root.addView(spinner)
        val intensityLabel = label("INTENSITY: ${textEffectIntensity}%")
        root.addView(intensityLabel)
        val intensity = SeekBar(this).apply { max = 100; progress = textEffectIntensity }
        intensity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { intensityLabel.text = "INTENSITY: $progress%" }; override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit; override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit })
        root.addView(intensity)
        android.app.AlertDialog.Builder(this).setTitle("TEXT ANIMATION").setView(root).setPositiveButton("APPLY") { _, _ ->
            selectedTextEffect = effects[spinner.selectedItemPosition]
            textEffectIntensity = intensity.progress
            textRevealMode = revealModes[reveal.selectedItemPosition]
            preferences.edit().putString("text_effect", selectedTextEffect).putInt("text_effect_intensity", textEffectIntensity).putString("text_reveal_mode", textRevealMode).apply()
            textEffectButton.text = "TEXT: $selectedTextEffect · ${textEffectIntensity}%"
            preview.textEffect = selectedTextEffect
            preview.textEffectIntensity = textEffectIntensity
            preview.textRevealMode = textRevealMode
            preview.invalidate()
        }.setNegativeButton("CANCEL", null).show()
    }

    private fun refreshWatcher() = object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit; override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refreshPreview() }; override fun afterTextChanged(s: Editable?) = Unit }

    private fun refreshKokoroState() { val previousName = selectedVoice?.name; voiceTts.initialize({ options -> runOnUiThread { voiceOptions = options; voiceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options.map { it.label }); val persistedVoiceName = preferences.getString("voice_name", null); val restoredIndex = options.indexOfFirst { it.name == (persistedVoiceName ?: previousName) }.takeIf { it >= 0 } ?: 0; if (options.isNotEmpty()) { voiceSpinner.setSelection(restoredIndex, false); selectedVoice = options[restoredIndex] } else selectedVoice = null; openPkgButton.visibility = android.view.View.GONE; testButton.visibility = android.view.View.GONE; kokoroSetupRow.visibility = android.view.View.GONE; voiceStatus.visibility = android.view.View.GONE } }, { error -> runOnUiThread { voiceOptions = emptyList(); selectedVoice = null; voiceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, emptyList<String>()); voiceStatus.text = error; voiceStatus.visibility = android.view.View.VISIBLE; openPkgButton.visibility = android.view.View.VISIBLE; testButton.visibility = android.view.View.VISIBLE; kokoroSetupRow.visibility = android.view.View.VISIBLE } }) }

    private fun edit(hint: String, lines: Int) = EditText(this).apply { this.hint = hint; setSingleLine(lines == 1); maxLines = lines; textSize = 17f; setPadding(dp(14), dp(10), dp(14), dp(10)) }
    private fun twoColumnRow(left: Pair<String, android.view.View>, right: Pair<String, android.view.View>): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        fun column(item: Pair<String, android.view.View>): LinearLayout {
            val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this@MainActivity).apply { text = item.first; textSize = 13f; setTextColor(0xFF5D646B.toInt()); setPadding(0, 0, 0, dp(2)) }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
            col.addView(item.second, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
            return col
        }
        row.addView(column(left), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        row.addView(column(right), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        return row
    }

    private fun section(root: LinearLayout, value: String) { root.addView(TextView(this).apply { text = value; textSize = 18f; setTextColor(0xFF172A3A.toInt()); setPadding(0, 20, 0, 8) }, lp()) }
    private fun button(t: String, action: () -> Unit) = Button(this).apply { text = t; textSize = 15f; setTextColor(0xFFFFFFFF.toInt()); isAllCaps = false; typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD); minHeight = dp(52); setPadding(dp(18), 0, dp(18), 0); background = GradientDrawable().apply { setColor(0xFF172A3A.toInt()); cornerRadius = dp(18).toFloat() }; elevation = dp(3).toFloat(); setOnClickListener { action() } }
    private fun label(t: String) = TextView(this).apply { text = t; textSize = 16f; setTextColor(0xFF5D646B.toInt()); setPadding(0, dp(6), 0, dp(6)) }
    private fun lp() = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4); bottomMargin = dp(4) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun refreshPreview() { preview.title = titleInput.text.toString().trim().ifBlank { "Main heading" }; preview.headlines = headlineInputs.map { it.text.toString().trim() }; preview.textEffect = selectedTextEffect; preview.textEffectIntensity = textEffectIntensity; preview.textRevealMode = textRevealMode; preview.invalidate() }
    private fun visualPreviewDurationMs(): Long = (6500L + ReelLayout.bodyWordCount(headlineInputs.map { it.text.toString() }) * 220L).coerceIn(6500L, 22000L)
    private fun updateVisualPreview(progress: Float) { visualPreviewLabel.text = "VISUAL PREVIEW ${(progress * 100).toInt()}%"; val images = reelBitmaps; if (images.isEmpty()) { preview.visualProgress = progress; preview.invalidate(); return }; val segment = (progress * images.size).toInt().coerceIn(0, images.size - 1); preview.backgroundBitmap = images[segment]; preview.effect = imageEffects.getOrElse(segment) { ReelEncoder.ImageEffect.ZOOM_IN }; preview.effectIntensity = imageEffectIntensities.getOrElse(segment) { 0.18f }; preview.visualProgress = (progress * images.size - segment).coerceIn(0f, 1f); preview.textPreviewProgress = progress; preview.showCta = progress >= 0.98f && ctaBitmap != null; preview.invalidate() }
    private fun toggleVisualPreview() { if (visualPreviewPlaying) stopVisualPreview(true) else { if (visualPreviewSlider.progress >= 100) visualPreviewSlider.progress = 0; visualPreviewStartProgress = visualPreviewSlider.progress; visualPreviewStartedAtMs = SystemClock.elapsedRealtime(); visualPreviewPlaying = true; visualPreviewPlayButton.text = "⏸"; preview.removeCallbacks(visualPreviewTick); preview.post(visualPreviewTick) } }
    private fun stopVisualPreview(resetIcon: Boolean) { visualPreviewPlaying = false; preview.removeCallbacks(visualPreviewTick); stopPreviewAudio(); if (resetIcon && ::visualPreviewPlayButton.isInitialized) visualPreviewPlayButton.text = "▶" }
    private fun stopPreviewAudio() { try { voicePlayer?.stop(); voicePlayer?.release() } catch (_: Exception) {}; try { ctaPreviewPlayer?.stop(); ctaPreviewPlayer?.release() } catch (_: Exception) {}; voicePlayer = null; ctaPreviewPlayer = null }
    private fun pickImage(code: Int) { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }, code) }
    private fun pickImages() { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }, 100) }
    private fun pickAudio() { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "audio/*"; addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }, 102) }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) { super.onActivityResult(requestCode, resultCode, data); if (resultCode != RESULT_OK || data == null) return; when (requestCode) { 100 -> { reelBitmaps.clear(); imageEffects.clear(); imageEffectIntensities.clear(); val uris = mutableListOf<Uri>(); data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) } ?: data.data?.let { uris.add(it) }; uris.forEach { uri -> try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}; decodePortrait(uri)?.let { reelBitmaps.add(it); imageEffects.add(ReelEncoder.ImageEffect.ZOOM_IN); imageEffectIntensities.add(0.18f) } }; mainBitmap = reelBitmaps.firstOrNull(); preview.backgroundBitmap = mainBitmap; mainImageLabel.text = when (reelBitmaps.size) { 0 -> "No images selected"; 1 -> "1 image selected"; else -> "${reelBitmaps.size} images selected" }; renderImageThumbnails(); refreshPreview() }; 101 -> { val uri = data.data ?: return; try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}; ctaBitmap = decodePortrait(uri); preview.ctaBitmap = ctaBitmap; ctaImageLabel.text = "Outro selected"; preferences.edit().putString("cta_uri", uri.toString()).apply(); preview.invalidate() }; 102 -> { val uri = data.data ?: return; try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}; musicUri = uri; musicLabel.text = "Music selected"; preferences.edit().putString("music_uri", uri.toString()).apply() } } }
    private fun renderImageThumbnails() { imageStrip.removeAllViews(); val size = dp(58); val radius = dp(10).toFloat(); reelBitmaps.forEachIndexed { index, bitmap -> val wrapper = FrameLayout(this).apply { background = GradientDrawable().apply { setColor(0xFF172A3A.toInt()); cornerRadius = radius }; setPadding(dp(2), dp(2), dp(2), dp(2)); elevation = dp(2).toFloat() }; val image = android.widget.ImageView(this).apply { setImageBitmap(bitmap); scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; contentDescription = "Reel image ${index + 1}" }; wrapper.addView(image, FrameLayout.LayoutParams(-1, -1)); wrapper.setOnClickListener { showImagePreview(index) }; imageStrip.addView(wrapper, LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(8) }) } }

    private fun restorePersistentSelections() {
        val savedTextEffect = preferences.getString("text_effect", null)
        if (savedTextEffect != null) selectedTextEffect = savedTextEffect
        textEffectIntensity = preferences.getInt("text_effect_intensity", textEffectIntensity)
        textRevealMode = preferences.getString("text_reveal_mode", textRevealMode) ?: textRevealMode
        if (::textEffectButton.isInitialized) textEffectButton.text = "TEXT: $selectedTextEffect · ${textEffectIntensity}%"
        if (::preview.isInitialized) {
            preview.textEffect = selectedTextEffect
            preview.textEffectIntensity = textEffectIntensity
            preview.textRevealMode = textRevealMode
            preview.invalidate()
        }
        preferences.getString("music_uri", null)?.let { runCatching { musicUri = Uri.parse(it); musicLabel.text = "Music selected" } }
        preferences.getString("cta_uri", null)?.let { runCatching { ctaBitmap = decodePortrait(Uri.parse(it)); preview.ctaBitmap = ctaBitmap; ctaImageLabel.text = "Outro selected" } }
    }

    private fun testVoice() {
        if (!::voiceTts.isInitialized) return toast("Voice service is still loading")
        val selected = voiceOptions.getOrNull(voiceSpinner.selectedItemPosition)?.name
        val parts = mutableListOf<String>()
        val heading = titleInput.text.toString().trim(); if (heading.isNotBlank()) parts.add(heading)
        headlineInputs.map { it.text.toString().trim() }.filter { it.isNotBlank() }.forEach { parts.add(it) }
        val speechText = parts.joinToString(". ")
        if (speechText.isBlank()) return toast("Enter a heading or subheading first")
        toast("Generating and playing voice...")
        val output = File(cacheDir, "daily_flare_voice.wav")
        voiceTts.speakToFile(speechText, selected, output) { ok, duration -> runOnUiThread { if (!ok) toast("Voice generation failed") else { playVoiceFile(output); toast("Playing selected voice: $duration ms") } } }
    }

    private fun getAudioDurationMs(file: File): Long { val retriever = MediaMetadataRetriever(); return try { retriever.setDataSource(file.absolutePath); retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L } catch (_: Exception) { 0L } finally { try { retriever.release() } catch (_: Exception) {} } }
    private fun playVoiceFile(file: File) { try { voicePlayer?.release(); voicePlayer = MediaPlayer().apply { setDataSource(file.absolutePath); setOnCompletionListener { it.release(); voicePlayer = null }; prepare(); start() } } catch (_: Exception) { toast("Voice was generated but could not be played") } }
    private fun openVoiceSettings() { try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) } catch (_: Exception) { toast("Android TTS settings are unavailable on this phone") } }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun exportReel() {
        val bg = mainBitmap ?: return toast("Choose the main image")
        val cta = ctaBitmap ?: return toast("Choose the outro image")
        val music = musicUri ?: return toast("Choose music")
        val title = titleInput.text.toString().trim().ifBlank { "Main heading" }
        val headlines = headlineInputs.map { it.text.toString().trim() }
        exportStatus.text = "Preparing export..."; exportProgress.progress = 0; toast("Starting export")
        thread(name = "daily-flare-export") {
            try {
                val video = File(cacheDir, "daily_flare_video.mp4")
                val voice = File(cacheDir, "daily_flare_export_voice.wav")
                val ctaVoice = File(cacheDir, "daily_flare_cta_voice.wav")
                val audio = File(cacheDir, "daily_flare_mixed_audio_aac.mp4")
                val output = File(cacheDir, "daily_flare_reel_18s.mp4")
                video.delete(); voice.delete(); ctaVoice.delete(); audio.delete(); output.delete()
                val speechParts = mutableListOf<String>(); if (title.isNotBlank()) speechParts.add(title); headlines.filter { it.isNotBlank() }.forEach { speechParts.add(it) }
                val speechText = speechParts.joinToString(". "); if (speechText.isBlank()) throw IllegalStateException("Enter a heading or subheading for the voice")
                val selectedVoice = voiceOptions.getOrNull(voiceSpinner.selectedItemPosition)?.name
                val voiceLatch = CountDownLatch(1); var voiceOk = false
                if (!::voiceTts.isInitialized) throw IllegalStateException("Android TTS is not ready")
                runOnUiThread { exportStatus.text = "Generating voice..." }
                voiceTts.speakToFile(speechText, selectedVoice, voice) { ok, _ -> voiceOk = ok; voiceLatch.countDown() }
                if (!voiceLatch.await(60, TimeUnit.SECONDS) || !voiceOk || !voice.exists() || voice.length() == 0L) throw IllegalStateException("Voice generation failed")
                val voiceDurationMs = getAudioDurationMs(voice)
                ReelEncoder(this).encode(bg, cta, title, headlines, voiceDurationMs, (voiceDurationMs * title.length / speechText.length).coerceAtLeast(0L), video, object : ReelEncoder.Drain { override fun onFrame(frame: Int, total: Int) { val percent = ((frame * 80L) / total.coerceAtLeast(1)).toInt(); runOnUiThread { exportProgress.progress = percent; exportStatus.text = "Rendering video... $percent%" } } })
                if (!video.exists() || video.length() == 0L) throw IllegalStateException("Video rendering produced no output")
                runOnUiThread { exportProgress.progress = 82; exportStatus.text = "Generating CTA voice..." }
                val ctaLatch = CountDownLatch(1); var ctaOk = false
                voiceTts.speakToFile("FOLLOW US ON SOCIAL MEDIA", selectedVoice, ctaVoice) { ok, _ -> ctaOk = ok; ctaLatch.countDown() }
                if (!ctaLatch.await(30, TimeUnit.SECONDS) || !ctaOk || !ctaVoice.exists() || ctaVoice.length() == 0L) throw IllegalStateException("CTA voice generation failed")
                AudioTranscoder.mixVoiceAndMusic(this, voice, ctaVoice, music, voiceDurationMs + 3000L, preferences.getInt("music_intensity", 10), audio)
                if (!audio.exists() || audio.length() == 0L) throw IllegalStateException("Voice and music could not be mixed")
                runOnUiThread { exportProgress.progress = 92; exportStatus.text = "Muxing final reel..." }
                AudioMuxer.mux(video, audio, output)
                if (!output.exists() || output.length() == 0L) throw IllegalStateException("Final reel was not created")
                val values = ContentValues().apply { put(MediaStore.Video.Media.DISPLAY_NAME, "daily_flare_reel_${System.currentTimeMillis()}.mp4"); put(MediaStore.Video.Media.MIME_TYPE, "video/mp4"); if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Daily Flare Reel"); put(MediaStore.Video.Media.IS_PENDING, 1) }
                val resolver = contentResolver; val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("Could not create gallery entry")
                resolver.openOutputStream(uri)?.use { input -> output.inputStream().use { it.copyTo(input) } }
                if (Build.VERSION.SDK_INT >= 29) resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                lastSavedOutputUri = uri
                runOnUiThread { exportProgress.progress = 100; exportStatus.text = "Export complete"; toast("Reel saved to Movies/Daily Flare Reel") }
            } catch (e: Exception) { runOnUiThread { exportStatus.text = e.message ?: "Export failed"; toast(e.message ?: "Export failed") } }
        }
    }
}