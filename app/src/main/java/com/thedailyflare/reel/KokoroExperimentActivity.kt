package com.thedailyflare.reel

import android.app.Activity
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import androidx.documentfile.provider.DocumentFile
import com.k2fsa.sherpa.onnx.*
import java.io.File
import java.io.FileOutputStream

class KokoroExperimentActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var textInput: EditText
    private var tts: OfflineTts? = null
    private var modelDir: File? = null
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        scroll.addView(root)
        root.addView(TextView(this).apply {
            text = "Kokoro AI Voice — Experimental"
            textSize = 26f
        }, lp())
        root.addView(button("CHOOSE KOKORO MODEL PACKAGE") { chooseFolder() }, lp())
        status = TextView(this).apply {
            text = "Choose a complete Sherpa-Kokoro package containing model.onnx, voices.bin, tokens.txt and espeak-ng-data."
            setPadding(0, 12, 0, 12)
        }
        root.addView(status, lp())
        textInput = EditText(this).apply {
            setText("Hello. This is a test of Kokoro running locally on this phone.")
            minLines = 4
        }
        root.addView(textInput, lp())
        root.addView(button("GENERATE BELLA") { generate(1) }, lp())
        root.addView(button("GENERATE ADAM") { generate(5) }, lp())
        root.addView(TextView(this).apply {
            text = "Experimental only. Your existing Android TTS and reel export are untouched."
        }, lp())
        setContentView(scroll)
    }

    private fun chooseFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, 100)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 100 || resultCode != RESULT_OK || data?.data == null) return

        val uri = data.data!!
        status.text = "Importing Kokoro package..."
        Thread {
            try {
                val flags = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, flags)
                val source = DocumentFile.fromTreeUri(this, uri)
                    ?: throw IllegalStateException("Unable to read selected folder")

                val destination = File(filesDir, "kokoro")
                destination.deleteRecursively()
                destination.mkdirs()
                copyTree(source, destination)

                val packageRoot = findPackageRoot(destination)
                if (packageRoot == null) {
                    val hasModel = findFile(destination, "model.onnx") != null
                    val hasIndividualVoices = destination.walkTopDown().any {
                        it.isFile && it.name.matches(Regex("^[ab][fm]_.+\\.bin$"))
                    }
                    val message = if (hasModel && hasIndividualVoices) {
                        "This is the ONNX Community package with separate voice files. Sherpa-Kokoro requires voices.bin plus tokens.txt and espeak-ng-data, so it cannot run this download directly."
                    } else {
                        "Package imported, but required files were not found: model.onnx, voices.bin, tokens.txt and espeak-ng-data."
                    }
                    runOnUiThread { status.text = message }
                    return@Thread
                }

                val config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        kokoro = OfflineTtsKokoroModelConfig(
                            model = File(packageRoot, "model.onnx").absolutePath,
                            voices = File(packageRoot, "voices.bin").absolutePath,
                            tokens = File(packageRoot, "tokens.txt").absolutePath,
                            dataDir = File(packageRoot, "espeak-ng-data").absolutePath
                        ),
                        numThreads = 2,
                        debug = false,
                        provider = "cpu"
                    )
                )

                tts?.release()
                tts = OfflineTts(config = config)
                modelDir = packageRoot
                runOnUiThread {
                    status.text = "Kokoro is ready locally. Choose Bella or Adam to generate and play audio."
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Import error: " + (e.message ?: "unknown error")
                }
            }
        }.start()
    }

    private fun copyTree(source: DocumentFile, destination: File) {
        source.listFiles().forEach { child ->
            val target = File(destination, child.name ?: "unnamed")
            if (child.isDirectory) {
                target.mkdirs()
                copyTree(child, target)
            } else if (child.isFile) {
                contentResolver.openInputStream(child.uri)?.use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Cannot read ${child.name}")
            }
        }
    }

    private fun findPackageRoot(root: File): File? {
        if (isCompatiblePackage(root)) return root
        return root.walkTopDown().firstOrNull { it.isDirectory && isCompatiblePackage(it) }
    }

    private fun isCompatiblePackage(dir: File): Boolean {
        return File(dir, "model.onnx").isFile &&
                File(dir, "voices.bin").isFile &&
                File(dir, "tokens.txt").isFile &&
                File(dir, "espeak-ng-data").isDirectory
    }

    private fun findFile(root: File, name: String): File? =
        root.walkTopDown().firstOrNull { it.isFile && it.name == name }

    private fun generate(sid: Int) {
        val engine = tts
        if (engine == null || modelDir == null) {
            toast("Import a compatible Kokoro package first")
            return
        }
        val text = textInput.text.toString().trim()
        if (text.isBlank()) {
            toast("Enter text first")
            return
        }

        status.text = "Generating voice locally..."
        Thread {
            try {
                val config = GenerationConfig(
                    sid = sid,
                    speed = 1.0f,
                    silenceScale = 0.2f
                )
                val audio = engine.generateWithConfigAndCallback(
                    text = text,
                    config = config,
                    callback = { 1 }
                )
                val output = File(cacheDir, "kokoro_${sid}.wav")
                output.delete()
                audio.save(filename = output.absolutePath)

                runOnUiThread {
                    player?.release()
                    player = MediaPlayer().apply {
                        setDataSource(output.absolutePath)
                        prepare()
                        start()
                    }
                    status.text = "Playing local Kokoro audio."
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Generation error: " + (e.message ?: "unknown error")
                }
            }
        }.start()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        tts?.release()
        tts = null
        super.onDestroy()
    }

    private fun button(text: String, action: () -> Unit) =
        Button(this).apply { this.text = text; setOnClickListener { action() } }

    private fun lp() = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
