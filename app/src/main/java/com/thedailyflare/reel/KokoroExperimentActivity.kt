package com.thedailyflare.reel

import android.app.Activity
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import androidx.documentfile.provider.DocumentFile
import com.k2fsa.sherpa.onnx.*
import java.io.File
import java.io.FileOutputStream

class KokoroExperimentActivity : Activity() {
    companion object { private const val REQUEST_PACKAGE = 100 }

    private lateinit var status: TextView
    private lateinit var textInput: EditText
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

        root.addView(button("CHOOSE KOKORO MODEL PACKAGE") { choosePackage() }, lp())

        status = TextView(this).apply {
            text = "Select the complete kokoro-en-v0_19.tar.bz2 file."
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

        restoreImportedPackage()
    }

    private fun restoreImportedPackage() {
        val existing = findPackageRoot(File(filesDir, "kokoro"))
        if (existing != null) {
            modelDir = existing
            status.text = "Kokoro package is ready locally. Bella and Adam are available."
        }
    }

    private fun choosePackage() {
        // Return to the original workflow: select the already-extracted Kokoro
        // folder. Android grants access to the folder and we copy its exact
        // structure without unpacking or rewriting model files.
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        startActivityForResult(intent, REQUEST_PACKAGE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PACKAGE || resultCode != RESULT_OK || data?.data == null) return

        val uri = data.data!!
        status.text = "Importing Kokoro folder..."

        Thread {
            try {
                val grantedFlags = data.flags and
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                try {
                    contentResolver.takePersistableUriPermission(uri, grantedFlags)
                } catch (_: SecurityException) {
                    // Some file managers do not expose persistable permissions.
                    // The current grant is still enough for this one-time import.
                }

                val source = DocumentFile.fromTreeUri(this, uri)
                    ?: throw IllegalStateException("Android could not open the selected folder")

                val destination = File(filesDir, "kokoro")
                destination.deleteRecursively()
                if (!destination.mkdirs() && !destination.isDirectory) {
                    throw IllegalStateException("Cannot create local Kokoro storage")
                }

                copyDocumentTree(source, destination)

                val packageRoot = findPackageRoot(destination)
                    ?: throw IllegalStateException(
                        "Required files were not found. Select the extracted folder containing model.onnx, voices.bin, tokens.txt and espeak-ng-data."
                    )

                modelDir = packageRoot
                runOnUiThread {
                    status.text = "Kokoro folder imported successfully. Bella and Adam are ready."
                }
            } catch (e: Exception) {
                File(filesDir, "kokoro").deleteRecursively()
                runOnUiThread {
                    status.text = "Import error: " + (e.message ?: e.javaClass.simpleName)
                }
            }
        }.start()
    }

    private fun copyDocumentTree(source: DocumentFile, destination: File) {
        for (child in source.listFiles()) {
            val name = child.name?.takeIf { it.isNotBlank() } ?: continue
            val target = File(destination, name)
            if (child.isDirectory) {
                if (!target.exists() && !target.mkdirs()) {
                    throw IllegalStateException("Cannot create folder $name")
                }
                copyDocumentTree(child, target)
            } else if (child.isFile) {
                contentResolver.openInputStream(child.uri)?.use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Cannot read $name")
            }
        }
    }

    private fun createEngine(packageRoot: File): OfflineTts {
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = File(packageRoot, "model.onnx").absolutePath,
                    voices = File(packageRoot, "voices.bin").absolutePath,
                    tokens = File(packageRoot, "tokens.txt").absolutePath,
                    dataDir = File(packageRoot, "espeak-ng-data").absolutePath
                ),
                // Match the official Android/Kotlin Kokoro examples and keep
                // memory pressure lower on the phone.
                numThreads = 1,
                debug = true,
                provider = "cpu"
            )
        )
        return OfflineTts(config = config)
    }

    private fun findPackageRoot(root: File): File? {
        if (!root.exists()) return null
        if (isCompatiblePackage(root)) return root

        return root.walkTopDown()
            .filter { it.isDirectory }
            .firstOrNull { isCompatiblePackage(it) }
    }

    private fun isCompatiblePackage(dir: File): Boolean =
        File(dir, "model.onnx").isFile &&
        File(dir, "voices.bin").isFile &&
        File(dir, "tokens.txt").isFile &&
        File(dir, "espeak-ng-data").isDirectory

    private fun generate(sid: Int) {
        val packageRoot = modelDir ?: findPackageRoot(File(filesDir, "kokoro"))
        if (packageRoot == null) {
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
            var engine: OfflineTts? = null
            try {
                // Create, generate and release on this same thread.
                engine = createEngine(packageRoot)

                val config = GenerationConfig(
                    sid = sid,
                    speed = 1.0f,
                    silenceScale = 0.2f
                )
                val audio = engine.generateWithConfigAndCallback(
                    text = text,
                    config = config,
                    callback = { _: FloatArray -> 1 }
                )

                val output = File(cacheDir, "kokoro_$sid.wav")
                output.delete()
                val saved = audio.save(filename = output.absolutePath)
                if (!saved || !output.isFile || output.length() == 0L) {
                    throw IllegalStateException("Kokoro generated no playable audio")
                }

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
                    status.text = "Generation error: " + (e.message ?: e.javaClass.simpleName)
                }
            } finally {
                try {
                    engine?.release()
                } catch (_: Exception) {
                }
            }
        }.start()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun button(text: String, action: () -> Unit) =
        Button(this).apply {
            this.text = text
            setOnClickListener { action() }
        }

    private fun lp() = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun toast(s: String) =
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
