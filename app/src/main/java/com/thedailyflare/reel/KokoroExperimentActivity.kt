package com.thedailyflare.reel

import android.app.Activity
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.ffmpegkit.kokoro.KokoroTTS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class KokoroExperimentActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var status: TextView
    private lateinit var textInput: EditText
    private var modelFile: File? = null
    private var player: MediaPlayer? = null
    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        scroll.addView(root)
        root.addView(TextView(this).apply { text = "Kokoro AI Voice — Experimental"; textSize = 26f; setTextColor(0xFF172A3A.toInt()) }, lp())
        root.addView(TextView(this).apply {
            text = "This test is isolated from the working reel exporter. Choose your Kokoro ONNX model, initialize it locally, then generate and play a voice."
            textSize = 15f; setPadding(0, 8, 0, 16)
        }, lp())
        root.addView(button("CHOOSE KOKORO MODEL") { chooseModel() }, lp())
        status = TextView(this).apply { text = "No model selected"; textSize = 16f; setPadding(0, 8, 0, 16) }
        root.addView(status, lp())
        textInput = EditText(this).apply {
            hint = "Enter text to test Kokoro"
            setText("Hello. This is a test of the Kokoro AI voice running completely on this phone.")
            minLines = 4; textSize = 18f
        }
        root.addView(textInput, lp())
        root.addView(button("GENERATE AND PLAY KOKORO VOICE") { generateAndPlay() }, lp())
        root.addView(TextView(this).apply {
            text = "Test goal only: offline synthesis and playback. Android TTS, reel timing, synchronization, music and export are unchanged."
            textSize = 14f; setPadding(0, 16, 0, 0)
        }, lp())
        setContentView(scroll)
    }

    private fun chooseModel() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQUEST_MODEL)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MODEL || resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        scope.launch(Dispatchers.IO) {
            try {
                val destination = File(getExternalFilesDir(null), "kokoro.onnx")
                contentResolver.openInputStream(uri)?.use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
                    ?: throw IllegalStateException("Could not open selected model")
                modelFile = destination; ready = false
                runOnUiThread { status.text = "Model copied. Initializing Kokoro..." }
                try { KokoroTTS.release() } catch (_: Exception) {}
                KokoroTTS.initialize(applicationContext, destination.absolutePath)
                ready = true
                runOnUiThread { status.text = "Kokoro is ready ✓ Tap Generate and Play."; toast("Kokoro model loaded") }
            } catch (e: Exception) {
                ready = false
                runOnUiThread { status.text = "Kokoro initialization failed: " + (e.message ?: "unknown error") }
            }
        }
    }

    private fun generateAndPlay() {
        if (!ready || modelFile == null) return toast("Choose and initialize the Kokoro model first")
        val text = textInput.text.toString().trim()
        if (text.isBlank()) return toast("Enter some text first")
        status.text = "Generating Kokoro voice..."
        scope.launch(Dispatchers.IO) {
            try {
                val result = KokoroTTS.speak(text)
                val output = File(cacheDir, "kokoro_test.wav")
                output.outputStream().use { it.write(result.audioData) }
                runOnUiThread { status.text = "Generated ✓ Playing..."; play(output) }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Kokoro generation failed: " + (e.message ?: "unknown error") }
            }
        }
    }

    private fun play(file: File) {
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { it.release(); player = null }
                prepare(); start()
            }
        } catch (e: Exception) { status.text = "Audio playback failed: " + (e.message ?: "unknown error") }
    }

    override fun onDestroy() {
        player?.release(); player = null
        scope.cancel()
        try { KokoroTTS.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; textSize = 16f; setOnClickListener { action() } }
    private fun lp() = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object { private const val REQUEST_MODEL = 200 }
}
