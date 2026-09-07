package com.thedailyflare.reel

import android.app.Activity
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.view.Gravity
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
    private lateinit var voiceSpinner: Spinner
    private lateinit var speedSpinner: Spinner
    // Use the exact same voice registry as the main reel screen.
    private val voices = VoiceTts.AVAILABLE_VOICES
    private val speeds = listOf(0.75f, 0.9f, 1.0f, 1.1f, 1.25f, 1.5f)
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply { setBackgroundColor(0xFFF6F7F8.toInt()) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "Kokoro Package"
            textSize = 30f
            typeface = Typeface.create("sans", Typeface.BOLD)
            setTextColor(0xFF172A3A.toInt())
        }, lp())

        root.addView(TextView(this).apply {
            text = "Import and manage the local Kokoro voice model"
            textSize = 15f
            setTextColor(0xFF5D646B.toInt())
            setPadding(0, dp(4), 0, dp(18))
        }, lp())

        root.addView(card().apply {
            addView(TextView(this@KokoroExperimentActivity).apply {
                text = "MODEL PACKAGE"
                textSize = 13f
                typeface = Typeface.create("sans", Typeface.BOLD)
                setTextColor(0xFF5D646B.toInt())
            }, lp())
            addView(button("CHOOSE PACKAGE") { choosePackage() }, lp())
        }, lp())

        status = TextView(this).apply {
            text = "Select your extracted Kokoro model folder."
            textSize = 15f
            setTextColor(0xFF5D646B.toInt())
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(0xFFFFFFFF.toInt(), 16)
        }
        root.addView(status, lp())

        root.addView(sectionLabel("VOICE TEST"), lp())
        textInput = EditText(this).apply {
            setText("Hello. This is a test of Kokoro running locally on this phone.")
            minLines = 4
            textSize = 16f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(0xFFFFFFFF.toInt(), 16)
        }
        root.addView(textInput, lp())

        voiceSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@KokoroExperimentActivity, android.R.layout.simple_spinner_dropdown_item, voices.map { it.label })
            background = rounded(0xFFFFFFFF.toInt(), 14)
        }
        speedSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@KokoroExperimentActivity, android.R.layout.simple_spinner_dropdown_item, speeds.map { "${it}×" })
            setSelection(speeds.indexOf(1.0f))
            background = rounded(0xFFFFFFFF.toInt(), 14)
        }
        root.addView(twoColumnRow("VOICE" to voiceSpinner, "SPEED" to speedSpinner), lp())
        root.addView(button("GENERATE VOICE") { generate(voices[voiceSpinner.selectedItemPosition].sid, speeds[speedSpinner.selectedItemPosition]) }, lp())

        root.addView(TextView(this).apply {
            text = "The imported package is shared with Daily Flare Reel and remains available for voice generation and export."
            textSize = 14f
            setTextColor(0xFF5D646B.toInt())
            setPadding(dp(4), dp(12), dp(4), 0)
        }, lp())

        setContentView(scroll)
        restoreImportedPackage()
    }

    private fun restoreImportedPackage() {
        val existing = findPackageRoot(File(filesDir, "kokoro"))
        if (existing != null) {
            modelDir = existing
            status.text = "Kokoro package is ready locally. Select a voice and speed."
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
                    status.text = "Kokoro folder imported successfully. Select a voice and speed."
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

    private fun generate(sid: Int, speed: Float = 1.0f) {
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

        // Keep the working folder/persistence code completely untouched.
        // This test isolates native engine creation, synthesis, WAV writing,
        // and playback so we can see exactly which stage fails.
        status.text = "Checking Kokoro model..."
        Thread {
            var engine: OfflineTts? = null
            try {
                mainHandler.post { status.text = "Loading Kokoro model..." }
                engine = createEngine(packageRoot)

                mainHandler.post { status.text = "Generating voice locally..." }

                // Use the simplest generation API. The previous callback path
                // enters JNI repeatedly and is unnecessary for a first stability test.
                val audio = engine.generate(
                    text = text,
                    sid = sid,
                    speed = speed
                )

                if (audio.samples.isEmpty()) {
                    throw IllegalStateException("Kokoro returned empty audio")
                }

                mainHandler.post { status.text = "Saving generated audio..." }
                val output = File(cacheDir, "kokoro_$sid.wav")
                output.delete()

                audio.save(filename = output.absolutePath)

                if (!output.isFile || output.length() < 128L) {
                    throw IllegalStateException("Generated WAV file is empty")
                }

                // Release the native engine before Android playback starts.
                try {
                    engine.release()
                } catch (_: Throwable) {
                }
                engine = null

                mainHandler.post {
                    try {
                        status.text = "Playing local Kokoro audio..."
                        player?.release()
                        player = MediaPlayer().apply {
                            setDataSource(output.absolutePath)
                            setOnCompletionListener {
                                status.text = "Kokoro generation completed successfully."
                            }
                            prepare()
                            start()
                        }
                    } catch (e: Exception) {
                        status.text = "Audio playback error: " +
                            (e.message ?: e.javaClass.simpleName)
                    }
                }
            } catch (t: Throwable) {
                // Java exceptions are caught here. If Android still exits to
                // home, the remaining fault is inside native JNI/ONNX code.
                mainHandler.post {
                    status.text = "Generation error: " +
                        (t.message ?: t.javaClass.simpleName)
                }
            } finally {
                try {
                    engine?.release()
                } catch (_: Throwable) {
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
            textSize = 15f
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.create("sans", Typeface.BOLD)
            minHeight = dp(52)
            background = rounded(0xFF172A3A.toInt(), 18)
            elevation = dp(3).toFloat()
            setOnClickListener { action() }
        }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        typeface = Typeface.create("sans", Typeface.BOLD)
        setTextColor(0xFF5D646B.toInt())
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun twoColumnRow(left: Pair<String, View>, right: Pair<String, View>) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            fun column(item: Pair<String, View>) = LinearLayout(this@KokoroExperimentActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@KokoroExperimentActivity).apply {
                    text = item.first
                    textSize = 12f
                    typeface = Typeface.create("sans", Typeface.BOLD)
                    setTextColor(0xFF5D646B.toInt())
                    setPadding(0, 0, 0, dp(4))
                })
                addView(item.second, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            addView(column(left), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
            addView(column(right), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = rounded(0xFFFFFFFF.toInt(), 18)
        elevation = dp(2).toFloat()
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun lp() = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(5)
        bottomMargin = dp(5)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun toast(s: String) =
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()

}