package com.thedailyflare.reel

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

/**
 * Decodes WebM CTA videos through FFmpeg/libvpx into RGBA PNG frames.
 * This bypasses Android's native VP9/WebM alpha handling while leaving MP4 handling untouched.
 */
object CtaAlphaDecoder {
    const val FRAME_RATE = 15f

    fun decode(context: Context, uri: Uri): File? {
        val input = runCatching { copyToCache(context, uri) }.getOrNull() ?: return null
        val root = File(context.cacheDir, "cta_alpha_frames")
        root.mkdirs()
        val dir = File(root, "${System.currentTimeMillis()}_${input.nameWithoutExtension}")
        if (!dir.mkdirs()) {
            input.delete()
            return null
        }

        val pattern = File(dir, "frame_%05d.png")
        // Explicitly force libvpx-vp9 as the decoder. WebM alpha is carried as
        // Matroska BlockAdditional data and libvpx reconstructs the alpha plane.
        // format=rgba guarantees the PNG encoder receives a four-channel frame.
        val command = "-y -loglevel error -map 0:v:0 -c:v libvpx-vp9 -i ${quote(input.absolutePath)} -vf fps=${FRAME_RATE.toInt()},format=rgba ${quote(pattern.absolutePath)}"
        val session = FFmpegKit.execute(command)
        input.delete()

        if (!ReturnCode.isSuccess(session.returnCode)) {
            dir.deleteRecursively()
            return null
        }
        if (dir.listFiles { f -> f.isFile && f.extension.equals("png", ignoreCase = true) }.isNullOrEmpty()) {
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
