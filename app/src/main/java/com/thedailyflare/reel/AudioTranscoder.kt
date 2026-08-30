package com.thedailyflare.reel

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/** Audio preparation helper reconstructed from the APK's audio pipeline. */
class AudioTranscoder(private val context: Context) {
    fun transcode(uri: Uri, output: File): Boolean {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return false
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
        if (mime != "audio/mp4a-latm") {
            extractor.release()
            return false
        }
        return copyToMp4(extractor, format, output)
    }

    private fun copyToMp4(extractor: MediaExtractor, format: MediaFormat, output: File): Boolean {
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val track = muxer.addTrack(format)
        muxer.start()
        val buffer = ByteBuffer.allocateDirect(1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
            muxer.writeSampleData(track, buffer, info)
            extractor.advance()
        }
        muxer.stop()
        muxer.release()
        extractor.release()
        return true
    }
}
