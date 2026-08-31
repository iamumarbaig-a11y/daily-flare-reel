package com.thedailyflare.reel

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var preview: ReelPreviewView
    private lateinit var mainImageLabel: TextView
    private lateinit var ctaImageLabel: TextView
    private lateinit var musicLabel: TextView
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
        root.addView(TextView(this).apply {
            text = "18 seconds • 9:16 • 1080×1920\n15s main image + text • 3s CTA image • music for all 18s"
            textSize = 17f; setPadding(0, 4, 0, 18)
        }, lp())

        preview = ReelPreviewView(this).apply { setBackgroundColor(0xFFEFEFEF.toInt()) }
        root.addView(preview, LinearLayout.LayoutParams(-1, 520))

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

        section(root, "3. MUSIC — ALL 18 SECONDS")
        root.addView(button("CHOOSE MUSIC") { pickAudio() }, lp())
        musicLabel = label("No music selected"); root.addView(musicLabel, lp())

        root.addView(TextView(this).apply {
            text = "The export is exactly 18 seconds: 15 seconds of the main image with the heading and 7 subheadings, followed by 3 seconds of the CTA image."
            textSize = 14f; setPadding(0, 12, 0, 12)
        }, lp())
        root.addView(button("EXPORT 18-SECOND REEL") { exportReel() }, lp())
        setContentView(scroll)
    }

    private fun section(root: LinearLayout, value: String) { root.addView(TextView(this).apply {
        text = value; textSize = 18f; setTextColor(0xFF172A3A.toInt()); setPadding(0, 16, 0, 6)
    }, lp()) }
    private fun edit(h: String, lines: Int) = EditText(this).apply { hint = h; textSize = 18f; minLines = lines; setSingleLine(false) }
    private fun button(t: String, action: () -> Unit) = Button(this).apply { text = t; textSize = 16f; setOnClickListener { action() } }
    private fun label(t: String) = TextView(this).apply { text = t; textSize = 16f; setPadding(0, 4, 0, 4) }
    private fun lp() = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun pickImage(code: Int) { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }, code) }
    private fun pickAudio() { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        type = "audio/*"; addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }, 102) }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        when (requestCode) {
            100 -> { mainBitmap = decode(uri); preview.backgroundBitmap = mainBitmap; mainImageLabel.text = "Main image selected"; refreshPreview() }
            101 -> { ctaBitmap = decode(uri); ctaImageLabel.text = "CTA image selected" }
            102 -> { musicUri = uri; musicLabel.text = "Music selected" }
        }
    }
    private fun decode(uri: Uri): Bitmap? = try { contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null }
    private fun refreshPreview() { preview.title = titleInput.text.toString(); preview.headlines = headlineInputs.map { it.text.toString() }; preview.invalidate() }

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
                val audio = File(cacheDir, "daily_flare_audio.mp4")
                val output = File(getExternalFilesDir(null), "daily_flare_reel_18s.mp4")
                video.delete(); audio.delete(); output.delete()
                ReelEncoder().encode(bg, cta, title, headlines, video)
                val audioReady = AudioTranscoder(this).transcode(music, audio)
                val success = audioReady && AudioMuxer().mux(video, audio, output)
                runOnUiThread { toast(if (success) "Export complete: 18 seconds" else "Export failed") }
            } catch (e: Exception) { runOnUiThread { toast("Export failed: ${e.message ?: "unknown error"}") } }
        }
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
