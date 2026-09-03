package com.thedailyflare.reel

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

class AudioMuxer {
    companion object {

    }

    fun mux(video: File, audio: File, output: File): Boolean {
        val videoExtractor = MediaExtractor().apply { setDataSource(video.absolutePath) }
        val audioExtractor = MediaExtractor().apply { setDataSource(audio.absolutePath) }
        val videoTrack = (0 until videoExtractor.trackCount).firstOrNull {
            videoExtractor.getTrackFormat(it).getString("mime")?.startsWith("video/") == true
        } ?: run {
            videoExtractor.release()
            audioExtractor.release()
            return false
        }
        val audioTrack = (0 until audioExtractor.trackCount).firstOrNull {
            audioExtractor.getTrackFormat(it).getString("mime")?.startsWith("audio/") == true
        } ?: run {
            videoExtractor.release()
            audioExtractor.release()
            return false
        }

        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val outVideo = muxer.addTrack(videoExtractor.getTrackFormat(videoTrack))
        val outAudio = muxer.addTrack(audioExtractor.getTrackFormat(audioTrack))
        muxer.start()
        val buffer = ByteBuffer.allocateDirect(1024 * 1024)

        fun copy(extractor: MediaExtractor, track: Int, destination: Int) {
            extractor.selectTrack(track)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val sampleTime = extractor.sampleTime
                if (sampleTime < 0L) break
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, sampleTime, extractor.sampleFlags)
                muxer.writeSampleData(destination, buffer, info)
                extractor.advance()
            }
        }

        try {
            copy(videoExtractor, videoTrack, outVideo, limitDuration = true)
            copy(audioExtractor, audioTrack, outAudio, limitDuration = true)
            muxer.stop()
            return true
        } finally {
            muxer.release()
            videoExtractor.release()
            audioExtractor.release()
        }
    }
}
