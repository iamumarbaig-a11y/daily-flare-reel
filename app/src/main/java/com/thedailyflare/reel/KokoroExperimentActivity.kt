package com.thedailyflare.reel

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.*
import com.k2fsa.sherpa.onnx.*
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
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
            text = "Select the complete kokoro-en-v0_19.tar.bz2 file. Do not select a folder or extract it first."
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
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/x-bzip2",
                "application/x-bzip",
                "application/octet-stream"
            ))
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
                val grantedFlags = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (grantedFlags != 0) {
                    try {
                        contentResolver.takePersistableUriPermission(uri, grantedFlags)
                    } catch (_: SecurityException) {
                    }
                }

                val destination = File(filesDir, "kokoro")
                destination.deleteRecursively()
                if (!destination.mkdirs() && !destination.isDirectory) {
                    throw IllegalStateException("Cannot create local Kokoro storage")
                }

                val importedEntries = extractTarBz2(uri, destination)
                if (importedEntries == 0) {
                    throw IllegalStateException("The selected file contained no readable TAR entries. Please select the original kokoro-en-v0_19.tar.bz2 file.")
                }

                val packageRoot = findPackageRoot(destination)
                    ?: throw IllegalStateException(
                        "Extraction finished, but the Kokoro package layout was not found. Extracted $importedEntries entries. Please use the original official kokoro-en-v0_19.tar.bz2 download."
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
                    status.text = "Kokoro is ready locally. Bella and Adam are now available."
                }
            } catch (e: Exception) {
                File(filesDir, "kokoro").deleteRecursively()
                runOnUiThread {
                    status.text = "Import error: " + (e.message ?: e.javaClass.simpleName)
                }
            }
        }.start()
    }

    private fun extractTarBz2(uri: Uri, destination: File): Int {
        contentResolver.openInputStream(uri)?.use { raw ->
            BufferedInputStream(raw).use { buffered ->
                // Verify bzip2 by its file signature instead of trusting the picker filename.
                buffered.mark(4)
                val b0 = buffered.read()
                val b1 = buffered.read()
                val b2 = buffered.read()
                buffered.reset()
                if (b0 != 'B'.code || b1 != 'Z'.code || b2 != 'h'.code) {
                    throw IllegalArgumentException(
                        "This is not a .tar.bz2 Kokoro archive. Select kokoro-en-v0_19.tar.bz2 itself, not a folder."
                    )
                }

                BZip2CompressorInputStream(buffered, true).use { bz2 ->
                    TarArchiveInputStream(bz2).use { tar ->
                        var entries = 0
                        while (true) {
                            val current: TarArchiveEntry = tar.nextTarEntry ?: break
                            entries++

                            val entryName = current.name
                                .replace('\\', '/')
                                .removePrefix("./")
                            if (entryName.isBlank()) continue

                            val target = safeTarget(destination, entryName)
                            when {
                                current.isDirectory -> {
                                    if (!target.exists() && !target.mkdirs()) {
                                        throw IllegalStateException("Cannot create $entryName")
                                    }
                                }
                                current.isFile -> {
                                    target.parentFile?.let { parent ->
                                        if (!parent.exists() && !parent.mkdirs()) {
                                            throw IllegalStateException("Cannot create folder for $entryName")
                                        }
                                    }
                                    FileOutputStream(target).use { output ->
                                        tar.copyTo(output)
                                    }
                                }
                            }
                        }
                        return entries
                    }
                }
            }
        } ?: throw IllegalStateException("Cannot read the selected package")
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
                    text = text,
                    config = config,
                    callback = { 1 }
                )
                val output = File(cacheDir, "kokoro_$sid.wav")
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
        Button(this).apply {
            this.text = text
            setOnClickListener { action() }
        }

    private fun lp() = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun toast(s: String) =
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
