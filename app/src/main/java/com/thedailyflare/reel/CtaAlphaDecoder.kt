package com.thedailyflare.reel

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

/**
 * Android's MediaMetadataRetriever/MediaCodec path does not reliably expose VP9 WebM alpha.
 * This isolated importer decodes only WebM files that advertise AlphaMode=1 into RGBA PNG
 * frames. Existing MP4/opaque-video handling remains unchanged.
 */
object CtaAlphaDecoder {
    const val FRAME_RATE = 15f

    fun hasAlpha(context: Context, uri: Uri): Boolean {
        return runCatching {
            val input = copyToCache(context, uri)
            val output = FFprobeKit.execute("-v error -select_streams v:0 -show_entries stream_tags=alpha_mode -of default=nw=1:nk=1 ${quote(input.absolutePath)}").output.orEmpty().trim()
            input.delete()
            output == "1"
        }.getOrDefault(false)
    }

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
        val command = "-y -c:v libvpx-vp9 -i ${quote(input.absolutePath)} -vf fps=${FRAME_RATE.toInt()} -pix_fmt rgba ${quote(pattern.absolutePath)}"
        val session = FFmpegKit.execute(command)
        input.delete()
        if (!ReturnCode.isSuccess(session.returnCode)) {
            dir.deleteRecursively()
            return null
        }
        if (dir.listFiles { f -> f.extension == "png" }.isNullOrEmpty()) {
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
