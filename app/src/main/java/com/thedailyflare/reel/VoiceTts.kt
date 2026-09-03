package com.thedailyflare.reel

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.io.File

class VoiceTts(context: Context) {
    data class VoiceOption(val name: String, val label: String)
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var voices: List<VoiceOption> = emptyList()

    fun initialize(onReady: (List<VoiceOption>) -> Unit, onError: (String) -> Unit) {
        tts = TextToSpeech(appContext) { status ->
            if (status != TextToSpeech.SUCCESS) { onError("Android Text-to-Speech is unavailable"); return@TextToSpeech }
            ready = true
            voices = tts?.voices?.filter { it.locale.language == "en" }?.sortedBy { it.name }?.map {
                VoiceOption(it.name, it.locale.displayName + " — " + it.name)
            } ?: emptyList()
            onReady(voices)
        }
    }

    fun speakToFile(text: String, voiceName: String?, output: File, onDone: (Boolean, Long) -> Unit) {
        val engine = tts
        if (!ready || engine == null) { onDone(false, 0L); return }
        if (!voiceName.isNullOrBlank()) engine.voices?.firstOrNull { it.name == voiceName }?.let { engine.voice = it }
        output.delete()
        val id = "daily_flare_voice_" + System.nanoTime()
        engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) { if (utteranceId == id) onDone(output.exists() && output.length() > 0L, audioDuration(output)) }
            override fun onError(utteranceId: String) { if (utteranceId == id) onDone(false, 0L) }
        })
        if (engine.synthesizeToFile(text, Bundle(), output, id) == TextToSpeech.ERROR) onDone(false, 0L)
    }

    private fun audioDuration(file: File): Long = try {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(file.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        }
    } catch (_: Exception) { 0L }

    fun shutdown() { tts?.stop(); tts?.shutdown(); tts = null; ready = false }
}