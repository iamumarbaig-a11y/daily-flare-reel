package com.thedailyflare.reel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Decodes VP9/WebM CTA videos through FFmpeg/libvpx into RGBA PNG frames.
 * The decoded frames live in filesDir so the CTA remains usable after cache cleanup.
 */
object CtaAlphaDecoder {
    const val FRAME_RATE = 15f
    private const val MAX_DECODE_SECONDS = 45L

    fun decode(context: Context, uri: Uri): File? {
        val input = runCatching { copyToCache(context, uri) }.getOrNull() ?: return null
        val root = File(context.filesDir, "cta_alpha_frames")
        root.mkdirs()
        val dir = File(root, "${System.currentTimeMillis()}_${input.nameWithoutExtension}")
        if (!dir.mkdirs()) {
            input.delete()
            return null
        }

        val pattern = File(dir, "frame_%05d.png")
        val command = "-y -loglevel error -c:v libvpx-vp9 -i ${quote(input.absolutePath)} -map 0:v:0 -an -vf fps=${FRAME_RATE.toInt()},format=rgba ${quote(pattern.absolutePath)}"
        val completed = CountDownLatch(1)
        val sessionRef = AtomicReference<FFmpegSession?>(null)

        FFmpegKit.executeAsync(command, { session ->
            sessionRef.set(session)
            completed.countDown()
        })

        val finished = runCatching { completed.await(MAX_DECODE_SECONDS, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!finished) {
            sessionRef.get()?.let { runCatching { FFmpegKit.cancel(it.sessionId) } }
            input.delete()
            dir.deleteRecursively()
            return null
        }

        val session = sessionRef.get()
        input.delete()
        if (session == null || !ReturnCode.isSuccess(session.returnCode)) {
            dir.deleteRecursively()
            return null
        }

        val frames = dir.listFiles { f -> f.isFile && f.extension.equals("png", ignoreCase = true) }
        if (frames.isNullOrEmpty()) {
            dir.deleteRecursively()
            return null
        }

        val firstFile = frames.minByOrNull { it.name } ?: run {
            dir.deleteRecursively()
            return null
        }
        val first = BitmapFactory.decodeFile(firstFile.absolutePath) ?: run {
            dir.deleteRecursively()
            return null
        }
        val alpha = first.copy(Bitmap.Config.ARGB_8888, false)
        first.recycle()
        val pixels = IntArray(alpha.width * alpha.height)
        alpha.getPixels(pixels, 0, alpha.width, 0, 0, alpha.width, alpha.height)
        val hasTransparent = pixels.any { ((it ushr 24) and 0xff) < 250 }
        alpha.recycle()
        if (!hasTransparent) {
            dir.deleteRecursively()
            return null
        }

        return dir
    }

    private fun copyToCache(context: Context, uri: Uri): File {
        val dir = File(context.cacheDir, "cta_alpha_input").apply { mkdirs() }
        val file = File.createTempFile("cta_", ".webm", dir)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open CTA video" }
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
