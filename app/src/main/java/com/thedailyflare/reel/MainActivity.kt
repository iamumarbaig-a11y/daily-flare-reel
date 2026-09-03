package com.thedailyflare.reel

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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
import android.widget.TextView
import android.widget.Toast
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var preview: ReelPreviewView
    private lateinit var mainImageLabel: TextView
    private lateinit var ctaImageLabel: TextView
    private lateinit var musicLabel: TextView
    private lateinit var voiceSpinner: Spinner
    private lateinit var voiceTts: VoiceTts
    private var voiceOptions = emptyList<VoiceTts.VoiceOption>()
    private lateinit var titleInput: EditText
    private val headlineInputs = mutableListOf<EditText>()
    private var mainBitmap: Bitmap? = null
    private var ctaBitmap: Bitmap? = null
    private var musicUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); buildUi() }

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
        titleInput.setOnFocusChangeListener { _, _ -> refreshPreview() }
        for (i in 1..7) {
            section(root, "SUBHEADING $i")
            val input = edit("Subheading $i", 2)
            headlineInputs.add(input); root.addView(input, lp())
            input.setOnFocusChangeListener { _, _ -> refreshPreview() }
        }
        section(root, "2. 3-SECOND CTA IMAGE")
        root.addView(button("CHOOSE CTA IMAGE") { pickImage(101) }, lp())
        ctaImageLabel = label("No CTA image selected"); root.addView(ctaImageLabel, lp())
        section(root, "3. TEST ANDROID TTS VOICE")
        voiceSpinner = Spinner(this)
        root.addView(voiceSpinner, lp())
        voiceTts = VoiceTts(this)
        voiceTts.initialize({ options ->
            runOnUiThread {
                voiceOptions = options
                voiceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options.map { it.label })
                if (options.isEmpty()) toast("No English Android TTS voices found")
            }
        }, { error -> runOnUiThread { toast(error) } })
        root.addView(button("TEST SELECTED VOICE") { testVoice() }, lp())
        root.addView(TextView(this).apply {
            text = "This only tests the selected phone voice. Your current reel export is unchanged."
            textSize = 14f
            setPadding(0, 4, 0, 12)
        }, lp())

        section(root, "4. MUSIC — ALL 18 SECONDS")
        root.addView(button("CHOOSE MUSIC") { pickAudio() }, lp())
        musicLabel = label("No music selected"); root.addView(musicLabel, lp())
        root.addView(TextView(this).apply { text = "The export is exactly 18 seconds: 15 seconds of the main image with the heading and 7 subheadings, followed by 3 seconds of the CTA image. The finished video is saved to Movies/Daily Flare Reel."; textSize = 14f; setPadding(0, 12, 0, 12) }, lp())
        root.addView(button("EXPORT 18-SECOND REEL") { exportReel() }, lp())
        setContentView(scroll)
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
            101 -> { ctaBitmap = decodePortrait(uri); preview.ctaBitmap = ctaBitmap; ctaImageLabel.text = "CTA image selected"; preview.invalidate() }
            102 -> { musicUri = uri; musicLabel.text = "Music selected" }
        }
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

    private fun refreshPreview() { preview.title = titleInput.text.toString(); preview.headlines = headlineInputs.map { it.text.toString() }; preview.invalidate() }

    private fun testVoice() {
        if (!::voiceTts.isInitialized) return toast("Voice service is still loading")
        val selected = voiceOptions.getOrNull(voiceSpinner.selectedItemPosition)?.name
        val parts = mutableListOf<String>()
        val heading = titleInput.text.toString().trim()
        if (heading.isNotBlank()) parts.add(heading)
        headlineInputs.map { it.text.toString().trim() }.filter { it.isNotBlank() }.forEach { parts.add(it) }
        val speechText = parts.joinToString(". ")
        if (speechText.isBlank()) return toast("Enter a heading or subheading first")
        toast("Generating voice...")
        val output = File(cacheDir, "daily_flare_voice.wav")
        voiceTts.speakToFile(speechText, selected, output) { ok, duration ->
            runOnUiThread {
                if (ok) toast("Voice generated successfully: " + duration + " ms")
                else toast("Voice generation failed")
            }
        }
    }

    private fun exportReel() {
        val bg = mainBitmap ?: return toast("Choose the main 15-second image")
        val cta = ctaBitmap ?: return toast("Choose the 3-second CTA image")
        val music = musicUri ?: return toast("Choose music")
        val title = titleInput.text.toString().trim().ifBlank { "Main heading" }
        val headlines = headlineInputs.map { it.text.toString().trim() }
        toast("Rendering 15s news + 3s CTA")
        thread(name = "daily-flare-export") {
            try {
                val video = File(cacheDir, "daily_flare_video.mp4")
                val audio = File(cacheDir, "daily_flare_audio_aac.mp4")
                val output = File(cacheDir, "daily_flare_reel_18s.mp4")
                video.delete(); audio.delete(); output.delete()
                ReelEncoder(this).encode(bg, cta, title, headlines, video)
                if (!video.exists() || video.length() == 0L) throw IllegalStateException("Video rendering produced no output")
                if (!AudioTranscoder(this).transcode(music, audio) || !audio.exists() || audio.length() == 0L) throw IllegalStateException("Music could not be converted to AAC")
                if (!AudioMuxer().mux(video, audio, output) || !output.exists() || output.length() == 0L) throw IllegalStateException("Audio/video muxing failed")
                val saved = saveToGallery(output)
                runOnUiThread { toast(if (saved) "Export complete: saved to Movies/Daily Flare Reel" else "Export completed but could not save to gallery") }
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
        if (::voiceTts.isInitialized) voiceTts.shutdown()
        super.onDestroy()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
