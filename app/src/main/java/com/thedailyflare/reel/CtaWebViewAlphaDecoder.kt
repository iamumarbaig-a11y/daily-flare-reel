package com.thedailyflare.reel

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Decodes VP9 alpha WebM through the Chromium engine already present in Android WebView.
 *
 * This deliberately does not use FFmpegKit or MediaMetadataRetriever. Chromium supports
 * WebM alpha as a composited video source; drawing that video into a canvas preserves the
 * transparent pixels, which we persist as ordinary RGBA PNG frames for the existing CTA
 * renderer/export pipeline.
 */
object CtaWebViewAlphaDecoder {
    const val FRAME_RATE = 15f
    private const val MAX_SECONDS = 90L

    data class Result(val frameDir: File, val durationMs: Long)

    fun decode(activity: Activity, uri: android.net.Uri): Result? {
        val input = runCatching {
            activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null

        val root = File(activity.filesDir, "cta_alpha_frames")
        root.mkdirs()
        val dir = File(root, "${System.currentTimeMillis()}_webview")
        if (!dir.mkdirs()) return null

        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<Result?>(null)
        val errorRef = AtomicReference<String?>(null)
        val main = Handler(Looper.getMainLooper())

        main.post {
            val webView = WebView(activity)
            webView.layoutParams = ViewGroup.LayoutParams(2, 2)
            webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            webView.alpha = 0.01f
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.mediaPlaybackRequiresUserGesture = false
            webView.settings.allowFileAccess = false
            webView.settings.allowContentAccess = false
            webView.addJavascriptInterface(object {
                @JavascriptInterface
                fun ready(width: Int, height: Int, durationMs: Long, frameCount: Int) {
                    // Metadata is delivered before frame extraction starts.
                }

                @JavascriptInterface
                fun frame(index: Int, base64Png: String) {
                    try {
                        val bytes = Base64.decode(base64Png, Base64.DEFAULT)
                        FileOutputStream(File(dir, "frame_%05d.png".format(index))).use { it.write(bytes) }
                    } catch (t: Throwable) {
                        errorRef.compareAndSet(null, t.message ?: "frame write failed")
                    }
                }

                @JavascriptInterface
                fun complete(durationMs: Long) {
                    val frames = dir.listFiles { f -> f.isFile && f.extension.equals("png", true) } ?: emptyArray()
                    if (frames.isEmpty() || errorRef.get() != null) {
                        dir.deleteRecursively()
                    } else {
                        resultRef.set(Result(dir, durationMs.coerceAtLeast(1L)))
                    }
                    latch.countDown()
                    main.post { cleanup(activity, webView) }
                }

                @JavascriptInterface
                fun failed(message: String) {
                    errorRef.compareAndSet(null, message)
                    dir.deleteRecursively()
                    latch.countDown()
                    main.post { cleanup(activity, webView) }
                }
            }, "AndroidDecoder")

            val parent = activity.findViewById<ViewGroup>(android.R.id.content)
            parent.addView(webView)

            val encoded = Base64.encodeToString(input, Base64.NO_WRAP)
            val html = buildHtml(encoded)
            webView.loadDataWithBaseURL("https://daily-flare.local/", html, "text/html", "UTF-8", null)
        }

        val finished = runCatching { latch.await(MAX_SECONDS, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!finished) {
            main.post {
                resultRef.get()?.frameDir?.deleteRecursively()
            }
            return null
        }
        return resultRef.get()
    }

    private fun cleanup(activity: Activity, webView: WebView) {
        runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
        runCatching { webView.stopLoading(); webView.destroy() }
    }

    private fun buildHtml(base64: String): String = """
        <!doctype html>
        <html><body style='margin:0;background:transparent;overflow:hidden'>
        <video id='v' playsinline muted preload='auto' style='position:absolute;left:-10px;top:-10px;width:2px;height:2px'></video>
        <canvas id='c' style='display:none'></canvas>
        <script>
        (async function() {
          const v = document.getElementById('v');
          const c = document.getElementById('c');
          const ctx = c.getContext('2d', {alpha:true, willReadFrequently:false});
          const fps = 15;
          let finished = false;
          function fail(e) {
            if (finished) return;
            finished = true;
            AndroidDecoder.failed(String(e && e.message ? e.message : e));
          }
          try {
            v.src = 'data:video/webm;base64,$base64';
            await new Promise((resolve, reject) => {
              v.onloadedmetadata = resolve;
              v.onerror = () => reject(new Error('Chromium could not decode WebM'));
            });
            const width = v.videoWidth;
            const height = v.videoHeight;
            const duration = v.duration;
            if (!width || !height || !isFinite(duration) || duration <= 0) throw new Error('Invalid WebM metadata');
            c.width = width;
            c.height = height;
            AndroidDecoder.ready(width, height, Math.round(duration * 1000), Math.ceil(duration * fps));
            await v.play();
            v.pause();
            const frames = Math.max(1, Math.ceil(duration * fps));
            for (let i = 0; i < frames; i++) {
              const t = Math.min(Math.max(0, duration - 0.001), i / fps);
              await new Promise((resolve, reject) => {
                let done = false;
                const finish = () => { if (!done) { done = true; resolve(); } };
                const timer = setTimeout(() => { if (!done) { done = true; reject(new Error('WebM seek timeout')); } }, 5000);
                v.onseeked = () => { clearTimeout(timer); finish(); };
                try { v.currentTime = t; } catch (e) { clearTimeout(timer); reject(e); }
              });
              ctx.clearRect(0, 0, width, height);
              ctx.drawImage(v, 0, 0, width, height);
              const png = c.toDataURL('image/png').split(',')[1];
              AndroidDecoder.frame(i, png);
            }
            finished = true;
            AndroidDecoder.complete(Math.round(duration * 1000));
          } catch (e) { fail(e); }
        })();
        </script></body></html>
    """.trimIndent()
}
