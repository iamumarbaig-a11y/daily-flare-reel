package com.thedailyflare.reel

import android.content.Context
import android.media.MediaMetadataRetriever
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import kotlin.concurrent.thread

class VoiceTts(context: Context) {
    data class VoiceOption(val name: String, val label: String, val sid: Int)

    companion object {
        // Single source of truth for the Kokoro voice/SID mapping. Both the
        // package test screen and the main reel screen must use these exact IDs.
        val AVAILABLE_VOICES = listOf(
            VoiceOption("Bella", "Bella", 1), VoiceOption("Sarah", "Sarah", 2),
            VoiceOption("Nicole", "Nicole", 3), VoiceOption("Sky", "Sky", 4),
            VoiceOption("Adam", "Adam", 5), VoiceOption("Michael", "Michael", 6),
            VoiceOption("Emma", "Emma", 7), VoiceOption("Isabella", "Isabella", 8),
            VoiceOption("George", "George", 9), VoiceOption("Lewis", "Lewis", 10)
        )
    }

    private val appContext = context.applicationContext
    private val voices = AVAILABLE_VOICES

    fun initialize(onReady: (List<VoiceOption>) -> Unit, onError: (String) -> Unit) {
        if (findPackageRoot(File(appContext.filesDir, "kokoro")) == null) {
            onError("Kokoro model package is not imported yet. Open the Kokoro package screen once to import the folder.")
        } else onReady(voices)
    }

    /**
     * Kokoro should narrate words, not the meanings of emoji (for example
     * 🇺🇦 -> "Ukraine" or 🔥 -> "fire").  Keep the original text untouched
     * for the UI/video and clean only the speech copy.
     *
     * Newlines are deliberately preserved here; they are not converted into
     * pauses or otherwise reformatted.
     */
    private fun cleanSpeechTextForKokoro(text: String): String {
        return text
            .replace(Regex("[\\p{So}\\p{Cn}]"), "")
            .replace(Regex("\\uFE0F|\\u200D"), "")
    }

    fun speakToFile(text: String, voice: VoiceOption?, output: File, speed: Float = 1.0f, onDone: (Boolean, Long) -> Unit) {
        val packageRoot = findPackageRoot(File(appContext.filesDir, "kokoro"))
        if (packageRoot == null || voice == null) { onDone(false, 0L); return }
        val speechText = cleanSpeechTextForKokoro(text)
        thread(name = "kokoro-voice-generation") {
            var engine: OfflineTts? = null
            try {
                output.parentFile?.mkdirs(); output.delete()
                engine = createEngine(packageRoot)
                val audio = engine.generate(text = speechText, sid = voice.sid, speed = speed)
                if (audio.samples.isEmpty()) throw IllegalStateException("Kokoro returned empty audio")
                audio.save(filename = output.absolutePath)
                if (!output.isFile || output.length() < 128L) throw IllegalStateException("Generated audio is empty")
                try { engine.release() } catch (_: Throwable) { }
                engine = null
                onDone(true, audioDuration(output))
            } catch (_: Throwable) {
                onDone(false, 0L)
            } finally {
                try { engine?.release() } catch (_: Throwable) { }
            }
        }
    }

    private fun createEngine(packageRoot: File): OfflineTts = OfflineTts(
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = File(packageRoot, "model.onnx").absolutePath,
                    voices = File(packageRoot, "voices.bin").absolutePath,
                    tokens = File(packageRoot, "tokens.txt").absolutePath,
                    dataDir = File(packageRoot, "espeak-ng-data").absolutePath
                ),
                numThreads = 1, debug = true, provider = "cpu"
            )
        )
    )

    private fun findPackageRoot(root: File): File? {
        if (!root.exists()) return null
        if (isCompatiblePackage(root)) return root
        return root.walkTopDown().filter { it.isDirectory }.firstOrNull { isCompatiblePackage(it) }
    }

    private fun isCompatiblePackage(dir: File): Boolean =
        File(dir, "model.onnx").isFile && File(dir, "voices.bin").isFile &&
        File(dir, "tokens.txt").isFile && File(dir, "espeak-ng-data").isDirectory

    private fun audioDuration(file: File): Long = try {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(file.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        }
    } catch (_: Exception) { 0L }

    fun shutdown() = Unit
}
