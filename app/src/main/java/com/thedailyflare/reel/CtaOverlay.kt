package com.thedailyflare.reel

import android.content.Context
import android.net.Uri

data class CtaOverlay(
    val uri: Uri,
    val startMs: Long,
    val durationMs: Long,
    val x: Float,
    val y: Float,
    val scale: Float,
    val frameDir: String? = null,
    val frameRate: Float = 15f
)

object CtaOverlayStore {
    private const val PREFS = "daily_flare_reel_preferences"
    private const val KEY = "cta_video_overlays_v1"
    private const val KEY_SELECTED = "cta_video_selected_index_v1"
    private const val SEP = "|"

    fun load(context: Context): List<CtaOverlay> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val p = line.split(SEP)
            if (p.size !in 6..8) return@mapNotNull null
            runCatching {
                CtaOverlay(
                    uri = Uri.parse(p[0]),
                    startMs = p[1].toLong().coerceAtLeast(0L),
                    durationMs = p[2].toLong().coerceAtLeast(1L),
                    x = p[3].toFloat().coerceIn(0f, 1f),
                    y = p[4].toFloat().coerceIn(0f, 1f),
                    scale = p[5].toFloat().coerceIn(0.03f, 1f),
                    frameDir = p.getOrNull(6)?.takeIf { it.isNotBlank() },
                    frameRate = p.getOrNull(7)?.toFloatOrNull()?.coerceIn(1f, 60f) ?: 15f
                )
            }.getOrNull()
        }.toList()
    }

    fun save(context: Context, overlays: List<CtaOverlay>) {
        val raw = overlays.joinToString("\n") { o ->
            listOf(o.uri.toString(), o.startMs, o.durationMs, o.x, o.y, o.scale, o.frameDir.orEmpty(), o.frameRate).joinToString(SEP)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, raw).apply()
    }

    fun loadSelectedIndex(context: Context, size: Int): Int {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SELECTED, 0)
        return saved.coerceIn(0, (size - 1).coerceAtLeast(0))
    }

    fun saveSelectedIndex(context: Context, index: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_SELECTED, index.coerceAtLeast(0)).apply()
    }
}
