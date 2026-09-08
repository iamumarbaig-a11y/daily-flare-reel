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
import android.content.ComponentCallbacks2
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
    private var restoringSession = false

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
        titleInput.setText(prefs.getString(KEY_TITLE, "") ?: "")
        for (i in headlineInputs.indices) headlineInputs[i].setText(prefs.getString("$KEY_HEADLINE_PREFIX$i", "") ?: "")

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
        // Only CTA/music/voice keep their existing persistent behavior.
        if (!::titleInput.isInitialized || restoringSession) return
        val editor = prefs.edit()
        if (::voiceSpinner.isInitialized && voiceOptions.isNotEmpty()) {
            voiceOptions.getOrNull(voiceSpinner.selectedItemPosition)?.name?.let { editor.putString(KEY_VOICE, it) }
        }
        editor.apply()
    }

    private fun saveSessionState() {
        if (!::titleInput.isInitialized || restoringSession) return
        val state = getSharedPreferences(SESSION_PREFS, MODE_PRIVATE).edit()
            .putString(KEY_TITLE, titleInput.text.toString())
            .putString(KEY_SESSION_MAIN_URI, mainImageUri?.toString())
        headlineInputs.forEachIndexed { index, input -> state.putString("$KEY_HEADLINE_PREFIX$index", input.text.toString()) }
        state.apply()
    }

    private fun restoreSessionState() {
        val state = getSharedPreferences(SESSION_PREFS, MODE_PRIVATE)
        restoringSession = true
        titleInput.setText(state.getString(KEY_TITLE, "") ?: "")
        for (i in headlineInputs.indices) headlineInputs[i].setText(state.getString("$KEY_HEADLINE_PREFIX$i", "") ?: "")
        val value = state.getString(KEY_SESSION_MAIN_URI, null)
        if (value != null) {
            val uri = restoreUriString(value)
            mainImageUri = uri
            if (uri != null) {
                mainBitmap = decodePortrait(uri)
                preview.backgroundBitmap = mainBitmap
                mainImageLabel.text = if (mainBitmap != null) "Main image selected" else "Session main image unavailable"
            }
        }
        restoringSession = false
    }

    private fun clearSessionState() {
        getSharedPreferences(SESSION_PREFS, MODE_PRIVATE).edit().clear().apply()
    }

    private fun restoreUriString(value: String): Uri? = try { Uri.parse(value) } catch (_: Exception) { null }
}