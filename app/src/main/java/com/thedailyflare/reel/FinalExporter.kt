package com.thedailyflare.reel

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Combines the already-rendered 18s video with the selected music.
 * Media3 handles AAC conversion and MP4 muxing instead of manually muxing
 * the complete video track followed by the complete audio track.
 */
class FinalExporter(private val context: Context) {
    data class Result(val success: Boolean, val error: String? = null)

    fun export(video: File, musicUri: Uri, output: File): Result {
        val done = CountDownLatch(1)
        val error = AtomicReference<String?>(null)
        val success = AtomicReference(false)

        val videoItem = EditedMediaItem.Builder(
            MediaItem.fromUri(video.toURI().toString())
        ).build()

        val audioItem = EditedMediaItem.Builder(
            MediaItem.fromUri(musicUri)
        ).build()

        val videoSequence = EditedMediaItemSequence.withVideoFrom(listOf(videoItem))
        val audioSequence = EditedMediaItemSequence.withAudioFrom(listOf(audioItem))
            .buildUpon()
            .setIsLooping(true)
            .build()

        val composition = Composition.Builder(videoSequence, audioSequence).build()

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, result: ExportResult) {
                success.set(output.exists() && output.length() > 0L)
                done.countDown()
            }

            override fun onError(
                composition: Composition,
                result: ExportResult,
                exportException: ExportException
            ) {
                error.set(
                    exportException.message
                        ?: exportException.cause?.message
                        ?: "Media3 export error code ${exportException.errorCode}"
                )
                done.countDown()
            }
        }

        val transformer = Transformer.Builder(context)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(listener)
            .build()

        output.delete()
        try {
            // Transformer is accessed from the main application thread.
            android.os.Handler(context.mainLooper).post {
                try {
                    transformer.start(composition, output.absolutePath)
                } catch (e: Exception) {
                    error.set(e.message ?: e.javaClass.simpleName)
                    done.countDown()
                }
            }

            if (!done.await(5, TimeUnit.MINUTES)) {
                return Result(false, "Export timed out after 5 minutes")
            }
            return Result(success.get(), error.get())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return Result(false, "Export interrupted")
        }
    }
}
