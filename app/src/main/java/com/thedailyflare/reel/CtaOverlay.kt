package com.thedailyflare.reel

import android.content.Context
import android.net.Uri

data class CtaOverlay(
    val uri: Uri,
    val startMs: Long,
    val durationMs: Long,
    val x: Float,
    val y: Float,
    val scale: Float
)

object CtaOverlayStore {
    private const val PREFS = "daily_flare_reel_preferences"
    private const val KEY = "cta_video_overlays_v1"
    private const val SEP = "|"

    fun load(context: Context): List<CtaOverlay> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val p = line.split(SEP)
            if (p.size != 6) return@mapNotNull null
            runCatching {
                CtaOverlay(
                    uri = Uri.parse(p[0]),
                    startMs = p[1].toLong().coerceAtLeast(0L),
                    durationMs = p[2].toLong().coerceAtLeast(1L),
                    x = p[3].toFloat().coerceIn(0f, 1f),
                    y = p[4].toFloat().coerceIn(0f, 1f),
                    scale = p[5].toFloat().coerceIn(0.03f, 1f)
                )
            }.getOrNull()
        }.toList()
    }

    fun save(context: Context, overlays: List<CtaOverlay>) {
        val raw = overlays.joinToString("\n") { o ->
            listOf(o.uri.toString(), o.startMs, o.durationMs, o.x, o.y, o.scale).joinToString(SEP)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, raw).apply()
    }
}
