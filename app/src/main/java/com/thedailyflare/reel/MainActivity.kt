package com.thedailyflare.reel

import android.app.Activity
import android.content.ContentValues
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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.ProgressBar
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
    private lateinit var voiceTts: VoiceTts
    private lateinit var voiceStatus: TextView
    private lateinit var exportStatus: TextView
    private lateinit var exportProgress: ProgressBar
    private var voicePlayer: MediaPlayer? = null
    private var voiceOptions = emptyList<VoiceTts.VoiceOption>()
    private lateinit var titleInput: EditText
    private val headlineInputs = mutableListOf<EditText>()
    private var mainBitmap: Bitmap? = null
    private var ctaBitmap: Bitmap? = null
    private var musicUri: Uri? = null
    private var mainImageUri: Uri? = null

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        restoreEditorState()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 24, 32, 32) }
        scroll.addView(root)
        root.addView(TextView(this).apply { text = "Daily Flare Reel"; textSize = 30f; setTextColor(0xFF172A3A.toInt()) }, lp())
        root.addView(TextView(this).apply { text = "18 seconds • 9:16 • 1080×1920\n15s main image + text • 3s CTA image • music for all 18s"; textSize = 17f; setPadding(0, 4, 0, 18) }, lp())
        preview = ReelPreviewView(this).apply { setBackgroundColor(0xFFEFEFEF.toInt()) }
        root.addView(preview, lp())
        section(root, "1. MAIN 15-SECOND IMAGE")
        root.addView(button("CHOOSE MAIN IMAGE") { pickImage(100) }, lp())
        mainImageLabel = label("No main image selected"); root.addView(mainImageLabel, lp())
        section(root, "MAIN HEADING")
        titleInput = edit("Main heading", 2); root.addView(titleInput, lp())
        titleInput.setOnFocusChangeListener { _, _ -> refreshPreview(); saveEditorState() }
        titleInput.doAfterTextChanged { refreshPreview(); saveEditorState() }
        for (i in 1..7) {
            section(root, "SUBHEADING $i")
            val input = edit("Subheading $i", 2)
            headlineInputs.add(input); root.addView(input, lp())
            input.setOnFocusChangeListener { _, _ -> refreshPreview(); saveEditorState() }
            input.doAfterTextChanged { refreshPreview(); saveEditorState() }
        }
        section(root, "2. 3-SECOND CTA IMAGE")
        root.addView(button("CHOOSE CTA IMAGE") { pickImage(101) }, lp())
        ctaImageLabel = label("No CTA image selected"); root.addView(ctaImageLabel, lp())
        section(root, "3. TEST ANDROID TTS VOICE")
        voiceStatus = label("Loading voices...")
        root.addView(voiceStatus, lp())
        voiceSpinner = Spinner(this)
        root.addView(voiceSpinner, lp())
        voiceTts = VoiceTts(this)
        voiceTts.initialize({ options ->
            runOnUiThread {
                voiceOptions = options
                voiceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options.map { it.label })
                voiceStatus.text = if (options.isEmpty()) "No English voices available in the active Android TTS engine" else options.size.toString() + " English voice(s) available"
                val savedVoice = prefs.getString(KEY_VOICE, null)
                if (savedVoice != null) {
                    val index = options.indexOfFirst { it.name == savedVoice }
                    if (index >= 0) voiceSpinner.setSelection(index, false)
                }
                if (options.isEmpty()) toast("No English Android TTS voices found")
            }
        }, { error -> runOnUiThread { voiceStatus.text = error; toast(error) } })
        root.addView(button("TEST SELECTED VOICE") { testVoice() }, lp())
        root.addView(button("OPEN ANDROID VOICE SETTINGS") { openVoiceSettings() }, lp())
        root.addView(TextView(this).apply {
            text = "Test Selected Voice now plays the generated speech aloud. If only one voice appears, use Android Voice Settings to download or enable more voices."
            textSize = 14f
            setPadding(0, 4, 0, 12)
        }, lp())

        section(root, "4. MUSIC — ALL 18 SECONDS")
        root.addView(button("CHOOSE MUSIC") { pickAudio() }, lp())
        musicLabel = label("No music selected"); root.addView(musicLabel, lp())
        root.addView(TextView(this).apply { text = "The export is exactly 18 seconds: 15 seconds of the main image with the heading and 7 subheadings, followed by 3 seconds of the CTA image. The finished video is saved to Movies/Daily Flare Reel."; textSize = 14f; setPadding(0, 12, 0, 12) }, lp())
        exportStatus = label("Ready to export")
        root.addView(exportStatus, lp())
        exportProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0 }
        root.addView(exportProgress, lp())
        root.addView(button("EXPORT REEL") { exportReel() }, lp())
        setContentView(scroll)
        voiceSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) { saveEditorState() }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        })
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
            100 -> { mainImageUri = uri; mainBitmap = decodePortrait(uri); preview.backgroundBitmap = mainBitmap; mainImageLabel.text = "Main image selected"; refreshPreview() }
            101 -> { ctaBitmap = decodePortrait(uri); preview.ctaBitmap = ctaBitmap; ctaImageLabel.text = "CTA image selected"; prefs.edit().putString(KEY_CTA_URI, uri.toString()).apply(); preview.invalidate() }
            102 -> { musicUri = uri; musicLabel.text = "Music selected"; prefs.edit().putString(KEY_MUSIC_URI, uri.toString()).apply() }
        }
        saveEditorState()
    }

    private fun restoreEditorState() {
        restoreSessionState()

        restoreUri(KEY_CTA_URI)?.let { uri ->
            ctaBitmap = decodePortrait(uri)
            preview.ctaBitmap = ctaBitmap
            ctaImageLabel.text = if (ctaBitmap != null) "CTA image selected" else "Saved CTA image unavailable"
        }
        restoreUri(KEY_MUSIC_URI)?.let { uri ->
            musicUri = uri
            musicLabel.text = "Music selected"
        }
        refreshPreview()
    }

    private fun restoreUri(key: String): Uri? {
        val value = prefs.getString(key, null) ?: return null
        return try { Uri.parse(value) } catch (_: Exception) { null }
    }

    private fun saveEditorState() {
        if (!::titleInput.isInitialized) return
        val editor = prefs.edit()
        if (::voiceSpinner.isInitialized && voiceOptions.isNotEmpty()) {
            voiceOptions.getOrNull(voiceSpinner.selectedItemPosition)?.name?.let { editor.putString(KEY_VOICE, it) }
        }
        editor.apply()
    }

    private fun saveSessionState() {
        if (!::titleInput.isInitialized) return
        val editor = getSharedPreferences(SESSION_PREFS, MODE_PRIVATE).edit()
            .putString(KEY_TITLE, titleInput.text.toString())
            .putString(KEY_SESSION_MAIN_URI, mainImageUri?.toString())
        headlineInputs.forEachIndexed { i, input -> editor.putString("$KEY_HEADLINE_PREFIX$i", input.text.toString()) }
        editor.apply()
    }

    private fun restoreSessionState() {
        val session = getSharedPreferences(SESSION_PREFS, MODE_PRIVATE)
        titleInput.setText(session.getString(KEY_TITLE, "") ?: "")
        for (i in headlineInputs.indices) headlineInputs[i].setText(session.getString("$KEY_HEADLINE_PREFIX$i", "") ?: "")
        session.getString(KEY_SESSION_MAIN_URI, null)?.let {
            mainImageUri = Uri.parse(it)
            mainBitmap = decodePortrait(mainImageUri!!)
            preview.backgroundBitmap = mainBitmap
            mainImageLabel.text = if (mainBitmap != null) "Main image selected" else "Session main image unavailable"
        }
    }

    private fun refreshPreview() { preview.title = titleInput.text.toString(); preview.headlines = headlineInputs.map { it.text.toString() }; preview.invalidate(); saveSessionState() }

    private fun testVoice() {
        if (!::voiceTts.isInitialized) return toast("Voice service is still loading")
        val selected = voiceOptions.getOrNull(voiceSpinner.selectedItemPosition)?.name
        val parts = mutableListOf<String>()
        val heading = titleInput.text.toString().trim()
        if (heading.isNotBlank()) parts.add(heading)
        headlineInputs.map { it.text.toString().trim() }.filter { it.isNotBlank() }.forEach { parts.add(it) }
        val speechText = parts.joinToString(". ")
        if (speechText.isBlank()) return toast("Enter a heading or subheading first")
        toast("Generating and playing voice...")
        val output = File(cacheDir, "daily_flare_voice.wav")
        voiceTts.speakToFile(speechText, selected, output) { ok, duration ->
            runOnUiThread {
                if (!ok) toast("Voice generation failed") else { playVoiceFile(output); toast("Playing selected voice: " + duration + " ms") }
            }
        }
    }

    private fun getAudioDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try { retriever.setDataSource(file.absolutePath); retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L } catch (_: Exception) { 0L } finally { try { retriever.release() } catch (_: Exception) { } }
    }

    private fun playVoiceFile(file: File) {
        try {
            voicePlayer?.release()
            voicePlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { it.release(); voicePlayer = null }
                prepare(); start()
            }
        } catch (_: Exception) { toast("Voice was generated but could not be played") }
    }

    private fun openVoiceSettings() {
        try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) } catch (_: Exception) { toast("Android TTS settings are unavailable on this phone") }
    }

    private fun exportReel() {
        val bg = mainBitmap ?: return toast("Choose the main 15-second image")
        val cta = ctaBitmap ?: return toast("Choose the 3-second CTA image")
        val music = musicUri ?: return toast("Choose music")
        val title = titleInput.text.toString().trim().ifBlank { "Main heading" }
        val headlines = headlineInputs.map { it.text.toString().trim() }
        saveEditorState()
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

                val selectedVoice = voiceOptions.getOrNull(voiceSpinner.selectedItemPosition)?.name
                val voiceLatch = CountDownLatch(1)
                var voiceOk = false
                if (!::voiceTts.isInitialized) throw IllegalStateException("Android TTS is not ready")
                voiceTts.speakToFile(speechText, selectedVoice, voice) { ok, _ -> voiceOk = ok; voiceLatch.countDown() }
                if (!voiceLatch.await(60, TimeUnit.SECONDS) || !voiceOk || !voice.exists() || voice.length() == 0L) throw IllegalStateException("Voice generation failed")

                val voiceDurationMs = getAudioDurationMs(voice)
                val spokenCharacters = speechText.count { !it.isWhitespace() && it != '.' && it != ',' }
                val titleCharacters = title.count { !it.isWhitespace() && it != '.' && it != ',' }
                val rawTitleSpeechMs = if (spokenCharacters > 0) voiceDurationMs * titleCharacters / spokenCharacters else 0L
                val titleSpeechMs = (rawTitleSpeechMs - 250L).coerceAtLeast(0L)
                ReelEncoder(this).encode(bg, cta, title, headlines, voiceDurationMs, titleSpeechMs, video, object : ReelEncoder.Drain {
                    override fun onFrame(frame: Int, total: Int) {
                        val percent = ((frame * 80L) / total.coerceAtLeast(1)).toInt()
                        runOnUiThread { exportProgress.progress = percent; exportStatus.text = "Rendering video... $percent%" }
                    }
                })
                if (!video.exists() || video.length() == 0L) throw IllegalStateException("Video rendering produced no output")
                runOnUiThread { exportProgress.progress = 82; exportStatus.text = "Generating CTA voice..." }
                val ctaLatch = CountDownLatch(1)
                var ctaOk = false
                voiceTts.speakToFile("FOLLOW US ON SOCIAL MEDIA", selectedVoice, ctaVoice) { ok, _ -> ctaOk = ok; ctaLatch.countDown() }
                if (!ctaLatch.await(30, TimeUnit.SECONDS) || !ctaOk || !ctaVoice.exists() || ctaVoice.length() == 0L) throw IllegalStateException("CTA voice generation failed")
                runOnUiThread { exportProgress.progress = 88; exportStatus.text = "Mixing voice and music..." }
                if (!AudioTranscoder(this).transcodeMixed(music, voice, audio, ctaVoice) || !audio.exists() || audio.length() == 0L) throw IllegalStateException("Voice and music could not be mixed")
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
                try { contentResolver.openOutputStream(uri)?.use { out -> source.inputStream().use { input -> input.copyTo(out) } } ?: return false; values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0); contentResolver.update(uri, values, null, null) > 0 } catch (_: Exception) { contentResolver.delete(uri, null, null); false }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Daily Flare Reel").apply { mkdirs() }
                val destination = File(dir, "daily_flare_reel_${System.currentTimeMillis()}.mp4"); source.copyTo(destination, overwrite = true); sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(destination))); destination.exists() && destination.length() > 0L
            }
        } catch (_: Exception) { false }
    }

    override fun onPause() { saveEditorState(); saveSessionState(); super.onPause() }

    override fun onDestroy() {
        saveEditorState()
        saveSessionState()
        voicePlayer?.release(); voicePlayer = null
        if (::voiceTts.isInitialized) voiceTts.shutdown()
        super.onDestroy()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun EditText.doAfterTextChanged(action: () -> Unit) {
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = action()
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
    }

    companion object {
        private const val PREFS = "daily_flare_reel_state"
        private const val SESSION_PREFS = "daily_flare_reel_editor_session"
        private const val KEY_TITLE = "title"
        private const val KEY_HEADLINE_PREFIX = "headline_"
        private const val KEY_SESSION_MAIN_URI = "session_main_image_uri"
        private const val KEY_MAIN_URI = "main_image_uri"
        private const val KEY_CTA_URI = "cta_image_uri"
        private const val KEY_MUSIC_URI = "music_uri"
        private const val KEY_VOICE = "voice"
    }
}