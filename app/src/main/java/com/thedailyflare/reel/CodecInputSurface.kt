package com.thedailyflare.reel

import android.opengl.EGL14
import android.opengl.EGLExt
import android.view.Surface

/** EGL wrapper for MediaCodec input surfaces with explicit frame timestamps. */
class CodecInputSurface(private val surface: Surface) {
    private val display: android.opengl.EGLDisplay
    private val context: android.opengl.EGLContext
    private val eglSurface: android.opengl.EGLSurface

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "Unable to get EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "Unable to initialize EGL" }
        val attribs = intArrayOf(EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,0x3142,1,EGL14.EGL_NONE)
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1); val num = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs,0,configs,0,1,num,0) && num[0]>0) { "No EGL config" }
        context = EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
        check(context != EGL14.EGL_NO_CONTEXT) { "Unable to create EGL context" }
        eglSurface = EGL14.eglCreateWindowSurface(display,configs[0],surface,intArrayOf(EGL14.EGL_NONE),0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "Unable to create EGL window surface" }
    }
    fun createCanvasBitmap(width: Int, height: Int): android.graphics.Bitmap =
        android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)

    fun postBitmap(bitmap: android.graphics.Bitmap, presentationTimeNs: Long) {
        // CPU Canvas rendering is preserved while the EGL surface owns deterministic timestamps.
        val c = surface.lockCanvas(null)
        try { c.drawBitmap(bitmap, 0f, 0f, null) } finally { surface.unlockCanvasAndPost(c) }
        setPresentationTime(presentationTimeNs)
    }

    fun makeCurrent() { check(EGL14.eglMakeCurrent(display,eglSurface,eglSurface,context)) { "Unable to make EGL current" } }
    fun setPresentationTime(nsecs: Long) { EGLExt.eglPresentationTimeANDROID(display,eglSurface,nsecs) }
    fun swapBuffers() { check(EGL14.eglSwapBuffers(display,eglSurface)) { "eglSwapBuffers failed" } }
    fun release() {
        EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display,eglSurface); EGL14.eglDestroyContext(display,context)
        EGL14.eglReleaseThread(); EGL14.eglTerminate(display); surface.release()
    }
}