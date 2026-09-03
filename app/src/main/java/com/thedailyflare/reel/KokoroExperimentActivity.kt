package com.thedailyflare.reel

import android.app.Activity
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import com.k2fsa.sherpa.onnx.*
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream

class KokoroExperimentActivity : Activity() {
    companion object { private const val REQUEST_PACKAGE = 100 }

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
        root.addView(button("CHOOSE KOKORO MODEL PACKAGE") { choosePackage() }, lp())
        status = TextView(this).apply {
            text = "Choose the complete Kokoro package (.tar.bz2). The app will extract and import it locally."
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

    private fun choosePackage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_PACKAGE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PACKAGE || resultCode != RESULT_OK || data?.data == null) return

        val uri = data.data!!
        status.text = "Importing Kokoro package..."

        Thread {
            try {
                val flags = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (flags != 0) {
                    try { contentResolver.takePersistableUriPermission(uri, flags) }
                    catch (_: SecurityException) { }
                }

                val destination = File(filesDir, "kokoro")
                destination.deleteRecursively()
                if (!destination.mkdirs() && !destination.isDirectory) {
                    throw IllegalStateException("Cannot create local Kokoro storage")
                }

                importPackage(uri, destination)

                val packageRoot = findPackageRoot(destination)
                    ?: throw IllegalStateException(
                        "Import completed, but model.onnx, voices.bin, tokens.txt and espeak-ng-data were not found."
                    )

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
                File(filesDir, "kokoro").deleteRecursively()
                runOnUiThread {
                    status.text = "Import error: " + (e.message ?: e.javaClass.simpleName)
                }
            }
        }.start()
    }

    private fun importPackage(uri: Uri, destination: File) {
        val name = queryDisplayName(uri)?.lowercase().orEmpty()
        if (name.endsWith(".tar.bz2") || name.endsWith(".tbz2") || name.endsWith(".tbz")) {
            extractTarBz2(uri, destination)
        } else {
            throw IllegalArgumentException(
                "Please select the downloaded Kokoro .tar.bz2 package, not a folder or individual model file."
            )
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME))
            }
        }
        return null
    }

    private fun extractTarBz2(uri: Uri, destination: File) {
        contentResolver.openInputStream(uri)?.use { raw ->
            BZip2CompressorInputStream(raw).use { bz2 ->
                TarArchiveInputStream(bz2).use { tar ->
                    while (true) {
                        val current = tar.nextEntry as? TarArchiveEntry ?: break
                        val target = safeTarget(destination, current.name)
                        when {
                            current.isDirectory -> {
                                if (!target.exists() && !target.mkdirs()) {
                                    throw IllegalStateException("Cannot create ${current.name}")
                                }
                            }
                            current.isFile -> {
                                target.parentFile?.let {
                                    if (!it.exists() && !it.mkdirs()) {
                                        throw IllegalStateException("Cannot create folder for ${current.name}")
                                    }
                                }
                                FileOutputStream(target).use { output -> tar.copyTo(output) }
                            }
                        }
                    }
                }
            }
        } ?: throw IllegalStateException("Cannot read selected package")
    }

    private fun safeTarget(destination: File, entryName: String): File {
        val target = File(destination, entryName)
        val rootPath = destination.canonicalFile
        val targetPath = target.canonicalFile
        if (targetPath != rootPath && !targetPath.path.startsWith(rootPath.path + File.separator)) {
            throw SecurityException("Unsafe archive entry: $entryName")
        }
        return targetPath
    }

    private fun findPackageRoot(root: File): File? {
        if (isCompatiblePackage(root)) return root
        return root.walkTopDown().firstOrNull { it.isDirectory && isCompatiblePackage(it) }
    }

    private fun isCompatiblePackage(dir: File): Boolean =
        File(dir, "model.onnx").isFile &&
        File(dir, "voices.bin").isFile &&
        File(dir, "tokens.txt").isFile &&
        File(dir, "espeak-ng-data").isDirectory

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
                val config = GenerationConfig(sid = sid, speed = 1.0f, silenceScale = 0.2f)
                val audio = engine.generateWithConfigAndCallback(
                    text = text, config = config, callback = { 1 }
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
                    status.text = "Generation error: " + (e.message ?: e.javaClass.simpleName)
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
