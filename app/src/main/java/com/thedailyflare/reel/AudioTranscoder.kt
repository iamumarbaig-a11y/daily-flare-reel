package com.thedailyflare.reel

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

class AudioTranscoder(private val context: Context) {
    companion object { private const val MAX_US = 18_000_000L }

    fun transcode(uri: Uri, output: File): Boolean {
        return try {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)
            try {
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: return false
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
                if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                    copyAac(extractor, format, output)
                } else {
                    // Avoid fragile runtime software transcoding. Let Android's extractor provide AAC sources,
                    // while unsupported formats fail cleanly instead of producing a corrupt/empty MP4.
                    false
                }
            } finally { extractor.release() }
        } catch (_: Exception) { false }
    }

    private fun copyAac(extractor: MediaExtractor, format: MediaFormat, output: File): Boolean {
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        return try {
            val track = muxer.addTrack(format)
            muxer.start()
            val buffer = ByteBuffer.allocateDirect(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var wrote = false
            while (true) {
                val pts = extractor.sampleTime
                if (pts < 0L || pts >= MAX_US) break
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, pts, extractor.sampleFlags)
                muxer.writeSampleData(track, buffer, info)
                wrote = true
                extractor.advance()
            }
            muxer.stop()
            wrote
        } finally { muxer.release() }
    }
}
