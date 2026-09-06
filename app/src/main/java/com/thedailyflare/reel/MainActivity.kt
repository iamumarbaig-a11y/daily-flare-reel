package com.thedailyflare.reel

import android.app.Activity
import android.content.ContentValues
import android.content.SharedPreferences
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaPlayer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.concurrent.thread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var preview: ReelPreviewView
    private lateinit var mainImageLabel: TextView
    private lateinit var ctaImageLabel: TextView
    private lateinit var musicLabel: TextView
    private lateinit var voiceSpinner: Spinner
    private lateinit var speedSpinner: Spinner
    private lateinit var voiceTts: VoiceTts
    private val voiceSpeeds = listOf(0.75f, 0.9f, 1.0f, 1.1f, 1.25f, 1.5f)
    private lateinit var voiceStatus: TextView
    private lateinit var exportStatus: TextView
    private lateinit var exportProgress: ProgressBar
    private var voicePlayer: MediaPlayer? = null
    private var voiceOptions = emptyList<VoiceTts.VoiceOption>()
    private var selectedVoice: VoiceTts.VoiceOption? = null
    private lateinit var titleInput: EditText
    private val headlineInputs = mutableListOf<EditText>()
    private var mainBitmap: Bitmap? = null
    private var ctaBitmap: Bitmap? = null
    private var musicUri: Uri? = null
    private lateinit var musicIntensitySpinner: Spinner
    private lateinit var preferences: SharedPreferences
    private val musicIntensities = listOf(5, 10, 15, 20)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences("daily_flare_reel_preferences", MODE_PRIVATE)
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
        root.addView(ImageView(this).apply { setImageResource(R.drawable.daily_flare_logo); adjustViewBounds = true; setPadding(0, 0, 0, 8) }, LinearLayout.LayoutParams(-1, 96))
        root.addView(TextView(this).apply { text = "Daily Flare Reel"; textSize = 30f; setTextColor(0xFF172A3A.toInt()); setPadding(0, 0, 0, 12) }, lp())
        section(root, "1. MAIN 15-SECOND IMAGE")
        root.addView(button("CHOOSE MAIN IMAGE") { pickImage(100) }, lp())
        mainImageLabel = label("No main image selected"); root.addView(mainImageLabel, lp())

        // The editor lives directly on the preview. This keeps the existing text
        // objects and export logic unchanged, but lets the user tap and type where
        // the heading will visually appear instead of using controls below.
        val previewFrame = FrameLayout(this)
        preview = ReelPreviewView(this).apply { setBackgroundColor(0xFFEFEFEF.toInt()) }
        previewFrame.addView(preview, FrameLayout.LayoutParams(-1, -1))

        val textOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 0, 28, 0)
        }
        previewFrame.addView(
            textOverlay,
            FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = 112 }
        )

        titleInput = editOnPreview("Main heading", true)
        textOverlay.addView(titleInput, overlayLp())
        titleInput.setOnFocusChangeListener { _, _ -> refreshPreview() }

        for (i in 1..7) {
            val input = editOnPreview("Subheading $i", false)
            headlineInputs.add(input)
            textOverlay.addView(input, overlayLp())
            input.setOnFocusChangeListener { _, _ -> refreshPreview() }
        }

        root.addView(previewFrame, LinearLayout.LayoutParams(-1, 0, 1f))
        section(root, "2. 3-SECOND CTA IMAGE")
        root.addView(button("CHOOSE CTA IMAGE") { pickImage(101) }, lp())
        ctaImageLabel = label("No CTA image selected"); root.addView(ctaImageLabel, lp())
        section(root, "3. KOKORO AI VOICE")
        voiceStatus = label("Checking local Kokoro package...")
        root.addView(voiceStatus, lp())
        root.addView(TextView(this).apply { text = "VOICE"; textSize = 14f }, lp())
        voiceSpinner = Spinner(this)
        root.addView(voiceSpinner, lp())
        root.addView(TextView(this).apply { text = "VOICE SPEED"; textSize = 14f }, lp())
        speedSpinner = Spinner(this)
        speedSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voiceSpeeds.map { it.toString() + "×" })
        val savedSpeed = preferences.getFloat("voice_speed", 1.0f)
        speedSpinner.setSelection(voiceSpeeds.indexOf(savedSpeed).takeIf { it >= 0 } ?: voiceSpeeds.indexOf(1.0f), false)
        speedSpinner.onItemSelectedListener = simpleSelectionListener { position ->
            preferences.edit().putFloat("voice_speed", voiceSpeeds.getOrElse(position) { 1.0f }).apply()
        }
        root.addView(speedSpinner, lp())
        voiceTts = VoiceTts(this)
        voiceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                selectedVoice = null
            }
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                selectedVoice = voiceOptions.getOrNull(position)
                selectedVoice?.name?.let { preferences.edit().putString("voice_name", it).apply() }
            }
        }
        refreshKokoroState()
        root.addView(button("TEST SELECTED KOKORO VOICE") { testVoice() }, lp())
        root.addView(button("OPEN KOKORO MODEL PACKAGE") { startActivity(Intent(this, KokoroExperimentActivity::class.java)) }, lp())
        section(root, "4. MUSIC — ALL 18 SECONDS")
        root.addView(button("CHOOSE MUSIC") { pickAudio() }, lp())
        musicLabel = label("No music selected"); root.addView(musicLabel, lp())
        root.addView(TextView(this).apply { text = "MUSIC INTENSITY"; textSize = 14f }, lp())
        musicIntensitySpinner = Spinner(this)
        musicIntensitySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, musicIntensities.map { "$it%" })
        val savedMusicIntensity = preferences.getInt("music_intensity", 10)
        musicIntensitySpinner.setSelection(musicIntensities.indexOf(savedMusicIntensity).takeIf { it >= 0 } ?: musicIntensities.indexOf(10), false)
        musicIntensitySpinner.onItemSelectedListener = simpleSelectionListener { position ->
            preferences.edit().putInt("music_intensity", musicIntensities.getOrElse(position) { 10 }).apply()
        }
        root.addView(musicIntensitySpinner, lp())
        exportStatus = label("Ready to export")
        root.addView(exportStatus, lp())
        exportProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0 }
        root.addView(exportProgress, lp())
        root.addView(button("EXPORT REEL") { exportReel() }, lp())


        setContentView(scroll)
    }

    private fun refreshKokoroState() {
        // Keep the exact voice selected by the user when returning from the
        // model package screen. Do not fall back silently to another voice.
        val previousName = selectedVoice?.name
        voiceTts.initialize({ options ->
            runOnUiThread {
                voiceOptions = options
                val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options.map { it.label })
                voiceSpinner.adapter = adapter
                val persistedVoiceName = preferences.getString("voice_name", null)
                val restoredIndex = options.indexOfFirst { it.name == (persistedVoiceName ?: previousName) }
                    .takeIf { it >= 0 } ?: 0
                if (options.isNotEmpty()) {
                    voiceSpinner.setSelection(restoredIndex, false)
                    selectedVoice = options[restoredIndex]
                } else {
                    selectedVoice = null
                }
                voiceStatus.text = "Kokoro is ready locally — " + options.size + " voices available"
            }
        }, { error ->
            runOnUiThread {
                voiceOptions = emptyList()
                selectedVoice = null
                voiceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, emptyList<String>())
                voiceStatus.text = error
            }
        })
    }

    private fun editOnPreview(h: String, heading: Boolean) = EditText(this).apply {
        hint = h
        textSize = if (heading) 18f else 15f
        setTextColor(0xFF000000.toInt())
        setHintTextColor(0x99000000.toInt())
        typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD)
        setSingleLine(false)
        maxLines = 2
        setPadding(14, 6, 14, 6)
        background = android.graphics.drawable.ColorDrawable(0xEFFFFFFF.toInt())
    }
    private fun overlayLp() = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = 6
    }

    private fun section(root: LinearLayout, value: String) { root.addView(TextView(this).apply { text = value; textSize = 18f; setTextColor(0xFF172A3A.toInt()); setPadding(0, 16, 0, 6) }, lp()) }
    private fun edit(h: String, lines: Int) = EditText(this).apply { hint = h; textSize = 18f; minLines = lines; setSingleLine(false) }
    private fun button(t: String, action: () -> Unit) = Button(this).apply { text = t; textSize = 16f; setOnClickListener { action() } }
    private fun label(t: String) = TextView(this).apply { text = t; textSize = 16f; setPadding(0, 4, 0, 4) }
    private fun lp() = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun pickImage(code: Int) { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }, code) }
    private fun pickAudio() { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "audio/*"; addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }, 102) }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        when (requestCode) {
            100 -> { mainBitmap = decodePortrait(uri); preview.backgroundBitmap = mainBitmap; mainImageLabel.text = "Main image selected"; refreshPreview() }
            101 -> { ctaBitmap = decodePortrait(uri); preview.ctaBitmap = ctaBitmap; ctaImageLabel.text = "CTA image selected"; preferences.edit().putString("cta_uri", uri.toString()).apply(); preview.invalidate() }
            102 -> { musicUri = uri; musicLabel.text = "Music selected"; preferences.edit().putString("music_uri", uri.toString()).apply() }
        }
    }

    private fun restorePersistentSelections() {
        // Spinner values are restored before their listeners are attached so the
        // saved preferences cannot be overwritten by the default UI selection.
        preferences.getString("cta_uri", null)?.let { restoreCta(Uri.parse(it)) }
        preferences.getString("music_uri", null)?.let { restoreMusic(Uri.parse(it)) }
    }

    private fun restoreCta(uri: Uri) {
        val bitmap = decodePortrait(uri) ?: return
        ctaBitmap = bitmap; preview.ctaBitmap = bitmap; ctaImageLabel.text = "CTA image selected"; preview.invalidate()
    }

    private fun restoreMusic(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.close()
            musicUri = uri; musicLabel.text = "Music selected"
        } catch (_: Exception) { }
    }

    private fun simpleSelectionListener(onSelected: (Int) -> Unit) = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) = onSelected(position)
    }

    private fun decodePortrait(uri: Uri): Bitmap? {
        return try {
            val decoded = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
            val orientation = contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } ?: ExifInterface.ORIENTATION_NORMAL
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            }
            val oriented = if (orientation == ExifInterface.ORIENTATION_NORMAL) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
            centerCropPortrait(oriented)
        } catch (_: Exception) { null }
    }

    private fun centerCropPortrait(source: Bitmap): Bitmap {
        val targetW = 1080; val targetH = 1920; val targetRatio = targetW.toFloat() / targetH; val sourceRatio = source.width.toFloat() / source.height
        val cropW: Int; val cropH: Int
        if (sourceRatio > targetRatio) { cropH = source.height; cropW = (cropH * targetRatio).toInt() } else { cropW = source.width; cropH = (cropW / targetRatio).toInt() }
        val left = (source.width - cropW) / 2; val top = (source.height - cropH) / 2
        val cropped = Bitmap.createBitmap(source, left, top, cropW, cropH); val scaled = Bitmap.createScaledBitmap(cropped, targetW, targetH, true)
        if (cropped !== source) cropped.recycle(); if (scaled !== source) source.recycle(); return scaled
    }

    private fun refreshPreview() {
        // Text is rendered by the editable controls directly on top of the preview.
        // Export still reads the same titleInput/headlineInputs objects.
        preview.title = ""
        preview.headlines = emptyList()
        preview.invalidate()
    }

    private fun testVoice() {
        if (!::voiceTts.isInitialized) return toast("Voice service is still loading")
        val selected = selectedVoice ?: voiceOptions.getOrNull(voiceSpinner.selectedItemPosition)
            ?: return toast("Select a Kokoro voice first")
        val parts = mutableListOf<String>()
        val heading = titleInput.text.toString().trim()
        if (heading.isNotBlank()) parts.add(heading)
        headlineInputs.map { it.text.toString().trim() }.filter { it.isNotBlank() }.forEach { parts.add(it) }
        val speechText = parts.joinToString(". ")
        if (speechText.isBlank()) return toast("Enter a heading or subheading first")
        toast("Generating and playing voice...")
        val output = File(cacheDir, "daily_flare_voice.wav")
        val selectedSpeed = voiceSpeeds.getOrElse(speedSpinner.selectedItemPosition) { 1.0f }
        voiceTts.speakToFile(speechText, selected, output, selectedSpeed) { ok, duration ->
            runOnUiThread {
                if (!ok) {
                    toast("Voice generation failed")
                } else {
                    playVoiceFile(output)
                    toast("Playing Kokoro voice at " + selectedSpeed + "×: " + duration + " ms")
                }
            }
        }
    }

    private fun getAudioDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
    }

    private fun playVoiceFile(file: File) {
        try {
            voicePlayer?.release()
            voicePlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { it.release(); voicePlayer = null }
                prepare()
                start()
            }
        } catch (_: Exception) {
            toast("Voice was generated but could not be played")
        }
    }

    private fun openVoiceSettings() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (_: Exception) {
            toast("Android TTS settings are unavailable on this phone")
        }
    }

    private fun exportReel() {
        val bg = mainBitmap ?: return toast("Choose the main 15-second image")
        val cta = ctaBitmap ?: return toast("Choose the 3-second CTA image")
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

                val speechParts = mutableListOf<String>()
                if (title.isNotBlank()) speechParts.add(title)
                headlines.filter { it.isNotBlank() }.forEach { speechParts.add(it) }
                val speechText = speechParts.joinToString(". ")
                if (speechText.isBlank()) throw IllegalStateException("Enter a heading or subheading for the voice")

                val selectedVoiceOption = this@MainActivity.selectedVoice
                    ?: voiceOptions.getOrNull(voiceSpinner.selectedItemPosition)
                    ?: throw IllegalStateException("Select a Kokoro voice first")
                val voiceLatch = CountDownLatch(1)
                var voiceOk = false
                if (!::voiceTts.isInitialized) throw IllegalStateException("Kokoro voice service is not ready")
                val selectedSpeed = voiceSpeeds.getOrElse(speedSpinner.selectedItemPosition) { 1.0f }
                voiceTts.speakToFile(speechText, selectedVoiceOption, voice, selectedSpeed) { ok, _ ->
                    voiceOk = ok
                    voiceLatch.countDown()
                }
                if (!voiceLatch.await(60, TimeUnit.SECONDS) || !voiceOk || !voice.exists() || voice.length() == 0L) {
                    throw IllegalStateException("Kokoro voice generation failed")
                }

                val voiceDurationMs = getAudioDurationMs(voice)

                // Kokoro does not expose word timestamps. Measure the title and every
                // subheading with the exact selected voice and speed, then use those
                // real durations only for the visual timeline. The final narration
                // remains the original single continuous Kokoro render.
                runOnUiThread { exportProgress.progress = 8; exportStatus.text = "Measuring narration timing..." }
                val timingTexts = listOf(title) + headlines
                val timingDurationsMs = MutableList(timingTexts.size) { 0L }

                timingTexts.forEachIndexed { index, timingText ->
                    if (timingText.isBlank()) return@forEachIndexed
                    val timingFile = File(cacheDir, "daily_flare_timing_$index.wav")
                    timingFile.delete()
                    val timingLatch = CountDownLatch(1)
                    var timingOk = false
                    voiceTts.speakToFile(timingText, selectedVoiceOption, timingFile, selectedSpeed) { ok, _ ->
                        timingOk = ok
                        timingLatch.countDown()
                    }
                    if (!timingLatch.await(60, TimeUnit.SECONDS) || !timingOk || !timingFile.exists() || timingFile.length() == 0L) {
                        throw IllegalStateException("Kokoro timing measurement failed")
                    }
                    timingDurationsMs[index] = getAudioDurationMs(timingFile)
                    timingFile.delete()
                    val timingPercent = 8 + (((index + 1) * 12) / timingTexts.size.coerceAtLeast(1))
                    runOnUiThread {
                        exportProgress.progress = timingPercent
                        exportStatus.text = "Measuring narration timing... ${index + 1}/${timingTexts.size}"
                    }
                }

                // Isolated clips can contain slightly different edge silence from
                // the continuous narration. Keep their relative timing, but scale the
                // complete measured timeline to the actual final narration duration.
                val totalMeasuredSpeechMs = timingDurationsMs.sum().coerceAtLeast(1L)
                val measuredTitleSpeechMs = ((timingDurationsMs.firstOrNull() ?: 0L).toDouble()
                    * voiceDurationMs.toDouble() / totalMeasuredSpeechMs.toDouble()).toLong()
                val measuredHeadlineDurationsMs = headlines.indices.map { index ->
                    timingDurationsMs.getOrElse(index + 1) { 0L }
                }

                ReelEncoder(this).encode(
                    bg,
                    cta,
                    title,
                    headlines,
                    voiceDurationMs,
                    measuredTitleSpeechMs,
                    measuredHeadlineDurationsMs,
                    video,
                    object : ReelEncoder.Drain {
                    override fun onFrame(frame: Int, total: Int) {
                        val percent = 20 + ((frame * 60L) / total.coerceAtLeast(1)).toInt()
                        runOnUiThread { exportProgress.progress = percent; exportStatus.text = "Rendering video... $percent%" }
                    }
                })
                if (!video.exists() || video.length() == 0L) throw IllegalStateException("Video rendering produced no output")
                runOnUiThread { exportProgress.progress = 82; exportStatus.text = "Generating CTA voice..." }
                val ctaLatch = CountDownLatch(1)
                var ctaOk = false
                voiceTts.speakToFile("FOLLOW THE DAILY FLARE ON SOCIAL MEDIA.", selectedVoiceOption, ctaVoice, selectedSpeed) { ok, _ -> ctaOk = ok; ctaLatch.countDown() }
                if (!ctaLatch.await(30, TimeUnit.SECONDS) || !ctaOk || !ctaVoice.exists() || ctaVoice.length() == 0L) throw IllegalStateException("CTA voice generation failed")
                runOnUiThread { exportProgress.progress = 88; exportStatus.text = "Mixing voice and music..." }
                if (!AudioTranscoder(this).transcodeMixed(music, voice, audio, ctaVoice, musicIntensities.getOrElse(musicIntensitySpinner.selectedItemPosition) { 10 } / 100f) || !audio.exists() || audio.length() == 0L) throw IllegalStateException("Voice and music could not be mixed")
                runOnUiThread { exportProgress.progress = 95; exportStatus.text = "Finalizing video..." }
                if (!AudioMuxer().mux(video, audio, output) || !output.exists() || output.length() == 0L) throw IllegalStateException("Audio/video muxing failed")
                val saved = saveToGallery(output)
                runOnUiThread { exportProgress.progress = 100; exportStatus.text = if (saved) "Export complete ✓" else "Export completed but could not save to gallery"; toast(if (saved) "Export complete: saved to Movies/Daily Flare Reel" else "Export completed but could not save to gallery") }
            } catch (e: Exception) { runOnUiThread { toast("Export failed: ${e.message ?: "unknown error"}") } }
        }
    }

    private fun saveToGallery(source: File): Boolean {
        if (!source.exists() || source.length() == 0L) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "daily_flare_reel_${System.currentTimeMillis()}.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Daily Flare Reel")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
                try {
                    contentResolver.openOutputStream(uri)?.use { out -> source.inputStream().use { input -> input.copyTo(out) } } ?: return false
                    values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0); contentResolver.update(uri, values, null, null) > 0
                } catch (_: Exception) { contentResolver.delete(uri, null, null); false }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Daily Flare Reel").apply { mkdirs() }
                val destination = File(dir, "daily_flare_reel_${System.currentTimeMillis()}.mp4"); source.copyTo(destination, overwrite = true)
                sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(destination))); destination.exists() && destination.length() > 0L
            }
        } catch (_: Exception) { false }
    }

    override fun onDestroy() {
        voicePlayer?.release()
        voicePlayer = null
        if (::voiceTts.isInitialized) voiceTts.shutdown()
        super.onDestroy()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
